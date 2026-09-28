package com.maxleveldetox.enforcement

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
import com.maxleveldetox.overlay.EnforcementWall

/**
 * LockController (TRD §18) + LockScreenActivity.
 *
 * The blocking UI is NATIVE, not Flutter: it must appear even when the
 * Flutter engine is dead, killed, or mid-startup (TRD §1: Flutter must
 * never be the security boundary).
 *
 * Flow:
 *   Restricted app opened -> Accessibility detects package
 *   -> PolicyEngine -> BLOCK -> LockController.block()
 *   -> LockScreenActivity (over the restricted app) + HOME action.
 *
 * Kinds: BLOCKED_APP (dismiss = go home), WARNING (shorts escalation),
 * CAGE (no dismiss; auto-finish at cage end).
 */
object LockController {

    private val lastShown = mutableMapOf<String, Long>()
    private const val THROTTLE_MS = 1_500L

    fun block(context: Context, pkg: String) {
        val now = SystemClockNow.elapsed
        synchronized(lastShown) {
            val last = lastShown[pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            lastShown[pkg] = now
        }
        show(context, LockScreenActivity.KIND_BLOCKED_APP, pkg)
    }

    /** v2.3 r7: HARD surface — repeated attempts (countdown-gated). */
    fun blockHard(context: Context, pkg: String) {
        val now = SystemClockNow.elapsed
        synchronized(lastShown) {
            val last = lastShown[pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            lastShown[pkg] = now
        }
        show(context, LockScreenActivity.KIND_BLOCKED_APP, pkg, hard = true)
    }

    /** v2.3 r7: custom-message surface (schedules / app limits). */
    fun blockWithMessage(context: Context, pkg: String, message: String) {
        val now = SystemClockNow.elapsed
        synchronized(lastShown) {
            val last = lastShown[pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            lastShown[pkg] = now
        }
        show(context, LockScreenActivity.KIND_BLOCKED_APP, pkg,
            message = message)
    }

    fun warning(context: Context, count: Int, limit: Int) {
        show(context, LockScreenActivity.KIND_WARNING, "", count, limit)
    }

    fun cage(context: Context) {
        // v2.5 r9: when called from the accessibility service, the cage
        // surfaces on the overlay wall (above system bars — no home/shade
        // escape). Engine-2 / non-a11y callers fall through to the
        // activity surface.
        val endElapsed = try {
            val app = context.applicationContext as? MldApp
            val cage = app?.stateRepo?.blockingCage()
            val now = SystemClockNow.elapsed
            if (cage != null && cage.active && !cage.isExpired(now)) {
                now + cage.remainingSeconds(now) * 1000L
            } else now + 30 * 60 * 1000L
        } catch (_: Exception) {
            SystemClockNow.elapsed + 30 * 60 * 1000L
        }
        val svc = context as? android.accessibilityservice.AccessibilityService
        if (svc != null && EnforcementWall.isBound()) {
            EnforcementWall.showCage(svc, endElapsed)
            return
        }
        show(context, LockScreenActivity.KIND_CAGE, "")
    }

    private fun show(
        context: Context,
        kind: String,
        pkg: String,
        warningCount: Int = 0,
        warningLimit: Int = 0,
        hard: Boolean = false,
        message: String? = null,
    ) {
        com.maxleveldetox.accessibility.DiagLog.log("LOCK_SHOW", "kind=$kind pkg=$pkg hard=$hard")
        val intent = Intent(context, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(LockScreenActivity.EXTRA_KIND, kind)
            putExtra(LockScreenActivity.EXTRA_PACKAGE, pkg)
            putExtra(LockScreenActivity.EXTRA_WARNING_COUNT, warningCount)
            putExtra(LockScreenActivity.EXTRA_WARNING_LIMIT, warningLimit)
            putExtra(LockScreenActivity.EXTRA_HARD, hard)
            if (message != null) {
                putExtra(LockScreenActivity.EXTRA_MESSAGE, message)
            }
        }
        // The lock screen is part of enforcement, not the app task —
        // launching from any context (incl. accessibility service) works.
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Launcher-side restrictions on some OEMs; the HOME action in
            // the accessibility service still moved the user away.
            com.maxleveldetox.accessibility.DiagLog.logError("LockController.show($kind)", e)
        }
    }
}

class LockScreenActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    // v2.5.5 audit fix m-10: the unused CoroutineScope (never launched,
    // never cancelled) was dead weight — removed.
    private var kind: String = LockControllerKind.BLOCKED_APP.name
    private var blockedPkg: String = ""
    private var warningCount = 0
    private var warningLimit = 0
    private var hardMode = false
    private var customMessage: String? = null

    // v2.3 r7 home-trap state: set when the user legitimately leaves this
    // surface (dismiss button / session end) — otherwise onPause re-asserts
    // the wall while enforcement is live (Social Sentry dual-surface loop).
    private var intentionallyDone = false
    private var emergencyUntilElapsed = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = intent?.getStringExtra(EXTRA_KIND) ?: LockControllerKind.BLOCKED_APP.name
        blockedPkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        warningCount = intent?.getIntExtra(EXTRA_WARNING_COUNT, 0) ?: 0
        warningLimit = intent?.getIntExtra(EXTRA_WARNING_LIMIT, 5) ?: 5
        hardMode = intent?.getBooleanExtra(EXTRA_HARD, false) ?: false
        customMessage = intent?.getStringExtra(EXTRA_MESSAGE)

        // Show over the lock screen, keep the screen honest.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // v2.3 r7: no status/nav bars on the wall — removes the shade and
        // swipe-up affordances entirely (ShadeGuard covers edge swipes).
        ImmersiveBars.apply(this)

        setContentView(buildContent())
        startTicker()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveBars.apply(this)
    }

