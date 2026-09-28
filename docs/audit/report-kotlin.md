# MAXLEVEL DETOX — Kotlin Enforcement Engine Audit Report

**Task ID:** 3-a · **Agent:** kotlin-auditor · **Mode:** READ-ONLY (no files modified)
**Scope:** `mobile/android/app/src/main/kotlin/com/maxleveldetox/**` (all 64 Kotlin files, line-by-line), `AndroidManifest.xml`, all `res/` XMLs, `app/build.gradle`, `proguard-rules.pro`. Cross-checked bridge method names against `mobile/lib/data/native_bridge.dart`.

## Summary Table

| Metric | Value |
|---|---|
| Kotlin files audited | 64 |
| Kotlin LOC covered | ~16,735 (100% of engine) |
| Manifest/res/gradle/proguard files | 365 + 12 XML + 1 layout + 1 drawable + build.gradle + proguard |
| **CRITICAL findings** | **3** |
| **MAJOR findings** | **12** |
| **MINOR findings** | **20** |
| Bridge method parity (Flutter ↔ Kotlin) | 93/93 methods match — full parity ✅ |

---

# CRITICAL FINDINGS

## C-1. [CRITICAL] Predictive-back opt-in breaks every "BACK is swallowed" enforcement surface — the alarm becomes dismissible by the back gesture
**File:** `AndroidManifest.xml:57` + `alarm/ShockwaveAlarmEngine.kt:415-417` + `enforcement/LockController.kt:410-416` + `monk/MonkModeOverlayActivity.kt:146-148` + `reels/ReelsOverlayActivity.kt:255-260` + `safety/SafetyPauseActivity.kt:130-132`

The manifest opts the app into the predictive back API while targeting SDK 35:
```xml
<application ... android:enableOnBackInvokedCallback="true" ...>
```
Every enforcement surface is a plain `android.app.Activity` (not `ComponentActivity`) whose only back defense is an empty/deprecated override, e.g. the alarm:
```kotlin
override fun onBackPressed() {
    // The alarm does not stop because the user pressed back (TRD §26).
}
```
With `enableOnBackInvokedCallback="true"` on Android 13+ (and system-wide on 14+, incl. the project's own vivo Android 16 test device), `Activity.onBackPressed()` is **never invoked** — the system default back behavior (finish the activity) runs instead because no `OnBackInvokedCallback` is registered. Consequences:
- **AlarmActivity**: back gesture dismisses the puzzle; `onDestroy` releases the `MediaPlayer` → the alarm is silenced **without a correct answer**, violating TRD §26 ("The alarm stops ONLY on a correct answer — never on app switches or screen changes").
- Cage wall (`LockScreenActivity`), monk lock surface, reels HARD cooldown, safety pause are all back-dismissible.
- The a11y `onKeyEvent` BACK consumption (`DetoxAccessibilityService.kt:464-476`) only runs while the `EnforcementWall` **overlay** is showing — it does not cover any of these activity surfaces.

**Fix:** Either (a) remove the opt-in — in `AndroidManifest.xml` change `android:enableOnBackInvokedCallback="true"` → `"false"` (legacy `onBackPressed()` resumes working); or (b) keep the opt-in and register a no-op callback in each enforcement activity, e.g. add to `AlarmActivity.onCreate`:
```kotlin
if (Build.VERSION.SDK_INT >= 33) {
    window.decorView.windowInsetsController // (placeholder; real fix below)
}
```
Concrete (b): in each of the 5 activities add
```kotlin
if (android.os.Build.VERSION.SDK_INT >= 33) {
    onBackInvokedDispatcher.registerOnBackInvokedCallback(
        android.window.OnBackInvokedCallback.PRIORITY_DEFAULT
    ) { /* swallow: enforcement surface */ }
}
```
(and unregister in `onDestroy`). Option (a) is the one-line, lowest-risk fix.

## C-2. [CRITICAL] Alarm repeat-days are interpreted with the wrong day-numbering scheme — alarms fire on the wrong days
**File:** `alarm/ShockwaveAlarmEngine.kt:106` (vs Flutter `mobile/lib/data/models.dart:539`)

The Flutter side stores/sends `repeatDays` as **ISO** numbers, documented and rendered as `1=Mon..7=Sun`:
```dart
final List<int> repeatDays; // 1=Mon..7=Sun (ISO), empty = one-shot
a.repeatDays.map((d) => ['Mon', 'Tue', ..., 'Sun'][d - 1])
```
The Kotlin scheduler matches them against `java.util.Calendar.DAY_OF_WEEK`, which is **SUNDAY=1 … SATURDAY=7**:
```kotlin
if (days.isEmpty() || days.contains(candidate.get(Calendar.DAY_OF_WEEK))) {
    return candidate.timeInMillis
}
```
So a user who picks "Mon–Fri" (`[1,2,3,4,5]`) gets alarms on **Sun–Thu**; "Sunday only" (`[7]`) fires on **Saturday**. Every weekday selection is shifted one day earlier. (By contrast, `LockScheduler` uses `java.time … dayOfWeek.value` (ISO) and `ScheduleEngine` explicitly converts to ISO (`ScheduleEngine.kt:150`) — those two are correct; only the alarm engine is wrong.)

**Fix:** convert the candidate to ISO before comparing:
```kotlin
val isoDow = ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1  // Mon=1..Sun=7
if (days.isEmpty() || days.contains(isoDow)) { return candidate.timeInMillis }
```

## C-3. [CRITICAL] Unguarded background foreground-service start in `MldApp.onCreate` — crash (and potential crash-loop) when the process is started in the background
**File:** `MldApp.kt:175-177` (and sibling risk at `MldApp.kt:169-171`)

```kotlin
// If a lock-my-phone session survived a process death, re-arm it
if (com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(this)) {
    com.maxleveldetox.lock.LockMyPhoneService.start(this)   // <-- no try/catch
}
```
`LockMyPhoneService.start()` calls `context.startForegroundService(intent)` with **no try/catch** (unlike `MonkModeLockService.start` two lines below, which is wrapped, and unlike `LockMyPhoneController.startSeconds` which wraps its own call at `LockMyPhoneService.kt:422-427`). On Android 12+ a process started in the background (e.g. a widget `APPWIDGET_UPDATE` broadcast or `InsightAlarmReceiver`) with a persisted lock-my-phone session gets `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`) thrown **on the main thread inside `Application.onCreate`** → hard crash of the process on every such start → effective crash loop until the session expires.
Same pattern: `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { sessionEngine.recoverIfNeeded() }` (`MldApp.kt:169-171`) can reach `EnforcementService.start(context)` (`SessionEngine.kt:223-230`, also unguarded) → uncaught coroutine exception → process crash. `BootRecoveryReceiver`'s coroutine likewise has `try/finally` but **no catch** (`BootRecoveryReceiver.kt:38-78`).

