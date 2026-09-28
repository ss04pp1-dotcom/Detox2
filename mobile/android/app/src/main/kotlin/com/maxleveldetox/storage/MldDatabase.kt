package com.maxleveldetox.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database: transaction-heavy enforcement data (TRD §57).
 *   - sessions        -> history
 *   - violations      -> audit + escalation display
 *   - coin_transactions-> IMMUTABLE ledger (append-only, idempotent inserts)
 *   - alarms          -> persisted schedules (re-armed on boot)
 *   - daily_stats     -> insights/streaks aggregates
 */

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val mode: String,            // STUDY | DETOX
    val status: String,          // COMPLETED | BAILOUT | INTERRUPTED
    val startWall: Long,
    val endWall: Long,
    val durationMinutes: Int,
    val completed: Boolean,
    val bailedOut: Boolean,
    val violations: Int,
    val cageTriggered: Boolean,
    val tempUnlockUsed: Boolean,
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startWall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE completed = 1 AND startWall >= :since")
    suspend fun completedSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM sessions WHERE completed = 1 AND mode = :mode AND startWall >= :since")
    suspend fun minutesSince(mode: String, since: Long): Int
}

@Entity(tableName = "violations")
data class ViolationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampWall: Long,
    val packageName: String,
    val type: String,
    val severity: String,
    val warningNumber: Int,
    val actionTaken: String,
)

@Dao
interface ViolationDao {
    @Insert
    suspend fun insert(violation: ViolationEntity)

    @Query("SELECT * FROM violations ORDER BY timestampWall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ViolationEntity>

    @Query("SELECT COUNT(*) FROM violations WHERE timestampWall >= :since")
    suspend fun countSince(since: Long): Int
}

@Entity(tableName = "coin_transactions")
data class CoinTransactionEntity(
    /** Deterministic id: "coin_<source-ref>" — idempotent award dedupe. */
    @PrimaryKey val id: String,
    val type: String,            // AD_REWARD | TEMP_UNLOCK_SPEND | BAILOUT_SPEND | BONUS | ...
    val amount: Int,             // signed
    val timestampWall: Long,
    val source: String,
    val reference: String,
)

@Dao
interface CoinDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(txn: CoinTransactionEntity): Long

    @Insert
    suspend fun insert(txn: CoinTransactionEntity)

    @Query("SELECT COALESCE(SUM(amount), 0) FROM coin_transactions")
    suspend fun balance(): Int

    @Query("SELECT * FROM coin_transactions ORDER BY timestampWall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CoinTransactionEntity>

    @Query("SELECT COUNT(*) FROM coin_transactions WHERE timestampWall >= :since AND amount > 0")
    suspend fun earnedSince(since: Long): Int
}

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    val repeatDays: String,      // JSON array of ISO days, "[]" = one-shot
    val difficulty: String,      // EASY | MEDIUM | HARD
    val enabled: Boolean,
    val label: String,
)

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: AlarmEntity)

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun byId(id: String): AlarmEntity?

    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    suspend fun all(): List<AlarmEntity>

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)
}

@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    @PrimaryKey val dateKey: String,   // yyyy-MM-dd (local)
    val focusSeconds: Int = 0,
    val detoxSeconds: Int = 0,
    val blockedAttempts: Int = 0,
    val shortsWarnings: Int = 0,
    val cageCount: Int = 0,
    val coinsEarned: Int = 0,
    val coinsSpent: Int = 0,
    val sessionsCompleted: Int = 0,
    val sessionsBailed: Int = 0,
    val dpEarned: Int = 0,            // v2 (Phase C)
    val alarmsCompleted: Int = 0,     // v2 (Phase C)
    val monkCompletions: Int = 0,     // v2 (Phase C)
    val tasksCompleted: Int = 0,      // v2.5 r9 (Social Sentry task economy)
    val distractingMinutes: Int = 0,  // v2.5.8 roadmap (trend widget) — daily HIGH-WATER mark of distracting-app minutes
)

