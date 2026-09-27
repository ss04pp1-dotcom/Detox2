package com.maxleveldetox.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * TrendWidgetProvider (v2.5.8 roadmap — Distraction Trend screen widget).
 *
 * The interactive 6th home-screen widget: a 7-day bar chart with TWO series
 * the user can switch by TAPPING the chart:
 *
 *   series 0 — "Distraction screen-time": daily distracting-app minutes
 *               (high-water marks sampled from UsageStats by TrendSampler)
 *   series 1 — "Reels skipped": daily intercepted short-form attempts
 *               (daily_stats.shortsWarnings — every blocked reel counts)
 *
 * INTERACTIVITY (the launcher-compatible subset):
 *   - tap the CHART  -> broadcast toggles this widget instance's series
 *     (per-instance preference, redrawn immediately)
 *   - tap the TITLE  -> opens the app (same contract as every MLD widget)
 *
 * Design constraints honored from the existing battery:
 *   - reads NATIVE state only (Room) — works with Flutter dead;
 *   - zero enforcement authority — display surface only;
 *   - broadcast work runs on Dispatchers.Default via goAsync() (M-7 rule);
 *   - a failing render never crashes the launcher (everything guarded).
 */
class TrendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // v2.5.8: the widget's 30-min launcher tick also samples
                // today's distraction minutes (self-throttled to 1/10 min),
                // so today's bar keeps growing even when the monitor service
                // is stood down. This render may read a pre-tick value; the
                // next refresh picks the sample up.
                try {
                    TrendSampler.tick(context)
                } catch (_: Exception) {
                }
                appWidgetIds.forEach { id ->
                    render(context, appWidgetManager, id)
                }
            } catch (_: Exception) {
                // never crash the launcher
            } finally {
                result.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Resize -> redraw at the new size.
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                render(context, appWidgetManager, appWidgetId)
            } catch (_: Exception) {
            } finally {
                result.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
            if (id >= 0) {
                val result = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        prefs(context).edit()
                            .putInt(seriesKey(id), 1 - activeSeries(context, id))
                            .apply()
                        render(context, AppWidgetManager.getInstance(context), id)
                    } catch (_: Exception) {
                    } finally {
                        result.finish()
                    }
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Clean per-instance series preferences.
        prefs(context).edit().apply {
            appWidgetIds.forEach { id -> remove(seriesKey(id)) }
        }.apply()
        super.onDeleted(context, appWidgetIds)
    }

    // -----------------------------------------------------------------
    // Render
    // -----------------------------------------------------------------

    private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val series = activeSeries(context, appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_trend)

        views.setTextViewText(R.id.widget_trend_title, context.getString(R.string.widget_trend_title))
        views.setTextViewText(
            R.id.widget_trend_mode,
            context.getString(
                if (series == SERIES_DISTRACTION) R.string.widget_trend_mode_distraction
                else R.string.widget_trend_mode_reels
            )
        )

        val (values, labels, caption) = seriesData(context, series)
        views.setTextViewText(R.id.widget_trend_caption, caption)

        val density = context.resources.displayMetrics.density
        val options = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_MIN_WIDTH).takeIf { it > 0 } ?: 220
        val chartWidth = (minOf(maxOf(widthDp, 180), 400) * density).toInt()
        val chartHeight = (96 * density).toInt()
        val bitmap = drawChart(context, values, labels, series, chartWidth, chartHeight)
        views.setImageViewBitmap(R.id.widget_trend_chart, bitmap)

        // Interactivity: title -> app, chart -> toggle this instance's series.
        views.setOnClickPendingIntent(R.id.widget_trend_title, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_trend_chart, togglePendingIntent(context, appWidgetId))

        manager.updateAppWidget(appWidgetId, views)
    }

    /** 7-day series from Room: values for last 7 local days (oldest first). */
    private fun seriesData(
        context: Context,
        series: Int,
    ): Triple<IntArray, List<String>, String> {
        val today = dateKeyFor(System.currentTimeMillis())
        val since = dateKeyFor(System.currentTimeMillis() - 6L * 86_400_000L)
        val stats = try {
            runBlocking {
                MldApp.get(context).database.dailyStatDao().since(since)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val byKey = stats.associateBy { it.dateKey }

        val values = IntArray(7)
        val labels = mutableListOf<String>()
        var total = 0
        val dayFmt = SimpleDateFormat("EEE", Locale.US)
        for (i in 0 until 6) {
            val day = LocalDate.now().minusDays((6 - i).toLong())
            val key = day.toString()
            val stat = byKey[key]
            val v = if (series == SERIES_DISTRACTION) {
                (stat?.distractingMinutes ?: 0)
            } else {
                (stat?.shortsWarnings ?: 0)
            }
            values[i] = v
            total += v
            labels.add(dayFmt.format(Date.from(day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())).take(1))
        }
        // Today last (fresh value).
        val stat = byKey[today]
        val vToday = if (series == SERIES_DISTRACTION) {
            (stat?.distractingMinutes ?: 0)
        } else {
            (stat?.shortsWarnings ?: 0)
        }
        values[6] = vToday
        total += vToday
        labels.add(dayFmt.format(Date()).take(1))

        val caption = if (series == SERIES_DISTRACTION) {
            context.getString(R.string.widget_trend_caption_distraction, total)
        } else {
            context.getString(R.string.widget_trend_caption_reels, total)
        }
        return Triple(values, labels, caption)
    }

    // -----------------------------------------------------------------
    // Chart drawing (pure Canvas — no chart dependency, tiny APK cost)
    // -----------------------------------------------------------------

    private fun drawChart(
        context: Context,
        values: IntArray,
        labels: List<String>,
        series: Int,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        // Since the widget background is already the card, draw TRANSPARENT
        // so the card shows through.
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = context.resources.displayMetrics.density

        val pad = 4 * density
        val labelH = 12 * density
        val chartTop = pad
        val chartBottom = heightPx - labelH - pad
        val chartH = (chartBottom - chartTop).coerceAtLeast(4 * density)

        val max = values.maxOrNull() ?: 0
        val scale = if (max <= 0) 0f else chartH / max

        val slot = (widthPx - 2 * pad) / 7f
        val barW = (slot * 0.58f).coerceAtLeast(3 * density)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (series == SERIES_DISTRACTION) COLOR_DISTRACTION else COLOR_REELS
        }
        val barPaintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_DIM }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_LABEL
            textSize = 9 * density
            textAlign = Paint.Align.CENTER
        }

        for (i in 0 until 7) {
            val cx = pad + slot * (i + 0.5f)
            // Baseline: always draw a 2dp stub so zero-days are visible.
            val h = if (max <= 0) 0f else (values[i] * scale).coerceAtLeast(2 * density)
            val top = chartBottom - h
            val left = cx - barW / 2f
            val isToday = i == 6
            val paint = if (isToday || values[i] > 0) barPaint else barPaintDim
            canvas.drawRoundRect(
                RectF(left, top, left + barW, chartBottom),
                3 * density, 3 * density, paint
            )
            canvas.drawText(labels.getOrElse(i) { "" }, cx, heightPx - pad, labelPaint)
        }
        return bmp
    }

    // -----------------------------------------------------------------
    // Pending intents + prefs
    // -----------------------------------------------------------------

    private fun togglePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, TrendWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("mld_trend_widget", Context.MODE_PRIVATE)

    private fun seriesKey(appWidgetId: Int) = "series_$appWidgetId"

    private fun activeSeries(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(seriesKey(appWidgetId), SERIES_DISTRACTION)

    companion object {
        const val ACTION_TOGGLE = "com.maxleveldetox.widgets.TREND_TOGGLE"
        const val EXTRA_WIDGET_ID = "appWidgetId"
        const val SERIES_DISTRACTION = 0
        const val SERIES_REELS = 1

        private const val COLOR_DISTRACTION = 0xFF6366F1.toInt() // indigo accent
        private const val COLOR_REELS = 0xFF22C55E.toInt()       // success green
        private const val COLOR_DIM = 0xFF1E293B.toInt()         // edge gray
        private const val COLOR_LABEL = 0xFF94A3B8.toInt()       // muted text

        fun dateKeyFor(wallMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wallMs))
    }
}

