package com.maxleveldetox.growth

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * InsightNotifier (v2.2 Phase D) — two honest, non-shaming notification
 * surfaces (the ethical counterpart of the competitor's escalating-abuse
 * engine, which stays NOT ported):
 *
 *  1. DAILY INSIGHT — once per day at the remote-config hour (17..22,
 *     default 20:00 local): a neutral summary of today's focus minutes,
 *     blocked attempts and streak. Purely informational; if nothing
 *     happened, nothing is posted.
 *
 *  2. ANNOUNCEMENT DELIVERY — Flutter fetches /announcements on app start
 *     (pull model, no Firebase dependency by design) and forwards them
 *     here; unseen ones become local notifications. PROMOTION items are
 *     titled as offers so the user knows what they are looking at.
 *
 * Both surfaces are opt-out via remote config (insightNudgeEnabled) and
 * respect the POST_NOTIFICATIONS runtime permission.
 */
class InsightNotifier(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // -----------------------------------------------------------------
    // Daily insight
    // -----------------------------------------------------------------

    /**
     * Post the daily insight if it is due (hour >= configured hour, not
     * shown today, feature enabled, something to report). Safe to call
     * from any sweep/tick — idempotent per day.
     */
    fun maybePostDailyInsight() {
        scope.launch {
            try {
                maybePostDailyInsightInternal()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun maybePostDailyInsightInternal() {
        val app = MldApp.get(context)
        val cfg = app.runtimeConfig.current()
        if (!cfg.insightNudgeEnabled) return

        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < cfg.insightNudgeHour) return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val seen = readInsightSeen()
        if (seen == today) return

        val stat = app.database.dailyStatDao().byKey(today) ?: return
        val focusSeconds = stat.focusSeconds + stat.detoxSeconds
        val blocked = stat.blockedAttempts
        if (focusSeconds <= 0 && blocked <= 0) return // quiet day -> quiet phone

        val streakJson = try {
            app.progressEngine.compactJson()
        } catch (_: Exception) {
            JSONObject()
        }
        val streakDays = streakJson.optInt("streakDays", 0)

        val focusMinutes = (focusSeconds + 59) / 60
        var body = context.getString(
            R.string.notif_insight_body, focusMinutes, blocked, streakDays)

        // v2.5.9 (r11.1) — opportunity-cost line (user-requested): when
        // today's distracting time is significant, append the honest yearly
        // projection ("ei shomoy kaje lagale eita hoto"). Absent data adds
        // nothing — a quiet day stays a quiet notification.
        try {
            val cost = OpportunityCostEngine.snapshot(context)
            if (cost != null && stat.distractingMinutes >= 30) {
                body = "$body\n\n${cost.line}"
            }
        } catch (_: Exception) {
        }

        post(
            title = context.getString(R.string.notif_insight_title),
            body = body,
            id = NOTIF_INSIGHT_ID,
        )
        saveInsightSeen(today)
    }

    // -----------------------------------------------------------------
    // Announcement delivery (pull model, no Firebase by design)
    // -----------------------------------------------------------------

    /**
     * Forward announcements fetched by Flutter. Each unseen announcement
     * becomes one local notification; seen IDs are remembered (cap 50).
     * Expected shape: [{id, title, body, type}].
     */
    fun deliverAnnouncements(payload: String) {
        scope.launch {
            try {
                val arr = JSONArray(payload)
                val seenIds = readSeenAnnouncementIds().toMutableSet()
                var posted = 0
                for (i in 0 until arr.length()) {
                    if (posted >= MAX_ANNOUNCEMENTS_PER_BATCH) break
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isEmpty() || id in seenIds) continue
                    val type = item.optString("type", "INFO")
                    val title = if (type == "PROMOTION") {
                        context.getString(R.string.notif_announcement_offer_prefix, item.optString("title", ""))
                    } else {
                        item.optString("title", "")
                    }
                    if (title.isEmpty()) continue
                    post(
                        title = title,
                        body = item.optString("body", ""),
                        id = NOTIF_ANNOUNCEMENT_BASE + (id.hashCode() and 0xFFFF),
                    )
                    seenIds.add(id)
                    posted++
                }
                if (seenIds.isNotEmpty()) {
                    val capped = seenIds.toList().takeLast(SEEN_ANNOUNCEMENT_CAP).toSet()
                    saveSeenAnnouncementIds(capped)
                }
            } catch (_: Exception) {
            }
        }
    }

    // -----------------------------------------------------------------
    // Daily alarm scheduling
    // -----------------------------------------------------------------

    /** Schedule the next daily insight check at the configured hour. */
    fun scheduleDailyCheck() {
        try {
            val cfg = MldApp.get(context).runtimeConfig.current()
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, cfg.insightNudgeHour)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = checkPendingIntent(context)
            val op = if (Build.VERSION.SDK_INT >= 31) {
                am.canScheduleExactAlarms()
            } else true
            if (op) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: Exception) {
            // Alarm scheduling is best-effort; the sweep path also checks.
        }
    }

    private fun checkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, InsightAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // -----------------------------------------------------------------
    // Persistence (small string prefs in DataStore via StateRepository)
    // -----------------------------------------------------------------

    private fun readInsightSeen(): String =
        MldApp.get(context).stateRepo.blockingInsightSeen() ?: ""

    private suspend fun saveInsightSeen(dateKey: String) {
        MldApp.get(context).stateRepo.saveInsightSeen(dateKey)
    }

    private fun readSeenAnnouncementIds(): Set<String> {
        val raw = MldApp.get(context).stateRepo.blockingAnnouncementsSeen() ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private suspend fun saveSeenAnnouncementIds(ids: Set<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        MldApp.get(context).stateRepo.saveAnnouncementsSeen(arr.toString())
    }

    // -----------------------------------------------------------------
    // Posting
    // -----------------------------------------------------------------

    private fun post(title: String, body: String, id: Int) {
        try {
            if (body.isBlank()) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Insights & news", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Daily summaries and announcements" })
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (_: Exception) {
            // Best-effort surface; state is already persisted.
        }
    }

    companion object {
        const val CHANNEL = "mld_insights"
        const val NOTIF_INSIGHT_ID = 2300
        const val NOTIF_ANNOUNCEMENT_BASE = 2310
        const val SEEN_ANNOUNCEMENT_CAP = 50
        const val MAX_ANNOUNCEMENTS_PER_BATCH = 3
    }
}

/**
 * Fires at the configured insight hour (exact alarm, re-scheduled daily).
 * Manifest-registered so it works even after a process death.
 */
class InsightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val app = MldApp.get(context)
            app.insightNotifier.maybePostDailyInsight()
            app.insightNotifier.scheduleDailyCheck()
            // Also refresh widgets once a day even without other triggers.
            com.maxleveldetox.widgets.WidgetUpdater.updateAll(context)
        } catch (_: Exception) {
        }
    }
}
