package com.maxleveldetox

import android.app.Application
import android.content.Context
import com.maxleveldetox.alarm.ShockwaveAlarmEngine
import com.maxleveldetox.billing.BillingManager
import com.maxleveldetox.coins.CoinLedger
import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.enforcement.PolicyEngine
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.ViolationManager
import com.maxleveldetox.gamification.ProgressEngine
import com.maxleveldetox.growth.BreakPassManager
import com.maxleveldetox.growth.InsightNotifier
import com.maxleveldetox.guard.AccessibilityGuardJobService
import com.maxleveldetox.monitor.EngineStateStore
import com.maxleveldetox.prime.PrimeCommitManager
import com.maxleveldetox.recovery.PermissionMonitor
import com.maxleveldetox.reels.ReelsEscalationManager
import com.maxleveldetox.safety.SafetyPauseManager
import com.maxleveldetox.storage.MldDatabase
import com.maxleveldetox.storage.StateRepository
import com.maxleveldetox.unlock.EmergencyCodeManager
import com.maxleveldetox.unlock.TempUnlockManager
import com.maxleveldetox.usage.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MAXLEVEL DETOX application class + tiny service locator.
 *
 * The enforcement components outlive Flutter entirely: they are plain
 * Kotlin singletons owned by the Application process, backed by DataStore +
 * Room. If Flutter crashes, enforcement continues; if the process dies,
 * persisted state + [com.maxleveldetox.recovery.BootRecoveryReceiver]
 * bring it back (TRD §76, §35).
 */
class MldApp : Application() {

    lateinit var database: MldDatabase
        private set
    lateinit var stateRepo: StateRepository
        private set
    lateinit var runtimeConfig: RuntimeConfig
        private set
    lateinit var coinLedger: CoinLedger
        private set
    lateinit var policyEngine: PolicyEngine
        private set
    lateinit var violationManager: ViolationManager
        private set
    lateinit var sessionEngine: SessionEngine
        private set
    lateinit var tempUnlockManager: TempUnlockManager
        private set
    lateinit var alarmEngine: ShockwaveAlarmEngine
        private set
    lateinit var permissionMonitor: PermissionMonitor
        private set
    lateinit var usageTracker: UsageTracker
        private set
    lateinit var engineState: EngineStateStore
        private set

    // v2.0 Phase B: reels ladder, monk (object), safety pause, prime
    // commit + per-user emergency TOTP.
    lateinit var reelsEscalation: ReelsEscalationManager
        private set
    lateinit var safetyPause: SafetyPauseManager
        private set
    lateinit var primeCommit: PrimeCommitManager
        private set
    lateinit var emergencyCodes: EmergencyCodeManager
        private set

    // v2.1 Phase C: progress layer (DP / streaks / check-in / protection).
    lateinit var progressEngine: ProgressEngine
        private set

    // v2.2 Phase D: growth & monetization — billing launcher (entitlements
    // stay server-verified), weekly break passes, insight/announcement
    // notifications.
    lateinit var billing: BillingManager
        private set
    lateinit var breakPasses: BreakPassManager
        private set
    lateinit var insightNotifier: InsightNotifier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // v2.5.7 (K-5): crash + error-drain reporting. FIRST so every later
        // init failure is captured: uncaught exceptions are persisted to
        // files/crash_last.txt, DiagLog errors are mirrored to a capped
        // on-disk log, and the previous run's crash surfaces in DiagLog.
        try {
            com.maxleveldetox.monitor.CrashReporter.init(this)
            com.maxleveldetox.accessibility.DiagLog.diskMirror = { line ->
                com.maxleveldetox.monitor.CrashReporter.appendError(this, line)
            }
        } catch (_: Exception) {
            // reporting must never block startup
        }

        database = MldDatabase.build(this)
        stateRepo = StateRepository(this)
        runtimeConfig = RuntimeConfig(stateRepo)

        // v2.5.8 roadmap: restore the last validated remote detection rules
        // (server-pushed reels/shorts signatures) BEFORE any engine can ask
        // the detector what it supports. Failure -> compiled defaults.
        try {
            com.maxleveldetox.reels.DetectionRules.restore(stateRepo)
        } catch (_: Exception) {
            // keep compiled defaults (fail safe)
        }

        coinLedger = CoinLedger(database, stateRepo)
        usageTracker = UsageTracker(this)
        permissionMonitor = PermissionMonitor(this, stateRepo)

        policyEngine = PolicyEngine(this, stateRepo, runtimeConfig)
        violationManager = ViolationManager(database, stateRepo)
        tempUnlockManager = TempUnlockManager(stateRepo, coinLedger, runtimeConfig)

