package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A11yOverlayController — TYPE_ACCESSIBILITY_OVERLAY blocking surface
 * (v2.0 Phase A6).
 *
 * Ported trick from the reference app: an accessibility service may add
 * views via its own WindowManager using TYPE_ACCESSIBILITY_OVERLAY — this
 * window type does NOT require SYSTEM_ALERT_WINDOW. If the user revokes
 * the overlay permission mid-session, blocking still works through this
 * fallback path.
 *
 * Used by DetoxAccessibilityService when:
 *   - Settings.canDrawOverlays(context) == false, AND
 *   - a block verdict fired for the current foreground package.
 *
 * The overlay:
 *   - covers the screen with our blocking message,
 *   - its single button performs GLOBAL_ACTION_HOME (same as the lock
 *     screen's dismiss path),
 *   - auto-removes when the foreground package changes to an allowed one.
 *
 * SECURITY NOTE: only the accessibility service (engine 1) can show this —
 * engine 2 (UsageStats) has no window access and uses the activity-based
 * LockScreenActivity instead.
 */
object A11yOverlayController {

    private var currentOverlay: View? = null
    private var currentPkg: String? = null
    private val throttle = mutableMapOf<String, Long>()
    private const val THROTTLE_MS = 2_000L

    fun showBlocked(
        service: AccessibilityService,
        pkg: String,
        message: String,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(throttle) {
            val last = throttle[pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            throttle[pkg] = now
        }

        hide(service)

        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE)
            as WindowManager

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E1A"))
            gravity = Gravity.CENTER
        }

        val title = TextView(service).apply {
            text = "MAXLEVEL DETOX"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 16)
        }

        val body = TextView(service).apply {
            text = message
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 16, 48, 32)
        }

        val goHome = Button(service).apply {
            text = "Take me home"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DC2626"))
            setOnClickListener {
                try {
                    service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME)
                } catch (_: Exception) {
                }
                hide(service)
            }
        }

        root.addView(title)
        root.addView(body)
        root.addView(goHome, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        try {
            wm.addView(root, params)
            currentOverlay = root
            currentPkg = pkg
        } catch (_: Exception) {
            // Overlay stacking unavailable on this build — the activity
            // path (LockController) remains the enforcement surface.
        }
    }

    fun hide(service: AccessibilityService) {
        val overlay = currentOverlay ?: return
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE)
            as WindowManager
        try {
            wm.removeView(overlay)
        } catch (_: Exception) {
        }
        currentOverlay = null
        currentPkg = null
    }

    /** True if the live overlay is showing for the given package. */
    fun isShowing(pkg: String? = currentPkg): Boolean =
        currentOverlay != null && currentPkg != null && currentPkg == pkg

    /** Call from the accessibility service on every foreground change:
     *  overlays for packages other than the current foreground are stale. */
    fun reconcile(service: AccessibilityService, foregroundPkg: String?) {
        if (currentOverlay != null && foregroundPkg != currentPkg) {
            hide(service)
        }
    }
}
