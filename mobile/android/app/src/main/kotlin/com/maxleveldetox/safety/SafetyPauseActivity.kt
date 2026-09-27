package com.maxleveldetox.safety

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.maxleveldetox.MldApp

/**
 * SafetyPauseActivity (v2.0 Phase B4) — the reflection countdown.
 *
 * Full-screen, calm, non-hostile pause:
 *   - counts down the configured seconds (3..60) and releases itself
 *   - BACK is swallowed (the point is the pause) — but nothing bad
 *     happens if the user navigates away: onUserLeaveHint counts it as
 *     "closed early" for insights and finishes
 *   - emergency dialer always reachable (PRD §27)
 *
 * This is intentionally NOT a security surface. It never locks, never
 * escalates, never calls DeviceAdmin. Its only power is a moment of
 * friction chosen by the user themselves.
 */
class SafetyPauseActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 5
    private var targetPkg = ""
    private var countdownView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        val app = application as? MldApp
        remaining = app?.safetyPause?.let { it.statePauseSeconds() } ?: 5

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // v2.3 r7: bar-free friction surface.
        com.maxleveldetox.enforcement.ImmersiveBars.apply(this)

        setContentView(buildContent())
        startTicker()
    }

    private fun buildContent(): View {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val accent = Color.parseColor("#38BDF8")

        val root = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A1220"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(TextView(this).apply {
            text = "A MOMENT"
            textSize = 26f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        })
        countdownView = TextView(this).apply {
            textSize = 72f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            text = remaining.toString()
        }
        root.addView(countdownView)
        root.addView(TextView(this).apply {
            text = "You chose to pause before this app.\nBreathe. It opens by itself when the number reaches zero."
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        // Emergency is ALWAYS reachable (PRD §27) — never blocked.
        root.addView(Button(this).apply {
            text = "Emergency"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
        })

        return root
    }

    private val ticker = object : Runnable {
        override fun run() {
            remaining -= 1
            if (remaining <= 0) {
                // v2.1 Phase C: waited the full pause — a resisted impulse.
                (application as? MldApp)?.safetyPause?.recordCompleted(targetPkg)
                finish()
                return
            }
            countdownView?.text = remaining.toString()
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startTicker() {
        handler.post(ticker)
    }

    override fun onBackPressed() {
        // Swallowed — the pause releases itself.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User navigated away mid-pause — record for insights, no penalty.
        (application as? MldApp)?.safetyPause?.recordClosedEarly(targetPkg)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"

        fun intentFor(context: Context, pkg: String): Intent =
            Intent(context, SafetyPauseActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_PACKAGE, pkg)
            }
    }
}
