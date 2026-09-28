# MAXLEVEL DETOX — Flutter/Dart UI Layer Audit (Task 3-b)

Auditor: flutter-auditor (read-only, line-by-line)
Scope: `/home/z/my-project/work/maxlevel-detox/mobile` — every Dart file read completely.

## Summary Table

| Metric | Value |
|---|---|
| Dart files audited | 51 (of which 4 are pure re-export stubs) |
| Dart LOC covered | 14,657 |
| Config/docs also audited | pubspec.yaml, pubspec.lock, analysis_options.yaml, mobile/README.md (589 lines) |
| Findings — CRITICAL | 1 |
| Findings — MAJOR | 8 |
| Findings — MINOR | 20 |

Overall verdict: the layer is **architecturally clean and unusually disciplined** (single NativeBridge choke-point with a typed `NativeResult` envelope, no coin credit outside the reward callback, every periodic timer cancelled in `dispose`, mounted checks present nearly everywhere, all 41 named routes resolve, all 99 invoked MethodChannel methods have Kotlin handlers). The serious problems are **missing/dangling product wiring** (sign-in, remote config, event queue), one **ad-loading liveness bug**, one **UX-critical hold-to-confirm bug**, one **envelope-parsing bug in Account**, and a handful of races/leaks.

---

## 1. CRITICAL Findings

### [CRITICAL] lib/data/api_client.dart:31 + whole app — Google Sign-In does not exist; the entire authenticated product surface is unreachable, and Play purchases can never be granted

**Evidence:**
```dart
// api_client.dart:31 — the ONLY auth entry point in the codebase
Future<bool> loginWithGoogle(String idToken) async {
  final res = await _post('/auth/google', {'credential': idToken}, authenticated: false);
```
```bash
$ rg -n "loginWithGoogle|google_sign_in|GoogleSignIn" mobile/lib
mobile/lib/data/api_client.dart:31:  Future<bool> loginWithGoogle(String idToken) async {
```
- `loginWithGoogle` has **zero callers**. There is no `google_sign_in` dependency in `pubspec.yaml`, no native sign-in bridge method, and no sign-in button in `account_screen.dart` (it only renders the text “Sign in from Settings → Account → Sign in (Google)” at line 226-231 — a screen that contains no such control).
- Consequences (verified against the Worker contract in `worker/src/routes/app.ts`):
  - `POST /subscription/verify` requires `c.user` (line 850: `if (c.user === null) return fail(c, 'UNAUTHORIZED', …)`). The purchase stream listener in `main.dart:72-80` calls `verifyPlayPurchase` which returns `null` when unauthenticated → **a user who pays via Google Play never gets PRO**. Money taken, entitlement never granted.
  - `_bootstrapCloud → flushEventQueue` early-returns (`if (!isAuthenticated) return;`) — the offline queue never flushes.
  - Community (`_SignInWall`), friends, referral, Sinthia LLM layer, trial claim — all permanently gated off.
- The Account screen also never refreshes `isAuthenticated` reactively, but that is moot while sign-in is impossible.

**Suggested fix (exact):** add the sign-in flow, e.g. in `account_screen.dart`:
```dart
// pubspec.yaml
#  google_sign_in: ^6.2.1

// account_screen.dart (inside the "else" branch of the Sign out section)
else ...[
  MLDButton(
    label: 'Sign in with Google',
    onPressed: () async {
      final google = GoogleSignIn();
      final account = await google.signIn();
      final auth = await account?.authentication;
      final idToken = auth?.idToken;
      if (idToken == null) return;
      final ok = await ApiClient.instance.loginWithGoogle(idToken);
      if (!mounted) return;
      _toast(ok ? 'Signed in — sync unlocked' : 'Sign-in failed');
      _load();
    },
  ),
  ...
]
```
(Or expose a `signInWithGoogle` MethodChannel from Kotlin. Either way, the credential must reach `loginWithGoogle`.)

---

## 2. MAJOR Findings

### [MAJOR] lib/data/ad_reward_manager.dart:31-48, 55, 82 — `preload()` has no error handling; a throwing `RewardedAd.load` permanently bricks the coin faucet and emits an unhandled async error

**Evidence:**
```dart
Future<void> preload() async {
  if (_loaded != null || _loading) return;
  _loading = true;
  await RewardedAd.load(          // can complete with an error (PlatformException /
    adUnitId: AppConstants.rewardedAdUnitId,   // SDK-not-ready), which propagates out
    request: const AdRequest(),
    rewardedAdLoadCallback: RewardedAdLoadCallback(
      onAdLoaded: (ad) { _loaded = ad; _loading = false; },
      onAdFailedToLoad: (error) { _loaded = null; _loading = false; },
    ),
  );
}
```
and both call sites are fire-and-forget:
```dart
// coins_screen.dart:28  (initState)
AdRewardManager.instance.preload();
// ad_reward_manager.dart:55 & :82
unawaited(preload());
```
If the `RewardedAd.load` future errors (e.g., load issued before `MobileAds.instance.initialize()` completes — which itself is unawaited at `main.dart:24`), the exception escapes `preload()` as an **unhandled async error** and `_loading` stays `true` forever → every later `preload()` returns immediately → `isReady` is permanently `false` → **no rewarded ad can ever be shown → no coins can ever be earned** until process restart.

