package com.maxleveldetox.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import org.json.JSONObject

/**
 * LockMyPhoneService — scheduled full-device lock (v2.0 Phase A4).
 *
 * Ported from the reference app's hardest-to-bypass mode, adapted to our
 * invariants:
 *
 *  - DeviceAdmin (force-lock only) → DevicePolicyManager.lockNow()
 *  - 1.5s re-lock loop while the screen is ON (button-mashing proof)
 *  - SCREEN_ON / USER_PRESENT receivers → instant re-lock + attempt log
 *  - Session persisted in EngineStateStore → boot / OEM-kill recovery
 *  - Ends ONLY at the scheduled end time (wall-clock authority, like our
 *    session engine — elapsedRealtime can't survive a reboot) or via the
 *    app's validated stop path (bailout coin spend).
 *
 * SECURITY INVARIANT: an ordinary user cannot end this session by
 * power-button-unlocking, recents-swiping, force-stopping or uninstalling
 * (interceptor). The only exits are time or the paid bailout.
 */
class LockMyPhoneService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: kotlinx.coroutines.Job? = null
    private lateinit var screenReceiver: ScreenStateReceiver

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VALIDATED -> {
                // Only via LockMyPhoneController.stopValidated (bailout).
                stopClean(markEnded = true, reason = "validated_stop")
                return START_NOT_STICKY
            }
            else -> {
                val app = application as MldApp
                if (app.engineState.serviceStartBlockedByBackoff(
                        EngineStateStore.SERVICE_LOCK_PHONE)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                app.engineState.recordServiceRestart(EngineStateStore.SERVICE_LOCK_PHONE)
                startAsForeground()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        // Screen-state re-enforcement. (v2.5 r9.2: drop a receiver left by an
        // earlier onStartCommand first — sticky restart + MldApp/boot start
        // can both arrive, and a leaked duplicate double-fired lockNow.)
        if (this::screenReceiver.isInitialized) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        screenReceiver = ScreenStateReceiver(this).also {
            registerReceiver(
                it,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
        }

        loopJob?.cancel()
        loopJob = scope.launch {
            val runStartedAt = System.currentTimeMillis()
            var restartsCleared = false
            var offCallStrikes = 0
            while (isActive) {
                val controller = LockMyPhoneController
                if (!controller.isSessionActive(this@LockMyPhoneService)) {
                    stopClean(markEnded = true, reason = "expired")
                    break
                }

                // v2.5 r9.2: a run that stayed up for a minute is a clean
                // run — reset the crash-loop counter (it used to only ever
                // grow, eventually refusing legitimate restarts).
                if (!restartsCleared &&
                    System.currentTimeMillis() - runStartedAt > CLEAN_RUN_AFTER_MS
                ) {
                    restartsCleared = true
                    try {
                        (application as MldApp).engineState.resetServiceRestarts(
                            EngineStateStore.SERVICE_LOCK_PHONE)
                    } catch (_: Exception) {
                    }
                }

                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive) {
                    if (isTelephonyCallActive()) {
                        // CALL MODE (Social Sentry LMP CALL_MODE parity): a
                        // real phone call must be answerable, dialable and
                        // hang-up-able — locking a ringing / in-call screen
                        // every 1.5 s makes emergencies unreachable. The
                        // lock is paused for the call, but the call is not a
                        // pass to use the phone: wandering to any other
                        // app during the call re-locks it.
                        val fg = currentForegroundPackage()
                        if (fg != null && !isCallSurface(fg)) {
                            offCallStrikes++
                            if (offCallStrikes >= 2) {
                                lockNow()
                                offCallStrikes = 0
                            }
                        } else {
                            offCallStrikes = 0
                        }
                    } else {
                        offCallStrikes = 0
                        // Lock NOW. Every 1.5s while the screen is
                        // interactive — there is no window where the phone
                        // is usable.
                        lockNow()
                        // Also count an unlock attempt: we saw the screen
                        // interactive while the session is enforcing.
                        controller.recordAttempt(this@LockMyPhoneService)
                    }
                }

                delay(RELOCK_INTERVAL_MS)
            }
        }
    }

    fun lockNow() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MldDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow()
            } catch (_: SecurityException) {
                // Admin stripped between check and call — onAdminStripped
                // will fire via the receiver callback.
            }
        } else {
            // Admin stripped mid-session: never silently continue.
            LockMyPhoneController.onAdminStripped(this)
        }
    }

    // -----------------------------------------------------------------
    // Call mode helpers (v2.5 r9.2)
    // -----------------------------------------------------------------

    /**
     * True while the telephony stack is ringing or in a call. Only the two
     * TELEPHONY audio modes count — MODE_IN_COMMUNICATION is deliberately
     * excluded because any VoIP app can set it, which would turn "start a
     * video call" into a universal unlock.
     */
    fun isTelephonyCallActive(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = am.mode
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
        } catch (_: Exception) {
            false
        }
    }

    /** Latest foreground package from the usage-event stream, or null when
     *  unknown (no usage access / no recent event) — unknown is treated as
     *  "call UI" so a call is never blocked by a detection gap. */
    private fun currentForegroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000L, now)
            val e = UsageEvents.Event()
            var fg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    fg = e.packageName
                }
            }
            fg
        } catch (_: Exception) {
            null
        }
    }

    /** Dialer / in-call / telecom / system surfaces that may stay on screen
     *  during a call. Deliberately narrow — no generic "phone" substring. */
    private fun isCallSurface(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == packageName ||
            p == "android" ||
            p == "com.android.systemui" ||
            p == "com.android.phone" ||
            p == "com.android.server.telecom" ||
            p.contains("dialer") ||
            p.contains("incallui") ||
            p.contains("callui") ||
            p.contains("telecom") ||
            p.contains("contacts")
    }

    // -----------------------------------------------------------------

    private fun buildNotification(): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remaining = LockMyPhoneController.remainingSeconds(this)
        val mins = remaining / 60
        val secs = remaining % 60
        return NotificationCompat.Builder(this, CH_LOCK)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(R.string.notif_lockphone_active, mins, secs)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_LOCK,
                    getString(R.string.channel_lockphone),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_lockphone_desc) }
            )
        }
    }

    private fun stopClean(markEnded: Boolean, reason: String) {
        loopJob?.cancel()
        loopJob = null
        if (this::screenReceiver.isInitialized) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        if (markEnded) {
            LockMyPhoneController.markEnded(this, reason)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Swiping the app away must not end the lock: re-arm a restart. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (LockMyPhoneController.isSessionActive(this)) {
                com.maxleveldetox.guard.ServiceRevival.scheduleServiceRestart(
                    this, LockMyPhoneService::class.java, 9411)
            }
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isAlive = false
        if (this::screenReceiver.isInitialized) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        // Unexpected death (OEM kill): persisted session + boot/guard
        // recovery re-arms the loop. START_STICKY also restarts us.
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ FGS timeout: the persisted session + guard job
        // re-arm the loop within 15 min (wall-clock authority).
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        // Android 14 variant — same degrade path.
        onTimeout(startId, -1)
    }

    // -----------------------------------------------------------------
    // Screen-state re-enforcement receiver
    // -----------------------------------------------------------------

    class ScreenStateReceiver(private val service: LockMyPhoneService) :
        BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> {
                    // User just woke/unlocked the device during a lock
                    // session: re-lock instantly and log the attempt —
                    // EXCEPT for a real call (an incoming call wakes the
                    // screen; re-locking it would hide the answer button).
                    if (!service.isTelephonyCallActive()) {
                        service.lockNow()
                        LockMyPhoneController.recordAttempt(service)
                    }
                }
            }
        }
    }

    companion object {
        private const val NOTIF_ID = 1003
        private const val CH_LOCK = "mld_lockphone"
        const val RELOCK_INTERVAL_MS = 1_500L

        /** Clean-run threshold after which the crash-loop counter resets. */
        private const val CLEAN_RUN_AFTER_MS = 60_000L

        /** Process-local liveness (see ServiceRevival). */
        @Volatile
        var isAlive = false
            private set
        const val ACTION_STOP_VALIDATED = "com.maxleveldetox.action.STOP_LOCKPHONE_VALIDATED"

        fun start(context: Context) {
            val intent = Intent(context, LockMyPhoneService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // v2.5.5 audit fix C-3 companion guard: a background process
                // start (widget/alarm broadcast) must never crash the caller
                // — the session is persisted and ServiceRevival / the guard
                // job re-arm the service on the next legit start.
            }
        }
    }
}

