package com.maxleveldetox.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.overlay.EnforcementWall

/**
 * ShadeGuard v2 (v2.5 r9) — notification-shade neutralization, Social
 * Sentry parity.
 *
 * r7 gap (user-reported): the shade could still be pulled down during
 * enforcement. Causes: (1) the guard only recognized a short allowlist of
 * shade class-name fragments — many OEM ROMs (MIUI/ColorOS/HiOS/XOS
 * common in Bangladesh) report different class names, so the event was
 * ignored; (2) it only ran while a session was ENFORCING or Monk was
 * active — schedules, app limits, cage, Lock My Phone and Prime walls
 * got no shade protection; (3) 1 s throttle let the shade sit open.
 *
 * v2 behavior — ANY window-state change owned by com.android.systemui
 * while ANY enforcement surface is live is treated as the shade unless
 * it is explicitly on the denylist (recents / power dialog / IME /
 * volume / clipboard / screenshot). Response is GLOBAL_ACTION_BACK (the
 * documented way an a11y service collapses the open shade) within 350 ms.
 *
 * Defense in depth: the EnforcementWall a11y overlay physically covers
 * the status-bar region, so most shade swipes never reach SystemUI at
 * all — this guard is the second layer (it also protects the launcher /
 * allowed-app surfaces where no wall is up).
 *
 * SAFETY BOUNDS (learned from the r5/r6 regression — this guard must
 * NEVER fight the user outside enforcement):
 *   1. Only events whose package is com.android.systemui.
 *   2. Never the recents/global-actions(power)/IME/volume surfaces.
 *   3. Only while an enforcement surface is live.
 *   4. NEVER while a settings grace window or the emergency window is
 *      open (permission restore + real calls must stay reachable).
 *   5. Own package ignored.
 */
object ShadeGuard {

    /** Explicitly NOT the shade — never BACK these. */
    private val NOT_SHADE_HINTS = listOf(
        "recents", "globalactions", "clipboard", "ime", "inputmethod",
        "volume", "screenshot", "imagewire", "bubbles", "wallet",
        "mediacontrol", "workspaces", "keyguard",
    )

    private var lastBackAt = 0L

    fun maybeCollapse(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false

        val pkg = event.packageName?.toString() ?: return false
        if (pkg != "com.android.systemui") return false

        // Empty class names are treated as shade candidates too (some OEMs
        // fire bare events) — the denylist below still protects the
        // non-shade surfaces that identify themselves.
        val cls = (event.className?.toString() ?: "").lowercase()
        if (NOT_SHADE_HINTS.any { cls.contains(it) }) return false

        val app = service.application as? MldApp ?: return false

        // BOUND 3: any live enforcement surface.
        if (!enforcementSurfaceLive(app)) return false

        // BOUND 4: never fight the restore window / emergency window.
        val grace = app.stateRepo.blockingGraceWindow()
        if (grace != null && grace.expiresElapsed > SystemClockNow.elapsed) return false
        if (EnforcementWall.inEmergencyWindow()) return false

        // Throttle: Social-Sentry-grade response time.
        val now = SystemClockNow.elapsed
        if (now - lastBackAt < 350L) return false
        lastBackAt = now

        DiagLog.log("SHADE_GUARD", "collapsing shade (cls=$cls)")
        return try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Live enforcement surfaces (all of them — the r7 version checked only
     * the session + monk):
     *   - blocking session ENFORCING
     *   - cage active
     *   - Monk Mode active
     *   - Lock My Phone session active
     *   - Prime Commit enforcement window active
     *   - the EnforcementWall overlay itself is on screen (covers
     *     schedule / app-limit / shorts walls)
     */
    private fun enforcementSurfaceLive(app: MldApp): Boolean {
        if (EnforcementWall.isShowing()) return true
        return try {
            val session = app.stateRepo.blockingSession()
            if (session != null && session.status.isEnforcing) return true

            val cage = app.stateRepo.blockingCage()
            if (cage.active && !cage.isExpired(SystemClockNow.elapsed)) return true

            if (com.maxleveldetox.monk.MonkModeManager.isActive(app)) return true

            if (com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(app)) return true

            val prime = app.stateRepo.blockingPrime()
            prime.active
        } catch (_: Exception) {
            // State unreadable: safer to stand down (never fight the user
            // on a false positive).
            false
        }
    }
}
