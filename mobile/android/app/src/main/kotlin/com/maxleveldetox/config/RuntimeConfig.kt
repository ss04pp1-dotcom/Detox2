package com.maxleveldetox.config

import com.maxleveldetox.storage.StateRepository
import org.json.JSONObject

/**
 * RuntimeConfig — bounded remote configuration (PRD §34–35, §51).
 *
 * SECURITY BOUNDARY:
 *  1. Defaults are compiled in and always safe.
 *  2. Remote values are schema/type/range validated and CLAMPED to the
 *     frozen bounds before being cached. Unknown keys are dropped.
 *  3. The cached config only affects FUTURE product behavior. An active
 *     session keeps its own policy snapshot (policyVersion) — remote config
 *     can never weaken an in-flight session (PRD §2 rule 7).
 */
class RuntimeConfig(private val stateRepo: StateRepository) {

    data class Config(
        val shortsWarningCount: Int = 5,          // 3..7
        val cageDurationSeconds: Int = 1800,      // 300..7200
        val tempUnlockCoins: Int = 5,             // 1..50
        val tempUnlockMinutes: Int = 5,           // 1..15
        val bailoutCoins: Int = 500,              // 50..5000
        val defaultStudyMinutes: Int = 60,        // 10..480
        val defaultDetoxMinutes: Int = 120,       // 30..1440
        val minSupportedVersion: String = "1.0.0",
        val maintenanceMode: Boolean = false,
        // v2.1 Phase C — progress layer economy knobs. These tune PACING
        // ONLY: they can never touch enforcement decisions (PRD §35).
        val gamificationEnabled: Boolean = true,
        val dpMultiplierFocus: Double = 1.0,      // 0.5..3.0
        val dpMultiplierReels: Double = 1.0,      // 0.5..3.0
        val dpMultiplierNeutral: Double = 1.0,    // 0.5..3.0
        val dpDailyCap: Int = 150,                // 50..1000
        val dpWeeklyCap: Int = 800,               // 200..5000
        val dpMonthlyCap: Int = 3000,             // 1000..20000
        // v2.2 Phase D — growth pacing (never enforcement). The payments
        // kill-switch + bKash fields live SERVER-side only; the app learns
        // gateway state from the /payments/bkash/instructions response.
        val breakPassesPerWeek: Int = 2,          // 0..5
        val insightNudgeEnabled: Boolean = true,
        val insightNudgeHour: Int = 20,           // 17..22, local device time
    )

    companion object {
        val BOUNDS = mapOf(
            "shortsWarningCount" to (3 to 7),
            "cageDurationSeconds" to (300 to 7200),
            "tempUnlockCoins" to (1 to 50),
            "tempUnlockMinutes" to (1 to 15),
            "bailoutCoins" to (50 to 5000),
            "defaultStudyMinutes" to (10 to 480),
            "defaultDetoxMinutes" to (30 to 1440),
            "dpDailyCap" to (50 to 1000),
            "dpWeeklyCap" to (200 to 5000),
            "dpMonthlyCap" to (1000 to 20000),
            // v2.2 Phase D — growth pacing bounds.
            "breakPassesPerWeek" to (0 to 5),
            "insightNudgeHour" to (17 to 22),
        )

        val DOUBLE_BOUNDS = mapOf(
            "dpMultiplierFocus" to (0.5 to 3.0),
            "dpMultiplierReels" to (0.5 to 3.0),
            "dpMultiplierNeutral" to (0.5 to 3.0),
        )

        val DEFAULTS = Config()
    }

    @Volatile
    private var cached: Config = DEFAULTS

    init {
        // Restore the last validated cache (offline-first, TRD §50/§51).
        stateRepo.blockingRemoteConfigJson()?.let { raw ->
            val parsed = validateAndClamp(raw)
            if (parsed != null) cached = parsed
        }
    }

    fun current(): Config = cached

