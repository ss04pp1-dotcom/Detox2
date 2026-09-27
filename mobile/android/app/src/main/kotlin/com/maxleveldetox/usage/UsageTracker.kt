package com.maxleveldetox.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * UsageTracker (TRD §29–30) — UsageStatsManager-backed daily usage.
 *
 * Used for analytics + insights ONLY. Usage access is never treated as a
 * security mechanism (TRD §24): enforcement is the accessibility service +
 * policy engine.
 */
class UsageTracker(private val context: Context) {

    data class UsageRow(
        val packageName: String,
        val appName: String,
        val minutesToday: Int,
        val category: String,
    )

    fun hasUsageAccess(): Boolean = try {
        val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        mgr.queryAndAggregateUsageStats(now - 60_000, now).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /**
     * Today's foreground minutes per package, for the dashboard/insights.
     * Aggregated from UsageEvents (activity resumed/paused intervals).
     */
    fun todayUsage(): List<UsageRow> {
        val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val dayStart = now - (now % 86_400_000L) // UTC-day approx; display use only

        val events = mgr.queryEvents(dayStart, now)

        val intervals = HashMap<String, Long>()
        val openSince = HashMap<String, Long>()

        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    openSince[pkg] = event.timeStamp
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = openSince.remove(pkg) ?: continue
                    intervals[pkg] = (intervals[pkg] ?: 0L) + (event.timeStamp - start)
                }
            }
        }
        // Still-open foreground apps count up to now.
        openSince.forEach { (pkg, start) ->
            intervals[pkg] = (intervals[pkg] ?: 0L) + (now - start)
        }

        return intervals.entries
            .filter { it.value >= 60_000L }
            .sortedByDescending { it.value }
            .take(25)
            .map { (pkg, ms) ->
                UsageRow(
                    packageName = pkg,
                    appName = appLabel(pkg),
                    minutesToday = (ms / 60_000L).toInt(),
                    category = "apps",
                )
            }
    }

    private fun appLabel(pkg: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.')
    } catch (_: Exception) {
        pkg
    }

    fun toJson(): JSONArray = JSONArray().apply {
        todayUsage().forEach { row ->
            put(JSONObject().apply {
                put("packageName", row.packageName)
                put("appName", row.appName)
                put("minutesToday", row.minutesToday)
                put("category", row.category)
            })
        }
    }
}
