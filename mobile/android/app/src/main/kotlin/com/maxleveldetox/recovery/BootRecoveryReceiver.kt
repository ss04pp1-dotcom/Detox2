package com.maxleveldetox.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maxleveldetox.MldApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BootRecoveryReceiver (TRD §35, PRD §26).
 *
 * A device shutdown/reboot is NEVER treated as legitimate session
 * completion. On boot (and after app update):
 *   read persisted session -> still active? -> restore enforcement
 *   expired while off?    -> legitimate completion (endElapsed passed)
 *   cage active?           -> restore cage alarm
 *   alarms?                -> re-schedule every enabled alarm
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // v2.5.5 audit fix m-17: QUICKBOOT_POWERON dropped alongside its
        // manifest entry (spoofable non-protected broadcast).
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val result = goAsync()
        val app = context.applicationContext as? MldApp
        if (app == null) {
            result.finish()
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.sessionEngine.recoverIfNeeded()
                app.alarmEngine.rescheduleAll()

                // v2.0 Phase A2/A4: re-arm the persisted watchdog after
                // reboot (JobScheduler persistence is best-effort across
                // OEMs) and resume any active lock-my-phone session
                // (wall-clock authority survives the reboot).
                if (app.engineState.areGuardsEnabled()) {
                    com.maxleveldetox.guard.AccessibilityGuardJobService
                        .schedule(context)
                }
                if (com.maxleveldetox.lock.LockMyPhoneController
                        .isSessionActive(context)) {
                    com.maxleveldetox.lock.LockMyPhoneService.start(context)
                }

                // v2.5 r9.3: alarms do not survive a reboot — re-arm the
                // scheduled lock windows (and start one that is live now).
                try {
                    com.maxleveldetox.lock.LockScheduler.evaluateAndRearm(context, force = true)
                } catch (_: Exception) {
                }

                // v2.0 Phase B3: monk-mode wall-clock session survives the
                // reboot — re-arm the lock service + persisted guard job.
                if (com.maxleveldetox.monk.MonkModeManager.isActive(context)) {
                    try {
                        com.maxleveldetox.monk.MonkModeLockService.start(context)
                        com.maxleveldetox.monk.MonkModeGuardJobService.schedule(context)
                    } catch (_: Exception) {
                    }
                }

                // v2.0 Phase B5: reconcile any finished prime commit.
                app.primeCommit.reconcileIfNeeded()
            } catch (_: Exception) {
                // v2.5.5 audit fix C-3 sibling: an uncaught coroutine
                // exception in a background-started process used to kill it
                // in a loop. Recovery is idempotent — the next legit start
                // re-runs it.
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * EnforcementReceiver — session end / cage end / temp unlock expiry ticks.
 * Exact-where-allowed alarms keep these prompt; lazy evaluation in the
 * policy engine guarantees correctness even when alarms are deferred.
 */
class EnforcementReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MldApp ?: return
        val result = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_SESSION_END -> app.sessionEngine.evaluateAndMaybeComplete()
                    ACTION_CAGE_END -> app.sessionEngine.releaseCageIfExpired()
                    ACTION_TEMP_UNLOCK_END -> app.tempUnlockManager.expireIfNeeded()
                }
                com.maxleveldetox.enforcement.SessionEngine.Broadcaster.emit()
            } catch (_: Exception) {
                // v2.5.5 audit fix: same crash-loop guard as boot recovery.
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_SESSION_END = "com.maxleveldetox.action.SESSION_END"
        const val ACTION_CAGE_END = "com.maxleveldetox.action.CAGE_END"
        const val ACTION_TEMP_UNLOCK_END = "com.maxleveldetox.action.TEMP_UNLOCK_END"
    }
}
