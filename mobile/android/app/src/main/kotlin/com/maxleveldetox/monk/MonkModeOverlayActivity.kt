package com.maxleveldetox.monk

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
 * MonkModeOverlayActivity (v2.0 Phase B3) — the monk lock surface.
 *
 * Full-screen, no-dismiss lock face shown while the FSM is LOCKED:
 *   - goal text + remaining countdown (1s ticker)
 *   - BACK is swallowed (leaving is not an option until expiry)
 *   - emergency dialer is ALWAYS one tap away (PRD §27)
 *   - finishes by itself the moment monk mode ends or the FSM moves to
 *     ALLOWED_APP (the ticker checks every second)
 *
 * This is the Activity-based surface — the same dual-surface strategy as
 * the reference app: the service tick re-launches us whenever the user
 * somehow navigates away, so escaping requires defeating both.
 */
class MonkModeOverlayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var timerView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // v2.3 r7: bar-free enforcement surface.
        com.maxleveldetox.enforcement.ImmersiveBars.apply(this)

        setContentView(buildContent())
        startTicker()
    }

    private fun buildContent(): View {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val accent = Color.parseColor("#6366F1")

        val root = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#07070D"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        val goal = MonkModeManager.goal(this)

        root.addView(TextView(this).apply {
            text = "MONK MODE"
            textSize = 30f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        })
        timerView = TextView(this).apply {
            textSize = 56f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        root.addView(timerView)
        root.addView(TextView(this).apply {
            text = goal.ifBlank { "The phone is locked to your commitment." }
            textSize = 16f
            setTextColor(Color.parseColor("#C7D2FE"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Only your allowlisted apps are reachable.\nIncoming calls are always answered automatically.\nThis ends on its own — no button, no code, no shortcut."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        })

        // Emergency is ALWAYS reachable (PRD §27) — never blocked.
        // v2.7 r13 (user-requested): emergency = DIALER-ONLY LOCKDOWN — the
        // phone stays enforced, confined to the dialer until the user ends
        // it from the app (or the 15-minute safety cap expires).
        root.addView(Button(this).apply {
            text = "Emergency"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = true
            setOnClickListener {
                try {
                    com.maxleveldetox.safety.EmergencyLockdown.start(this@MonkModeOverlayActivity)
                    com.maxleveldetox.safety.EmergencyLockdown.openDialer(this@MonkModeOverlayActivity)
                } catch (_: Exception) {
                }
            }
        })

        return root
    }

    private val ticker = object : Runnable {
        override fun run() {
            when {
                !MonkModeManager.isActive(this@MonkModeOverlayActivity) -> {
                    finish()
                    return
                }
                // v2.7 r13: the emergency lockdown owns the screen while it
                // lasts — the dialer must stay visible and the service tick
                // polices the boundary (dialer + our app only).
                com.maxleveldetox.safety.EmergencyLockdown.isActive(this@MonkModeOverlayActivity) -> {
                    finish()
                    return
                }
                MonkModeManager.state(this@MonkModeOverlayActivity) ==
                    MonkModeManager.MonkState.ALLOWED_APP -> {
                    finish() // inside an allowed app / on a call
                    return
                }
                else -> {
                    val remaining = MonkModeManager.remainingSeconds(this@MonkModeOverlayActivity)
                    timerView?.text = format(remaining)
                }
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startTicker() {
        handler.post(ticker)
    }

    private fun format(seconds: Int): String =
        String.format(java.util.Locale.US, "%02d:%02d:%02d",
            seconds / 3600, (seconds % 3600) / 60, seconds % 60)

    override fun onBackPressed() {
        // Swallowed: the monk surface has no back exit.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // The service tick re-asserts the surface if the user escaped.
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    companion object {
        fun intentFor(context: Context): Intent =
            Intent(context, MonkModeOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
    }
}
