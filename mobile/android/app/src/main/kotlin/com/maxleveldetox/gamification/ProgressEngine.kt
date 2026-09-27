package com.maxleveldetox.gamification

import android.content.Context
import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.enforcement.AnalyticsOut
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.storage.DpAwardEntity
import com.maxleveldetox.storage.MldDatabase
import com.maxleveldetox.storage.RelapseEntity
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * ProgressEngine (v2.1 Phase C) — single-writer owner of the whole
 * gamification state domain: DP economy, streaks, check-in cycle and
 * streak protection (freezes + recovery grace + relapse log).
 *
 * PORTED-MECHANIC LINEAGE (from the competitor teardown, report-engagement
 * §4–§5, adapted to our values):
 *  - unified progression currency with reason-class multipliers and
 *    daily/weekly/monthly caps (anti-grind economy pacing);
 *  - streak freezes (2/month, auto-consumed on a missed rest day) and a
 *    24-hour Recovery Grace state machine for protection loss;
 *  - 7-day check-in cycle calendar;
 *  - milestone + level curve.
 *
 * DELIBERATELY NOT PORTED: abusive escalation copy, parasocial persona,
 * public shame graphs, aura decay, and any mechanic that ties progression
 * to monetization.
 *
 * SECURITY: this engine is enforcement-adjacent but never enforcement-
 * authoritative. Nothing here can unlock, extend, shorten or soften a
 * session, cage, monk lock or prime commit. Relapses reset progression
 * counters only. DP can never be spent (see GamificationModels header).
 */
