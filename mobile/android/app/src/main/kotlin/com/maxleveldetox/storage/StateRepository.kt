package com.maxleveldetox.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maxleveldetox.enforcement.CageSnapshot
import com.maxleveldetox.enforcement.SessionSnapshot
import com.maxleveldetox.enforcement.ShortsState
import com.maxleveldetox.enforcement.TempUnlockSnapshot
import com.maxleveldetox.prime.PrimeCommitManager
import com.maxleveldetox.reels.ReelsEscalationManager
import com.maxleveldetox.safety.SafetyPauseManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mld_enforcement")

/**
 * Persistence for enforcement-critical state (TRD §36).
 *
 * WRITE-BEFORE-STATE discipline (TRD §94): callers persist first, then
 * update in-memory state. Synchronous [blocking] accessors are provided for
 * the accessibility event path where a coroutine dispatch would be too
 * slow to answer "is this package blocked?".
 */
class StateRepository(private val context: Context) {

    /** v2.5 r9: application context for engine helpers (auto-reblock). */
    fun contextRef(): Context = context

    private object Keys {
        val SESSION = stringPreferencesKey("active_session")
        val CAGE = stringPreferencesKey("cage_state")
        val TEMP_UNLOCK = stringPreferencesKey("temp_unlock")
        val SHORTS = stringPreferencesKey("shorts_state")
        val COIN_BALANCE = stringPreferencesKey("coin_balance_cache")
        val PERMISSIONS = stringPreferencesKey("permission_snapshot")
        val REMOTE_CONFIG = stringPreferencesKey("remote_config_cache")
        // v2.5.8 roadmap: server-pushed reels/shorts detection signatures.
        val DETECTION_RULES = stringPreferencesKey("detection_rules_cache")
        val ONBOARDING = stringPreferencesKey("onboarding_complete")
        val PACT = stringPreferencesKey("pact_accepted")
        val SETTINGS_GRACE = stringPreferencesKey("settings_grace_window")

        // v2.0 Phase B: reels escalation / safety pause / prime commit /
        // emergency TOTP secret + replay ledger.
        val REELS = stringPreferencesKey("reels_escalation_state")
        val SAFETY = stringPreferencesKey("safety_pause_state")
        val SAFETY_COUNTERS = stringPreferencesKey("safety_pause_counters")
        val PRIME = stringPreferencesKey("prime_commit_state")
        val EMERGENCY_SECRET = stringPreferencesKey("emergency_totp_secret")
        val EMERGENCY_USED = stringPreferencesKey("emergency_totp_used")

        // v2.1 Phase C: gamification (DP / streak / protection / check-in).
        val DP = stringPreferencesKey("dp_state")
        val STREAK = stringPreferencesKey("streak_state")
        val PROTECTION = stringPreferencesKey("streak_protection_state")
        val CHECKIN = stringPreferencesKey("checkin_state")

        // v2.2 Phase D: break passes + insight/announcement delivery state.
        val BREAK_PASSES = stringPreferencesKey("break_pass_state")
        val INSIGHT_SEEN = stringPreferencesKey("insight_seen_date")
        val ANNOUNCEMENTS_SEEN = stringPreferencesKey("announcements_seen_ids")

        // v2.3 r7: per-app daily limits + blocking schedules (raw JSON,
        // single writers are AppLimitEngine / ScheduleEngine).
        val APP_LIMITS = stringPreferencesKey("app_limits_raw")
        val SCHEDULES = stringPreferencesKey("schedules_raw")

        // v2.5.5 audit fix M-1: per-package user block/allow overrides
        // (single writer is AppRulesStore).
        val APP_RULES = stringPreferencesKey("app_rules_raw")
    }

    // -----------------------------------------------------------------
    // Active session
    // -----------------------------------------------------------------

