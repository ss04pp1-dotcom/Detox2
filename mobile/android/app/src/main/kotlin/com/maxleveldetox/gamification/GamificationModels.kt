package com.maxleveldetox.gamification

import org.json.JSONObject

/**
 * GamificationModels (v2.1 Phase C) — the Progress layer data shapes.
 *
 * DESIGN INVARIANT (SECURITY_MODEL §"Progress layer"): the gamification
 * layer is a PARALLEL, read-mostly observer of enforcement. It never gates,
 * weakens, extends or terminates enforcement. Its only writes are to its own
 * state (DataStore) and its own Room tables. A total corruption of this
 * layer must leave every Phase A/B enforcement guarantee intact.
 *
 * DP ("Discipline Points") is a progression currency, NOT a spendable one —
 * it can never buy unlocks, bailouts or time. Coins remain the only economy
 * that interacts with enforcement escape hatches.
 */

// ---------------------------------------------------------------------------
// Level curve — lifetime DP, never decays (honest achievement)
// ---------------------------------------------------------------------------

enum class MldLevel(val minDp: Int, val display: String) {
    UNARMED(0, "Unarmed"),
    INITIATE(100, "Initiate"),
    DISCIPLE(250, "Disciple"),
    OPERATOR(450, "Operator"),
    FOCUSED(750, "Focused"),
    DEDICATED(1150, "Dedicated"),
    DISCIPLINED(1700, "Disciplined"),
    RELENTLESS(2400, "Relentless"),
    UNBREAKABLE(3300, "Unbreakable"),
    ASCENDED(4500, "Ascended"),
    MAXLEVEL(6000, "MAXLEVEL");

    companion object {
        fun forLifetimeDp(dp: Int): MldLevel {
            var level = UNARMED
            for (l in entries) if (dp >= l.minDp) level = l
            return level
        }

        fun next(lifetimeDp: Int): MldLevel? =
            entries.firstOrNull { it.minDp > lifetimeDp }
    }
}

// ---------------------------------------------------------------------------
// Award reasons — multiplier class + default amounts
// ---------------------------------------------------------------------------

enum class DpClass { FOCUS, REELS, NEUTRAL }

enum class DpReason(val defaultAmount: Int, val dpClass: DpClass, val oneTimeKey: String? = null) {
    SESSION_COMPLETED(10, DpClass.FOCUS),
    MONK_COMPLETED(10, DpClass.FOCUS),
    PRIME_COMPLETED(25, DpClass.FOCUS),
    CAGE_SURVIVED(15, DpClass.NEUTRAL),
    ALARM_COMPLETED(5, DpClass.NEUTRAL),
    SAFETY_PAUSE_COMPLETED(2, DpClass.NEUTRAL),
    REELS_BLOCKED(1, DpClass.REELS),
    CLEAN_DAY(25, DpClass.NEUTRAL),
    CHECK_IN(0, DpClass.NEUTRAL),          // amount = check-in cycle table
    STREAK_MILESTONE(0, DpClass.NEUTRAL),  // amount = milestone table (uncapped)
    FEATURE_SHORTS(10, DpClass.NEUTRAL, "shorts"),
    FEATURE_SAFETY(10, DpClass.NEUTRAL, "safety"),
    FEATURE_MONK(15, DpClass.NEUTRAL, "monk"),
    FEATURE_PRIME(20, DpClass.NEUTRAL, "prime"),
    FEATURE_ALARM(10, DpClass.NEUTRAL, "alarm"),
    FEATURE_WIDGET(10, DpClass.NEUTRAL, "widget"),  // v2.2 Phase D
    // v2.5 r9 — Social Sentry task/routine economy (tasks +8, subtask +2,
    // routine +4, discipline combo +2 — report-engagement §4c).
    TASK_COMPLETED(8, DpClass.FOCUS),
    SUBTASK_COMPLETED(2, DpClass.FOCUS),
    ROUTINE_COMPLETED(4, DpClass.FOCUS),
    // Daily gate handled by TasksEngine (date-keyed), not the lifetime
    // oneTimeKey flag.
    DISCIPLINE_COMBO(2, DpClass.FOCUS),
}

/** One-time streak milestones (uncapped awards). */
object StreakMilestones {
    val TABLE = listOf(3 to 30, 7 to 50, 14 to 75, 30 to 150, 60 to 200, 100 to 300)
    fun dpFor(days: Int): Int = TABLE.firstOrNull { it.first == days }?.second ?: 0
}

/** 7-day check-in cycle (10→30). */
object CheckInCycle {
    val REWARDS = intArrayOf(10, 10, 15, 20, 20, 25, 30)
}

// ---------------------------------------------------------------------------
// Persisted state shapes (DataStore, JSON — mirrors the Phase B pattern)
// ---------------------------------------------------------------------------

