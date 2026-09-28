package com.maxleveldetox.reels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.ViolationManager
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * ReelsEscalationManager (v2.0 Phase B2) — the de-escalation friction
 * ladder, ported from the reference app (report-modes.md §3) with our
 * honesty constraints:
 *
 *   attempt 1 (within 60s)  -> warning TOAST
 *   attempt 2                -> SOFT overlay with 1-min / 2-min unlock
 *                                buttons (spends the daily reels allowance)
 *   attempt >= 3             -> HARD overlay: 10-second countdown, then
 *                                ringtone + force-stop of the offending app
 *
 * QUOTA MODEL (time-based, not coins — coins are for session temp-unlock):
 *   - daily reels allowance (default 30 min, clamped 5..120): soft unlocks
 *     draw minutes from it
 *   - 3 emergency passes per day: independent 1-min unlocks for when the
 *     allowance is exhausted
 *   - per-package unblock window is reels-scoped: it does NOT weaken any
 *     Study/Detox session policy (the PolicyEngine never consults it)
 *
 * ANTI-BYPASS:
 *   - counters + quota persisted in DataStore; daily reset keyed by local
 *     date, not by app restarts
 *   - the 60s consecutive-reset prevents toasting forever without ever
 *     escalating; the hard lockout clears the counter only AFTER the full
 *     10s countdown completes (inside ReelsOverlayActivity)
 *   - unblock windows use elapsedRealtime — changing the system clock
 *     cannot extend them
 */
