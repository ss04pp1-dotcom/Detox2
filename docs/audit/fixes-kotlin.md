# MAXLEVEL DETOX — Kotlin Enforcement Engine Audit Fix Changelog

**Task ID:** 7-c · **Appliers:** kotlin-fixer subagent (previous session; all code edits, 61 in-code `v2.5.5 audit fix` markers) + coordinator (this session: verification of every finding against the report, gap-closure of m-1/M-11/m-17, build unblocking) · **Date:** 2026-09-21
**Scope:** `mobile/android/**` only (64 Kotlin files, manifest, res/, build.gradle, proguard). `mobile/lib/` untouched by this task.
**Source:** `/home/z/my-project/work/audit/report-kotlin.md` (Task 3-a: 3 CRITICAL, 12 MAJOR, 20 MINOR).
**Verification:** coordinator re-read every changed region; `flutter build apk --release --target-platform android-arm64` → **BUILD SUCCESSFUL** (compiles the full Kotlin tree through kapt/Room + R8).

> Provenance note: the kotlin-fixer died before writing this changelog (session context loss). All its edits were committed by the auto-checkpoint; the coordinator verified each finding below in the working tree and byte-diffed against the original zip.

---

## CRITICAL

### C-1 — Predictive-back opt-in made every "BACK is swallowed" surface dismissible (alarm puzzle, cage, monk walls)
- **AndroidManifest.xml:** `android:enableOnBackInvokedCallback` set to **false** — the legacy `onBackPressed()` interception in the lock activities works again on Android 13+. (Full OnBackInvokedCallback migration was deliberately not taken: it would require reworking every enforcement activity's override chain.)

### C-2 — Alarm repeat-days used the wrong day-numbering (alarms fired one day early)
- **alarm/ShockwaveAlarmEngine.kt:** ISO day conversion `val isoDow = ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Mon=1..Sun=7` before the `days.contains` check. "Mon–Fri" now fires Mon–Fri.

### C-3 — Unguarded background foreground-service starts in MldApp.onCreate (crash loop on Android 12+)
- **MldApp.kt:** `recoverIfNeeded()` moved into a `SupervisorJob` coroutine with try/catch; `LockMyPhoneService.start` re-arm guarded by try/catch (sticky service + BootRecoveryReceiver re-arm on the next legit start). Companion guards added in **EnforcementService.kt** and **BootRecoveryReceiver.kt** (coroutine try/catch).

## MAJOR

- **M-1 setAppRule no-op → real persistence:** new **enforcement/AppRulesStore.kt** (per-package block/allow store); `NativeBridge.setAppRule` persists + `getAppRules` surfaces the stored rule; **PolicyEngine** consults user rules (`explicit per-package user rules` layer); **StateRepository** carries the rules snapshot (active-session snapshot stays authoritative, TRD §109).
- **M-2 DnsTamperDetector dead → wired:** now invoked from **DetoxAccessibilityService** during Prime enforcement; node-budget cap added (m-14 fix).
- **M-3 KioskController/TrapOverlay/ShortsDetector dead code:** ShortsDetector.kt + TrapOverlay.kt **deleted**; the kiosk screen-pinning layer wired for real (**SessionEngine**: fresh DETOX session requests the kiosk pin; temp-unlock lifts it (**TempUnlockManager**); session end releases it; **LockMyPhoneService**/native state synced via **StateRepository**).
- **M-4 all-day schedules bled into the next day:** **ScheduleEngine** day-precise handling for all-day profiles.
- **M-5 engine-2 stand-down logic inverted:** **ForegroundAppMonitorService.shouldYield** corrected (no duplicate enforcement while engine 1 is healthy; no yield in the 30–60 s pre-stale gap).
- **M-6 NativeBridge Broadcaster listener leak:** listener tracked and removed via `detach()` from MainActivity's engine cleanup (**MainActivity.kt**, **NativeBridge.kt**, **SessionEngine.kt** unregister path).
- **M-7 main-thread runBlocking cluster:** widget broadcasts (**MldWidgets.kt**), MainActivity.onResume state builds, alarm-activity and ticker paths moved off the main thread; `buildStateJson` runs on a background dispatcher in the bridge.
- **M-8 cageTriggered read-after-clear:** **SessionEngine.finalizeLocked** captures cage state BEFORE clearing it — completions now record the real cage flag.
- **M-9 AdMob SAMPLE App ID in manifest:** injectable via gradle property `MLD_ADMOB_APP_ID` (manifestPlaceholders; default remains Google's public sample — release checklist must swap before Play upload; comment documents the failure condition).
- **M-10 MonkModeLockService audio-mode listener leak:** tracked, unregistered on destroy; registration guarded like the screen receiver.
- **M-11 Reels keyword allowlist never populated (dead "user setting"):** **option B applied by coordinator** — `whitelistedKeywords` + `isWhitelisted()` removed from **ReelsDetector.kt** and the call site from **DetoxAccessibilityService.kt**. No UI/store/bridge ever populated the field (verified: zero references in the Flutter layer and docs describe it as in-memory only); the full stack (UI → StateRepository → bridge → detector) is the correct future path if the feature is wanted.
- **M-12 SinthiaCheckIn double full-day UsageStats scan every 5 s:** re-query at most once per 5-minute window; single `todayUsage()` result reused for both totals.

## MINOR (applied)

m-1 AnalyticsOut never drained → **coordinator added `drainAnalyticsEvents` bridge method** (drains the queue as `{events:[{type,payload}]}`; the Flutter bootstrap/periodic sync now pulls and forwards it through the offline event queue — see fixes-flutter.md); m-2 GuardNotifier notification-id collisions re-based; m-3 "Fix Now" notification extra now routes to the permissions screen (MainActivity consumes `openRoute`; NativeBridge aligned); m-4 identical-branch conditionals removed (LockController, SessionEngine); m-5 EnforcementWall.hideInternal early-return → `isShowing()` stays truthful after unbind; m-7 CoinLedger always recomputes from the append-only ledger; m-8 session id UUID-backed suffix (no java.util.Random); m-10 unused/cancelled scopes cleaned (LockController, ReelsOverlayActivity); m-12 NativeBridge cross-thread fields `@Volatile`; m-13 `shorts!!` replaced with safe-call; m-14 DnsTamperDetector node budget + main-thread node walks moved off-thread (UninstallInterceptor/ReelsDetector); m-15 violation severity casing unified; m-16 background `startService` guarded; m-19 `MldApp.get()` no longer leaks UninitializedPropertyAccessException; m-20 FSI permission re-checked on Android 14+.

### Coordinator gap-closures (this session)
- **m-17 QUICKBOOT_POWERON (spoofable non-protected broadcast):** removed from the manifest intent-filter AND from BootRecoveryReceiver's action whitelist.
- **m-1 drain method** (above).
- **M-11 option B** (above).

## Deliberate skips (with reasons)
| Item | Reason |
|---|---|
| m-6 wall-clock authority (elapsedRealtime hardening for Lock-My-Phone/Monk/Prime) | Soft finding; a full monotonic-clock migration of the lock scheduling model is a design change beyond audit scope; the boot-recovery + TIME_SET/TIMEZONE_CHANGED re-evaluation paths already cover the common tamper cases. |
| m-18 silent debug-key fallback when key.properties absent | The release keystore + key.properties ARE present in the repo, so release builds sign correctly; the fallback only aids CI-less local rebuilds. Behavior documented in build.gradle. |
| C-1 full OnBackInvokedCallback migration | Disabling the opt-in restores the documented enforcement behavior on all API levels with zero surface change; the forward-migration touches every lock activity. |

## Build-environment adjustments (documented for reproducibility; see DELIVERY-README.md)
- `compileSdk` 35 → **36** (Flutter 3.44.9's resolved plugin set — google_sign_in_android 7.2.17, path_provider_android, shared_preferences_android, webview_flutter_android — compiles against SDK 36; targetSdk stays 35).
- `versionCode` 20 → **21**, `versionName` "2.5.5" → **"2.5.6"** (audit-fix release).
- `org.gradle.jvmargs` clamped to `-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC` + `org.gradle.parallel=false` — the build box has a 4 GiB RAM cap and no swap; the stock 2048m/768m daemon was OOM-killed mid-compile.
- The Flutter Gradle plugin's `forceNdkDownload` (empty-CMakeLists trick that forces a ~2 GB NDK install) is disabled **in this build environment's SDK copy only** (flutter_tools/gradle FlutterPlugin.kt) — the app has no native code; the NDK is used solely by AGP's strip task, which now runs against a minimal NDK (source.properties + llvm-strip) assembled from the official r27 zip. **The delivered project source contains no such patch** — on a normal machine the stock behavior (auto NDK install + strip) applies unchanged.

## Files changed (30 + 2 deleted + 1 new)
AndroidManifest.xml · app/build.gradle · gradle.properties · proguard-rules.pro (unchanged content, verified) · MainActivity.kt · MldApp.kt · accessibility/DetoxAccessibilityService.kt · ~~accessibility/ShortsDetector.kt (deleted)~~ · ~~accessibility/TrapOverlay.kt (deleted)~~ · alarm/ShockwaveAlarmEngine.kt · bridge/NativeBridge.kt · coins/CoinLedger.kt · enforcement/AppRulesStore.kt (NEW) · enforcement/{EnforcementService, LockController, PolicyEngine, ScheduleEngine, SessionEngine}.kt · gamification/SinthiaCheckIn.kt · guard/{DnsTamperDetector, GuardNotifier}.kt · lock/LockMyPhoneService.kt · monitor/ForegroundAppMonitorService.kt · monk/MonkModeLockService.kt · overlay/EnforcementWall.kt · prime/PrimeCommitManager.kt · recovery/BootRecoveryReceiver.kt · reels/{ReelsDetector, ReelsOverlayActivity}.kt · storage/StateRepository.kt · unlock/TempUnlockManager.kt · widgets/MldWidgets.kt
