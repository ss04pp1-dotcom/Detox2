package com.maxleveldetox.reels

import android.app.Activity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ReelsOverlayActivity (v2.0 Phase B2) — the escalation surfaces.
 *
 *   KIND_SOFT (attempt 2): dark card explaining the block with
 *     - "1 MIN" / "2 MIN" buttons that spend the daily reels allowance
 *     - one EMERGENCY PASS button (3/day, 1 min)
 *     - LEAVE button (always available — leaving is the goal)
 *
 *   KIND_HARD (attempt >= 3): black screen, 10-second countdown, NO exit
 *     except the always-reachable emergency dialer. When the countdown
 *     completes the manager clears the counter, plays the ringtone,
 *     posts the completion notification and force-stops the app — then
 *     this activity finishes and the user lands on HOME.
 *
 * Honesty: the hard lockout is 10 fixed seconds, not minutes. It exists
 * to break the autoplay loop, not to trap anyone (the emergency dialer
 * is one tap away at all times).
 */
class ReelsOverlayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var kind: String = KIND_SOFT
    private var targetPkg: String = ""
    private var attemptCount = 0
    private var countdownRemaining = HARD_LOCKOUT_SECONDS
    private var countdownView: TextView? = null
    private var sublabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = intent?.getStringExtra(EXTRA_KIND) ?: KIND_SOFT
        targetPkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        attemptCount = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // v2.3 r7: bar-free enforcement surface.
        com.maxleveldetox.enforcement.ImmersiveBars.apply(this)

        setContentView(buildContent())

        if (kind == KIND_HARD) startCountdown()
    }

    // -----------------------------------------------------------------
    // UI (programmatic — works in any process state)
    // -----------------------------------------------------------------

    private fun buildContent(): View {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val isHard = kind == KIND_HARD

        val root = LinearLayout(this).apply {
            setBackgroundColor(if (isHard) Color.parseColor("#050505") else Color.parseColor("#120C0E"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        fun title(text: String, color: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = 30f
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }

        fun body(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }

        // v2.5.9 (r11.1) — opportunity-cost line (user-requested): the
        // user's OWN weekly numbers, computed off the main thread and
        // attached when ready. Absent data (quiet week) leaves the card
        // unchanged.
        val costLine = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#F59E0B"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
            visibility = View.GONE
        }

        fun button(label: String, accent: Int, enabled: Boolean = true, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setTextColor(if (enabled) Color.WHITE else Color.parseColor("#64748B"))
                textSize = 14f
                isAllCaps = true
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.5f
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = pad(16).toFloat()
                    setColor(if (enabled) accent else Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, pad(52)
                ).apply { topMargin = pad(14) }
                setOnClickListener { if (enabled) onClick() }
            }

        if (isHard) {
            val accent = Color.parseColor("#EF4444")
            root.addView(title("COOLDOWN", accent))
            countdownView = TextView(this).apply {
                textSize = 64f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
                text = HARD_LOCKOUT_SECONDS.toString()
            }
            root.addView(countdownView)
            sublabel = body(
                "You tried to open short-form content $attemptCount times in a row.\n" +
                    "MAXLEVEL DETOX is holding this screen for 10 seconds.\n" +
                    "It will release automatically — nothing is required from you."
            )
            root.addView(sublabel)
            root.addView(costLine)
            attachOpportunityCost(costLine)
        } else {
            val accent = Color.parseColor("#F59E0B")
            root.addView(title("SHORTS BLOCKED", accent))
            root.addView(body(
                "That's attempt $attemptCount in the last minute.\n" +
                    "Next attempt starts a 10-second hard cooldown.\n\n" +
                    "You can spend part of your daily reels allowance\nto finish what you needed — or just leave."
            ))
            root.addView(costLine)
            attachOpportunityCost(costLine)

            val app = application as? MldApp
            val remainingPasses = app?.reelsEscalation?.statusJson()
                ?.optInt("emergencyPassesRemaining", 0) ?: 0

            root.addView(button("UNLOCK 1 MINUTE", accent) { requestUnlock(1) })
            root.addView(button("UNLOCK 2 MINUTES", accent) { requestUnlock(2) })
            root.addView(
                button(
                    "EMERGENCY PASS (1 MIN, $remainingPasses LEFT)",
                    Color.parseColor("#334155"),
                    enabled = remainingPasses > 0,
                ) { usePass() }
            )
            root.addView(button("LEAVE NOW", accent) { goHome() })
        }

        // Emergency is ALWAYS reachable (PRD §27) — never blocked.
        root.addView(Button(this).apply {
            text = "Emergency"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = true
            setOnClickListener { openDialer() }
        })

        return root
    }

    private fun pad(dp: Int): Int = (resources.displayMetrics.density * dp).toInt()

    /**
     * v2.5.9 (r11.1) — load the weekly opportunity-cost snapshot and reveal
     * the line when there is something honest to say (≥30 distraction
     * minutes this week). Fire-and-forget; the overlay never waits on it.
     */
    private fun attachOpportunityCost(view: TextView) {
        scope.launch {
            val snap = com.maxleveldetox.growth.OpportunityCostEngine.snapshot(this@ReelsOverlayActivity)
            if (snap == null) return@launch
            val text = "\"${snap.line}\""
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    view.text = text
                    view.visibility = View.VISIBLE
                } catch (_: Exception) {
                    // activity finished mid-load — nothing to do
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Unlock actions (soft overlay)
    // -----------------------------------------------------------------

    private fun requestUnlock(minutes: Int) {
        val app = application as? MldApp ?: return
        scope.launch {
            val r = app.reelsEscalation.requestSoftUnlock(targetPkg, minutes)
            runOnUiThread {
                if (r.ok) {
                    toast("Unlocked for $minutes minute(s). Use them well.")
                    finish()
                } else {
                    toast(r.message ?: "Unlock unavailable.")
                }
            }
        }
    }

    private fun usePass() {
        val app = application as? MldApp ?: return
        scope.launch {
            val r = app.reelsEscalation.useEmergencyPass(targetPkg)
            runOnUiThread {
                if (r.ok) {
                    toast("Emergency pass used — 1 minute.")
                    finish()
                } else {
                    toast(r.message ?: "No passes left today.")
                }
            }
        }
    }

    private fun toast(text: String) {
        try {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    // -----------------------------------------------------------------
    // Hard countdown
    // -----------------------------------------------------------------

    private val countdownTicker = object : Runnable {
        override fun run() {
            countdownRemaining -= 1
            countdownView?.text = countdownRemaining.coerceAtLeast(0).toString()
            if (countdownRemaining <= 0) {
                onCountdownComplete()
                return
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startCountdown() {
        handler.post(countdownTicker)
    }

    private fun onCountdownComplete() {
        val app = application as? MldApp
        if (app != null && targetPkg.isNotEmpty()) {
            app.reelsEscalation.onHardLockoutFinished(targetPkg)
        }
        goHome()
    }

    // -----------------------------------------------------------------
    // Navigation guards
    // -----------------------------------------------------------------

    override fun onBackPressed() {
        // The hard cooldown cannot be backed out of; the soft overlay
        // allows leaving (leaving is the goal).
        if (kind == KIND_HARD) return
        goHome()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the soft overlay voluntarily = same as LEAVE NOW.
        if (kind == KIND_SOFT) finish()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(home)
        } catch (_: Exception) {
        }
        finish()
    }

    private fun openDialer() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownTicker)
        // v2.5.5 audit fix m-10: cancel the scope so unlock-button
        // coroutines can never complete after destroy.
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_COUNT = "count"
        const val KIND_SOFT = "SOFT"
        const val KIND_HARD = "HARD"
        const val HARD_LOCKOUT_SECONDS = 10

        fun intentFor(context: android.content.Context, kind: String, pkg: String, count: Int): Intent =
            Intent(context, ReelsOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_COUNT, count)
            }
    }
}