class ReelsEscalationManager(
    private val context: Context,
    private val stateRepo: StateRepository,
    private val violationManager: ViolationManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------
    // Persisted state model
    // -----------------------------------------------------------------

    data class ReelsState(
        val consecutiveCount: Int = 0,
        val lastBlockElapsed: Long = 0L,
        val hardLockoutCountToday: Int = 0,
        val emergencyPassesUsedToday: Int = 0,
        val allowanceRemainingMs: Long = DEFAULT_ALLOWANCE_MINUTES * 60_000L,
        val unblockStartElapsed: Long = 0L,
        val unblockEndElapsed: Long = 0L,
        val unblockPackage: String = "",
        val dateKey: String = "",
        val dailyLimitMinutes: Int = DEFAULT_ALLOWANCE_MINUTES,
    ) {
        val hasActiveUnblock: Boolean
            get() = unblockPackage.isNotEmpty() &&
                SystemClockNow.elapsed < unblockEndElapsed
    }

    enum class Step { TOAST, SOFT, HARD }

    data class Escalation(
        val step: Step,
        val count: Int,
        val hardLockoutCountToday: Int,
        val allowanceRemainingMinutes: Int,
        val emergencyPassesRemaining: Int,
    )

    // -----------------------------------------------------------------
    // Escalation entry point (called by the accessibility service)
    // -----------------------------------------------------------------

    /**
     * Records a confirmed detection and returns the escalation step for
     * the caller to present. All state mutation is persisted before the
     * callback fires (write-before-state, TRD §94). The callback is
     * delivered on the MAIN thread — the surfaces shown (toast, overlay
     * activities) require a looper.
     */
    fun onDetection(
        pkg: String,
        strategy: String,
        onEscalate: (Escalation) -> Unit,
    ) {
        scope.launch {
            val esc = escalateLocked(pkg) ?: return@launch
            violationManager.record(
                sessionId = "reels",
                pkg = pkg,
                type = com.maxleveldetox.enforcement.ViolationType.SHORTS_ENTRY,
                severity = if (esc.step == Step.HARD) "HIGH" else "MEDIUM",
                warningNumber = esc.count,
                action = "reels_${esc.step.name.lowercase()}:$strategy",
            )
            // v2.1 Phase C: an intercepted reel is a won moment (+1 DP,
            // internally throttled against farming).
            try {
                (context.applicationContext as? com.maxleveldetox.MldApp)
                    ?.progressEngine?.onReelsBlocked(pkg)
            } catch (_: Exception) {
            }
            mainHandler.post { onEscalate(esc) }
        }
    }

    private suspend fun escalateLocked(pkg: String): Escalation? = mutex.withLock {
        var state = rolloverDateIfNeeded(stateRepo.blockingReels())

        // Consecutive-counter reset after a quiet minute.
        val now = SystemClockNow.elapsed
        if (now - state.lastBlockElapsed > RESET_TIMEOUT_MS) {
            state = state.copy(consecutiveCount = 0)
        }

        // While a reels-scoped unlock window is open for THIS package the
        // blocker stands down entirely (the designed escape hatch).
        if (state.hasActiveUnblock && state.unblockPackage == pkg) return null

        val next = state.consecutiveCount + 1
        state = state.copy(
            consecutiveCount = next,
            lastBlockElapsed = now,
        )

        val step = when {
            next >= HARD_THRESHOLD -> {
                state = state.copy(hardLockoutCountToday = state.hardLockoutCountToday + 1)
                Step.HARD
            }
            else -> Step.TOAST
        }

        stateRepo.saveReels(state)

        Escalation(
            step = step,
            count = next,
            hardLockoutCountToday = state.hardLockoutCountToday,
            allowanceRemainingMinutes = (state.allowanceRemainingMs / 60_000L).toInt(),
            emergencyPassesRemaining = (EMERGENCY_PASSES_PER_DAY - state.emergencyPassesUsedToday),
        )
    }

    // -----------------------------------------------------------------
    // Unlock windows (soft-unlock minutes + emergency passes)
    // -----------------------------------------------------------------

    data class UnlockResult(
        val ok: Boolean,
        val errorCode: String?,
        val message: String?,
        val remainingSeconds: Int = 0,
    )

    /** Soft overlay buttons: spend 1 or 2 allowance minutes. */
    suspend fun requestSoftUnlock(pkg: String, minutes: Int): UnlockResult = mutex.withLock {
        if (minutes != 1 && minutes != 2) {
            return UnlockResult(false, "INVALID_REQUEST", "Only 1 or 2 minute unlocks.", 0)
        }
        val state = rolloverDateIfNeeded(stateRepo.blockingReels())
        if (state.hasActiveUnblock) {
            return UnlockResult(false, "TEMP_UNLOCK_ACTIVE", "An unlock window is already running.", 0)
        }
        val costMs = minutes * 60_000L
        if (state.allowanceRemainingMs < costMs) {
            return UnlockResult(
                false, "ALLOWANCE_EXHAUSTED",
                "Daily reels allowance used. Emergency passes remain: " +
                    "${EMERGENCY_PASSES_PER_DAY - state.emergencyPassesUsedToday}.", 0,
            )
        }
        val now = SystemClockNow.elapsed
        val next = state.copy(
            allowanceRemainingMs = state.allowanceRemainingMs - costMs,
            unblockStartElapsed = now,
            unblockEndElapsed = now + costMs,
            unblockPackage = pkg,
            consecutiveCount = 0, // fresh start after paying the cost
        )
        stateRepo.saveReels(next)
        return UnlockResult(true, null, null, minutes * 60)
    }

    /** Emergency passes: 3 per day, 1 minute each, independent of the
     *  allowance (for genuine one-off needs). */
    suspend fun useEmergencyPass(pkg: String): UnlockResult = mutex.withLock {
        val state = rolloverDateIfNeeded(stateRepo.blockingReels())
        if (state.hasActiveUnblock) {
            return UnlockResult(false, "TEMP_UNLOCK_ACTIVE", "An unlock window is already running.", 0)
        }
        if (state.emergencyPassesUsedToday >= EMERGENCY_PASSES_PER_DAY) {
            return UnlockResult(false, "ALLOWANCE_EXHAUSTED",
                "All $EMERGENCY_PASSES_PER_DAY emergency passes are used today.", 0)
        }
        val now = SystemClockNow.elapsed
        val next = state.copy(
            emergencyPassesUsedToday = state.emergencyPassesUsedToday + 1,
            unblockStartElapsed = now,
            unblockEndElapsed = now + 60_000L,
            unblockPackage = pkg,
            consecutiveCount = 0,
        )
        stateRepo.saveReels(next)
        return UnlockResult(true, null, null, 60)
    }

    /** True while the reels blocker stands down for this package. */
    fun isUnblocked(pkg: String): Boolean {
        val state = stateRepo.blockingReels()
        return state.hasActiveUnblock && state.unblockPackage == pkg
    }

    // -----------------------------------------------------------------
    // Hard lockout completion (called by ReelsOverlayActivity countdown)
    // -----------------------------------------------------------------

    /**
     * The 10s countdown completed in the foreground: clear the consecutive
     * counter, play the (shaming) ringtone, post the completion
     * notification and force-stop the offending app.
     */
    fun onHardLockoutFinished(pkg: String) {
        scope.launch {
            mutex.withLock {
                val state = rolloverDateIfNeeded(stateRepo.blockingReels())
                stateRepo.saveReels(state.copy(consecutiveCount = 0))
            }
            violationManager.record(
                sessionId = "reels",
                pkg = pkg,
                type = com.maxleveldetox.enforcement.ViolationType.SHORTS_ENTRY,
                severity = "HIGH",
                warningNumber = 0,
                action = "reels_hard_lockout_served",
            )
            playLockoutFinishedRingtone()
            postLockoutFinishedNotification()
            // v2.7 r13 (user-requested): even the hard lockout stays inside
            // the offending app — navigate to its safe surface (YouTube
            // Home / FB Feed / IG Feed) instead of killing it. Force-stop
            // remains the fallback for platforms with no safe surface
            // (TikTok / browser shorts tabs).
            if (!com.maxleveldetox.reels.ReelsRedirect.navigateFromContext(context, pkg)) {
                forceStopPackage(pkg)
            }
        }
    }

    private fun playLockoutFinishedRingtone() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return // silent/vibrate — respect it
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (_: Exception) {
            // ringtone is a shaming nicety, never a security dependency
        }
    }

    private fun postLockoutFinishedNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_REELS, context.getString(R.string.channel_reels),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.channel_reels_desc)
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_REELS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(context.getString(R.string.notif_reels_lockout_done_title))
                .setContentText(context.getString(R.string.notif_reels_lockout_done_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_LOCKOUT_DONE, notif)
        } catch (_: Exception) {
        }
    }

    private fun forceStopPackage(pkg: String) {
        // HOME first (via the service that detected us), then kill the
        // background process so relaunching takes real intent.
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
        } catch (_: Exception) {
        }
    }

    // -----------------------------------------------------------------
    // Daily rollover + status projection
    // -----------------------------------------------------------------

    private fun rolloverDateIfNeeded(state: ReelsState): ReelsState {
        val today = dateKey()
        if (state.dateKey == today) return state
        return state.copy(
            dateKey = today,
            hardLockoutCountToday = 0,
            emergencyPassesUsedToday = 0,
            allowanceRemainingMs = state.dailyLimitMinutes * 60_000L,
        )
    }

    fun statusJson(): JSONObject {
        val state = rolloverDateIfNeeded(stateRepo.blockingReels())
        return JSONObject().apply {
            put("consecutiveCount", state.consecutiveCount)
            put("hardLockoutCountToday", state.hardLockoutCountToday)
            put("emergencyPassesRemaining",
                (EMERGENCY_PASSES_PER_DAY - state.emergencyPassesUsedToday).coerceAtLeast(0))
            put("allowanceRemainingMinutes", (state.allowanceRemainingMs / 60_000L).toInt())
            put("dailyLimitMinutes", state.dailyLimitMinutes)
            put("unblockActive", state.hasActiveUnblock)
            put("unblockPackage", state.unblockPackage)
            put("unblockRemainingSeconds",
                ((state.unblockEndElapsed - SystemClockNow.elapsed) / 1000L).coerceAtLeast(0).toInt())
        }
    }

    /** Settings hook (clamped 5..120). */
    suspend fun setDailyLimitMinutes(minutes: Int): Boolean {
        if (minutes < 5 || minutes > 120) return false
        mutex.withLock {
            val state = rolloverDateIfNeeded(stateRepo.blockingReels())
            stateRepo.saveReels(state.copy(dailyLimitMinutes = minutes))
        }
        return true
    }

    private fun dateKey(): String {
        // Local-date key (same format as SessionEngine.dateKeyFor).
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        // v2.9.2 r18 (user-requested burst semantics): attempts spaced
        // ~1 minute apart must NEVER accumulate to the cage — only a rapid
        // burst ("ak tana" — 5 entries in one continuous stretch, each
        // less than ~50 s after the previous) escalates. A quiet gap
        // longer than the window resets the counter completely.
        const val RESET_TIMEOUT_MS = 50_000L          // consecutive window
        const val HARD_THRESHOLD = 5                  // 5 rapid attempts -> 1-minute cage lockout
        const val EMERGENCY_PASSES_PER_DAY = 3
        const val DEFAULT_ALLOWANCE_MINUTES = 30
        const val CHANNEL_REELS = "mld_reels"
        const val NOTIF_ID_LOCKOUT_DONE = 4101
    }
}
