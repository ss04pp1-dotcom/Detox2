package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog

/**
 * SafetyPauseOverlay (v2.9.3 r19) — the user-requested HARD pause surface.
 *
 * THE COMPLAINT (v2.9.2 user report): "prime commit, safety pause — full of
 * bugs". The old pause was SafetyPauseActivity, a plain activity: BACK was
 * swallowed but HOME / RECENTS worked, the notification shade pulled down,
 * and onUserLeaveHint even FINISHED the pause the moment the user left —
 * fail-open by design ("friction, not blockade"). The user wants every
 * enforcement surface to hold like the CAGE / study lock ("একদম হার্ড করো,
 * স্টাডি লক এর মত").
 *
 * This surface is exactly the mechanism the user already confirmed nothing
 * escapes from (the SESSION KIOSK wall): a full-screen
 * TYPE_ACCESSIBILITY_OVERLAY window that
 *   - consumes EVERY touch inside its bounds (status + nav bar regions
 *     included — home gestures and shade pulls land on OUR window),
 *   - never takes key focus (keys are consumed by the a11y key filter,
 *     which treats this overlay as a live enforcement surface — see
 *     DetoxAccessibilityService.isAnyModeActive),
 *   - counts down the configured seconds (3..60) and removes itself when
 *     it reaches zero, revealing the app the user was about to open.
 *
 * Countdown completion is still a resisted impulse (recordCompleted), the
 * Emergency dialer stays one tap away (PRD §27 — emergency outranks every
 * surface, including this one), and if the accessibility service dies the
 * overlay dies with it (fail-open exactly like the old activity, never a
 * bricked phone).
 *
 * The old SafetyPauseActivity stays registered in the manifest as a
 * FALLBACK: if the overlay add fails on some OEM build, the trigger path
 * falls back to the activity (v2.0 behavior) instead of showing nothing.
 */
object SafetyPauseOverlay {

    private const val BG = "#0A1220"
    private const val ACCENT = "#38BDF8"
    private const val DANGER = "#EF4444"

    private val handler = Handler(Looper.getMainLooper())

    private var serviceRef: AccessibilityService? = null
    private var overlay: View? = null
    private var ticker: Runnable? = null
    private var countdownView: TextView? = null
    private var targetPkg = ""
    private var remaining = 0

    fun isShowing(): Boolean = overlay != null

    // -----------------------------------------------------------------
    // Show — called from the a11y trigger (main thread). Returns false
    // when the overlay could not be added so the caller can fall back
    // to the legacy SafetyPauseActivity.
    // -----------------------------------------------------------------

    fun show(service: AccessibilityService, pkg: String, seconds: Int): Boolean {
        if (overlay != null) return true // a pause is already holding
        val clamped = seconds.coerceIn(3, 60)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                addOverlay(service, pkg, clamped)
                true
            } catch (e: Exception) {
                DiagLog.logError("SafetyPauseOverlay.show", e)
                false
            }
        }
        handler.post {
            try {
                addOverlay(service, pkg, clamped)
            } catch (e: Exception) {
                DiagLog.logError("SafetyPauseOverlay.show", e)
            }
        }
        // Off-main-thread callers cannot observe success synchronously —
        // assume success; the 30 s per-app suppression prevents loops.
        return true
    }

    private fun addOverlay(
        service: AccessibilityService,
        pkg: String,
        seconds: Int,
    ) {
        serviceRef = service
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density
        val pad = (density * 24).toInt()

        targetPkg = pkg
        remaining = seconds

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(BG))
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(TextView(service).apply {
            text = "A MOMENT"
            textSize = 26f
            setTextColor(Color.parseColor(ACCENT))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        })
        countdownView = TextView(service).apply {
            textSize = 72f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            text = remaining.toString()
        }
        root.addView(countdownView)
        root.addView(TextView(service).apply {
            text = "You chose to pause before this app.\n" +
                "Breathe. It opens by itself when the number reaches zero.\n" +
                "Nothing on the phone works until then."
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        // Emergency is ALWAYS reachable (PRD §27) — and starting the
        // emergency stands this overlay down (see start()).
        root.addView(Button(service).apply {
            text = "Emergency"
            setTextColor(Color.parseColor(DANGER))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = true
            setOnClickListener {
                hide()
                com.maxleveldetox.safety.EmergencyLockdown.start(service)
                com.maxleveldetox.safety.EmergencyLockdown.openDialer(service)
            }
        })

        @Suppress("DEPRECATION")
        root.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE: consumes every touch inside its bounds but
            // never takes key focus — keys die in the service filter.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        wm.addView(root, params)
        overlay = root
        DiagLog.log("SAFETY_PAUSE", "overlay up: pkg=$pkg seconds=$seconds")
        startTicker(service)
    }

    // -----------------------------------------------------------------
    // Countdown — one tick per second; zero = completed impulse.
    // -----------------------------------------------------------------

    private fun startTicker(service: AccessibilityService) {
        ticker?.let { handler.removeCallbacks(it) }
        val t = object : Runnable {
            override fun run() {
                if (overlay == null) return
                remaining -= 1
                if (remaining <= 0) {
                    // v2.1 Phase C: waited the full pause — a resisted
                    // impulse. Record BEFORE removing (a crash mid-way
                    // must not lose the insight).
                    try {
                        (service.application as? MldApp)
                            ?.safetyPause?.recordCompleted(targetPkg)
                    } catch (_: Exception) {
                    }
                    DiagLog.log("SAFETY_PAUSE", "countdown finished: pkg=$targetPkg")
                    hide()
                    return
                }
                countdownView?.text = remaining.toString()
                handler.postDelayed(this, 1_000L)
            }
        }
        ticker = t
        handler.post(t)
    }

    // -----------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------

    /** Soft request from any thread (posted to main). */
    fun hide() {
        if (overlay == null && ticker == null) return
        handler.post { removeInternal() }
    }

    /** a11y service death — the system drops the window anyway; clear us. */
    fun onServiceDestroyed() {
        removeInternal()
    }

    private fun removeInternal() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        val v = overlay
        overlay = null
        countdownView = null
        if (v == null) return
        val svc = serviceRef ?: return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(v)
        } catch (_: Exception) {
            // already removed by the system
        }
    }

    fun bind(service: AccessibilityService) {
        serviceRef = service
    }

    fun unbind() {
        serviceRef = null
        removeInternal()
    }
}
