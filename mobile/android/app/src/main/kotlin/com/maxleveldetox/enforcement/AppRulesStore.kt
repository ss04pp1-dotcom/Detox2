package com.maxleveldetox.enforcement

import android.content.Context
import com.maxleveldetox.MldApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppRulesStore (v2.5.5 audit fix M-1) — per-package user overrides for the
 * App Rules screen.
 *
 * DESIGN:
 *   - The user can explicitly BLOCK or explicitly ALLOW any launcher-visible
 *     package. Rules are persisted as raw JSON in the enforcement DataStore
 *     (the AppLimitEngine pattern) and mirrored into an in-memory map so the
 *     a11y hot path stays cheap.
 *   - Rules influence FUTURE sessions: [PolicyEngine] consults them between
 *     the session allowlist and the category block. The ACTIVE session's
 *     policy snapshot stays authoritative (TRD §109) — an explicit user rule
 *     does not retro-actively alter a session that is already running.
 *   - An explicit ALLOW also exempts the package from the unknown-package
 *     STRICT/MAXLEVEL default-deny for future sessions (that is the entire
 *     point of an "allow" rule) but never bypasses the cage or monk lockdown,
 *     which are consulted earlier in the evaluation order.
 */
object AppRulesStore {

    data class AppRule(
        val pkg: String,
        val blocked: Boolean,
    )

    @Volatile
    private var rules: Map<String, AppRule> = emptyMap()

    @Volatile
    private var loaded = false

    // -----------------------------------------------------------------
    // Persistence (raw JSON, StateRepository pattern)
    // -----------------------------------------------------------------

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = MldApp.get(context).stateRepo.blockingAppRulesRaw()
            rules = parse(raw)
            loaded = true
        }
    }

    private fun parse(raw: String?): Map<String, AppRule> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val map = HashMap<String, AppRule>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.optString("pkg")
                if (pkg.isNotBlank()) {
                    map[pkg] = AppRule(pkg = pkg, blocked = o.optBoolean("blocked", true))
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Upsert one rule (blocked=false stores an explicit ALLOW that beats
     *  the category block and the default-deny; it never bypasses the cage
     *  or monk lockdown, which are evaluated earlier). */
    fun set(context: Context, pkg: String, blocked: Boolean) {
        if (pkg.isBlank()) return
        ensureLoaded(context)
        synchronized(this) {
            rules = rules + (pkg to AppRule(pkg, blocked = blocked))
            persistLocked(context)
        }
    }

    private fun persistLocked(context: Context) {
        val arr = JSONArray()
        rules.values.sortedBy { it.pkg }.forEach { r ->
            arr.put(JSONObject().apply {
                put("pkg", r.pkg)
                put("blocked", r.blocked)
            })
        }
        val app = MldApp.get(context)
        kotlinx.coroutines.runBlocking {
            app.stateRepo.saveAppRulesRaw(arr.toString())
        }
    }

    // -----------------------------------------------------------------
    // Consultation (a11y hot path — in-memory only)
    // -----------------------------------------------------------------

    /** Explicit user-block for the package (null = no rule). */
    fun isExplicitlyBlocked(context: Context, pkg: String): Boolean {
        ensureLoaded(context)
        return rules[pkg]?.blocked == true
    }

    /** Explicit user-allow for the package (null = no rule). */
    fun isExplicitlyAllowed(context: Context, pkg: String): Boolean {
        ensureLoaded(context)
        return rules[pkg]?.let { !it.blocked } == true
    }

    /** All stored rules (for diagnostics / the rules screen). */
    fun all(context: Context): List<AppRule> {
        ensureLoaded(context)
        return rules.values.sortedBy { it.pkg }
    }

    /** Test/debug hook — drop the in-memory mirror so the next read
     *  re-loads from DataStore. */
    fun resetCache() {
        synchronized(this) {
            loaded = false
            rules = emptyMap()
        }
    }
}