**Suggested fix (exact):**
```dart
Future<void> preload() async {
  if (_loaded != null || _loading) return;
  _loading = true;
  try {
    await RewardedAd.load(
      adUnitId: AppConstants.rewardedAdUnitId,
      request: const AdRequest(),
      rewardedAdLoadCallback: RewardedAdLoadCallback(
        onAdLoaded: (ad) { _loaded = ad; _loading = false; },
        onAdFailedToLoad: (error) { _loaded = null; _loading = false; },
      ),
    );
  } catch (_) {
    _loaded = null;
    _loading = false;   // never leave the guard stuck
  }
}
```

### [MAJOR] lib/data/ad_reward_manager.dart:61-79 — reward/dismiss completion race: the user can be told “no coin” after actually earning one

**Evidence:**
```dart
ad.show(
  onUserEarnedReward: (ad, reward) async {
    final rewardKey = 'adr_${_randomHex(16)}';
    final result = await NativeBridge.instance.awardAdCoin(rewardKey);  // async hop
    completer.completeEarned(result.data == true);
  },
);
// onAdDismissedFullScreenContent → completer.completeDismissed();
```
`onUserEarnedReward` fires, then the native ledger round-trip runs; if the user closes the ad during that await, `completeDismissed()` runs first, `_done` flips, and the later `completeEarned()` becomes a **no-op**. The coin *is* credited natively but the Coins screen shows “Ad closed before the reward — no coin this time.” (`coins_screen.dart:48`). In a coin-economy app whose pact literally says “coins are earned only through completed rewarded ads”, this messaging inversion is integrity-relevant.

**Suggested fix (exact):** make `earned` dominant and only terminal when no reward is in flight:
```dart
class AdOutcomeCompleter {
  final Completer<AdOutcome> _completer = Completer<AdOutcome>();
  bool _done = false;
  bool _earning = false;                       // reward callback in flight

  void markEarning() => _earning = true;

  void completeDismissed() {
    if (_done || _earning) return;             // wait for the reward verdict
    _done = true;
    _completer.complete(AdOutcome.dismissed);
  }
  ...
}
// showAndEarn():
onUserEarnedReward: (ad, reward) async {
  completer.markEarning();
  final result = await NativeBridge.instance.awardAdCoin(rewardKey);
  completer.completeEarned(result.data == true);
},
// and in completeEarned/completeFailedToShow also guard a stray late dismiss.
```

### [MAJOR] lib/data/api_client.dart:296-308 — `flushEventQueue` re-sends already-uploaded batches after a partial failure (duplicate server events)

**Evidence:**
```dart
for (int i = 0; i < queue.length; i += 50) {
  final batch = queue.skip(i).take(50).toList();
  final ok = await _post('/events', {'events': batch});
  if (ok == null) return; // still offline — retry later
}
await prefs.setString('mld_event_queue', '[]');
```
If batch 1 of 3 succeeds and batch 2 fails (timeout mid-flush), the function returns **without removing the successfully-sent prefix**; the next flush re-uploads batch 1. Unless the Worker dedupes on `eventId`, every flaky connection produces duplicate analytics events.

**Suggested fix (exact):** persist the remaining suffix after each successful batch:
```dart
for (int i = 0; i < queue.length; i += 50) {
  final batch = queue.skip(i).take(50).toList();
  final ok = await _post('/events', {'events': batch});
  if (ok == null) {
    await prefs.setString('mld_event_queue', jsonEncode(queue.skip(i + batch.length).toList()));
    return; // remaining events retry later; sent prefix is dropped
  }
}
await prefs.setString('mld_event_queue', '[]');
```

### [MAJOR] lib/features/account/account_screen.dart:97-99 — Account screen parses the `/subscription` envelope at the wrong level; PRO users always see “Free tier”

**Evidence:**
```dart
final plan = _subscription?['planId'] as String?;
final pro = _subscription?['active'] as bool? ?? false;
final trialDays = _trial?['daysRemaining'] as int?;
```
The Worker returns `ok(c, { subscription: … })` (`worker/src/routes/app.ts:938`) — `planId`/`active` live **inside** `subscription`, not at the top level. `insights_screen.dart:47-50` and `paywall_screen.dart:60-63` both parse `subRes['subscription']` correctly, proving the contract. Result: `pro` is always `false`, `plan` always `null` → the Account screen shows “Free tier” and “Upgrade to PRO” even for active subscribers.

**Suggested fix (exact):**
```dart
final sub = _subscription?['subscription'] as Map<dynamic, dynamic>?;
final plan = sub?['planId'] as String?;
final pro = sub?['active'] as bool? ?? false;
// and where displayed: 'PRO — $plan' → 'PRO — ${plan ?? ''}'
```
(Store `SubscriptionInfo.fromJson(Map.from(sub))` like the paywall does for consistency.)

### [MAJOR] mobile/lib (no caller) — Remote config, feature flags, app-version check, device registration, heartbeat and the offline event queue are ALL dead code: never invoked from the UI layer