@Dao
interface DailyStatDao {
    @androidx.room.Transaction
    suspend fun bump(dateKey: String, field: String, delta: Int) {
        val current = byKey(dateKey) ?: DailyStatEntity(dateKey)
        val updated = when (field) {
            "focusSeconds" -> current.copy(focusSeconds = current.focusSeconds + delta)
            "detoxSeconds" -> current.copy(detoxSeconds = current.detoxSeconds + delta)
            "blockedAttempts" -> current.copy(blockedAttempts = current.blockedAttempts + delta)
            "shortsWarnings" -> current.copy(shortsWarnings = current.shortsWarnings + delta)
            "cageCount" -> current.copy(cageCount = current.cageCount + delta)
            "coinsEarned" -> current.copy(coinsEarned = current.coinsEarned + delta)
            "coinsSpent" -> current.copy(coinsSpent = current.coinsSpent + delta)
            "sessionsCompleted" -> current.copy(sessionsCompleted = current.sessionsCompleted + delta)
            "sessionsBailed" -> current.copy(sessionsBailed = current.sessionsBailed + delta)
            "dpEarned" -> current.copy(dpEarned = current.dpEarned + delta)
            "alarmsCompleted" -> current.copy(alarmsCompleted = current.alarmsCompleted + delta)
            "monkCompletions" -> current.copy(monkCompletions = current.monkCompletions + delta)
            "tasksCompleted" -> current.copy(tasksCompleted = current.tasksCompleted + delta)
            // v2.5.8: high-water bump, not an increment — the sampler writes
            // the CURRENT cumulative minutes for today (max).
            "distractingMinutesMax" ->
                current.copy(distractingMinutes = maxOf(current.distractingMinutes, delta))
            else -> current
        }
        upsert(updated)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: DailyStatEntity)

    @Query("SELECT * FROM daily_stats WHERE dateKey = :key")
    suspend fun byKey(key: String): DailyStatEntity?

    @Query("SELECT * FROM daily_stats WHERE dateKey >= :sinceKey ORDER BY dateKey")
    suspend fun since(sinceKey: String): List<DailyStatEntity>
}

// ---------------------------------------------------------------------------
// v2.1 Phase C — gamification ledger + relapse history (append-only)
// ---------------------------------------------------------------------------

@Entity(tableName = "dp_awards")
data class DpAwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reason: String,           // DpReason name
    val amount: Int,              // awarded (post-multiplier, post-cap)
    val multiplierApplied: Double,
    val capped: Boolean,          // true when a cap trimmed the award
    val timestampWall: Long,
    val dateKey: String,          // yyyy-MM-dd (local)
)

@Dao
interface DpAwardDao {
    @Insert
    suspend fun insert(award: DpAwardEntity)

    @Query("SELECT * FROM dp_awards ORDER BY timestampWall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DpAwardEntity>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM dp_awards")
    suspend fun totalEarned(): Int

    @Query("DELETE FROM dp_awards")
    suspend fun deleteAll()
}

@Entity(tableName = "relapse_events")
data class RelapseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampWall: Long,
    val dateKey: String,
    val reason: String,
    val streakDaysAtRelapse: Int,
    val source: String,           // PRIME_GIVEUP | PROTECTION_LOST
)

@Dao
interface RelapseDao {
    @Insert
    suspend fun insert(relapse: RelapseEntity)

    @Query("SELECT * FROM relapse_events ORDER BY timestampWall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RelapseEntity>

    @Query("SELECT COUNT(*) FROM relapse_events WHERE dateKey = :dateKey")
    suspend fun countForDate(dateKey: String): Int

    @Query("DELETE FROM relapse_events")
    suspend fun deleteAll()
}

@Database(
    entities = [
        SessionEntity::class,
        ViolationEntity::class,
        CoinTransactionEntity::class,
        AlarmEntity::class,
        DailyStatEntity::class,
        DpAwardEntity::class,
        RelapseEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MldDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun violationDao(): ViolationDao
    abstract fun coinDao(): CoinDao
    abstract fun alarmDao(): AlarmDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun dpAwardDao(): DpAwardDao
    abstract fun relapseDao(): RelapseDao

    companion object {
        /** v1 -> v2 (Phase C): gamification tables + daily_stats columns. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dp_awards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`reason` TEXT NOT NULL, " +
                        "`amount` INTEGER NOT NULL, " +
                        "`multiplierApplied` REAL NOT NULL, " +
                        "`capped` INTEGER NOT NULL, " +
                        "`timestampWall` INTEGER NOT NULL, " +
                        "`dateKey` TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relapse_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestampWall` INTEGER NOT NULL, " +
                        "`dateKey` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, " +
                        "`streakDaysAtRelapse` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL)")
                db.execSQL("ALTER TABLE `daily_stats` ADD COLUMN `dpEarned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `daily_stats` ADD COLUMN `alarmsCompleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `daily_stats` ADD COLUMN `monkCompletions` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3 (r9): Social Sentry task economy column. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_stats` ADD COLUMN `tasksCompleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 -> v4 (v2.5.8 roadmap): trend-widget column — daily
         *  distracting-app minutes high-water mark (UsageStats cumulative,
         *  sampled periodically by TrendSampler). */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_stats` ADD COLUMN `distractingMinutes` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): MldDatabase =
            Room.databaseBuilder(context, MldDatabase::class.java, "mld.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
