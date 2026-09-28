package com.maxleveldetox.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.maxleveldetox.MldApp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * LockScheduler (v2.5 r9.3) — scheduled + recurring Lock My Phone starts
 * (Social Sentry LMP: "one-shot scheduled" + "regular schedules").
 *
 * Two schedule kinds, persisted in SharedPreferences (survive process death
 * and reboot; the boot receiver / app start / guard job re-arm the alarm):
 *   - "weekly": days of week (ISO 1=Mon..7=Sun) + start/end minute-of-day.
 *               start > end wraps past midnight; start == end = all day.
 *   - "once":   an absolute start time + duration in minutes (fires once).
 *
 * Authority stays with wall-clock time and the existing session engine: a
 * schedule never runs its own loop — when its window is live it starts an
 * ordinary lock-my-phone session for the REMAINING window, so every
 * existing rule (1.5 s lock loop, call mode, guards, coin bailout as the
 * only exit) applies unchanged.
 *
 * Safety / ethics:
 *   - never starts without the Commitment Pact accepted and Device Admin
 *     active (same gates as a manual start);
 *   - a schedule cannot be edited, disabled or deleted while its window is
 *     live and a lock session is running (that would be a free exit);
 *   - if the user pays the bailout, the schedule does NOT relock the same
 *     window again (the next window is unaffected).
 */
object LockScheduler {

    private const val PREFS = "mld_lock_schedules"
    private const val K_LIST = "list"
    private const val K_USER_STOP_AT = "user_stop_at_ms"
    private const val K_ARMED_AT = "armed_at_ms"

    const val ACTION_FIRE = "com.maxleveldetox.action.LOCK_SCHEDULE_FIRE"
    private const val REQ_CODE = 9403
    private const val MAX_SCHEDULES = 12
    private const val EVAL_THROTTLE_MS = 30_000L
    private const val MAX_ONCE_MINUTES = 480
    private const val DAY_MS = 24L * 3600L * 1000L

    @Volatile
    private var lastEvalElapsed = 0L

    data class Schedule(
        val id: String,
        val kind: String,
        val enabled: Boolean,
        val days: List<Int>,
        val startMin: Int,
        val endMin: Int,
        val startEpochMs: Long,
        val durationMinutes: Int,
        val consumed: Boolean,
        /** Windows that START before this instant are ignored, so creating
         *  or editing a schedule never locks the phone in the middle of a
         *  window that is already running. */
        val createdAtMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("enabled", enabled)
            put("days", JSONArray(days))
            put("startMin", startMin)
            put("endMin", endMin)
            put("startEpochMs", startEpochMs)
            put("durationMinutes", durationMinutes)
            put("consumed", consumed)
            put("createdAtMs", createdAtMs)
        }

