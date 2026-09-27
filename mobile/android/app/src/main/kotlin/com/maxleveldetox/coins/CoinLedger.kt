package com.maxleveldetox.coins

import com.maxleveldetox.storage.CoinTransactionEntity
import com.maxleveldetox.storage.MldDatabase
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CoinLedger (TRD §21, §100–101) — the immutable coin economy.
 *
 * ANTI-BYPAX RULES (all enforced HERE, not in Flutter):
 *  1. balance == SUM(amount) over coin_transactions — reconstructable.
 *  2. `awardAd` is idempotent: deterministic PK "coin_<rewardKey>" with
 *     INSERT OR IGNORE. A replayed ad callback cannot mint a second coin.
 *  3. `spend` runs inside a mutex and verifies balance >= cost BEFORE the
 *     insert — no double-spend, no negative balance (Room transaction
 *     semantics + single-process mutex).
 *  4. There is deliberately NO method that adds an arbitrary amount from
 *     Flutter input. The only faucets are completed ad callbacks and
 *     (future) server-verified grants.
 */
class CoinLedger(
    private val database: MldDatabase,
    private val stateRepo: StateRepository,
) {
    private val mutex = Mutex()

    suspend fun balance(): Int {
        // v2.5.5 audit fix m-7: ALWAYS recompute from the append-only ledger
        // (the SUM is a cheap indexed aggregate). The old `if (cached > 0)
        // return cached` served a stale positive balance whenever a change
        // bypassed saveCoinBalance (external DB edit, restore, or a future
        // code path forgetting the cache update).
        val computed = database.coinDao().balance()
        stateRepo.saveCoinBalance(computed)
        return computed
    }

    /**
     * Award one coin for a completed rewarded ad.
     * @return true if a NEW transaction was inserted, false on duplicate.
     */
    suspend fun awardAd(rewardKey: String): Boolean = mutex.withLock {
        val id = "coin_$rewardKey"
        val inserted = database.coinDao().insertIgnore(
            CoinTransactionEntity(
                id = id,
                type = "AD_REWARD",
                amount = 1,
                timestampWall = System.currentTimeMillis(),
                source = "ad",
                reference = rewardKey,
            )
        )
        if (inserted != -1L) {
            val newBalance = database.coinDao().balance()
            stateRepo.saveCoinBalance(newBalance)
            database.dailyStatDao().bump(
                dateKey = com.maxleveldetox.enforcement.SessionEngine
                    .dateKeyFor(System.currentTimeMillis()),
                field = "coinsEarned", delta = 1,
            )
            true
        } else {
            false // duplicate callback — idempotency held the line
        }
    }

    /**
     * v2.5.7 (C-3): apply a SERVER-originated grant (admin adjustment /
     * bonus / refund) to the device ledger. Idempotent by the server's
     * transaction id (INSERT OR IGNORE with deterministic PK). Positive
     * amounts only — the server is the grant authority, the device is the
     * spend authority (offline-first).
     * @return true if a NEW grant was inserted, false on duplicate/invalid.
     */
    suspend fun applyGrant(transactionId: String, amount: Int, type: String): Boolean =
        mutex.withLock {
            if (amount <= 0 || amount > 1_000_000) return false
            val id = "coin_srv_$transactionId"
            val inserted = database.coinDao().insertIgnore(
                CoinTransactionEntity(
                    id = id,
                    type = if (type in SERVER_GRANT_TYPES) type else "ADMIN_ADJUSTMENT",
                    amount = amount,
                    timestampWall = System.currentTimeMillis(),
                    source = "server",
                    reference = transactionId,
                )
            )
            if (inserted != -1L) {
                val newBalance = database.coinDao().balance()
                stateRepo.saveCoinBalance(newBalance)
                true
            } else {
                false // already applied — idempotency held the line
            }
        }

    /** Server grant types accepted by [applyGrant] (mirrors the Worker's
     *  grant filter in routes/coins.ts). */
    private val SERVER_GRANT_TYPES = setOf("ADMIN_ADJUSTMENT", "BONUS", "REFUND")

    /**
     * Atomic spend. Returns (success, newBalance).
     * v2.5.7 (L-3): the transaction id now carries a random suffix — two
     * spends inside the same millisecond with the same reference used to
     * collide on the Room PK and crash a fast double-spend.
     */
    suspend fun spend(type: String, cost: Int, reference: String): Pair<Boolean, Int> =
        mutex.withLock {
            if (cost <= 0) return false to 0

            val current = database.coinDao().balance()
            if (current < cost) {
                return false to current
            }

            database.coinDao().insert(
                CoinTransactionEntity(
                    id = "ctx_${System.currentTimeMillis()}_${Integer.toHexString(System.identityHashCode(this))}_${reference.hashCode().toUInt()}_${(0..0xFFFF).random()}",
                    type = type,
                    amount = -cost,
                    timestampWall = System.currentTimeMillis(),
                    source = "device",
                    reference = reference,
                )
            )
            val newBalance = database.coinDao().balance()
            stateRepo.saveCoinBalance(newBalance)
            database.dailyStatDao().bump(
                dateKey = com.maxleveldetox.enforcement.SessionEngine
                    .dateKeyFor(System.currentTimeMillis()),
                field = "coinsSpent", delta = cost,
            )
            true to newBalance
        }

    suspend fun recent(limit: Int): List<CoinTransactionEntity> =
        database.coinDao().recent(limit)
}
