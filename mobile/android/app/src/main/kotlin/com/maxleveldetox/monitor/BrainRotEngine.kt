package com.maxleveldetox.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.overlay.EnforcementWall
import org.json.JSONObject

/**
 * BrainRotEngine (v2.5 r9) — Social Sentry's visceral self-image feedback
 * layer (report-engagement §8), ported as the ethical variant.
 *
 * Stage model (per distracting-app minutes today, thresholds identical to
 * Social Sentry's BrainRotStage.fromMinutes):
 *   HEALTHY  < 50 min
 *   MILD     50–109
 *   SEVERE   110–159
 *   FULL     >= 160
 *
 * Surfaces:
 *   - HUD pill: a small NON-TOUCHABLE a11y overlay (taps pass through —
 *     unlike EnforcementWall it never blocks the user), top-center,
 *     auto-hides after 5 s, throttled to one pill / 30 min, snoozeable
 *     for 2 h. Only from stage MILD up while the screen is in use.
 *   - getBrainRotStatus bridge -> Dart companion + tasks screens.
 *   - BrainRotWidgetProvider (the 5th home-screen widget).
 *
 * Computation is throttled: minutes are recomputed at most once per 60 s
 * (per-package 60 s cache, same cadence as Social Sentry).
 */
object BrainRotEngine {

    const val STAGE_HEALTHY = "HEALTHY"
    const val STAGE_MILD = "MILD"
    const val STAGE_SEVERE = "SEVERE"
    const val STAGE_FULL = "FULL"

    private const val SNOOZE_MS = 2 * 60 * 60 * 1000L
    private const val PILL_THROTTLE_MS = 30 * 60 * 1000L
    private const val PILL_AUTOHIDE_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val prefsName = { ctx: Context -> ctx.getSharedPreferences("mld_brainrot", Context.MODE_PRIVATE) }

    private var cachedMinutes = -1
    private var cachedAt = 0L
    private var hudView: android.view.View? = null

    // -----------------------------------------------------------------
    // Stage model
    // -----------------------------------------------------------------

    fun stageForMinutes(minutes: Int): Triple<String, String, Int> = when {
        minutes < 50 -> Triple(STAGE_HEALTHY, "Brain: healthy", Color.parseColor("#22C55E"))
        minutes < 110 -> Triple(STAGE_MILD, "Brain rot: mild", Color.parseColor("#F59E0B"))
        minutes < 160 -> Triple(STAGE_SEVERE, "Brain rot: severe", Color.parseColor("#F97316"))
        else -> Triple(STAGE_FULL, "Brain rot: full", Color.parseColor("#EF4444"))
    }

    /** Distracting minutes today (60 s cache). */
    fun distractingMinutes(context: Context): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedMinutes >= 0 && now - cachedAt < 60_000L) return cachedMinutes
        val app = context.applicationContext as? MldApp ?: return cachedMinutes.coerceAtLeast(0)
        return try {
            val minutes = app.usageTracker.todayUsage()
                .filter { app.policyEngine.isDistracting(it.packageName) }
                .sumOf { it.minutesToday }
            cachedMinutes = minutes
            cachedAt = now
            minutes
        } catch (_: Exception) {
            cachedMinutes.coerceAtLeast(0)
        }
    }

    fun statusJson(context: Context): JSONObject {
        val minutes = distractingMinutes(context)
        val (stage, label, color) = stageForMinutes(minutes)
        val nextAt = when (stage) {
            STAGE_HEALTHY -> 50
            STAGE_MILD -> 110
            STAGE_SEVERE -> 160
            else -> -1
        }
        return JSONObject().apply {
            put("stage", stage)
            put("label", label)
            put("color", String.format("#%06X", 0xFFFFFF and color))
            put("minutes", minutes)
            put("nextStageAtMinutes", nextAt)
        }
    }

    // -----------------------------------------------------------------
    // HUD pill (non-blocking)
    // -----------------------------------------------------------------

    /** Called from the ForegroundAppMonitorService sweep. */
    fun maybeShowHud(context: Context, service: AccessibilityService?) {
        val minutes = distractingMinutes(context)
        val (stage, _, _) = stageForMinutes(minutes)
        if (stage == STAGE_HEALTHY) return
        if (EnforcementWall.isShowing()) return // never stack on a wall

        val prefs = prefsName(context)
        val nowWall = System.currentTimeMillis()
        if (nowWall < prefs.getLong("snoozedUntil", 0L)) return
        if (nowWall - prefs.getLong("lastPillAt", 0L) < PILL_THROTTLE_MS) return

        val svc = service ?: return
        prefs.edit().putLong("lastPillAt", nowWall).apply()

        handler.post { addHud(svc, minutes) }
    }

    fun snooze(context: Context) {
        prefsName(context).edit()
            .putLong("snoozedUntil", System.currentTimeMillis() + SNOOZE_MS)
            .apply()
        handler.post { removeHud() }
    }

    private fun addHud(service: AccessibilityService, minutes: Int) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeHud()

        val (stage, label, color) = stageForMinutes(minutes)
        val d = service.resources.displayMetrics.density

        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(0xEE0A0E1A.toInt())
                setStroke((2 * d).toInt(), color)
            }
        }
        pill.addView(TextView(service).apply {
            text = "$label  ·  ${minutes}m"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })

        // NON-touchable: the HUD informs, never blocks (unlike SS's
        // blocking HUD — ethical divergence, documented in TRD).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (36 * d).toInt()
        }

        try {
            wm.addView(pill, params)
            hudView = pill
            handler.postDelayed({ removeHud() }, PILL_AUTOHIDE_MS)
            DiagLog.log("BRAINROT_HUD", "pill stage=$stage minutes=$minutes")
        } catch (_: Exception) {
        }
    }

    private fun removeHud() {
        val view = hudView ?: return
        hudView = null
        // The view was added from the a11y service's window manager; the
        // service owns removal.
        try {
            val ctx = view.context
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }
}
