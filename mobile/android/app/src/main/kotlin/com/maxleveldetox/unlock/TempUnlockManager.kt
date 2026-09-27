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
 *   2. no cage is running (cage disables unlocks, PRD §17)
 *   3. no unlock is already running (one at a time)
 *   4. the requested packages are unlockable by policy
 *   5. the coin balance covers the configured cost
 * Only then is the coin spend executed atomically and the unlock window
 * persisted. Expiry is lazy: every policy evaluation checks the window and
 * clears it the moment it ends — enforcement resumes IMMEDIATELY.
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
        if (session == null || !session.status.isEnforcing) {
            return Result(false, ErrorCodes.SESSION_NOT_ACTIVE, "No active session.")
        }

        // Prime commits refuse temporary unlocks (v2.0 Phase B5): the
        // commitment contract has exactly one exit — the TOTP give-up.
        val prime = stateRepo.blockingPrime()
        if (prime.active && prime.sessionId == session.id) {
            return Result(false, ErrorCodes.PRIME_ACTIVE,
                "Temporary unlock is disabled during a Prime commit.")
        }

        val cage = stateRepo.blockingCage()
        if (cage.active && !cage.isExpired(SystemClockNow.elapsed)) {
            return Result(false, ErrorCodes.CAGE_ACTIVE,
                "Temporary unlock is disabled while Cage is active.")
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
            reference = "unlock:${session.id}:${SystemClockNow.elapsed}",
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
        // v2.5.5 audit fix M-3: a live temp-unlock window lifts the kiosk
        // pin (safety outranks strictness during the paid window).
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)
        return Result(true, null, null)
    }

    /** Clear the window (expiry, cage activation, session end). */
    suspend fun clear() {
        stateRepo.saveTempUnlock(TempUnlockSnapshot.INACTIVE)
        // v2.5.5 audit fix M-3: a cleared temp-unlock re-arms the kiosk
        // trap (the desired-state computation consults the window).
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
