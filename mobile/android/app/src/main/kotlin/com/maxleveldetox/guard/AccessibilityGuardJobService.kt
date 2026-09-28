package com.maxleveldetox.guard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.maxleveldetox.MldApp
import com.maxleveldetox.monitor.EngineStateStore
import com.maxleveldetox.monitor.ForegroundAppMonitorService

/**
 * AccessibilityGuardJobService — persisted watchdog (v2.0 Phase A2).
 *
 * A JobScheduler job (id 9401, persisted, 15-minute floor) that runs even
 * when the app process was killed by an OEM battery manager. Ported from
 * the reference app's proven guard design with our state machine:
 *
 *   Case 1 — a11y permission granted + heartbeat stale  = "BLACKOUT"
 *            (OEM silently killed us; permission still on)
 *            → start engine 2 + post "Protection degraded, tap to fix"
 *              notification + record violation.
 *
 *   Case 2 — a11y permission granted + heartbeat fresh  = healthy
 *            → stop engine 2 if it was a stale-flap; count a clean day.
 *
 *   Case 3 — a11y permission revoked (with active session)
 *            → engine 2 takeover + RECOVERY flow notification
 *              (PermissionMonitor drives the grace window).
 *
 *   Case 4 — no session and no history → nothing to guard; clean.
 *
 * Battery-awareness: after 3 consecutive fully-clean days the job
 * un-schedules itself (any incident re-arms it via rearm()).
 *
 * NOTE ON DEGRADE vs SILENCE: unknown states NEVER silently disable
 * enforcement (TRD §114) — they degrade to engine 2 + user escalation.
 */
class AccessibilityGuardJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as MldApp
        val result = GuardEvaluator.evaluate(app, applicationContext)
        jobFinished(params, result.scheduleRetry)
        return false // work completed synchronously
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // System pulled the job mid-flight (constraints lost): retry.
        return true
    }

    companion object {
        const val JOB_ID = 9401
        private const val PERIOD_MS = 15 * 60_000L // JobScheduler floor

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                as JobScheduler
            // v2.5 r9.2: this used to be a ONE-SHOT job (minimum latency +
            // override deadline) that nothing re-armed after it ran, so the
            // watchdog fired once and then went silent for as long as the
            // process stayed alive — i.e. it was gone exactly when an OEM
            // kill needed it. It is now a genuine PERIODIC persisted job
            // (like the monk guard), and scheduling is idempotent: calling
            // schedule() again while a periodic job is pending is a no-op.
            // (Re-scheduling an id replaces — and can cancel — a job that
            // is being started, so MldApp.onCreate must not do that.)
            val existing = try {
                js.getPendingJob(JOB_ID)
            } catch (_: Exception) {
                null
            }
            if (existing != null && existing.isPeriodic) return

            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AccessibilityGuardJobService::class.java),
            )
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)          // survives reboot
                .setRequiresBatteryNotLow(false)
                .build()
            try {
                js.schedule(info)
            } catch (_: Exception) {
            }
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                as JobScheduler
            js.cancel(JOB_ID)
        }

        fun isScheduled(context: Context): Boolean {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                as JobScheduler
            return js.allPendingJobs.any { it.id == JOB_ID }
        }
    }
}

/**
 * Pure evaluation logic, separated from the JobService for testability and
 * reuse by BootRecoveryReceiver and the EnforcementService sweep.
 */
object GuardEvaluator {

    data class Result(
        val clean: Boolean,
        val action: String,          // audit label
        val scheduleRetry: Boolean,  // job system retry hint
    )

