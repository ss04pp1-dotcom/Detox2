package com.maxleveldetox.unlock

import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.coins.CoinLedger
import com.maxleveldetox.enforcement.ErrorCodes
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.TempUnlockSnapshot
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TempUnlockManager (TRD §22–23, §54, PRD §15–17).
 *
 * NATIVE VALIDATION (TRD §41): Flutter's requestTempUnlock(packages) is a
 * REQUEST. Before anything happens we verify, in order:
 *   1. a session is actually active
 *   2. no Prime commit owns the session (one exit only)
 *   3. no unlock is already running (one at a time)
 *   4. the requested packages are unlockable by policy
 *   5. the coin balance covers the configured cost
 * Only then is the coin spend executed atomically and the unlock window
 * persisted. Expiry is lazy: every policy evaluation checks the window and
 * clears it the moment it ends — enforcement resumes IMMEDIATELY.
 *
 * v2.9.11 r27 (user request — the cage screen is FUNCTIONAL, not a dead
 * end): the old CAGE_ACTIVE refusal is GONE. A temporary unlock can now
 * be bought from inside the cage; the a11y cage gate allows the window's
 * packages through (see DetoxAccessibilityService.handleForeground) and
 * full cage enforcement resumes the moment the window expires.
 */
class TempUnlockManager(
    private val stateRepo: StateRepository,
    private val coinLedger: CoinLedger,
    private val runtimeConfig: RuntimeConfig,
) {
    private val mutex = Mutex()

    data class Result(val ok: Boolean, val errorCode: String?, val message: String?)

    suspend fun request(packages: List<String>): Result = mutex.withLock {
        val session = stateRepo.blockingSession()
        val sessionLive = session != null && session.status.isEnforcing
        // v2.9.12 r28 (user report — "case er moddhe thekei… temporary
        // unlock er kajgulo korte parbe"): the burst cage can trigger
        // OUTSIDE any session (5 rapid shorts attempts with no session
        // running). Refusing with SESSION_NOT_ACTIVE there left the
        // cage's TEMPORARY UNLOCK button permanently dead — exactly what
        // the user reported. The unlock is now buyable whenever EITHER a
        // session enforces OR the cage is active.
        val cageLive = try {
            com.maxleveldetox.overlay.EnforcementWall.isCageActive()
        } catch (_: Exception) {
            false
        }
        if (!sessionLive && !cageLive) {
            return Result(false, ErrorCodes.SESSION_NOT_ACTIVE, "No active session.")
        }

        // Prime commits refuse temporary unlocks (v2.0 Phase B5): the
        // commitment contract has exactly one exit — the TOTP give-up.
        if (sessionLive) {
            val prime = stateRepo.blockingPrime()
            if (prime.active && prime.sessionId == session!!.id) {
                return Result(false, ErrorCodes.PRIME_ACTIVE,
                    "Temporary unlock is disabled during a Prime commit.")
            }
        }

        val current = stateRepo.blockingTempUnlock()
        if (current.active && !current.isExpired(SystemClockNow.elapsed)) {
            return Result(false, ErrorCodes.TEMP_UNLOCK_ACTIVE,
                "An unlock window is already running.")
        }

        val validPackages = packages.filter { it in UNLOCKABLE_PACKAGES }
        if (validPackages.isEmpty()) {
            return Result(false, ErrorCodes.INVALID_REQUEST,
                "No unlockable app selected.")
        }

        val config = runtimeConfig.current()
        val spend = coinLedger.spend(
            type = "TEMP_UNLOCK_SPEND",
            cost = config.tempUnlockCoins,
            reference = "unlock:${session?.id ?: "cage"}:${SystemClockNow.elapsed}",
        )
        if (!spend.first) {
            return Result(false, ErrorCodes.INSUFFICIENT_COINS,
                "${config.tempUnlockCoins} coins are required.")
        }

        val now = SystemClockNow.elapsed
        stateRepo.saveTempUnlock(
            TempUnlockSnapshot(
                active = true,
                startElapsed = now,
                endElapsed = now + config.tempUnlockMinutes * 60_000L,
                allowedPackages = validPackages.toSet(),
            )
        )
        // v2.5 r9: snapshot the paused enforcement scope so the
        // auto-reblock monitor can restore it if this process dies
        // mid-window (Social Sentry mechanism #17).
        AutoReblockMonitor.snapshotUnlockStart(stateRepo.contextRef())
        // v2.9 r17: a live temp-unlock window lifts the session kiosk
        // surfaces (safety outranks strictness during the paid window).
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)
        return Result(true, null, null)
    }

    /** Clear the window (expiry, cage activation, session end). */
    suspend fun clear() {
        stateRepo.saveTempUnlock(TempUnlockSnapshot.INACTIVE)
        // v2.9 r17: a cleared temp-unlock re-arms the session kiosk
        // surfaces (the armed-state computation consults the window).
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)
    }

    /** Lazy expiry — called from every state read + policy evaluation. */
    suspend fun expireIfNeeded() {
        val current = stateRepo.blockingTempUnlock()
        if (current.active && current.isExpired(SystemClockNow.elapsed)) {
            clear()
        }
    }

    companion object {
        /** Packages ever unlockable (PRD §16 default allowlist). */
        val UNLOCKABLE_PACKAGES = setOf(
            "com.android.camera2",
            "com.google.android.apps.photos",
            "com.android.chrome",
        )
    }
}
