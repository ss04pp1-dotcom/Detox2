package com.maxleveldetox.enforcement

import org.json.JSONArray
import org.json.JSONObject

/**
 * Enforcement domain models + JSON wire format (the contract the Flutter
 * side consumes via the state stream — see data/models.dart).
 *
 * SECURITY INVARIANT: every value here is derived from persisted native
 * state. Nothing in this file trusts Flutter input.
 */

enum class SessionMode { STUDY, DETOX;

    companion object {
        fun fromName(name: String?): SessionMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DETOX
    }
}

/**
 * Unified session state machine (PRD §8 ⊕ TRD §5 superset):
 *
 *   IDLE → ARMED → STARTING → ACTIVE ⇄ TEMP_UNLOCK
 *   ACTIVE → PAUSED → ACTIVE  (study break, r9.4: bounded, extends the end)
 *   ACTIVE → CAGE (shorts escalation, tracked separately as cageState)
 *   ACTIVE → COMPLETING → COMPLETED → (cleared to IDLE)
 *   ACTIVE → BAILOUT (after native coin spend) → IDLE
 *   any → RECOVERY (permission loss / process death) → ACTIVE
 *   any → ERROR (unvalidated state; safe hold, never auto-unlock)
 */
enum class SessionStatus { IDLE, ARMED, STARTING, ACTIVE, TEMP_UNLOCK, CAGE, COMPLETING, COMPLETED, BAILOUT, RECOVERY, ERROR, PAUSED;

    /** Enforcement is running. PAUSED is deliberately NOT enforcing: during a
     *  study break every enforcement path (policy, a11y, guards) stands down. */
    val isEnforcing: Boolean
        get() = this == ACTIVE || this == TEMP_UNLOCK || this == CAGE || this == RECOVERY

    /** A session exists (enforcing OR on a study break). Use this — not
     *  isEnforcing — for "is there a session I must not overwrite / show". */
    val isLive: Boolean
        get() = isEnforcing || this == PAUSED

    companion object {
        fun fromName(name: String?): SessionStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: IDLE
    }
}

enum class Strictness { BALANCED, STRICT, MAXLEVEL;

    companion object {
        fun fromName(name: String?): Strictness =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MAXLEVEL
    }
}

/** Policy verdict for a package (TRD §10). */
enum class PolicyDecision { ALLOW, BLOCK, TEMP_ALLOW, CAGE_BLOCK, MONK_BLOCK, SYSTEM_ALLOW, EMERGENCY_ALLOW }

enum class ViolationType(val label: String) {
    BLOCKED_APP("Blocked app"),
    SHORTS_ENTRY("Shorts attempt"),
    PERMISSION_TAMPER("Permission changed"),
    RESTRICTED_SETTING("Restricted setting"),
    UNAUTHORIZED_UNLOCK("Unlock attempt"),
    SESSION_TAMPER("Session tamper"),
    MONK_EXIT("Left allowed app"),
    PRIME_GIVEUP("Prime commit give-up"),
}

