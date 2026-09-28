package com.maxleveldetox.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.R

/**
 * GuardNotifier — the "Protection Degraded" escalation surface (Phase A2).
 *
 * Design rules:
 *  - ONE channel, IMPORTANCE_DEFAULT (these must be seen, unlike the
 *    silent enforcement tickers).
 *  - Every notification carries a direct Fix Now action (opens the app's
 *    permission/engine screen) — never a dead end.
 *  - Cooldown per cause: a flapping process must not spam the user
 *    (5-minute per-cause cooldown).
 */
object GuardNotifier {

    private const val CH_GUARD = "mld_guard"
    // v2.5.5 audit fix m-2: 2001/2002 collided with SessionEngine's
    // NOTIF_COMPLETION/NOTIF_CAGE — a guard alert silently replaced (or
    // was replaced by) a completion/cage notification. Renumbered.
    private const val NID_BLACKOUT = 2401
    private const val NID_PERMISSION = 2402
    private const val COOLDOWN_MS = 5 * 60_000L

    private val lastShown = mutableMapOf<String, Long>()

    fun notifyBlackout(context: Context) {
        if (throttled("blackout")) return
        post(
            context, NID_BLACKOUT,
            title = context.getString(R.string.guard_blackout_title),
            text = context.getString(R.string.guard_blackout_body),
        )
    }

    fun notifyPermissionLost(context: Context) {
        if (throttled("permission")) return
        post(
            context, NID_PERMISSION,
            title = context.getString(R.string.guard_permission_title),
            text = context.getString(R.string.guard_permission_body),
        )
    }

    // -----------------------------------------------------------------

    private fun throttled(cause: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lastShown) {
            val last = lastShown[cause] ?: 0L
            if (now - last < COOLDOWN_MS) return true
            lastShown[cause] = now
            return false
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_GUARD,
                    context.getString(R.string.channel_guard),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_guard_desc)
                }
            )
        }

        val fixPi = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                // v2.5.5 audit fix m-3: MainActivity consumes `openRoute` —
                // the old `route` extra made every "Fix Now" tap a no-op.
                putExtra("openRoute", "permissions")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(fixPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — the FGS notification and the
            // in-app RECOVERY flow remain as escalation paths.
        }
    }
}
