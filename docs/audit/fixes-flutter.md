# MAXLEVEL DETOX — Flutter/Dart Audit Fix Changelog

**Task ID:** 7-d · **Appliers:** flutter-fixer subagent (majority of file edits) + coordinator (verification, corrections, final review) · **Date:** 2026-09-21
**Scope:** `mobile/lib/**` + `mobile/pubspec.yaml`. Kotlin (`mobile/android/**`) and Worker/Admin untouched by this task.
**Source:** `/home/z/my-project/work/audit/report-flutter.md` (Task 3-b, 1 CRITICAL + 8 MAJOR + 20 MINOR).
**Verification:** `flutter analyze` (Flutter 3.44.9 / Dart 3.12.2) — **0 errors, 3 pre-existing warnings** (unused `_create` method, unused local `p`, unused field `_error` — all dead code that predates the audit fixes; left in place). `flutter build apk --release --target-platform android-arm64` → **BUILD SUCCESSFUL** (v2.5.6, versionCode 21).

> Provenance note: the flutter-fixer agent died mid-run after completing all code edits but before writing this changelog. The coordinator reconstructed and verified every finding below line-by-line against the report; one bug in an already-applied fix (`flushEventQueue` off-by-one-batch) was found and corrected.

---

## CRITICAL

### [CRITICAL] Google Sign-In — the entire authenticated product surface was unreachable
- **pubspec.yaml:** added `google_sign_in: ^7.1.0` (resolved: google_sign_in 7.2.0 / google_sign_in_android 7.2.17).
- **lib/core/constants.dart:** new injectable constant `googleServerClientId = String.fromEnvironment('MLD_GOOGLE_SERVER_CLIENT_ID')` — the backend OAuth Web Client ID is supplied at build time:
  `flutter build apk --release --dart-define=MLD_GOOGLE_SERVER_CLIENT_ID=1234-abc.apps.googleusercontent.com`
  When empty (this default build), the sign-in button explains the missing configuration instead of failing silently.
- **lib/features/account/account_screen.dart:** real "Sign in with Google" button in the not-authenticated branch (the old screen only rendered instructional text — the control it described never existed). Flow: `GoogleSignIn` (serverClientId when configured) → idToken → `ApiClient.loginWithGoogle(idToken)` → toast + reactive refresh. `GoogleSignInException` handled (canceled vs real failure); mounted-guards after every await.
- **lib/data/api_client.dart:38-47:** verified `loginWithGoogle` parses the Worker envelope (`{user, tokens:{accessToken, refreshToken, accessExpiresIn}}` — matches `worker/src/routes/app.ts` `authGoogle`) and persists via the same `_persistSession` path as `restoreSession`.
- Consequence: Play purchase verification (`verifyPlayPurchase`), the offline event queue flush, community/friends/referral/trial are all now reachable after sign-in.

## MAJOR

### [MAJOR] account_screen.dart:97-99 — /subscription parsed at the wrong nesting level (PRO always showed "Free tier")
- Now stores parsed projections like the paywall: `SubscriptionInfo.fromJson(Map.from(sub['subscription']))` / `TrialInfo.fromJson(...)`; reads `planId/active/daysRemaining` through the parsed models. Envelope comment documents the frozen Worker shape (`GET /subscription → {subscription: {...}|null}`).

### [MAJOR] Remote config / flags / app-version / device registration / heartbeat / event queue were ALL dead code
- **lib/main.dart `_bootstrapCloud()`** now runs the full pipeline after `restoreSession()`:
  `ensureDeviceRegistered()` → `fetchConfig()` → `NativeBridge.applyRemoteConfig(jsonEncode(cfg['config']))` (Kotlin `RuntimeConfig` finally receives server-bounded overrides: coinsPerAd, temp-unlock cost/minutes, bailoutCost, shorts limits, cage duration) → `fetchFlags()` → stored **client-side** in `ApiClient.featureFlags` (deliberately NOT pushed through applyRemoteConfig — Kotlin has no flags surface and a `{'flags':...}` payload would be treated as an all-defaults config and wipe cached remote values; documented in code) → `_checkAppVersion()` → `_drainAndFlush()`.
- **`_periodicCloudSync()`** — new 15-minute `Timer` (disposed in `dispose()`): `sendHeartbeat(permissionSummary: {accessibility, usageAccess, overlay, notifications}, activeSession:)` fed from the live AppState projection, then `_drainAndFlush()`. Fire-and-forget with try/catch (offline-first; never gates UI).
- **`_drainAndFlush()`** — pulls the new `drainAnalyticsEvents` bridge batch (see fixes-kotlin.md m-1), normalizes/drops native event types not in the Worker's frozen `EVENT_TYPES` set (mode-suffixed `SESSION_STARTED_*` → `SESSION_STARTED`; `DP_*`/`PRIME_*`/`SESSION_PAUSED` etc. dropped so one bad event can't 400 a whole batch), enqueues each via `enqueueEvent`, then flushes.
- `_checkAppVersion()` reads `GET /app/version` (`{minimum, latest, forceUpdate, message}`) and logs a non-blocking update nudge.

