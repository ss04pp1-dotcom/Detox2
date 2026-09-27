package com.maxleveldetox.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.monitor.EngineStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground enforcement service (TRD §47).
 *
 * Responsibilities:
 *  - hold the enforcement context with a persistent, honest notification
 *    ("Detox active · 01:24:12 remaining" — UI/UX §46)
 *  - periodic permission sweep (detects accessibility being disabled mid-
 *    session: the #1 tamper path on ordinary Android)
 *  - lazy completion + cage release evaluation
 *
 * HONESTY NOTE: a foreground service is NOT a guarantee against every form
 * of process termination (TRD §47/§34). Persistence + recovery are.
 */
class EnforcementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweepJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
            else -> startAsForeground()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val app = application as MldApp
        com.maxleveldetox.accessibility.DiagLog.log("FGS_START", "EnforcementService starting")

        // Phase A5: crash-loop backoff gate — a service restart storm must
        // degrade to user escalation, never to an infinite loop.
        if (app.engineState.serviceStartBlockedByBackoff(
                EngineStateStore.SERVICE_ENFORCEMENT)) {
            com.maxleveldetox.accessibility.DiagLog.log("FGS_BLOCKED", "backoff gate stopped the restart")
            stopSelf()
            return
        }
        app.engineState.recordServiceRestart(EngineStateStore.SERVICE_ENFORCEMENT)

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // Healthy-run marker: after 5 minutes of continuous sweeping the
        // restart counter ages out (Phase A5 clean-run reset).
        val startedAt = System.currentTimeMillis()

        // Periodic sweeps: permission tamper detection + lazy completion +
        // dual-engine guard evaluation (Phase A2).
        sweepJob?.cancel()
        sweepJob = scope.launch {
            var guardTick = 0L
            while (isActive) {
                delay(SWEEP_INTERVAL_MS)

                // Lazy completion / cage release / prime reconcile.
                app.sessionEngine.evaluateAndMaybeComplete()
                app.sessionEngine.releaseCageIfExpired()
                app.primeCommit.reconcileIfNeeded()

                // v2.1 Phase C: progress layer housekeeping — day rollover,
                // grace expiry, monthly freeze allowance (parallel layer).
                try {
                    app.progressEngine.onSweepTick()
                } catch (_: Exception) {
                }

                // v2.2 Phase D: lazy daily-insight check (the exact alarm is
                // the primary driver; this covers OEM alarm loss).
                try {
                    app.insightNotifier.maybePostDailyInsight()
                } catch (_: Exception) {
                }

                // Permission tamper check during an active session (TRD §31/§32).
                val session = app.stateRepo.blockingSession()
                if (session != null && session.status.isEnforcing) {
                    app.permissionMonitor.checkAndReact()
                }

                // v2.9 r17 — SESSION KIOSK self-healing heartbeat: re-assert
                // the wall/strips against the CURRENT foreground (covers missed
                // a11y events, emergency-lockdown auto-expiry, OEM window
                // removals). SessionKiosk carries its own stand-down logic.
                try {
                    com.maxleveldetox.overlay.SessionKiosk.sync(this@EnforcementService)
                } catch (_: Exception) {
                }

                // Guard evaluation every 4th sweep (~2 min) — the persisted
                // JobScheduler guard covers the 15-min scale; this covers
                // in-process flaps (Phase A2 dual coverage).
                guardTick++
                if (guardTick % 4 == 0L) {
                    try {
                        com.maxleveldetox.guard.GuardEvaluator.evaluate(
                            app, this@EnforcementService)
                    } catch (_: Exception) {
                    }
                }

                // Clean-run reset (Phase A5).
                if (System.currentTimeMillis() - startedAt > CLEAN_RUN_AFTER_MS) {
                    app.engineState.resetServiceRestarts(
                        EngineStateStore.SERVICE_ENFORCEMENT)
                }

                updateNotification()
            }
        }
    }

    private fun stopSelfClean() {
        sweepJob?.cancel()
        sweepJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task removal is NOT session termination — the service keeps
        // running; if the system kills us anyway, persisted state + boot
        // recovery restore enforcement (PRD §4).
        super.onTaskRemoved(rootIntent)
    }

    // -----------------------------------------------------------------

    private fun buildNotification(): Notification {
        val app = application as MldApp
        val session = app.stateRepo.blockingSession()
        val cage = app.stateRepo.blockingCage()

        val contentText = when {
            cage.active -> getString(R.string.notif_cage_active,
                SessionEngine.formatSeconds(cage.remainingSeconds(SystemClockNow.elapsed)))
            session != null && session.status == SessionStatus.PAUSED -> getString(
                R.string.notif_session_paused,
                SessionEngine.formatSeconds(((session.pauseEndElapsed - SystemClockNow.elapsed) / 1000L)
                    .coerceAtLeast(0).toInt()),
            )
            session != null && session.status.isEnforcing -> getString(
                R.string.notif_session_active,
                if (session.mode == SessionMode.STUDY) "Study" else "Detox",
                SessionEngine.formatSeconds(session.remainingSeconds(SystemClockNow.elapsed)),
            )
            else -> getString(R.string.app_name)
        }

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, SessionEngine.CH_ENFORCEMENT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    SessionEngine.CH_ENFORCEMENT,
                    getString(R.string.channel_enforcement),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_enforcement_desc) }
            )
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val SWEEP_INTERVAL_MS = 30_000L
        private const val CLEAN_RUN_AFTER_MS = 5 * 60_000L
        const val ACTION_STOP = "com.maxleveldetox.action.STOP_ENFORCEMENT"

        fun start(context: Context) {
            val intent = Intent(context, EnforcementService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // v2.5.5 audit fix C-3 companion guard: recoverIfNeeded can
                // reach this from a background-started process — never let
                // ForegroundServiceStartNotAllowedException crash the caller
                // (the persisted guard job + recovery re-arm the engine).
            }
        }

        fun stop(context: Context) {
            // v2.5.5 audit fix m-16: startService from the background throws
            // IllegalStateException when the target service is not already
            // running (double-stop / OEM kill). Hard stopService is always
            // legal; the action-based stop is kept as a guarded fallback.
            try {
                context.stopService(Intent(context, EnforcementService::class.java))
            } catch (_: Exception) {
            }
            try {
                context.startService(
                    Intent(context, EnforcementService::class.java)
                        .setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