/**
 * TrendSampler (v2.5.8) — keeps daily_stats.distractingMinutes honest.
 *
 * UsageStats reports CUMULATIVE minutes-today, so the daily stat is a
 * HIGH-WATER mark: sample now and again, take the max. Called from the
 * ForegroundAppMonitorService sweep (every ~30s but self-throttled to one
 * sample per 10 minutes) and from the trend widget's own update tick — the
 * widget only reads Room, the sampler is what feeds today's bar.
 *
 * Usage access is analytics-only (TRD §24) — a missing permission simply
 * leaves the distraction series at zero (reels-skipped still works).
 */
object TrendSampler {

    private const val THROTTLE_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastSampleElapsed = 0L

    fun tick(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastSampleElapsed < THROTTLE_MS) return
            lastSampleElapsed = now
        }
        scope.launch {
            try {
                val app = context.applicationContext as? MldApp ?: return@launch
                val minutes = com.maxleveldetox.monitor.BrainRotEngine
                    .distractingMinutes(context.applicationContext)
                val dateKey = TrendWidgetProvider.dateKeyFor(System.currentTimeMillis())
                app.database.dailyStatDao().bump(dateKey, "distractingMinutesMax", minutes)
            } catch (_: Exception) {
                // analytics-only — never propagate
            }
        }
    }
}
