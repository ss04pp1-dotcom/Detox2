package com.maxleveldetox.enforcement

import com.maxleveldetox.storage.MldDatabase
import com.maxleveldetox.storage.StateRepository
import com.maxleveldetox.storage.ViolationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViolationManager (TRD §39) + the shared Shorts warning escalation
 * (PRD §12–14, §111).
 *
 * SHARED COUNTER RULE: the warning counter is one per day across ALL
 * short-form platforms — Instagram → YouTube → TikTok → … increments the
 * SAME counter, so switching apps cannot reset escalation (PRD §14).
 */
class ViolationManager(
    private val database: MldDatabase,
    private val stateRepo: StateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val escalationMutex = Mutex()

    /** Set when a new session starts so violations attach to the right id. */
    @Volatile
    var clearSessionViolations: String = "none"

    // -----------------------------------------------------------------
    // Generic violation record
    // -----------------------------------------------------------------

    fun record(
        sessionId: String,
        pkg: String,
        type: ViolationType,
        severity: String,
        warningNumber: Int,
        action: String,
    ) {
        scope.launch {
            database.violationDao().insert(
                ViolationEntity(
                    sessionId = sessionId,
                    timestampWall = System.currentTimeMillis(),
                    packageName = pkg,
                    type = type.name,
                    severity = severity,
                    warningNumber = warningNumber,
                    actionTaken = action,
                )
            )
            val dateKey = SessionEngine.dateKeyFor(System.currentTimeMillis())
            when (type) {
                ViolationType.BLOCKED_APP -> database.dailyStatDao().bump(dateKey, "blockedAttempts", 1)
                ViolationType.SHORTS_ENTRY -> database.dailyStatDao().bump(dateKey, "shortsWarnings", 1)
                else -> Unit
            }
        }
    }

    // -----------------------------------------------------------------
    // Shorts escalation — called by the accessibility/detector path.
    // -----------------------------------------------------------------

    /**
     * Register a shorts attempt. Returns the warning number (1..limit+1)
     * after incrementing the shared daily counter. When the count exceeds
     * the configured limit, [onCage] fires (engine activates the cage).
     */
    fun shortsAttempt(
        pkg: String,
        warningLimit: Int = 5,
        onWarning: (count: Int, limit: Int) -> Unit,
        onCage: () -> Unit,
    ) {
        scope.launch {
            val count = escalateLocked() ?: return@launch
            val limit = warningLimit.coerceIn(3, 7)

            record(
                sessionId = clearSessionViolations,
                pkg = pkg,
                type = ViolationType.SHORTS_ENTRY,
                severity = if (count >= limit) "HIGH" else "MEDIUM",
                warningNumber = count.coerceAtMost(limit),
                action = if (count > limit) "cage" else "warning_$count",
            )
            AnalyticsOut.post("SHORTS_WARNING", mapOf("count" to count, "package" to pkg))

            if (count > limit) onCage() else onWarning(count, limit)
        }
    }

    private suspend fun escalateLocked(): Int? = escalationMutex.withLock {
        var state = stateRepo.blockingShorts()
        val today = SessionEngine.dateKeyFor(System.currentTimeMillis())
        if (!state.enabled) return null

        // Daily reset keeps warnings meaningful without being a lifetime ban.
        if (state.warningDateKey != today) {
            state = state.copy(warningCount = 0, warningDateKey = today)
        }
        val next = state.warningCount + 1
        stateRepo.saveShorts(state.copy(warningCount = next))
        return next
    }

    fun currentWarningCount(): Int {
        val state = stateRepo.blockingShorts()
        val today = SessionEngine.dateKeyFor(System.currentTimeMillis())
        return if (state.warningDateKey == today) state.warningCount else 0
    }
}
