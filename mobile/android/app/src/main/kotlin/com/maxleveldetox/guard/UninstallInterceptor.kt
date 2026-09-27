package com.maxleveldetox.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.LockController
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.ViolationType

/**
 * UninstallInterceptor — screen-scrape interception of uninstall / force-
 * stop / disable attempts against MAXLEVEL DETOX (v2.0 Phase A3).
 *
 * Ported from the reference app's proven multilingual interception design:
 * while a session is enforcing, accessibility events coming FROM the OS
 * settings / package-installer UI are text-scraped; if the concatenated
 * text contains our app label plus an uninstall/force-stop/disable keyword
 * (English + Bangla first — our market), we:
 *
 *   1. performGlobalAction(GLOBAL_ACTION_BACK) — leaves the danger screen,
 *   2. schedule a delayed re-check (some OEMs re-focus the screen),
 *   3. after 2 interceptions in a row, show the blocking lock screen
 *      (UNAUTHORIZED_UNLOCK kind) — the user is walked back out.
 *
 * SECURITY BOUND: interception is ACTIVE ONLY while a session is enforcing
 * (PRD: the user's own device, their committed block window). Outside a
 * session the user may freely uninstall — that is their right, and forced
 * otherwise would be malware behavior (TRD §22 ethics note).
 *
 * Node scanning is strictly bounded (max depth 3, max 40 nodes) to keep the
 * a11y hot path cheap.
 */
object UninstallInterceptor {