**Fix:** wrap both call sites:
```kotlin
if (com.maxleveldetox.lock.LockMyPhoneController.isSessionActive(this)) {
    try { com.maxleveldetox.lock.LockMyPhoneService.start(this) } catch (_: Exception) {}
}
```
and
```kotlin
CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
    try { sessionEngine.recoverIfNeeded() } catch (_: Exception) {}
}
```
Also add try/catch inside `EnforcementService.start`/`LockMyPhoneService.start` companions (mirroring `ForegroundAppMonitorService.start`, `ForegroundAppMonitorService.kt:410-429`, which already does this correctly).

---

# MAJOR FINDINGS

## M-1. [MAJOR] `setAppRule` bridge method is a complete no-op — the App Rules feature has no effect
**File:** `bridge/NativeBridge.kt:636-640`
```kotlin
"setAppRule" -> {
    // App rules influence FUTURE sessions; the active
    // session's policy snapshot stays authoritative (TRD §109).
    ok()
}
```
Flutter calls it with `{packageName, blocked}` (`native_bridge.dart:254`). Nothing is persisted; `getAppRules` (line 618-635) only recomputes categories from the static known-package lists. The App Rules UI is decorative.
**Fix:** persist per-package overrides (e.g. into the `APP_LIMITS`-style raw JSON in StateRepository with a new key `app_rules_raw`) and consult them in `PolicyEngine.evaluate` step 8 (and in `categoryOf`).

## M-2. [MAJOR] `DnsTamperDetector` is dead code — the documented Prime-mode Private-DNS tamper guard never runs
**File:** `guard/DnsTamperDetector.kt` — full file; grep confirms **zero call sites** (`DetoxAccessibilityService.onAccessibilityEvent` wires `ShadeGuard` and `UninstallInterceptor` only, `DetoxAccessibilityService.kt:98,110`).
**Fix:** in `DetoxAccessibilityService.onAccessibilityEvent`, after the ShadeGuard line add:
```kotlin
if (com.maxleveldetox.guard.DnsTamperDetector.maybeIntercept(this, event)) return
```
(also consider bounding `scrapeNode` like `UninstallInterceptor.appendNodeText`, see m-14).

## M-3. [MAJOR] `KioskController`, `TrapOverlay`, `ShortsDetector` are dead code — the documented screen-pinning "TRAP layer" never activates
**Files:** `enforcement/KioskController.kt` (only reference is `TrapOverlay.openDialer` → `unpinTemporarily()`, itself dead); `accessibility/TrapOverlay.kt` (never instantiated); `accessibility/ShortsDetector.kt` (superseded by `reels/ReelsDetector`, never referenced).
`KioskController.onActivityResumed/Paused/Destroyed/sync` are documented as "called from MainActivity" but `MainActivity.kt` never calls them — so `startLockTask()` kiosk pinning during DETOX sessions (v1.0.5 gap fix) does not exist at runtime.
**Fix:** either wire it (in `MainActivity.onCreate/onResume/onPause/onDestroy` call `KioskController.onActivityResumed(this)` etc., and call `KioskController.sync(app.stateRepo)` from `SessionEngine.startSession/finalizeLocked/recoverIfNeeded` + `TempUnlockManager.request/clear` as the class doc specifies), or delete the three files (~550 LOC of dead code that misleads auditors and maintainers).

## M-4. [MAJOR] All-day blocking schedules also block the following day
**File:** `enforcement/ScheduleEngine.kt:153-165`
```kotlin
return profiles.firstOrNull { p ->
    p.enabled && p.blockedPackages.isNotEmpty() &&
        (isoToday in p.days || isoYesterday in p.days) && when {
            p.startMinuteOfDay == p.endMinuteOfDay -> true   // all-day
            ...
```
For an all-day Monday profile (`start == end`, `days={1}`), on **Tuesday** `isoYesterday == 1 ∈ days` → the guard passes and the `true` branch fires → the profile is active all Tuesday as well. Every all-day schedule bleeds one day forward. (Compare `LockScheduler.activeWindow` which correctly uses date-anchored `[start, end)` windows, `lock/LockScheduler.kt:244-267`.)
**Fix:** make the all-day branch day-precise:
```kotlin
p.startMinuteOfDay == p.endMinuteOfDay -> isoToday in p.days
```

## M-5. [MAJOR] Engine-2 stand-down logic is inverted — duplicate enforcement while engine 1 is healthy, yield exactly in the 30–60s pre-stale gap
**File:** `monitor/ForegroundAppMonitorService.kt:287-305`
```kotlin
if (app.permissionMonitor.snapshot().accessibility && !app.engineState.isEngine1Stale()) {
    val healthyFor = System.currentTimeMillis() - (app.engineState.engine1HeartbeatMs())
    return healthyFor > YIELD_GRACE_MS     // yields when heartbeat is 30–60s OLD
}
```
`healthyFor` is the heartbeat **age**. While engine 1 is genuinely healthy and events flow (age < 20s), engine 2 **never yields** → permanent 850 ms polling, duplicate `LockController` walls and duplicate violation rows whenever engine 2 was started (blackout hand-off, permission-loss, `onUnbind`). When the device goes quiet for 30–60 s (no a11y events), engine 2 **stops** — including right after engine 1 has actually died (heartbeat frozen; between 30–60 s it is not yet "stale") — leaving a window where neither engine enforces until the 15-min guard job re-arms. This is the opposite of the comment's intent ("give engine 1 a grace period to prove sustained health").
**Fix:** yield only while engine 1 is freshly beating, e.g.:
```kotlin
if (app.permissionMonitor.snapshot().accessibility && !app.engineState.isEngine1Stale()) {
    return true   // engine 1 alive & fresh → engine 2 stands down immediately
}
```
or, to keep an anti-flap grace, track `engine1HealthySince` in `EngineStateStore` and return `now - engine1HealthySince > YIELD_GRACE_MS`.