/**
 * LockMyPhoneController — session authority for lock-my-phone mode.
 *
 * Wall-clock authority (startWallMs + durationSeconds persisted in
 * EngineStateStore): unlike elapsedRealtime, wall clock survives reboot,
 * and boot recovery re-arms the service immediately.
 */
object LockMyPhoneController {

    private const val PREFS = "mld_lock_phone"
    private const val K_SESSION = "lock_session"

    fun start(context: Context, durationMinutes: Int, reason: String): Boolean =
        startSeconds(context, durationMinutes * 60, reason)

    /** Second-precision start (schedules start for the REMAINING window). */
    fun startSeconds(context: Context, durationSeconds: Int, reason: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("startWallMs", System.currentTimeMillis())
            put("durationSeconds", durationSeconds)
            put("reason", reason)
            put("attempts", 0)
            put("ended", false)
        }
        prefs.edit().putString(K_SESSION, json.toString()).apply()
        // v2.5 r9.2: make sure the persisted watchdog exists for this
        // session (it may have self-disabled after clean days).
        try {
            MldApp.get(context).engineState.setGuardsEnabled(true)
            com.maxleveldetox.guard.AccessibilityGuardJobService.schedule(context)
        } catch (_: Exception) {
        }
        // Chain the next scheduled window (alarm at this session's end).
        try {
            LockScheduler.rearm(context)
        } catch (_: Exception) {
        }
        try {
            LockMyPhoneService.start(context)
        } catch (_: Exception) {
            // Background start refused (API 31+): the session is persisted;
            // ServiceRevival / the guard job start the service later.
        }
        return true
    }

    fun isSessionActive(context: Context): Boolean {
        val json = sessionJson(context) ?: return false
        if (json.optBoolean("ended", true)) return false
        val endMs = json.optLong("startWallMs", 0L) +
            json.optLong("durationSeconds", 0L) * 1000L
        return System.currentTimeMillis() < endMs
    }

    fun remainingSeconds(context: Context): Int {
        val json = sessionJson(context) ?: return 0
        val endMs = json.optLong("startWallMs", 0L) +
            json.optLong("durationSeconds", 0L) * 1000
        return ((endMs - System.currentTimeMillis()) / 1000L)
            .coerceAtLeast(0L).toInt()
    }

    fun recordAttempt(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sessionJson(context) ?: return
        json.put("attempts", json.optInt("attempts", 0) + 1)
        prefs.edit().putString(K_SESSION, json.toString()).apply()
    }

    fun attempts(context: Context): Int =
        sessionJson(context)?.optInt("attempts", 0) ?: 0

    fun markEnded(context: Context, reason: String) {
        if (reason == "bailout" || reason == "validated_stop") {
            // A paid exit: schedules must not relock this same window.
            try {
                LockScheduler.noteUserStop(context)
            } catch (_: Exception) {
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sessionJson(context) ?: return
        json.put("ended", true)
        json.put("endReason", reason)
        json.put("endWallMs", System.currentTimeMillis())
        prefs.edit().putString(K_SESSION, json.toString()).apply()
    }

    /** Validated stop: called ONLY from the bailout path after the native
     *  coin spend succeeds (never from UI directly). */
    fun stopValidated(context: Context) {
        // v2.5.5 audit fix m-16: guarded — a background startService on a
        // dead service used to throw IllegalStateException.
        try {
            context.startService(
                Intent(context, LockMyPhoneService::class.java)
                    .setAction(LockMyPhoneService.ACTION_STOP_VALIDATED)
            )
        } catch (_: Exception) {
        }
    }

    /** Admin rights stripped mid-session: never silently continue — mark
     *  RECOVERY and let the guard notification drive re-enable. */
    fun onAdminStripped(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sessionJson(context) ?: return
        json.put("adminStrippedAtWallMs", System.currentTimeMillis())
        prefs.edit().putString(K_SESSION, json.toString()).apply()
    }

    fun adminStripped(context: Context): Boolean =
        sessionJson(context)?.has("adminStrippedAtWallMs") == true

    fun statusJson(context: Context): JSONObject = JSONObject().apply {
        val json = sessionJson(context)
        put("active", isSessionActive(context))
        put("remainingSeconds", remainingSeconds(context))
        put("attempts", attempts(context))
        put("adminStripped", adminStripped(context))
        put("reason", json?.optString("reason") ?: "")
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(
            ComponentName(context, MldDeviceAdminReceiver::class.java)
        )
    }

    /** One-tap activation from the app (user consents via system dialog). */
    fun requestAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, MldDeviceAdminReceiver::class.java),
            )
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.device_admin_explanation),
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!dpm.isAdminActive(ComponentName(context, MldDeviceAdminReceiver::class.java))) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun sessionJson(context: Context): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(K_SESSION, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }
}
