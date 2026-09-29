package com.maxleveldetox.monk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.lock.MldDeviceAdminReceiver
import org.json.JSONObject

/**
 * MonkModeManager (v2.0 Phase B3) — allowlist-only device lockdown.
 *
 * Ported from the reference app's Monk Mode (report-modes.md §1.4/§10)
 * with our safety constraints:
 *
 *   - The FSM is { LOCKED, ALLOWED_APP }. LOCKED = full-device lockdown
 *     (DeviceAdmin lockNow + overlay surface). ALLOWED_APP = the user is
 *     inside one of the allowlisted apps (or the dialer during a real
 *     ringing event) and is policed by an 800ms UsageEvents tick.
 *   - Duration-bounded: wall-clock end time persisted in SharedPreferences
 *     survives reboots and process death (boot recovery re-arms the
 *     service — BootRecoveryReceiver).
 *   - Phone-call exemption is AUTOMATIC and AUDIO-EVENT-DRIVEN: when the
 *     AudioManager reports RINGING / IN_CALL we enter ALLOWED_APP with
 *     the dialer. When the mode returns to NORMAL we re-lock. No code, no
 *     button, no trust in the user pressing anything.
 *   - Emergency dialer remains one tap away at all times from the lock
 *     surface (PRD §27 hard boundary).
 *   - There is NO give-up flow for Monk Mode: the only exits are duration
 *     expiry and the always-available emergency call. (Bailout coins are
 *     a session concept; monk mode is a commitment, not a session.)
 *
 * Anti-bypass: lockNow() re-asserts every tick while LOCKED; the guard
 * job (MonkModeGuardJobService) resurrects the service after OEM kills;
 * the boot receiver re-arms after reboots.
 */
object MonkModeManager {

    enum class MonkState { LOCKED, ALLOWED_APP }

    private const val PREFS = "mld_monk_session"
    private const val K_SESSION = "monk_session"

    /** Notification identity (used by the lock service). */
    const val CHANNEL_MONK = "mld_monk"
    const val NOTIF_ID = 4201
    const val GUARD_JOB_ID = 9402

    /** AudioManager.MODE_RINGING (== 1) is hidden from the public SDK as of
     * API 35; the system still sets audio mode 1 while a call is ringing,
     * so the call exemption in monk mode keeps working with the literal. */
    const val AUDIO_MODE_RINGING: Int = 1

    // -----------------------------------------------------------------
    // Activation / deactivation
    // -----------------------------------------------------------------

    /**
     * Activate monk mode. Requires device admin (lockNow is the core
     * enforcement primitive) — the caller (NativeBridge) checks first.
     */
    fun activate(
        context: Context,
        goal: String,
        durationMinutes: Int,
        allowedPackages: Set<String>,
    ): Boolean {
        if (durationMinutes < 1 || durationMinutes > 720) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("goal", goal.take(200))
            put("startWallMs", System.currentTimeMillis())
            put("endWallMs", System.currentTimeMillis() + durationMinutes * 60_000L)
            put("allowedApps", org.json.JSONArray(allowedPackages))
            put("state", MonkState.LOCKED.name)
            put("exits", 0)
            put("active", true)
        }
        prefs.edit().putString(K_SESSION, json.toString()).apply()

        MonkModeLockService.start(context, freshActivation = true)

        // v2.9.11 r27: monk mode enforces the nav-key lockdown — arm the
        // a11y key filter instantly (watchdog backstop).
        try {
            com.maxleveldetox.accessibility.DetoxAccessibilityService.syncKeyFilterSoon()
        } catch (_: Exception) {
        }