data class DpState(
    val currentDp: Int = 0,
    val lifetimeDp: Int = 0,
    val peakDp: Int = 0,
    val earnedToday: Int = 0,
    val todayKey: String = "",
    val earnedThisWeek: Int = 0,
    val weekAnchor: String = "",
    val earnedThisMonth: Int = 0,
    val monthAnchor: String = "",
    val firstEarnDateKey: String = "",
    val featureFlags: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentDp", currentDp)
        put("lifetimeDp", lifetimeDp)
        put("peakDp", peakDp)
        put("earnedToday", earnedToday)
        put("todayKey", todayKey)
        put("earnedThisWeek", earnedThisWeek)
        put("weekAnchor", weekAnchor)
        put("earnedThisMonth", earnedThisMonth)
        put("monthAnchor", monthAnchor)
        put("firstEarnDateKey", firstEarnDateKey)
        put("featureFlags", org.json.JSONArray(featureFlags.toList()))
    }

    companion object {
        fun fromJson(o: JSONObject) = DpState(
            currentDp = o.optInt("currentDp", 0),
            lifetimeDp = o.optInt("lifetimeDp", 0),
            peakDp = o.optInt("peakDp", 0),
            earnedToday = o.optInt("earnedToday", 0),
            todayKey = o.optString("todayKey", ""),
            earnedThisWeek = o.optInt("earnedThisWeek", 0),
            weekAnchor = o.optString("weekAnchor", ""),
            earnedThisMonth = o.optInt("earnedThisMonth", 0),
            monthAnchor = o.optString("monthAnchor", ""),
            firstEarnDateKey = o.optString("firstEarnDateKey", ""),
            featureFlags = run {
                val s = mutableSetOf<String>()
                val a = o.optJSONArray("featureFlags")
                if (a != null) for (i in 0 until a.length()) s.add(a.optString(i))
                s
            },
        )
    }
}

data class StreakState(
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    /** Streak epoch (wall ms). Shifted forward by frozen days so frozen
     *  time neither extends nor destroys the streak (time-neutral freeze). */
    val startDateWallMs: Long = 0L,
    val lastRolledDateKey: String = "",
    val lastCleanDateKey: String = "",
    val milestones: Set<Int> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentStreakDays", currentStreakDays)
        put("bestStreakDays", bestStreakDays)
        put("startDateWallMs", startDateWallMs)
        put("lastRolledDateKey", lastRolledDateKey)
        put("lastCleanDateKey", lastCleanDateKey)
        put("milestones", org.json.JSONArray(milestones.toList()))
    }

    companion object {
        fun fromJson(o: JSONObject) = StreakState(
            currentStreakDays = o.optInt("currentStreakDays", 0),
            bestStreakDays = o.optInt("bestStreakDays", 0),
            startDateWallMs = o.optLong("startDateWallMs", 0L),
            lastRolledDateKey = o.optString("lastRolledDateKey", ""),
            lastCleanDateKey = o.optString("lastCleanDateKey", ""),
            milestones = run {
                val s = mutableSetOf<Int>()
                val a = o.optJSONArray("milestones")
                if (a != null) for (i in 0 until a.length()) s.add(a.optInt(i))
                s
            },
        )
    }
}

data class ProtectionState(
    val freezeAllowanceMonth: String = "",
    val freezesUsedThisMonth: Int = 0,
    val totalFreezesUsed: Int = 0,
    /** >0 while progression is frozen (protection lost / grace running). */
    val frozenSinceWallMs: Long = 0L,
    val recoveryGraceUntilWallMs: Long = 0L,
    val pendingRelapseReason: String = "",
) {
    val isFrozen: Boolean get() = frozenSinceWallMs > 0L
    fun toJson(): JSONObject = JSONObject().apply {
        put("freezeAllowanceMonth", freezeAllowanceMonth)
        put("freezesUsedThisMonth", freezesUsedThisMonth)
        put("totalFreezesUsed", totalFreezesUsed)
        put("frozenSinceWallMs", frozenSinceWallMs)
        put("recoveryGraceUntilWallMs", recoveryGraceUntilWallMs)
        put("pendingRelapseReason", pendingRelapseReason)
    }

    companion object {
        const val MONTHLY_FREEZES = 2
        const val GRACE_HOURS = 24

        fun fromJson(o: JSONObject) = ProtectionState(
            freezeAllowanceMonth = o.optString("freezeAllowanceMonth", ""),
            freezesUsedThisMonth = o.optInt("freezesUsedThisMonth", 0),
            totalFreezesUsed = o.optInt("totalFreezesUsed", 0),
            frozenSinceWallMs = o.optLong("frozenSinceWallMs", 0L),
            recoveryGraceUntilWallMs = o.optLong("recoveryGraceUntilWallMs", 0L),
            pendingRelapseReason = o.optString("pendingRelapseReason", ""),
        )
    }
}

data class CheckInState(
    val cycleDay: Int = 0,
    val lastCheckInDateKey: String = "",
    val totalCheckIns: Int = 0,
    val cyclesCompleted: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cycleDay", cycleDay)
        put("lastCheckInDateKey", lastCheckInDateKey)
        put("totalCheckIns", totalCheckIns)
        put("cyclesCompleted", cyclesCompleted)
    }

    companion object {
        fun fromJson(o: JSONObject) = CheckInState(
            cycleDay = o.optInt("cycleDay", 0).coerceIn(0, 6),
            lastCheckInDateKey = o.optString("lastCheckInDateKey", ""),
            totalCheckIns = o.optInt("totalCheckIns", 0),
            cyclesCompleted = o.optInt("cyclesCompleted", 0),
        )
    }
}

// ---------------------------------------------------------------------------
// Room rows (ledger + relapse history — see MldDatabase v2)
// ---------------------------------------------------------------------------

/** Append-only DP award ledger (audit + history UI). */
data class DpAwardEntry(
    val id: Long,
    val reason: String,
    val amount: Int,
    val multiplierApplied: Double,
    val capped: Boolean,
    val timestampWall: Long,
    val dateKey: String,
)

data class RelapseEntry(
    val id: Long,
    val timestampWall: Long,
    val reason: String,
    val streakDaysAtRelapse: Int,
    val source: String,
)