    suspend fun saveSession(snapshot: SessionSnapshot?) {
        context.dataStore.edit { prefs ->
            if (snapshot == null) prefs.remove(Keys.SESSION)
            else prefs[Keys.SESSION] = snapshot.run {
                JSONObject().apply {
                    put("id", id)
                    put("mode", mode.name)
                    put("status", status.name)
                    put("startElapsed", startElapsed)
                    put("endElapsed", endElapsed)
                    put("createdAtWall", createdAtWall)
                    put("strictness", strictness.name)
                    put("policyVersion", policyVersion)
                    put("allowedPackages", org.json.JSONArray(allowedPackages.toList()))
                    put("blockedCategories", org.json.JSONArray(blockedCategories.toList()))
                    put("violationCount", violationCount)
                    put("pausedAtElapsed", pausedAtElapsed)
                    put("pauseEndElapsed", pauseEndElapsed)
                    put("pauseCount", pauseCount)
                    put("pausedTotalSeconds", pausedTotalSeconds)
                    put("subjectName", subjectName)
                    stampClocks(this, endElapsed)
                }.toString()
            }
        }
    }

    // -----------------------------------------------------------------
    // Reboot safety (v2.5 r9.4).
    //
    // Session / cage / temp-unlock timing is stored in elapsedRealtime, which
    // RESTARTS AT ZERO on every boot. Read back after a reboot, an old
    // `endElapsed` (an uptime from the previous boot) is compared with the new
    // boot's tiny uptime — so a session, a cage or a temporary unlock would
    // silently last for hours or days longer (a temp unlock would outlive the
    // reboot: a free unlock). Each persisted timeline is therefore stamped with
    // the wall-clock end + boot count at save time, and rebased on first read
    // after a reboot.
    // -----------------------------------------------------------------

