package com.maxleveldetox.gamification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.MldApp

/**
 * SinthiaCheckIn (v2.5 r9) — Social Sentry's hourly companion check-ins
 * (report-engagement §1b), ported as the ethical variant.
 *
 * Every whole hour of daily screen time fires ONE notification with the
 * hour's stats + an escalating roast (1h gentle → 5h savage → 9h
 * Banglish → 12h+ medical advice — same escalation ladder as Social
 * Sentry's SinthiaCheckInManager, ~4 variants per tier instead of ~10).
 * Skipped while YouTube is foreground (same carve-out as SS line 140) and
 * deduped per hour-of-day via SharedPreferences.
 *
 * Tapping opens the app on the companion route (deeplink extra).
 */
object SinthiaCheckIn {

    private const val CHANNEL = "mld_sinthia"
    private const val PREFS = "mld_sinthia_checkin"
    private const val K_LAST_DATE = "lastDate"
    private const val K_LAST_HOUR = "lastHour"

    /** v2.5.5 audit fix M-12: re-query UsageStats at most once per 5
     *  minutes — the whole-hour boundary cannot move faster. */
    private const val QUERY_THROTTLE_MS = 5 * 60_000L

    @Volatile
    private var lastQueryAt = 0L

    private val POOLS: Map<Int, Array<String>> = mapOf(
        1 to arrayOf(
            "One hour in. Take a sip of water 💧",
            "Hour one done. Stretch those shoulders.",
        ),
        2 to arrayOf(
            "Two hours. Your eyes need a break, not another scroll.",
            "Two hours on the screen. The world is still out there.",
        ),
        3 to arrayOf(
            "Three hours?! Close it. Now. I mean it 😤",
            "Three hours. Even your phone is tired of you.",
        ),
        5 to arrayOf(
            "Five hours on this thing? That's actually pathetic 🤢",
            "Five. Hours. Touch grass. Immediately.",
        ),
        9 to arrayOf(
            "Kire vai tor ki jibon nai?! 9 ghonta! 😡",
            "9 ghonta screen e?! Ashole kono poriskar nai tor?",
        ),
        12 to arrayOf(
            "TWELVE HOURS. GO TO A DOCTOR 🏥",
            "12 hours. Log off. This is an intervention.",
        ),
    )

    /** Called from the ForegroundAppMonitorService sweep.
     *  v2.5.5 audit fix M-12: previously EVERY tick (5 s while engine 2 is
     *  up) ran TWO full-day UsageStats queryEvents scans — ~17k scans/day.
     *  Now: cheap SP dedupe FIRST, and ONE shared usage query only when a
     *  new whole hour is actually due. */
    fun tick(context: Context, foregroundPkg: String?) {
        val app = context.applicationContext as? MldApp ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Cheap gate BEFORE any UsageStats work (throttle + SP dedupe).
        val now = System.currentTimeMillis()
        if (now - lastQueryAt < QUERY_THROTTLE_MS) return
        lastQueryAt = now

        val rows = try {
            app.usageTracker.todayUsage()
        } catch (_: Exception) {
            return
        }
        val totalMinutes = rows.sumOf { it.minutesToday }
        val hour = totalMinutes / 60
        if (hour <= 0) return

        val today = todayKey()
        val lastDate = prefs.getString(K_LAST_DATE, "")
        val lastHour = prefs.getInt(K_LAST_HOUR, 0)
        if (lastDate == today && hour <= lastHour) return

        // YouTube carve-out (SS): never interrupt an active video.
        if (foregroundPkg == "com.google.android.youtube") return

        val distracting = rows
            .filter { app.policyEngine.isDistracting(it.packageName) }
            .sumOf { it.minutesToday }

        prefs.edit()
            .putString(K_LAST_DATE, today)
            .putInt(K_LAST_HOUR, hour)
            .apply()

        val line = messageFor(hour)
        val text = "Today: ${totalMinutes}m total · ${distracting}m distracting\n$line"
        post(context, hour, "Sinthia Check-in · hour $hour", text)
    }

    private fun messageFor(hour: Int): String {
        val tier = when {
            hour >= 12 -> 12
            hour >= 9 -> 9
            hour >= 5 -> 5
            hour >= 3 -> 3
            hour >= 2 -> 2
            else -> 1
        }
        // v2.5.7 (L-2): the last remaining `!!` in the tree — a missing
        // tier-1 pool entry must degrade to the fixed first-message, never
        // throw NPE on a check-in notification path.
        val pool = POOLS[tier] ?: POOLS[1]
        return if (pool != null && pool.isNotEmpty()) {
            pool[(System.currentTimeMillis() / 60_000L).toInt().mod(pool.size)]
        } else {
            "Sinthia ekhane achi — ajker session ta sesh kore fel!"
        }
    }

    private fun post(context: Context, hour: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Sinthia Check-ins",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Hourly companion check-ins" }
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("openRoute", "companion")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(5000 + (hour % 100), n)
        } catch (_: Exception) {
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
}