    /**
     * Accept a remote config payload. Returns true only if the payload
     * passed schema + type validation (clamped values are still accepted —
     * clamping is recorded by the caller reading [lastClampLog]).
     */
    @Volatile
    var lastClampLog: List<String> = emptyList()
        private set

    fun validateAndClamp(rawJson: String): Config? {
        return try {
            val json = JSONObject(rawJson)
            val clamped = mutableListOf<String>()

            fun int(key: String, fallback: Int): Int {
                if (!json.has(key)) return fallback
                val v = json.opt(key)
                if (v !is Number) return fallback
                val bounds = BOUNDS[key] ?: return fallback
                val asInt = v.toInt()
                return if (asInt < bounds.first) {
                    clamped.add("$key: $asInt -> ${bounds.first}")
                    bounds.first
                } else if (asInt > bounds.second) {
                    clamped.add("$key: $asInt -> ${bounds.second}")
                    bounds.second
                } else asInt
            }

            val version = json.optString("minSupportedVersion", DEFAULTS.minSupportedVersion)
            val maintenance = json.optBoolean("maintenanceMode", false)

            fun dbl(key: String, fallback: Double): Double {
                if (!json.has(key)) return fallback
                val v = json.opt(key)
                if (v !is Number) return fallback
                val bounds = DOUBLE_BOUNDS[key] ?: return fallback
                val asDouble = v.toDouble()
                return asDouble.coerceIn(bounds.first, bounds.second)
            }

            val config = Config(
                shortsWarningCount = int("shortsWarningCount", DEFAULTS.shortsWarningCount),
                cageDurationSeconds = int("cageDurationSeconds", DEFAULTS.cageDurationSeconds),
                tempUnlockCoins = int("tempUnlockCoins", DEFAULTS.tempUnlockCoins),
                tempUnlockMinutes = int("tempUnlockMinutes", DEFAULTS.tempUnlockMinutes),
                bailoutCoins = int("bailoutCoins", DEFAULTS.bailoutCoins),
                defaultStudyMinutes = int("defaultStudyMinutes", DEFAULTS.defaultStudyMinutes),
                defaultDetoxMinutes = int("defaultDetoxMinutes", DEFAULTS.defaultDetoxMinutes),
                minSupportedVersion = version,
                maintenanceMode = maintenance,
                gamificationEnabled = json.optBoolean("gamificationEnabled", true),
                dpMultiplierFocus = dbl("dpMultiplierFocus", DEFAULTS.dpMultiplierFocus),
                dpMultiplierReels = dbl("dpMultiplierReels", DEFAULTS.dpMultiplierReels),
                dpMultiplierNeutral = dbl("dpMultiplierNeutral", DEFAULTS.dpMultiplierNeutral),
                dpDailyCap = int("dpDailyCap", DEFAULTS.dpDailyCap),
                dpWeeklyCap = int("dpWeeklyCap", DEFAULTS.dpWeeklyCap),
                dpMonthlyCap = int("dpMonthlyCap", DEFAULTS.dpMonthlyCap),
                // v2.2 Phase D — absent on pre-v2.2 configs -> defaults.
                breakPassesPerWeek = int("breakPassesPerWeek", DEFAULTS.breakPassesPerWeek),
                insightNudgeEnabled = json.optBoolean("insightNudgeEnabled", true),
                insightNudgeHour = int("insightNudgeHour", DEFAULTS.insightNudgeHour),
            )
            lastClampLog = clamped
            config
        } catch (_: Exception) {
            null // corrupt payload -> keep current cache (fail safe)
        }
    }

    /**
     * Apply + persist a remote payload. Rejected payloads never touch the
     * cached config.
     */
    suspend fun applyRemote(rawJson: String): Boolean {
        val parsed = validateAndClamp(rawJson) ?: return false
        cached = parsed
        stateRepo.saveRemoteConfigJson(rawJson)
        return true
    }
}
