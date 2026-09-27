package com.maxleveldetox.gamification

import android.content.Context
import com.maxleveldetox.MldApp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * TasksEngine (v2.5 r9) — Social Sentry task/subtask/routine economy
 * (report-engagement §4c, TasksDataStore port).
 *
 * Model: a task is { id, title, note, priority (1-3), category, routine
 * (bool), createdAt, completedAt?, subtasks: [{ id, title, done }] }.
 * Routines reset at every local midnight (completion history kept as a
 * count per date-key, not as a forever-growing list).
 *
 * Awards (ProgressEngine, caps apply):
 *   - task completed      +8  (TASK_COMPLETED)
 *   - subtask completed   +2  (SUBTASK_COMPLETED)
 *   - routine completed   +4  (ROUTINE_COMPLETED)
 *   - discipline combo    +2  once/day — ≥2 tasks completed today AND
 *                             ≥10 focus minutes today.
 *
 * Storage: SharedPreferences JSON (same durability class as Social
 * Sentry's TasksDataStore; tiny data, synchronous reads for the bridge).
 */
object TasksEngine {

    private const val PREFS = "mld_tasks"
    private const val K_TASKS = "tasks"
    private const val K_ROUTINE_HISTORY = "routine_history" // taskId -> JSONArray of dateKeys
    private const val K_COMBO_DATE = "combo_date"

    // -----------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------

    fun addTask(
        context: Context,
        title: String,
        note: String,
        priority: Int,
        category: String,
        routine: Boolean,
    ): JSONObject? {
        if (title.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        val task = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("title", title.trim())
            put("note", note.trim())
            put("priority", priority.coerceIn(1, 3))
            put("category", category)
            put("routine", routine)
            put("createdAt", System.currentTimeMillis())
            put("completedAt", JSONObject.NULL)
            put("subtasks", JSONArray())
        }
        arr.put(task)
        prefs.edit().putString(K_TASKS, arr.toString()).apply()
        return task
    }

    fun addSubtask(context: Context, taskId: String, title: String): Boolean {
        if (title.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") == taskId) {
                t.getJSONArray("subtasks").put(
                    JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("title", title.trim())
                        put("done", false)
                    }
                )
                prefs.edit().putString(K_TASKS, arr.toString()).apply()
                return true
            }
        }
        return false
    }

    /** Toggle a subtask; awards +2 on the done transition. */
    fun toggleSubtask(context: Context, taskId: String, subtaskId: String): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") != taskId) continue
            val subs = t.getJSONArray("subtasks")
            for (j in 0 until subs.length()) {
                val s = subs.getJSONObject(j)
                if (s.getString("id") != subtaskId) continue
                val nowDone = !s.getBoolean("done")
                s.put("done", nowDone)
                prefs.edit().putString(K_TASKS, arr.toString()).apply()
                if (nowDone) award(context, "subtask")
                return s
            }
        }
        return null
    }

    /** Complete a task/routine; awards +8/+4; routines auto-reset for the
     *  next day (completion recorded into history). */
    fun completeTask(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") != taskId) continue
            if (!t.isNull("completedAt") && t.getBoolean("routine") == false) return false
            val isRoutine = t.getBoolean("routine")
            t.put("completedAt", System.currentTimeMillis())
            if (isRoutine) {
                val hist = JSONObject(prefs.getString(K_ROUTINE_HISTORY, "{}") ?: "{}")
                val key = todayKey()
                val days = hist.optJSONArray(t.getString("id")) ?: JSONArray()
                if (!days.toString().contains("\"$key\"")) days.put(key)
                hist.put(t.getString("id"), days)
                prefs.edit().putString(K_ROUTINE_HISTORY, hist.toString()).apply()
            }
            prefs.edit().putString(K_TASKS, arr.toString()).apply()
            award(context, if (isRoutine) "routine" else "task")
            maybeAwardCombo(context)
            return true
        }
        return false
    }

    /** Re-open a task (no XP clawback — same as Social Sentry). */
    fun reopenTask(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") == taskId) {
                t.put("completedAt", JSONObject.NULL)
                prefs.edit().putString(K_TASKS, arr.toString()).apply()
                return true
            }
        }
        return false
    }

    fun deleteTask(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") == taskId) removed = true else out.put(t)
        }
        if (removed) prefs.edit().putString(K_TASKS, out.toString()).apply()
        return removed
    }

    // -----------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------

    fun getTasks(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return loadTasks(prefs)
    }

    fun statusJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        var open = 0
        var done = 0
        var routines = 0
        var subtasksOpen = 0
        var doneToday = 0
        val today = todayKey()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val completed = !t.isNull("completedAt")
            if (completed) {
                done++
                val dk = dateKey(t.optLong("completedAt", 0L))
                if (dk == today) doneToday++
            } else open++
            if (t.getBoolean("routine")) routines++
            val subs = t.getJSONArray("subtasks")
            for (j in 0 until subs.length()) {
                if (!subs.getJSONObject(j).getBoolean("done")) subtasksOpen++
            }
        }
        return JSONObject().apply {
            put("open", open)
            put("done", done)
            put("doneToday", doneToday)
            put("routines", routines)
            put("subtasksOpen", subtasksOpen)
            put("comboEarnedToday", prefs.getString(K_COMBO_DATE, "") == today)
        }
    }

    /** Daily routine reset: routines completed on a previous day become
     *  open again (completion history is kept separately). Call from the
     *  monitor sweep — cheap date-key guard inside. */
    fun maybeResetRoutines(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = loadTasks(prefs)
        val today = todayKey()
        var changed = false
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (!t.getBoolean("routine")) continue
            if (t.isNull("completedAt")) continue
            if (dateKey(t.optLong("completedAt", 0L)) == today) continue
            t.put("completedAt", JSONObject.NULL)
            changed = true
        }
        if (changed) prefs.edit().putString(K_TASKS, arr.toString()).apply()
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun award(context: Context, kind: String) {
        val app = context.applicationContext as? MldApp ?: return
        app.progressEngine.onTaskCompleted(kind)
    }

    /** ≥2 tasks completed today AND ≥10 focus minutes → +2 once/day. */
    private fun maybeAwardCombo(context: Context) {
        val app = context.applicationContext as? MldApp ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        if (prefs.getString(K_COMBO_DATE, "") == today) return
        val status = statusJson(context)
        if (status.optInt("doneToday", 0) >= 2 &&
            app.progressEngine.todayFocusMinutes() >= 10
        ) {
            prefs.edit().putString(K_COMBO_DATE, today).apply()
            app.progressEngine.awardDisciplineCombo()
        }
    }

    private fun loadTasks(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(K_TASKS, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun todayKey(): String = dateKey(System.currentTimeMillis())

    private fun dateKey(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wallMs))
}