### [MAJOR] mld_widgets.dart — MLDHoldToConfirmButton double _start() (bailout hold ≈1.35 s with progress reset instead of 2.2 s)
- The redundant `GestureDetector(onLongPressStart: _start)` wrapper was deleted; the deterministic `Listener(onPointerDown/Up/Cancel)` remains the single start/reset source. The 500-coin bailout gate is now genuinely 2.2 s.

### [MAJOR] monk_mode_screen.dart — Monk Mode had NO deactivation path (lock-in despite UI text promising an in-app exit)
- **lib/data/native_bridge.dart:** new `deactivateMonkMode()` wrapper (the Kotlin handler — added by the Kotlin fixer — validates active state, calls `MonkModeManager.deactivate(context, "user")`, returns status; INVALID_REQUEST when not active).
- **monk_mode_screen.dart:** hold-to-confirm "DEACTIVATE" control visible only while active, with error dialog feedback on failure. Early deactivation awards no completion DP (that is only on natural expiry) — matches the documented product semantics.

### [MAJOR] bailout double navigation race + Completion screen tautology
- **lib/data/app_state.dart:** new `suppressCompletionRedirect` flag.
- **bailout_screen.dart:** sets the flag before `executeBailout` await, clears it in all exit paths; single `pushNamedAndRemoveUntil(routeShell)` navigation.
- **active_session_screen.dart:** the state-stream watcher honors the flag — the "COMMITMENT COMPLETE" celebration can no longer flash over a 500-coin bailout.
- **completion_screen.dart:** the always-true `coins >= 0` "No bailout ✓" row now reflects the real last-session bailout state.

## MINOR (all applied except the 4 deliberate skips below)

- acceptPact/completeOnboarding results checked with snackbar-on-failure (onboarding_screen, permission_setup_screen).
- Activation error title `COULD NOT START ${isStudy ? 'STUDY' : 'DETOX'}` + `ActivationArgs.fromDetox` type-guard (activation_screen).
- TextEditingController leaks: dispose overrides added at all 8 sites (study_setup ×3, tasks, prime_commit, community ×2, schedules editor).
- setState-after-dialog mounted guards at the 4 sites (prime_commit ×2, emergency_codes, community).
- `withOpacity` → `withValues(alpha:)` (progress_screen ×3, dashboard_screen).
- Unused imports removed (dashboard, system_health `app_state`); unused `_app` element removed (activation).
- Dead settings rows (Theme/Backup/Privacy/About) now show an honest "not available yet" toast instead of silently doing nothing; empty Share-invite button copies the referral link to clipboard + toast.
- Non-defensive casts hardened: study `seconds`, models.dart days/repeatDays/dailyFocus/cycleRewards, remainingSeconds reads (lock/prime/monk/safety), lock schedule `s['id'].toString()`, community empty-displayName initial (`_initial()` helper), cycleRewards bounds-checked against real length.
- Shorts / App-rules / Alarm write failures now surface "Engine refused the change" snackbars (result envelopes checked).
- safety_pause: re-reads authoritative native status after every write (no optimistic divergence).
- bkash amounts formatted via `_fmtBdt` (no more "299.0 BDT").
- Study subject sent trimmed (chip highlight matches typed names).
- purchaseStream `onError` handler added; `MobileAds.initialize()` awaited-with-catch via `unawaited(_initAds())`.
- Debug tools (reset) wrapped in a confirmation dialog.
- state-stream map-cast wrapped in try/catch — corrupt frames skipped, never propagated.
- `since` param URL-encoded; flushEventQueue mid-failure retry semantics corrected (see coordinator note below).

### Coordinator correction (post-subagent)
- **api_client.flushEventQueue:** the subagent's fix dropped the FAILED batch on a mid-flush error (`skip(i + batch.length)`); corrected to `skip(i)` — successfully-uploaded batches are dropped, the failed batch and everything after it are retained for retry.

### Deliberate skips (with reasons)
| Item | Reason |
|---|---|
| IndexedStack eager tab mounts (dashboard) | Design choice for a 5-tab enforcement shell; eager native loads are the intended UX. |
| MethodChannel surface gaps (13 unused Dart wrappers) | Feature-parity work, not defects; each needs product intent (reels daily-limit UI, violations list, brain-rot snooze etc.). Documented in the report. |
| Dashboard "Today's Progress" '—' placeholder tiles | No cheap data source exists without new native getters; left visible-but-plain rather than fabricating numbers. |
| Pre-existing analyzer warnings (`_create`, `p`, `_error`) | Dead code that predates this audit round; removing the unreachable `_create` + `_NewCommitSheet` (~100 lines) is a product decision (community commit composer), not a bug fix. |

## Files changed (33)
lib/core/constants.dart · lib/main.dart · lib/data/{api_client, app_state, models, native_bridge, ad_reward_manager}.dart · lib/shared/mld_widgets.dart · lib/features/{account/…, alarm/…, bailout/…, coins/…, community/…, dashboard/…, emergency_codes/…, lock/… ×2, monk/…, onboarding/…, payments/…, permissions/…, prime/…, progress/…, safety/…, session/… ×4, settings/… ×4, shorts/…, study/…, tasks/…}.dart · pubspec.yaml (+google_sign_in ^7.1.0) · pubspec.lock (regenerated)
