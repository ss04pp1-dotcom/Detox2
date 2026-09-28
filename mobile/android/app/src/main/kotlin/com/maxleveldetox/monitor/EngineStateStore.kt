package com.maxleveldetox.monitor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * EngineStateStore — hot-path persistence for the dual-engine enforcement
 * system (v2.0 Phase A1/A2).
 *
 * SharedPreferences (not DataStore) deliberately: the engine-2 poll loop and
 * the guard jobs read/write these values on an 850ms/15min cadence and need
 * synchronous, allocation-cheap access. DataStore's runBlocking reads would
 * be wasteful on the poll path (TRD §36 hot-path discipline).
 *
 * SECURITY INVARIANT: values written here are engine health facts, never
 * policy decisions. Policy always re-derives from StateRepository +
 * PolicyEngine at enforcement time.
 */
class EngineStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mld_engine", Context.MODE_PRIVATE)

    // -----------------------------------------------------------------
    // Engine-1 (accessibility) heartbeat
    // -----------------------------------------------------------------

    /** Accessibility service writes a wall-clock heartbeat on every event
     *  batch (throttled). Staleness beyond [STALE_AFTER_MS] = blackout. */
    fun writeEngine1Heartbeat(nowWallMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_ENGINE1_HEARTBEAT, nowWallMs).apply()
    }

    fun engine1HeartbeatMs(): Long = prefs.getLong(K_ENGINE1_HEARTBEAT, 0L)

    fun isEngine1Stale(nowWallMs: Long = System.currentTimeMillis()): Boolean {
        val last = engine1HeartbeatMs()
        if (last == 0L) return false // never connected yet — not a blackout
        return nowWallMs - last > STALE_AFTER_MS
    }

    // -----------------------------------------------------------------
    // Engine-2 (UsageStats foreground monitor) state
    // -----------------------------------------------------------------

    fun setEngine2Running(running: Boolean, nowWallMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(K_ENGINE2_RUNNING, running)
            .putLong(K_ENGINE2_STATE_AT, nowWallMs)
            .apply()
    }

    fun isEngine2Running(): Boolean = prefs.getBoolean(K_ENGINE2_RUNNING, false)

    fun engine2StartedAtMs(): Long = prefs.getLong(K_ENGINE2_STATE_AT, 0L)

    // -----------------------------------------------------------------
    // Guard health / clean-day streak (battery-aware self-disable)
    // -----------------------------------------------------------------

    fun recordGuardCheck(clean: Boolean, nowWallMs: Long = System.currentTimeMillis()) {
        val today = dayKey(nowWallMs)
        val lastCleanDay = prefs.getString(K_GUARD_LAST_CLEAN_DAY, null)
        if (clean) {
            if (lastCleanDay != today) {
                val streak = prefs.getInt(K_GUARD_CLEAN_STREAK, 0)
                // Consecutive-day continuation only if yesterday was clean.
                val continued = lastCleanDay != null && isNextDay(lastCleanDay, today)
                prefs.edit()
                    .putInt(K_GUARD_CLEAN_STREAK, if (continued) streak + 1 else 1)
                    .putString(K_GUARD_LAST_CLEAN_DAY, today)
                    .apply()
            }
        } else {
            // Any dirty check resets the streak and re-arms the guards.
            prefs.edit()
                .putInt(K_GUARD_CLEAN_STREAK, 0)
                .putString(K_GUARD_LAST_CLEAN_DAY, null)
                .apply()
        }
    }

    fun guardCleanStreakDays(): Int = prefs.getInt(K_GUARD_CLEAN_STREAK, 0)

    /** Guards self-disable after 3 consecutive fully-clean days to save
     *  battery; any incident re-arms them (recordGuardCheck(false)). */
    fun shouldGuardsSelfDisable(): Boolean = guardCleanStreakDays() >= GUARD_DISABLE_AFTER_CLEAN_DAYS

    fun setGuardsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(K_GUARDS_ENABLED, enabled).apply()
    }

    fun areGuardsEnabled(): Boolean = prefs.getBoolean(K_GUARDS_ENABLED, true)

    // -----------------------------------------------------------------
    // FGS crash-loop restart tracking (Phase A5)
    // -----------------------------------------------------------------

    fun recordServiceRestart(serviceKey: String, nowWallMs: Long = System.currentTimeMillis()): Int {
        val count = prefs.getInt(K_RESTART_COUNT + serviceKey, 0) + 1
        prefs.edit()
            .putInt(K_RESTART_COUNT + serviceKey, count)
            .putLong(K_RESTART_LAST_AT + serviceKey, nowWallMs)
            .apply()
        return count
    }

    fun serviceRestartCount(serviceKey: String): Int =
        prefs.getInt(K_RESTART_COUNT + serviceKey, 0)

    fun resetServiceRestarts(serviceKey: String) {
        prefs.edit().remove(K_RESTART_COUNT + serviceKey).apply()
    }

    /** Exponential backoff gate: 40s × 2^(n-1), capped at 15 min. After 6
     *  attempts within the crash window we refuse further auto-restarts and
     *  escalate to the user instead of looping (Phase A5 policy). */
    fun serviceStartBlockedByBackoff(serviceKey: String, nowWallMs: Long = System.currentTimeMillis()): Boolean {
        val count = serviceRestartCount(serviceKey)
        if (count == 0) return false
        val lastAt = prefs.getLong(K_RESTART_LAST_AT + serviceKey, 0L)
        if (nowWallMs - lastAt > CRASH_WINDOW_MS) {
            // Outside the crash window — previous attempts aged out.
            resetServiceRestarts(serviceKey)
            return false
        }
        if (count >= MAX_RESTART_ATTEMPTS) return true
        val backoffMs = (INITIAL_BACKOFF_MS shl (count - 1).coerceAtMost(5))
            .coerceAtMost(MAX_BACKOFF_MS)
        return nowWallMs - lastAt < backoffMs
    }

    // -----------------------------------------------------------------
    // Engine status snapshot (bridge/state-stream payload)
    // -----------------------------------------------------------------

    fun statusJson(): JSONObject = JSONObject().apply {
        put("engine1HeartbeatAgeMs",
            if (engine1HeartbeatMs() == 0L) -1
            else System.currentTimeMillis() - engine1HeartbeatMs())
        put("engine1Stale", isEngine1Stale())
        put("engine2Running", isEngine2Running())
        put("guardsEnabled", areGuardsEnabled())
        put("guardCleanStreakDays", guardCleanStreakDays())
    }

    // -----------------------------------------------------------------

    private fun dayKey(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return "${cal.get(java.util.Calendar.YEAR)}-" +
            "${cal.get(java.util.Calendar.MONTH) + 1}-" +
            "${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    private fun isNextDay(prevDayKey: String, todayKey: String): Boolean {
        val sdf = java.text.SimpleDateFormat("yyyy-M-d", java.util.Locale.ROOT)
        return try {
            // v2.5.7 (L-2): safe-call instead of `!!` — a malformed stored
            // day key (clock reset, corrupt prefs) must degrade to "not the
            // next day", never an NPE.
            val prev = sdf.parse(prevDayKey) ?: return false
            val cal = java.util.Calendar.getInstance().apply { time = prev }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val next = dayKey(cal.timeInMillis)
            next == todayKey
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val K_ENGINE1_HEARTBEAT = "engine1_heartbeat_ms"
        private const val K_ENGINE2_RUNNING = "engine2_running"
        private const val K_ENGINE2_STATE_AT = "engine2_state_at_ms"
        private const val K_GUARD_CLEAN_STREAK = "guard_clean_streak"
        private const val K_GUARD_LAST_CLEAN_DAY = "guard_last_clean_day"
        private const val K_GUARDS_ENABLED = "guards_enabled"
        private const val K_RESTART_COUNT = "restart_count_"
        private const val K_RESTART_LAST_AT = "restart_last_at_"

        /** 60s without an accessibility heartbeat = suspected OEM kill
         *  (the "blackout" case — permission still granted, process dead). */
        const val STALE_AFTER_MS = 60_000L

        const val GUARD_DISABLE_AFTER_CLEAN_DAYS = 3

        const val SERVICE_ENFORCEMENT = "enforcement"
        const val SERVICE_ENGINE2 = "engine2"
        const val SERVICE_LOCK_PHONE = "lock_phone"

        private const val INITIAL_BACKOFF_MS = 40_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L
        private const val MAX_RESTART_ATTEMPTS = 6
        private const val CRASH_WINDOW_MS = 30 * 60_000L
    }
}
