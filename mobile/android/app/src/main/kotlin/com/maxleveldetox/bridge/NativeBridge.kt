package com.maxleveldetox.bridge

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.CageSnapshot
import com.maxleveldetox.enforcement.ErrorCodes
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.enforcement.SessionSnapshot
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.Strictness
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.storage.AlarmEntity
import android.content.pm.ApplicationInfo
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NativeBridge — the `com.maxleveldetox/native` MethodChannel +
 * `com.maxleveldetox/statestream` EventChannel (TRD §40–42).
 *
 * SECURITY POSTURE:
 *  - Every method re-validates state natively. Flutter arguments are
 *    requests, never authority.
 *  - All responses use the structured envelope {success, data|errorCode}.
 *  - debug_* methods are hard-gated on BuildConfig.DEBUG — they physically
 *    cannot run in release builds (TRD §139/§140).
 *  - The state stream is a read-only projection.
 */
class NativeBridge(private val context: Context) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // v2.5.5 audit fix m-12: written on the main thread (onListen/onCancel)
    // and read from Dispatchers.Default (ticker/billing collectors) — mark
    // volatile to close the benign-but-latent visibility race.
    @Volatile
    private var eventSink: EventChannel.EventSink? = null
    @Volatile
    private var tickerActive = false

    // v2.5.5 audit fix M-6: the Broadcaster listener is tracked so it can
    // be REMOVED in detach() — every activity recreation used to leak one
    // listener (and one MainActivity) forever.
    //
    // v2.5.7 (K-1): the listener now dispatches the state push onto the
    // bridge scope instead of running it on the EMITTING thread. Emits fire
    // from MainActivity.onResume/onNewIntent on the MAIN thread, and
    // buildStateJson performs seven runBlocking DataStore reads (session,
    // cage, tempUnlock, shorts, coinBalance, onboarding, pact) — i.e. disk
    // I/O on the main thread on every settings-return. Budget devices paid
    // with dropped frames/ANR risk. The Default-dispatcher hop costs
    // nothing (pushState already posts to the main handler at the end).
    private val broadcasterListener: () -> Unit = {
        if (scope.isActive) {
            scope.launch { pushState() }
        }
    }

    // -----------------------------------------------------------------
    // Registration (called from MainActivity)
    // -----------------------------------------------------------------

    fun attach(messenger: io.flutter.plugin.common.BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler(this)
        EventChannel(messenger, STATE_STREAM).setStreamHandler(this)
        EventChannel(messenger, BILLING_STREAM).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    billingSink = events
                    startBillingFlow()
                }

                override fun onCancel(arguments: Any?) {
                    billingSink = null
                }
            })

        // Push state to Flutter on every native change (v2.2 Phase D: the
        // Broadcaster keeps a listener LIST — widget updates coexist).
        SessionEngine.Broadcaster.addListener(broadcasterListener)
    }

    /** v2.5.5 audit fix M-6: called from MainActivity's engine cleanup so a
     *  recreated activity never leaves a stale bridge (and its context)
     *  registered on the Broadcaster. Idempotent. */
    fun detach() {
        SessionEngine.Broadcaster.removeListener(broadcasterListener)
        eventSink = null
        billingSink = null
        tickerActive = false
        scope.cancel()
    }

    private fun startBillingFlow() {
        if (billingFlowStarted) return
        billingFlowStarted = true
        scope.launch {
            context.applicationContext.let { ctx ->
                MldApp.get(ctx).billing.purchaseEvents.collect { purchase ->
                    val payload = mapOf(
                        "productId" to purchase.productId,
                        "purchaseToken" to purchase.purchaseToken,
                        "orderId" to (purchase.orderId ?: ""),
                    )
                    mainHandler.post {
                        try {
                            billingSink?.success(payload)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // EventChannel — read-only state projection
    // -----------------------------------------------------------------

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        pushState()
        startTicker()
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        tickerActive = false
    }

    private fun startTicker() {
        if (tickerActive) return
        tickerActive = true
        scope.launch {
            while (isActive && eventSink != null) {
                delay(1_000L)
                pushState()
            }
            tickerActive = false
        }
    }

    private fun pushState() {
        val sink = eventSink ?: return
        val state = buildStateJson() ?: return
        streamPushes++
        // EventChannel callbacks must run on the main thread.
        mainHandler.post {
            try {
                sink.success(state)
            } catch (_: Exception) { /* listener gone */ }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val CHANNEL = "com.maxleveldetox/native"
        const val STATE_STREAM = "com.maxleveldetox/statestream"
        const val BILLING_STREAM = "com.maxleveldetox/billing"

        /** Stream liveness counter — the System Health screen reads it. */
        @Volatile
        var streamPushes: Int = 0
            private set
    }

    // v2.2 Phase D: billing purchase events pushed to Flutter so it can
    // call the Worker verify endpoint (the client never grants itself).
    @Volatile
    private var billingSink: EventChannel.EventSink? = null
    @Volatile
    private var billingFlowStarted = false

    private fun buildStateJson(): Map<String, Any?>? {
        // v2.3 r7 HARDENING (the r6 home-screen-loading regression class):
        // every field is now individually guarded — one failing subsystem
        // (coins DB hiccup, progress parse, config read) degrades that
        // field to null instead of nulling the WHOLE state, which used to
        // stall the Flutter bootstrap forever (bootstrapped only flips
        // when the stream delivers).
        return try {
            val app = MldApp.get(context)
            val now = SystemClockNow.elapsed

            val session = try {
                app.stateRepo.blockingSession()
            } catch (_: Exception) {
                null
            }
            val cage = try {
                app.stateRepo.blockingCage()
            } catch (_: Exception) {
                null
            }
            val tempUnlock = try {
                app.stateRepo.blockingTempUnlock()
            } catch (_: Exception) {
                null
            }
            val shorts = try {
                app.stateRepo.blockingShorts()
            } catch (_: Exception) {
                null
            }

            val sessionJson: JSONObject? = session?.let {
                if (it.status.isLive || it.status == SessionStatus.RECOVERY) {
                    it.toJson()
                } else null
            }

            mapOf(
                "session" to sessionJson?.let { jsonToMap(it) },
                "cage" to if (cage != null && cage.active && !cage.isExpired(now))
                    jsonToMap(cage.toJson(now)) else null,
                "tempUnlock" to if (tempUnlock != null && tempUnlock.active && !tempUnlock.isExpired(now))
                    jsonToMap(tempUnlock.toJson(now)) else null,
                "shorts" to try {
                    // v2.5.5 audit fix m-13: safe-call instead of !! — a null
                    // shorts snapshot degrades that field, not the throw path.
                    jsonToMap(shorts?.toJson(app.runtimeConfig.current().shortsWarningCount)
                        ?: JSONObject())
                } catch (_: Exception) {
                    null
                },
                "coins" to try {
                    // v2.5.5 audit fix M-7: buildStateJson runs on the MAIN
                    // thread via onResume/onNewIntent broadcasters — the
                    // runBlocking Room SUM was removed in favour of the
                    // DataStore-cached balance (updated on every ledger
                    // write, see CoinLedger.saveCoinBalance).
                    app.stateRepo.blockingCoinBalance()
                } catch (_: Exception) {
                    0
                },
                "permissions" to try {
                    jsonToMap(app.permissionMonitor.snapshot().toJson())
                } catch (_: Exception) {
                    null
                },
                "onboardingComplete" to try {
                    app.stateRepo.blockingOnboardingComplete()
                } catch (_: Exception) {
                    false
                },
                "pactAccepted" to try {
                    app.stateRepo.blockingPactAccepted()
                } catch (_: Exception) {
                    false
                },
                // v2.1 Phase C: compact progress projection (full snapshot is
                // a separate call — the 1s ticker must stay cheap).
                "progress" to try {
                    jsonToMap(app.progressEngine.compactJson())
                } catch (_: Exception) {
                    null
                },
            )
        } catch (e: Exception) {
            com.maxleveldetox.accessibility.DiagLog.logError("buildStateJson", e)
            // Minimal-but-valid state so the stream still DELIVERS and the
            // Flutter bootstrap can complete (empty state shows the
            // permission-problem view, never an infinite spinner).
            mapOf(
                "session" to null,
                "cage" to null,
                "tempUnlock" to null,
                "shorts" to null,
                "coins" to 0,
                "permissions" to null,
                "onboardingComplete" to false,
                "pactAccepted" to false,
                "progress" to null,
            )
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonValue(json.get(key))
        }
        return map
    }

    /** Recursive JSON -> channel-safe value. (Arrays used to keep raw
     *  JSONObject elements, which StandardMessageCodec cannot serialize —
     *  any list of objects would have thrown at the channel boundary.) */
    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> jsonToMap(value)
        is JSONArray -> List(value.length()) { i -> jsonValue(value.opt(i)) }
        JSONObject.NULL -> null
        else -> value
    }

    // -----------------------------------------------------------------
    // MethodChannel
    // -----------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>

        scope.launch {
            val envelope = handle(call.method, args)
            mainHandler.post { result.success(envelope) }
        }
    }

    private fun ok(data: JSONObject = JSONObject()): Map<String, Any?> =
        mapOf("success" to true, "data" to jsonToMap(data))

    private fun err(code: String, message: String): Map<String, Any?> =
        mapOf("success" to false, "errorCode" to code, "message" to message)

    private suspend fun handle(method: String, args: Map<*, *>?): Map<String, Any?> {
        val app = MldApp.get(context)
        return try {
            when (method) {
                // -----------------------------------------------------
                // Onboarding / pact
                // -----------------------------------------------------
                "completeOnboarding" -> {
                    app.stateRepo.setOnboardingComplete(true)
                    ok()
                }
                "acceptPact" -> {
                    app.stateRepo.setPactAccepted(true)
                    ok()
                }

                // -----------------------------------------------------
                // Sessions
                // -----------------------------------------------------
                "startSession" -> {
                    val mode = SessionMode.fromName(args?.get("mode") as? String)
                    val duration = (args?.get("durationMinutes") as? Number)?.toInt() ?: 60
                    val strictness = Strictness.fromName(args?.get("strictness") as? String)
                    val allowed = (args?.get("allowedPackages") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val categories = (args?.get("blockedCategories") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val subject = args?.get("subject") as? String ?: ""

                    // Hard gate: enforcement cannot activate without the Pact.
                    if (!app.stateRepo.blockingPactAccepted()) {
                        return err(ErrorCodes.PERMISSION_REQUIRED, "The Commitment Pact must be accepted first.")
                    }

                    val r = app.sessionEngine.startSession(mode, duration, strictness, allowed, categories, subject)
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                // v2.5 r9.4 - study break + subjects.
                "pauseSession" -> {
                    val minutes = (args?.get("minutes") as? Number)?.toInt() ?: 5
                    val r = app.sessionEngine.pauseSession(minutes)
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                "resumeSession" -> {
                    val r = app.sessionEngine.resumeSession()
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                "getStudySubjects" ->
                    ok(com.maxleveldetox.enforcement.StudySubjectStore.toJson(context))
                "addStudySubject" -> {
                    val error = com.maxleveldetox.enforcement.StudySubjectStore.add(
                        context, args?.get("name") as? String ?: "")
                    if (error != null) return err(ErrorCodes.INVALID_REQUEST, error)
                    ok(com.maxleveldetox.enforcement.StudySubjectStore.toJson(context))
                }
                "removeStudySubject" -> {
                    com.maxleveldetox.enforcement.StudySubjectStore.remove(
                        context, args?.get("name") as? String ?: "")
                    ok(com.maxleveldetox.enforcement.StudySubjectStore.toJson(context))
                }
                "stopSession" -> {
                    val r = app.sessionEngine.requestStop()
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }

                // -----------------------------------------------------
                // v2.0 Phase A: dual-engine + guards + lock-my-phone
                // -----------------------------------------------------
                "getEngineStatus" -> ok(app.engineState.statusJson())
                "getGuardStatus" -> ok(JSONObject().apply {
                    put("guardsEnabled", app.engineState.areGuardsEnabled())
                    put("guardScheduled",
                        com.maxleveldetox.guard.AccessibilityGuardJobService.isScheduled(context))
                    put("cleanStreakDays", app.engineState.guardCleanStreakDays())
                })
                "requestDeviceAdmin" -> {
                    com.maxleveldetox.lock.LockMyPhoneController.requestAdmin(context)
                    ok()
                }
                "getDeviceAdminState" -> ok(JSONObject().apply {
                    put("adminActive",
                        com.maxleveldetox.lock.LockMyPhoneController.isAdminActive(context))
                })
                "startLockMyPhone" -> {
                    val minutes = (args?.get("durationMinutes") as? Number)?.toInt() ?: 0
                    if (minutes < 1 || minutes > 480) {
                        return err(ErrorCodes.INVALID_REQUEST, "durationMinutes out of range (1..480)")
                    }
                    // Hard gates: pact + device admin active.
                    if (!app.stateRepo.blockingPactAccepted()) {
                        return err(ErrorCodes.PERMISSION_REQUIRED, "The Commitment Pact must be accepted first.")
                    }
                    if (!com.maxleveldetox.lock.LockMyPhoneController.isAdminActive(context)) {
                        return err(ErrorCodes.PERMISSION_REQUIRED,
                            "Device admin must be active for lock-my-phone.")
                    }
                    val reason = args?.get("reason") as? String ?: "manual"
                    com.maxleveldetox.lock.LockMyPhoneController.start(context, minutes, reason)
                    ok(com.maxleveldetox.lock.LockMyPhoneController.statusJson(context))
                }
                "getLockMyPhoneStatus" ->
                    ok(com.maxleveldetox.lock.LockMyPhoneController.statusJson(context))
                // v2.5 r9.3 - scheduled / recurring lock-my-phone windows.
                "getLockSchedules" -> ok(JSONObject().apply {
                    put("schedules", com.maxleveldetox.lock.LockScheduler.toJsonArray(context))
                })
                "saveLockSchedule" -> {
                    val days = (args?.get("days") as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
                    val error = com.maxleveldetox.lock.LockScheduler.save(
                        context = context,
                        id = args?.get("id") as? String,
                        kind = args?.get("kind") as? String ?: "weekly",
                        enabled = args?.get("enabled") as? Boolean ?: true,
                        days = days,
                        startMin = (args?.get("startMin") as? Number)?.toInt() ?: 0,
                        endMin = (args?.get("endMin") as? Number)?.toInt() ?: 0,
                        startEpochMs = (args?.get("startEpochMs") as? Number)?.toLong() ?: 0L,
                        durationMinutes = (args?.get("durationMinutes") as? Number)?.toInt() ?: 0,
                    )
                    if (error != null) return err(ErrorCodes.INVALID_REQUEST, error)
                    ok(JSONObject().apply {
                        put("schedules", com.maxleveldetox.lock.LockScheduler.toJsonArray(context))
                    })
                }
                "deleteLockSchedule" -> {
                    val id = args?.get("id") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "id required")
                    val error = com.maxleveldetox.lock.LockScheduler.delete(context, id)
                    if (error != null) return err(ErrorCodes.INVALID_REQUEST, error)
                    ok(JSONObject().apply {
                        put("schedules", com.maxleveldetox.lock.LockScheduler.toJsonArray(context))
                    })
                }
                "stopLockMyPhoneValidated" -> {
                    // ONLY reachable after the user re-confirms in the
                    // bailout flow — spends bailout coins through the
                    // immutable ledger, then ends the lock session.
                    val cost = app.runtimeConfig.current().bailoutCoins
                    val balance = app.coinLedger.balance()
                    if (balance < cost) {
                        return err(ErrorCodes.INSUFFICIENT_COINS,
                            "Bailout costs $cost coins; balance is $balance.")
                    }
                    val (spent, remaining) = app.coinLedger.spend(
                        type = "BAILOUT",
                        cost = cost,
                        reference = "lock_my_phone_" + System.currentTimeMillis(),
                    )
                    if (!spent) {
                        return err(ErrorCodes.INSUFFICIENT_COINS, "Coin spend failed.")
                    }
                    com.maxleveldetox.lock.LockMyPhoneController.markEnded(
                        context, "bailout")
                    com.maxleveldetox.lock.LockMyPhoneController.stopValidated(context)
                    ok(com.maxleveldetox.lock.LockMyPhoneController.statusJson(context)
                        .put("coinsSpent", cost)
                        .put("coinBalance", remaining))
                }
                "validateBailout" -> {
                    val v = app.sessionEngine.validateBailout()
                    ok(JSONObject(v))
                }
                "executeBailout" -> {
                    val r = app.sessionEngine.executeBailout()
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }

                // -----------------------------------------------------
                // v2.0 Phase B: reels escalation ladder
                // -----------------------------------------------------
                "getReelsStatus" -> ok(app.reelsEscalation.statusJson())
                "setReelsDailyLimitMinutes" -> {
                    val minutes = (args?.get("minutes") as? Number)?.toInt() ?: -1
                    val okSet = app.reelsEscalation.setDailyLimitMinutes(minutes)
                    if (okSet) ok(app.reelsEscalation.statusJson())
                    else err(ErrorCodes.INVALID_REQUEST, "minutes out of range (5..120)")
                }
                "useReelsEmergencyPass" -> {
                    val pkg = args?.get("package") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "package required")
                    val r = app.reelsEscalation.useEmergencyPass(pkg)
                    if (r.ok) ok(app.reelsEscalation.statusJson())
                    else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }

                // -----------------------------------------------------
                // v2.0 Phase B3: monk mode
                // -----------------------------------------------------
                "activateMonkMode" -> {
                    val goal = args?.get("goal") as? String ?: ""
                    val minutes = (args?.get("durationMinutes") as? Number)?.toInt() ?: 0
                    val allowed = (args?.get("allowedPackages") as? List<*>)
                        ?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                    if (minutes < 1 || minutes > 720) {
                        return err(ErrorCodes.INVALID_REQUEST, "durationMinutes out of range (1..720)")
                    }
                    if (!app.stateRepo.blockingPactAccepted()) {
                        return err(ErrorCodes.PERMISSION_REQUIRED, "The Commitment Pact must be accepted first.")
                    }
                    if (!com.maxleveldetox.monk.MonkModeManager.isAdminActive(context)) {
                        return err(ErrorCodes.PERMISSION_REQUIRED,
                            "Device admin must be active for monk mode.")
                    }
                    val okStart = com.maxleveldetox.monk.MonkModeManager.activate(
                        context, goal, minutes, allowed)
                    if (okStart) ok(com.maxleveldetox.monk.MonkModeManager.statusJson(context))
                    else err(ErrorCodes.INVALID_REQUEST, "Monk activation failed.")
                }
                "getMonkModeStatus" ->
                    ok(com.maxleveldetox.monk.MonkModeManager.statusJson(context))
                // v2.5.5 audit fix (Flutter M-7): the active Monk view TOLD the
                // user "deactivation is only possible from this screen" but
                // no deactivate path existed anywhere — users were locked in
                // for the full window. Early deactivation is the documented
                // in-app exit (device unlocked, user present) and yields NO
                // completion DP (that is only awarded on natural expiry).
                "deactivateMonkMode" -> {
                    if (!com.maxleveldetox.monk.MonkModeManager.isActive(context)) {
                        return err(ErrorCodes.INVALID_REQUEST, "Monk mode is not active.")
                    }
                    try {
                        com.maxleveldetox.monk.MonkModeManager.deactivate(context, "user")
                    } catch (e: Exception) {
                        return err(ErrorCodes.SYSTEM_RESTRICTION, "Deactivation failed.")
                    }
                    ok(com.maxleveldetox.monk.MonkModeManager.statusJson(context))
                }

                // -----------------------------------------------------
                // v2.0 Phase B4: safety pause
                // -----------------------------------------------------
                "setSafetyPauseEnabled" -> {
                    val enabled = args?.get("enabled") as? Boolean ?: false
                    app.safetyPause.setEnabled(enabled)
                    // v2.1 Phase C: first activation bonus.
                    if (enabled) {
                        try { app.progressEngine.onFeatureActivated("safety") } catch (_: Exception) {}
                    }
                    ok(app.safetyPause.statusJson())
                }
                "setSafetyPauseApps" -> {
                    val apps = (args?.get("apps") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                    app.safetyPause.setApps(apps)
                    ok(app.safetyPause.statusJson())
                }
                "setSafetyPauseSeconds" -> {
                    val seconds = (args?.get("seconds") as? Number)?.toInt() ?: -1
                    val okSet = app.safetyPause.setPauseSeconds(seconds)
                    if (okSet) ok(app.safetyPause.statusJson())
                    else err(ErrorCodes.INVALID_REQUEST, "seconds out of range (3..60)")
                }
                "getSafetyPauseStatus" -> ok(app.safetyPause.statusJson())

                // -----------------------------------------------------
                // v2.0 Phase B5: emergency TOTP + prime commit
                // -----------------------------------------------------
                "enrollEmergencyCodes" -> {
                    // Returns the secret + otpauth URL exactly once per
                    // enrollment — the UI shows the QR and warns the user.
                    val enrollment = app.emergencyCodes.enroll()
                    ok(JSONObject().apply {
                        put("secret", enrollment.secret)
                        put("otpauthUrl", enrollment.otpauthUrl)
                        put("isNew", enrollment.isNew)
                    })
                }
                "getEmergencyCodeStatus" -> ok(JSONObject().apply {
                    put("enrolled", app.emergencyCodes.isEnrolled())
                })
                "activatePrimeCommit" -> {
                    val title = args?.get("title") as? String ?: "Prime commit"
                    val hours = (args?.get("commitHours") as? Number)?.toInt() ?: 0
                    val r = app.primeCommit.activate(title, hours)
                    if (r.ok) ok(app.primeCommit.statusJson())
                    else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                "giveUpPrimeCommit" -> {
                    val code = args?.get("code") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "code required")
                    val r = app.primeCommit.giveUp(code)
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                "getPrimeCommitStatus" -> ok(app.primeCommit.statusJson())

                // -----------------------------------------------------
                // Temporary unlock
                // -----------------------------------------------------
                "requestTempUnlock" -> {
                    val packages = (args?.get("packages") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val r = app.tempUnlockManager.request(packages)
                    if (r.ok) ok(stateData()) else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }

                // -----------------------------------------------------
                // Coins
                // -----------------------------------------------------
                "awardAdCoin" -> {
                    val rewardKey = args?.get("rewardKey") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "rewardKey required")
                    if (rewardKey.length > 128 || !rewardKey.matches(Regex("[A-Za-z0-9_-]+"))) {
                        return err(ErrorCodes.INVALID_REQUEST, "invalid rewardKey")
                    }
                    val awarded = app.coinLedger.awardAd(rewardKey)
                    ok(JSONObject().put("awarded", awarded))
                }
                "getCoinTransactions" -> {
                    val limit = ((args?.get("limit") as? Number)?.toInt() ?: 100).coerceIn(1, 500)
                    val arr = JSONArray()
                    app.coinLedger.recent(limit).forEach { t ->
                        arr.put(JSONObject().apply {
                            put("id", t.id)
                            put("type", t.type)
                            put("amount", t.amount)
                            put("timestamp", t.timestampWall)
                            put("source", t.source)
                        })
                    }
                    ok(JSONObject().put("transactions", arr))
                }
                // v2.5.7 (C-3): apply server-originated coin grants (admin
                // adjustments / bonuses) to the immutable device ledger.
                // Idempotent by transactionId; never accepts an arbitrary
                // Flutter-supplied amount without a server transaction id.
                "applyServerCoinGrants" -> {
                    val payload = args?.get("grantsJson") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "grantsJson required")
                    if (payload.length > 100_000) {
                        return err(ErrorCodes.INVALID_REQUEST, "payload too large")
                    }
                    val grants = try { JSONArray(payload) } catch (_: Exception) { JSONArray() }
                    var applied = 0
                    for (i in 0 until grants.length()) {
                        val g = grants.optJSONObject(i) ?: continue
                        val txId = g.optString("transactionId", "")
                        val amount = g.optInt("amount", 0)
                        val type = g.optString("type", "ADMIN_ADJUSTMENT")
                        if (txId.length > 128 || !txId.matches(Regex("[A-Za-z0-9_-]+"))) {
                            continue // malformed grant — skip, never fail the batch
                        }
                        if (app.coinLedger.applyGrant(txId, amount, type)) applied++
                    }
                    ok(JSONObject().put("applied", applied))
                }
                // v2.5.7 (K-3): real device identity for /devices/register.
                "getDeviceInfo" -> ok(JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER ?: "unknown")
                    put("model", Build.MODEL ?: "android")
                    put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                    put("apiLevel", Build.VERSION.SDK_INT)
                })
                // v2.5.7 (K-4): the forced-update dialog's Play Store button.
                "openPlayStorePage" -> {
                    val opened = try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=${context.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    } catch (_: Exception) {
                        // Play app missing — browser fallback.
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    ok(JSONObject().put("opened", opened))
                }

                // -----------------------------------------------------
                // Shorts blocker
                // -----------------------------------------------------
                "setShortsEnabled" -> {
                    val enabled = args?.get("enabled") as? Boolean ?: return err(ErrorCodes.INVALID_REQUEST, "enabled required")
                    val s = app.stateRepo.blockingShorts()
                    app.stateRepo.saveShorts(s.copy(enabled = enabled))
                    // v2.1 Phase C: first activation bonus.
                    if (enabled) {
                        try { app.progressEngine.onFeatureActivated("shorts") } catch (_: Exception) {}
                    }
                    ok()
                }
                "setShortsPlatform" -> {
                    val pkg = args?.get("packageName") as? String ?: return err(ErrorCodes.INVALID_REQUEST, "packageName required")
                    val enabled = args?.get("enabled") as? Boolean ?: true
                    val s = app.stateRepo.blockingShorts()
                    val updated = s.platforms.toMutableMap()
                    if (updated.containsKey(pkg)) updated[pkg] = enabled
                    app.stateRepo.saveShorts(s.copy(platforms = updated))
                    ok()
                }

                // -----------------------------------------------------
                // App rules
                // -----------------------------------------------------
                "getAppRules" -> {
                    val arr = JSONArray()
                    val pm = context.packageManager
                    val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(launcher, 0).forEach { ri ->
                        val pkg = ri.activityInfo.packageName
                        if (pkg == context.packageName) return@forEach
                        val category = app.policyEngine.categoryOf(pkg) ?: "other"
                        // v2.5.5 audit fix M-1: surface the user's stored
                        // rule (explicit block/allow) instead of only the
                        // category-derived default.
                        val userRule =
                            com.maxleveldetox.enforcement.AppRulesStore.all(context)
                                .firstOrNull { it.pkg == pkg }
                        arr.put(JSONObject().apply {
                            put("packageName", pkg)
                            put("appName", ri.loadLabel(pm).toString())
                            put("category", category)
                            put("blocked", userRule?.blocked
                                ?: (category in setOf("social", "games", "shorts", "entertainment")))
                        })
                    }
                    ok(JSONObject().put("apps", arr))
                }
                "setAppRule" -> {
                    // v2.5.5 audit fix M-1: the handler used to return ok()
                    // without persisting anything — the App Rules feature
                    // was decorative. Rules now persist via AppRulesStore
                    // and PolicyEngine consults them for future sessions
                    // (the ACTIVE session's snapshot stays authoritative,
                    // TRD §109).
                    val pkg = args?.get("packageName") as? String
                    if (pkg.isNullOrBlank()) {
                        return err(ErrorCodes.INVALID_REQUEST, "packageName is required")
                    }
                    val blocked = args?.get("blocked") as? Boolean ?: true
                    com.maxleveldetox.enforcement.AppRulesStore.set(context, pkg.trim(), blocked)
                    ok()
                }

                // -----------------------------------------------------
                // v2.3 r7 — App limits (per-app daily minutes)
                // -----------------------------------------------------
                "getAppLimits" -> {
                    val arr = JSONArray()
                    com.maxleveldetox.enforcement.AppLimitEngine.all(context).forEach { l ->
                        arr.put(JSONObject().apply {
                            put("packageName", l.pkg)
                            put("dailyLimitMinutes", l.dailyLimitMinutes)
                            put("minutesUsedToday",
                                com.maxleveldetox.enforcement.AppLimitEngine
                                    .minutesUsedToday(context, l.pkg))
                        })
                    }
                    ok(JSONObject().put("limits", arr))
                }
                "setAppLimit" -> {
                    val pkg = args?.get("package") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "package required")
                    val minutes = ((args?.get("minutes") as? Number)?.toInt() ?: 0)
                        .coerceIn(0, 1440)
                    com.maxleveldetox.enforcement.AppLimitEngine
                        .setLimit(context, pkg, minutes)
                    ok()
                }

                // -----------------------------------------------------
                // v2.3 r7 — Blocking schedules
                // -----------------------------------------------------
                "getSchedules" -> {
                    val arr = JSONArray()
                    com.maxleveldetox.enforcement.ScheduleEngine.all(context).forEach { p ->
                        arr.put(p.toJson())
                    }
                    ok(JSONObject().put("schedules", arr))
                }
                "saveSchedule" -> {
                    val id = args?.get("id") as? String
                        ?: com.maxleveldetox.enforcement.ScheduleEngine.newId()
                    val days = (args?.get("days") as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toInt()?.coerceIn(1, 7) }
                        ?.toSet() ?: emptySet()
                    val pkgs = (args?.get("packages") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                    val profile = com.maxleveldetox.enforcement.ScheduleEngine.Profile(
                        id = id,
                        name = (args?.get("name") as? String ?: "Schedule").take(40),
                        startMinuteOfDay = ((args?.get("startMinute") as? Number)?.toInt() ?: 0)
                            .coerceIn(0, 1439),
                        endMinuteOfDay = ((args?.get("endMinute") as? Number)?.toInt() ?: 0)
                            .coerceIn(0, 1439),
                        days = days,
                        blockedPackages = pkgs,
                        enabled = args?.get("enabled") as? Boolean ?: true,
                    )
                    com.maxleveldetox.enforcement.ScheduleEngine.upsert(context, profile)
                    ok()
                }
                "deleteSchedule" -> {
                    val id = args?.get("id") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "id required")
                    com.maxleveldetox.enforcement.ScheduleEngine.delete(context, id)
                    ok()
                }

                // -----------------------------------------------------
                // v2.3 r7 — Notification guard (listener access + toggle)
                // -----------------------------------------------------
                "getNotificationGuard" -> ok(JSONObject().apply {
                    put("granted", com.maxleveldetox.guard.NotificationBlockerAccess
                        .isGranted(context))
                    put("enabled", com.maxleveldetox.guard.NotificationBlockerPrefs
                        .enabled(context))
                })
                "setNotificationGuardEnabled" -> {
                    val enabled = args?.get("enabled") as? Boolean ?: true
                    com.maxleveldetox.guard.NotificationBlockerPrefs
                        .setEnabled(context, enabled)
                    ok()
                }

                // -----------------------------------------------------
                // Permissions
                // -----------------------------------------------------
                "getPermissionState" -> ok(app.permissionMonitor.snapshot().toJson())
                "openPermissionSettings" -> {
                    val key = args?.get("key") as? String ?: "app"
                    val intent = if (key == "notificationListener") {
                        com.maxleveldetox.guard.NotificationBlockerAccess.settingsIntent()
                    } else {
                        app.policyEngine.settingsIntentFor(key)
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        ok()
                    } catch (e: Exception) {
                        err(ErrorCodes.SYSTEM_RESTRICTION, "Could not open settings: ${e.message}")
                    }
                }
                "requestIgnoreBatteryOptimizations" -> {
                    requestBatteryException()
                    ok()
                }
                // v2.5 r9.2 - OEM autostart / background-power screen (E9).
                "openOemBackgroundSettings" -> {
                    val opened = com.maxleveldetox.guard.OemBackgroundSettings.open(context)
                    ok(JSONObject().put("opened", opened))
                }

                // -----------------------------------------------------
                // Usage / history / stats
                // -----------------------------------------------------
                "getUsageStats" -> ok(JSONObject().put("usage", app.usageTracker.toJson()))
                "getHistory" -> {
                    val limit = ((args?.get("limit") as? Number)?.toInt() ?: 60).coerceIn(1, 200)
                    val arr = JSONArray()
                    app.database.sessionDao().recent(limit).forEach { s ->
                        arr.put(JSONObject().apply {
                            put("id", s.id)
                            put("mode", s.mode)
                            put("completed", s.completed)
                            put("bailedOut", s.bailedOut)
                            put("start", s.startWall)
                            put("durationMinutes", s.durationMinutes)
                            put("violations", s.violations)
                            put("cageTriggered", s.cageTriggered)
                            put("tempUnlockUsed", s.tempUnlockUsed)
                        })
                    }
                    ok(JSONObject().put("history", arr))
                }
                "getWeeklyStats" -> ok(weeklyStats(app))
                "getViolations" -> {
                    val limit = ((args?.get("limit") as? Number)?.toInt() ?: 50).coerceIn(1, 200)
                    val arr = JSONArray()
                    app.database.violationDao().recent(limit).forEach { v ->
                        arr.put(JSONObject().apply {
                            put("timestamp", v.timestampWall)
                            put("type", v.type)
                            put("packageName", v.packageName)
                            put("warningNumber", v.warningNumber)
                        })
                    }
                    ok(JSONObject().put("violations", arr))
                }

                // -----------------------------------------------------
                // Alarms
                // -----------------------------------------------------
                "scheduleAlarm" -> {
                    val id = args?.get("id") as? String ?: return err(ErrorCodes.INVALID_REQUEST, "id required")
                    val hour = (args?.get("hour") as? Number)?.toInt() ?: 6
                    val minute = (args?.get("minute") as? Number)?.toInt() ?: 30
                    val difficulty = args?.get("difficulty") as? String ?: "MEDIUM"
                    val enabled = args?.get("enabled") as? Boolean ?: true
                    val repeat = args?.get("repeatDays") as? List<*> ?: emptyList<Any>()
                    app.alarmEngine.upsert(
                        AlarmEntity(
                            id = id,
                            hour = hour.coerceIn(0, 23),
                            minute = minute.coerceIn(0, 59),
                            repeatDays = JSONArray(repeat).toString(),
                            difficulty = if (difficulty in listOf("EASY", "MEDIUM", "HARD")) difficulty else "MEDIUM",
                            enabled = enabled,
                            label = args?.get("label") as? String ?: "",
                        )
                    )
                    // v2.1 Phase C: first alarm setup bonus.
                    if (enabled) {
                        try { app.progressEngine.onFeatureActivated("alarm") } catch (_: Exception) {}
                    }
                    ok()
                }
                "cancelAlarm" -> {
                    val id = args?.get("alarmId") as? String ?: return err(ErrorCodes.INVALID_REQUEST, "alarmId required")
                    app.alarmEngine.delete(id)
                    ok()
                }
                "getAlarms" -> {
                    val arr = JSONArray()
                    kotlinx.coroutines.runBlocking {
                        app.database.alarmDao().all().forEach { a ->
                            arr.put(JSONObject().apply {
                                put("id", a.id)
                                put("hour", a.hour)
                                put("minute", a.minute)
                                put("repeatDays", ShockwaveAlarmDaysParser.parse(a.repeatDays))
                                put("difficulty", a.difficulty)
                                put("enabled", a.enabled)
                                put("label", a.label)
                            })
                        }
                    }
                    ok(JSONObject().put("alarms", arr))
                }

                // -----------------------------------------------------
                // Remote config — validated + clamped natively before use
                // -----------------------------------------------------
                "applyRemoteConfig" -> {
                    val json = args?.get("configJson") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "configJson required")
                    val applied = app.runtimeConfig.applyRemote(json)
                    if (applied) ok() else err(ErrorCodes.INVALID_REQUEST, "Config failed validation")
                }

                // v2.5.8 roadmap: dynamic remote detection rules (server-
                // pushed reels/shorts signatures). Same validation-before-
                // activation boundary as remote config: a payload that
                // fails the strict schema NEVER touches the active ruleset.
                "applyDetectionRules" -> {
                    val json = args?.get("rulesJson") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "rulesJson required")
                    if (json.length > 25_000) {
                        return err(ErrorCodes.INVALID_REQUEST, "rules payload too large")
                    }
                    val parsed = com.maxleveldetox.reels.DetectionRules.parseAndValidate(json)
                    if (parsed === null) {
                        err(ErrorCodes.INVALID_REQUEST, "Detection rules failed validation")
                    } else {
                        com.maxleveldetox.reels.DetectionRules.activate(parsed)
                        scope.launch {
                            com.maxleveldetox.reels.DetectionRules.persist(app.stateRepo, json)
                        }
                        ok(JSONObject().apply {
                            put("version", parsed.version)
                            put("platforms", parsed.platforms.size)
                        })
                    }
                }
                "getDetectionRulesStatus" -> ok(JSONObject().apply {
                    val active = com.maxleveldetox.reels.DetectionRules.activeRuleSet()
                    put("version", active.version)
                    put("remote", active.platforms.isNotEmpty())
                })

                // -----------------------------------------------------
                // v2.1 Phase C: progress layer (gamification)
                // -----------------------------------------------------
                "getProgress" -> ok(app.progressEngine.snapshotJson())
                // v2.5.8 roadmap: DP mirror payload for the community
                // leaderboard (read-only projection, server clamps again).
                "getLeaderboardSnapshot" -> ok(app.progressEngine.leaderboardSnapshotJson())

                // v2.5.9 (r11.1) — opportunity-cost snapshot (user-requested
                // "ei shomoy kaje lagale eita hoto" nudge) + the 7-day
                // distraction trend for the Insights screen chart.
                "getOpportunityCost" -> ok(
                    kotlinx.coroutines.runBlocking {
                        com.maxleveldetox.growth.OpportunityCostEngine.snapshotJson(context)
                    })
                "getDistractionTrend" -> ok(
                    kotlinx.coroutines.runBlocking {
                        com.maxleveldetox.growth.OpportunityCostEngine.trendJson(context)
                    })
                // v2.5.9 (r11.2) — today's top distracting apps for the
                // Insights "TOP DISTRACTING APPS" card (display-only).
                "getTopDistractingApps" -> ok(
                    JSONObject().put(
                        "apps",
                        kotlinx.coroutines.runBlocking {
                            com.maxleveldetox.monitor.BrainRotEngine
                                .topDistractingAppsJson(context)
                        }))
                "claimCheckIn" -> {
                    val awarded = app.progressEngine.claimCheckIn()
                    if (awarded > 0) {
                        ok(JSONObject().apply {
                            put("awarded", awarded)
                            put("progress", app.progressEngine.snapshotJson())
                        })
                    } else {
                        err("CHECKIN_UNAVAILABLE", "No check-in available right now.")
                    }
                }
                "getDpHistory" -> {
                    val limit = (args?.get("limit") as? Number)?.toInt() ?: 30
                    ok(JSONObject().put("awards", app.progressEngine.recentAwardsJson(limit)))
                }
                "getRelapseHistory" -> {
                    val limit = (args?.get("limit") as? Number)?.toInt() ?: 20
                    ok(JSONObject().put("relapses", app.progressEngine.relapseHistoryJson(limit)))
                }

                // -----------------------------------------------------
                // v2.2 Phase D: growth & monetization
                // -----------------------------------------------------
                "getBreakPassStatus" -> ok(app.breakPasses.statusJson())
                "useBreakPass" -> {
                    val pkg = args?.get("package") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "package required")
                    val r = app.breakPasses.usePass(pkg)
                    if (r.ok) ok(app.breakPasses.statusJson()
                        .put("grantedSeconds", r.remainingSeconds))
                    else err(r.errorCode ?: ErrorCodes.UNKNOWN, r.message ?: "failed")
                }
                "endBreakPassEarly" -> {
                    app.breakPasses.endBreakEarly()
                    ok(app.breakPasses.statusJson())
                }
                "getBillingProducts" -> {
                    val products = app.billing.queryProducts()
                    ok(JSONObject().put("products", app.billing.productsJson(products))
                        .put("billingReady", products.isNotEmpty()))
                }
                "launchPurchase" -> {
                    val productId = args?.get("productId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "productId required")
                    val activity = context as? android.app.Activity
                        ?: return err(ErrorCodes.SYSTEM_RESTRICTION,
                            "Purchase flow requires the foreground activity.")
                    // v2.5.7 (W-7): bind the purchase to the signed-in
                    // account id (obfuscated account id) when Flutter knows it.
                    (args?.get("accountId") as? String)?.takeIf { it.isNotBlank() }?.let { aid ->
                        app.billing.accountId = aid
                    }
                    val launched = app.billing.launchPurchase(activity, productId)
                    if (launched) ok()
                    else err(ErrorCodes.SYSTEM_RESTRICTION,
                        "Google Play billing is unavailable. Try bKash or restore later.")
                }
                "restorePurchases" -> {
                    app.billing.restorePurchases()
                    ok()
                }
                "getWidgetStatus" -> ok(JSONObject().apply {
                    put("anyPinned", com.maxleveldetox.widgets.WidgetUpdater.anyPinned(context))
                    put("pinSupported",
                        try {
                            (context.getSystemService(android.appwidget.AppWidgetManager::class.java))
                                ?.isRequestPinAppWidgetSupported ?: false
                        } catch (_: Exception) {
                            false
                        })
                })
                "pinWidget" -> {
                    val which = args?.get("which") as? String ?: "streak"
                    val provider = when (which) {
                        "session" -> com.maxleveldetox.widgets.SessionWidgetProvider::class.java
                        "usage" -> com.maxleveldetox.widgets.UsageWidgetProvider::class.java
                        "coins" -> com.maxleveldetox.widgets.CoinsWidgetProvider::class.java
                        "brainrot" -> com.maxleveldetox.widgets.BrainRotWidgetProvider::class.java
                        // v2.5.8 roadmap: distraction trend chart widget.
                        "trend" -> com.maxleveldetox.widgets.TrendWidgetProvider::class.java
                        else -> com.maxleveldetox.widgets.StreakWidgetProvider::class.java
                    }
                    val requested = com.maxleveldetox.widgets.WidgetUpdater.requestPin(context, provider)
                    if (requested) {
                        // One-time bonus when the user adopts widgets.
                        try { app.progressEngine.onFeatureActivated("widget") } catch (_: Exception) {}
                        ok()
                    } else {
                        err(ErrorCodes.SYSTEM_RESTRICTION,
                            "This launcher does not support widget pinning.")
                    }
                }
                "updateWidgets" -> {
                    com.maxleveldetox.widgets.WidgetUpdater.updateAll(context)
                    ok()
                }
                "deliverAnnouncements" -> {
                    // Pull-model delivery: Flutter fetched /announcements and
                    // forwards them; native posts unseen ones as local
                    // notifications (no Firebase dependency by design).
                    val payload = args?.get("announcementsJson") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "announcementsJson required")
                    if (payload.length > 20_000) {
                        return err(ErrorCodes.INVALID_REQUEST, "payload too large")
                    }
                    app.insightNotifier.deliverAnnouncements(payload)
                    ok()
                }
                "checkDailyInsight" -> {
                    app.insightNotifier.maybePostDailyInsight()
                    ok()
                }

                // -----------------------------------------------------
                // v2.5 r9 — Social Sentry parity: tasks / brain rot /
                // companion (Sinthia) local state.
                // -----------------------------------------------------
                "getTasks" -> ok(JSONObject().apply {
                    put("tasks", com.maxleveldetox.gamification.TasksEngine.getTasks(context))
                    put("status", com.maxleveldetox.gamification.TasksEngine.statusJson(context))
                })
                "addTask" -> {
                    val title = args?.get("title") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "title required")
                    val task = com.maxleveldetox.gamification.TasksEngine.addTask(
                        context,
                        title = title,
                        note = args?.get("note") as? String ?: "",
                        priority = (args?.get("priority") as? Int) ?: 2,
                        category = args?.get("category") as? String ?: "general",
                        routine = args?.get("routine") as? Boolean ?: false,
                    )
                    if (task != null) ok(task) else err(ErrorCodes.INVALID_REQUEST, "Invalid task")
                }
                "addSubtask" -> {
                    val taskId = args?.get("taskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "taskId required")
                    val title = args?.get("title") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "title required")
                    if (com.maxleveldetox.gamification.TasksEngine.addSubtask(context, taskId, title)) ok()
                    else err(ErrorCodes.INVALID_REQUEST, "Task not found")
                }
                "toggleSubtask" -> {
                    val taskId = args?.get("taskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "taskId required")
                    val subtaskId = args?.get("subtaskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "subtaskId required")
                    val sub = com.maxleveldetox.gamification.TasksEngine.toggleSubtask(context, taskId, subtaskId)
                    if (sub != null) ok(sub) else err(ErrorCodes.INVALID_REQUEST, "Subtask not found")
                }
                "completeTask" -> {
                    val taskId = args?.get("taskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "taskId required")
                    if (com.maxleveldetox.gamification.TasksEngine.completeTask(context, taskId)) ok()
                    else err(ErrorCodes.INVALID_REQUEST, "Task not completable")
                }
                "reopenTask" -> {
                    val taskId = args?.get("taskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "taskId required")
                    if (com.maxleveldetox.gamification.TasksEngine.reopenTask(context, taskId)) ok()
                    else err(ErrorCodes.INVALID_REQUEST, "Task not found")
                }
                "deleteTask" -> {
                    val taskId = args?.get("taskId") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "taskId required")
                    if (com.maxleveldetox.gamification.TasksEngine.deleteTask(context, taskId)) ok()
                    else err(ErrorCodes.INVALID_REQUEST, "Task not found")
                }
                "getBrainRotStatus" -> ok(com.maxleveldetox.monitor.BrainRotEngine.statusJson(context))
                "snoozeBrainRot" -> {
                    com.maxleveldetox.monitor.BrainRotEngine.snooze(context)
                    ok()
                }
                "getCompanionConfig" -> ok(companionCfg(context))
                "setCompanionConfig" -> {
                    val prefs = context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
                    (args?.get("name") as? String)?.let { prefs.edit().putString("name", it).apply() }
                    (args?.get("personality") as? String)?.let {
                        prefs.edit().putString("personality", it).apply()
                    }
                    (args?.get("roastMode") as? Boolean)?.let {
                        prefs.edit().putBoolean("roastMode", it).apply()
                    }
                    ok(companionCfg(context))
                }
                "getCompanionHistory" -> ok(JSONObject().apply {
                    put("messages", JSONArray(
                        context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
                            .getString("history", "[]")))
                })
                "appendCompanionMessage" -> {
                    val role = args?.get("role") as? String ?: "user"
                    val content = args?.get("content") as? String
                        ?: return err(ErrorCodes.INVALID_REQUEST, "content required")
                    appendCompanionMessage(context, role, content)
                    ok()
                }
                "clearCompanionHistory" -> {
                    context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
                        .edit().putString("history", "[]").apply()
                    ok()
                }
                "consumeOpenCompanion" -> {
                    val prefs = context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
                    val pending = prefs.getBoolean("openCompanionPending", false)
                    if (pending) prefs.edit().putBoolean("openCompanionPending", false).apply()
                    ok(JSONObject().apply { put("pending", pending) })
                }
                // v2.5.5 audit fix m-3: guard "Fix Now" notifications now
                // route to the permissions screen (was dropped silently).
                "consumeOpenPermissions" -> {
                    val prefs = context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
                    val pending = prefs.getBoolean("openPermissionsPending", false)
                    if (pending) prefs.edit().putBoolean("openPermissionsPending", false).apply()
                    ok(JSONObject().apply { put("pending", pending) })
                }

                // -----------------------------------------------------
                // Diagnostics (v2.2.1) — System Health + enforcement log
                // -----------------------------------------------------
                "getDiagLog" -> ok(JSONObject().apply {
                    put("lines", JSONArray(com.maxleveldetox.accessibility.DiagLog.drain()))
                })
                "getSystemReport" -> ok(buildSystemReport(app))

                // -----------------------------------------------------
                // Emergency
                // -----------------------------------------------------
                "emergencyCall" -> {
                    try {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_DIAL)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        ok()
                    } catch (e: Exception) {
                        err(ErrorCodes.SYSTEM_RESTRICTION, "Dialer unavailable")
                    }
                }

                // -----------------------------------------------------
                // Debug-only tools — IMPOSSIBLE in release builds.
                // -----------------------------------------------------
                "debugFastForward", "debugSimulateShorts", "debugResetData",
                "debugAwardDp", "debugResetProgress" -> {
                    if (!BuildConfigDebug.isDebug(context)) {
                        return err(ErrorCodes.SYSTEM_RESTRICTION, "Debug tools do not exist in release builds.")
                    }
                    when (method) {
                        "debugFastForward" -> {
                            val minutes = ((args?.get("minutes") as? Number)?.toInt() ?: 1).coerceIn(1, 60)
                            val s = app.stateRepo.blockingSession() ?: return err(ErrorCodes.SESSION_NOT_ACTIVE, "No session")
                            app.stateRepo.saveSession(s.copy(endElapsed = s.endElapsed - minutes * 60_000L))
                            SessionEngine.Broadcaster.emit()
                            ok()
                        }
                        "debugSimulateShorts" -> {
                            app.violationManager.shortsAttempt(
                                pkg = "com.instagram.android",
                                onWarning = { c, l -> SessionEngine.Broadcaster.emit(); },
                                onCage = { scope.launch { app.sessionEngine.activateCage() } },
                            )
                            ok()
                        }
                        "debugResetData" -> {
                            app.stateRepo.saveSession(null)
                            app.stateRepo.saveCage(CageSnapshot.INACTIVE)
                            SessionEngine.Broadcaster.emit()
                            ok()
                        }
                        "debugAwardDp" -> {
                            val amount = (args?.get("amount") as? Number)?.toInt() ?: 10
                            val reason = args?.get("reason") as? String ?: "SESSION_COMPLETED"
                            val parsed = try {
                                com.maxleveldetox.gamification.DpReason.valueOf(reason)
                            } catch (_: Exception) {
                                com.maxleveldetox.gamification.DpReason.SESSION_COMPLETED
                            }
                            app.progressEngine.debugAward(parsed, amount)
                            ok()
                        }
                        "debugResetProgress" -> {
                            app.progressEngine.debugReset()
                            ok()
                        }
                        else -> err(ErrorCodes.UNKNOWN, "unreachable")
                    }
                }

                // -----------------------------------------------------
                // v2.5.5 audit fix m-1: native telemetry drain
                // -----------------------------------------------------
                // AnalyticsOut accumulated native analytics events that
                // were never delivered anywhere (the queue just capped at
                // 200 and discarded). Flutter pulls the drained batch on
                // app start and before each offline-queue flush, then
                // forwards the events to the Worker through the same
                // /events pipeline as its own telemetry.
                "drainAnalyticsEvents" -> {
                    val drained = com.maxleveldetox.enforcement.AnalyticsOut.drain()
                    val arr = JSONArray()
                    for ((type, payload) in drained) {
                        arr.put(JSONObject().apply {
                            put("type", type)
                            put("payload", mapToJson(payload))
                        })
                    }
                    ok(JSONObject().put("events", arr))
                }

                else -> err(ErrorCodes.UNKNOWN, "Unknown method: $method")
            }
        } catch (e: Exception) {
            com.maxleveldetox.accessibility.DiagLog.logError("native:$method", e)
            err(ErrorCodes.UNKNOWN, e.message ?: "native error")
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun stateData(): JSONObject = JSONObject().put("state",
        mapToJson(buildStateJson() ?: emptyMap<String, Any?>()))

    // -----------------------------------------------------------------
    // v2.2.1 — System Health report: identity, permissions, storage,
    // services and errors. Everything a remote debugging session needs
    // from ONE screenshot (System Health screen renders this).
    // -----------------------------------------------------------------

    private fun buildSystemReport(app: MldApp): JSONObject {
        val report = JSONObject()

        // Identity + build.
        val pm = context.packageManager
        val pkgName = context.packageName
        val pkgInfo = try { pm.getPackageInfo(pkgName, 0) } catch (_: Exception) { null }
        report.put("package", pkgName)
        report.put("versionName", pkgInfo?.versionName ?: "?")
        report.put("versionCode", if (Build.VERSION.SDK_INT >= 28)
            pkgInfo?.longVersionCode ?: -1L else (pkgInfo?.versionCode ?: -1).toLong())
        report.put("targetSdk", pkgInfo?.applicationInfo?.targetSdkVersion ?: -1)
        report.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")

        // Accessibility: expected component vs. what Android actually has enabled.
        val expected = android.content.ComponentName(context,
            com.maxleveldetox.accessibility.DetoxAccessibilityService::class.java).flattenToString()
        val enabledServices = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (e: Exception) {
            com.maxleveldetox.accessibility.DiagLog.logError("readEnabledA11y", e)
            "<unreadable>"
        }
        report.put("expectedA11yComponent", expected)
        report.put("enabledA11yServices", enabledServices)

        // Permission snapshot (same source as the UI).
        report.put("permissions", app.permissionMonitor.snapshot().toJson())

        // DataStore read probe.
        val dataStoreOk = try {
            app.stateRepo.blockingOnboardingComplete()
            app.stateRepo.blockingPactAccepted()
            true
        } catch (e: Exception) {
            com.maxleveldetox.accessibility.DiagLog.logError("dataStoreProbe", e)
            false
        }
        report.put("dataStoreReadable", dataStoreOk)

        // Room read probe (immutable ledger balance — read-only).
        val roomProbe = try {
            val bal = kotlinx.coroutines.runBlocking { app.coinLedger.balance() }
            "ok (balance=$bal)"
        } catch (e: Exception) {
            com.maxleveldetox.accessibility.DiagLog.logError("roomProbe", e)
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
        report.put("roomProbe", roomProbe)

        // State stream liveness + bridge counters.
        report.put("streamPushes", streamPushes)

        // Dual-engine + guards.
        report.put("engineStatus", app.engineState.statusJson())
        report.put("guardsEnabled", app.engineState.areGuardsEnabled())

        // Session / cage projection (what native currently believes).
        val session = app.stateRepo.blockingSession()
        report.put("sessionActive", session != null && session.status.isEnforcing)
        report.put("sessionStatus", session?.status?.name ?: "NONE")
        val cage = app.stateRepo.blockingCage()
        report.put("cageActive", cage.active && !cage.isExpired(SystemClockNow.elapsed))

        // Shorts blocker state.
        report.put("shortsEnabled", app.stateRepo.blockingShorts().enabled)

        // Recent native errors + tail of the decision log.
        report.put("nativeErrors", JSONArray(com.maxleveldetox.accessibility.DiagLog.drainErrors()))
        report.put("recentLog", JSONArray(
            com.maxleveldetox.accessibility.DiagLog.drain().takeLast(40)))

        // v2.5.7 (K-5): persisted crash/error history (survives process death).
        report.put("lastCrash", com.maxleveldetox.monitor.CrashReporter.lastCrash(context)
            ?: JSONObject.NULL)
        report.put("persistentErrorLog", JSONArray(
            com.maxleveldetox.monitor.CrashReporter.errorLogTail(context)))

        return report
    }

    // -----------------------------------------------------------------
    // v2.5 r9 — companion (Sinthia) local config/history helpers.
    // -----------------------------------------------------------------

    private fun companionCfg(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: "Sinthia"
        val personality = prefs.getString("personality", null) ?: "balanced"
        val roast = prefs.getBoolean("roastMode", true)
        val interactions = prefs.getInt("totalInteractions", 0)
        // Relationship level grows with interactions (SS WaifuSettings).
        val relationship = (interactions / 20).coerceAtMost(5)
        return JSONObject().apply {
            put("name", name)
            put("personality", personality)
            put("roastMode", roast)
            put("totalInteractions", interactions)
            put("relationshipLevel", relationship)
        }
    }

    private fun appendCompanionMessage(context: Context, role: String, content: String) {
        val prefs = context.getSharedPreferences("mld_companion", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("role", role)
            put("content", content.take(2000))
            put("at", System.currentTimeMillis())
        })
        // Cap history at the last 200 messages (SS Room equivalent).
        while (arr.length() > 200) arr.remove(0)
        prefs.edit()
            .putString("history", arr.toString())
            .putInt("totalInteractions", prefs.getInt("totalInteractions", 0) + 1)
            .apply()
    }

    private fun mapToJson(map: Map<*, *>): JSONObject {
        val json = JSONObject()
        map.forEach { (k, v) ->
            when (v) {
                null -> json.put(k.toString(), JSONObject.NULL)
                is Map<*, *> -> json.put(k.toString(), mapToJson(v))
                else -> json.put(k.toString(), v)
            }
        }
        return json
    }

    private fun weeklyStats(app: MldApp): JSONObject {
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - 7L * 86_400_000))

        val rows = kotlinx.coroutines.runBlocking { app.database.dailyStatDao().since(since) }
        val focus = rows.sumOf { it.focusSeconds }
        val detox = rows.sumOf { it.detoxSeconds }
        val blocked = rows.sumOf { it.blockedAttempts }
        val completed = rows.sumOf { it.sessionsCompleted }
        val bailed = rows.sumOf { it.sessionsBailed }

        // Streak: consecutive days (ending today) with any focus/detox time.
        var streak = 0
        val byDate = rows.associateBy { it.dateKey }
        for (offset in 0..7) {
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Date(System.currentTimeMillis() - offset * 86_400_000))
            val row = byDate[key]
            if (row != null && (row.focusSeconds > 0 || row.detoxSeconds > 0)) streak++ else break
        }

        return JSONObject().apply {
            put("focusSeconds", focus)
            put("detoxSeconds", detox)
            put("blockedAttempts", blocked)
            put("sessionsCompleted", completed)
            put("sessionsPlanned", completed + bailed)
            put("streakDays", streak)
            put("dailyFocus", JSONArray(rows.map { it.focusSeconds }))
            put("dailyDetox", JSONArray(rows.map { it.detoxSeconds }))
        }
    }

    private fun requestBatteryException() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { /* optional permission */ }
            }
        }
    }
}

/** Debug-build detection: FLAG_DEBUGGABLE is set by the build system for
 * debug builds only — release builds physically cannot pass this check. */
object BuildConfigDebug {
    fun isDebug(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** Days parser helper for alarms. */
object ShockwaveAlarmDaysParser {
    fun parse(json: String): JSONArray = try {
        JSONArray(json)
    } catch (_: Exception) {
        JSONArray()
    }
}
