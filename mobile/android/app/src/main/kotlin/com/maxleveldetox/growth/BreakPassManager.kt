package com.maxleveldetox.growth

import android.content.Context
import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.MldApp
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * BreakPassManager (v2.2 Phase D, port-plan item 20 — the ethical rework of
 * the competitor's "pause allowance").
 *
 * Weekly-capped 5-minute standing-down windows for the reels blocker,
 * designed to make breaks PLANNED instead of impulsive:
 *
 *   - allowance: `breakPassesPerWeek` (remote-config bounded 0..5, default 2)
 *   - one pass = one 5-minute window on one package
 *   - REFUSED while a Study/Detox session, Cage, Prime commit or Monk mode
 *     is active — those flows have their own, stricter economics
 *   - no coins, no DP, no punishment: a pass is a legitimate tool, and the
 *     reels escalation counter resets exactly like a paid unlock would
 *   - lazy self-expiry on elapsedRealtime (Phase B pattern), survives
 *     reboots as "expired" (wall-clock honesty, never re-armed)
 */
class BreakPassManager(
    private val context: Context,
    private val stateRepo: StateRepository,
    private val runtimeConfig: RuntimeConfig,
) {

    data class BreakPassState(
        val weekAnchor: String = "",
        val usedThisWeek: Int = 0,
        val activeStartElapsed: Long = 0L,
        val activeEndElapsed: Long = 0L,
        val activePackage: String = "",
    ) {
        val hasActiveWindow: Boolean
            get() = activePackage.isNotEmpty() &&
                SystemClockNow.elapsed < activeEndElapsed

        fun toJson(): JSONObject = JSONObject().apply {
            put("weekAnchor", weekAnchor)
            put("usedThisWeek", usedThisWeek)
            put("activeStartElapsed", activeStartElapsed)
            put("activeEndElapsed", activeEndElapsed)
            put("activePackage", activePackage)
        }

        companion object {
            fun fromJson(raw: String?): BreakPassState {
                if (raw.isNullOrBlank()) return BreakPassState()
                return try {
                    val json = JSONObject(raw)
                    BreakPassState(
                        weekAnchor = json.optString("weekAnchor", ""),
                        usedThisWeek = json.optInt("usedThisWeek", 0),
                        activeStartElapsed = json.optLong("activeStartElapsed", 0L),
                        activeEndElapsed = json.optLong("activeEndElapsed", 0L),
                        activePackage = json.optString("activePackage", ""),
                    )
                } catch (_: Exception) {
                    BreakPassState()
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Parse the persisted break-pass state (String? -> BreakPassState). */
    private fun currentState(): BreakPassState =
        BreakPassState.fromJson(stateRepo.blockingBreakPasses())

    // -----------------------------------------------------------------
    // Status (bridge-shaped)
    // -----------------------------------------------------------------

    fun passesRemaining(): Int {
        val state = rolloverWeekIfNeeded(currentState())
        val allowance = runtimeConfig.current().breakPassesPerWeek
        return (allowance - state.usedThisWeek).coerceAtLeast(0)
    }

    fun isBreakActive(pkg: String): Boolean {
        val state = currentState()
        return state.hasActiveWindow && state.activePackage == pkg
    }

    fun statusJson(): JSONObject {
        val state = rolloverWeekIfNeeded(currentState())
        val cfg = runtimeConfig.current().breakPassesPerWeek
        val activeRemainingSeconds = if (state.hasActiveWindow) {
            ((state.activeEndElapsed - SystemClockNow.elapsed) / 1000L).toInt()
        } else 0
        return JSONObject().apply {
            put("allowancePerWeek", cfg)
            put("usedThisWeek", state.usedThisWeek)
            put("remaining", (cfg - state.usedThisWeek).coerceAtLeast(0))
            put("windowSeconds", PASS_WINDOW_SECONDS)
            put("activePackage", if (state.hasActiveWindow) state.activePackage else "")
            put("activeRemainingSeconds", activeRemainingSeconds)
        }
    }

    // -----------------------------------------------------------------
    // Using a pass
    // -----------------------------------------------------------------

    data class PassResult(
        val ok: Boolean,
        val errorCode: String?,
        val message: String?,
        val remainingSeconds: Int = 0,
    )

    suspend fun usePass(pkg: String): PassResult = mutex.withLock {
        if (pkg.isBlank()) {
            return PassResult(false, "INVALID_REQUEST", "package required", 0)
        }

        // Hard gates: never undercut an active enforcement mode.
        if (sessionEnforcing()) {
            return PassResult(false, "SESSION_ACTIVE",
                "Finish the active session first — break passes work between sessions.", 0)
        }
        val cage = stateRepo.blockingCage()
        if (cage.active && !cage.isExpired(SystemClockNow.elapsed)) {
            return PassResult(false, "CAGE_ACTIVE",
                "The Cage is active. Break passes do not apply.", 0)
        }
        if ((context.applicationContext as? MldApp)?.primeCommit?.isCommitActive() == true) {
            return PassResult(false, "PRIME_ACTIVE",
                "A Prime commit is running. Break passes do not apply.", 0)
        }
        if (com.maxleveldetox.monk.MonkModeManager.isActive(context)) {
            return PassResult(false, "MONK_ACTIVE",
                "Monk mode is running. Break passes do not apply.", 0)
        }

        val state = rolloverWeekIfNeeded(currentState())
        if (state.hasActiveWindow) {
            return PassResult(false, "BREAK_ACTIVE",
                "A break window is already open.", remainingSeconds(state))
        }
        val allowance = runtimeConfig.current().breakPassesPerWeek
        if (allowance <= 0 || state.usedThisWeek >= allowance) {
            return PassResult(false, "ALLOWANCE_EXHAUSTED",
                "No break passes left this week. They reset Monday.", 0)
        }

        val now = SystemClockNow.elapsed
        val next = state.copy(
            usedThisWeek = state.usedThisWeek + 1,
            activeStartElapsed = now,
            activeEndElapsed = now + TimeUnit.SECONDS.toMillis(PASS_WINDOW_SECONDS.toLong()),
            activePackage = pkg,
        )
        stateRepo.saveBreakPasses(next.toJson().toString())
        return PassResult(true, null, null, PASS_WINDOW_SECONDS)
    }

    /** End the window early (user tapped "end break" — honesty is free). */
    suspend fun endBreakEarly() = mutex.withLock {
        val state = currentState()
        if (state.hasActiveWindow) {
            stateRepo.saveBreakPasses(
                state.copy(activeEndElapsed = SystemClockNow.elapsed).toJson().toString())
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun remainingSeconds(state: BreakPassState): Int {
        if (!state.hasActiveWindow) return 0
        return ((state.activeEndElapsed - SystemClockNow.elapsed) / 1000L)
            .coerceAtLeast(0L).toInt()
    }

    private fun sessionEnforcing(): Boolean {
        val session = stateRepo.blockingSession() ?: return false
        return session.status.isEnforcing
    }

    private fun rolloverWeekIfNeeded(state: BreakPassState): BreakPassState {
        val currentWeek = weekAnchorNow()
        if (state.weekAnchor == currentWeek) return state
        val rolled = state.copy(weekAnchor = currentWeek, usedThisWeek = 0)
        // Persist lazily-read rollovers so week boundaries survive restarts.
        scope.launch {
            try {
                stateRepo.saveBreakPasses(rolled.toJson().toString())
            } catch (_: Exception) {
            }
        }
        return rolled
    }

    companion object {
        const val PASS_WINDOW_SECONDS = 300 // 5 minutes, fixed by design

        /** ISO week key, e.g. "2026-W38". Weeks start Monday (Calendar default US). */
        fun weekAnchorNow(): String {
            val cal = Calendar.getInstance()
            val weekFormat = SimpleDateFormat("yyyy-'W'ww", Locale.US)
            return weekFormat.format(Date(cal.timeInMillis))
        }
    }
}
