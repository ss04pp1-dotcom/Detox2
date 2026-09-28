package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.enforcement.PolicyDecision
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.enforcement.SessionSnapshot
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.ViolationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SessionKiosk (v2.9 r17) — user-requested TOTAL lockdown for Study/Detox.
 *
 * THE COMPLAINT (v2.9.0 user report): "Study mode and Detox mode use app
 * pinning — because of that the home button works, the back button works
 * and the notification panel works. I want NOTHING to work in these modes.
 * No way out at all."
 *
 * WHY PINNING FAILED: user-consent screen pinning (Activity.startLockTask
 * without device-owner) is escapable by design (hold Back + Recents), is
 * skipped entirely for STUDY sessions, never covers the launcher, and it
 * BLOCKS the emergency dialer launch while pinned (a non-allowlisted
 * activity cannot come forward) — one of the roots of the
 * "emergency exits, system fails" bug. Pinning is GONE as of r17.
 *
 * THE KIOSK LAYER (three cooperating surfaces, all owned here):
 *
 *   1. WALL — a full-screen TYPE_ACCESSIBILITY_OVERLAY window that floats
 *      ABOVE the status bar and the navigation bar. Home gestures, recents
 *      swipes and shade pulls physically land on OUR window and are
 *      consumed. It becomes the phone's only surface whenever the user is
 *      anywhere but our app or a session-allowed app:
 *        launcher, settings, unknown apps, systemui (shade / recents /
 *        power dialog) — everything is covered instantly.
 *
 *   2. STRIPS — slim top + bottom overlays (status-bar height, nav-bar
 *      height) shown while OUR OWN app or a STUDY-allowlisted app is in
 *      the foreground: the app stays usable, but the shade and the
 *      home/gesture areas are covered.
 *
 *   3. KEY FILTER — DetoxAccessibilityService.onKeyEvent consumes BACK /
 *      APP_SWITCH / HOME hardware keys while the wall or strips are up
 *      (see DetoxAccessibilityService r17).
 *
 * STAND-DOWNS (safety always outranks strictness — PRD §27):
 *   - EmergencyLockdown live  -> dialer owns the screen, everything hides
 *     (EmergencyLockdown.start calls hideAll directly).
 *   - temp-unlock window live -> paid window behaves like v2.8.
 *   - study break (PAUSED) / RECOVERY / CAGE statuses -> own surfaces.
 *   - settings grace window live -> Settings must stay reachable.
 *
 * SELF-HEALING: the 1 s ticker re-checks the armed state (session end,
 * pause, emergency) and the EnforcementService 30 s sweep re-syncs — a
 * killed window or a missed event recovers within seconds.
 */
object SessionKiosk {

    private const val WALL_BG = "#0A0E1A"
    private const val STRIP_BG = "#0A0E1A"
    private const val ACCENT = "#6366F1"
    private const val DANGER = "#EF4444"

    /** Max allowlist shortcut buttons on the STUDY wall. */
    private const val MAX_SHORTCUTS = 6

    /** Escape-attempt violation recording throttle (per package). */
    private const val VIOLATION_THROTTLE_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var serviceRef: AccessibilityService? = null

    // Live windows (main-thread confined — all mutations post through handler)
    private var wall: View? = null
    private var topStrip: View? = null
    private var bottomStrip: View? = null
    private var ticker: Runnable? = null

    /** Snapshot cached at wall-build time so the 1 s tick never touches
     *  DataStore just to paint the countdown. */
    private var wallEndElapsed = 0L
    private var wallMode = SessionMode.DETOX
    private var wallSubject = ""
    private var wallTotalSeconds = 0
    private var nativeKioskView: NativeLockKioskView? = null
    private var lastViolationAt = mutableMapOf<String, Long>()

    // -----------------------------------------------------------------
    // Service binding
    // -----------------------------------------------------------------

    fun bind(service: AccessibilityService) {
        serviceRef = service
        DiagLog.log("KIOSK", "bound to accessibility service")
    }

    fun unbind() {
        serviceRef = null
        removeAllInternal()
    }

    fun isWallShowing(): Boolean = wall != null
    fun isStripShowing(): Boolean = topStrip != null || bottomStrip != null

    /**
     * v2.9.2 r18 — public armed check (MainActivity consumes the BACK
     * gesture/key while armed, so the session screen itself holds the
     * user like the cage). True while a session is actively enforcing
     * with no stand-down window open.
     */
    fun isArmed(context: Context): Boolean = armedSession(context) != null