        // v2.1 Phase C: first monk activation bonus.
        try {
            (context.applicationContext as? com.maxleveldetox.MldApp)
                ?.progressEngine?.onFeatureActivated("monk")
        } catch (_: Exception) {
        }
        return true
    }

    /** Legitimate end (duration expiry) or internal cleanup. */
    fun deactivate(context: Context, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sessionJson(context)?.let { json ->
            json.put("active", false)
            json.put("endReason", reason)
            json.put("endWallMs", System.currentTimeMillis())
            prefs.edit().putString(K_SESSION, json.toString()).apply()
        }
        // v2.9.11 r27: disarm the a11y key filter when monk mode ends
        // (volume multi-press root fix — no key round-trip at idle).
        try {
            com.maxleveldetox.accessibility.DetoxAccessibilityService.syncKeyFilterSoon()
        } catch (_: Exception) {
        }
        context.startService(
            Intent(context, MonkModeLockService::class.java)
                .setAction(MonkModeLockService.ACTION_STOP)
        )
        MonkModeGuardJobService.cancel(context)

        // v2.1 Phase C: a monk session that ran to its natural end counts
        // as a completed discipline block (daily stat + DP).
        if (reason == "expiry") {
            try {
                (context.applicationContext as? com.maxleveldetox.MldApp)
                    ?.progressEngine?.onMonkCompleted()
            } catch (_: Exception) {
            }
        }
    }

    // -----------------------------------------------------------------
    // State helpers (SP-backed, process-death safe)
    // -----------------------------------------------------------------

    fun isActive(context: Context): Boolean {
        val json = sessionJson(context) ?: return false
        if (!json.optBoolean("active", false)) return false
        return System.currentTimeMillis() < json.optLong("endWallMs", 0L)
    }

    fun remainingSeconds(context: Context): Int {
        val json = sessionJson(context) ?: return 0
        val endMs = json.optLong("endWallMs", 0L)
        return ((endMs - System.currentTimeMillis()) / 1000L)
            .coerceAtLeast(0L).toInt()
    }

    fun goal(context: Context): String =
        sessionJson(context)?.optString("goal", "") ?: ""

    fun allowedApps(context: Context): Set<String> {
        val json = sessionJson(context) ?: return emptySet()
        val out = mutableSetOf<String>()
        val arr = json.optJSONArray("allowedApps") ?: return out
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /** The device's default HOME launcher — always reachable in monk
     *  mode (otherwise unrecognized OEM launchers cause a lockNow loop
     *  while the user is simply at their home screen). */
    fun isDefaultLauncher(context: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(home, 0)
            resolved?.activityInfo?.packageName == pkg
        } catch (_: Exception) {
            false
        }
    }

    fun state(context: Context): MonkState {
        val name = sessionJson(context)?.optString("state") ?: MonkState.LOCKED.name
        return if (name == MonkState.ALLOWED_APP.name) MonkState.ALLOWED_APP else MonkState.LOCKED
    }

    fun setState(context: Context, state: MonkState) {
        mutate(context) { it.put("state", state.name) }
    }

    /** The user left the allowed app (or the call ended) — count the exit. */
    fun recordExit(context: Context) {
        mutate(context) { it.put("exits", it.optInt("exits", 0) + 1) }
    }

    fun exits(context: Context): Int =
        sessionJson(context)?.optInt("exits", 0) ?: 0

    // -----------------------------------------------------------------
    // DeviceAdmin lockNow — the re-lock primitive
    // -----------------------------------------------------------------

    fun lockNow(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            val admin = ComponentName(context, MldDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
        } catch (_: Exception) {
            // SecurityException on some OEMs when locked-by-keyguard — the
            // overlay surface is still up; next tick retries.
        }
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(ComponentName(context, MldDeviceAdminReceiver::class.java))
    }

    // -----------------------------------------------------------------
    // Ringing detection (audio mode listener lives in the service)
    // -----------------------------------------------------------------

    fun isRingingOrInCall(audioManager: AudioManager): Boolean =
        audioManager.mode == AUDIO_MODE_RINGING ||
            audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    // -----------------------------------------------------------------
    // Status projection
    // -----------------------------------------------------------------

    fun statusJson(context: Context): JSONObject = JSONObject().apply {
        put("active", isActive(context))
        put("remainingSeconds", remainingSeconds(context))
        put("goal", goal(context))
        put("state", state(context).name)
        put("exits", exits(context))
        put("allowedApps", org.json.JSONArray(allowedApps(context).toList()))
        put("adminActive", isAdminActive(context))
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun mutate(context: Context, block: (JSONObject) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sessionJson(context) ?: return
        block(json)
        prefs.edit().putString(K_SESSION, json.toString()).apply()
    }

    private fun sessionJson(context: Context): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(K_SESSION, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }
}