    // -----------------------------------------------------------------
    // UI built programmatically — no layout inflation dependencies, works
    // in any process state (UI/UX §23–28 visual language).
    // -----------------------------------------------------------------

    private fun buildContent(): View {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val isCage = kind == LockControllerKind.CAGE.name
        val isWarning = kind == LockControllerKind.WARNING.name

        val bg = when {
            isCage -> Color.parseColor("#140B0B")
            isWarning -> Color.parseColor("#160D0D")
            else -> Color.parseColor("#0A0E1A")
        }
        val accent = when {
            isCage -> Color.parseColor("#EF4444")
            isWarning -> if (warningCount >= warningLimit) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B")
            else -> Color.parseColor("#6366F1")
        }

        val root = LinearLayout(this).apply {
            setBackgroundColor(bg)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        fun title(text: String, size: Float, color: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }

        fun body(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }

        timerView = TextView(this).apply {
            textSize = 52f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }

        when {
            isCage -> {
                root.addView(title("CAGE", 34f, accent))
                root.addView(timerView)
                root.addView(body("Repeated violations detected.\nYour restriction has been temporarily intensified."))
                root.addView(body("Cage ends automatically when the timer reaches zero."))
            }
            isWarning -> {
                root.addView(title(if (warningCount >= warningLimit) "FINAL WARNING" else "SHORTS BLOCKED", 28f, accent))
                root.addView(body(
                    if (warningCount >= warningLimit)
                        "This is your last warning.\nOne more attempt activates CAGE for 30 minutes."
                    else
                        "Short-form content is restricted.\nThis is warning $warningCount of $warningLimit."
                ))
                root.addView(dotsView(accent).also { dots -> rootDots = dots })
                root.addView(button("LEAVE NOW", accent) { goHome() })
            }
            else -> {
                // v2.5.5 audit fix m-4: drop the copy-paste identical-branch
                // conditional — the hard-mode distinction lives in the body.
                root.addView(title("ACCESS BLOCKED", 28f, accent))
                if (hardMode) {
                    root.addView(body("Repeated attempts detected. The exit button unlocks after a short wait."))
                }
                root.addView(timerView)
                root.addView(body(customMessage ?: blockedMessage()))
                root.addView(button(
                    if (hardMode) "WAIT…" else "RETURN TO FOCUS",
                    accent,
                ) { goHome() }.also { btn ->
                    if (hardMode) gateButton(btn, HARD_GATE_SECONDS)
                })
            }
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

    private var timerView: TextView? = null
    private var rootDots: LinearLayout? = null

    private fun dotsView(accent: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, pad(12), 0, pad(12))
        for (i in 0 until warningLimit) {
            val filled = i < warningCount
            addView(View(this@LockScreenActivity).apply {
                val dp10 = pad(10)
                layoutParams = LinearLayout.LayoutParams(dp10, dp10).apply {
                    setMargins(dp10 / 2, 0, dp10 / 2, 0)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (filled) accent else Color.TRANSPARENT)
                    setStroke(3, accent)
                }
            })
        }
    }

    private fun pad(dp: Int): Int = (resources.displayMetrics.density * dp).toInt()

    private fun button(label: String, accent: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        isAllCaps = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = pad(16).toFloat()
            setColor(accent)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, pad(54)
        ).apply { topMargin = pad(24) }
        setOnClickListener { onClick() }
    }