/** Immutable snapshot of the active session as persisted in DataStore. */
data class SessionSnapshot(
    val id: String,
    val mode: SessionMode,
    val status: SessionStatus,
    val startElapsed: Long,   // SystemClock.elapsedRealtime() — TRD §7/§52
    val endElapsed: Long,
    val createdAtWall: Long,  // display/logs only, never authority
    val strictness: Strictness,
    val policyVersion: Int,
    val allowedPackages: Set<String>,
    val blockedCategories: Set<String>,
    val violationCount: Int,
    // ---- v2.5 r9.4: study break (pause/resume) + subject --------------
    /** Elapsed-realtime instant the current break began (0 = not paused). */
    val pausedAtElapsed: Long = 0L,
    /** Elapsed-realtime instant the current break auto-ends. */
    val pauseEndElapsed: Long = 0L,
    /** Breaks taken so far in this session. */
    val pauseCount: Int = 0,
    /** Total seconds spent on breaks (already added to endElapsed). */
    val pausedTotalSeconds: Int = 0,
    /** Optional study subject label ("" = none). */
    val subjectName: String = "",
) {
    /** While PAUSED the clock is frozen at the instant the break began. */
    fun remainingSeconds(now: Long): Int {
        val ref = if (status == SessionStatus.PAUSED && pausedAtElapsed > 0L) pausedAtElapsed else now
        return ((endElapsed - ref) / 1000L).coerceAtLeast(0).toInt()
    }
    fun totalSeconds(): Int = ((endElapsed - startElapsed) / 1000L).coerceAtLeast(1).toInt()
    /** A paused session never expires — the break auto-ends first and pushes
     *  the end back by the break length. */
    fun isExpired(now: Long): Boolean = status != SessionStatus.PAUSED && now >= endElapsed

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("mode", mode.name)
        put("status", status.name)
        put("remainingSeconds", remainingSeconds(SystemClockNow.elapsed))
        put("totalSeconds", totalSeconds())
        put("strictness", strictness.name)
        put("policyVersion", policyVersion)
        put("violationCount", violationCount)
        put("blockedAppCount", blockedAppCount)
        put("allowedPackages", JSONArray(allowedPackages.toList()))
        put("blockedCategories", JSONArray(blockedCategories.toList()))
        put("paused", status == SessionStatus.PAUSED)
        put("pauseRemainingSeconds",
            if (status == SessionStatus.PAUSED)
                ((pauseEndElapsed - SystemClockNow.elapsed) / 1000L).coerceAtLeast(0).toInt()
            else 0)
        put("pauseCount", pauseCount)
        put("maxPauses", MAX_PAUSES)
        put("pausedTotalSeconds", pausedTotalSeconds)
        put("subjectName", subjectName)
    }

    /** Number of apps the policy engine currently restricts (approximated
     *  by the installed launcher-visible count for DETOX/MAXLEVEL). */
    val blockedAppCount: Int get() = cachedBlockedCount

    companion object {
        var cachedBlockedCount: Int = 0

        /** Study breaks: at most this many per session, each at most
         *  MAX_PAUSE_MINUTES long (auto-resume when it runs out). */
        const val MAX_PAUSES = 3
        const val MAX_PAUSE_MINUTES = 10

        fun fromJson(json: JSONObject?): SessionSnapshot? {
            if (json == null || !json.has("id")) return null
            return SessionSnapshot(
                id = json.getString("id"),
                mode = SessionMode.fromName(json.optString("mode")),
                status = SessionStatus.fromName(json.optString("status")),
                startElapsed = json.optLong("startElapsed", 0L),
                endElapsed = json.optLong("endElapsed", 0L),
                createdAtWall = json.optLong("createdAtWall", 0L),
                strictness = Strictness.fromName(json.optString("strictness")),
                policyVersion = json.optInt("policyVersion", 1),
                allowedPackages = json.optJSONArray("allowedPackages")?.toStringSet() ?: emptySet(),
                blockedCategories = json.optJSONArray("blockedCategories")?.toStringSet() ?: emptySet(),
                violationCount = json.optInt("violationCount", 0),
                pausedAtElapsed = json.optLong("pausedAtElapsed", 0L),
                pauseEndElapsed = json.optLong("pauseEndElapsed", 0L),
                pauseCount = json.optInt("pauseCount", 0),
                pausedTotalSeconds = json.optInt("pausedTotalSeconds", 0),
                subjectName = json.optString("subjectName", ""),
            )
        }

        private fun JSONArray.toStringSet(): Set<String> {
            val set = mutableSetOf<String>()
            for (i in 0 until length()) set.add(optString(i))
            return set
        }
    }
}

/** Cage state — independent of the session, independently persisted. */
data class CageSnapshot(
    val active: Boolean,
    val startElapsed: Long,
    val endElapsed: Long,
) {
    fun remainingSeconds(now: Long): Int = ((endElapsed - now) / 1000L).coerceAtLeast(0).toInt()
    fun isExpired(now: Long): Boolean = now >= endElapsed

    fun toJson(now: Long): JSONObject = JSONObject().apply {
        put("active", active && !isExpired(now))
        put("remainingSeconds", remainingSeconds(now))
    }

    companion object {
        val INACTIVE = CageSnapshot(false, 0L, 0L)

        fun fromJson(json: JSONObject?): CageSnapshot {
            if (json == null) return INACTIVE
            return CageSnapshot(
                active = json.optBoolean("active", false),
                startElapsed = json.optLong("startElapsed", 0L),
                endElapsed = json.optLong("endElapsed", 0L),
            )
        }
    }
}

