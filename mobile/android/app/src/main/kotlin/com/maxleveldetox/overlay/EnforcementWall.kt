package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.enforcement.SystemClockNow

/**
 * EnforcementWall (v2.5 r9) — PRIMARY blocking surface, Social-Sentry parity.
 *
 * WHY THIS EXISTS (the r9 home/shade parity fix):
 * The old primary surface was LockScreenActivity — a normal activity sits
 * BELOW the SystemUI windows, so the home button / gesture and the
 * notification-shade swipe always reached the system first. Social Sentry's
 * enforcement overlays are TYPE_ACCESSIBILITY_OVERLAY windows (2032), which
 * float ABOVE the status bar (2000) and the navigation bar (2019) — the
 * user's top-edge shade swipe and bottom home gesture land on OUR window
 * and are consumed. That is why "home didn't work and the panel couldn't be
 * pulled down" in Social Sentry, and this wall reproduces exactly that.
 *
 * Layering (Social Sentry mechanisms #24 + #25 + back-key #23):
 *   - Full-screen TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_FOCUSABLE +
 *     FLAG_LAYOUT_IN_SCREEN + cutout shortEdges → covers status AND nav
 *     bar regions; touches never reach SystemUI.
 *   - Immersive system-ui visibility → no status bar hint while showing.
 *   - HARD surface: countdown-gated dismissal (10 s) — no reflexive escape.
 *   - Emergency dialer is ALWAYS reachable (PRD §27) — the wall stands
 *     down for 90 s, then re-arms.
 *   - BACK is consumed by the accessibility service's onKeyEvent while
 *     this wall is showing (see DetoxAccessibilityService).
 *
 * Fallbacks: if the a11y overlay cannot be added (service not connected,
 * OEM quirk), callers fall back to LockScreenActivity — never a gap.
 */
object EnforcementWall {

    const val KIND_BLOCKED = "BLOCKED"
    const val KIND_HARD = "HARD"
    const val KIND_CAGE = "CAGE"

    private const val HARD_GATE_SECONDS = 10
    private const val EMERGENCY_WINDOW_MS = 90_000L
    private const val THROTTLE_MS = 600L

    private val handler = Handler(Looper.getMainLooper())
    private val throttle = mutableMapOf<String, Long>()

    private var serviceRef: AccessibilityService? = null
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var overlayKind: String? = null
    private var ticker: Runnable? = null
    private var emergencyUntilElapsed = 0L
    private var cageEndElapsed = 0L
    private var nativeCageView: NativeLockKioskView? = null

    /** Bound while the accessibility service is connected (engine 1). */
    fun bind(service: AccessibilityService) {
        serviceRef = service
    }

    fun unbind() {
        serviceRef = null
        hide()
    }

    fun isBound(): Boolean = serviceRef != null

    /** True when any wall overlay is currently on screen. */
    fun isShowing(): Boolean = overlay != null

    /**
     * True while the cage lockout is running.
     *
     * v2.9.4 r20: IN-MEMORY ONLY. The old fallback also consulted the
     * PERSISTED cage snapshot — a leftover 30-minute cage from a previous
     * install (or one warped by an elapsedRealtime reset across reboot)
     * kept "reasserting the cage" over every app for hours/days after
     * the user had long left shorts ("hut kore bondi korlo — ami Chrome-e
     * chilam, shorts dekhchilam na"). The only production cage now is
     * the 60 s in-memory burst cage; legacy persisted cages are cleared
     * at recovery (SessionEngine.recoverIfNeeded).
     */
    fun isCageActive(): Boolean {
        return cageEndElapsed > SystemClockNow.elapsed
    }

    fun getCageEndElapsed(): Long = cageEndElapsed

    /** Reassert the cage overlay if active and currently not on screen. */
    fun reassertCage(service: AccessibilityService) {
        if (isCageActive() && overlay == null) {
            show(service, KIND_CAGE, "", "")
        }
    }

    /** True while the emergency stand-down window is open. */
    fun inEmergencyWindow(): Boolean =
        SystemClockNow.elapsed < emergencyUntilElapsed

    // -----------------------------------------------------------------
    // Public surfaces
    // -----------------------------------------------------------------

    /** Standard block wall for [pkg] (soft — one tap returns home). */
    fun showBlocked(service: AccessibilityService, pkg: String, message: String) {
        show(service, KIND_BLOCKED, pkg, message)
    }

    /** HARD wall — repeated attempts; dismissal gated behind a countdown. */
    fun showHard(service: AccessibilityService, pkg: String, message: String) {
        show(service, KIND_HARD, pkg, message)
    }

    /** Cage wall — timer only, auto-removes at expiry, no dismiss button. */
    fun showCage(service: AccessibilityService, endElapsed: Long) {
        cageEndElapsed = endElapsed
        show(service, KIND_CAGE, "", "")
    }