    /** v2.3 r7: countdown-gated dismissal on the HARD surface — a
     *  reflexive tap cannot dismiss the wall (Social Sentry overlay UX). */
    private fun gateButton(btn: Button, seconds: Int) {
        val originalLabel = "RETURN TO FOCUS"
        var left = seconds
        btn.isEnabled = false
        btn.text = "WAIT ${left}s"
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing) return
                left -= 1
                if (left <= 0) {
                    btn.isEnabled = true
                    btn.text = originalLabel
                } else {
                    btn.text = "WAIT ${left}s"
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
        handler.postDelayed(tick, 1_000L)
    }

    private fun blockedMessage(): String {
        val app = application as? MldApp ?: return "This app is restricted while your session is active."
        val session = app.stateRepo.blockingSession()
        val name = appLabel(blockedPkg)
        return buildString {
            append(name.ifBlank { "This app" })
            append(" is restricted")
            if (session != null) append(" until your ${if (session.mode == SessionMode.STUDY) "Study" else "Detox"} session ends.")
            else append(" while your session is active.")
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { "" }

    // -----------------------------------------------------------------
    // Ticker: updates remaining time; cage auto-finishes at zero.
    // -----------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            val app = application as? MldApp ?: return
            val now = SystemClockNow.elapsed
            val session = app.stateRepo.blockingSession()
            val cage = app.stateRepo.blockingCage()

            when (kind) {
                LockControllerKind.CAGE.name -> {
                    val remaining = cage.remainingSeconds(now)
                    timerView?.text = SessionEngine.formatSeconds(remaining)
                    if (cage.isExpired(now)) {
                        finish()
                        return
                    }
                }
                else -> {
                    if (session != null && session.status.isEnforcing) {
                        timerView?.text = SessionEngine.formatSeconds(session.remainingSeconds(now))
                    } else if (session == null) {
                        // Session ended while the lock screen was showing.
                        finish()
                        return
                    }
                }
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startTicker() {
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Back does not dismiss a cage/warning. For blocked apps, back is
        // allowed (leaving is the goal) — but we go HOME instead of back to
        // the restricted app.
        if (kind == LockControllerKind.CAGE.name) return
        goHome()
    }

    // -----------------------------------------------------------------
    // v2.3 r7 — HOME TRAP: while enforcement is live and the user has not
    // legitimately dismissed this surface, losing focus re-asserts the
    // wall. This is the kiosk-style detection loop (TRD §38) that closes
    // the Home-button escape hatch.
    // -----------------------------------------------------------------

    private val reassertRunnable = object : Runnable {
        override fun run() {
            if (intentionallyDone || isFinishing) return
            // Emergency window (dialer open) — never cover a real call.
            // v2.9 r16: same stand-down while the emergency LOCKDOWN is
            // live — the dialer owns the screen and the a11y bounce loop
            // enforces everything else.
            if (SystemClockNow.elapsed < emergencyUntilElapsed ||
                com.maxleveldetox.safety.EmergencyLockdown.isActive(this@LockScreenActivity)
            ) {
                handler.postDelayed(this, REASSERT_MS)
                return
            }
            if (!enforcementStillLive()) {
                finish()
                return
            }
            try {
                startActivity(Intent(this@LockScreenActivity, LockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(EXTRA_KIND, kind)
                    putExtra(EXTRA_PACKAGE, blockedPkg)
                    putExtra(EXTRA_WARNING_COUNT, warningCount)
                    putExtra(EXTRA_WARNING_LIMIT, warningLimit)
                    putExtra(EXTRA_HARD, hardMode)
                    if (customMessage != null) putExtra(EXTRA_MESSAGE, customMessage)
                })
            } catch (_: Exception) {
            }
            handler.postDelayed(this, REASSERT_MS)
        }
    }

    private fun enforcementStillLive(): Boolean {
        val app = application as? MldApp ?: return false
        return try {
            val session = app.stateRepo.blockingSession()
            if (session != null && session.status.isEnforcing) return true
            val cage = app.stateRepo.blockingCage()
            if (cage.active && !cage.isExpired(SystemClockNow.elapsed)) return true
            // v2.5 r9: the trap now holds for every enforcement engine —
            // Monk Mode, Lock My Phone and Prime Commit walls too (the
            // r7 version released the wall for these, which read as
            // "home works" during enforcement).
            if (com.maxleveldetox.monk.MonkModeManager.isActive(this)) return true
            if (com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(this)) return true
            val prime = app.stateRepo.blockingPrime()
            prime.active
        } catch (_: Exception) {
            // State unreadable: safer to hold the wall one extra cycle.
            true
        }
    }

    override fun onPause() {
        super.onPause()
        if (!intentionallyDone) {
            handler.removeCallbacks(reassertRunnable)
            handler.postDelayed(reassertRunnable, REASSERT_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(reassertRunnable)
        ImmersiveBars.apply(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If the user somehow lands back on a restricted app, the
        // accessibility service will re-trigger the lock — this is the
        // detection loop (TRD §38), not a one-shot gate.
    }

    private fun goHome() {
        intentionallyDone = true
        handler.removeCallbacks(reassertRunnable)
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(home)
        } catch (_: Exception) { /* already home */ }
        finish()
    }

    private fun openDialer() {
        // v2.9 r16 (user-reported bug): emergency from the lock surface now
        // enters the DIALER-ONLY LOCKDOWN — same as every other emergency
        // surface. The legacy behavior stood this trap down for 90 s and
        // opened a bare dialer with NOTHING enforcing, leaving the whole
        // phone open mid-emergency. The lockdown keeps the session armed
        // and bounces everything that is not the dialer family.
        com.maxleveldetox.safety.EmergencyLockdown.start(this)
        try {
            com.maxleveldetox.safety.EmergencyLockdown.openDialer(this)
        } catch (_: Exception) { /* dialer missing is an OS-level concern */ }
        // The lockdown owns the screen; the trap must not sit on top of
        // the dialer re-asserting itself.
        intentionallyDone = true
        handler.removeCallbacks(reassertRunnable)
        finish()
    }

    enum class LockControllerKind { BLOCKED_APP, WARNING, CAGE }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_WARNING_COUNT = "warningCount"
        const val EXTRA_WARNING_LIMIT = "warningLimit"
        const val EXTRA_HARD = "hard"
        const val EXTRA_MESSAGE = "message"

        /** v2.3 r7: hard-surface dismissal gate. */
        const val HARD_GATE_SECONDS = 10

        /** v2.5 r9: home-trap re-assert cadence (350 ms — Social-Sentry
         *  grade; the r7 400 ms felt loose on gesture-nav devices). */
        private const val REASSERT_MS = 350L

        /** v2.3 r7: how long the trap stands down for a real emergency
         *  call after the dialer opens. */
        private const val EMERGENCY_WINDOW_MS = 90_000L

        // String constants matching the enum for intent extras.
        const val KIND_BLOCKED_APP = "BLOCKED_APP"
        const val KIND_WARNING = "WARNING"
        const val KIND_CAGE = "CAGE"
    }
}