**Evidence** (exhaustive grep over `mobile/lib`):
```bash
$ rg -n "fetchConfig|fetchFlags|fetchAppVersion|enqueueEvent|ensureDeviceRegistered|sendHeartbeat" mobile/lib
api_client.dart:91,105,121,123,125,279   # definitions only — zero call sites
$ rg -n "applyRemoteConfig|getViolations|checkDailyInsight|getEngineStatus|getGuardStatus|stopLockMyPhoneValidated|getReelsStatus|setReelsDailyLimitMinutes|useReelsEmergencyPass|snoozeBrainRot|clearCompanionHistory" mobile/lib
native_bridge.dart only                 # definitions only — zero call sites
```
- `applyRemoteConfig` is the **only** Dart-side path that feeds `RuntimeConfig.applyRemote(rawJson)` in Kotlin (verified: `RuntimeConfig.kt` performs no HTTP of its own). Since `fetchConfig()/fetchFlags()` are never called and their results never forwarded, **remote-config bounded overrides never reach the engine** — `AppConstants` values (coinsPerAd, tempUnlock cost/minutes, bailoutCost, shortsWarningLimit, cageDuration) are permanently the compiled defaults.
- `enqueueEvent` has no producers → `mld_event_queue` is always empty → the server-side telemetry/audit pipeline (`POST /events`) receives nothing.
- `ensureDeviceRegistered` / `sendHeartbeat` never run → the admin Devices/Analytics dashboards will show no data.
- `fetchAppVersion` never called → no update nudge.
- 13 bridge methods are unreachable from the UI: `applyRemoteConfig`, `getViolations`, `checkDailyInsight`, `getEngineStatus`, `getGuardStatus`, `stopLockMyPhoneValidated`, `getReelsStatus`, `setReelsDailyLimitMinutes`, `useReelsEmergencyPass`, `snoozeBrainRot`, `clearCompanionHistory`, `debugAwardDp`, `debugResetProgress`.

**Suggested fix (exact):** in `_MldAppState._bootstrapCloud()` (main.dart:59) wire the pipeline:
```dart
Future<void> _bootstrapCloud() async {
  await ApiClient.instance.restoreSession();
  await ApiClient.instance.ensureDeviceRegistered();
  final cfg = await ApiClient.instance.fetchConfig();
  if (cfg != null && cfg['config'] is Map) {
    await NativeBridge.instance.applyRemoteConfig(jsonEncode(cfg['config']));
  }
  final flags = await ApiClient.instance.fetchFlags();
  if (flags != null && flags['flags'] is Map) {
    await NativeBridge.instance.applyRemoteConfig(jsonEncode({'flags': flags['flags']}));
  }
  await ApiClient.instance.flushEventQueue();
}
```
…and add `enqueueEvent('session_started', …)`-style producers at the session/alarm/coin call sites (or document the deliberate removal).

### [MAJOR] lib/shared/mld_widgets.dart:113-137, 157-164 — `MLDHoldToConfirmButton` starts the hold twice (Listener + GestureDetector): the bailout hold resets at ~500 ms and completes in ~1.35 s instead of 2.2 s