    fun evaluate(app: MldApp, context: Context): Result {
        // v2.5 r9.2: every guard pass also revives a lock-my-phone / monk
        // session whose service died (Social Sentry guard jobs 9402/9932
        // parity — see ServiceRevival).
        ServiceRevival.reviveIfNeeded(context)

        val engine = app.engineState
        val session = app.stateRepo.blockingSession()
        val enforcing = session != null && session.status.isEnforcing
        val a11yGranted = app.permissionMonitor.snapshot().accessibility
        // v2.5 r9.5: schedules / app limits are enforced without a session,
        // so they also need engine 2 when the accessibility path is gone.
        val alwaysOn = com.maxleveldetox.enforcement.AlwaysOnRules.exist(context)

        // ------------------------------------------------------------------
        // Case 4: nothing to guard.
        // ------------------------------------------------------------------
        if (!enforcing && !alwaysOn && !engine.isEngine2Running()) {
            engine.recordGuardCheck(clean = true)
            maybeSelfDisable(app, context)
            return Result(true, "idle_healthy", false)
        }

        // ------------------------------------------------------------------
        // Case 3: permission revoked mid-session → engine 2 takeover.
        // ------------------------------------------------------------------
        if (!a11yGranted) {
            if (enforcing) {
                ForegroundAppMonitorService.start(context)
                GuardNotifier.notifyPermissionLost(context)
                engine.recordGuardCheck(clean = false)
                rearm(context, engine) // re-arm after any incident
                return Result(false, "permission_lost_engine2_takeover", false)
            }
            if (alwaysOn) {
                // Their schedules / limits still apply: engine 2 carries
                // them (its own notification is the signal — no alarm).
                ForegroundAppMonitorService.start(context)
                engine.recordGuardCheck(clean = true)
                return Result(true, "a11y_off_always_on_engine2", false)
            }
            // No session: user simply turned it off — their right (PRD §35).
            engine.recordGuardCheck(clean = true)
            return Result(true, "a11y_off_no_session", false)
        }

        // ------------------------------------------------------------------
        // Case 1 vs 2: permission granted — is the process alive?
        // ------------------------------------------------------------------
        val stale = engine.isEngine1Stale()
        return if (stale) {
            // BLACKOUT: OEM kill suspected. Engine 2 immediately.
            if (enforcing) {
                ForegroundAppMonitorService.start(context)
                GuardNotifier.notifyBlackout(context)
                engine.recordGuardCheck(clean = false)
                rearm(context, engine)
                Result(false, "blackout_engine2_started", false)
            } else if (alwaysOn && isScreenOn(context)) {
                // Schedules / limits with a dead accessibility process:
                // engine 2 (only while the screen is on — an idle phone
                // has no events, which also looks "stale").
                ForegroundAppMonitorService.start(context)
                engine.recordGuardCheck(clean = false)
                Result(false, "blackout_always_on_engine2", false)
            } else {
                // No session active: just re-arm and note it. No alarm —
                // nothing is being bypassed right now.
                engine.recordGuardCheck(clean = false)
                Result(false, "blackout_no_session", false)
            }
        } else {
            // Healthy: engine 2 will self-standdown via its own yield logic.
            engine.recordGuardCheck(clean = true)
            maybeSelfDisable(app, context)
            Result(true, "healthy", false)
        }
    }

    private fun isScreenOn(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    } catch (_: Exception) {
        true
    }

    /** Incident: flag the guards enabled AND make sure the persisted job is
     *  actually scheduled again (it may have been cancelled by an earlier
     *  self-disable — flipping the flag alone never re-created the job). */
    private fun rearm(context: Context, engine: EngineStateStore) {
        engine.setGuardsEnabled(true)
        AccessibilityGuardJobService.schedule(context)
    }

    private fun maybeSelfDisable(app: MldApp, context: Context) {
        val engine = app.engineState
        // v2.5 r9.2: never self-disable while a lock-my-phone / monk session
        // is live — this job is what revives their services.
        val lockSessionLive =
            com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(context) ||
                com.maxleveldetox.monk.MonkModeManager.isActive(context)
        if (!lockSessionLive && engine.areGuardsEnabled() && engine.shouldGuardsSelfDisable()) {
            engine.setGuardsEnabled(false)
            AccessibilityGuardJobService.cancel(context)
        }
    }
}
