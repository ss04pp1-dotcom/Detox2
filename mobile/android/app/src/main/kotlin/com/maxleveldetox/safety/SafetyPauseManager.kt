package com.maxleveldetox.safety

import android.content.Context
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * SafetyPauseManager (v2.0 Phase B4) — the "pause before you scroll".
 *
 * Ported from the reference app's Safety Mode (report-modes.md §1.6/§5):
 * a short, FULL-SCREEN countdown (3..60s, default 5s) shown when the
 * user opens a configured distracting app. By DESIGN this is friction,
 * not blockade:
 *
 *   - the countdown releases automatically — nothing is required
 *   - BACK is swallowed but the pause expires on its own
 *   - no DeviceAdmin, no force-stop, no violation escalation
 *   - 30s re-trigger suppression so app-switching is not punished twice
 *
 * The behavioral lever is the moment of reflection, not a wall — the
 * reference app itself rates this mechanic strength 2/5 (intentional).
 *
 * PRIVACY: package names only; no screen content is involved.
 */
class SafetyPauseManager(
    private val context: Context,
    private val stateRepo: StateRepository,
) {

    data class SafetyState(
        val enabled: Boolean = false,
        val apps: Set<String> = emptySet(),
        val pauseSeconds: Int = DEFAULT_PAUSE_SECONDS,
    ) {
        companion object {
            val DEFAULT = SafetyState()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** In-memory re-trigger suppression (per app, 30s). */
    private val lastShownAt = HashMap<String, Long>()

    // -----------------------------------------------------------------
    // Decision (a11y hot path — cheap)
    // -----------------------------------------------------------------

    /** True when opening [pkg] should trigger the pause right now. */
    fun shouldPause(pkg: String): Boolean {
        val state = stateRepo.blockingSafety()
        if (!state.enabled || pkg !in state.apps) return false
        val last = synchronized(lastShownAt) { lastShownAt[pkg] } ?: 0L
        return SystemClockNow.elapsed - last > SUPPRESSION_MS
    }

    /** Called when the pause surface actually went up. */
    fun recordShown(pkg: String) {
        synchronized(lastShownAt) { lastShownAt[pkg] = SystemClockNow.elapsed }
        scope.launch {
            val state = stateRepo.blockingSafety()
            stateRepo.saveSafety(state) // round-trips weekly counters below
            bumpWeeklyCount(shown = true)
        }
    }

    /** The user bailed out of the pause before it finished (insight only). */
    fun recordClosedEarly(pkg: String) {
        scope.launch { bumpWeeklyCount(shown = false) }
    }

    /** v2.1 Phase C: the user waited the full pause — a resisted impulse. */
    fun recordCompleted(pkg: String) {
        scope.launch {
            try {
                (context.applicationContext as? com.maxleveldetox.MldApp)
                    ?.progressEngine?.onSafetyPauseCompleted()
            } catch (_: Exception) {
            }
        }
    }

    // -----------------------------------------------------------------
    // Settings surface
    // -----------------------------------------------------------------

    suspend fun setEnabled(enabled: Boolean) {
        val state = stateRepo.blockingSafety()
        stateRepo.saveSafety(state.copy(enabled = enabled))
    }

    suspend fun setApps(apps: Set<String>) {
        val state = stateRepo.blockingSafety()
        stateRepo.saveSafety(state.copy(apps = apps))
    }

    /** Clamp 3..60s. */
    suspend fun setPauseSeconds(seconds: Int): Boolean {
        if (seconds < 3 || seconds > 60) return false
        val state = stateRepo.blockingSafety()
        stateRepo.saveSafety(state.copy(pauseSeconds = seconds))
        return true
    }

    fun statusJson(): JSONObject {
        val state = stateRepo.blockingSafety()
        return JSONObject().apply {
            put("enabled", state.enabled)
            put("apps", org.json.JSONArray(state.apps.toList()))
            put("pauseSeconds", state.pauseSeconds)
        }
    }

    /** Persisted pause seconds (read by the pause activity). */
    fun statePauseSeconds(): Int = stateRepo.blockingSafety().pauseSeconds

    // -----------------------------------------------------------------
    // Weekly insight counters (kept in the same DataStore payload —
    // "shownThisWeek"/"closedThisWeek" with a week-anchor date key).
    // -----------------------------------------------------------------

    private suspend fun bumpWeeklyCount(shown: Boolean) {
        try {
            val prefsKey = if (shown) "shownThisWeek" else "closedThisWeek"
            val anchor = weekAnchorKey()
            val raw = stateRepo.blockingSafetyCountersRaw() ?: return
            val json = JSONObject(raw)
            if (json.optString("weekAnchor") != anchor) {
                json.put("weekAnchor", anchor)
                json.put("shownThisWeek", 0)
                json.put("closedThisWeek", 0)
            }
            json.put(prefsKey, json.optInt(prefsKey, 0) + 1)
            stateRepo.saveSafetyCountersRaw(json.toString())
        } catch (_: Exception) {
        }
    }

    private fun weekAnchorKey(): String {
        val cal = java.util.Calendar.getInstance()
        val week = cal.get(java.util.Calendar.WEEK_OF_YEAR)
        val year = cal.get(java.util.Calendar.YEAR)
        return "$year-W$week"
    }

    companion object {
        const val DEFAULT_PAUSE_SECONDS = 5
        const val SUPPRESSION_MS = 30_000L
    }
}