class ProgressEngine(
    private val context: Context,
    private val stateRepo: StateRepository,
    private val database: MldDatabase,
    private val runtimeConfig: RuntimeConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val notifier = GamificationNotifier(context)

    // In-memory mirrors (persisted before any external effect — TRD §94).
    private var dp = DpState()
    private var streak = StreakState()
    private var protection = ProtectionState()
    private var checkIn = CheckInState()

    /** Last award per high-frequency reason (throttle window start, wall ms). */
    private val lastAwardAt = mutableMapOf<String, Long>()

    init {
        dp = stateRepo.blockingDp()?.let { DpState.fromJson(JSONObject(it)) } ?: DpState()
        streak = stateRepo.blockingStreak()?.let { StreakState.fromJson(JSONObject(it)) } ?: StreakState()
        protection = stateRepo.blockingProtection()?.let { ProtectionState.fromJson(JSONObject(it)) } ?: ProtectionState()
        checkIn = stateRepo.blockingCheckIn()?.let { CheckInState.fromJson(JSONObject(it)) } ?: CheckInState()

        // Ledger self-heal: the append-only dp_awards table is the source of
        // truth for lifetime totals; a crash between state write and ledger
        // insert must not create phantom DP (integrity rule, cf. CoinLedger).
        scope.launch {
            val ledgerTotal = database.dpAwardDao().totalEarned()
            if (ledgerTotal != dp.lifetimeDp) {
                mutex.withLock {
                    val delta = ledgerTotal - dp.lifetimeDp
                    dp = dp.copy(
                        currentDp = (dp.currentDp + delta).coerceAtLeast(0),
                        lifetimeDp = ledgerTotal,
                    )
                    stateRepo.saveDp(dp.toJson().toString())
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Date helpers (local zone, day-keyed like SessionEngine)
    // -----------------------------------------------------------------

    private fun dateKey(wallMs: Long): String =
        SessionEngine.dateKeyFor(wallMs)

    private fun todayKey(): String = dateKey(System.currentTimeMillis())

    private fun weekAnchor(now: LocalDate): String {
        val wf = WeekFields.ISO
        return "%04d-W%02d".format(Locale.ROOT, now.get(wf.weekBasedYear()), now.get(wf.weekOfWeekBasedYear()))
    }

    private fun monthAnchor(now: LocalDate): String = "%04d-%02d".format(now.year, now.monthValue)

    private fun localDate(dateKey: String): LocalDate? = try {
        LocalDate.parse(dateKey)
    } catch (_: Exception) {
        null
    }

    // -----------------------------------------------------------------
    // Sweep integration — day rollover + grace expiry + allowance refresh
    // -----------------------------------------------------------------

    /** Called from the EnforcementService periodic sweep (every ~30s). */
    fun onSweepTick() {
        scope.launch {
            mutex.withLock {
                rolloverLocked()
                resolveExpiredGraceLocked()
            }
        }
    }

    /**
     * Process every unrolled day up to yesterday. Day semantics:
     *   clean    = active (session/alarm/monk/check-in) AND no break
     *              (no bailout, no cage, no relapse)  -> streak++
     *   missed   = no activity at all                  -> freeze or reset
     *   broke    = activity but a break happened       -> reset
     */
    private suspend fun rolloverLocked() {
        val today = todayKey()
        if (streak.lastRolledDateKey == today) {
            // Still refresh the monthly freeze allowance on every sweep —
            // cheap, and the UI depends on it being current.
            refreshAllowanceLocked()
            return
        }

        var cursor = localDate(streak.lastRolledDateKey)?.plusDays(1)
        val yesterday = localDate(today)?.minusDays(1)
        var current = streak.currentStreakDays

        while (cursor != null && yesterday != null && !cursor.isAfter(yesterday)) {
            val key = cursor.toString()
            when {
                isCleanDay(key) -> {
                    current += 1
                    streak = streak.copy(lastCleanDateKey = key)
                }
                !isActiveDay(key) -> {
                    // Missed day: auto-consume a freeze if available.
                    if (protection.freezesUsedThisMonth < ProtectionState.MONTHLY_FREEZES) {
                        protection = protection.copy(
                            freezesUsedThisMonth = protection.freezesUsedThisMonth + 1,
                            totalFreezesUsed = protection.totalFreezesUsed + 1,
                        )
                        stateRepo.saveProtection(protection.toJson().toString())
                        notifier.notifyFreezeUsed(
                            ProtectionState.MONTHLY_FREEZES - protection.freezesUsedThisMonth)
                    } else {
                        if (current > 0) {
                            notifier.notifyStreakReset("Missed a day with no freezes left.")
                        }
                        current = 0
                    }
                }
                else -> {
                    if (current > 0) {
                        notifier.notifyStreakReset("A rule was broken that day.")
                    }
                    current = 0
                }
            }
            cursor = cursor.plusDays(1)
        }

        val previousBest = streak.bestStreakDays
        streak = streak.copy(
            currentStreakDays = current,
            bestStreakDays = maxOf(previousBest, current),
            lastRolledDateKey = today,
        )
        stateRepo.saveStreak(streak.toJson().toString())

        // Yesterday clean -> CLEAN_DAY award + milestone checks.
        if (yesterday != null && isCleanDay(yesterday.toString())) {
            awardLocked(DpReason.CLEAN_DAY, DpReason.CLEAN_DAY.defaultAmount)
        }
        checkMilestonesLocked()

        refreshAllowanceLocked()
    }

    private fun refreshAllowanceLocked() {
        val month = monthAnchor(LocalDate.now())
        if (protection.freezeAllowanceMonth != month) {
            protection = protection.copy(
                freezeAllowanceMonth = month,
                freezesUsedThisMonth = 0,
            )
            scope.launch { stateRepo.saveProtection(protection.toJson().toString()) }
        }
    }

    /** Active = any discipline signal that day (session/alarm/monk/check-in). */
    private suspend fun isActiveDay(key: String): Boolean {
        val stats = database.dailyStatDao().byKey(key) ?: return false
        val checkInThatDay = checkIn.lastCheckInDateKey == key
        return stats.sessionsCompleted > 0 || stats.alarmsCompleted > 0 ||
            stats.monkCompletions > 0 || checkInThatDay
    }

    /** Clean = active AND no break (bailout / cage / relapse). */
    private suspend fun isCleanDay(key: String): Boolean {
        if (!isActiveDay(key)) return false
        val stats = database.dailyStatDao().byKey(key) ?: return false
        if (stats.sessionsBailed > 0 || stats.cageCount > 0) return false
        if (database.relapseDao().countForDate(key) > 0) return false
        return true
    }

    // -----------------------------------------------------------------
    // DP economy
    // -----------------------------------------------------------------

    /**
     * Award DP for [reason]. Applies the reason-class multiplier from
     * remote config, enforces daily/weekly/monthly caps, appends the
     * ledger row, and fires level-up notifications. Returns the awarded
     * amount (0 when disabled / frozen / capped out).
     */
    private suspend fun awardLocked(reason: DpReason, baseAmount: Int): Int {
        val cfg = runtimeConfig.current()
        if (!cfg.gamificationEnabled) return 0
        if (protection.isFrozen) return 0
        if (baseAmount <= 0) return 0

        val multiplier = when (reason.dpClass) {
            DpClass.FOCUS -> cfg.dpMultiplierFocus
            DpClass.REELS -> cfg.dpMultiplierReels
            DpClass.NEUTRAL -> cfg.dpMultiplierNeutral
        }
        var amount = Math.round(baseAmount * multiplier).toInt()

        // Lazy cap-anchor rollover.
        val now = LocalDate.now()
        val today = todayKey()
        if (dp.todayKey != today) dp = dp.copy(earnedToday = 0, todayKey = today)
        if (dp.weekAnchor != weekAnchor(now)) dp = dp.copy(earnedThisWeek = 0, weekAnchor = weekAnchor(now))
        if (dp.monthAnchor != monthAnchor(now)) dp = dp.copy(earnedThisMonth = 0, monthAnchor = monthAnchor(now))
        if (dp.firstEarnDateKey.isEmpty()) dp = dp.copy(firstEarnDateKey = today)

        var hitCap = false
        // First earn day gets a friendlier daily cap (+100).
        val dailyCap = if (dp.firstEarnDateKey == today) cfg.dpDailyCap + 100 else cfg.dpDailyCap
        val dailyRoom = (dailyCap - dp.earnedToday).coerceAtLeast(0)
        val weeklyRoom = (cfg.dpWeeklyCap - dp.earnedThisWeek).coerceAtLeast(0)
        val monthlyRoom = (cfg.dpMonthlyCap - dp.earnedThisMonth).coerceAtLeast(0)
        val capped = minOf(dailyRoom, weeklyRoom, monthlyRoom)
        if (amount > capped) {
            amount = capped
            hitCap = true
        }
        if (amount <= 0) return 0

        val beforeLevel = MldLevel.forLifetimeDp(dp.lifetimeDp)
        dp = dp.copy(
            currentDp = dp.currentDp + amount,
            lifetimeDp = dp.lifetimeDp + amount,
            peakDp = maxOf(dp.peakDp, dp.currentDp),
            earnedToday = dp.earnedToday + amount,
            earnedThisWeek = dp.earnedThisWeek + amount,
            earnedThisMonth = dp.earnedThisMonth + amount,
        )
        stateRepo.saveDp(dp.toJson().toString())

        database.dpAwardDao().insert(
            DpAwardEntity(
                reason = reason.name,
                amount = amount,
                multiplierApplied = multiplier,
                capped = hitCap,
                timestampWall = System.currentTimeMillis(),
                dateKey = today,
            )
        )
        database.dailyStatDao().bump(today, "dpEarned", amount)

        val afterLevel = MldLevel.forLifetimeDp(dp.lifetimeDp)
        if (afterLevel.ordinal > beforeLevel.ordinal) {
            notifier.notifyLevelUp(afterLevel.display, dp.lifetimeDp)
        }
        return amount
    }

    /** Uncapped path — milestones only (still blocked while frozen). */
    private suspend fun awardUncappedLocked(reason: DpReason, amount: Int): Int {
        val cfg = runtimeConfig.current()
        if (!cfg.gamificationEnabled || protection.isFrozen || amount <= 0) return 0

        val beforeLevel = MldLevel.forLifetimeDp(dp.lifetimeDp)
        val today = todayKey()
        dp = dp.copy(
            currentDp = dp.currentDp + amount,
            lifetimeDp = dp.lifetimeDp + amount,
            peakDp = maxOf(dp.peakDp, dp.currentDp),
        )
        if (dp.firstEarnDateKey.isEmpty()) dp = dp.copy(firstEarnDateKey = today)
        stateRepo.saveDp(dp.toJson().toString())

        database.dpAwardDao().insert(
            DpAwardEntity(
                reason = reason.name,
                amount = amount,
                multiplierApplied = 1.0,
                capped = false,
                timestampWall = System.currentTimeMillis(),
                dateKey = today,
            )
        )
        database.dailyStatDao().bump(today, "dpEarned", amount)

        val afterLevel = MldLevel.forLifetimeDp(dp.lifetimeDp)
        if (afterLevel.ordinal > beforeLevel.ordinal) {
            notifier.notifyLevelUp(afterLevel.display, dp.lifetimeDp)
        }
        return amount
    }

    private suspend fun checkMilestonesLocked() {
        for ((days, dpAmount) in StreakMilestones.TABLE) {
            if (streak.currentStreakDays >= days && days !in streak.milestones) {
                streak = streak.copy(milestones = streak.milestones + days)
                stateRepo.saveStreak(streak.toJson().toString())
                awardUncappedLocked(DpReason.STREAK_MILESTONE, dpAmount)
                notifier.notifyMilestone(days, dpAmount)
            }
        }
    }

    // -----------------------------------------------------------------
    // Event hooks (fire-and-forget; each is internally serialized)
    // -----------------------------------------------------------------

    fun onSessionCompleted(mode: String, durationMinutes: Int) {
        scope.launch {
            mutex.withLock {
                awardLocked(DpReason.SESSION_COMPLETED, DpReason.SESSION_COMPLETED.defaultAmount)
                AnalyticsOut.post("DP_SESSION_COMPLETED", mapOf("mode" to mode))
            }
        }
    }

    fun onBailout() {
        // No award — a bailout already costs 500 coins and the day will be
        // judged unclean at rollover. Recorded here for the event trail.
        AnalyticsOut.post("DP_BAILOUT", emptyMap())
    }

    fun onCageSurvived() {
        scope.launch {
            mutex.withLock { awardLocked(DpReason.CAGE_SURVIVED, DpReason.CAGE_SURVIVED.defaultAmount) }
        }
    }

    fun onReelsBlocked(pkg: String) {
        scope.launch {
            mutex.withLock {
                // Anti-farming throttle: at most one REELS_BLOCKED award per
                // 30s regardless of how many platforms were intercepted.
                val now = System.currentTimeMillis()
                val last = lastAwardAt[DpReason.REELS_BLOCKED.name] ?: 0L
                if (now - last < 30_000L) return@withLock
                lastAwardAt[DpReason.REELS_BLOCKED.name] = now
                val got = awardLocked(DpReason.REELS_BLOCKED, DpReason.REELS_BLOCKED.defaultAmount)
                if (got > 0) AnalyticsOut.post("DP_REELS_BLOCKED", mapOf("package" to pkg))
            }
        }
    }

    fun onMonkCompleted() {
        scope.launch {
            mutex.withLock {
                database.dailyStatDao().bump(todayKey(), "monkCompletions", 1)
                awardLocked(DpReason.MONK_COMPLETED, DpReason.MONK_COMPLETED.defaultAmount)
            }
        }
    }

    fun onPrimeCompleted() {
        scope.launch {
            mutex.withLock { awardLocked(DpReason.PRIME_COMPLETED, DpReason.PRIME_COMPLETED.defaultAmount) }
        }
    }

    fun onSafetyPauseCompleted() {
        scope.launch {
            mutex.withLock { awardLocked(DpReason.SAFETY_PAUSE_COMPLETED, DpReason.SAFETY_PAUSE_COMPLETED.defaultAmount) }
        }
    }

    fun onAlarmCompleted() {
        scope.launch {
            mutex.withLock {
                database.dailyStatDao().bump(todayKey(), "alarmsCompleted", 1)
                awardLocked(DpReason.ALARM_COMPLETED, DpReason.ALARM_COMPLETED.defaultAmount)
            }
        }
    }

    // -----------------------------------------------------------------
    // v2.5 r9 — Social Sentry task economy hooks (report-engagement §4c:
    // task +8 / subtask +2 / routine +4, discipline combo +2 once a day
    // when ≥2 tasks completed AND ≥10 focus minutes today).
    // -----------------------------------------------------------------

    fun onTaskCompleted(kind: String) {
        scope.launch {
            mutex.withLock {
                val reason = when (kind) {
                    "routine" -> DpReason.ROUTINE_COMPLETED
                    "subtask" -> DpReason.SUBTASK_COMPLETED
                    else -> DpReason.TASK_COMPLETED
                }
                val got = awardLocked(reason, reason.defaultAmount)
                if (got > 0) {
                    database.dailyStatDao().bump(todayKey(), "tasksCompleted", 1)
                    AnalyticsOut.post("DP_TASK_COMPLETED", mapOf("kind" to kind))
                }
            }
        }
    }

    /** Once-per-day discipline combo (date-keyed gate lives in TasksEngine). */
    fun awardDisciplineCombo() {
        scope.launch {
            mutex.withLock { awardLocked(DpReason.DISCIPLINE_COMBO, DpReason.DISCIPLINE_COMBO.defaultAmount) }
        }
    }

    /** Today's focus minutes (session log) — combo input. */
    fun todayFocusMinutes(): Int = try {
        runBlocking {
            val stat = database.dailyStatDao().byKey(todayKey())
            ((stat?.focusSeconds ?: 0) + (stat?.detoxSeconds ?: 0)) / 60
        }
    } catch (_: Exception) {
        0
    }

    /** One-time feature activation bonuses (idempotent by feature key). */
    fun onFeatureActivated(featureKey: String) {
        val reason = when (featureKey) {
            "shorts" -> DpReason.FEATURE_SHORTS
            "safety" -> DpReason.FEATURE_SAFETY
            "monk" -> DpReason.FEATURE_MONK
            "prime" -> DpReason.FEATURE_PRIME
            "alarm" -> DpReason.FEATURE_ALARM
            "widget" -> DpReason.FEATURE_WIDGET
            else -> null
        } ?: return
        scope.launch {
            mutex.withLock {
                if (reason.oneTimeKey != null && reason.oneTimeKey in dp.featureFlags) return@withLock
                val got = awardLocked(reason, reason.defaultAmount)
                if (got > 0 && reason.oneTimeKey != null) {
                    dp = dp.copy(featureFlags = dp.featureFlags + reason.oneTimeKey)
                    stateRepo.saveDp(dp.toJson().toString())
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Streak protection — recovery grace + relapse
    // -----------------------------------------------------------------

    /** Protection lost (permission tamper): freeze DP + arm 24h grace. */
    fun onProtectionLost(reason: String) {
        scope.launch {
            mutex.withLock {
                if (protection.frozenSinceWallMs > 0L) return@withLock
                protection = protection.copy(
                    frozenSinceWallMs = System.currentTimeMillis(),
                    recoveryGraceUntilWallMs =
                        System.currentTimeMillis() + ProtectionState.GRACE_HOURS * 3_600_000L,
                    pendingRelapseReason = reason,
                )
                stateRepo.saveProtection(protection.toJson().toString())
                notifier.notifyGraceArmed(ProtectionState.GRACE_HOURS)
                AnalyticsOut.post("DP_GRACE_ARMED", mapOf("reason" to reason))
            }
        }
    }

    /** Protection restored inside the window: streak intact, DP resumes. */
    fun onProtectionRestored() {
        scope.launch {
            mutex.withLock {
                if (protection.frozenSinceWallMs == 0L) return@withLock
                protection = ProtectionState(
                    freezeAllowanceMonth = protection.freezeAllowanceMonth,
                    freezesUsedThisMonth = protection.freezesUsedThisMonth,
                    totalFreezesUsed = protection.totalFreezesUsed,
                )
                stateRepo.saveProtection(protection.toJson().toString())
                AnalyticsOut.post("DP_GRACE_CLEARED", emptyMap())
            }
        }
    }

    /** Sweep: grace expired without restoration -> relapse. */
    private suspend fun resolveExpiredGraceLocked() {
        if (protection.frozenSinceWallMs == 0L) return
        if (protection.recoveryGraceUntilWallMs == 0L) return
        if (System.currentTimeMillis() < protection.recoveryGraceUntilWallMs) return

        val reason = protection.pendingRelapseReason.ifEmpty { "Recovery grace expired" }
        relapseLocked(reason, "PROTECTION_LOST")
    }

    /** Prime give-up is a deliberate broken commitment: immediate relapse. */
    fun onPrimeGiveUp(title: String) {
        scope.launch {
            mutex.withLock { relapseLocked("Prime commit ended early: $title", "PRIME_GIVEUP") }
        }
    }

    private suspend fun relapseLocked(reason: String, source: String) {
        val had = streak.currentStreakDays
        val now = System.currentTimeMillis()
        database.relapseDao().insert(
            RelapseEntity(
                timestampWall = now,
                dateKey = todayKey(),
                reason = reason,
                streakDaysAtRelapse = had,
                source = source,
            )
        )
        streak = streak.copy(currentStreakDays = 0)
        protection = ProtectionState(
            freezeAllowanceMonth = protection.freezeAllowanceMonth,
            freezesUsedThisMonth = protection.freezesUsedThisMonth,
            totalFreezesUsed = protection.totalFreezesUsed,
        )
        stateRepo.saveStreak(streak.toJson().toString())
        stateRepo.saveProtection(protection.toJson().toString())
        notifier.notifyRelapse(had, source)
        AnalyticsOut.post("DP_RELAPSE", mapOf("source" to source, "streakDays" to had))
    }

    // -----------------------------------------------------------------
    // Check-in cycle
    // -----------------------------------------------------------------

    fun checkInAvailable(): Boolean {
        val cfg = runtimeConfig.current()
        if (!cfg.gamificationEnabled) return false
        return checkIn.lastCheckInDateKey != todayKey()
    }

    /** Claim today's check-in. Returns awarded DP (0 = not available). */
    suspend fun claimCheckIn(): Int = mutex.withLock {
        if (!checkInAvailable()) return 0
        val reward = CheckInCycle.REWARDS[checkIn.cycleDay]
        val day = checkIn.cycleDay
        val newCycleDay = (day + 1) % CheckInCycle.REWARDS.size
        checkIn = checkIn.copy(
            cycleDay = newCycleDay,
            lastCheckInDateKey = todayKey(),
            totalCheckIns = checkIn.totalCheckIns + 1,
            cyclesCompleted = checkIn.cyclesCompleted + if (newCycleDay == 0) 1 else 0,
        )
        stateRepo.saveCheckIn(checkIn.toJson().toString())
        val got = awardLocked(DpReason.CHECK_IN, reward)
        if (got == 0) {
            // Capped out or frozen — the check-in itself still counts as an
            // active day (streak protection), just without DP.
            AnalyticsOut.post("DP_CHECKIN_NOCOIN", emptyMap())
        }
        got
    }

    // -----------------------------------------------------------------
    // Snapshots for the bridge / Flutter
    // -----------------------------------------------------------------

    fun snapshotJson(): JSONObject = runBlockingSnapshot()

    /**
     * v2.5.8 roadmap: the community-leaderboard mirror payload (POST
     * /leaderboard/sync). Read-only projection of the DP authority — the
     * server clamps and sanity-caps it again, so this is deliberately a
     * plain snapshot with zero enforcement meaning.
     */
    fun leaderboardSnapshotJson(): JSONObject {
        val now = java.time.LocalDate.now()
        val level = MldLevel.forLifetimeDp(dp.lifetimeDp)
        // Window anchors follow the device's own week/month rollover (same
        // computation the caps use) — the server stores them verbatim.
        val week = if (dp.weekAnchor.isEmpty()) weekAnchor(now) else dp.weekAnchor
        val month = if (dp.monthAnchor.isEmpty()) monthAnchor(now) else dp.monthAnchor
        return JSONObject().apply {
            put("lifetimeDp", dp.lifetimeDp)
            put("weekDp", dp.earnedThisWeek)
            put("weekAnchor", week)
            put("monthDp", dp.earnedThisMonth)
            put("monthAnchor", month)
            put("streakDays", streak.currentStreakDays)
            put("levelName", level.display)
        }
    }

    private fun runBlockingSnapshot(): JSONObject {
        val today = todayKey()
        val level = MldLevel.forLifetimeDp(dp.lifetimeDp)
        val next = MldLevel.next(dp.lifetimeDp)
        val cfg = runtimeConfig.current()

        val dpJson = JSONObject().apply {
            put("current", dp.currentDp)
            put("lifetime", dp.lifetimeDp)
            put("peak", dp.peakDp)
            put("level", level.ordinal + 1)
            put("levelName", level.display)
            put("nextLevelDp", next?.minDp ?: JSONObject.NULL)
            put("nextLevelName", next?.display ?: JSONObject.NULL)
            put("progressPct", if (next == null) 100 else {
                val span = next.minDp - level.minDp
                if (span <= 0) 100 else (((dp.lifetimeDp - level.minDp) * 100) / span).coerceIn(0, 100)
            })
            put("earnedToday", dp.earnedToday)
            put("dailyCap", if (dp.firstEarnDateKey == today) cfg.dpDailyCap + 100 else cfg.dpDailyCap)
        }

        val streakJson = JSONObject().apply {
            put("current", streak.currentStreakDays)
            put("best", streak.bestStreakDays)
            put("lastCleanDateKey", streak.lastCleanDateKey)
            put("onTrackToday", isActiveDaySnapshot(today))
            put("milestones", org.json.JSONArray(StreakMilestones.TABLE.map { (d, amount) ->
                JSONObject().put("days", d).put("dp", amount).put("achieved", d in streak.milestones)
            }))
        }

        val protectionJson = JSONObject().apply {
            put("freezesRemaining",
                (ProtectionState.MONTHLY_FREEZES - protection.freezesUsedThisMonth).coerceAtLeast(0))
            put("freezesUsedThisMonth", protection.freezesUsedThisMonth)
            put("totalFreezesUsed", protection.totalFreezesUsed)
            put("frozen", protection.isFrozen)
            put("graceUntilWallMs", protection.recoveryGraceUntilWallMs)
            put("pendingRelapseReason", protection.pendingRelapseReason)
        }

        val checkInJson = JSONObject().apply {
            put("available", checkInAvailable())
            put("cycleDay", checkIn.cycleDay)
            put("cycleRewards", org.json.JSONArray().apply {
                CheckInCycle.REWARDS.forEach { put(it) }
            })
            put("totalCheckIns", checkIn.totalCheckIns)
            put("cyclesCompleted", checkIn.cyclesCompleted)
            put("lastDateKey", checkIn.lastCheckInDateKey)
        }

        return JSONObject().apply {
            put("enabled", cfg.gamificationEnabled)
            put("dp", dpJson)
            put("streak", streakJson)
            put("protection", protectionJson)
            put("checkIn", checkInJson)
        }
    }

    private fun isActiveDaySnapshot(today: String): Boolean {
        // Non-suspending approximation for the snapshot path (check-in state
        // is in memory; the stats query is only needed for the "so far" hint
        // and is resolved lazily by the full history screen).
        val checkInToday = checkIn.lastCheckInDateKey == today
        if (checkInToday) return true
        return false // conservative; rollover decides with full data
    }

    /** Compact progress object embedded in every stateData() push. */
    fun compactJson(): JSONObject {
        val cfg = runtimeConfig.current()
        if (!cfg.gamificationEnabled) return JSONObject().put("enabled", false)
        val level = MldLevel.forLifetimeDp(dp.lifetimeDp)
        val next = MldLevel.next(dp.lifetimeDp)
        return JSONObject().apply {
            put("enabled", true)
            put("level", level.ordinal + 1)
            put("levelName", level.display)
            put("dp", dp.currentDp)
            put("nextLevelDp", next?.minDp ?: JSONObject.NULL)
            put("streakDays", streak.currentStreakDays)
            put("checkInAvailable", checkInAvailable())
            put("frozen", protection.isFrozen)
        }
    }

    // -----------------------------------------------------------------
    // History (Room-backed)
    // -----------------------------------------------------------------

    fun recentAwardsJson(limit: Int): org.json.JSONArray =
        kotlinx.coroutines.runBlocking {
            val rows = database.dpAwardDao().recent(limit.coerceIn(1, 200))
            org.json.JSONArray().apply {
                rows.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("reason", r.reason)
                        put("amount", r.amount)
                        put("multiplier", r.multiplierApplied)
                        put("capped", r.capped)
                        put("timestampWall", r.timestampWall)
                        put("dateKey", r.dateKey)
                    })
                }
            }
        }

    fun relapseHistoryJson(limit: Int): org.json.JSONArray =
        kotlinx.coroutines.runBlocking {
            val rows = database.relapseDao().recent(limit.coerceIn(1, 200))
            org.json.JSONArray().apply {
                rows.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("timestampWall", r.timestampWall)
                        put("reason", r.reason)
                        put("streakDaysAtRelapse", r.streakDaysAtRelapse)
                        put("source", r.source)
                    })
                }
            }
        }

    // -----------------------------------------------------------------
    // Debug tools (debug builds only — gated by the bridge)
    // -----------------------------------------------------------------

    fun debugAward(reason: DpReason, amount: Int) {
        scope.launch {
            mutex.withLock { awardLocked(reason, amount.coerceIn(1, 500)) }
        }
    }

    fun debugReset() {
        scope.launch {
            mutex.withLock {
                dp = DpState()
                streak = StreakState()
                protection = ProtectionState()
                checkIn = CheckInState()
                lastAwardAt.clear()
                stateRepo.saveDp(dp.toJson().toString())
                stateRepo.saveStreak(streak.toJson().toString())
                stateRepo.saveProtection(protection.toJson().toString())
                stateRepo.saveCheckIn(checkIn.toJson().toString())
                database.dpAwardDao().deleteAll()
                database.relapseDao().deleteAll()
            }
        }
    }
}