**Evidence:**
```dart
return GestureDetector(
  onLongPressStart: (_) => _start(),     // fires ~500 ms into the press
  onLongPressEnd: (_) => _reset(),
  onLongPressCancel: _reset,
  child: Listener(
    onPointerDown: (_) => _start(),      // fires immediately
    onPointerUp: (_) => _reset(),
    onPointerCancel: (_) => _reset(),
```
Sequence: `onPointerDown` → `_start()` (loop #1 ticking); at the long-press timeout `onLongPressStart` → `_start()` **again** → `setState(_progress = 0)` wipes the accumulated bar and starts loop #2; both loops now tick the same `_progress` → the bar visibly jumps back and the remaining 1700 ms elapse at double speed. Net effect: the deliberate 2.2 s high-friction gate on the **500-coin bailout** is effectively ~1.35 s with a visual glitch, and `HapticFeedback.mediumImpact()` fires twice.

**Suggested fix (exact):** delete the redundant GestureDetector handlers (keep the Listener, which covers press/cancel/up deterministically):
```dart
return Listener(
  onPointerDown: (_) => _start(),
  onPointerUp: (_) => _reset(),
  onPointerCancel: (_) => _reset(),
  child: SizedBox( ... ),
);
```

### [MAJOR] lib/features/monk/monk_mode_screen.dart:102-153 (+ bridge) — Monk Mode has no deactivation path anywhere; users are locked in for the full window despite UI text promising otherwise

**Evidence:**
```dart
// monk_mode_screen.dart:145-147 — the active view TELLS the user:
'Deactivation is only possible from this screen while the device '
'is unlocked — enforcement holds through reboots.',
```
…but `_activeView()` renders only the status card, the allowlist, and that text — **no deactivate button**. `NativeBridge` exposes no `deactivateMonkMode`, and the Kotlin `NativeBridge.kt` dispatch table has no monk-deactivate case, even though `MonkModeManager.deactivate(context, reason)` exists (`MonkModeManager.kt:96`). The class-level comment says “no exit until you deactivate from here” — the exit simply does not exist. Only the 120-minute timer (or process death + natural expiry) ends it.

**Suggested fix (exact):** in `_activeView()`, after the allowlist list add:
```dart
const SizedBox(height: AppSpacing.xxl),
MLDButton(
  label: 'Deactivate Monk Mode',
  variant: MLDButtonVariant.secondary,
  onPressed: () async {
    // add to native_bridge.dart:
    // Future<bool> deactivateMonkMode() =>
    //     (await call('deactivateMonkMode')).isOk;
    final ok = await NativeBridge.instance.deactivateMonkMode();
    if (!mounted) return;
    if (ok) { _load(); } else { _toast('Could not deactivate.'); }
  },
),
```
plus the Kotlin `"deactivateMonkMode" -> { ... }` case (Kotlin-auditor should confirm the reason/audit-log argument).

### [MAJOR] lib/features/session/active_session_screen.dart:38-47 + bailout_screen.dart:48-53 — bailout triggers two competing `pushNamedAndRemoveUntil` navigations (shell vs completion), and the Completion screen lies about the bailout

**Evidence:**
```dart
// active_session_screen.dart — stream listener, still mounted under the bailout route
_watch = NativeBridge.instance.stateStream.listen((s) {
  if (!mounted || !_hadSession) return;
  if (s.session == null || !s.session!.isActive) {
    _hadSession = false;
    Navigator.of(context).pushNamedAndRemoveUntil(AppConstants.routeCompletion, (route) => false);
  }
});
```
```dart
// bailout_screen.dart:50-53
Navigator.of(context).pushNamedAndRemoveUntil(AppConstants.routeShell, (route) => false);
```
When `executeBailout` lands, the native state push (EventChannel) and the method-channel reply are separate async events. If the state event is delivered first, the user sees the **“COMMITMENT COMPLETE — You finished what you started” celebration flash** after paying 500 coins, before the bailout screen's shell push replaces it. Compounding it:
```dart
// completion_screen.dart:65 — condition is a tautology
_row('No bailout', app.state.coins >= 0),
```
`coins >= 0` is always true, so the completion summary shows a “No bailout ✓” row even after a bailout (and never after a real completion with zero coins? no — always).

**Suggested fix (exact):** (a) in `bailout_screen._execute`, push the shell *before* awaiting is not possible — instead suppress the session watcher: pass a flag via AppState (`app.suppressCompletionRedirect = true` set in `_execute` before the await, cleared after) or have `_watch` ignore `SessionStatus.bailout`/`completed` when the last session ended via bailout (native `HistoryEntry.bailedOut` is available on `getHistory`, or add `endReason` to the state push). (b) `completion_screen.dart:65` → `_row('No bailout', !lastSessionBailedOut)` fed from the state/history, or simply delete the row.

---

## 3. MINOR Findings

### [MINOR] lib/features/onboarding/onboarding_screen.dart:163-173 & permissions/permission_setup_screen.dart:95-102 — `acceptPact()` / `completeOnboarding()` return values ignored
`await NativeBridge.instance.acceptPact();` — if native fails, the user is navigated onward anyway; the splash gate bounces them back on next cold start. Fix: `final ok = await ...; if (!ok) { show snackbar; return; }`.

### [MINOR] lib/features/session/activation_screen.dart:118-120 — failed DETOX activation is titled “COULD NOT START STUDY”
`isStudy` defaults to `true` when the route arguments are `DetoxConfirmArgs`. Fix: `final args = ActivationArgs.fromDetox(ModalRoute.of(context)?.settings.arguments); ... args.mode == 'STUDY'` (reuse the `didChangeDependencies`-cached args).

### [MINOR] lib/features/session/activation_screen.dart:24-36 — `ActivationArgs.fromDetox` crashes on unexpected argument types
`final map = raw; … (map?.durationMinutes as num?)` — dynamic dispatch throws `NoSuchMethodError` if `raw` is e.g. a `Map`. Fix: `if (raw is! DetoxConfirmArgs && raw is! ActivationArgs) return <safe defaults>`.

### [MINOR] TextEditingController leaks (never disposed) — 8 sites
`study_setup_screen.dart:63, 428, 646`; `tasks_screen.dart:62`; `prime_commit_screen.dart:105`; `community_screen.dart:269 (_NewCommitSheetState._reason), 338 (_FriendsTabState._query)`; `schedules_screen.dart:241 (_name — the editor State has no dispose())`. Fix: add `dispose()` overrides / dispose dialog controllers after the dialog resolves.

### [MINOR] setState-after-dialog without mounted guard — 4 sites
`prime_commit_screen.dart:77 & 92`; `emergency_codes_screen.dart:64`; `community_screen.dart:134` — `setState(() => _busy = true)` runs after `await showDialog/showModalBottomSheet`. Fix: `if (!mounted) return;` immediately after each dialog await.

### [MINOR] Deprecated `Color.withOpacity` — 4 sites
`progress_screen.dart:430, 435, 440`; `dashboard_screen.dart:384`. Fix: `withValues(alpha: 0.15)`.

### [MINOR] Unused imports / dead elements / dead buttons
- `dashboard_screen.dart:7` and `settings/system_health_screen.dart:5` import `data/app_state.dart` but never name `AppState` (analyzer: unused_import).
- `activation_screen.dart:204-206` — `AppState get _app` is never used (unused_element).
- `settings_screen.dart:95, 165, 166, 168` — Theme / Backup / Privacy / About rows have `onTap: () {}` (dead settings).
- `community_screen.dart:504-511` — “Share invite link” button body is empty (`// Share sheet via clipboard fallback.`) — the button does nothing. Fix: `Clipboard.setData(ClipboardData(text: 'https://maxleveldetox.com/r/$code')); _toast('Invite link copied');`.

### [MINOR] Non-defensive `as num` / `as int?` casts that can throw on malformed native payloads
- `study_setup_screen.dart:292` — `(row['seconds'] as num).toInt()` → use `((row['seconds'] as num?) ?? 0).toInt()`.
- `models.dart:514-515, 548-549, 596-598, 794-795` — `(e as num).toInt()` inside `days/repeatDays/dailyFocus/cycleRewards` mappings.
- `lock_my_phone_screen.dart:94-95`, `prime_commit_screen.dart:146`, `monk_mode_screen.dart:103-104`, `safety_pause_screen.dart:37` — `_status?['remainingSeconds'] as int?` → prefer `((…) as num?)?.toInt()` (the codebase itself uses the safe form elsewhere; Kotlin `Int` serializes as Dart `int` today, so these are latent, not active).
- `community_screen.dart:416, 437` — `(u['displayName'] as String? ?? '?')[0]` throws `RangeError` for an empty display name → guard: `(… ?? '?').characters.isEmpty ? '?' : (…)[0]`.
- `progress_screen.dart:396` — `checkIn.cycleRewards[checkIn.cycleDay.clamp(0, 6)]` → `RangeError` if native sends a shorter rewards list with `available: true` → `cycleRewards.isEmpty ? 0 : cycleRewards[checkIn.cycleDay.clamp(0, cycleRewards.length - 1)]`.

### [MINOR] lib/core/constants.dart:74-78 — production ships Google’s **sample** rewarded ad unit + hardcoded base URL
`rewardedAdUnitId = 'ca-app-pub-3940256099942544/5224354917'` serves test ads only — in production, no fills → no coins → no temp unlocks (economy deadlock). `apiBaseUrl` has no dev/staging override. Fix: `--dart-define=MLD_REWARDED_UNIT=…` / `String.fromEnvironment` with the sample as debug default (README §“Before shipping” acknowledges both).

### [MINOR] lib/data/api_client.dart:130-132 — `since` query param is not URL-encoded
`'/announcements?since=$since'` — an ISO timestamp with `+` would be corrupted. Fix: `'/announcements?since=${Uri.encodeQueryComponent(since)}'`.

### [MINOR] lib/features/dashboard/dashboard_screen.dart:50 — IndexedStack mounts all five tabs at once
`IndexedStack(index: _tab, children: pages)` → Study/Detox/Insights/Settings `initState` native loads (`getStudySubjects`, `getWeeklyStats`+`fetchSubscription`, `getNotificationGuard`, …) all fire on first shell build. Fix: lazy `IndexedStack` (build children per visited index) or `PageView` + `AutomaticKeepAliveClientMixin`.

### [MINOR] Shorts / App-rules / Alarm writes give no failure feedback
- `shorts_settings_screen.dart:120-130` — `setShortsEnabled`/`setShortsPlatform` booleans ignored; toggle silently reverts on next state push if native rejected.
- `app_rules_screen.dart:44-47` — `setAppRule` result ignored.
- `alarm_setup_screen.dart:36-41` — `if (ok) _load();` — a failed `scheduleAlarm` is invisible.
Fix: `if (!ok) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Engine refused the change.')));`

### [MINOR] lib/features/safety/safety_pause_screen.dart:68-77 — optimistic local set can diverge from native state
`_toggleApp` mutates `_apps` then calls `setSafetyPauseApps` without checking the result; on failure the UI shows apps that aren’t actually protected. Fix: await, check bool, reload or revert.

### [MINOR] lib/features/dashboard/dashboard_screen.dart:413-415 — “Today’s Progress” Focus/Detox tiles are hardcoded `'—'` placeholders
Not wired to `getWeeklyStats`/usage data. Fix: feed from `UsageStat`/weekly stats or remove the tiles.

### [MINOR] lib/features/payments/bkash_screen.dart:263, 273, 397 — amount renders as “299.0 BDT”
`'${payment.amountMinor / 100} BDT'` → int/100 is a double. Fix: reuse `Plan.priceLabel`-style formatting (`major % 1 == 0 ? major.toInt().toString() : major.toStringAsFixed(2)`).

### [MINOR] lib/features/study/study_setup_screen.dart:84-92 — subject stored untrimmed, selection stored trimmed
`addStudySubject(name)` sends the raw string; `_subject = name.trim()`. A name typed with trailing spaces never highlights its chip (`_subject != _subjects[i]`). Fix: send `name.trim()` to native.

### [MINOR] lib/main.dart:72-80 — `purchaseStream` listener has no `onError`
The EventChannel decode/map could error (malformed billing payload) → unhandled zone error. The `stateStream` equivalent *is* guarded in `app_state.dart:40-45`. Fix: add `onError: (Object e) { /* log */ }` to the `listen`.

### [MINOR] lib/main.dart:24 — `MobileAds.instance.initialize()` unawaited
Returns a Future that can fail; combined with MAJOR #2 this is the likely trigger for the stuck-`_loading` scenario. Fix: `unawaited(MobileAds.instance.initialize().catchError((_){}));`

### [MINOR] lib/features/settings/settings_screen.dart:173-180 — debug tools lack confirmation dialogs
`debugResetData()` wipes sessions/coins on a single tap with no confirm. Fix: wrap in `showDialog<bool>`.

### [MINOR] lib/data/native_bridge.dart:55-57 — state-stream map cast has no error recovery
`.map((raw) => DeviceState.fromJson(Map<dynamic, dynamic>.from(raw as Map)))` — a non-Map event becomes a stream error; AppState's `onError` only prints in debug, and the splash watchdog covers bootstrap. Fix: wrap in a try/catch inside the map and skip bad frames.

### [MINOR] lib/features/lock/lock_my_phone_screen.dart:51 + lock_schedules_section.dart:303 — `s['id'] as String` unguarded
If native ever emits a numeric id the delete throws. Fix: `s['id']?.toString()`.

### [MINOR] MethodChannel surface gaps (Dart side, feature parity)
No Dart callers exist for: reels daily-limit UI (`getReelsStatus`/`setReelsDailyLimitMinutes`/`useReelsEmergencyPass`), brain-rot snooze (`snoozeBrainRot`), companion history clear (`clearCompanionHistory`), violations list (`getViolations`), lock-my-phone validated stop (`stopLockMyPhoneValidated` — note the Lock screen *tells* the user the bailout exits a lock session; only `executeBailout` exists on that path, native decides), engine/guard status (`getEngineStatus`/`getGuardStatus`), daily insight check (`checkDailyInsight`). Each has Kotlin support but no Flutter entry point. Prioritize per product intent.

---

## 4. Feature-Flow Map (UI action → app_state/manager → native_bridge → Kotlin method)

| # | Feature | UI action (file) | State/manager hop | Bridge method(s) invoked | Kotlin handler (NativeBridge.kt line) | Notes |
|---|---|---|---|---|---|---|
| 1 | Splash gate | `main.dart` `_SplashScreenState._resolve` | `AppState.stateStream` (bootstrap + 4 s watchdog → `refresh()`) | `getPermissionState` (via refresh) | :727 | Routes onboarding→pact→session→shell; `consumeOpenCompanion` deeplink (:1049) |
| 2 | Onboarding | `onboarding_screen.dart` `_next`→Pact `_sign` | — | `acceptPact` | :301 | Result ignored (MINOR) |
| 3 | Permissions setup | `permission_setup_screen.dart` `_open`/`_continue` | `AppState.refresh()` every 2 s while visible | `openPermissionSettings`, `requestIgnoreBatteryOptimizations`, `getNotificationGuard` (isNotificationListenerGranted), `completeOnboarding` | :728, :743, :711, :297 | Polling pattern (r7 fix) |
| 4 | Dashboard shell | `dashboard_screen.dart` | `AppStateScope` (InheritedNotifier) | — (state-driven only) | — | Session-active swaps whole shell for `ActiveSessionScreen(embedded:true)` |
| 5 | Study start | `study_setup_screen.dart` `_start` → ActivationScreen args | — | `startSession` (mode=STUDY, strictness=STRICT, categories, allowlist groups, subject) | :309 | Subjects via `getStudySubjects`/`addStudySubject`/`removeStudySubject` (:335/:337/:343) |
| 6 | Detox start (BALANCED/STRICT/MAXLEVEL) | `study_setup_screen.dart` DetoxSetup → `_continueToConfirm` → `detox_confirm_screen.dart` → ActivationScreen | — | `startSession` (mode=DETOX, strictness from chips) | :309 | Strictness strings uppercase; models parse lowercase — consistent |
| 7 | Activation | `activation_screen.dart` `_activate` | awaits `NativeResult` before navigating (TRD §95 honored) | `startSession` | :309 | Error → FIX PERMISSIONS / Back |
| 8 | Active session | `active_session_screen.dart` | `AppStateScope` per-second pushes | `pauseSession`/`resumeSession` (study break), `openEmergencyDialer`→`emergencyCall` (:1067) | :326/:331/:1067 | Completion redirect via stream watcher |
| 9 | Session end / recovery | `completion_screen.dart`, `recovery_screen.dart` | — | (native-driven; recovery CONTINUE → ActiveSession) | — | `stopSession` exists in bridge (:348) but is never called by any screen — native completes sessions itself |
| 10 | Reels/shorts blocker | `shorts_settings_screen.dart` toggles | `AppState.toggleShorts`/`toggleShortsPlatform` | `setShortsEnabled`, `setShortsPlatform` | :595/:605 | Warning dots + cage info read from state stream |
| 11 | Coins (earn) | `coins_screen.dart` `_watchAd` | `AdRewardManager.showAndEarn` | `awardAdCoin` (rewardKey, idempotent) — **only credit path in Dart** | :568 | MAJOR #2/#3 risks |
| 12 | Coin history | `coin_history_screen.dart` | — | `getCoinTransactions` | :577 | Read-only |
| 13 | Temp unlock (5 coins/5 min) | `temp_unlock_screen.dart` `_unlock` | — | `requestTempUnlock` | :559 | Button gated on balance; native re-validates INSUFFICIENT_COINS/CAGE_ACTIVE/SESSION_NOT_ACTIVE |
| 14 | Bailout (500 coins) | `bailout_screen.dart` `_execute` (hold-to-confirm) | — | `validateBailout`, `executeBailout` | :447/:451 | Atomic native spend; MAJOR #7/#9 issues |
| 15 | Shockwave alarm | `alarm_setup_screen.dart` create/toggle/delete | — | `scheduleAlarm`, `cancelAlarm`, `getAlarms` | :793/:817/:822 | Ring+puzzle fully native (AlarmActivity) |
| 16 | Lock My Phone | `lock_my_phone_screen.dart` + `lock_schedules_section.dart` | local `_status` polling 1 s | `getDeviceAdminState`, `requestDeviceAdmin`, `startLockMyPhone`, `getLockMyPhoneStatus`, `getLockSchedules`, `saveLockSchedule`, `deleteLockSchedule` | :367/:363/:371/:388/:391/:394/:413 | `stopLockMyPhoneValidated` (:422) has no Dart caller |
| 17 | Monk Mode | `monk_mode_screen.dart` `_activate` | local `_status` polling 5 s | `activateMonkMode`, `getMonkModeStatus`, `getUsageStats` (allowlist picker) | :477/:497/:756 | MAJOR #8: no deactivate |
| 18 | Prime Commit | `prime_commit_screen.dart` | local `_status` polling 1 s | `activatePrimeCommit`, `giveUpPrimeCommit` (TOTP code), `getPrimeCommitStatus` | :541/:548/:554 | Relapse consequences native |
| 19 | Safety Pause | `safety_pause_screen.dart` | local state | `setSafetyPauseEnabled`, `setSafetyPauseApps`, `setSafetyPauseSeconds`, `getSafetyPauseStatus`, `getUsageStats` | :503/:512/:517/:523/:756 | Optimistic set (MINOR) |
| 20 | Emergency codes | `emergency_codes_screen.dart` `_enroll` | local `_status` | `enrollEmergencyCodes`, `getEmergencyCodeStatus` | :528/:538 | Secret shown once; consumed by Prime give-up |
| 21 | Progress / DP | `progress_screen.dart` | local snapshot | `getProgress`, `claimCheckIn`, `getDpHistory`, `getRelapseHistory`, `getBreakPassStatus`, `useBreakPass`, `endBreakPassEarly` | :853/:854/:865/:869/:877/:878/:886 | Check-in cycle + weekly break passes |
| 22 | Tasks | `tasks_screen.dart` | local list | `getTasks`, `addTask`, `addSubtask`, `toggleSubtask`, `completeTask`, `reopenTask`, `deleteTask` | :964-:1009 | DP awards native |
| 23 | Companion (Sinthia) | `companion_screen.dart` + `companion_engine.dart` | `CompanionEngine` (persona ctx) | `getBrainRotStatus`, `getProgress`, `getCompanionConfig`, `setCompanionConfig`, `getCompanionHistory`, `appendCompanionMessage` | :1015/:853/:1020/:1021/:1032/:1037 | LLM via Worker `/ai/chat` (auth-gated → CRITICAL #1 blocks layer 2); `stripEmergencyCode` honored; `clearCompanionHistory` (:1044) unused |
| 24 | Community / friends / referral | `community_screen.dart` | `ApiClient` | — (pure HTTP: `/community/commits`, `/friends…`, `/referral…`) | — | Walled behind sign-in (CRITICAL #1) |
| 25 | Account / sync | `account_screen.dart` | `ApiClient` | `getDeviceAdminState`, `requestDeviceAdmin`, `openPermissionSettings('deviceAdmin')` | :367/:363/:728 | MAJOR #5 envelope bug; no sign-in UI (CRITICAL #1) |
| 26 | Paywall | `paywall_screen.dart` | `ApiClient` + purchases | `getBillingProducts`, `launchPurchase`, `restorePurchases` | :890/:895/:906 | Purchase stream (`com.maxleveldetox/billing` EventChannel) → `verifyPlayPurchase` — dead without sign-in (CRITICAL #1) |
| 27 | bKash | `bkash_screen.dart` | `ApiClient` | — (HTTP `/payments/bkash/*`) | — | Manual gateway; solid state machine |
| 28 | Widgets | `settings/widgets_screen.dart` | — | `getWidgetStatus`, `pinWidget`, `updateWidgets` | :910/:920/:939 | 5 widget types incl. brainrot |
| 29 | App rules / limits / schedules | `settings/app_rules_screen.dart`, `schedules_screen.dart` | local lists | `getAppRules`, `setAppRule`, `getAppLimits`, `setAppLimit`, `getSchedules`, `saveSchedule`, `deleteSchedule` | :618/:636/:645/:658/:671/:678/:701 | |
| 30 | Notification guard | `settings_screen.dart` | local `_notifGranted/_notifEnabled` | `getNotificationGuard`, `setNotificationGuardEnabled`, `openPermissionSettings('notificationListener')` | :711/:717/:728 | |
| 31 | System health | `settings/system_health_screen.dart` | local report | `getSystemReport`, `getDiagLog` | :1062/:1059 | Copy-to-clipboard report |
| 32 | Config/telemetry (DEAD) | — | — | `applyRemoteConfig`, `getViolations`, `checkDailyInsight`, `getEngineStatus`, `getGuardStatus`, reels extras, snooze, clear-history, 2 debug fns | :843/:776/:955/:356/:357/:459-:466/:1016/:1044/:1083 | MAJOR #6: no Dart callers |

---

## 5. MethodChannel inventory (Dart → Kotlin)

**Channels**
- `MethodChannel('com.maxleveldetox/native')` — `NativeBridge.call()`
- `EventChannel('com.maxleveldetox/statestream')` → `DeviceState` pushes (1 Hz while enforcing)
- `EventChannel('com.maxleveldetox/billing')` → purchase maps (`main.dart:72`)

**All 99 method names invoked from Dart** (each verified to have a Kotlin handler in `bridge/NativeBridge.kt`):

`acceptPact, activateMonkMode, activatePrimeCommit, addStudySubject, addSubtask, addTask, appendCompanionMessage, applyRemoteConfig, awardAdCoin, cancelAlarm, checkDailyInsight, claimCheckIn, clearCompanionHistory, completeOnboarding, completeTask, consumeOpenCompanion, debugAwardDp, debugFastForward, debugResetData, debugResetProgress, debugSimulateShorts, deleteLockSchedule, deleteSchedule, deleteTask, deliverAnnouncements, emergencyCall, endBreakPassEarly, enrollEmergencyCodes, executeBailout, getAlarms, getAppLimits, getAppRules, getBillingProducts, getBrainRotStatus, getBreakPassStatus, getCompanionConfig, getCompanionHistory, getDeviceAdminState, getDpHistory, getEmergencyCodeStatus, getEngineStatus, getGuardStatus, getHistory, getLockMyPhoneStatus, getLockSchedules, getMonkModeStatus, getNotificationGuard, getPermissionState, getPrimeCommitStatus, getProgress, getReelsStatus, getRelapseHistory, getSafetyPauseStatus, getSchedules, getShortsPlatform(n/a — see setShortsPlatform), getStudySubjects, getSystemReport, getTasks, getUsageStats, getViolations, getWeeklyStats, getWidgetStatus, giveUpPrimeCommit, launchPurchase, openEmergencyDialer(→`emergencyCall`), openOemBackgroundSettings, openPermissionSettings, pauseSession, pinWidget, removeStudySubject, reopenTask, requestDeviceAdmin, requestIgnoreBatteryOptimizations, requestTempUnlock, restorePurchases, resumeSession, saveLockSchedule, saveSchedule, scheduleAlarm, setAppLimit, setAppRule, setCompanionConfig, setNotificationGuardEnabled, setReelsDailyLimitMinutes, setSafetyPauseApps, setSafetyPauseEnabled, setSafetyPauseSeconds, setShortsEnabled, setShortsPlatform, snoozeBrainRot, startLockMyPhone, startSession, stopLockMyPhoneValidated, stopSession, toggleSubtask, updateWidgets, useBreakPass, useReelsEmergencyPass, validateBailout`

*(Name note: Dart `openEmergencyDialer()` invokes native method `emergencyCall`; Dart `isDeviceAdminActive()` invokes `getDeviceAdminState`. Everything else is 1:1.)*

**Dart-invoked with zero UI callers (dead surface, MAJOR #6 / MINOR #20):** `applyRemoteConfig, checkDailyInsight, clearCompanionHistory, debugAwardDp, debugResetProgress, getEngineStatus, getGuardStatus, getReelsStatus, getViolations, setReelsDailyLimitMinutes, snoozeBrainRot, stopLockMyPhoneValidated, useReelsEmergencyPass`.

**Missing from the bridge (wanted by UI logic but not defined in Dart):** `deactivateMonkMode` (MAJOR #8), any Google sign-in credential channel (CRITICAL #1).

---

## 6. Verified-good properties (no action needed)

- **Coin integrity (Dart side):** the only coin-credit call in the entire layer is `NativeBridge.awardAdCoin(rewardKey)` inside the SDK's `onUserEarnedReward` callback (`ad_reward_manager.dart:73-78`), with a fresh 128-bit random key per callback; native ledger dedupes. No UI path can mint coins. UI gating for spends (balance checks) is advisory only — native re-validates (`INSUFFICIENT_COINS`, `CAGE_ACTIVE`, `SESSION_NOT_ACTIVE` handled in `temp_unlock_screen.dart:47-52`, `bailout_screen.dart:57-62`).
- **Timers:** every `Timer.periodic`/`Timer` is cancelled in `dispose()` (lock_my_phone:37, permission_setup:91, prime:35, monk:37, _BreakView:265, app_state watchdog:96).
- **Route integrity:** all 41 registered routes resolve; every literal string used with `pushNamed` (`'/activation'`, `'/pro'`, `'/payments/bkash'`, `'/settings'`) matches a defined constant; no typos found via constants-vs-usage diff.
- **MissingPluginException / PlatformException:** centrally converted to `NativeResult.err` in `NativeBridge.call()` (native_bridge.dart:89-93) — no screen can crash off a dead channel; `debug_*` methods are additionally gated by `assertionsEnabled`.
- **Async-gap hygiene:** ~90% of await sites have `if (!mounted) return;` — the exceptions are catalogued in MINOR findings above.
- **Models:** all `fromJson` factories are null-safe with typed fallbacks (except the `(e as num)` list casts noted).
- **Splash bootstrap:** dual protection (4 s AppState watchdog + 4 s splash deadline) prevents the r6 infinite-spinner.
- **Study-break view:** re-syncs from native pushes each second (didUpdateWidget) — no local clock drift; wall-clock only used for display labels, all enforcement timers remain native (elapsedRealtime).

## 7. Config audit

- `pubspec.yaml` — dependency set is minimal and appropriate; the `path_provider_android: 2.2.17` pin (NDK-free) is deliberate and documented; **missing: any sign-in package** (see CRITICAL #1). SDK constraint `>=3.6.0` matches Dart 3 features used (records, patterns).
- `pubspec.lock` — resolves all direct deps; single SDK block; no unexpected transitive risk.
- `analysis_options.yaml` — flutter_lints 4 + 3 extra rules; the unused-import/unused-element hits above indicate `flutter analyze` was not run clean at ship time.
- `mobile/README.md` — honestly documents the sample ad unit + debug-signing caveats; feature map matches the code.

— End of report —