    private fun currentBootCount(): Int = try {
        android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /** Stamp [o] with what is needed to rebase it after a reboot. */
    private fun stampClocks(o: JSONObject, endElapsed: Long) {
        val nowEl = com.maxleveldetox.enforcement.SystemClockNow.elapsed
        o.put("endWall", System.currentTimeMillis() + (endElapsed - nowEl))
        o.put("savedElapsed", nowEl)
        o.put("bootCount", currentBootCount())
    }

    /** True when [o] was written during an earlier boot. */
    private fun rebootedSince(o: JSONObject): Boolean {
        val savedBoot = o.optInt("bootCount", -1)
        val curBoot = currentBootCount()
        if (savedBoot != -1 && curBoot != -1 && savedBoot != curBoot) return true
        val savedEl = o.optLong("savedElapsed", -1L)
        return savedEl >= 0L &&
            com.maxleveldetox.enforcement.SystemClockNow.elapsed < savedEl
    }

    /** Session JSON rebased into the current boot's elapsed domain. */
    private fun rebaseSessionJson(o: JSONObject): JSONObject {
        val nowEl = com.maxleveldetox.enforcement.SystemClockNow.elapsed
        val nowWall = System.currentTimeMillis()
        val start = o.optLong("startElapsed", 0L)
        val end = o.optLong("endElapsed", 0L)
        val total = (end - start).coerceAtLeast(1_000L)
        val pausedAt = o.optLong("pausedAtElapsed", 0L)
        val paused = o.optString("status") == "PAUSED" && pausedAt > 0L

        val remaining: Long
        if (paused) {
            // Remaining time is frozen during a break; both values come from
            // the SAME (old) boot, so their difference is still valid.
            remaining = (end - pausedAt).coerceAtLeast(0L)
            // A reboot ends the break (conservative).
            o.put("status", "ACTIVE")
            o.put("pausedAtElapsed", 0L)
            o.put("pauseEndElapsed", 0L)
        } else {
            val endWall = o.optLong("endWall", 0L).takeIf { it > 0L }
                ?: (o.optLong("createdAtWall", nowWall) + total)
            remaining = (endWall - nowWall).coerceAtLeast(0L)
        }
        val newEnd = nowEl + remaining
        o.put("endElapsed", newEnd)
        o.put("startElapsed", newEnd - total)
        stampClocks(o, newEnd)
        return o
    }

    /** Blocking read for the enforcement hot path. */
    fun blockingSession(): SessionSnapshot? = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.SESSION] ?: return@runBlocking null
        return@runBlocking try {
            val o = JSONObject(raw)
            if (rebootedSince(o)) {
                rebaseSessionJson(o)   // mutates [o] in place
                context.dataStore.edit { it[Keys.SESSION] = o.toString() }
            } else if (!o.has("bootCount")) {
                // Session written by an older build: stamp it once.
                stampClocks(o, o.optLong("endElapsed", 0L))
                context.dataStore.edit { it[Keys.SESSION] = o.toString() }
            }
            SessionSnapshot.fromJson(o)
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------
    // Cage
    // -----------------------------------------------------------------

    suspend fun saveCage(snapshot: CageSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CAGE] = JSONObject().apply {
                put("active", snapshot.active)
                put("startElapsed", snapshot.startElapsed)
                put("endElapsed", snapshot.endElapsed)
                stampClocks(this, snapshot.endElapsed)
            }.toString()
        }
    }

    fun blockingCage(): CageSnapshot = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.CAGE] ?: return@runBlocking CageSnapshot.INACTIVE
        return@runBlocking try {
            val o = JSONObject(raw)
            if (rebootedSince(o)) {
                // A cage survives a reboot for its remaining wall-clock time
                // (a legacy record without a wall end simply ends).
                val nowEl = com.maxleveldetox.enforcement.SystemClockNow.elapsed
                val endWall = o.optLong("endWall", 0L)
                val remaining = if (endWall > 0L)
                    (endWall - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
                val total = (o.optLong("endElapsed", 0L) - o.optLong("startElapsed", 0L))
                    .coerceAtLeast(1_000L)
                o.put("endElapsed", nowEl + remaining)
                o.put("startElapsed", nowEl + remaining - total)
                if (remaining <= 0L) o.put("active", false)
                stampClocks(o, nowEl + remaining)
                context.dataStore.edit { it[Keys.CAGE] = o.toString() }
            }
            CageSnapshot.fromJson(o)
        } catch (_: Exception) {
            CageSnapshot.INACTIVE
        }
    }

    // -----------------------------------------------------------------
    // Temporary unlock
    // -----------------------------------------------------------------

    suspend fun saveTempUnlock(snapshot: TempUnlockSnapshot) {
        context.dataStore.edit { prefs ->
            if (!snapshot.active) prefs.remove(Keys.TEMP_UNLOCK)
            else prefs[Keys.TEMP_UNLOCK] = JSONObject().apply {
                put("active", snapshot.active)
                put("startElapsed", snapshot.startElapsed)
                put("endElapsed", snapshot.endElapsed)
                put("allowedPackages", org.json.JSONArray(snapshot.allowedPackages.toList()))
                stampClocks(this, snapshot.endElapsed)
            }.toString()
        }
    }

    fun blockingTempUnlock(): TempUnlockSnapshot = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.TEMP_UNLOCK] ?: return@runBlocking TempUnlockSnapshot.INACTIVE
        return@runBlocking try {
            val o = JSONObject(raw)
            if (rebootedSince(o)) {
                // A temporary unlock never outlives a reboot (it used to:
                // its old uptime-based end looked hours/days away).
                context.dataStore.edit { it.remove(Keys.TEMP_UNLOCK) }
                TempUnlockSnapshot.INACTIVE
            } else {
                TempUnlockSnapshot.fromJson(o)
            }
        } catch (_: Exception) {
            TempUnlockSnapshot.INACTIVE
        }
    }

    // -----------------------------------------------------------------
    // Shorts blocker
    // -----------------------------------------------------------------

    suspend fun saveShorts(state: ShortsState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHORTS] = JSONObject().apply {
                put("enabled", state.enabled)
                put("warningCount", state.warningCount)
                put("warningDateKey", state.warningDateKey)
                put("platforms", org.json.JSONArray().apply {
                    state.platforms.forEach { (pkg, en) ->
                        put(JSONObject().put("packageName", pkg).put("enabled", en))
                    }
                })
            }.toString()
        }
    }

    fun blockingShorts(): ShortsState = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.SHORTS] ?: return@runBlocking ShortsState(true, 0, "", ShortsState.DEFAULT_PLATFORMS)
        return@runBlocking try {
            ShortsState.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            ShortsState(true, 0, "", ShortsState.DEFAULT_PLATFORMS)
        }
    }

    // -----------------------------------------------------------------
    // Coin balance cache (ledger in Room is the source of truth)
    // -----------------------------------------------------------------

    suspend fun saveCoinBalance(balance: Int) {
        context.dataStore.edit { it[Keys.COIN_BALANCE] = balance.toString() }
    }

    fun blockingCoinBalance(): Int = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[Keys.COIN_BALANCE]?.toIntOrNull() ?: 0
    }

    // -----------------------------------------------------------------
    // Remote config cache (validated + clamped before it ever lands here)
    // -----------------------------------------------------------------

    suspend fun saveRemoteConfigJson(json: String) {
        context.dataStore.edit { it[Keys.REMOTE_CONFIG] = json }
    }

    fun blockingRemoteConfigJson(): String? = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[Keys.REMOTE_CONFIG]
    }

    // -----------------------------------------------------------------
    // Detection-rules cache (v2.5.8 — validated strictly before it lands
    // here; the compiled defaults in ReelsDetector stay the fallback)
    // -----------------------------------------------------------------

    suspend fun saveDetectionRulesJson(json: String) {
        context.dataStore.edit { it[Keys.DETECTION_RULES] = json }
    }

    fun blockingDetectionRulesJson(): String? = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[Keys.DETECTION_RULES]
    }

    // -----------------------------------------------------------------
    // Permission snapshot (tamper diffing)
    // -----------------------------------------------------------------

    suspend fun savePermissionSnapshot(json: String) {
        context.dataStore.edit { it[Keys.PERMISSIONS] = json }
    }

    fun blockingPermissionSnapshot(): String? = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[Keys.PERMISSIONS]
    }

    // -----------------------------------------------------------------
    // Onboarding / pact gates
    // -----------------------------------------------------------------

    suspend fun setOnboardingComplete(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING] = done.toString() }
    }

    suspend fun setPactAccepted(done: Boolean) {
        context.dataStore.edit { it[Keys.PACT] = done.toString() }
    }

    fun blockingOnboardingComplete(): Boolean = runBlocking {
        context.dataStore.data.first()[Keys.ONBOARDING] == "true"
    }

    fun blockingPactAccepted(): Boolean = runBlocking {
        context.dataStore.data.first()[Keys.PACT] == "true"
    }

    // -----------------------------------------------------------------
    // Settings grace window — time-bounded, purpose-tagged, audited.
    // Lets the user reach a specific settings screen to RESTORE a lost
    // permission during a session, without opening the door to arbitrary
    // settings browsing (TRD §84 — "detect and respond").
    // -----------------------------------------------------------------

    data class GraceWindow(val pkg: String, val purpose: String, val expiresElapsed: Long)

    suspend fun saveGraceWindow(window: GraceWindow?) {
        context.dataStore.edit { prefs ->
            if (window == null) prefs.remove(Keys.SETTINGS_GRACE)
            else prefs[Keys.SETTINGS_GRACE] = JSONObject().apply {
                put("pkg", window.pkg)
                put("purpose", window.purpose)
                put("expiresElapsed", window.expiresElapsed)
            }.toString()
        }
    }

    fun blockingGraceWindow(): GraceWindow? = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.SETTINGS_GRACE] ?: return@runBlocking null
        return@runBlocking try {
            val o = JSONObject(raw)
            GraceWindow(
                pkg = o.getString("pkg"),
                purpose = o.getString("purpose"),
                expiresElapsed = o.getLong("expiresElapsed"),
            )
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------
    // Reels escalation ladder (v2.0 Phase B2)
    // -----------------------------------------------------------------

    suspend fun saveReels(state: ReelsEscalationManager.ReelsState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REELS] = JSONObject().apply {
                put("consecutiveCount", state.consecutiveCount)
                put("lastBlockElapsed", state.lastBlockElapsed)
                put("hardLockoutCountToday", state.hardLockoutCountToday)
                put("emergencyPassesUsedToday", state.emergencyPassesUsedToday)
                put("allowanceRemainingMs", state.allowanceRemainingMs)
                put("unblockStartElapsed", state.unblockStartElapsed)
                put("unblockEndElapsed", state.unblockEndElapsed)
                put("unblockPackage", state.unblockPackage)
                put("dateKey", state.dateKey)
                put("dailyLimitMinutes", state.dailyLimitMinutes)
            }.toString()
        }
    }

    fun blockingReels(): ReelsEscalationManager.ReelsState = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.REELS]
            ?: return@runBlocking ReelsEscalationManager.ReelsState()
        return@runBlocking try {
            val o = JSONObject(raw)
            ReelsEscalationManager.ReelsState(
                consecutiveCount = o.optInt("consecutiveCount", 0),
                lastBlockElapsed = o.optLong("lastBlockElapsed", 0L),
                hardLockoutCountToday = o.optInt("hardLockoutCountToday", 0),
                emergencyPassesUsedToday = o.optInt("emergencyPassesUsedToday", 0),
                allowanceRemainingMs = o.optLong("allowanceRemainingMs",
                    ReelsEscalationManager.DEFAULT_ALLOWANCE_MINUTES * 60_000L),
                unblockStartElapsed = o.optLong("unblockStartElapsed", 0L),
                unblockEndElapsed = o.optLong("unblockEndElapsed", 0L),
                unblockPackage = o.optString("unblockPackage", ""),
                dateKey = o.optString("dateKey", ""),
                dailyLimitMinutes = o.optInt("dailyLimitMinutes",
                    ReelsEscalationManager.DEFAULT_ALLOWANCE_MINUTES),
            )
        } catch (_: Exception) {
            ReelsEscalationManager.ReelsState()
        }
    }

    // -----------------------------------------------------------------
    // Safety pause (v2.0 Phase B4)
    // -----------------------------------------------------------------

    suspend fun saveSafety(state: SafetyPauseManager.SafetyState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SAFETY] = JSONObject().apply {
                put("enabled", state.enabled)
                put("apps", org.json.JSONArray(state.apps.toList()))
                put("pauseSeconds", state.pauseSeconds)
            }.toString()
        }
    }

    fun blockingSafety(): SafetyPauseManager.SafetyState = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.SAFETY]
            ?: return@runBlocking SafetyPauseManager.SafetyState.DEFAULT
        return@runBlocking try {
            val o = JSONObject(raw)
            val apps = mutableSetOf<String>()
            val arr = o.optJSONArray("apps")
            if (arr != null) for (i in 0 until arr.length()) apps.add(arr.optString(i))
            SafetyPauseManager.SafetyState(
                enabled = o.optBoolean("enabled", false),
                apps = apps,
                pauseSeconds = o.optInt("pauseSeconds",
                    SafetyPauseManager.DEFAULT_PAUSE_SECONDS),
            )
        } catch (_: Exception) {
            SafetyPauseManager.SafetyState.DEFAULT
        }
    }

    fun blockingSafetyCountersRaw(): String? = runBlocking {
        context.dataStore.data.first()[Keys.SAFETY_COUNTERS]
    }

    suspend fun saveSafetyCountersRaw(raw: String) {
        context.dataStore.edit { it[Keys.SAFETY_COUNTERS] = raw }
    }

    // -----------------------------------------------------------------
    // Prime commit (v2.0 Phase B5)
    // -----------------------------------------------------------------

    suspend fun savePrime(state: PrimeCommitManager.PrimeState) {
        context.dataStore.edit { prefs ->
            if (!state.active && state.sessionId.isEmpty() && !state.gaveUp) {
                prefs.remove(Keys.PRIME)
            } else {
                prefs[Keys.PRIME] = JSONObject().apply {
                    put("active", state.active)
                    put("title", state.title)
                    put("sessionId", state.sessionId)
                    put("startWallMs", state.startWallMs)
                    put("endWallMs", state.endWallMs)
                    put("gaveUp", state.gaveUp)
                }.toString()
            }
        }
    }

    fun blockingPrime(): PrimeCommitManager.PrimeState = runBlocking {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.PRIME] ?: return@runBlocking PrimeCommitManager.PrimeState()
        return@runBlocking try {
            val o = JSONObject(raw)
            PrimeCommitManager.PrimeState(
                active = o.optBoolean("active", false),
                title = o.optString("title", ""),
                sessionId = o.optString("sessionId", ""),
                startWallMs = o.optLong("startWallMs", 0L),
                endWallMs = o.optLong("endWallMs", 0L),
                gaveUp = o.optBoolean("gaveUp", false),
            )
        } catch (_: Exception) {
            PrimeCommitManager.PrimeState()
        }
    }

    // -----------------------------------------------------------------
    // Emergency TOTP secret + replay ledger (v2.0 Phase B5)
    // -----------------------------------------------------------------

    suspend fun saveEmergencySecret(secret: String) {
        context.dataStore.edit { it[Keys.EMERGENCY_SECRET] = secret }
    }

    fun blockingEmergencySecret(): String? = runBlocking {
        context.dataStore.data.first()[Keys.EMERGENCY_SECRET]
    }

    suspend fun saveEmergencyUsed(used: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EMERGENCY_USED] = JSONObject().apply {
                used.forEach { (k, v) -> put(k, v) }
            }.toString()
        }
    }

    fun blockingEmergencyUsed(): String? = runBlocking {
        context.dataStore.data.first()[Keys.EMERGENCY_USED]
    }

    // -----------------------------------------------------------------
    // Gamification (v2.1 Phase C) — raw JSON blobs, single-writer is
    // ProgressEngine; these accessors follow the same persist/blocking
    // discipline as the Phase B states above.
    // -----------------------------------------------------------------

    suspend fun saveDp(raw: String) {
        context.dataStore.edit { it[Keys.DP] = raw }
    }

    fun blockingDp(): String? = runBlocking {
        context.dataStore.data.first()[Keys.DP]
    }

    suspend fun saveStreak(raw: String) {
        context.dataStore.edit { it[Keys.STREAK] = raw }
    }

    fun blockingStreak(): String? = runBlocking {
        context.dataStore.data.first()[Keys.STREAK]
    }

    suspend fun saveProtection(raw: String) {
        context.dataStore.edit { it[Keys.PROTECTION] = raw }
    }

    fun blockingProtection(): String? = runBlocking {
        context.dataStore.data.first()[Keys.PROTECTION]
    }

    suspend fun saveCheckIn(raw: String) {
        context.dataStore.edit { it[Keys.CHECKIN] = raw }
    }

    fun blockingCheckIn(): String? = runBlocking {
        context.dataStore.data.first()[Keys.CHECKIN]
    }

    // -----------------------------------------------------------------
    // v2.2 Phase D — break passes + insight/announcement state
    // (raw string blobs; single writers are BreakPassManager and
    // InsightNotifier respectively)
    // -----------------------------------------------------------------

    suspend fun saveBreakPasses(raw: String) {
        context.dataStore.edit { it[Keys.BREAK_PASSES] = raw }
    }

    fun blockingBreakPasses(): String? = runBlocking {
        context.dataStore.data.first()[Keys.BREAK_PASSES]
    }

    suspend fun saveInsightSeen(dateKey: String) {
        context.dataStore.edit { it[Keys.INSIGHT_SEEN] = dateKey }
    }

    fun blockingInsightSeen(): String? = runBlocking {
        context.dataStore.data.first()[Keys.INSIGHT_SEEN]
    }

    suspend fun saveAnnouncementsSeen(raw: String) {
        context.dataStore.edit { it[Keys.ANNOUNCEMENTS_SEEN] = raw }
    }

    fun blockingAnnouncementsSeen(): String? = runBlocking {
        context.dataStore.data.first()[Keys.ANNOUNCEMENTS_SEEN]
    }

    // -----------------------------------------------------------------
    // v2.3 r7 — app limits + schedules (raw JSON blobs)
    // -----------------------------------------------------------------

    suspend fun saveAppLimitsRaw(raw: String) {
        context.dataStore.edit { it[Keys.APP_LIMITS] = raw }
    }

    fun blockingAppLimitsRaw(): String? = runBlocking {
        context.dataStore.data.first()[Keys.APP_LIMITS]
    }

    suspend fun saveSchedulesRaw(raw: String) {
        context.dataStore.edit { it[Keys.SCHEDULES] = raw }
    }

    fun blockingSchedulesRaw(): String? = runBlocking {
        context.dataStore.data.first()[Keys.SCHEDULES]
    }

    // -----------------------------------------------------------------
    // v2.5.5 audit fix M-1 — per-package app rules (user block/allow
    // overrides that influence FUTURE sessions; the active session's
    // policy snapshot stays authoritative, TRD §109)
    // -----------------------------------------------------------------

    suspend fun saveAppRulesRaw(raw: String) {
        context.dataStore.edit { it[Keys.APP_RULES] = raw }
    }

    fun blockingAppRulesRaw(): String? = runBlocking {
        context.dataStore.data.first()[Keys.APP_RULES]
    }
}
