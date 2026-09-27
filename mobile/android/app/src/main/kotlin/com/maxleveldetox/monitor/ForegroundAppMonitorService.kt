package com.maxleveldetox.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.AlwaysOnRules
import com.maxleveldetox.enforcement.AppLimitEngine
import com.maxleveldetox.enforcement.LockController
import com.maxleveldetox.enforcement.ScheduleEngine
import com.maxleveldetox.enforcement.PolicyDecision
import com.maxleveldetox.enforcement.ViolationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ForegroundAppMonitorService — ENFORCEMENT ENGINE 2 (v2.0 Phase A1).
 *
 * Strategy (ported from the reference app's proven design, adapted to our
 * architecture): a specialUse foreground service that independently polls
 * UsageStatsManager event history every 850ms to determine the current
 * foreground package. When the accessibility engine (engine 1) is healthy,
 * this service stays dormant. It activates when:
 *
 *   1. The accessibility heartbeat goes stale > 60s ("blackout" — the OEM
 *      silently killed our process while the permission is still granted),
 *      or
 *   2. The accessibility permission is revoked mid-session (engine 2 keeps
 *      enforcing while the RECOVERY flow drives the user to re-enable), or
 *   3. The accessibility service's onDestroy fires (pre-kill handoff).
 *
 * When engine 1 reconnects (onServiceConnected), it stops engine 2.
 *
 * HONESTY NOTE (TRD §47/§34): engine 2 is *degraded* enforcement — it has
 * ~1s latency and no screen-content visibility (no shorts detection), but
 * it is far better than a silent total bypass after an OEM kill.
 */
class ForegroundAppMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClean()
                return START_NOT_STICKY
            }
            else -> {
                val app = application as MldApp
                // Phase A5: crash-loop backoff gate.
                if (app.engineState.serviceStartBlockedByBackoff(
                        EngineStateStore.SERVICE_ENGINE2)) {
                    // Escalate instead of loop: a guard job will retry later.
                    stopSelf()
                    return START_NOT_STICKY
                }
                app.engineState.recordServiceRestart(EngineStateStore.SERVICE_ENGINE2)
                startAsForeground()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val app = application as MldApp
        app.engineState.setEngine2Running(true)
        postNotice()

        pollJob?.cancel()
        pollJob = scope.launch {
            val runStartedAt = System.currentTimeMillis()
            var restartsCleared = false
            var lastEventTime = System.currentTimeMillis()
            var lastSlowTick = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val foreground = queryForegroundSince(lastEventTime - POLL_MS)
                lastEventTime = now
                if (foreground != null) {
                    enforceIfRestricted(foreground)
                }

                // Standalone duties (engine 1 may be dead — we are the
                // backup timer authority too).
                app.sessionEngine.evaluateAndMaybeComplete()
                app.sessionEngine.releaseCageIfExpired()

                // v2.5 r9 — Social Sentry parity slow ticks (≤5 s while
                // engine 2 is up; each helper self-throttles internally):
                //   - auto-reblock: temp-unlock tamper/expiry restore (#17)
                //   - Sinthia hourly check-in notifications (§1b)
                //   - brain-rot HUD pill (≥ MILD stage)
                //   - daily routine reset for the task economy
                if (now - lastSlowTick >= 5_000L) {
                    lastSlowTick = now

                    // v2.5 r9.2: a run that stayed up for a minute is a
                    // clean run — forget earlier restarts, otherwise six
                    // ordinary handoffs inside the crash window would
                    // block engine 2 for up to 30 minutes.
                    if (!restartsCleared && now - runStartedAt > CLEAN_RUN_AFTER_MS) {
                        restartsCleared = true
                        app.engineState.resetServiceRestarts(EngineStateStore.SERVICE_ENGINE2)
                    }
                    // Revive lock-my-phone / monk services killed by the OEM.
                    try {
                        com.maxleveldetox.guard.ServiceRevival.reviveIfNeeded(
                            this@ForegroundAppMonitorService)
                    } catch (_: Exception) {
                    }
                    try {
                        com.maxleveldetox.unlock.AutoReblockMonitor.tick(this@ForegroundAppMonitorService)
                    } catch (_: Exception) {
                    }
                    try {
                        com.maxleveldetox.gamification.SinthiaCheckIn.tick(
                            this@ForegroundAppMonitorService, foreground)
                    } catch (_: Exception) {
                    }
                    try {
                        com.maxleveldetox.monitor.BrainRotEngine.maybeShowHud(
                            this@ForegroundAppMonitorService, null)
                    } catch (_: Exception) {
                    }
                    try {
                        com.maxleveldetox.gamification.TasksEngine.maybeResetRoutines(
                            this@ForegroundAppMonitorService)
                    } catch (_: Exception) {
                    }
                    // v2.5.8 roadmap: keep the trend widget's daily
                    // distraction-minutes high-water mark fresh
                    // (self-throttled to one sample / 10 min).
                    try {
                        com.maxleveldetox.widgets.TrendSampler.tick(
                            this@ForegroundAppMonitorService)
                    } catch (_: Exception) {
                    }
                }

                // Self-standdown: if engine 1 is healthy again AND no
                // permission emergency exists, engine 2 yields.
                if (shouldYield()) {
                    stopClean()
                    break
                }

                delay(POLL_MS)
            }
        }
    }

    // -----------------------------------------------------------------
    // Foreground detection via UsageStats event stream
    // -----------------------------------------------------------------

    private fun queryForegroundSince(sinceMs: Long): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(sinceMs, System.currentTimeMillis())
        var foreground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    foreground = event.packageName
                UsageEvents.Event.ACTIVITY_RESUMED -> // API 29+ alias
                    foreground = event.packageName
            }
        }
        return foreground
    }

    /**
     * v2.5 r9.5 — scheduled blocking + per-app daily limits, independent of
     * any session (the same rules the accessibility path applies in
     * `enforceAlwaysOnSurfaces`). Returns true when a wall was raised.
     */
    private fun enforceAlwaysOn(app: MldApp, pkg: String): Boolean {
        try {
            if (ScheduleEngine.blocksNow(this, pkg)) {
                LockController.blockWithMessage(this, pkg, "Blocked by your active schedule.")
                app.violationManager.record(
                    sessionId = "schedule",
                    pkg = pkg,
                    type = ViolationType.BLOCKED_APP,
                    severity = "LOW",
                    warningNumber = 0,
                    action = "engine2_schedule_block",
                )
                return true
            }
        } catch (_: Exception) {
        }
        try {
            val exceeded = AppLimitEngine.exceededLimit(this, pkg)
            if (exceeded != null) {
                LockController.blockWithMessage(
                    this, pkg,
                    "Daily limit reached (${exceeded.dailyLimitMinutes} min). " +
                        "It resets at midnight — or unlock with coins.",
                )
                app.violationManager.record(
                    sessionId = "app_limit",
                    pkg = pkg,
                    type = ViolationType.BLOCKED_APP,
                    severity = "LOW",
                    warningNumber = 0,
                    action = "engine2_app_limit_block",
                )
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun enforceIfRestricted(pkg: String) {
        val app = application as MldApp

        // Always-on rules first (hard boundaries — emergency, system,
        // ourselves — can never be walled).
        if (pkg != packageName &&
            !app.policyEngine.isEmergency(pkg) &&
            !app.policyEngine.isSystemEssential(pkg) &&
            enforceAlwaysOn(app, pkg)
        ) return

        // Monk mode polices the device WITHOUT a session (v2.0 Phase B3):
        // engine 2 must keep the lockdown alive even when no Study/Detox
        // session exists.
        val monkActive = com.maxleveldetox.monk.MonkModeManager.isActive(this)
        val session = app.stateRepo.blockingSession()
        if (!monkActive && (session == null || !session.status.isEnforcing)) return

        when (app.policyEngine.evaluate(pkg)) {
            PolicyDecision.BLOCK, PolicyDecision.CAGE_BLOCK,
            PolicyDecision.MONK_BLOCK -> {
                // Same enforcement surface the accessibility path uses —
                // LockScreenActivity over the restricted app. HOME is
                // handled by the activity itself (back goes home).
                LockController.block(this, pkg)
                app.violationManager.record(
                    sessionId = session?.id ?: "monk",
                    pkg = pkg,
                    type = if (monkActive && session == null)
                        ViolationType.MONK_EXIT else ViolationType.BLOCKED_APP,
                    // v2.5.5 audit fix m-15: every other call site emits
                    // uppercase severities — severity-based UI filtering
                    // missed these lowercase rows.
                    severity = if (monkActive) "HIGH"
                        else if (session?.strictness?.name == "MAXLEVEL") "HIGH" else "MEDIUM",
                    warningNumber = 0,
                    action = "engine2_block",
                )
            }
            else -> Unit
        }
    }

    // -----------------------------------------------------------------
    // Engine handoff
    // -----------------------------------------------------------------

    private fun shouldYield(): Boolean {
        val app = application as MldApp
        // Engine 1 healthy (fresh heartbeat) and a11y permission granted.
        // v2.5.5 audit fix M-5: the old logic returned
        // `healthyFor > YIELD_GRACE_MS` where healthyFor was the heartbeat
        // AGE — engine 2 kept enforcing while engine 1 was actively healthy
        // (duplicate walls + violation rows) and stood down exactly in the
        // 30–60s quiet-window before engine 1 would be flagged stale. The
        // failover design requires the opposite: a healthy, freshly beating
        // engine 1 means engine 2 stands down immediately.
        if (app.permissionMonitor.snapshot().accessibility &&
            !app.engineState.isEngine1Stale()
        ) {
            return true
        }
        // Nothing left to enforce: no session, no monk lockdown and no
        // always-on rules (schedules / app limits) — v2.5 r9.5.
        val session = app.stateRepo.blockingSession()
        if (session != null && session.status.isEnforcing) return false
        if (com.maxleveldetox.monk.MonkModeManager.isActive(this)) return false
        return !AlwaysOnRules.exist(this)
    }

    // -----------------------------------------------------------------
    // Notification (honest, non-alarming)
    // -----------------------------------------------------------------

    private fun postNotice() {
        // Engine 2's own FGS notification IS the user-visible signal
        // (merged into the enforcement channel, low importance).
    }

    private fun buildNotification(): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_ENGINE2)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_engine2_active))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ENGINE2,
                    getString(R.string.channel_engine2),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = getString(R.string.channel_engine2_desc) }
            )
        }
    }

    private fun stopClean() {
        pollJob?.cancel()
        pollJob = null
        (application as MldApp).engineState.setEngine2Running(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Swiping the app away must not end enforcement: re-arm a restart. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val app = application as MldApp
            val session = app.stateRepo.blockingSession()
            if ((session != null && session.status.isEnforcing) ||
                AlwaysOnRules.exist(this)
            ) {
                com.maxleveldetox.guard.ServiceRevival.scheduleServiceRestart(
                    this, ForegroundAppMonitorService::class.java, 9412)
            }
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        alive = false
        (application as? MldApp)?.engineState?.setEngine2Running(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ FGS timeout: degrade gracefully — persist running
        // state false; the persisted guard job re-arms us within 15 min.
        (application as? MldApp)?.engineState?.setEngine2Running(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        // Android 14 variant — delegate to the same degrade path.
        onTimeout(startId, -1)
    }

    companion object {
        private const val NOTIF_ID = 1002
        private const val CH_ENGINE2 = "mld_engine2"
        const val POLL_MS = 850L

        /** Engine 1 must stay healthy this long before engine 2 yields.
         *  v2.5.5 audit fix M-5: no longer consulted by shouldYield() — kept
         *  for reference and potential anti-flap use in GuardEvaluator. */
        const val YIELD_GRACE_MS = 30_000L

        const val ACTION_STOP = "com.maxleveldetox.action.STOP_ENGINE2"

        /** Clean-run threshold after which the crash-loop counter resets. */
        private const val CLEAN_RUN_AFTER_MS = 60_000L

        /** Process-local liveness. The persisted `engine2_running` flag is
         *  NOT trustworthy after an OEM kill (onDestroy never ran, so it
         *  stayed `true`), which used to make start() a permanent no-op. */
        @Volatile
        var alive = false
            private set

        fun start(context: Context) {
            if (alive) return
            val intent = Intent(context, ForegroundAppMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // API 31+ may refuse a background foreground-service start
                // (ForegroundServiceStartNotAllowedException). Never crash
                // the caller (guard job / a11y unbind): the guard
                // notification + the next guard pass are the fallback.
                try {
                    com.maxleveldetox.accessibility.DiagLog.logError("engine2_start", e)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            // v2.5.5 audit fix m-16: startService from the background throws
            // IllegalStateException when the service is not already running
            // (double-stop / OEM kill). Prefer a hard stopService which is
            // always legal, falling back to the action-based path.
            try {
                context.stopService(Intent(context, ForegroundAppMonitorService::class.java))
            } catch (_: Exception) {
            }
            try {
                context.startService(
                    Intent(context, ForegroundAppMonitorService::class.java)
                        .setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