    /**
     * v2.9.9 r25 (user request: cage = active Mood's session screen) —
     * arm the cage timer WITHOUT showing the wall overlay. The cage is
     * served IN THE APP: the accessibility service launches MainActivity
     * and the Flutter cage surface (which mirrors the running mood's
     * session screen 1:1) takes over the whole in-app experience. The
     * wall only ever covers OTHER apps — the a11y cage gate reasserts it
     * the moment the user leaves our app for anything that is not the
     * dialer.
     */
    fun startCage(endElapsed: Long) {
        cageEndElapsed = endElapsed
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun show(
        service: AccessibilityService,
        kind: String,
        pkg: String,
        message: String,
    ) {
        val now = SystemClockNow.elapsed
        synchronized(throttle) {
            val last = throttle[kind + ":" + pkg] ?: 0L
            if (now - last < THROTTLE_MS) return
            throttle[kind + ":" + pkg] = now
        }

        // Accessibility callbacks arrive on the main thread — run
        // synchronously there so callers can check isShowing() right
        // away (fallback routing depends on it).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideInternal()
            addWall(service, kind, pkg, message)
        } else {
            handler.post {
                hideInternal()
                addWall(service, kind, pkg, message)
            }
        }
    }

    private fun addWall(
        service: AccessibilityService,
        kind: String,
        pkg: String,
        message: String,
    ) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val accent = when (kind) {
            KIND_CAGE -> Color.parseColor("#EF4444")
            else -> Color.parseColor("#6366F1")
        }

        val pad = (service.resources.displayMetrics.density * 24).toInt()

