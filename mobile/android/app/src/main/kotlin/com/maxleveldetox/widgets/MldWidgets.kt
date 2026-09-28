package com.maxleveldetox.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.SystemClockNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget battery (v2.2 Phase D, port-plan item 18 — the ethical
 * subset of the competitor's 5 widgets: usage/streak/session/coins. The
 * shame-based "Brain Rot" widget is deliberately NOT ported).
 *
 * All four widgets read NATIVE state (DataStore/Room) — they keep working
 * when Flutter is dead and never hold enforcement authority. Tapping a
 * widget simply opens the app.
 *
 *   StreakWidgetProvider   — level + streak days + frozen pill
 *   SessionWidgetProvider  — active session mode + remaining time (or idle)
 *   UsageWidgetProvider    — today's focus minutes + blocked attempts
 *   CoinsWidgetProvider    — coin balance + bailout cost progress
 */
abstract class MldWidgetProvider : AppWidgetProvider() {

    protected abstract val layoutId: Int
    protected abstract fun titleRes(context: Context): String

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // v2.5.5 audit fix M-7: widget broadcasts arrive on the MAIN
        // thread — the DataStore/Room runBlocking reads inside
        // valueText/subText used to block it (ANR exposure on low-end
        // devices; the BrainRot widget additionally ran a full-day usage
        // scan). goAsync() + Dispatchers.Default keeps the broadcast's
        // ~10 s budget without janking the launcher.
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val views = RemoteViews(context.packageName, layoutId)
                views.setTextViewText(R.id.widget_title, titleRes(context))
                views.setTextViewText(R.id.widget_value, valueText(context))
                views.setTextViewText(R.id.widget_sub, subText(context))
                views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
                appWidgetManager.updateAppWidget(appWidgetIds, views)
            } catch (_: Exception) {
                // A failing widget must never crash the process.
            } finally {
                result.finish()
            }
        }
    }

    /** Big line — implemented per widget. */
    protected abstract fun valueText(context: Context): String

    /** Small line — implemented per widget. */
    protected abstract fun subText(context: Context): String

    companion object {
        fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(context.packageName)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Streak
// ---------------------------------------------------------------------------

class StreakWidgetProvider : MldWidgetProvider() {

    override val layoutId: Int get() = R.layout.widget_stat

    override fun titleRes(context: Context): String =
        context.getString(R.string.widget_streak_title)

    override fun valueText(context: Context): String {
        val json = progressJson(context)
        if (!json.optBoolean("enabled", false)) return "—"
        val levelName = json.optString("levelName", "")
        return "$levelName"
    }

    override fun subText(context: Context): String {
        val json = progressJson(context)
        if (!json.optBoolean("enabled", false)) {
            return context.getString(R.string.widget_progress_off)
        }
        val days = json.optInt("streakDays", 0)
        val frozen = json.optBoolean("frozen", false)
        val base = context.getString(R.string.widget_streak_days, days)
        return if (frozen) "$base · ${context.getString(R.string.widget_frozen)}" else base
    }

    private fun progressJson(context: Context): JSONObject = try {
        MldApp.get(context).progressEngine.compactJson()
    } catch (_: Exception) {
        JSONObject().put("enabled", false)
    }
}

// ---------------------------------------------------------------------------
// Active session
// ---------------------------------------------------------------------------

class SessionWidgetProvider : MldWidgetProvider() {

    override val layoutId: Int get() = R.layout.widget_stat

    override fun titleRes(context: Context): String =
        context.getString(R.string.widget_session_title)

    override fun valueText(context: Context): String {
        val session = MldApp.get(context).stateRepo.blockingSession() ?: return idle()
        if (!session.status.isEnforcing) return idle()
        val now = SystemClockNow.elapsed
        val remainingMs = (session.endElapsed - now).coerceAtLeast(0L)
        val totalMinutes = (remainingMs + 59_999L) / 60_000L
        val mode = if (session.mode.name == "STUDY") {
            context.getString(R.string.widget_session_study)
        } else {
            context.getString(R.string.widget_session_detox)
        }
        return "$mode · ${minutesLabel(context, totalMinutes.toInt())}"
    }

    override fun subText(context: Context): String {
        val session = MldApp.get(context).stateRepo.blockingSession() ?: return ""
        if (!session.status.isEnforcing) return ""
        return context.getString(R.string.widget_session_active)
    }

    private fun idle(): String = "—"

    private fun minutesLabel(context: Context, minutes: Int): String =
        context.getString(R.string.widget_minutes_left, minutes)
}

// ---------------------------------------------------------------------------
// Today's usage
// ---------------------------------------------------------------------------

class UsageWidgetProvider : MldWidgetProvider() {

    override val layoutId: Int get() = R.layout.widget_stat

    override fun titleRes(context: Context): String =
        context.getString(R.string.widget_usage_title)

    override fun valueText(context: Context): String {
        val today = todayKey()
        val app = MldApp.get(context)
        val stat = runBlocking { app.database.dailyStatDao().byKey(today) }
        val focusMinutes = ((stat?.focusSeconds ?: 0) + (stat?.detoxSeconds ?: 0) + 59) / 60
        return context.getString(R.string.widget_usage_focus, focusMinutes)
    }

    override fun subText(context: Context): String {
        val today = todayKey()
        val app = MldApp.get(context)
        val stat = runBlocking { app.database.dailyStatDao().byKey(today) }
        val blocked = stat?.blockedAttempts ?: 0
        return context.getString(R.string.widget_usage_blocked, blocked)
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

// ---------------------------------------------------------------------------
// Coins
// ---------------------------------------------------------------------------

class CoinsWidgetProvider : MldWidgetProvider() {

    override val layoutId: Int get() = R.layout.widget_stat

    override fun titleRes(context: Context): String =
        context.getString(R.string.widget_coins_title)

    override fun valueText(context: Context): String {
        val app = MldApp.get(context)
        val balance = runBlocking { app.coinLedger.balance() }
        return balance.toString()
    }

    override fun subText(context: Context): String {
        val app = MldApp.get(context)
        val cost = app.runtimeConfig.current().bailoutCoins
        return context.getString(R.string.widget_coins_bailout, cost)
    }
}

// ---------------------------------------------------------------------------
// v2.5 r9 — Brain Rot stage (Social Sentry's 5th widget, BrainRotWidget-
// Provider port: current stage badge + distracting minutes).
// ---------------------------------------------------------------------------

class BrainRotWidgetProvider : MldWidgetProvider() {

    override val layoutId: Int get() = R.layout.widget_stat

    override fun titleRes(context: Context): String =
        context.getString(R.string.widget_brainrot_title)

    override fun valueText(context: Context): String {
        val status = com.maxleveldetox.monitor.BrainRotEngine.statusJson(context)
        return status.optString("label", "—")
    }

    override fun subText(context: Context): String {
        val status = com.maxleveldetox.monitor.BrainRotEngine.statusJson(context)
        val minutes = status.optInt("minutes", 0)
        val nextAt = status.optInt("nextStageAtMinutes", -1)
        return if (nextAt > 0) {
            context.getString(R.string.widget_brainrot_sub, minutes, nextAt)
        } else {
            context.getString(R.string.widget_brainrot_max, minutes)
        }
    }
}

// ---------------------------------------------------------------------------
// Updater + pinning helper
// ---------------------------------------------------------------------------

object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Refresh every pinned widget. Cheap (4 RemoteViews updates) and called
     * on session broadcasts, progress changes and periodic onUpdate ticks.
     */
    fun updateAll(context: Context) {
        scope.launch {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return@launch
                val providers = listOf(
                    StreakWidgetProvider::class.java,
                    SessionWidgetProvider::class.java,
                    UsageWidgetProvider::class.java,
                    CoinsWidgetProvider::class.java,
                    BrainRotWidgetProvider::class.java,
                    // v2.5.8 roadmap: interactive 7-day trend chart.
                    TrendWidgetProvider::class.java,
                )
                providers.forEach { provider ->
                    val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                    if (ids.isNotEmpty()) {
                        val instance = provider.getDeclaredConstructor().newInstance()
                        instance.onUpdate(context, manager, ids)
                    }
                }
            } catch (_: Exception) {
                // Widgets are a convenience surface — never crash callers.
            }
        }
    }

    /** True while at least one MLD widget is pinned to the launcher. */
    fun anyPinned(context: Context): Boolean {
        return try {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            listOf(
                StreakWidgetProvider::class.java,
                SessionWidgetProvider::class.java,
                UsageWidgetProvider::class.java,
                CoinsWidgetProvider::class.java,
                BrainRotWidgetProvider::class.java,
                // v2.5.8 roadmap: interactive 7-day trend chart.
                TrendWidgetProvider::class.java,
            ).any { provider ->
                manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ask the launcher to pin a widget (API 26+, our minSdk). Returns
     * false when the launcher does not support pinning. Accepts any MLD
     * widget provider — including TrendWidgetProvider, which deliberately
     * extends AppWidgetProvider directly (custom chart rendering) rather
     * than the title/value/sub MldWidgetProvider base.
     */
    fun requestPin(context: Context, providerClass: Class<out AppWidgetProvider>): Boolean {
        return try {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            manager.requestPinAppWidget(ComponentName(context, providerClass), null, null)
        } catch (_: Exception) {
            false
        }
    }
}
