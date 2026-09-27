package com.maxleveldetox.enforcement

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * KioskController — the TRAP layer (user-reported v1.0.5 gap).
 *
 * Blocking restricted apps is not enough: during a DETOX session the user
 * must not be able to wander off to the launcher either. Android's screen
 * pinning (user-consent LockTask) is the only OS-sanctioned way for a
 * normal app to truly disable HOME / RECENTS / BACK.
 *
 * Layered design:
 *   1. startLockTask() on MainActivity while a DETOX session is ACTIVE —
 *      OS level: HOME, RECENTS and the status bar are disabled. Requires a
 *      one-tap system consent dialog (Android's rule for non-device-owner
 *      apps; the price of a real hard lock).
 *   2. If pinning is declined or lost, DetoxAccessibilityService's
 *      snap-back pulls the app back to the foreground whenever the
 *      launcher appears (rubber-band effect).
 *   3. PopScope(canPop: false) on the Dart session screen swallows BACK.
 *
 * Pinning is intentionally NOT used for STUDY sessions (their allowlist
 * may include education apps outside this one) and is lifted for temp
 * unlock windows, RECOVERY (the user must reach Settings), the emergency
 * dialer and the alarm — safety always outranks strictness (PRD §27).
 *
 * All state transitions flow through sync()/setRequested() which are
 * idempotent and safe to call from any thread, any number of times:
 *   - SessionEngine.startSession / finalizeLocked / recoverIfNeeded
 *   - TempUnlockManager.request / clear
 *   - PermissionMonitor (RECOVERY enter / resume)
 *   - EnforcementService 30s sweep (self-healing heartbeat)
 */
object KioskController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activityRef: WeakReference<Activity>? = null

    @Volatile private var resumed = false
    @Volatile private var requested = false

    /** Set optimistically after a startLockTask() call that did not throw.
     *  Cleared on pause/unpin — the unpin gesture and our own stopLockTask
     *  both pause the activity, so the flag tracks reality closely enough
     *  without depending on ActivityManager API level differences. */
    @Volatile private var optimisticPinned = false
    @Volatile private var lastPinAttemptElapsed = 0L

    private const val PIN_RETRY_COOLDOWN_MS = 5_000L

    // ------------------------------------------------------------------
    // Activity lifecycle wiring (called from MainActivity)
    // ------------------------------------------------------------------

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        resumed = true
        refreshPin()
    }

    fun onActivityPaused() {
        resumed = false
        optimisticPinned = false
    }

    fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            resumed = false
        }
    }

    // ------------------------------------------------------------------
    // Desired-state computation (idempotent)
    // ------------------------------------------------------------------

    /** True when persisted session state demands the kiosk trap:
     *  DETOX + ACTIVE (not RECOVERY) + no live temp-unlock window. */
    fun wanted(stateRepo: StateRepository): Boolean {
        val session = stateRepo.blockingSession() ?: return false
        if (session.status != SessionStatus.ACTIVE) return false
        if (session.mode != SessionMode.DETOX) return false
        val unlock = stateRepo.blockingTempUnlock()
        val unlockLive = unlock.active && !unlock.isExpired(SystemClockNow.elapsed)
        return !unlockLive
    }

    fun sync(stateRepo: StateRepository) = setRequested(wanted(stateRepo))

    fun syncAsync(stateRepo: StateRepository) {
        scope.launch { sync(stateRepo) }
    }

    fun setRequested(want: Boolean) {
        requested = want
        if (want) refreshPin() else unpin()
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Lift the pin WITHOUT forgetting the request — used by the emergency
     *  dialer and the alarm so system screens stay reachable. The trap
     *  re-arms automatically when MainActivity resumes. */
    fun unpinTemporarily() {
        unpin()
    }

    private fun refreshPin() {
        if (!requested || !resumed) return
        val activity = activityRef?.get() ?: return
        if (optimisticPinned) return
        val now = SystemClockNow.elapsed
        if (now - lastPinAttemptElapsed < PIN_RETRY_COOLDOWN_MS) return
        lastPinAttemptElapsed = now
        mainHandler.post {
            try {
                activity.startLockTask()
                optimisticPinned = true
            } catch (_: Exception) {
                // Consent declined or OEM restriction — the accessibility
                // snap-back still covers the launcher escape path.
                optimisticPinned = false
            }
        }
    }

    private fun unpin() {
        optimisticPinned = false
        val activity = activityRef?.get() ?: return
        mainHandler.post {
            try {
                activity.stopLockTask()
            } catch (_: Exception) {
                // Not pinned (or already unpinned) — nothing to do.
            }
        }
    }
}
