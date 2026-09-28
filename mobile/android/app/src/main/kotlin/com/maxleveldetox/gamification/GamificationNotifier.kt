package com.maxleveldetox.gamification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.R

/**
 * GamificationNotifier (v2.1 Phase C) — progress notifications.
 *
 * TONE POLICY (deliberate divergence from the competitor): every message is
 * factual and respectful. No escalating abuse, no guilt weaponization, no
 * threats, no parasocial framing. Bad news is delivered plainly with the
 * recovery path attached — the app's job is honesty, not shame.
 */
class GamificationNotifier(private val context: Context) {

    fun notifyLevelUp(levelName: String, lifetimeDp: Int) {
        post(context.getString(R.string.notif_levelup_title),
            context.getString(R.string.notif_levelup_body, levelName, lifetimeDp))
    }

    fun notifyMilestone(days: Int, dpAmount: Int) {
        post(context.getString(R.string.notif_milestone_title),
            context.getString(R.string.notif_milestone_body, days, dpAmount))
    }

    fun notifyFreezeUsed(remaining: Int) {
        post(context.getString(R.string.notif_freeze_title),
            context.getString(R.string.notif_freeze_body, remaining))
    }

    fun notifyStreakReset(reason: String) {
        post(context.getString(R.string.notif_streak_reset_title),
            context.getString(R.string.notif_streak_reset_body, reason))
    }

    fun notifyGraceArmed(graceHours: Int) {
        post(context.getString(R.string.notif_grace_title),
            context.getString(R.string.notif_grace_body, graceHours))
    }

    fun notifyRelapse(streakDays: Int, source: String) {
        post(context.getString(R.string.notif_relapse_title),
            context.getString(R.string.notif_relapse_body, streakDays, source))
    }

    // -----------------------------------------------------------------

    private fun post(title: String, body: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Progress", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Levels, streaks and milestones" })
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_BASE + (notifySeq++ % 8), n)
        } catch (_: Exception) {
            // Notifications are best-effort; progress state is already
            // persisted. Never crash enforcement-adjacent code over a toast.
        }
    }

    companion object {
        const val CHANNEL = "mld_progress"
        const val NOTIF_BASE = 2100
        private var notifySeq = 0
    }
}
