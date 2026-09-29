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
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.ViolationType
import com.maxleveldetox.guard.UninstallInterceptor
import com.maxleveldetox.monitor.ForegroundAppMonitorService
import com.maxleveldetox.overlay.A11yOverlayController
import android.view.KeyEvent
import com.maxleveldetox.overlay.EnforcementWall
import com.maxleveldetox.reels.ReelsDetector
import com.maxleveldetox.reels.ReelsEscalationManager
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
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = EVENT_TIMEOUT_MS
            // v2.5 r9 + v2.9.11 r27: FLAG_REQUEST_FILTER_KEY_EVENTS is now
            // applied DYNAMICALLY by syncKeyFilterFlag() — armed ONLY while
            // a mode is enforcing. Keeping it always-on made the system
            // round-trip EVERY hardware key on the device through this
            // service even at idle, and OEM input pipelines duplicate or
            // replay delayed volume events — one press moved the volume
            // several steps (the recurring "ekbar chap dile koyekta chap"
            // bug). With the flag off at idle, idle volume keys never
            // touch this service at all.
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // Arm or disarm the key filter to match the CURRENT enforcement
        // state (a session may already be running when the service
        // (re)connects — recovery path).
        syncKeyFilterFlag()

        // v2.5 r9: bind the primary blocking surface (TYPE_ACCESSIBILITY_
        // OVERLAY wall — floats above the status/nav bars, so home
        // gestures and shade swipes physically cannot reach SystemUI).
        EnforcementWall.bind(this)

        // v2.9 r17: bind the SESSION KIOSK (total lockdown for Study/Detox —
        // full-screen wall over the launcher + strips over our own / allowed
        // apps; replaces the removed screen-pinning approach).
        com.maxleveldetox.overlay.SessionKiosk.bind(this)

        // v2.9.3 r19: bind the hard SAFETY PAUSE overlay (cage-hold countdown
        // — same TYPE_ACCESSIBILITY_OVERLAY mechanism as the kiosk wall).
        com.maxleveldetox.overlay.SafetyPauseOverlay.bind(this)

        // v2.9.2 r18: KIOSK ASSERT LOOP — a session started while our own
        // app was already foreground produces no window event, and OEMs
        // occasionally strip overlay windows. This 2 s heartbeat re-arms
        // the strips/wall whenever a session is enforcing with no kiosk
        // surface showing, so the session screen itself is ALWAYS the
        // lock (user-requested: "study mode dhukle je screen ta ashe oitai
        // cage er moto atkaye rakhbe — kichui kaj korbe na").
        startKioskAssertLoop()

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
        if (pkg == packageName) {
            // v2.9.2 r18: our OWN window events keep the SESSION KIOSK
            // asserted — a session started while our app was already in
            // the foreground produces no other event, and the strips are
            // what make home/back/shade dead on the session screen itself.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                try {
                    com.maxleveldetox.overlay.SessionKiosk.onOwnAppResumed(this)
                } catch (_: Exception) {
                }
                // v2.9.12 r28: same moment, cage edition — our app's window
                // came forward, so the cage wall must not sit on top of
                // the functional Flutter cage surface.
                try {
                    if (EnforcementWall.isCageActive()) {
                        EnforcementWall.hideIfCage()
                    }
                } catch (_: Exception) {
                }
            }
            return
        }

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
                handleForeground(pkg, event)
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

    private fun handleForeground(pkg: String, event: AccessibilityEvent) {
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
            // v2.9.9 r25 (user request: cage = active Mood's session
            // screen): OUR OWN APP is allowed during the cage — the
            // Flutter cage surface takes over the whole in-app experience
            // and mirrors the running mood's session screen (icon, title,
            // timer, stats, per-mood controls) with the cage countdown.
            // Kicking the user out of the app here is what made the cage
            // look like a foreign punishment screen. Only the dialer and
            // ourselves are reachable; every other app still gets the
            // wall + HOME below.
            if (pkg == packageName) {
                // v2.9.12 r28 (user report: "case e eishob kisui hoy nai"):
                // the FUNCTIONAL cage surface is IN THE APP (End / Details /
                // Temporary Unlock / Watch Ad, r27). The native cage wall
                // is a TYPE_ACCESSIBILITY_OVERLAY that floats ABOVE
                // MainActivity — if it was re-asserted while the user was
                // away it would keep covering our own cage screen forever.
                // Clear it the moment our app owns the foreground; the
                // cage gate re-asserts it on any exit attempt.
                try {
                    EnforcementWall.hideIfCage()
                } catch (_: Exception) {
                }
                return
            }
            // v2.9.11 r27 (user request: the cage screen is FUNCTIONAL —
            // a paid temporary-unlock window bought INSIDE the cage must
            // actually open its apps): allow the unlock window's packages
            // through the cage gate. Mirrors PolicyEngine's TEMP_ALLOW
            // rule outside the cage; the unlock expires on its own and
            // full cage enforcement resumes automatically.
            try {
                val unlock = app.stateRepo.blockingTempUnlock()
                if (unlock.allows(pkg, SystemClockNow.elapsed)) {
                    return
                }
            } catch (_: Exception) {
            }
            // v2.9.2 r18 (user-requested): leaving the dialer ENDS the
            // emergency and returns the user straight back to the cage.
            if (EmergencyLockdown.isActive(this)) {
                EmergencyLockdown.end(this)
            }
            DiagLog.log("CAGE_ACTIVE", "Non-dialer $pkg blocked while cage active")
            performGlobalAction(GLOBAL_ACTION_HOME)
            EnforcementWall.reassertCage(this)
            return
        }

        // ----------------------------------------------------------------
        // v2.7 r13 — EMERGENCY LOCKDOWN. v2.9.2 r18 (user-requested)
        // semantics change: the dialer bounce loop is GONE. While the
        // emergency is live the dialer family (and our own app, so the
        // banner can end it deliberately) is the only reachable surface;
        // the moment the user LEAVES the dialer for anything else, the
        // emergency ENDS and the normal pipeline below hands them back to
        // the surface they came from (session kiosk wall / cage / monk /
        // lock screen) instead of dragging them to the dialer forever.
        // ----------------------------------------------------------------
        if (EmergencyLockdown.isActive(this) &&
            !EmergencyLockdown.isAllowed(this, pkg)) {
            DiagLog.log("EMERGENCY_LOCKDOWN", "$pkg left dialer -> end + re-assert origin")
            EmergencyLockdown.end(this)
            // v2.9 r16: violation recording stays throttled (the exit can
            // fire several times a second across the transition).
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
                        action = "emergency_lockdown_exit",
                    )
                }
            }
            // FALL THROUGH — the pipeline below re-asserts the origin
            // surface (kiosk wall / block wall / monk / lock-my-phone).
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
        // Notification shade suppression across ALL modes (v2.9.4 r20 fix):
        // a SystemUI window that is NOT explicitly denylisted is treated as
        // the shade and collapsed. r19 and earlier fired on EVERY systemui
        // window-state change — including the VOLUME dialog, recents, the
        // power menu and heads-ups — which injected stray BACK/DISMISS
        // actions on every volume press ("ekbar chap dile koyekta chap
        // niyeche"). The denylist mirrors ShadeGuard's NOT_SHADE_HINTS.
        // On API 31+ the DISMISS action alone is used; the extra BACK was
        // pressed into whatever app was behind and stole real back presses.
        // ----------------------------------------------------------------
        if (pkg.contains("systemui", ignoreCase = true) && isAnyModeActive() && !EmergencyLockdown.isActive(this)) {
            val sysCls = (event.className?.toString() ?: "").lowercase()
            val notShade = listOf(
                "recents", "globalactions", "clipboard", "ime", "inputmethod",
                "volume", "screenshot", "imagewire", "bubbles", "wallet",
                "mediacontrol", "workspaces", "keyguard",
            ).any { sysCls.contains(it) }
            if (!notShade) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                DiagLog.log("SHADE_BLOCKED", "shade collapsed (cls=$sysCls)")
                return
            }
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
                // v2.9.3 r19 (user-requested HARD pause): the surface is
                // the cage-hold OVERLAY first (full-screen a11y window —
                // home/recents/shade/back all dead while it counts down);
                // the legacy activity remains only as an add-failure
                // fallback so a pause can never silently vanish.
                if (decision == PolicyDecision.ALLOW &&
                    app.safetyPause.shouldPause(pkg)) {
                    app.safetyPause.recordShown(pkg)
                    val seconds = try {
                        app.safetyPause.statePauseSeconds()
                    } catch (_: Exception) {
                        5
                    }
                    val shown = try {
                        com.maxleveldetox.overlay.SafetyPauseOverlay.show(this, pkg, seconds)
                    } catch (_: Exception) {
                        false
                    }
                    if (!shown) {
                        try {
                            startActivity(SafetyPauseActivity.intentFor(this, pkg))
                        } catch (_: Exception) {
                        }
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

    /**
     * v2.9.2 r18 — EPISODE TRACKING. One "attempt" is one reels ENTRY,
     * not one detection: while the SAME reels surface is still on screen
     * (the redirect ladder is fighting it), further detections never
     * advance the burst counter — no more cage on a first entry from
     * rescan double-counting. An episode closes on a verified return to
     * the app's feed (verification scan) or a non-reels activity
     * transition; the next detection is then a fresh attempt.
     */
    private val reelsOnSurface = HashMap<String, Boolean>()
    private val reelsLastAt = HashMap<String, Long>()
    private val reelsVerifyAt = HashMap<String, Long>()

    private fun handleContent(pkg: String, event: AccessibilityEvent) {
        // v2.5 r9.1 PERF: content-change events fire for EVERY app on every
        // redraw. Bail out on the (cheap) package check first, and throttle
        // scans per package, BEFORE touching persisted state (DataStore +
        // JSON parse) or walking any node tree.
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
        val platformEnabled = pkg in shorts.platforms.filterValues { it }.keys
        if (!platformEnabled) return

        val manager = app.reelsEscalation
        if (manager.isUnblocked(pkg)) return // paid window open — stand down
        if (app.breakPasses.isBreakActive(pkg)) return // weekly break pass — planned pause

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReelsSignal < REELS_DEBOUNCE_MS) return

        // v2.9.2 r18: content events sometimes carry the activity class
        // name — try the cheapest, most drift-resistant check before any
        // tree walk, then the full multi-signal detect.
        val detection = reelsDetector.detectActivity(pkg, event.className?.toString())
            ?: reelsDetector.detect(pkg, event, activeRootFor(pkg))
            ?: return

        lastReelsSignal = now
        // v2.9.2 r18: UNIFIED in- and out-of-session interception.
        handleReelsDetection(app, pkg, detection.strategy)
    }

    /**
     * v2.9 r16 — WINDOW_STATE_CHANGED reels check. The activity class name
     * (e.g. com.google.android.youtube.shorts.ShortsActivity) is matched
     * against the platform's activityHints — instant, no tree walk, and
     * immune to view-id drift.
     *
     * v2.9.2 r18: a NON-reels activity transition of a monitored package
     * CLOSES the episode — the next detection is a fresh attempt (strong,
     * drift-proof re-entry signal for the burst counter).
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

        if (detection == null) {
            // The visible activity is NOT a reels surface — any open
            // episode is over (the redirect landed / the user left).
            if (reelsOnSurface[pkg] == true) {
                reelsOnSurface[pkg] = false
                DiagLog.log("REELS_EPISODE", "$pkg activity -> ${event.className} (closed)")
            }
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReelsSignal < REELS_DEBOUNCE_MS) return
        lastReelsSignal = now

        DiagLog.log("REELS_ACTIVITY", "$pkg ${event.className} -> ${detection.strategy}")
        handleReelsDetection(app, pkg, detection.strategy)
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
        handleReelsDetection(app, pkg, detection.strategy)
    }

    /**
     * v2.9.2 r18 — UNIFIED reels interception (user-requested semantics,
     * in- and out-of-session alike):
     *
     *   - ALWAYS stay inside the app: redirect to the app's own
     *     feed/home. The user is NEVER kicked out of a feed platform —
     *     when the ladder fails, the closed-loop re-scans keep fighting
     *     INSIDE the app. HOME remains only for platforms with no safe
     *     surface at all (TikTok — the whole app IS the feed; browser
     *     shorts tabs were removed with the URL strategy, r20).
     *
     *   - v2.9.4 r20: the redirect ladder is PER PLATFORM (YouTube /
     *     Instagram: bottom-nav Home tab FIRST, BACK only as the last
     *     rung) and rate-limited (min 2 s between attempts) — the old
     *     BACK-first machine-gun is what exited apps.
     *
     *   - An "attempt" = a fresh reels ENTRY (episode tracking above).
     *     Detections while the same reels surface is still on screen
     *     never advance the counter.
     *
     *   - 5 attempts in one rapid stretch (a gap of ~1 min resets) ->
     *     the 1-minute CAGE: inescapable wall, dialer via Emergency
     *     only, and leaving the dialer returns straight to the cage.
     */
    private fun handleReelsDetection(app: MldApp, pkg: String, strategy: String) {
        val manager = app.reelsEscalation
        val now = android.os.SystemClock.elapsedRealtime()
        val onSurface = reelsOnSurface[pkg] == true
        val stale = now - (reelsLastAt[pkg] ?: 0L) > REELS_EPISODE_STALL_MS
        val isNewAttempt = !onSurface || stale
        reelsLastAt[pkg] = now

        // NEVER kick the user out of a feed platform (user-requested):
        // redirect to the app's own feed/home; on failure keep retrying
        // in-app via the closed loop. HOME only for no-safe-surface apps.
        if (!ReelsRedirect.navigateFromService(this, pkg)) {
            if (pkg in ReelsRedirect.NO_SAFE_SURFACE) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                reelsOnSurface[pkg] = false
            } else {
                scheduleReelsRescans(pkg)
            }
        }

        if (isNewAttempt) {
            reelsOnSurface[pkg] = true
            DiagLog.log("REELS_ATTEMPT", "$pkg fresh attempt via $strategy")

            // In-session audit trail: the config warning ladder surfaces
            // are replaced by the unified burst ladder — recording only.
            val session = try {
                app.stateRepo.blockingSession()
            } catch (_: Exception) {
                null
            }
            if (session != null && session.status.isEnforcing) {
                app.violationManager.shortsAttempt(
                    pkg = pkg,
                    warningLimit = app.runtimeConfig.current().shortsWarningCount,
                    onWarning = { _, _ -> },
                    onCage = { },
                )
            }

            manager.onDetection(pkg, strategy) { esc ->
                if (esc.step == ReelsEscalationManager.Step.HARD) {
                    // 5 rapid attempts in one stretch -> 1-minute cage.
                    // v2.9.4 r20: the cage end is ALWAYS the 60 s burst cage
                    // (the old 30-minute default in LockController.cage was
                    // a zombie-cage source — see SessionEngine.recoverIfNeeded).
                    // v2.9.9 r25 (user request: cage = active Mood's session
                    // screen): the cage is served IN THE APP — bring
                    // MainActivity forward so the Flutter cage surface
                    // (mirroring the running mood's session screen with the
                    // cage countdown) is what the user sees, instead of
                    // dumping them on the launcher behind a wall. The wall
                    // still covers every other app if they leave (the cage
                    // gate reasserts it).
                    val cageEnd = SystemClockNow.elapsed + REELS_CAGE_MS
                    if (EnforcementWall.isBound()) {
                        EnforcementWall.startCage(cageEnd)
                    } else {
                        LockController.cage(this, cageEnd)
                    }
                    // v2.9.9 r25: mirror the 60 s burst cage into the
                    // PERSISTED state — the Flutter cage surface (which
                    // mirrors the active mood's session screen, per the
                    // user request) renders from stateRepo.cage, so
                    // without this the app would never show it. The
                    // 60 s burst end is used, NOT the 30-min config
                    // default (that mismatch was the old zombie-cage
                    // source). Every read treats an expired cage as
                    // inactive and the periodic monitors call
                    // releaseCageIfExpired, so this can never outlive
                    // its minute. Broadcast so the bridge pushes the
                    // cage state to Flutter immediately.
                    scope.launch {
                        try {
                            app.stateRepo.saveCage(
                                com.maxleveldetox.enforcement.CageSnapshot(
                                    true, cageEnd - REELS_CAGE_MS, cageEnd))
                            com.maxleveldetox.enforcement.SessionEngine
                                .Broadcaster.emit()
                        } catch (e: Exception) {
                            DiagLog.logError("burstCagePersist", e)
                        }
                    }
                    launchCageSurface()
                } else {
                    try {
                        android.widget.Toast.makeText(
                            this, "Short-form content blocked (${esc.count}/5)",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            DiagLog.log("REELS_SAME_EPISODE", "$pkg redirect retry via $strategy")
        }

        scheduleEpisodeVerify(pkg)
    }

    /**
     * v2.9.2 r18 — post-redirect verification (~1.2 s later): if the
     * reels surface is gone the episode CLOSES (the next detection is a
     * fresh attempt); if it survived the redirect, fight again — bounded
     * to ~12 s past the last detection. Single-flight per package.
     *
     * v2.9.4 r20 (user-reported kick-out fix): NEVER navigate blind. The
     * old loop re-ran the redirect ladder even after the target app had
     * left the foreground entirely — the BACK rung then fired into the
     * launcher/other apps and, on YouTube, the repeated BACKs summed
     * into the double-back exit that threw the user out of the app. Now
     * the loop only fights while [pkg] still owns a window; the moment
     * the app is gone the episode simply closes (a fresh ENTRY later is
     * a new attempt — the burst counter still catches rapid re-entry).
     */
    private fun scheduleEpisodeVerify(pkg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(reelsVerifyAt) {
            val pending = reelsVerifyAt[pkg] ?: 0L
            if (pending > now) return // one pending verify is enough
            reelsVerifyAt[pkg] = now + REELS_VERIFY_DELAY_MS + 100L
        }
        scope.launch {
            kotlinx.coroutines.delay(REELS_VERIFY_DELAY_MS)
            synchronized(reelsVerifyAt) { reelsVerifyAt[pkg] = 0L }
            try {
                if (pkg !in ReelsDetector.SUPPORTED_PACKAGES) return@launch
                if (application as? MldApp == null) return@launch
                val lastAt = reelsLastAt[pkg] ?: return@launch
                val vNow = android.os.SystemClock.elapsedRealtime()
                if (vNow - lastAt > REELS_EPISODE_VERIFY_MAX_MS) return@launch

                // App already gone from the screen? Nothing to fight for —
                // close the episode and stop (no blind global actions).
                val root = activeRootFor(pkg) ?: run {
                    reelsOnSurface[pkg] = false
                    DiagLog.log("REELS_EPISODE", "$pkg left the screen")
                    return@launch
                }

                val stillThere = try {
                    reelsDetector.detect(pkg, null, root) != null
                } catch (_: Exception) {
                    true // transient read failure — keep the episode open
                }
                if (!stillThere) {
                    reelsOnSurface[pkg] = false
                    DiagLog.log("REELS_EPISODE", "$pkg left the reels surface")
                } else {
                    // The same screen survived the redirect — keep
                    // fighting INSIDE the app (never HOME for feed apps;
                    // navigateFromService rate-limits itself, r20).
                    ReelsRedirect.navigateFromService(
                        this@DetoxAccessibilityService, pkg)
                    scheduleEpisodeVerify(pkg)
                }
            } catch (_: Exception) {
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

    override fun onInterrupt() {
        // Service interrupted — persisted state + recovery handle continuity.
    }

    /**
     * v2.9.9 r25 — bring our own app forward: the cage is served in-app
     * on the mood-mirroring Flutter cage surface (user request: cage =
     * the active mood's session screen, 100% same design). MainActivity
     * is singleTop, so an existing instance is simply brought to the
     * front (onNewIntent) — never duplicated, and the Flutter navigator
     * state (the cage route) is preserved.
     */
    private fun launchCageSurface() {
        try {
            val intent = android.content.Intent(
                this, com.maxleveldetox.MainActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DiagLog.log("CAGE_SURFACE", "app brought forward for in-app cage")
        } catch (e: Exception) {
            // Never a gap: if the launch fails (background-activity
            // restrictions on this OEM etc.) the cage timer is armed and
            // the wall still covers other apps via the cage gate.
            DiagLog.logError("launchCageSurface", e)
        }
    }

    // -----------------------------------------------------------------
    // v2.5 r9 — BACK / APP_SWITCH consumption while the enforcement wall
    // is showing (Social Sentry mechanism #23: key filtering scoped to
    // the safety overlay only; every other key falls through).
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // v2.9.2 r18 — KIOSK ASSERT LOOP
    // -----------------------------------------------------------------

    private var kioskAssertLoop: kotlinx.coroutines.Job? = null

    private fun startKioskAssertLoop() {
        if (kioskAssertLoop?.isActive == true) return
        kioskAssertLoop = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(KIOSK_ASSERT_INTERVAL_MS)
                try {
                    val app = application as? MldApp ?: continue
                    // v2.9.11 r27 — KEY FILTER WATCHDOG: every tick, arm or
                    // disarm FLAG_REQUEST_FILTER_KEY_EVENTS to match the
                    // live enforcement state (instant arming is additionally
                    // triggered at session/cage/monk/lock start; this loop
                    // is the backstop that DISARMS it within 2 s of the last
                    // mode ending — the volume multi-press fix).
                    syncKeyFilterFlag()
                    val session = app.stateRepo.blockingSession()
                    val enforcing = session != null && session.status.isEnforcing
                    if (!enforcing) continue
                    if (EmergencyLockdown.isActive(this@DetoxAccessibilityService)) continue
                    if (EnforcementWall.isCageActive()) continue
                    if (com.maxleveldetox.overlay.SessionKiosk.isWallShowing() ||
                        com.maxleveldetox.overlay.SessionKiosk.isStripShowing()
                    ) continue
                    com.maxleveldetox.overlay.SessionKiosk.sync(this@DetoxAccessibilityService)
                } catch (_: Exception) {
                    // transient state read failure — next tick retries
                }
            }
        }
    }

    private fun isAnyModeActive(): Boolean {
        if (EnforcementWall.isShowing() || EnforcementWall.isCageActive()) return true
        if (com.maxleveldetox.overlay.SessionKiosk.isWallShowing() ||
            com.maxleveldetox.overlay.SessionKiosk.isStripShowing()
        ) return true
        // v2.9.3 r19: the hard safety-pause countdown holds like the cage —
        // while it is on screen the navigation keys are dead.
        if (com.maxleveldetox.overlay.SafetyPauseOverlay.isShowing()) return true
        val app = application as? MldApp
        val session = try { app?.stateRepo?.blockingSession() } catch (_: Exception) { null }
        if (session != null && session.status.isEnforcing) return true
        if (com.maxleveldetox.monk.MonkModeManager.isActive(this)) return true
        if (com.maxleveldetox.lock.LockMyPhoneService.isAlive) return true
        return false
    }

    // -----------------------------------------------------------------
    // v2.9.6 r22 — KEY FILTER LATENCY FIX + v2.9.11 r27 — ROOT FIX.
    //
    // User report (three rounds): with the app installed, ONE volume
    // press moved the volume several steps ("ekbar chap dile onekbar
    // chap hoye sondho barche"). Two layers were already in place:
    //   1. FAST PATH — we only ever CONSUME the five navigation keys;
    //      everything else returns false with ZERO work.
    //   2. TTL CACHE — the nav-key enforcement check runs at most once
    //      per 250 ms instead of per event.
    // The bug STILL came back on the user's OEM, which proves the root
    // cause was never our handler body: with flagRequestFilterKeyEvents
    // ALWAYS on, the system input dispatcher performs a synchronous
    // binder round-trip into this service for EVERY hardware key on the
    // device — even at complete idle — and that round-trip alone is
    // enough for OEM pipelines (MIUI/HyperOS, Transsion) to duplicate or
    // replay volume events.
    //
    // r27 ROOT FIX: the flag itself is now DYNAMIC — armed ONLY while a
    // mode is enforcing (syncKeyFilterFlag). At idle, volume keys (and
    // every other key) never enter this service at all, so one press is
    // one step, guaranteed. While a mode is enforcing, the fast path +
    // TTL cache keep the round-trip as short as it can possibly be, and
    // the small volume-key debounce below additionally consumes
    // OEM-replayed duplicate presses inside its window (one press = one
    // step, nothing else — no behaviour is attached to volume keys).
    // -----------------------------------------------------------------

    /** Cached isAnyModeActive() verdict — see the comment above. */
    @Volatile private var anyModeCache = false
    @Volatile private var anyModeCacheAt = 0L

    private fun isAnyModeActiveCached(): Boolean {
        val now = SystemClockNow.elapsed
        if (now - anyModeCacheAt < MODE_CACHE_TTL_MS) return anyModeCache
        val live = isAnyModeActive()
        anyModeCache = live
        anyModeCacheAt = now
        return live
    }

    // -----------------------------------------------------------------
    // v2.9.11 r27 — DYNAMIC KEY FILTER (the root volume fix).
    // -----------------------------------------------------------------

    /** Live service reference for instant arm/disarm from other
     * components (session/cage/monk/lock start). */
    @Volatile private var keyFilterFlagOn = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Synchronized
    fun syncKeyFilterFlag() {
        val want = isAnyModeActive()
        if (want == keyFilterFlagOn) return
        keyFilterFlagOn = want
        mainHandler.post {
            try {
                serviceInfo = serviceInfo?.apply {
                    flags = if (want) {
                        flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    } else {
                        flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
                    }
                }
                DiagLog.log("KEY_FILTER", if (want) "armed (enforcement active)" else "disarmed (idle)")
            } catch (e: Exception) {
                DiagLog.logError("syncKeyFilterFlag", e)
            }
        }
    }

    // -----------------------------------------------------------------
    // v2.9.11 r27 — VOLUME-KEY DUPLICATE DEBOUNCE (pure bug fix).
    //
    // The user's recurring report: ONE volume press registers as MANY
    // ("ekbar chap dile onekbar chap hoye sondho barche"). The dynamic
    // key-filter flag above kills the root cause at idle; while a mode
    // is enforcing the filter is armed and some OEM input pipelines can
    // still REPLAY a single press as several ACTION_DOWNs. The first
    // press passes through untouched and replays inside the debounce
    // window are consumed, so one press is always exactly one step.
    //
    // There is NO other behaviour attached to volume keys — no coins,
    // no toasts, no UI. Physical hold-to-ramp (repeatCount > 0) works
    // completely normal.
    // -----------------------------------------------------------------

    /** Last seen volume-key ACTION_DOWN (duplicate/replay protection). */
    @Volatile private var lastVolumeKeyDownAt = 0L

    private fun onVolumeKeyDebounce(event: KeyEvent): Boolean {
        // Only fresh presses are debounced; ACTION_UP and hold-repeats
        // (repeatCount > 0) always pass straight through.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false
        val now = SystemClockNow.elapsed
        if (now - lastVolumeKeyDownAt < VOLUME_KEY_DEBOUNCE_MS) {
            DiagLog.log("VOLUME_DEBOUNCE", "replayed volume press consumed")
            return true
        }
        lastVolumeKeyDownAt = now
        return false
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        return when (event.keyCode) {
            // v2.9.11 r27 — volume keys have NO app behaviour; this is
            // only the duplicate-replay debounce of the multi-press bug
            // fix. Reached only while the key filter is armed (a mode is
            // enforcing).
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> onVolumeKeyDebounce(event)
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ALL_APPS,
            -> {
                // Emergency dialer must always stay interactive
                if (EmergencyLockdown.isActive(this)) return false

                // While ANY mode is active (Study, Detox, Monk, Lock My
                // Phone, Cage, etc.), Home, Back, Recents hardware and
                // navigation keys are completely blocked (like the Cage),
                // without changing the original UI of the mode.
                isAnyModeActiveCached()
            }
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
        com.maxleveldetox.overlay.SafetyPauseOverlay.unbind()
        A11yOverlayController.hide(this)
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private var lastHeartbeatWrite = 0L

    companion object {
        private const val FG_THROTTLE_MS = 800L
        private const val REELS_DEBOUNCE_MS = 500L

        /** v2.9.2 r18 — burst-cage episode semantics: a fresh reels ENTRY
         *  after this much quiet is always a new attempt; detections of
         *  the SAME still-showing surface never count. */
        private const val REELS_EPISODE_STALL_MS = 15_000L

        /** v2.9.2 r18 — post-redirect verification delay + bound. The
         *  delay is deliberately tight (900 ms): the redirect needs to
         *  land, but the faster a re-entry is confirmed, the faster the
         *  burst counter catches rapid hammering. */
        private const val REELS_VERIFY_DELAY_MS = 900L
        private const val REELS_EPISODE_VERIFY_MAX_MS = 12_000L

        /** v2.9.2 r18 — the 1-minute cage after 5 rapid attempts. */
        private const val REELS_CAGE_MS = 60_000L

        /** v2.9.2 r18 — kiosk assert heartbeat cadence. */
        private const val KIOSK_ASSERT_INTERVAL_MS = 2_000L

        /** v2.9.6 r22 — key-filter enforcement cache TTL. The raw check
         *  includes a blocking DataStore read; per-key-event execution
         *  stalled the input pipeline (volume keys stepped several bars
         *  per press). At most one real check per 250 ms. */
        private const val MODE_CACHE_TTL_MS = 250L

        /** v2.9.11 r27 — volume-key duplicate debounce: a REPLAYED
         *  ACTION_DOWN inside this window is consumed so one press is
         *  always one step (pure bug fix — no behaviour, no coins). */
        private const val VOLUME_KEY_DEBOUNCE_MS = 200L

        /** v2.9.11 r27 — live service reference so session/cage/monk/lock
         *  start can arm the (now dynamic) key filter INSTANTLY; the 2 s
         *  watchdog loop is the disarm backstop. */
        @Volatile
        private var instance: DetoxAccessibilityService? = null

        /** Arm/disarm the a11y key filter to match the live enforcement
         *  state RIGHT NOW (v2.9.11 r27 volume multi-press root fix).
         *  Called at mode starts/ends; the kiosk watchdog loop backstops
         *  it every 2 s in both directions. */
        fun syncKeyFilterSoon() {
            instance?.syncKeyFilterFlag()
        }

        /** v2.9 r16 (extended in r18): delayed re-scan schedule after
         *  entering a monitored app — covers late-inflating shorts UI and
         *  verifies redirects (the closed loop). */
        private val RESCAN_DELAYS_MS = longArrayOf(500L, 1500L, 3000L, 5000L, 8000L)

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
