package com.maxleveldetox.monk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.ViolationType

/**
 * MonkModeLockService (v2.0 Phase B3) — the monk-mode enforcement engine.
 *
 * Ported from the reference app's MonkModeLockService (report-modes.md
 * §1.4/§10), adapted:
 *
 *   - enforcementRunnable ticks 1500ms while LOCKED / 800ms while
 *     ALLOWED_APP, only while the screen is interactive (battery honesty).
 *   - LOCKED: asserts the lock surface (MonkModeOverlayActivity) and
 *     re-asserts lockNow() on fresh activation.
 *   - ALLOWED_APP: queries UsageEvents over the last 5s for the latest
 *     ACTIVITY_RESUMED package; if it is not (self | allowlist | system
 *     essential | dialer-during-call) we exit to LOCKED: lockNow() +
 *     overlay + a MONK_EXIT violation.
 *   - Audio-mode listener (API 31+): RINGING/IN_CALL -> ALLOWED_APP with
 *     the dialer; back to NORMAL -> re-lock. On older APIs the dialer is
 *     policed by the same UsageEvents tick (dialer is emergency-allowed
 *     by the policy engine anyway — PRD §27).
 *   - Expiry is wall-clock (survives reboot); boot recovery re-arms.
 *   - onTaskRemoved re-kicks the service (guard job also exists).
 */
class MonkModeLockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null
    private var audioManager: AudioManager? = null

    // v2.5.5 audit fix M-10: the audio-mode listener is tracked so it can
    // be removed in onDestroy — every sticky/ServiceRevival restart used to
    // register ANOTHER listener (each holding the dead service instance).
    private var audioListener: AudioManager.OnModeChangedListener? = null
    private var callModeActive = false

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // null intent = sticky restart after process death.
                if (!MonkModeManager.isActive(this)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                val fresh = intent?.getBooleanExtra(EXTRA_FRESH, false) ?: false
                if (fresh) {
                    MonkModeManager.lockNow(this)
                    showLockSurface()
                }
                startEngines()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    MonkModeManager.CHANNEL_MONK,
                    getString(R.string.channel_monk),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_monk_desc) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, MonkModeManager.CHANNEL_MONK)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.notif_monk_active))
            .setContentText(MonkModeManager.goal(this).ifBlank { getString(R.string.notif_monk_goal_blank) })
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MonkModeManager.NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(MonkModeManager.NOTIF_ID, notif)
        }
    }

    // -----------------------------------------------------------------
    // Enforcement engines
    // -----------------------------------------------------------------

    private fun startEngines() {
        registerScreenReceiver()
        registerAudioListener()
        scheduleExpiryCheck()
        handler.post(enforcementTick)
        MonkModeGuardJobService.schedule(this)
    }

    private val enforcementTick: Runnable = object : Runnable {
        override fun run() {
            if (!MonkModeManager.isActive(this@MonkModeLockService)) {
                MonkModeManager.deactivate(this@MonkModeLockService, "expiry")
                return
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                when (MonkModeManager.state(this@MonkModeLockService)) {
                    MonkModeManager.MonkState.LOCKED -> tickLocked()
                    MonkModeManager.MonkState.ALLOWED_APP -> tickAllowedApp()
                }
            }
            val delay = if (MonkModeManager.state(this@MonkModeLockService) == MonkModeManager.MonkState.ALLOWED_APP) {
                TICK_ALLOWED_APP_MS
            } else {
                TICK_LOCKED_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    /** LOCKED: make sure the lock surface owns the screen. */
    private fun tickLocked() {
        // The overlay activity re-asserts itself; if the user somehow
        // escaped to a non-allowed app (e.g. notification shade launch),
        // the usage-events check below catches it as an implicit exit —
        // but in LOCKED we simply re-assert the surface + re-lock.
        val topPkg = latestResumedPackage()
        if (topPkg != null && !isAllowedWhileLocked(topPkg)) {
            // Escaped the lock surface: treat as exit -> re-lock.
            recordExit("left_locked_surface:$topPkg")
            MonkModeManager.lockNow(this)
            showLockSurface()
        } else if (topPkg != null && topPkg == packageName) {
            // Our own surface is up — nothing to do.
        }
    }

    /** ALLOWED_APP: police the 5s UsageEvents window. */
    private fun tickAllowedApp() {
        val topPkg = latestResumedPackage() ?: return
        if (isAllowedWhileAllowedApp(topPkg)) return

        recordExit("left_allowed_app:$topPkg")
        MonkModeManager.setState(this, MonkModeManager.MonkState.LOCKED)
        MonkModeManager.lockNow(this)
        showLockSurface()
    }

    private fun isAllowedWhileLocked(pkg: String): Boolean =
        pkg == packageName || isAllowedWhileAllowedApp(pkg)

    private fun isAllowedWhileAllowedApp(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg in MonkModeManager.allowedApps(this)) return true
        // The default launcher is always reachable (home is home).
        if (MonkModeManager.isDefaultLauncher(this, pkg)) return true
        // System essentials + emergency (dialer etc.) are never monk-blocked.
        val app = application as? MldApp
        if (app?.policyEngine?.isSystemEssential(pkg) == true) return true
        // During a live call the dialer/in-call UI must stay usable.
        if (callModeActive && app?.policyEngine?.isEmergency(pkg) == true) return true
        return false
    }

    /** Exit throttle: one recorded exit per package per 60s — the tick
     *  re-locks on every event but only LOGS once a minute. */
    private val lastExitAt = HashMap<String, Long>()

    private fun recordExit(reason: String) {
        val pkg = reason.substringAfterLast(':')
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastExitAt) {
            val last = lastExitAt[pkg] ?: 0L
            if (now - last < EXIT_LOG_THROTTLE_MS) return
            lastExitAt[pkg] = now
        }
        MonkModeManager.recordExit(this)
        val app = application as? MldApp ?: return
        app.violationManager.record(
            sessionId = "monk",
            pkg = pkg,
            type = ViolationType.MONK_EXIT,
            severity = "MEDIUM",
            warningNumber = 0,
            action = reason.substringBeforeLast(':'),
        )
    }

    /** Latest ACTIVITY_RESUMED package in the last 5 seconds. */
    private fun latestResumedPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5_000L, now)
            var latest: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latest = e.packageName
                }
            }
            latest
        } catch (_: Exception) {
            null // permission lost — overlay + lockNow still hold the line
        }
    }

    private fun showLockSurface() {
        try {
            startActivity(
                MonkModeOverlayActivity.intentFor(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    // -----------------------------------------------------------------
    // Screen state receiver — re-attach the surface on unlock.
    // -----------------------------------------------------------------

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        if (MonkModeManager.isActive(context)) {
                            showLockSurface()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    // -----------------------------------------------------------------
    // Audio mode listener — automatic call exemption (API 31+).
    // -----------------------------------------------------------------

    private fun registerAudioListener() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // v2.5.5 audit fix M-10: guard like registerScreenReceiver — a
        // listener is registered at most once per service instance.
        if (audioListener != null) return
        try {
            val listener = AudioManager.OnModeChangedListener { mode ->
                when {
                    mode == MonkModeManager.AUDIO_MODE_RINGING || mode == AudioManager.MODE_IN_CALL ||
                        mode == AudioManager.MODE_IN_COMMUNICATION -> {
                        callModeActive = true
                        if (MonkModeManager.state(this) != MonkModeManager.MonkState.ALLOWED_APP) {
                            MonkModeManager.setState(this, MonkModeManager.MonkState.ALLOWED_APP)
                        }
                    }
                    mode == AudioManager.MODE_NORMAL -> {
                        if (callModeActive) {
                            callModeActive = false
                            // Call ended: re-lock unless a genuinely allowed
                            // app is in the foreground.
                            val top = latestResumedPackage()
                            if (top == null || !isAllowedWhileAllowedApp(top)) {
                                MonkModeManager.setState(this, MonkModeManager.MonkState.LOCKED)
                                MonkModeManager.lockNow(this)
                                showLockSurface()
                            }
                        }
                    }
                }
            }
            am.addOnModeChangedListener(mainExecutor, listener)
            audioListener = listener
        } catch (_: Exception) {
            // Listener registration failure -> UsageEvents tick still
            // polices the dialer path (emergency allow).
        }
    }

    // -----------------------------------------------------------------
    // Expiry — wall-clock authority, ≤60s re-check.
    // -----------------------------------------------------------------

    private val expiryCheck: Runnable = object : Runnable {
        override fun run() {
            if (!MonkModeManager.isActive(this@MonkModeLockService)) {
                MonkModeManager.deactivate(this@MonkModeLockService, "expiry")
                return
            }
            handler.postDelayed(this, EXPIRY_TICK_MS)
        }
    }

    private fun scheduleExpiryCheck() {
        handler.post(expiryCheck)
    }

    // -----------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped us away: re-kick via the guard pattern (the
        // persisted guard job also covers this — belt and suspenders).
        if (MonkModeManager.isActive(this)) {
            val restart = Intent(this, MonkModeLockService::class.java)
                .setAction(ACTION_RESTART)
            val pending = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1_500L,
                pending,
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
        // v2.5.5 audit fix M-10: unregister the audio-mode listener.
        audioListener?.let { l ->
            try {
                audioManager?.removeOnModeChangedListener(l)
            } catch (_: Exception) {
            }
        }
        audioListener = null
        isAlive = false
        super.onDestroy()
    }

    companion object {
        /** Process-local liveness (see ServiceRevival). */
        @Volatile
        var isAlive = false
            private set

        const val ACTION_STOP = "com.maxleveldetox.monk.STOP"
        const val ACTION_RESTART = "com.maxleveldetox.monk.RESTART"
        const val EXTRA_FRESH = "fresh"

        private const val TICK_LOCKED_MS = 1_500L
        private const val TICK_ALLOWED_APP_MS = 800L
        private const val EXPIRY_TICK_MS = 30_000L
        private const val EXIT_LOG_THROTTLE_MS = 60_000L

        fun start(context: Context, freshActivation: Boolean = false) {
            val intent = Intent(context, MonkModeLockService::class.java)
                .putExtra(EXTRA_FRESH, freshActivation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
