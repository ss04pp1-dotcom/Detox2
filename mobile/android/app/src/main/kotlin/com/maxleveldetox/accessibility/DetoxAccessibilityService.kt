package com.maxleveldetox.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.AppLimitEngine
import com.maxleveldetox.enforcement.LockController
import com.maxleveldetox.enforcement.PolicyDecision
import com.maxleveldetox.enforcement.ScheduleEngine
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.ViolationType
import com.maxleveldetox.guard.UninstallInterceptor
import com.maxleveldetox.monitor.ForegroundAppMonitorService
import com.maxleveldetox.overlay.A11yOverlayController
import android.view.KeyEvent
import com.maxleveldetox.overlay.EnforcementWall
import com.maxleveldetox.reels.ReelsDetector
import com.maxleveldetox.reels.ReelsEscalationManager
import com.maxleveldetox.reels.ReelsOverlayActivity
import com.maxleveldetox.reels.ReelsRedirect
import com.maxleveldetox.safety.EmergencyLockdown
import com.maxleveldetox.safety.SafetyPauseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * DetoxAccessibilityService (TRD §12–13) — the enforcement backbone.
 *
 * Event pipeline (throttled per package):
 *   TYPE_WINDOW_STATE_CHANGED -> foreground package
 *     -> PolicyEngine.evaluate
 *       BLOCK/CAGE_BLOCK -> LockController + GLOBAL_ACTION_HOME + violation
 *   TYPE_WINDOW_CONTENT_CHANGED (target apps only)
 *     -> ReelsDetector (view-id signatures) -> warning escalation / cage
 *
 * PRIVACY (TRD §115): node text is scanned in memory for shorts signals and
 * immediately discarded. Nothing is persisted beyond (package, signal,
 * timestamp).
 */
class DetoxAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastHandled = HashMap<String, Long>()

    /** v2.0 Phase B1: per-app signature detector + ladder debounce. */
    private val reelsDetector = ReelsDetector()
    private var lastReelsSignal = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagLog.log("A11Y_CONNECTED", "service online, events wired")
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = EVENT_TIMEOUT_MS
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                // v2.5 r9: key filtering — lets us consume BACK (and
                // APP_SWITCH) while the enforcement wall is up (Social
                // Sentry mechanism #23).
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        // v2.5 r9: bind the primary blocking surface (TYPE_ACCESSIBILITY_
        // OVERLAY wall — floats above the status/nav bars, so home
        // gestures and shade swipes physically cannot reach SystemUI).
        EnforcementWall.bind(this)

        // v2.9 r17: bind the SESSION KIOSK (total lockdown for Study/Detox —
        // full-screen wall over the launcher + strips over our own / allowed
        // apps; replaces the removed screen-pinning approach).
        com.maxleveldetox.overlay.SessionKiosk.bind(this)

        // ENGINE HANDOFF (Phase A1): engine 1 is alive — if engine 2 was
        // covering a blackout, it will observe the fresh heartbeat and
        // yield on its own (ForegroundAppMonitorService.shouldYield).
        (application as? MldApp)?.engineState?.writeEngine1Heartbeat()

        // If we recovered after a blackout, count a clean health point so
        // guards do not stay alarmed (guards re-check via job anyway).
        (application as? MldApp)?.let { app ->
            if (app.engineState.isEngine2Running()) {
                // engine 2 self-standsdown after its 30s health grace —
                // nothing else needed here.
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // v2.3 r7: collapse the notification shade while enforcement is
        // live (ShadeGuard carries its own safety bounds — enforcement-only,
        // never during a settings grace window).
        if (com.maxleveldetox.guard.ShadeGuard.maybeCollapse(this, event)) return

        // HEARTBEAT (Phase A1): prove engine-1 liveness on every event
        // batch (throttled — the write itself is a cheap SP apply).
        val wallNow = System.currentTimeMillis()
        if (wallNow - lastHeartbeatWrite > HEARTBEAT_THROTTLE_MS) {
            lastHeartbeatWrite = wallNow
            (application as? MldApp)?.engineState?.writeEngine1Heartbeat(wallNow)
        }

        // UNINSTALL INTERCEPTION (Phase A3): settings/installer screens
        // are scraped for uninstall attempts during enforcing sessions.
        if (UninstallInterceptor.maybeIntercept(this, event)) return

        // v2.5.5 audit fix M-2: the documented Prime-mode Private-DNS tamper
        // guard was dead code — never wired into the event pipeline. It
        // carries its own bounds (settings-only + Prime-active + backoff).
        if (com.maxleveldetox.guard.DnsTamperDetector.maybeIntercept(this, event)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleForeground(pkg)
                // v2.9 r16: window-state carries the ACTIVITY class name —
                // the cheapest, most drift-resistant reels signal (YouTube
                // Shorts / FB Reels players are dedicated activities). Also
                // arms the scheduled re-scans below.
                maybeActivityReels(pkg, event)
                scheduleReelsRescans(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContent(pkg, event)
        }
    }

    // -----------------------------------------------------------------
    // Foreground package enforcement
    // -----------------------------------------------------------------

    private fun handleForeground(pkg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastHandled) {
            val last = lastHandled["fg:$pkg"] ?: 0L
            if (now - last < FG_THROTTLE_MS) return
            lastHandled["fg:$pkg"] = now
        }

        val app = application as? MldApp ?: return

        // ----------------------------------------------------------------
        // CAGE LOCKOUT ACTIVE: if cage is running, ONLY dialer is allowed.
        // If user is not in dialer, reassert the cage screen immediately!
        // ----------------------------------------------------------------
        if (EnforcementWall.isCageActive()) {
            if (EmergencyLockdown.isDialer(this, pkg)) {
                // Allowed in dialer for emergency
                return
            }
            DiagLog.log("CAGE_ACTIVE", "Non-dialer $pkg blocked while cage active")
            performGlobalAction(GLOBAL_ACTION_HOME)
            EnforcementWall.reassertCage(this)
            return
        }

        // ----------------------------------------------------------------
        // v2.7 r13 — EMERGENCY LOCKDOWN (user-requested semantics): while
        // the emergency surface is live the phone is a DIALER AND NOTHING
        // ELSE. Every other app is bounced straight back to the dialer;
        // our own app stays reachable so the user can end emergency
        // deliberately. Runs before every other surface so nothing else
        // can widen the lockdown.
        // ----------------------------------------------------------------
        if (pkg != packageName && EmergencyLockdown.isActive(this) &&
            !EmergencyLockdown.isAllowed(this, pkg)) {
            DiagLog.log("EMERGENCY_LOCKDOWN", "$pkg bounced to dialer")
            EmergencyLockdown.openDialer(this)
            try {
                android.widget.Toast.makeText(
                    this, "Emergency mode — dialer only.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (_: Exception) {
            }
            // v2.9 r16: the bounce loop can fire several times a second —
            // record at most one violation per app per throttle window so
            // the DataStore write path is not hammered.
            val recNow = android.os.SystemClock.elapsedRealtime()
            synchronized(lastHandled) {
                val lastRec = lastHandled["emrec:$pkg"] ?: 0L
                if (recNow - lastRec >= EMERGENCY_RECORD_THROTTLE_MS) {
                    lastHandled["emrec:$pkg"] = recNow
                    app.violationManager.record(
                        sessionId = "emergency",
                        pkg = pkg,
                        type = ViolationType.BLOCKED_APP,
                        severity = "LOW",
                        warningNumber = 0,
                        action = "emergency_lockdown",
                    )
                }
            }
            return
        }

        // ----------------------------------------------------------------
        // v2.3 r7 — ALWAYS-ON surfaces (no session required, Social
        // Sentry parity): scheduled blocking + per-app daily limits.
        // Hard boundaries (emergency / system / ourselves) come first so
        // these walls can never cover the emergency surface.
        // ----------------------------------------------------------------
        if (!app.policyEngine.isEmergency(pkg) &&
            !app.policyEngine.isSystemEssential(pkg) && pkg != packageName) {
            enforceAlwaysOnSurfaces(app, pkg)
        }

        // ----------------------------------------------------------------
        // Notification shade / quick settings suppression across ALL modes:
        // When any mode is active, pulling down shade is immediately dismissed.
        // ----------------------------------------------------------------
        if (pkg.contains("systemui", ignoreCase = true) && isAnyModeActive() && !EmergencyLockdown.isActive(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            DiagLog.log("SHADE_BLOCKED", "Notification panel dismissed during active mode")
            return
        }

        val decision = app.policyEngine.evaluate(pkg)
        DiagLog.log("FG_EVENT", "$pkg -> $decision")

        // ----------------------------------------------------------------
        // v2.9 r17 — SESSION KIOSK (user-requested total lockdown): while
        // a STUDY/DETOX session is ACTIVE every surface that is not our
        // own app, a session-allowed study app, an input method, the
        // dialer family or a real block decision gets the full-screen
        // kiosk WALL — the launcher, Settings, unknown apps and the
        // SystemUI shade/recents surfaces stop existing for the user.
        // Home gestures, shade pulls and recents swipes land on OUR
        // overlay and are consumed; BACK/APP_SWITCH/HOME keys are
        // consumed in onKeyEvent. Returns true when walled (handled).
        // ----------------------------------------------------------------
        if (com.maxleveldetox.overlay.SessionKiosk.onForegroundEvent(
                this, pkg, decision)
        ) {
            return
        }

        when (decision) {
            PolicyDecision.BLOCK, PolicyDecision.CAGE_BLOCK, PolicyDecision.MONK_BLOCK -> {
                val session = app.stateRepo.blockingSession()

                // v2.3 r7 START GRACE (Social Sentry Force-Mode grace): the
                // first seconds after activation let the foreground settle
                // so activation never self-blocks the user mid-tap.
                if (session != null && decision != PolicyDecision.CAGE_BLOCK &&
                    SystemClockNow.elapsed - session.startElapsed < START_GRACE_MS
                ) {
                    DiagLog.log("GRACE_SKIP", "$pkg within start grace")
                    return
                }

                // 1) Move the restricted app out of the foreground.
                DiagLog.log("BLOCKED", "$pkg decision=$decision")
                performGlobalAction(GLOBAL_ACTION_HOME)

                // v2.3 r7 escalation: repeated attempts get the HARD lock
                // surface (countdown-gated dismissal). Computed before the
                // overlay branch so the violation severity sees it too.
                val hard = escalate(pkg)
                // v2.5 r9.5 (Social Sentry E26): a hard block also flushes the
                // app's background process so it cannot resume behind the wall.
                if (hard) flushFromRecents(pkg)
                // 2) v2.5 r9 — PRIMARY surface: the a11y overlay wall
                //    (TYPE_ACCESSIBILITY_OVERLAY, above status+nav bars —
                //    home/shade are physically blocked, Social Sentry
                //    parity). Fallback: the activity wall (LockController)
                //    if overlay stacking fails on this OEM build.
                val wallMsg = if (decision == PolicyDecision.MONK_BLOCK)
                    "Monk Mode is active. Only your allowlisted apps are reachable."
                else "This app is blocked during the active session."
                val wallShown = if (EnforcementWall.isBound()) {
                    if (hard) EnforcementWall.showHard(this, pkg, wallMsg)
                    else EnforcementWall.showBlocked(this, pkg, wallMsg)
                    EnforcementWall.isShowing()
                } else false
                if (!wallShown) {
                    if (Settings.canDrawOverlays(this)) {
                        if (hard) LockController.blockHard(this, pkg)
                        else LockController.block(this, pkg)
                    } else {
                        A11yOverlayController.showBlocked(this, pkg, wallMsg)
                    }
                }
                // 3) Record the violation (audit + escalation display).
                app.violationManager.record(
                    sessionId = app.stateRepo.blockingSession()?.id ?: "none",
                    pkg = pkg,
                    type = if (decision == PolicyDecision.CAGE_BLOCK) ViolationType.SESSION_TAMPER else ViolationType.BLOCKED_APP,
                    severity = if (decision == PolicyDecision.CAGE_BLOCK || hard) "HIGH" else "LOW",
                    warningNumber = 0,
                    action = "blocked",
                )
            }
            PolicyDecision.ALLOW, PolicyDecision.SYSTEM_ALLOW,
            PolicyDecision.EMERGENCY_ALLOW, PolicyDecision.TEMP_ALLOW -> {
                // Permitted — drop any stale blocking overlay (Phase A6)
                // and reconcile the enforcement wall (v2.5 r9).
                A11yOverlayController.reconcile(this, pkg)
                EnforcementWall.reconcile(this, pkg)

                // SAFETY PAUSE (Phase B4): a chosen moment of friction
                // before configured apps — outside any block decision.
                if (decision == PolicyDecision.ALLOW &&
                    app.safetyPause.shouldPause(pkg)) {
                    app.safetyPause.recordShown(pkg)
                    try {
                        startActivity(SafetyPauseActivity.intentFor(this, pkg))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // v2.3 r7 — always-on surfaces + blocked-app escalation
    // -----------------------------------------------------------------

    /** Scheduled blocking + app-limit enforcement, independent of any
     *  session. Each wall is throttled by LockController itself. */
    private fun enforceAlwaysOnSurfaces(app: MldApp, pkg: String) {
        try {
            if (ScheduleEngine.blocksNow(this, pkg)) {
                DiagLog.log("SCHEDULE_BLOCK", "$pkg")
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockController.blockWithMessage(
                    this, pkg,
                    "Blocked by your active schedule.",
                )
                app.violationManager.record(
                    sessionId = "schedule",
                    pkg = pkg,
                    type = ViolationType.BLOCKED_APP,
                    severity = "LOW",
                    warningNumber = 0,
                    action = "schedule_block",
                )
                return
            }
        } catch (_: Exception) {
        }
        try {
            val exceeded = AppLimitEngine.exceededLimit(this, pkg)
            if (exceeded != null) {
                DiagLog.log(
                    "APP_LIMIT_BLOCK",
                    "$pkg limit=${exceeded.dailyLimitMinutes}m",
                )
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockController.blockWithMessage(
                    this, pkg,
                    "Daily limit reached (${exceeded.dailyLimitMinutes} min). " +
                        "It resets at midnight — or unlock with coins.",
                )
                app.violationManager.record(
                    sessionId = "app_limit",
                    pkg = pkg,
                    type = ViolationType.BLOCKED_APP,
                    severity = "LOW",
                    warningNumber = 0,
                    action = "app_limit_block",
                )
            }
        } catch (_: Exception) {
        }
    }

    /** Per-package blocked-attempt counter (60s reset window). 3rd+
     *  attempt inside the window escalates to the HARD lock surface. */
    private val blockCounts = HashMap<String, Int>()
    private val blockCountAt = HashMap<String, Long>()

    private fun escalate(pkg: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(blockCounts) {
            val last = blockCountAt[pkg] ?: 0L
            if (now - last > BLOCK_COUNT_RESET_MS) blockCounts[pkg] = 0
            blockCountAt[pkg] = now
            val n = (blockCounts[pkg] ?: 0) + 1
            blockCounts[pkg] = n
            return n >= HARD_AFTER_ATTEMPTS
        }
    }

    // -----------------------------------------------------------------
    // Shorts / Reels detection within target apps
    // -----------------------------------------------------------------

    private val ShortsState_DEFAULT_PLATFORMS_KEYS: Set<String> =
        com.maxleveldetox.enforcement.ShortsState.DEFAULT_PLATFORMS.keys

    private var lastShortsSignal = 0L

    private fun handleContent(pkg: String, event: AccessibilityEvent) {
        // v2.5 r9.1 PERF: content-change events fire for EVERY app on every
        // redraw. Bail out on the (cheap) package check first, and throttle
        // scans per package, BEFORE touching persisted state (DataStore +
        // JSON parse) or walking any node tree. Both detection paths below
        // only ever act on ReelsDetector.SUPPORTED_PACKAGES.
        if (pkg !in ReelsDetector.SUPPORTED_PACKAGES) return
        val scanNow = android.os.SystemClock.elapsedRealtime()
        synchronized(lastHandled) {
            val lastScan = lastHandled["ct:$pkg"] ?: 0L
            if (scanNow - lastScan < CONTENT_SCAN_THROTTLE_MS) return
            lastHandled["ct:$pkg"] = scanNow
        }

        val app = application as? MldApp ?: return
        val shorts = app.stateRepo.blockingShorts()
        if (!shorts.enabled) return

        val session = app.stateRepo.blockingSession()
        val sessionEnforcing = session != null && session.status.isEnforcing
        val platformEnabled = pkg in shorts.platforms.filterValues { it }.keys

        // -----------------------------------------------------------------
        // IN-SESSION path: shorts attempts feed the warning ladder that
        // escalates to CAGE (detector unified with the path below in r9.1).
        // -----------------------------------------------------------------
        if (sessionEnforcing && platformEnabled &&
            pkg in ShortsState_DEFAULT_PLATFORMS_KEYS) {
            handleContentSession(app, pkg, event)
            return
        }

        // -----------------------------------------------------------------
        // OUT-OF-SESSION path (v2.0 Phase B1/B2): the per-app signature
        // detector + escalation ladder run whenever the shorts blocker
        // is on — no session required.
        // -----------------------------------------------------------------
        if (!platformEnabled || pkg !in ReelsDetector.SUPPORTED_PACKAGES) return

        val manager = app.reelsEscalation
        if (manager.isUnblocked(pkg)) return // paid window open — stand down
        if (app.breakPasses.isBreakActive(pkg)) return // weekly break pass — planned pause

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReelsSignal < REELS_DEBOUNCE_MS) return

        val detection = reelsDetector.detect(pkg, event, activeRootFor(pkg))
            ?: return

        lastReelsSignal = now
        interceptReelsDetected(app, pkg, detection.strategy, manager)
    }

    /**
     * v2.9 r16 — WINDOW_STATE_CHANGED reels check. The activity class name
     * (e.g. com.google.android.youtube.shorts.ShortsActivity) is matched
     * against the platform's activityHints — instant, no tree walk, and
     * immune to view-id drift. Falls back to a full detect() when the
     * class name carries no hints (cheap — resolve() already ran).
     */
    private fun maybeActivityReels(pkg: String, event: AccessibilityEvent) {
        if (pkg !in ReelsDetector.SUPPORTED_PACKAGES) return
        val app = application as? MldApp ?: return
        val shorts = try {
            app.stateRepo.blockingShorts()
        } catch (_: Exception) {
            return
        }
        if (!shorts.enabled) return
        if (pkg !in shorts.platforms.filterValues { it }.keys) return
        val manager = app.reelsEscalation
        if (manager.isUnblocked(pkg)) return
        if (app.breakPasses.isBreakActive(pkg)) return

        val detection = reelsDetector.detectActivity(pkg, event.className?.toString())
            ?: return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReelsSignal < REELS_DEBOUNCE_MS) return
        lastReelsSignal = now

        DiagLog.log("REELS_ACTIVITY", "$pkg ${event.className} -> ${detection.strategy}")
        val session = try {
            app.stateRepo.blockingSession()
        } catch (_: Exception) {
            null
        }
        val sessionEnforcing = session != null && session.status.isEnforcing
        if (sessionEnforcing && pkg in ShortsState_DEFAULT_PLATFORMS_KEYS) {
            // In-session ladder — same shape as handleContentSession.
            if (!ReelsRedirect.navigateFromService(this, pkg)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            app.violationManager.shortsAttempt(
                pkg = pkg,
                warningLimit = app.runtimeConfig.current().shortsWarningCount,
                onWarning = { count, limit ->
                    LockController.warning(this, count, limit)
                },
                onCage = {
                    scope.launch { app.sessionEngine.activateCage() }
                    LockController.cage(this)
                },
            )
        } else {
            interceptReelsDetected(app, pkg, detection.strategy, manager)
        }
    }

    /**
     * v2.9 r16 — scheduled re-scans after entering a monitored app. The
     * shorts/reels UI inflates AFTER the window-state event; content-change
     * events can be throttled or batched by the system. Three delayed
     * scans (+500/+1500/+3000 ms) close the gap — and they double as the
     * closed-loop verification for ReelsRedirect (a redirect that did not
     * stick re-detects and advances the redirect ladder's next rung).
     */
    private fun scheduleReelsRescans(pkg: String) {
        if (pkg !in ReelsDetector.SUPPORTED_PACKAGES) return
        for (delay in RESCAN_DELAYS_MS) {
            scope.launch {
                kotlinx.coroutines.delay(delay)
                try {
                    reelsRescan(pkg)
                } catch (_: Exception) {
                    // service may be gone by then — fine
                }
            }
        }
    }

    private fun reelsRescan(pkg: String) {
        if (pkg !in ReelsDetector.SUPPORTED_PACKAGES) return
        val app = application as? MldApp ?: return
        val shorts = try {
            app.stateRepo.blockingShorts()
        } catch (_: Exception) {
            return
        }
        if (!shorts.enabled) return
        if (pkg !in shorts.platforms.filterValues { it }.keys) return
        val manager = app.reelsEscalation
        if (manager.isUnblocked(pkg)) return
        if (app.breakPasses.isBreakActive(pkg)) return

        val now = android.os.SystemClock.elapsedRealtime()
        // The re-scan is the closed-loop verifier: it honours the short
        // debounce (so one redirect is not double-counted instantly) but
        // NOT the longer in-session cooldown — a redirect that did not
        // stick must advance the ladder promptly.
        if (now - lastReelsSignal < REELS_DEBOUNCE_MS) return

        val detection = reelsDetector.detect(pkg, null, activeRootFor(pkg)) ?: return
        lastReelsSignal = now
        DiagLog.log("REELS_RESCAN", "$pkg -> ${detection.strategy}")
        interceptReelsDetected(app, pkg, detection.strategy, manager)
    }

    /** Shared out-of-session interception: redirect ladder + escalation
     *  surface (toast -> soft overlay -> hard lockout). */
    private fun interceptReelsDetected(
        app: MldApp,
        pkg: String,
        strategy: String,
        manager: ReelsEscalationManager,
    ) {
        // v2.7 r13 (user-requested): stay INSIDE the app — navigate to its
        // safe surface (YouTube Home / FB Feed / IG Feed) instead of
        // kicking the user out to the launcher. HOME is the fallback only.
        if (!ReelsRedirect.navigateFromService(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        manager.onDetection(pkg, strategy) { esc ->
            when (esc.step) {
                ReelsEscalationManager.Step.TOAST, ReelsEscalationManager.Step.SOFT -> {
                    try {
                        android.widget.Toast.makeText(
                            this, "Short-form content blocked (${esc.count}/5)",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } catch (_: Exception) {
                    }
                }
                ReelsEscalationManager.Step.HARD -> {
                    // 5 consecutive attempts in 1 min -> 1-minute Cage lock
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    if (EnforcementWall.isBound()) {
                        EnforcementWall.showCage(this, SystemClockNow.elapsed + 60_000L)
                    } else {
                        LockController.cage(this)
                    }
                }
            }
        }
    }

    /** Kill [pkg]'s background process shortly after it was sent HOME. */
    private fun flushFromRecents(pkg: String) {
        scope.launch {
            kotlinx.coroutines.delay(700L)
            try {
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Root of the active window, but ONLY when it belongs to [pkg]. A
     * content-change event can arrive from a package whose window is not the
     * active one (overlay, IME, split-screen); scanning the wrong tree would
     * let the detector's partial-id fallback match another app's views.
     *
     * v2.9 r16: when the active window belongs to another package, fall
     * back to the window list (FLAG_RETRIEVE_INTERACTIVE_WINDOWS is set):
     * shorts/reels sometimes render in a layer that is not the
     * "active" window (player overlays, split top). Without this the
     * scan silently returned null — the classic mid-scroll miss.
     */
    private fun activeRootFor(pkg: String): android.view.accessibility.AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() == pkg) return root
        return try {
            windows.firstOrNull { it.root?.packageName?.toString() == pkg }?.root
        } catch (_: Exception) {
            null
        }
    }

    /** In-session ladder: warnings -> cage (Phase 1 behavior, r9.1 detector). */
    private fun handleContentSession(app: MldApp, pkg: String, event: AccessibilityEvent) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShortsSignal < SHORTS_COOLDOWN_MS) return

        // v2.5 r9.1: the in-session ladder now uses the SAME per-app
        // signature detector (view-ids) as the out-of-session path. The
        // legacy text-hint detector matched ordinary UI labels
        // ("Subscriptions", "Following", "Your story" ...) and walked only
        // 3 levels deep, so it both false-fired on normal browsing (each
        // hit counts toward the 30-minute Cage) and missed real feeds.
        val detected = reelsDetector.detect(pkg, event, activeRootFor(pkg)) != null
        if (!detected) return

        lastShortsSignal = now

        // v2.7 r13: in-session shorts interception now also stays inside
        // the app (safe surface) — same redirect as the out-of-session
        // path. HOME is the fallback only.
        if (!ReelsRedirect.navigateFromService(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        app.violationManager.shortsAttempt(
            pkg = pkg,
            warningLimit = app.runtimeConfig.current().shortsWarningCount,
            onWarning = { count, limit ->
                LockController.warning(this, count, limit)
            },
            onCage = {
                scope.launch { app.sessionEngine.activateCage() }
                LockController.cage(this)
            },
        )
    }

    override fun onInterrupt() {
        // Service interrupted — persisted state + recovery handle continuity.
    }

    // -----------------------------------------------------------------
    // v2.5 r9 — BACK / APP_SWITCH consumption while the enforcement wall
    // is showing (Social Sentry mechanism #23: key filtering scoped to
    // the safety overlay only; every other key falls through).
    // -----------------------------------------------------------------

    private fun isAnyModeActive(): Boolean {
        if (EnforcementWall.isShowing() || EnforcementWall.isCageActive()) return true
        if (com.maxleveldetox.overlay.SessionKiosk.isWallShowing() ||
            com.maxleveldetox.overlay.SessionKiosk.isStripShowing()
        ) return true
        val app = application as? MldApp
        val session = try { app?.stateRepo?.blockingSession() } catch (_: Exception) { null }
        if (session != null && session.status.isEnforcing) return true
        if (com.maxleveldetox.monk.MonkModeManager.isActive(this)) return true
        if (com.maxleveldetox.lock.LockMyPhoneService.isAlive) return true
        return false
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        // Emergency dialer must always stay interactive
        if (EmergencyLockdown.isActive(this)) return false

        // While ANY mode is active (Study, Detox, Monk, Lock My Phone, Cage, etc.),
        // Home, Back, Recents hardware and navigation keys are completely blocked
        // (like the Cage), without changing the original UI of the mode.
        if (!isAnyModeActive()) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ALL_APPS,
            -> true
            else -> false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // ENGINE HANDOFF (Phase A1): accessibility is going away — either
        // the user disabled it (PermissionMonitor drives RECOVERY) or the
        // system is killing us. In both cases engine 2 must take over so
        // enforcement does not silently vanish (Phase A1/A2 policy).
        (application as? MldApp)?.let { app ->
            val session = app.stateRepo.blockingSession()
            if ((session != null && session.status.isEnforcing) ||
                com.maxleveldetox.enforcement.AlwaysOnRules.exist(this)
            ) {
                try {
                    ForegroundAppMonitorService.start(this)
                } catch (_: Exception) {
                }
            }
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Last-gasp handoff: same policy as onUnbind (Phase A1).
        (application as? MldApp)?.let { app ->
            val session = app.stateRepo.blockingSession()
            if ((session != null && session.status.isEnforcing) ||
                com.maxleveldetox.enforcement.AlwaysOnRules.exist(this)
            ) {
                try {
                    ForegroundAppMonitorService.start(this)
                } catch (_: Exception) {
                }
            }
        }
        // v2.5 r9: the wall lives in our window — release it (the system
        // removes a11y overlay windows when the service dies anyway).
        EnforcementWall.unbind()
        com.maxleveldetox.overlay.SessionKiosk.unbind()
        A11yOverlayController.hide(this)
        scope.cancel()
        super.onDestroy()
    }

    private var lastHeartbeatWrite = 0L

    companion object {
        private const val FG_THROTTLE_MS = 800L
        private const val SHORTS_COOLDOWN_MS = 6_000L
        private const val REELS_DEBOUNCE_MS = 500L

        /** v2.9 r16: delayed re-scan schedule after entering a monitored
         *  app — covers late-inflating shorts UI and verifies redirects. */
        private val RESCAN_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)

        /** v2.9 r16: emergency-bounce violation recording throttle (the
         *  bounce loop can fire many times a second — one record per app
         *  per window is plenty). */
        private const val EMERGENCY_RECORD_THROTTLE_MS = 5_000L

        /** v2.5 r9.1: min gap between content scans of the same package. */
        private const val CONTENT_SCAN_THROTTLE_MS = 300L
        private const val EVENT_TIMEOUT_MS = 200L
        private const val HEARTBEAT_THROTTLE_MS = 20_000L

        /** v2.3 r7: activation grace (Social Sentry force-mode grace). */
        private const val START_GRACE_MS = 5_000L

        /** v2.3 r7: blocked-app escalation window + threshold. */
        private const val BLOCK_COUNT_RESET_MS = 60_000L
        private const val HARD_AFTER_ATTEMPTS = 3
    }
}