/** Temporary unlock window (PRD §15–17). */
data class TempUnlockSnapshot(
    val active: Boolean,
    val startElapsed: Long,
    val endElapsed: Long,
    val allowedPackages: Set<String>,
) {
    fun remainingSeconds(now: Long): Int = ((endElapsed - now) / 1000L).coerceAtLeast(0).toInt()
    fun isExpired(now: Long): Boolean = now >= endElapsed

    fun allows(pkg: String, now: Long): Boolean = active && !isExpired(now) && pkg in allowedPackages

    fun toJson(now: Long): JSONObject = JSONObject().apply {
        put("active", active && !isExpired(now))
        put("remainingSeconds", remainingSeconds(now))
        put("allowedPackages", JSONArray(allowedPackages.toList()))
    }

    companion object {
        val INACTIVE = TempUnlockSnapshot(false, 0L, 0L, emptySet())

        fun fromJson(json: JSONObject?): TempUnlockSnapshot {
            if (json == null) return INACTIVE
            val pkgs = mutableSetOf<String>()
            val arr = json.optJSONArray("allowedPackages")
            if (arr != null) for (i in 0 until arr.length()) pkgs.add(arr.optString(i))
            return TempUnlockSnapshot(
                active = json.optBoolean("active", false),
                startElapsed = json.optLong("startElapsed", 0L),
                endElapsed = json.optLong("endElapsed", 0L),
                allowedPackages = pkgs,
            )
        }
    }
}

/** Shorts blocker state: shared cross-platform warning counter (PRD §14). */
data class ShortsState(
    val enabled: Boolean,
    val warningCount: Int,
    val warningDateKey: String,   // daily reset (local date)
    val platforms: Map<String, Boolean>,  // package -> enabled
) {
    fun toJson(warningLimit: Int): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("warningCount", warningCount)
        put("warningLimit", warningLimit)
        val arr = JSONArray()
        platforms.forEach { (pkg, enabled) ->
            arr.put(JSONObject().put("packageName", pkg).put("appName", APP_NAMES[pkg] ?: pkg).put("enabled", enabled))
        }
        put("platforms", arr)
    }

    companion object {
        val DEFAULT_PLATFORMS = linkedMapOf(
            "com.instagram.android" to true,
            "com.google.android.youtube" to true,
            "com.facebook.katana" to true,
            "com.zhiliaoapp.musically" to true,   // TikTok
            "com.ss.android.ugc.trill" to true,   // TikTok (regional)
        )

        val APP_NAMES = mapOf(
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.facebook.katana" to "Facebook",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
        )

        fun fromJson(json: JSONObject?): ShortsState {
            if (json == null) {
                return ShortsState(true, 0, "", DEFAULT_PLATFORMS)
            }
            val platforms = mutableMapOf<String, Boolean>()
            DEFAULT_PLATFORMS.forEach { (pkg, en) -> platforms[pkg] = en }
            val arr = json.optJSONArray("platforms")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    platforms[o.optString("packageName")] = o.optBoolean("enabled", true)
                }
            }
            return ShortsState(
                enabled = json.optBoolean("enabled", true),
                warningCount = json.optInt("warningCount", 0),
                warningDateKey = json.optString("warningDateKey", ""),
                platforms = platforms,
            )
        }
    }
}

/**
 * Monotonic clock indirection — one seam so tests can inject time and the
 * rest of the engine never touches wall-clock for authority (TRD §52).
 */
object SystemClockNow {
    val elapsed: Long
        get() = android.os.SystemClock.elapsedRealtime()
}

/** Structured native error codes (TRD §74). */
object ErrorCodes {
    const val ACCESSIBILITY_DISABLED = "ACCESSIBILITY_DISABLED"
    const val USAGE_ACCESS_DISABLED = "USAGE_ACCESS_DISABLED"
    const val OVERLAY_DISABLED = "OVERLAY_DISABLED"
    const val SESSION_NOT_ACTIVE = "SESSION_NOT_ACTIVE"
    const val SESSION_NOT_COMPLETE = "SESSION_NOT_COMPLETE"
    const val INSUFFICIENT_COINS = "INSUFFICIENT_COINS"
    const val TEMP_UNLOCK_ACTIVE = "TEMP_UNLOCK_ACTIVE"
    const val CAGE_ACTIVE = "CAGE_ACTIVE"
    const val PRIME_ACTIVE = "PRIME_ACTIVE"
    const val INVALID_SESSION = "INVALID_SESSION"
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val ALARM_NOT_ALLOWED = "ALARM_NOT_ALLOWED"
    const val SYSTEM_RESTRICTION = "SYSTEM_RESTRICTION"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val PAUSE_NOT_ALLOWED = "PAUSE_NOT_ALLOWED"
    const val UNKNOWN = "UNKNOWN"
}