        companion object {
            fun fromJson(o: JSONObject): Schedule {
                val days = ArrayList<Int>()
                val arr = o.optJSONArray("days")
                if (arr != null) {
                    for (i in 0 until arr.length()) days.add(arr.optInt(i))
                }
                return Schedule(
                    id = o.optString("id"),
                    kind = o.optString("kind", "weekly"),
                    enabled = o.optBoolean("enabled", true),
                    days = days,
                    startMin = o.optInt("startMin", 0),
                    endMin = o.optInt("endMin", 0),
                    startEpochMs = o.optLong("startEpochMs", 0L),
                    durationMinutes = o.optInt("durationMinutes", 0),
                    consumed = o.optBoolean("consumed", false),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<Schedule> {
        val raw = prefs(context).getString(K_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Schedule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Schedule.fromJson(o))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, items: List<Schedule>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(K_LIST, arr.toString()).apply()
    }

    fun toJsonArray(context: Context): JSONArray {
        val arr = JSONArray()
        list(context).forEach { s ->
            val o = s.toJson()
            o.put("windowLive", isLive(context, s))
            arr.put(o)
        }
        return arr
    }

    // -----------------------------------------------------------------
    // Save / delete (with the "no free exit while live" guard)
    // -----------------------------------------------------------------

    /** Returns null on success, or a human-readable error. */
    fun save(
        context: Context,
        id: String?,
        kind: String,
        enabled: Boolean,
        days: List<Int>,
        startMin: Int,
        endMin: Int,
        startEpochMs: Long,
        durationMinutes: Int,
    ): String? {
        val now = System.currentTimeMillis()
        val items = list(context).toMutableList()
        val existing = if (id.isNullOrBlank()) null else items.firstOrNull { it.id == id }

        if (existing != null && isLive(context, existing)) {
            return "This schedule is live right now — it cannot be changed until its window ends."
        }

        when (kind) {
            "weekly" -> {
                val cleanDays = days.filter { it in 1..7 }.distinct().sorted()
                if (cleanDays.isEmpty()) return "Pick at least one day."
                if (startMin !in 0..1439 || endMin !in 0..1439) return "Invalid time."
                val windowMin = windowMinutes(startMin, endMin)
                if (windowMin < 15) return "The lock window must be at least 15 minutes."
            }
            "once" -> {
                if (durationMinutes !in 1..MAX_ONCE_MINUTES) {
                    return "Duration must be 1 to $MAX_ONCE_MINUTES minutes."
                }
                if (startEpochMs < now - 60_000L) return "Start time is in the past."
            }
            else -> return "Unknown schedule type."
        }

        if (existing == null && items.size >= MAX_SCHEDULES) {
            return "At most $MAX_SCHEDULES schedules."
        }

        val cleaned = Schedule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            kind = kind,
            enabled = enabled,
            days = if (kind == "weekly") days.filter { it in 1..7 }.distinct().sorted() else emptyList(),
            startMin = if (kind == "weekly") startMin else 0,
            endMin = if (kind == "weekly") endMin else 0,
            startEpochMs = if (kind == "once") startEpochMs else 0L,
            durationMinutes = if (kind == "once") durationMinutes else 0,
            consumed = false,
            createdAtMs = now,
        )
        if (existing != null) {
            items[items.indexOf(existing)] = cleaned
        } else {
            items.add(cleaned)
        }
        write(context, items)
        evaluateAndRearm(context, force = true)
        return null
    }

    fun delete(context: Context, id: String): String? {
        val items = list(context).toMutableList()
        val existing = items.firstOrNull { it.id == id } ?: return null
        if (isLive(context, existing)) {
            return "This schedule is live right now — it cannot be deleted until its window ends."
        }
        items.remove(existing)
        write(context, items)
        rearm(context, force = true)
        return null
    }

    // -----------------------------------------------------------------
    // Window maths (wall clock, DST-safe via java.time)
    // -----------------------------------------------------------------

    /** Length of a weekly window in minutes (start == end = 24 h). */
    private fun windowMinutes(startMin: Int, endMin: Int): Int = when {
        endMin > startMin -> endMin - startMin
        endMin < startMin -> 24 * 60 - startMin + endMin
        else -> 24 * 60
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** [start, end) epoch-ms of the weekly window that STARTS on the date
     *  offset [dayOffset] from today's date, or null if that weekday is not
     *  selected. */
    private fun weeklyWindow(s: Schedule, nowMs: Long, dayOffset: Long): Pair<Long, Long>? {
        val z = zone()
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), z).toLocalDate()
        val date = today.plusDays(dayOffset)
        if (date.dayOfWeek.value !in s.days) return null
        val start = date.atTime(LocalTime.of(s.startMin / 60, s.startMin % 60)).atZone(z)
        val end = start.plusMinutes(windowMinutes(s.startMin, s.endMin).toLong())
        return Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    /** The window of [s] that contains [nowMs], or null. */
    private fun activeWindow(s: Schedule, nowMs: Long): Pair<Long, Long>? {
        if (!s.enabled) return null
        if (s.kind == "once") {
            if (s.consumed) return null
            val end = s.startEpochMs + s.durationMinutes * 60_000L
            return if (nowMs >= s.startEpochMs && nowMs < end) Pair(s.startEpochMs, end) else null
        }
        for (offset in longArrayOf(-1L, 0L)) {
            val w = weeklyWindow(s, nowMs, offset) ?: continue
            if (nowMs >= w.first && nowMs < w.second) return w
        }
        return null
    }

    /** Earliest window start strictly after [nowMs], or null. */
    private fun nextStart(s: Schedule, nowMs: Long): Long? {
        if (!s.enabled) return null
        if (s.kind == "once") {
            return if (!s.consumed && s.startEpochMs > nowMs) s.startEpochMs else null
        }
        var best: Long? = null
        for (offset in 0L..8L) {
            val w = weeklyWindow(s, nowMs, offset) ?: continue
            if (w.first > nowMs && (best == null || w.first < best)) best = w.first
        }
        return best
    }

    /** True when [s]'s window is live AND a lock session is running. */
    private fun isLive(context: Context, s: Schedule): Boolean {
        if (!LockMyPhoneController.isSessionActive(context)) return false
        return activeWindow(s, System.currentTimeMillis()) != null
    }

    // -----------------------------------------------------------------
    // Evaluation + alarm
    // -----------------------------------------------------------------

    /**
     * Start a session for any live window, then (re)arm the next alarm.
     * Cheap and idempotent — called from the alarm receiver, app start, boot,
     * the guard job and engine 2's slow tick (throttled unless [force]).
     */
    fun evaluateAndRearm(context: Context, force: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastEvalElapsed < EVAL_THROTTLE_MS) return
        lastEvalElapsed = nowElapsed
        try {
            startDueSession(context)
        } catch (_: Exception) {
        }
        try {
            rearm(context, force)
        } catch (_: Exception) {
        }
    }

    private fun startDueSession(context: Context) {
        val items = list(context)
        if (items.none { it.enabled }) return
        if (LockMyPhoneController.isSessionActive(context)) return
        if (!LockMyPhoneController.isAdminActive(context)) return
        val app = MldApp.get(context)
        if (!app.stateRepo.blockingPactAccepted()) return

        val now = System.currentTimeMillis()
        val userStopAt = prefs(context).getLong(K_USER_STOP_AT, 0L)

        var chosen: Schedule? = null
        var chosenEnd = 0L
        for (s in items) {
            val w = activeWindow(s, now) ?: continue
            // The user paid the bailout inside this window — leave it alone.
            if (userStopAt >= w.first) continue
            // Created / edited after this window began: starts next time.
            if (s.kind == "weekly" && w.first < s.createdAtMs) continue
            if (w.second > chosenEnd) {
                chosen = s
                chosenEnd = w.second
            }
        }
        val pick = chosen ?: return

        val seconds = ((chosenEnd - now) / 1000L).toInt().coerceIn(30, 24 * 3600)
        if (pick.kind == "once") {
            write(context, items.map { if (it.id == pick.id) it.copy(consumed = true) else it })
        }
        LockMyPhoneController.startSeconds(context, seconds, "schedule")
    }

    /** Called when the user ends a session through the paid bailout. */
    fun noteUserStop(context: Context) {
        prefs(context).edit().putLong(K_USER_STOP_AT, System.currentTimeMillis()).apply()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LockScheduleReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(
            context, REQ_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** (Re)arm the alarm for the next window start (or the end of the
     *  running session, so a continuing window is chained straight away). */
    fun rearm(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)
        val now = System.currentTimeMillis()
        val items = list(ctx).filter { it.enabled }

        var next = Long.MAX_VALUE
        for (s in items) {
            val n = nextStart(s, now) ?: continue
            if (n < next) next = n
        }
        if (items.isNotEmpty() && LockMyPhoneController.isSessionActive(ctx)) {
            val end = now + LockMyPhoneController.remainingSeconds(ctx) * 1000L + 2_000L
            if (end < next) next = end
        }

        val p = prefs(ctx)
        if (next == Long.MAX_VALUE) {
            am.cancel(pi)
            p.edit().remove(K_ARMED_AT).apply()
            return
        }
        if (!force && p.getLong(K_ARMED_AT, 0L) == next) return

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        p.edit().putLong(K_ARMED_AT, next).apply()
    }
}

/**
 * Fired by the schedule alarm and by clock / time-zone changes (a shifted
 * clock moves every window, so everything is re-evaluated).
 */
class LockScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            LockScheduler.evaluateAndRearm(context, force = true)
        } catch (_: Exception) {
        }
    }
}