        sessionEngine = SessionEngine(
            context = this,
            stateRepo = stateRepo,
            database = database,
            policyEngine = policyEngine,
            violationManager = violationManager,
            tempUnlockManager = tempUnlockManager,
            coinLedger = coinLedger,
            runtimeConfig = runtimeConfig,
            permissionMonitor = permissionMonitor,
        )

        alarmEngine = ShockwaveAlarmEngine(this, database)

        // v2.0 Phase B managers.
        reelsEscalation = ReelsEscalationManager(this, stateRepo, violationManager)
        safetyPause = SafetyPauseManager(this, stateRepo)
        primeCommit = PrimeCommitManager(this, stateRepo)
        emergencyCodes = EmergencyCodeManager(this, stateRepo)

        // v2.1 Phase C progress engine — constructed LAST: it observes the
        // other engines but none of them depend on it (parallel layer).
        progressEngine = ProgressEngine(this, stateRepo, database, runtimeConfig)

        // v2.2 Phase D growth managers.
        billing = BillingManager(this)
        breakPasses = BreakPassManager(this, stateRepo, runtimeConfig)
        insightNotifier = InsightNotifier(this)

        // Connect Play Billing early (also restores offline purchases so
        // they can be re-verified). Entitlements stay server-authoritative.
        try {
            billing.connect()
        } catch (_: Exception) {
        }

        // Daily insight alarm (reschedules itself; also checked lazily by
        // the enforcement sweep).
        try {
            insightNotifier.scheduleDailyCheck()
        } catch (_: Exception) {
        }

        // Home-screen widgets refresh on every session state change — they
        // read native state directly and never touch Flutter (v2.2 Phase D;
        // Broadcaster supports multiple listeners since this change).
        try {
            SessionEngine.Broadcaster.addListener {
                com.maxleveldetox.widgets.WidgetUpdater.updateAll(this)
            }
            com.maxleveldetox.widgets.WidgetUpdater.updateAll(this)
        } catch (_: Exception) {
        }

        // Dual-engine health store + persisted watchdog (v2.0 Phase A1/A2).
        engineState = EngineStateStore(this)
        if (engineState.areGuardsEnabled()) {
            AccessibilityGuardJobService.schedule(this)
        }

        // Process start = recovery opportunity (force-stop, update, crash).
        // v2.5.5 audit fix C-3: recoverIfNeeded can reach
        // EnforcementService.start (unguarded startForegroundService) — an
        // uncaught coroutine exception would kill a background-started
        // process (widget/alarm broadcast) in a loop. Guarded like the monk
        // path below.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                sessionEngine.recoverIfNeeded()
            } catch (_: Exception) {
            }
        }

        // If a lock-my-phone session survived a process death, re-arm it
        // immediately (Phase A4: wall-clock authority).
        // v2.5.5 audit fix C-3: LockMyPhoneService.start() calls
        // startForegroundService() with no try/catch — on Android 12+ a
        // background process start (widget broadcast) throws
        // ForegroundServiceStartNotAllowedException inside onCreate →
        // crash loop while the session persists. Guarded (the sticky
        // service + BootRecoveryReceiver re-arm it on the next legit start).
        if (com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(this)) {
            try {
                com.maxleveldetox.lock.LockMyPhoneService.start(this)
            } catch (_: Exception) {
            }
        }

        // v2.5 r9.3: scheduled / recurring lock-my-phone windows — start any
        // window that is live now and (re)arm the next alarm.
        try {
            com.maxleveldetox.lock.LockScheduler.evaluateAndRearm(this, force = true)
        } catch (_: Exception) {
        }

        // If a monk-mode session survived a process death, re-arm the
        // lock service + guard (Phase B3: wall-clock authority).
        if (com.maxleveldetox.monk.MonkModeManager.isActive(this)) {
            try {
                com.maxleveldetox.monk.MonkModeLockService.start(this)
                com.maxleveldetox.monk.MonkModeGuardJobService.schedule(this)
            } catch (_: Exception) {
            }
        }

        // Initialize Firebase and subscribe to push notification broadcast topics
        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("all")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("announcements")
        } catch (e: Exception) {
            android.util.Log.w("MldApp", "Firebase init skipped", e)
        }
    }

    companion object {
        private lateinit var instance: MldApp

        /** v2.5.5 audit fix m-19: never leak
         * UninitializedPropertyAccessException when called before
         * onCreate completes (e.g. an early ContentProvider).
         */
        fun get(context: Context): MldApp {
            val app = context.applicationContext as? MldApp
            if (app != null) return app
            check(::instance.isInitialized) {
                "MldApp.get() called before Application.onCreate()"
            }
            return instance
        }
    }
}