    // -----------------------------------------------------------------
    // Armed-state computation
    // -----------------------------------------------------------------

    /**
     * The kiosk is ARMED only while a session is actively enforcing with
     * no stand-down window open. Returns the session (for allowlist +
     * countdown data) or null.
     */
    private fun armedSession(context: Context): SessionSnapshot? {
        val app = context.applicationContext as? MldApp ?: return null
        return try {
            val session = app.stateRepo.blockingSession()
                ?: return null
            // ACTIVE only — PAUSED (break), RECOVERY, CAGE, TEMP_UNLOCK,
            // COMPLETED etc. all own their own surfaces / stand down.
            if (session.status != SessionStatus.ACTIVE) return null

            val now = SystemClockNow.elapsed

            // Paid temp-unlock window: the kiosk lifts entirely.
            val unlock = app.stateRepo.blockingTempUnlock()
            if (unlock.active && !unlock.isExpired(now)) return null

            // Settings restore window: Settings must stay reachable.
            val grace = app.stateRepo.blockingGraceWindow()
            if (grace != null && grace.expiresElapsed > now) return null

            // Emergency lockdown owns the screen (dialer only).
            if (com.maxleveldetox.safety.EmergencyLockdown.isActive(context)) return null

            session
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------
    // A11y pipeline hook — called on every foreground window change.
    // Returns true when the kiosk walled the package (pipeline stops).
    // -----------------------------------------------------------------

    fun onForegroundEvent(
        service: AccessibilityService,
        pkg: String,
        decision: PolicyDecision,
    ): Boolean {
        val session = armedSession(service)
        if (session == null) {
            // Disarmed — drop any leftover surfaces (e.g. temp unlock
            // opened, break started, emergency started).
            if (isWallShowing() || isStripShowing()) hideAll(service)
            return false
        }

        // Real block decisions keep their own (violation-recording,
        // escalation-aware) walls. EMERGENCY_ALLOW keeps the dialer
        // family free (calls are sacred — PRD §27).
        when (decision) {
            PolicyDecision.BLOCK,
            PolicyDecision.CAGE_BLOCK,
            PolicyDecision.MONK_BLOCK,
            PolicyDecision.EMERGENCY_ALLOW,
            PolicyDecision.TEMP_ALLOW,
            -> return false
            PolicyDecision.ALLOW, PolicyDecision.SYSTEM_ALLOW -> Unit
        }

        // Our own app: strips only (the Flutter session screen is the UX).
        if (pkg == service.packageName) {
            showStripsInternal(service)
            return false
        }

        val monkActive = com.maxleveldetox.monk.MonkModeManager.isActive(service)
        val isAllowedStudy = session != null && pkg in session.allowedPackages
        val isAllowedMonk = monkActive && pkg in com.maxleveldetox.monk.MonkModeManager.allowedApps(service)

        // STUDY / MONK allowlist: the app is usable, but shade/gesture areas stay
        // covered by the strips.
        if (isAllowedStudy || isAllowedMonk) {
            showStripsInternal(service)
            return false
        }

        // Keyboards are companions of a focused (allowed) surface — never
        // wall the IME itself.
        if (isInputMethod(service, pkg)) return false

        // Everything else — the LAUNCHER above all — gets the WALL.
        if (session != null) {
            showWallInternal(service, session, pkg)
            recordEscapeAttempt(service, session, pkg)
            return true
        }
        return false
    }

    // -----------------------------------------------------------------
    // Full sync — transitions (session start/end, temp-unlock clear,
    // emergency end) + the EnforcementService 30 s sweep.
    // -----------------------------------------------------------------

    fun sync(context: Context) {
        val session = armedSession(context)
        if (session == null) {
            hideAll(context)
            return
        }
        if (isWallShowing()) return // wall covers everything already
        val top = latestResumedPackage(context)
        handler.post {
            if (serviceRef == null || isWallShowing()) return@post
            when {
                top != null && top in session.allowedPackages ->
                    showStripsInternal(context)
                top != null && isInputMethod(context, top) -> showStripsInternal(context)
                else -> showWallInternal(context, session, top ?: "")
            }
        }
    }

    fun syncAsync(context: Context) {
        scope.launch { sync(context) }
    }

    // -----------------------------------------------------------------
    // MainActivity lifecycle hooks (strips while our app is foreground).
    // -----------------------------------------------------------------

    fun onOwnAppResumed(context: Context) {
        val s = armedSession(context)
        if (s != null) {
            handler.post { if (serviceRef != null) showWallInternal(context, s, context.packageName) }
        }
    }

    fun onOwnAppPaused(context: Context) {
        // Something else takes the foreground; the next a11y event (or the
        // sweep) decides wall vs strips. Nothing to do eagerly.
    }

    // -----------------------------------------------------------------
    // Emergency stand-down (called by EmergencyLockdown.start).
    // -----------------------------------------------------------------

    fun hideAll(context: Context) {
        if (!isWallShowing() && !isStripShowing() && ticker == null) return
        handler.post { removeAllInternal() }
    }

    // -----------------------------------------------------------------
    // Internals — wall construction
    // -----------------------------------------------------------------

    private fun showWallInternal(
        context: Context,
        session: SessionSnapshot,
        foregroundPkg: String,
    ) {
        val service = serviceRef ?: return
        if (wall != null) return // already covering

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density
        val pad = (density * 24).toInt()

        wallEndElapsed = session.endElapsed
        wallMode = session.mode
        wallSubject = session.subjectName
        wallTotalSeconds = session.totalSeconds()

        val kiosk = NativeLockKioskView(
            service = service,
            mode = session.mode,
            subjectName = session.subjectName,
            totalDurationSeconds = session.totalSeconds(),
            endElapsed = session.endElapsed,
            allowedPackages = session.allowedPackages,
            onOpenApp = { openOwnApp(service) },
            onLaunchAllowed = { pkg -> launchAllowed(service, pkg) },
            onWatchAd = {
                try {
                    val app = service.applicationContext as? MldApp ?: MldApp.instance
                    val txId = "ad_lock_" + System.currentTimeMillis()
                    scope.launch {
                        app.coinLedger.awardAd(txId)
                        handler.post {
                            nativeKioskView?.updateTick(
                                ((wallEndElapsed - SystemClockNow.elapsed) / 1000L).coerceAtLeast(0L).toInt(),
                                wallTotalSeconds
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        )
        nativeKioskView = kiosk
        val root = kiosk
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
            // NOT_FOCUSABLE: consumes every touch inside its bounds
            // (full screen, status + nav bar regions included) but never
            // takes key focus — keys are consumed by the service filter.
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
            wall = root
            DiagLog.log("KIOSK_WALL", "mode=${session.mode} fg=$foregroundPkg")
            startTicker(service, null)
        } catch (e: Exception) {
            DiagLog.logError("SessionKiosk.showWall", e)
        }
    }

    // -----------------------------------------------------------------
    // Internals — strip construction
    // -----------------------------------------------------------------

    private fun showStripsInternal(context: Context) {
        val service = serviceRef ?: return

        // If the full wall is up it already covers the strips' job.
        if (wall != null) return

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density

        val statusBar = maxOf(dimension(service, "status_bar_height", (36 * density).toInt()), (36 * density).toInt())
        val navBar = maxOf(dimension(service, "navigation_bar_height", (48 * density).toInt()), (54 * density).toInt())

        if (topStrip == null) {
            try {
                val v = View(service).apply {
                    setBackgroundColor(Color.parseColor(STRIP_BG))
                    isClickable = true
                    isFocusable = false
                    setOnTouchListener { _, _ -> true }
                }
                wm.addView(v, stripParams(statusBar, Gravity.TOP))
                topStrip = v
            } catch (e: Exception) {
                DiagLog.logError("SessionKiosk.topStrip", e)
            }
        }
        if (bottomStrip == null) {
            try {
                val v = View(service).apply {
                    setBackgroundColor(Color.parseColor(STRIP_BG))
                    isClickable = true
                    isFocusable = false
                    setOnTouchListener { _, _ -> true }
                }
                wm.addView(v, stripParams(navBar, Gravity.BOTTOM))
                bottomStrip = v
            } catch (e: Exception) {
                DiagLog.logError("SessionKiosk.bottomStrip", e)
            }
        }
        if ((topStrip != null || bottomStrip != null) && ticker == null) {
            startTicker(service, null)
        }
    }

    private fun stripParams(height: Int, gravity: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        p.gravity = gravity or Gravity.CENTER_HORIZONTAL
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun dimension(context: Context, name: String, fallback: Int): Int = try {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
    } catch (_: Exception) {
        fallback
    }

    // -----------------------------------------------------------------
    // Ticker — countdown paint + disarm self-healing (1 s).
    // -----------------------------------------------------------------

    private fun startTicker(service: AccessibilityService, timerView: TextView?) {
        ticker?.let { handler.removeCallbacks(it) }
        var tick = 0L
        val t = object : Runnable {
            override fun run() {
                if (wall == null && topStrip == null && bottomStrip == null) return
                tick++

                // Countdown paint (wall only).
                if (wall != null) {
                    val remain = ((wallEndElapsed - SystemClockNow.elapsed) / 1000L)
                        .coerceAtLeast(0L).toInt()
                    nativeKioskView?.updateTick(remain, wallTotalSeconds)
                    if (timerView != null) {
                        val h = remain / 3600
                        val m = (remain % 3600) / 60
                        val s = remain % 60
                        timerView.text = if (h > 0)
                            "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
                    }
                }

                // Disarm self-healing: re-read the armed state every
                // ~3 s (session end, pause, temp unlock, emergency).
                if (tick % 3 == 0L) {
                    val armed = armedSession(service) != null
                    if (!armed) {
                        removeAllInternal()
                        DiagLog.log("KIOSK", "disarmed — surfaces removed")
                        return
                    }
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        ticker = t
        handler.post(t)
    }

    private fun removeAllInternal() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        nativeKioskView = null
        val svc = serviceRef
        val views = listOfNotNull(wall, topStrip, bottomStrip)
        wall = null
        topStrip = null
        bottomStrip = null
        if (svc == null || views.isEmpty()) return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for (v in views) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
                // Already removed by the system (service death etc.)
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

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
        ).apply { topMargin = (service.resources.displayMetrics.density * 16).toInt() }
        setOnClickListener { onClick() }
    }

    /** Allowlist launchers for the STUDY wall (installed + launchable). */
    private fun shortcutsFor(
        service: AccessibilityService,
        session: SessionSnapshot,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val pm = service.packageManager
        for (pkg in session.allowedPackages.sorted()) {
            if (out.size >= MAX_SHORTCUTS) break
            if (pkg == service.packageName) continue
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                continue
            }
            out.add(pkg to label)
        }
        return out
    }

    private fun launchAllowed(service: AccessibilityService, pkg: String) {
        try {
            val launch = service.packageManager.getLaunchIntentForPackage(pkg) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launch)
            // The allowed app takes the screen; strips cover the shade +
            // gesture areas on top of it.
            handler.post {
                removeWallOnly()
                if (serviceRef != null) showStripsInternal(service)
            }
        } catch (_: Exception) {
        }
    }

    private fun openOwnApp(service: AccessibilityService) {
        try {
            val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(service.packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            service.startActivity(intent)
            // MainActivity.onResume flips to strips instantly; make sure
            // the wall is gone regardless.
            handler.post {
                removeWallOnly()
                if (serviceRef != null) showStripsInternal(service)
            }
        } catch (_: Exception) {
        }
    }

    private fun removeWallOnly() {
        val v = wall
        wall = null
        val svc = serviceRef ?: return
        if (v == null) return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    /** Enabled input methods are never kiosk surfaces. */
    private fun isInputMethod(context: Context, pkg: String): Boolean = try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.enabledInputMethodList.any { it.packageName == pkg }
    } catch (_: Exception) {
        false
    }

    /** Latest ACTIVITY_RESUMED package in the last 12 s (UsageStats). */
    private fun latestResumedPackage(context: Context): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 12_000L, now)
        var latest: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = e.packageName
            }
        }
        latest
    } catch (_: Exception) {
        null
    }

    /** One LOW violation per package per 30 s — the escape attempt shows
     *  up in Insights without flooding the DataStore write path. */
    private fun recordEscapeAttempt(
        context: Context,
        session: SessionSnapshot,
        pkg: String,
    ) {
        nativeKioskView?.recordViolation()
        val now = SystemClockNow.elapsed
        synchronized(lastViolationAt) {
            val last = lastViolationAt[pkg] ?: 0L
            if (now - last < VIOLATION_THROTTLE_MS) return
            lastViolationAt[pkg] = now
        }
        val app = context.applicationContext as? MldApp ?: return
        try {
            app.violationManager.record(
                sessionId = session.id,
                pkg = pkg,
                type = ViolationType.BLOCKED_APP,
                severity = "LOW",
                warningNumber = 0,
                action = "kiosk_wall",
            )
        } catch (_: Exception) {
        }
    }
}