        val root = if (kind == KIND_CAGE) {
            val cageKiosk = NativeLockKioskView(
                service = service,
                mode = com.maxleveldetox.enforcement.SessionMode.DETOX,
                subjectName = "",
                totalDurationSeconds = 60,
                endElapsed = cageEndElapsed,
                isCage = true,
            )
            nativeCageView = cageKiosk
            cageKiosk
        } else {
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0A0E1A"))
                setPadding(pad, pad * 2, pad, pad * 2)
            }
        }

        fun title(text: String): TextView = TextView(service).apply {
            this.text = text
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        fun body(text: String): TextView = TextView(service).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }

        val timerView = TextView(service).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        when (kind) {
            KIND_CAGE -> {
                // Handled natively by NativeLockKioskView
            }
            KIND_HARD -> {
                root.addView(title("ACCESS BLOCKED"))
                root.addView(timerView)
                root.addView(body("Repeated attempts detected.\nThe exit unlocks after a short wait."))
                root.addView(body(message))
                root.addView(gatedButton(service, accent))
            }
            else -> {
                root.addView(title("ACCESS BLOCKED"))
                root.addView(timerView)
                root.addView(body(message))
                root.addView(solidButton(service, "RETURN TO FOCUS", accent) {
                    goHomeAndHide(service)
                })
            }
        }

        // Emergency is ALWAYS reachable (PRD §27).
        root.addView(Button(service).apply {
            text = "Emergency"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = true
            setOnClickListener { openDialer(service) }
        })

        // Immersive: no status-bar hint behind the wall (Social Sentry
        // setSystemUiVisibility(5894) on their overlays).
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
            // NOT_FOCUSABLE: the window consumes every touch inside its
            // bounds (full screen — including the status/nav bar areas)
            // but does not take keyboard focus; BACK is handled by the
            // service's onKeyEvent instead.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            wm.addView(root, params)
            overlay = root
            overlayPkg = pkg
            overlayKind = kind
            DiagLog.log("WALL_SHOW", "kind=$kind pkg=$pkg")
            startTicker(service, kind, timerView)
        } catch (e: Exception) {
            // Overlay stacking unavailable — caller falls back to the
            // activity surface (LockController). Never a gap.
            DiagLog.logError("EnforcementWall.addWall", e)
        }
    }

    private fun solidButton(
        service: AccessibilityService,
        label: String,
        accent: Int,
        onClick: () -> Unit,
    ): Button = Button(service).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        isAllCaps = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = GradientDrawable().apply {
            val d = service.resources.displayMetrics.density
            cornerRadius = 16 * d
            setColor(accent)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (service.resources.displayMetrics.density * 54).toInt(),
        ).apply { topMargin = (service.resources.displayMetrics.density * 24).toInt() }
        setOnClickListener { onClick() }
    }

    /** Countdown-gated dismissal (Social Sentry overlay UX hardening). */
    private fun gatedButton(service: AccessibilityService, accent: Int): Button {
        var left = HARD_GATE_SECONDS
        val btn = solidButton(service, "WAIT ${left}s", accent) { }
        btn.isEnabled = false
        val tick = object : Runnable {
            override fun run() {
                if (overlayKind != KIND_HARD) return
                left -= 1
                if (left <= 0) {
                    btn.isEnabled = true
                    btn.text = "RETURN TO FOCUS"
                    btn.setOnClickListener { goHomeAndHide(service) }
                } else {
                    btn.text = "WAIT ${left}s"
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
        handler.postDelayed(tick, 1_000L)
        return btn
    }

    /** Ticker: countdown text + auto-remove when the wall's reason ends. */
    private fun startTicker(service: AccessibilityService, kind: String, timerView: TextView) {
        ticker?.let { handler.removeCallbacks(it) }
        val t = object : Runnable {
            override fun run() {
                if (overlay == null) return
                val app = service.application as? MldApp
                try {
                    val now = SystemClockNow.elapsed
                    when (kind) {
                        KIND_CAGE -> {
                            val remaining = ((cageEndElapsed - now) / 1000L)
                                .coerceAtLeast(0L).toInt()
                            if (remaining <= 0) {
                                cageEndElapsed = 0L
                                hideInternal()
                                return
                            }
                            nativeCageView?.updateTick(remaining, 60)
                            timerView.text = formatSeconds(remaining)
                        }
                        else -> {
                            val session = app?.stateRepo?.blockingSession()
                            if (session == null) {
                                hideInternal()
                                return
                            }
                            if (session.status.isEnforcing) {
                                timerView.text =
                                    formatSeconds(session.remainingSeconds(now))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        ticker = t
        handler.post(t)
    }

    private fun formatSeconds(total: Int): String {
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    private fun goHomeAndHide(service: AccessibilityService) {
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
        }
        hideInternal()
    }

    private fun openDialer(service: AccessibilityService) {
        // v2.9 r16 (user-reported bug): emergency from the wall now enters
        // the DIALER-ONLY LOCKDOWN (EmergencyLockdown) — exactly like every
        // other emergency surface. The legacy behavior here stood the wall
        // down for 90 s and opened the dialer with NOTHING enforcing, which
        // left the whole phone open mid-emergency. The lockdown keeps every
        // enforcement surface armed and bounces anything that is not the
        // dialer family straight back to the dialer.
        com.maxleveldetox.safety.EmergencyLockdown.start(service)
        hideInternal()
        com.maxleveldetox.safety.EmergencyLockdown.openDialer(service)
    }

    /** Remove the wall (must run on the main thread). */
    private fun hideInternal() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        nativeCageView = null
        // v2.5.5 audit fix m-5: clear the state fields BEFORE the serviceRef
        // bail-out — after unbind() (service death) the old code returned
        // early with overlay != null, so isShowing() lied until the next
        // show/hide cycle (the system had already removed the window).
        val view = overlay
        overlay = null
        overlayPkg = null
        overlayKind = null
        if (view == null) return
        val svc = serviceRef ?: return // dead service → window already gone
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    /** Hide from any thread (posts to main). */
    fun hide() {
        handler.post { hideInternal() }
    }

    /** True if the live wall is for [pkg] (stale-check on foreground change). */
    fun isShowingFor(pkg: String?): Boolean =
        overlay != null && overlayPkg != null && overlayPkg == pkg

    /**
     * Call from the accessibility service on every foreground change:
     * a wall for a package other than the current foreground is stale —
     * EXCEPT cage/shorts-lockout which persist until their timer ends.
     */
    fun reconcile(service: AccessibilityService, foregroundPkg: String?) {
        if (overlay == null) return
        val kind = overlayKind ?: return
        if (kind == KIND_CAGE) return
        if (foregroundPkg != null && foregroundPkg != overlayPkg) {
            hide()
        }
    }

    /** Re-assert helper for ShadeGuard: if a wall should be showing but
     *  isn't (tamper/OEM removal), bring it back for the given package. */
    fun reassert(service: AccessibilityService, pkg: String, hard: Boolean) {
        if (overlay != null) return
        // v2.9 r16: during the emergency lockdown the dialer owns the
        // screen — the a11y bounce loop handles everything; re-asserting
        // the wall here would cover the dialer.
        if (inEmergencyWindow() ||
            com.maxleveldetox.safety.EmergencyLockdown.isActive(service)) return
        val app = service.application as? MldApp ?: return
        val session = try {
            app.stateRepo.blockingSession()
        } catch (_: Exception) {
            null
        }
        val enforcing = session != null && session.status.isEnforcing
        if (!enforcing) return
        if (hard) showHard(service, pkg, "This app is blocked during the active session.")
        else showBlocked(service, pkg, "This app is blocked during the active session.")
    }

    /** Settings check used by engine-2 gating. */
    @Suppress("unused")
    private fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)
}