## M-6. [MAJOR] `NativeBridge` leaks one `MainActivity` per activity recreation (never-removed Broadcaster listener)
**Files:** `bridge/NativeBridge.kt:54-74`, `enforcement/SessionEngine.kt:65-83`
```kotlin
fun attach(messenger: BinaryMessenger) {
    MethodChannel(messenger, CHANNEL).setMethodCallHandler(this)
    ...
    SessionEngine.Broadcaster.addListener { pushState() }   // never removed
}
```
`MainActivity.configureFlutterEngine` constructs a **new** `NativeBridge(this)` on every activity recreation (rotation, dark-mode, `launchMode=singleTop` re-delivery). `Broadcaster` has `addListener` but **no `removeListener`**, and each stale bridge holds its `context` (= the old `MainActivity`) via the listener closure → unbounded listener list + leaked activities + duplicated `pushState()` work.
**Fix:** add `fun removeListener(l: () -> Unit)` to `Broadcaster`; have `NativeBridge` implement cleanup (`detach()` calling `Broadcaster.removeListener(...)` + `scope.cancel()`), invoked from `MainActivity.cleanUpFlutterEngine`/`onDestroy`; or make the bridge a process-level singleton bound to `applicationContext`.

## M-7. [MAJOR] Main-thread blocking I/O cluster (runBlocking DataStore/Room on the UI thread)
**Files (worst offenders):**
- `bridge/NativeBridge.kt:205` — `kotlinx.coroutines.runBlocking { app.coinLedger.balance() }` inside `buildStateJson()`, which runs on the **main thread** whenever `SessionEngine.Broadcaster.emit()` is called from `MainActivity.onResume/onNewIntent` (`MainActivity.kt:30-43`); `coinLedger.balance()` may fall through to a **Room query** (`CoinLedger.kt:29-36`) → main thread blocks on disk.
- `widgets/MldWidgets.kt:168,176,198` — `runBlocking { app.database.dailyStatDao().byKey(...) }` / `runBlocking { app.coinLedger.balance() }` inside widget `onUpdate` (broadcast → main thread); `BrainRotWidgetProvider` additionally triggers a full-day `UsageStatsManager.queryEvents` scan via `BrainRotEngine.statusJson` on main.
- `alarm/ShockwaveAlarmEngine.kt:213-215` — `runBlocking { it.database.alarmDao().byId(alarmId) }` in `AlarmActivity.onCreate` (main thread; also `MediaPlayer.prepare()` at line 379).
- `enforcement/LockController.kt:371-399` + `overlay/EnforcementWall.kt:327-370` — 1-second tickers on the main `Handler` call `stateRepo.blockingSession()/blockingCage()` (runBlocking DataStore reads) every second while surfaces are up; plus `reassertRunnable` every 350 ms (`LockController.kt:425-453`).
- `accessibility/DetoxAccessibilityService.kt:148,317,320` + `enforcement/PolicyEngine.kt:93,111,122,127` — the a11y hot path performs up to ~6 blocking DataStore reads per foreground event on the service's main thread (mitigated after first load because DataStore caches in memory, but each pending `edit` serializes behind these reads and every rebase path **writes** from the read accessor, `StateRepository.kt:174-191`).
**Impact:** jank and ANR exposure on low-end devices (the project's own target market), especially the widget path and `MainActivity.onResume`.
**Fix (pattern):** never call the `blocking*` accessors or `runBlocking` from the main thread — post widget updates through `WidgetUpdater.updateAll`'s existing `Dispatchers.Default` scope by moving the `runBlocking` calls into `scope.launch` (compute `RemoteViews` off-thread, then `appWidgetManager.updateAppWidget`); in `NativeBridge.buildStateJson`, replace the `runBlocking { coinLedger.balance() }` with the cached `stateRepo.blockingCoinBalance()` or make `pushState` async; in `AlarmActivity`, load difficulty in a coroutine and apply to a default UI. For the tickers, cache the remaining seconds and refresh from a background scope.

## M-8. [MAJOR] `finalizeLocked` records `cageTriggered = false` for every session (read-after-clear)
**File:** `enforcement/SessionEngine.kt:437-460`
```kotlin
stateRepo.saveSession(null)
stateRepo.saveCage(CageSnapshot.INACTIVE)          // cage cleared HERE (line 438)
tempUnlockManager.clear()
...
cageTriggered = stateRepo.blockingCage().active,   // read AFTER clearing (line 458)
```
The history row's `cageTriggered` is therefore always `false`, corrupting the session history / insights ("cage ever triggered") data the Flutter History screen renders.
**Fix:** capture before clearing:
```kotlin
val cageWasActive = stateRepo.blockingCage().active   // before saveCage(INACTIVE)
...
cageTriggered = cageWasActive,
```

## M-9. [MAJOR] Google Ads manifest meta-data uses the public SAMPLE App ID — release builds ship a broken/policy-violating ads configuration
**File:** `AndroidManifest.xml:70-72`
```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-3940256099942544~3347511713" />  <!-- sample -->
```
The comment itself says "Sample app id; replace per flavor before release." With the sample ID, rewarded ads fail (or serve test inventory) in production → the only coin faucet (`awardAdCoin`) is dead in release, and AdMob policy prohibits shipping test IDs.
**Fix:** inject the real AdMob App ID (per build flavor / `local.properties`) before the release build; fail the build if the value still starts with `ca-app-pub-3940256099942544`.

## M-10. [MAJOR] `MonkModeLockService` audio-mode listener is never removed and re-registered on every start
**File:** `monk/MonkModeLockService.kt:284-317` (called from `startEngines()` at 128-134, which runs on every non-stop `onStartCommand`, incl. sticky restarts and `ServiceRevival` restarts)
```kotlin
am.addOnModeChangedListener(mainExecutor) { mode -> ... }   // no guard, no removeOnModeChangedListener
```
`registerScreenReceiver` is guarded (`if (screenReceiver != null) return`) but the audio listener is not, and `onDestroy` never calls `am.removeOnModeChangedListener`. Result: N listeners after N restarts (each holding the dead service instance), duplicate call-mode transitions, and memory pressure during long monk sessions with OEM restarts.
**Fix:** store the listener in a field, guard registration like `screenReceiver`, and remove it in `onDestroy`:
```kotlin
private var audioListener: AudioManager.OnModeChangedListener? = null
// register: if (audioListener == null) { audioListener = AudioManager.OnModeChangedListener { ... }; am.addOnModeChangedListener(mainExecutor, audioListener!!) }
// onDestroy: audioManager?.removeOnModeChangedListener(audioListener); audioListener = null
```

## M-11. [MAJOR] Reels caption-keyword allowlist (`whitelistedKeywords`) is never populated — documented user setting is dead
**Files:** `reels/ReelsDetector.kt:52` (`var whitelistedKeywords: List<String> = emptyList()`), consumed at `accessibility/DetoxAccessibilityService.kt:349` (`if (reelsDetector.isWhitelisted(event)) return`). No bridge method or settings store ever writes it, so the check is always `false`.
**Fix:** either add a bridge method (`setReelsWhitelist(keywords: List<String>)`) persisting to StateRepository and loading into the detector on service connect, or remove the field + call (and the doc claims).

## M-12. [MAJOR] `SinthiaCheckIn.tick` runs two full-day UsageStats scans every 5 seconds while engine 2 is up
**Files:** `gamification/SinthiaCheckIn.kt:63-99` (calls `app.usageTracker.todayUsage()` at lines 68 **and** 84 — each is a `queryEvents(sinceMidnight, now)` full-day iteration), scheduled from the engine-2 slow tick (`monitor/ForegroundAppMonitorService.kt:149-153`, every 5 000 ms).
Combined with the 850 ms poll and `BrainRotEngine.distractingMinutes` (60 s cache), engine 2 does ~17 280 full-day usage scans/day while active → significant battery/CPU cost on the low-end OEM devices this app targets.
**Fix:** cache the hour result per hour-of-day (like `lastHour` — return early before querying when `hour == lastHour` can't have changed), and reuse a single `todayUsage()` result for both total and distracting sums:
```kotlin
val rows = app.usageTracker.todayUsage()
val totalMinutes = rows.sumOf { it.minutesToday }
val distracting = rows.filter { app.policyEngine.isDistracting(it.packageName) }.sumOf { it.minutesToday }
```
Also early-bail before any query: `if (lastDate == today && hour <= lastHour) return` already exists — move a cheap elapsed-time estimate before the usage query (e.g., only re-query at most once per 5 minutes).

---

# MINOR FINDINGS

## m-1. [MINOR] `AnalyticsOut` queue is never drained — native analytics events go nowhere
`enforcement/SessionEngine.kt:633-648`; grep shows no `AnalyticsOut.drain()` call site (the Flutter side has no bridge method for it). Events accumulate to the 200 cap and are discarded. **Fix:** add a bridge method `drainAnalytics` and call it from Flutter periodically, or delete the object.

## m-2. [MINOR] Notification ID collisions across subsystems
`guard/GuardNotifier.kt:27-28` uses `NID_BLACKOUT = 2001` / `NID_PERMISSION = 2002` — identical to `SessionEngine.NOTIF_COMPLETION = 2001` / `NOTIF_CAGE = 2002` (`SessionEngine.kt:614-615`). A guard alert silently replaces (or is replaced by) a completion/cage notification. **Fix:** renumber the guard IDs (e.g. 2401/2402).

## m-3. [MINOR] Guard "Fix Now" notification extra mismatch — tapping never routes to the permissions screen
`guard/GuardNotifier.kt:81` puts `putExtra("route", "permissions")`, but `MainActivity.routeIntentExtras` (`MainActivity.kt:47-54`) reads `getStringExtra("openRoute")`. **Fix:** change the extra key to `"openRoute"` (and route value `"permissions"` if the Flutter splash router supports it; today only `"companion"` is consumed).

## m-4. [MINOR] Dead identical branches (copy-paste smells)
`enforcement/SessionEngine.kt:151-152`: `if (mode == SessionMode.STUDY) "SESSION_STARTED" else "SESSION_STARTED"` — the branch was presumably meant to distinguish `STUDY_STARTED`/`DETOX_STARTED`. Same pattern at `enforcement/LockController.kt:259` (`if (hardMode) "ACCESS BLOCKED" else "ACCESS BLOCKED"`). **Fix:** emit `"SESSION_STARTED_STUDY"` vs `"SESSION_STARTED_DETOX"` (or drop the conditional); drop the title conditional in LockController.

## m-5. [MINOR] `EnforcementWall.hideInternal` early-returns when unbound → `isShowing()` stays `true` after `unbind()`
`overlay/EnforcementWall.kt:411-425`: `val svc = serviceRef ?: return` fires before the state fields are cleared, so after `unbind()` (service death) `overlay != null` persists and `isShowing()` lies (affects `DetoxAccessibilityService.onKeyEvent` gating and `ShadeGuard` checks until the next show/hide cycle). **Fix:** clear `overlay/overlayPkg/overlayKind` and stop the ticker before the `serviceRef` bail-out (the system already removed the window when the service died).

## m-6. [MINOR] Wall-clock authority is clock-tamper-soft for Lock-My-Phone / Monk / Prime
`lock/LockMyPhoneService.kt:431-437`, `monk/MonkModeManager.kt:125-129`, `prime/PrimeCommitManager.kt:151-152` all use `System.currentTimeMillis()`. Moving the clock forward past `endWallMs` ends the lock early (a user-facing bypass); moving it back extends it. Documented trade-off (reboot survival), and `LockScheduleReceiver` re-evaluates on `TIME_SET` for scheduled windows only — manual sessions are not re-checked. **Fix (hardening):** persist both `endWallMs` and `endElapsed`+bootCount (the `StateRepository.stampClocks` pattern) and take `max(remaining)` of both authorities.

## m-7. [MINOR] `CoinLedger.balance()` can serve a stale positive cache
`coins/CoinLedger.kt:29-36`: `if (cached > 0) return cached` — the Room ledger is only re-read when the cached value is 0; any balance change that bypasses `saveCoinBalance` (external DB edit, restore, a future code path forgetting the cache update) is invisible while cached > 0. **Fix:** always recompute (the SUM is cheap) or add a TTL/version stamp.

## m-8. [MINOR] `SessionEngine.generateSessionId` uses `String.random()` (java.util.Random)
`enforcement/SessionEngine.kt:605-609`. Not security-relevant (display id), but inconsistent with the security posture elsewhere (`UUID.randomUUID` in `ScheduleEngine`/`TasksEngine`, `SecureRandom` in `EmergencyCodeManager`). **Fix:** `java.util.UUID.randomUUID().toString().take(6)`.

## m-9. [MINOR] `PrimeCommitManager.postCommitFinishedNotification` never calls `ensureChannel()`
`prime/PrimeCommitManager.kt:283-299`. After a process restart (commit finishing via sweep after process death), the `mld_prime` channel may not exist → notification silently dropped on API 26+. **Fix:** call `ensureChannel()` first (as `postCommitStartedNotification` does).

## m-10. [MINOR] Unused `scope` in `LockScreenActivity`; un-cancelled `scope` in `ReelsOverlayActivity`
`enforcement/LockController.kt:142` (never used), `reels/ReelsOverlayActivity.kt:43` (used by unlock buttons but never cancelled in `onDestroy` — coroutines can complete after destroy). **Fix:** delete the former; add `scope.cancel()` in `onDestroy` for the latter.

## m-11. [MINOR] `AppLimitEngine` trivia
`enforcement/AppLimitEngine.kt:37` `lastNotifiedDay` never read/written (dead field); `:175-176` comment claims "one-minute slack" but `>=` gives exactly-on-the-minute semantics. **Fix:** delete the field; fix the comment.

## m-12. [MINOR] `NativeBridge` cross-thread fields lack `@Volatile`
`bridge/NativeBridge.kt:47-48,152-153` — `eventSink`, `tickerActive`, `billingSink`, `billingFlowStarted` are written on the main thread (`onListen/onCancel`) and read from `Dispatchers.Default` (ticker/billing collector) without synchronization → benign today (worst case a duplicated/missed 1 s push) but a latent race. **Fix:** mark them `@Volatile` and route writes through `mainHandler`.

## m-13. [MINOR] `shorts!!.toJson(...)` non-null assertion inside a try
`bridge/NativeBridge.kt:200` — `shorts` is nullable (`blockingShorts()` never returns null in practice, but the `!!` throws NPE if it ever does; the catch converts it to `null`). **Fix:** `shorts?.toJson(...) ?: JSONObject()`.

## m-14. [MINOR] Unbounded node scrape in (currently dead) `DnsTamperDetector`; main-thread node walks in `UninstallInterceptor`/`ReelsDetector`
`guard/DnsTamperDetector.kt:100-111` recurses to depth 30 with **no node cap** (unlike `UninstallInterceptor`'s 220-node budget). If wired per M-2 this becomes a main-thread stall on large settings screens. `UninstallInterceptor.scanActiveWindow` (300 ms throttle, 220 nodes) and `ReelsDetector.bfsScan` (500 nodes) run on the a11y main thread — bounded but non-trivial every 300 ms during settings browsing. **Fix:** port the `budget` pattern from `UninstallInterceptor` into `DnsTamperDetector.scrapeNode`; consider dispatching deep scans off the main thread.

## m-15. [MINOR] Inconsistent violation `severity` casing
`monitor/ForegroundAppMonitorService.kt:273-274` and `guard/UninstallInterceptor.kt:195` emit `"high"/"medium"` while every other call site emits `"HIGH"/"MEDIUM"/"LOW"` (e.g. `DetoxAccessibilityService.kt:197`). Any severity-based UI filtering misses the lowercase rows. **Fix:** uppercase the two sites.

## m-16. [MINOR] `stop`-by-`startService` pattern is background-fragile
`enforcement/EnforcementService.kt:232-237`, `lock/LockMyPhoneService.kt:475-480` (`stopValidated`), `monitor/ForegroundAppMonitorService.kt:431-436` stop services via `context.startService(intent.setAction(ACTION_STOP))`. On API 26+ this throws `IllegalStateException` if the app is in the background and the target service is **not** already running (double-stop, or stop after OEM killed the service). All current call sites are foreground/FGS-alive contexts, so latent. **Fix:** prefer `context.stopService(Intent(...))` for a hard stop, or wrap in try/catch.

## m-17. [MINOR] `QUICKBOOT_POWERON` in the exported boot receiver is a spoofable non-protected broadcast
`AndroidManifest.xml:214`. Any app can broadcast it (it is not on the protected list on all ROMs) → triggers `recoverIfNeeded` + service re-arms. No data exposure (worst case: FGS starts). **Fix:** acceptable to keep for HTC/legacy ROMs, but verify `intent.action` provenance is irrelevant (state re-read makes it idempotent), or drop it.

## m-18. [MINOR] Release build silently falls back to the debug signing key when `key.properties` is absent
`app/build.gradle:76-80`. A release APK could be signed with the debug key without anyone noticing. **Fix:** `fail` the release build when the keystore is missing (or gate on an explicit `-PallowDebugSigning` flag).

## m-19. [MINOR] `MldApp.get()` can throw `UninitializedPropertyAccessException`
`MldApp.kt:200-201` — `context.applicationContext as? MldApp ?: instance` dereferences the lateinit `instance` if called before `onCreate` completes (e.g., a future ContentProvider). **Fix:** guard with `::instance.isInitialized` and throw a descriptive error otherwise.

## m-20. [MINOR] FSI permission not re-checked on Android 14+; `USE_FULL_SCREEN_INTENT` may be denied
`alarm/ShockwaveAlarmEngine.kt:139-165` posts a full-screen-intent notification for the screen-off alarm path without checking `NotificationManager.canUseFullScreenIntent()` (Android 14+ revokes it by default for non-alarm/calendar categories). The direct `startActivity` fallback throws (background launch) and is swallowed → with screen off and FSI denied, the alarm may only surface as a heads-up notification. **Fix:** check `canUseFullScreenIntent()`; if false, direct the user to grant it from the permission screen and/or use a high-importance alarm-channel notification with `setCategory(CATEGORY_ALARM)`.

---

# FEATURE-FLOW MAP (end-to-end)

## 1. App startup
`MldApp.onCreate` (main): builds `MldDatabase` (Room `mld.db`, migrations 1→2→3) → `StateRepository` (DataStore `mld_enforcement`, all accessors `runBlocking`-based with r9.4 reboot rebasing via `endWall`+`savedElapsed`+`bootCount` stamps) → `RuntimeConfig` (clamped remote-config cache) → engines/managers wired (see below) → `billing.connect()` (async, restores purchases; entitlements stay server-verified) → `InsightNotifier.scheduleDailyCheck()` (exact alarm at 20:05 default) → registers widget updater on `SessionEngine.Broadcaster` + initial `WidgetUpdater.updateAll` → `EngineStateStore` guard job if enabled → background `recoverIfNeeded()` (C-3 crash path) → re-arm lock-my-phone / lock schedules / monk. `MainActivity` (launcher, singleTop, exported) attaches `NativeBridge` (channel `com.maxleveldetox/native`, streams `statestream` + `billing`) on every engine configuration (M-6 leak); `onResume` runs `PermissionMonitor.checkAndReact()` + `Broadcaster.emit()` + routes `openRoute` extras (only `"companion"` supported).

## 2. MethodChannel bridge
93 methods, 1:1 parity with `native_bridge.dart` (verified by name — list below). Every call is dispatched on `Dispatchers.Default` inside a per-call coroutine; responses use the `{success, data|errorCode, message}` envelope; `debug_*` gated by `FLAG_DEBUGGABLE` (release-safe). Handled methods: completeOnboarding, acceptPact, startSession, pauseSession, resumeSession, getStudySubjects, addStudySubject, removeStudySubject, stopSession, validateBailout, executeBailout, getEngineStatus, getGuardStatus, requestDeviceAdmin, getDeviceAdminState, startLockMyPhone, getLockMyPhoneStatus, getLockSchedules, saveLockSchedule, deleteLockSchedule, stopLockMyPhoneValidated, getReelsStatus, setReelsDailyLimitMinutes, useReelsEmergencyPass, activateMonkMode, getMonkModeStatus, setSafetyPauseEnabled, setSafetyPauseApps, setSafetyPauseSeconds, getSafetyPauseStatus, enrollEmergencyCodes, getEmergencyCodeStatus, activatePrimeCommit, giveUpPrimeCommit, getPrimeCommitStatus, requestTempUnlock, awardAdCoin, getCoinTransactions, setShortsEnabled, setShortsPlatform, getAppRules, setAppRule (no-op — M-1), getAppLimits, setAppLimit, getSchedules, saveSchedule, deleteSchedule, getNotificationGuard, setNotificationGuardEnabled, getPermissionState, openPermissionSettings, requestIgnoreBatteryOptimizations, openOemBackgroundSettings, getUsageStats, getHistory, getWeeklyStats, getViolations, scheduleAlarm, cancelAlarm, getAlarms, applyRemoteConfig, getProgress, claimCheckIn, getDpHistory, getRelapseHistory, getBreakPassStatus, useBreakPass, endBreakPassEarly, getBillingProducts, launchPurchase, restorePurchases, getWidgetStatus, pinWidget, updateWidgets, deliverAnnouncements, checkDailyInsight, getTasks, addTask, addSubtask, toggleSubtask, completeTask, reopenTask, deleteTask, getBrainRotStatus, snoozeBrainRot, getCompanionConfig, setCompanionConfig, getCompanionHistory, appendCompanionMessage, clearCompanionHistory, consumeOpenCompanion, getDiagLog, getSystemReport, emergencyCall, debugFastForward, debugSimulateShorts, debugResetData, debugAwardDp, debugResetProgress.

## 3. Session lifecycle (Study/Detox)
`startSession` (mutex): permission precheck (a11y+usage+overlay), one-session gate, pact gate (bridge level), snapshot persisted FIRST (elapsedRealtime authority + wall-clock/bootCount stamps), `violationManager.clearSessionViolations` set, `EnforcementService.start` (specialUse FGS, START_STICKY, 30 s sweep: lazy completion, cage release, prime reconcile, progress tick, insight check, permission check when enforcing, guard eval every 4th sweep, backoff-gated restarts with clean-run reset), end alarm (`setExactAndAllowWhileIdle` when `canScheduleExactAlarms`, else `setWindow`), `Broadcaster.emit`. Completion: lazy (`evaluateAndMaybeComplete` on every access + sweep + alarm) — only when `elapsed >= endElapsed`; `finalizeLocked` persists terminal state first, stops service/alarms, writes history + daily stats + study-subject seconds, fires notifications/progress hooks. `stopSession`/`requestStop` honored only past end time. Study break: STUDY-only, ≤3 breaks, ≤10 min, blocked during prime/cage/unlock, pauses the end-clock (frozen `remainingSeconds`), auto-resume alarm, reboot ends the break. Bailout: `executeBailout` spends `bailoutCoins` atomically (mutex + Room insert + verified balance) before `finalizeLocked(BAILOUT)`; refused during Prime. Reboot: DataStore rebasing (`rebaseSessionJson` maps remaining wall-clock onto new uptime; temp unlocks die at reboot; cages survive their wall-clock remainder) — solid design, correctly implemented.

## 4. Accessibility detection → blocking (engine 1)
`DetoxAccessibilityService` (TYPE_WINDOW_STATE_CHANGED + CONTENT_CHANGED, 200 ms system throttle): per-pkg 800 ms foreground throttle → heartbeat (≤20 s) → `ShadeGuard.maybeCollapse` (SystemUI window → BACK unless denylisted/grace/emergency) → `UninstallInterceptor.maybeIntercept` (settings scrape during enforcing sessions, 2 strikes → lock wall) → always-on surfaces (schedules/app-limits — engine-independent) → `PolicyEngine.evaluate` (order: emergency → system essentials (static+dynamic launcher/IME cache) → cage → monk → settings-grace → session allowlist/temp-unlock → blocked categories → strictness default) → BLOCK: `GLOBAL_ACTION_HOME` → escalation counter (3 in 60 s → HARD wall) → `EnforcementWall` a11y overlay (TYPE_ACCESSIBILITY_OVERLAY, above system bars; gated 10 s dismissal; cage/shorts-lockout variants; BACK/APP_SWITCH consumed via `onKeyEvent` while showing; emergency dialer 90 s stand-down) with fallbacks `LockController` activity wall (overlay-permission present) → `A11yOverlayController` (no overlay permission) → violation row. ALLOW: reconcile stale surfaces + safety-pause hook.

## 5. Engine 2 (ForegroundAppMonitorService) + guards
Started on a11y unbind/destroy, blackout (heartbeat >60 s stale), permission loss, or always-on rules with dead a11y (screen on). Polls usage events every 850 ms; enforces via `PolicyEngine` + `LockController` (SAW permission enables background activity starts); slow 5 s tick: ServiceRevival (lock/monk resurrect), AutoReblockMonitor (temp-unlock expiry re-assert), SinthiaCheckIn (M-12), BrainRot HUD, task routine resets; self-standdown via (inverted — M-5) `shouldYield`. `AccessibilityGuardJobService` (id 9401, periodic 15 min, persisted) runs `GuardEvaluator` (cases: idle-healthy / permission-lost / blackout / healthy; engine-2 takeover + guard notifications with 5-min cooldowns; self-disable after 3 clean days, re-armed on incidents; never during lock/monk sessions).

## 6. Reels/shorts flow
Content events for `ReelsDetector.SUPPORTED_PACKAGES` (300 ms/pkg scan throttle) → `blockingShorts` gate → in-session path (warning ladder via `ViolationManager.shortsAttempt`, shared daily counter, `shortsWarningCount` (3..7) warnings → `activateCage`: 30-min default cage, supersedes temp unlock, cage alarm + notification + CAGE_SURVIVED DP on survive) and out-of-session path (`ReelsEscalationManager`: count 1 → toast, 2 → SOFT overlay (1/2-min allowance unlocks, 3 emergency passes/day, 30-min default daily allowance), ≥3 → HARD wall/overlay with 10 s countdown → counter clear + ringtone + notification + `killBackgroundProcesses`). Unblock windows are elapsedRealtime-scoped per package (reboot-safe expiry).

## 7. Shockwave alarm
Bridge `scheduleAlarm` (validated: hour 0-23, minute 0-59, difficulty whitelist, repeatDays passthrough — **C-2 day-numbering bug**) → Room `alarms` table → `scheduleNext` (`setAlarmClock` when exact allowed, else window) → `AlarmReceiver` (full-screen-intent notification + direct activity launch; goAsync reschedule) → `AlarmActivity` (puzzle by difficulty; MediaPlayer alarm-stream looping; stops only on correct answer — broken by C-1 back dismissal).

## 8. Coin economy
`CoinLedger`: append-only `coin_transactions` (Room); `awardAd` idempotent via deterministic PK `coin_<rewardKey>` + INSERT OR IGNORE (regex-validated key); `spend` under mutex with balance check before insert; DataStore `coin_balance_cache` mirror; no Flutter-reachable free faucet ✅. Faucets: ads (M-9 sample App ID breaks this in release). Spends: temp unlock (5 coins default), bailout (500 default), lock-my-phone bailout (500). No HMAC/checksum on the ledger (root tamper possible) — acceptable on-device trust model, noted.

## 9. Temp unlock → auto-reblock
`requestTempUnlock` (mutex; gates: enforcing session, no prime, no cage, no live window, packages ⊆ UNLOCKABLE_PACKAGES {camera2, photos, chrome}, coin spend first) → snapshot persisted (elapsedRealtime + stamps) → `AutoReblockMonitor.snapshotUnlockStart` → expiry: lazy on every policy read + engine-2 5 s tick (clear + engine restart). Alarm `ACTION_TEMP_UNLOCK_END` handled in `EnforcementReceiver` but never scheduled (lazy-only by design — promptness gap ≤ engine-2 tick / next policy evaluation).

## 10. Monk mode
`activate` (pact+admin gates at bridge; 1..720 min; SP-persisted `endWallMs`) → `MonkModeLockService` (specialUse FGS, START_STICKY): FSM LOCKED/ALLOWED_APP; 1.5 s/800 ms main-thread ticks; `lockNow` loop + `MonkModeOverlayActivity` surface; UsageEvents policing (self/allowlist/default-launcher/system-essentials/dialer-during-call); audio-mode listener for automatic call exemption (M-10 leak); screen receiver re-asserts; expiry via 30 s wall-clock check → `deactivate` (+ DP/monkCompletions on expiry); guard job 9402 (15 min persisted); no give-up flow (by design). Boot/process-start recovery via BootRecoveryReceiver + MldApp.

## 11. Lock-my-phone
Bridge `startLockMyPhone` (pact + admin gates, 1..480 min) or `LockScheduler` (weekly/once windows, SP-persisted; live-window lock against edits/deletes; user-stop suppression after paid bailout; TIME_SET/TIMEZONE re-eval) → `LockMyPhoneController.startSeconds` (wall-clock session in SP `mld_lock_phone` + guards re-armed + `LockScheduler.rearm`) → `LockMyPhoneService`: 1.5 s `dpm.lockNow()` loop while interactive (call-mode exemption via audio mode + 2-strike off-call detection), SCREEN_ON/USER_PRESENT instant re-lock, attempts counter, `onTaskRemoved` alarm revival, FGS-timeout degrade. Exits: wall-clock expiry or `stopLockMyPhoneValidated` (bridge: coin balance check → atomic spend → `markEnded("bailout")` → `stopValidated` action). Admin stripped → `onAdminStripped` flag + recovery escalation (MldDeviceAdminReceiver.onDisabled).

## 12. Emergency codes (TOTP)
`enroll` (SecureRandom 16-char Base32 = 80-bit key, shown once, otpauth URL, 300 s step, SHA1, 6 digits) → `verify` (mutex; ±1 step tolerance; per-purpose+code replay ledger, 20-min burn, pruned ×2) → used by Prime give-up. No hardcoded/shared master secret ✅ (explicitly contrasted with the reference app's extracted backdoor).

## 13. Prime commit
`activate` (pact + TOTP enrolled gates; 1..24 h) → owns a MAXLEVEL DETOX session via normal SessionEngine + `PrimeState` (active, sessionId, endWallMs) → refuses temp unlock + bailout (checked in both managers/engine) → exits: expiry (`reconcileIfNeeded` on sweep/boot/bridge — success notification + DP; M-9 channel bug) or `giveUp` (TOTP verify → relapse violation BEFORE ending → `finalizeForPrimeGiveUp` → relapse record + streak reset + notifications).

## 14. Boot recovery
`BootRecoveryReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON): `recoverIfNeeded` (session re-arm / legitimate completion; cage alarm; DataStore rebasing), alarms re-scheduled, guard job re-armed, lock-my-phone + lock schedules + monk re-armed, prime reconciled. `EnforcementReceiver`: SESSION_END / CAGE_END / TEMP_UNLOCK_END actions from SessionEngine alarms.

## 15. Widgets
5 providers (streak/session/usage/coins/brainrot), shared `widget_stat` layout, 30-min `updatePeriodMillis`, tap → launch intent; `WidgetUpdater.updateAll` on every `Broadcaster.emit` + daily insight alarm; reflective instantiation guarded by the global ProGuard keep. Main-thread `runBlocking` reads in `onUpdate` (M-7).

## 16. Billing
`BillingManager` (Play Billing 7.1.1): launcher-only; 4 frozen SKUs; purchases acknowledged best-effort and emitted to Flutter via `com.maxleveldetox/billing` EventChannel for **server-side** verification; restore on connect + explicit `restorePurchases`. No local entitlement authority ✅.

## 17. Progress layer (DP/streaks)
`ProgressEngine` single-writer (mutex + DataStore JSON mirrors + Room `dp_awards`/`relapse_events` ledger with init self-heal): capped awards with class multipliers, daily/weekly/monthly caps, one-time feature flags, 30 s REELS_BLOCKED anti-farm throttle; streak rollover (clean/missed/broke day semantics, 2 freezes/month auto-consumed, milestones uncapped), 24 h recovery grace on protection loss, relapse on prime give-up/grace expiry; hooks from all enforcement paths (failures never affect enforcement ✅). `TasksEngine` (SP JSON): tasks/subtasks/routines, daily routine reset, discipline combo; `SinthiaCheckIn` hourly roast notifications (M-12); `GamificationNotifier` respectful-tone posts.

## 18. Notification guard
`NotificationBlockerService` (NotificationListenerService, user-granted access): cancels non-emergency notifications during monk (allowlist-based) or DETOX+toggle-on; never our own; `MonkModeNotifications` allowlist assembled from policy engine + monk allowlist + launchers.

---

# SECURITY ASSESSMENT SUMMARY

**Strong (as designed):**
- No hardcoded secrets anywhere; emergency exit is per-installation SecureRandom TOTP with replay guard (`unlock/EmergencyCodeManager.kt`) — the reference app's master-code backdoor was explicitly not ported. ✅
- Flutter input is treated as requests; every enforcement decision re-derived from persisted native state (bridge hard-gates: pact, admin, ranges, regex-validated `rewardKey`, size-capped announcement payload). ✅
- `debug_*` bridge methods gated on `FLAG_DEBUGGABLE` — physically absent in release. ✅
- Coin ledger: append-only, idempotent ad awards, mutex-guarded spends, no free faucet method. ✅ (root-tamper not defended — accepted trust model)
- elapsedRealtime authority + wall-clock/bootCount rebasing defeats clock-change extension of sessions/cages and reboot-based unlock persistence. ✅
- Manifest: correct permission sets (`BIND_ACCESSIBILITY_SERVICE`, `BIND_JOB_SERVICE`, `BIND_DEVICE_ADMIN`, `BIND_NOTIFICATION_LISTENER_SERVICE` on the right components; `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `FOREGROUND_SERVICE_SPECIAL_USE` + subtype properties declared; no `QUERY_ALL_PACKAGES` — visibility handled via `<queries>`). ✅
- Room: parameterized queries only (no SQL injection surface); DataStore keys fixed. ✅

**Weak / needs the fixes above:** predictive-back dismissal (C-1), alarm day mapping (C-2), background-FGS crash (C-3), dead security features (M-1/M-2/M-3/M-11), engine-2 inversion (M-5), main-thread I/O (M-7), AdMob sample ID (M-9), plus the minors.

**Timing-check audit (elapsedRealtime vs currentTimeMillis):** enforcement timelines (session/cage/temp-unlock/reels-unblock/break-pass) correctly use `elapsedRealtime` with reboot rebasing; lock-my-phone/monk/prime use wall clock by documented design (m-6 hardening suggested); heartbeats/streaks/insights use wall clock (acceptable); `EnforcementService` clean-run marker uses wall clock (clock flip resets it — cosmetic).

**Race-condition audit:** `SessionEngine`/`TempUnlockManager`/`CoinLedger`/`ViolationManager.shortsAttempt`/`ReelsEscalationManager`/`BreakPassManager`/`PrimeCommitManager`/`ProgressEngine` all mutex-serialized ✅; unsynchronized shared state found only in `NativeBridge` (m-12), `LockMyPhoneController.recordAttempt` (loop vs receiver, last-write-wins on an int), `GamificationNotifier.notifySeq`, `SessionSnapshot.cachedBlockedCount` — all benign-grade.

**Resource-leak audit:** `MonkModeLockService` audio listener (M-10), `NativeBridge` listeners (M-6), `ReelsOverlayActivity` scope (m-10); receivers/cursors/DB otherwise correctly released (`unregisterReceiver` in every `onDestroy`/`stopClean`, single Room instance, `goAsync().finish()` in `finally` for all async receivers ✅).

---

# TOP FIX ORDER (recommended)

1. **C-1** predictive back (one-line manifest change or per-activity callbacks) — restores the core alarm/lock guarantee.
2. **C-2** alarm ISO day mapping (one-line conversion) — alarms currently fire on wrong days.
3. **C-3** guard `LockMyPhoneService.start`/`recoverIfNeeded` with try/catch — prevents background crash loops.
4. **M-9** real AdMob App ID — release blocker for the coin economy.
5. **M-5** engine-2 `shouldYield` inversion — restores the dual-engine failover design.
6. **M-4** all-day schedule day leak.
7. **M-8** `cageTriggered` capture-before-clear.
8. **M-7** main-thread `runBlocking` removal (widgets, bridge, AlarmActivity first).
9. **M-6** NativeBridge listener leak.
10. **M-1/M-2/M-3/M-11** dead-feature wiring (app rules, DNS guard, kiosk, reels allowlist) or deletion.

*Audit performed without modifying any project file. Line numbers refer to the current working tree.*