    /** OEM settings / installer / cleanup packages whose events we scrape. */
    private val WATCHED_PACKAGES = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.securitycenter",          // Xiaomi MIUI
        "com.miui.home",
        "com.xiaomi.mipicks",
        "com.coloros.safecenter",           // OPPO ColorOS
        "com.coloros.simsettings",
        "com.oppo.safe",
        "com.iqoo.secure",                  // Vivo iQOO
        "com.vivo.imanager",
        "com.vivo.permissionmanager",
        "com.sec.android.app.samsungapps",  // Samsung
        "com.samsung.android.sm.devicesecurity",
        "com.samsung.android.lool",
        "com.huawei.systemmanager",         // Huawei
        "com.google.android.apps.nbu.files",
        "com.oneplus.security",
        // v2.5 r9.2 - remaining packages from the reference app's list.
        "com.google.android.settings.intelligence",
        "com.miui.packageinstaller",
        "com.coloros.phonemanager",
        "com.oplus.safecenter",
        "com.oplus.battery",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.settings",
    )

    /** Exact list + structural matches, so an OEM we have never seen
     *  (settings, packageinstaller, securitycenter ...) is still covered. */
    private fun isWatched(pkg: String): Boolean =
        pkg in WATCHED_PACKAGES ||
            pkg.endsWith(".settings") ||
            pkg.contains("packageinstaller") ||
            pkg.contains("securitycenter") ||
            pkg.contains("safecenter") ||
            pkg.contains("phonemanager") ||
            pkg.contains("systemmanager")

    /** English + Bangla uninstall/force-stop/disable vocabulary. */
    private val KEYWORDS = listOf(
        // English
        "uninstall", "un-install", "remove app", "force stop", "force quit",
        "disable", "disabling", "deactivate", "force close", "clear data",
        // Bangla (common settings-UI strings)
        "আনইনস্টল", "আন ইনস্টল", "মুছে ফেলুন", "বাধা দিন",
        "জোর করে বন্ধ", "ডিইনস্টল", "অ্যাপ সরান",
        // v2.5 r9.2 - Bangla "deactivate / remove" (device-admin screen).
        "নিষ্ক্রিয়", "সরান", "নিষ্ক্রিয় করুন",
    )

    /** Our label fragments (matched case-insensitively). */
    private val SELF_LABELS = listOf(
        "maxlevel", "max level", "detox", "ম্যাক্সলেভেল",
    )

    // v2.5 r9.2: the old bounds (depth 3 / 40 nodes, and the node budget was
    // never actually decremented) could not reach the app-name row or the
    // Uninstall / Force stop buttons on a real App-info screen. The scan now
    // runs on the ACTIVE WINDOW only, for watched packages during an
    // enforcing session, throttled, with a real node budget.
    private const val MAX_NODE_DEPTH = 14
    private const val MAX_NODES = 220
    private const val SCAN_THROTTLE_MS = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var recentInterceptions = 0
    private var lastInterceptAt = 0L
    private var lastScanAt = 0L

    // -----------------------------------------------------------------

    fun maybeIntercept(
        service: AccessibilityService,
        event: AccessibilityEvent,
    ): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (!isWatched(pkg)) return false
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return false

        val app = service.application as? MldApp ?: return false
        val session = app.stateRepo.blockingSession()
        // ETHICS BOUND: only during an enforcing session.
        if (session == null || !session.status.isEnforcing) return false

        // Never fight the user's own permission-recovery / restore window
        // (same rule ShadeGuard follows).
        val grace = try {
            app.stateRepo.blockingGraceWindow()
        } catch (_: Exception) {
            null
        }
        if (grace != null &&
            grace.expiresElapsed > com.maxleveldetox.enforcement.SystemClockNow.elapsed
        ) return false

        // 1) Cheap path: the event's own text (window title etc.).
        var text = collectText(event)
        if (mentionsSelf(text) && mentionsKeyword(text)) {
            intercept(service, session.id, pkg)
            return true
        }

        // 2) Deep path (Social Sentry m0.a "fallback deep scan"): the app
        //    name and the Uninstall / Force stop / Deactivate buttons are
        //    almost never in event.text - they are nodes in the window.
        val scanned = scanActiveWindow(service, pkg) ?: return false
        text = "$text $scanned"
        if (!mentionsSelf(text) || !mentionsKeyword(text)) return false

        intercept(service, session.id, pkg)
        return true
    }

    /** Lower-cased text of the active window when it belongs to [pkg];
     *  null if throttled / unavailable. */
    private fun scanActiveWindow(service: AccessibilityService, pkg: String): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScanAt < SCAN_THROTTLE_MS) return null
        lastScanAt = now
        val root: AccessibilityNodeInfo? = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root == null) return null
        if (root.packageName?.toString() != pkg) return null
        val sb = StringBuilder()
        val budget = intArrayOf(MAX_NODES)
        appendNodeText(sb, root, 0, budget)
        return sb.toString().lowercase()
    }

    // -----------------------------------------------------------------

    private fun intercept(service: AccessibilityService, sessionId: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastInterceptAt > RESET_AFTER_MS) recentInterceptions = 0
        lastInterceptAt = now
        recentInterceptions++

        // 1. Walk the user out of the danger screen.
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
        }

        val app = service.application as MldApp
        app.violationManager.record(
            sessionId = sessionId,
            pkg = pkg,
            type = ViolationType.UNAUTHORIZED_UNLOCK,
            severity = "high",
            warningNumber = recentInterceptions,
            action = "uninstall_intercept",
        )

        // 2. Delayed re-check: if the OEM re-focuses the uninstall screen,
        //    BACK again.
        handler.postDelayed({
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
            }
        }, RECHECK_DELAY_MS)

        // 3. Repeated attempts: hard wall.
        if (recentInterceptions >= HARD_WALL_AFTER) {
            recentInterceptions = 0
            LockController.block(service, pkg)
        }
    }

    // -----------------------------------------------------------------
    // Text collection (bounded)
    // -----------------------------------------------------------------

    private fun collectText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        event.contentDescription?.let { sb.append(it).append(' ') }
        return sb.toString().lowercase()
    }

    private fun appendNodeText(
        sb: StringBuilder,
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
    ) {
        if (node == null || depth > MAX_NODE_DEPTH || budget[0] <= 0) return
        budget[0]--
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        val n = node.childCount
        for (i in 0 until n) {
            if (budget[0] <= 0) return
            appendNodeText(sb, node.getChild(i), depth + 1, budget)
        }
    }

    private fun mentionsSelf(lowerText: String): Boolean =
        SELF_LABELS.any { lowerText.contains(it) }

    private fun mentionsKeyword(lowerText: String): Boolean =
        KEYWORDS.any { lowerText.contains(it) }

    // -----------------------------------------------------------------

    private const val RESET_AFTER_MS = 60_000L
    private const val RECHECK_DELAY_MS = 350L
    private const val HARD_WALL_AFTER = 2
}
