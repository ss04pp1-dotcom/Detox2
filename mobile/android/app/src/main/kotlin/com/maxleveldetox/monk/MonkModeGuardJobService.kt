package com.maxleveldetox.monk

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * MonkModeGuardJobService (v2.0 Phase B3) — persisted watchdog that
 * resurrects the monk lock service after OEM kills, force-stops and
 * reboots (JobScheduler persistence is best-effort; BootRecoveryReceiver
 * re-schedules us too — belt and suspenders, same pattern as the
 * reference app's id-9932 guard).
 *
 * The job is cheap: it only (re)starts the service when a monk session
 * is actually active; the service self-cleans when it is not.
 */
class MonkModeGuardJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (MonkModeManager.isActive(applicationContext)) {
            try {
                MonkModeLockService.start(applicationContext)
            } catch (_: Exception) {
                // crash-loop protection: the next guard run retries; the
                // backoff ladder in EngineStateStore also gates service
                // starts on repeated rapid failures.
            }
        }
        jobFinished(params, false)
        // Reschedule ourselves for the next window.
        schedule(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Reschedule on system-driven stop — monk must never stay dead
        // while a session is active.
        schedule(applicationContext)
        return true
    }

    companion object {
        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (isScheduled(context)) return
            val info = JobInfo.Builder(
                MonkModeManager.GUARD_JOB_ID,
                ComponentName(context, MonkModeGuardJobService::class.java),
            )
                .setPersisted(true)
                .setPeriodic(15 * 60_000L) // 15 min like the a11y guard
                .build()
            try {
                js.schedule(info)
            } catch (_: Exception) {
            }
        }

        fun isScheduled(context: Context): Boolean {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            return js.allPendingJobs.any { it.id == MonkModeManager.GUARD_JOB_ID }
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(MonkModeManager.GUARD_JOB_ID)
        }
    }
}
