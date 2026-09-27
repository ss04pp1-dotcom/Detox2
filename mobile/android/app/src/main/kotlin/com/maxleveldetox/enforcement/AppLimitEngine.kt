package com.maxleveldetox.enforcement

import android.app.usage.UsageStatsManager
import android.content.Context
import com.maxleveldetox.MldApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * AppLimitEngine (v2.3 r7) — per-app daily usage limits (Social Sentry
 * "App Limits" parity).
 *
 * DESIGN:
 *   - Limits are persisted as raw JSON in the enforcement DataStore and
 *     mirrored into an in-memory map (the a11y hot path must stay cheap
 *     when no limits are configured — the common case).
 *   - Usage measurement: UsageStatsManager event intervals since LOCAL
 *     midnight (display-day semantics, not the UTC approx used by the
 *     insights tracker — a limit user sees "60 min today" and expects the
 *     same day boundary the clock shows).
 *   - Enforcement surface: the SAME LockScreenActivity the session engine
 *     uses (with a limit-specific message), plus a violation row so the
 *     attempt is auditable.
 *   - Limits apply WITHOUT any session (always-on, like the shorts
 *     blocker) and STACK with session policy: a session allowlist does not
 *     waive a spent limit.
 *   - Escape hatch: the existing coin-based temp unlock (TempUnlockManager)
 *     or an emergency TOTP pass — identical authority paths to the rest
 *     of the app, no new backdoor.
 */
object AppLimitEngine {

    data class AppLimit(
        val pkg: String,
        val dailyLimitMinutes: Int,
        val lastNotifiedDay: Int = 0, // day-of-year the last limit notice was posted
    )

    @Volatile
    private var limits: Map<String, AppLimit> = emptyMap()

    @Volatile
    private var loaded = false

    // -----------------------------------------------------------------
    // Persistence (raw JSON, StateRepository pattern)
    // -----------------------------------------------------------------

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = MldApp.get(context).stateRepo.blockingAppLimitsRaw()
            limits = parse(raw)
            loaded = true
        }
    }

    private fun parse(raw: String?): Map<String, AppLimit> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val map = HashMap<String, AppLimit>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = AppLimit(
                    pkg = o.optString("pkg"),
                    dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0),
                )
                if (l.pkg.isNotBlank() && l.dailyLimitMinutes > 0) map[l.pkg] = l
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        limits.values.forEach { l ->
            arr.put(JSONObject().apply {
                put("pkg", l.pkg)
                put("dailyLimitMinutes", l.dailyLimitMinutes)
            })
        }
        val app = MldApp.get(context)
        kotlinx.coroutines.runBlocking {
            app.stateRepo.saveAppLimitsRaw(arr.toString())
        }
    }

    // -----------------------------------------------------------------
    // Public API (NativeBridge-backed)
    // -----------------------------------------------------------------

    fun all(context: Context): List<AppLimit> {
        ensureLoaded(context)
        return limits.values.sortedBy { it.pkg }
    }

    /** minutes == 0 removes the limit. */
    fun setLimit(context: Context, pkg: String, minutes: Int) {
        ensureLoaded(context)
        synchronized(this) {
            val m = minutes.coerceIn(0, 1440)
            limits = if (m <= 0) limits - pkg
            else limits + (pkg to AppLimit(pkg, m))
            persist(context)
        }
    }

    fun limitFor(context: Context, pkg: String): AppLimit? {
        ensureLoaded(context)
        return limits[pkg]
    }

    // -----------------------------------------------------------------
    // Usage measurement — LOCAL midnight day window
    // -----------------------------------------------------------------

    fun minutesUsedToday(context: Context, pkg: String): Int {
        return try {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return 0
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis
            val now = System.currentTimeMillis()

            val events = mgr.queryEvents(dayStart, now)
            var totalMs = 0L
            var openSince = -1L
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != pkg) continue
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                    android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (openSince < 0) openSince = event.timeStamp
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                    android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED,
                    android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (openSince >= 0) {
                            totalMs += (event.timeStamp - openSince).coerceAtLeast(0)
                            openSince = -1
                        }
                    }
                }
            }
            if (openSince >= 0) totalMs += (now - openSince).coerceAtLeast(0)
            (totalMs / 60_000L).toInt()
        } catch (_: Exception) {
            0
        }
    }

    // -----------------------------------------------------------------
    // Enforcement hook (called from the a11y foreground path)
    // -----------------------------------------------------------------

    /**
     * Returns the exceeded limit for [pkg], or null when within budget /
     * no limit configured. Does NOT take the enforcement action itself —
     * the caller owns the lock+violation surface so both engines stay in
     * sync.
     */
    fun exceededLimit(context: Context, pkg: String): AppLimit? {
        val limit = limitFor(context, pkg) ?: return null
        // One-minute slack so a 60-minute limit blocks at minute 60, not 59.
        if (minutesUsedToday(context, pkg) >= limit.dailyLimitMinutes) return limit
        return null
    }
}
