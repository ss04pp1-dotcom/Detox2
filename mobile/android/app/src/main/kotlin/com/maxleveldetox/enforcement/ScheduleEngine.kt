package com.maxleveldetox.enforcement

import android.content.Context
import com.maxleveldetox.MldApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * ScheduleEngine (v2.3 r7) — profile-based scheduled blocking (Social
 * Sentry "ScheduleBlockProfiles" parity, deliberately simplified to the
 * blocking surface that matters).
 *
 * DESIGN:
 *   - A profile = {days-of-week, start/end minute-of-day, blocked package
 *     list, enabled}. Overnight windows (start > end) wrap midnight, same
 *     semantics as the reference app (today >= start OR yesterday < end).
 *   - Enforced by the accessibility foreground path WITHOUT any session
 *     (always-on), same stacking model as AppLimitEngine: session policy
 *     and schedules are independent walls.
 *   - NEVER block: emergency/dialer, system essentials, our own package
 *     (delegated to PolicyEngine's hard boundaries before any schedule
 *     verdict is applied by the caller).
 *   - Persistence: raw JSON in the enforcement DataStore + in-memory
 *     mirror (hot path stays cheap when no schedules exist).
 */
object ScheduleEngine {

    data class Profile(
        val id: String,
        val name: String,
        val startMinuteOfDay: Int,   // 0..1439
        val endMinuteOfDay: Int,     // 0..1439 (start > end → overnight wrap)
        val days: Set<Int>,          // ISO day-of-week: Mon=1..Sun=7
        val blockedPackages: Set<String>,
        val enabled: Boolean,
    ) {
        val isOvernight: Boolean get() = startMinuteOfDay > endMinuteOfDay

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("startMinuteOfDay", startMinuteOfDay)
            put("endMinuteOfDay", endMinuteOfDay)
            put("days", JSONArray(days.sorted().toList()))
            put("blockedPackages", JSONArray(blockedPackages.sorted().toList()))
            put("enabled", enabled)
        }

        companion object {
            fun fromJson(o: JSONObject): Profile? {
                val id = o.optString("id")
                if (id.isBlank()) return null
                return Profile(
                    id = id,
                    name = o.optString("name", "Schedule"),
                    startMinuteOfDay = o.optInt("startMinuteOfDay", 0).coerceIn(0, 1439),
                    endMinuteOfDay = o.optInt("endMinuteOfDay", 0).coerceIn(0, 1439),
                    days = o.optJSONArray("days")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            (arr.opt(i) as? Number)?.toInt()?.coerceIn(1, 7)
                        }.toSet()
                    } ?: emptySet(),
                    blockedPackages = o.optJSONArray("blockedPackages")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optString(i).takeIf { it.isNotBlank() }
                        }.toSet()
                    } ?: emptySet(),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }
    }

    @Volatile
    private var profiles: List<Profile> = emptyList()

    @Volatile
    private var loaded = false

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = MldApp.get(context).stateRepo.blockingSchedulesRaw()
            profiles = parse(raw)
            loaded = true
        }
    }

    private fun parse(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                Profile.fromJson(arr.getJSONObject(i))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        val app = MldApp.get(context)
        kotlinx.coroutines.runBlocking {
            app.stateRepo.saveSchedulesRaw(arr.toString())
        }
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    fun all(context: Context): List<Profile> {
        ensureLoaded(context)
        return profiles
    }

    fun upsert(context: Context, profile: Profile) {
        ensureLoaded(context)
        synchronized(this) {
            profiles = (profiles.filter { it.id != profile.id } + profile)
                .sortedBy { it.startMinuteOfDay }
            persist(context)
        }
    }

    fun delete(context: Context, id: String) {
        ensureLoaded(context)
        synchronized(this) {
            profiles = profiles.filter { it.id != id }
            persist(context)
        }
    }

    /** Today's active profile at this instant (first match wins). */
    fun activeNow(context: Context): Profile? {
        ensureLoaded(context)
        if (profiles.isEmpty()) return null
        val cal = Calendar.getInstance()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // ISO day: Calendar.MONDAY=2..SUNDAY=1 → Mon=1..Sun=7
        val isoToday = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val isoYesterday = if (isoToday == 1) 7 else isoToday - 1

        return profiles.firstOrNull { p ->
            p.enabled && p.blockedPackages.isNotEmpty() &&
                (isoToday in p.days || isoYesterday in p.days) && when {
                    // All-day profile — day-precise (v2.5.5 audit fix M-4:
                    // the old `-> true` made a Monday all-day profile also
                    // block ALL of Tuesday, because isoYesterday matched).
                    p.startMinuteOfDay == p.endMinuteOfDay -> isoToday in p.days
                    // Overnight: today past start, or yesterday's tail.
                    p.isOvernight -> (isoToday in p.days && minuteOfDay >= p.startMinuteOfDay) ||
                        (isoYesterday in p.days && minuteOfDay < p.endMinuteOfDay)
                    // Normal window (only meaningful on today's days).
                    else -> isoToday in p.days &&
                        minuteOfDay >= p.startMinuteOfDay && minuteOfDay < p.endMinuteOfDay
                }
        }
    }

    /**
     * True when [pkg] is blocked by the schedule active RIGHT NOW.
     * Hard boundaries (emergency/system/own) are excluded by the CALLER
     * before consulting this.
     */
    fun blocksNow(context: Context, pkg: String): Boolean {
        val profile = activeNow(context) ?: return false
        return pkg in profile.blockedPackages
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)
}
