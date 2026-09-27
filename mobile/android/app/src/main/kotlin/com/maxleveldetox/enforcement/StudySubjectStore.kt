package com.maxleveldetox.enforcement

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StudySubjectStore (v2.5 r9.4) — study subjects + per-subject time
 * (Social Sentry "ForceMode subjects" + `getTodaySubjectBreakdown`).
 *
 * Deliberately a small SharedPreferences JSON store (not Room): no schema
 * migration, single writer, survives process death. It is a reporting layer
 * only — it never influences enforcement.
 *
 * Study day = the date of (now - 4 h), so a late-night session that runs past
 * midnight still counts toward the day the student thinks they are in
 * (Social Sentry uses the same 04:00 boundary).
 */
object StudySubjectStore {

    private const val PREFS = "mld_study_subjects"
    private const val K_SUBJECTS = "subjects"
    private const val K_DAYS = "days"
    private const val MAX_SUBJECTS = 12
    private const val MAX_NAME = 24
    private const val KEEP_DAYS = 45
    private const val DAY_BOUNDARY_MS = 4L * 3600L * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Study-day key (yyyy-MM-dd) for a wall-clock instant. */
    fun studyDayKey(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wallMs - DAY_BOUNDARY_MS))

    fun list(context: Context): List<String> {
        val raw = prefs(context).getString(K_SUBJECTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeList(context: Context, items: List<String>) {
        prefs(context).edit().putString(K_SUBJECTS, JSONArray(items).toString()).apply()
    }

    /** Returns null on success or a human-readable error. */
    fun add(context: Context, rawName: String): String? {
        val name = rawName.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return "Enter a subject name."
        if (name.length > MAX_NAME) return "Keep the name under $MAX_NAME characters."
        if (name.any { it.isISOControl() }) return "Invalid subject name."
        val items = list(context).toMutableList()
        if (items.any { it.equals(name, ignoreCase = true) }) return null // already there
        if (items.size >= MAX_SUBJECTS) return "At most $MAX_SUBJECTS subjects."
        items.add(name)
        writeList(context, items)
        return null
    }

    fun remove(context: Context, name: String) {
        writeList(context, list(context).filterNot { it.equals(name, ignoreCase = true) })
    }

    /** Add focused seconds for [subject] on the study day of [wallMs]. */
    fun addSeconds(context: Context, subject: String, seconds: Int, wallMs: Long) {
        val name = subject.trim()
        if (name.isEmpty() || seconds <= 0) return
        val p = prefs(context)
        val days = try {
            JSONObject(p.getString(K_DAYS, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val key = studyDayKey(wallMs)
        val day = days.optJSONObject(key) ?: JSONObject()
        // Case-insensitive merge into an existing spelling.
        var useName = name
        val dayKeys = day.keys()
        while (dayKeys.hasNext()) {
            val k = dayKeys.next()
            if (k.equals(name, ignoreCase = true)) useName = k
        }
        day.put(useName, day.optInt(useName, 0) + seconds)
        days.put(key, day)

        // Prune old days.
        val keys = ArrayList<String>()
        val kit = days.keys()
        while (kit.hasNext()) keys.add(kit.next())
        if (keys.size > KEEP_DAYS) {
            keys.sort()
            keys.take(keys.size - KEEP_DAYS).forEach { days.remove(it) }
        }
        p.edit().putString(K_DAYS, days.toString()).apply()
    }

    /** [{name, seconds}] for a study day, largest first. */
    fun breakdown(context: Context, dayKey: String): JSONArray {
        val out = JSONArray()
        try {
            val days = JSONObject(prefs(context).getString(K_DAYS, "{}") ?: "{}")
            val day = days.optJSONObject(dayKey) ?: return out
            val rows = ArrayList<Pair<String, Int>>()
            val dayKeys = day.keys()
            while (dayKeys.hasNext()) {
                val k = dayKeys.next()
                rows.add(Pair(k, day.optInt(k, 0)))
            }
            rows.sortByDescending { it.second }
            rows.forEach { out.put(JSONObject().put("name", it.first).put("seconds", it.second)) }
        } catch (_: Exception) {
        }
        return out
    }

    fun toJson(context: Context): JSONObject {
        val dayKey = studyDayKey(System.currentTimeMillis())
        return JSONObject().apply {
            put("subjects", JSONArray(list(context)))
            put("dayKey", dayKey)
            put("today", breakdown(context, dayKey))
        }
    }
}
