package com.maxleveldetox.reels

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * ReelsEscalationManager (v2.0 Phase B2 → v2.9.4 r20) — the burst
 * counter behind the shorts interception ladder:
 *
 *   attempt 1..4 (each < 50 s apart)  -> warning TOAST
 *   attempt 5 (rapid stretch)         -> 1-minute CAGE (EnforcementWall,
 *                                       in-memory, never persisted)
 *
 * QUOTA MODEL (time-based, not coins — coins are for session temp-unlock):
 *   - daily reels allowance (default 30 min, clamped 5..120): the
 *     settings screen's unlock buttons draw minutes from it
 *   - 3 emergency passes per day: independent 1-min unlocks for when
 *     the allowance is exhausted
 *   - per-package unblock window is reels-scoped: it does NOT weaken any
 *     Study/Detox session policy (the PolicyEngine never consults it)
 *
 * v2.9.4 r20: the old 10-second overlay lockout flow
 * (ReelsOverlayActivity + onHardLockoutFinished + ringtone/notification/
 * force-stop) was REMOVED — the activity was unreachable dead code since
 * r18 (nothing ever started it) and its removal is part of the
 * junk-clean-up. The escalation surface is the toast + burst cage.
 *
 * ANTI-BYPASS:
 *   - counters + quota persisted in DataStore; daily reset keyed by local
 *     date, not by app restarts
 *   - the 50 s consecutive-reset prevents toasting forever without ever
 *     escalating (attempts spaced ~1 min apart NEVER accumulate)
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
    }
}
