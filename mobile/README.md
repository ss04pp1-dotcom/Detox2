# MAXLEVEL DETOX — Mobile App (Flutter + Kotlin)

Security-first Android discipline platform. **Kotlin manages the phone,
Flutter manages the experience.**

- `lib/` — Flutter UI (dark, premium, enforcement-first design system)
- `android/app/src/main/kotlin/com/maxleveldetox/` — native enforcement
  engine (the security boundary — see `docs/ARCHITECTURE.md`)

## Prerequisites

- Flutter SDK (stable, 3.22+) — https://docs.flutter.dev/get-started/install
- Android Studio with SDK 35 + platform tools
- JDK 17

## Build & run

```bash
cd mobile
flutter pub get
flutter run                      # debug on a connected device/emulator
flutter build apk --release      # release AAB: flutter build appbundle
```

> **Gradle wrapper note:** the repository ships `gradle/wrapper/
> gradle-wrapper.properties` but not the wrapper jar. If `flutter run`
> complains, run `flutter create --platforms=android .` once (it preserves
> existing files and restores the wrapper), or copy `gradle/wrapper/
> gradle-wrapper.jar` from any recent Flutter project.

## Before shipping to Play

1. **Signing**: replace `signingConfig = signingConfigs.debug` in
   `android/app/build.gradle` with your production keystore (never commit it).
2. **AdMob**: replace the sample app id in `AndroidManifest.xml`
   (`com.google.android.gms.ads.APPLICATION_ID`) and the test rewarded unit
   in `lib/core/constants.dart` (`rewardedAdUnitId`).
3. **Permissions**: every permission is declared with a purpose — see
   `docs/SECURITY_MODEL.md` for the Play policy mapping (Accessibility API
   usage disclosure, QUERY_ALL_PACKAGES avoidance, exact alarms, FGS types).
4. **Icons**: add launcher icons (e.g. `flutter_launcher_icons`) — none are
   committed.

## Feature map

| Feature | Flutter screen | Native authority |
|---|---|---|
| Study Mode | `features/study/study_setup_screen.dart` | `SessionEngine.startSession` |
| Detox Mode | `features/detox/*` | `SessionEngine` + `PolicyEngine` |
| Shorts Blocker | `features/shorts/shorts_settings_screen.dart` | `DetoxAccessibilityService` + `ShortsDetector` + `ViolationManager` |
| Cage | native overlay (`LockScreenActivity`) + `features/session/active_session_screen.dart` | `SessionEngine.activateCage` |
| Temporary Unlock | `features/unlock/temp_unlock_screen.dart` | `TempUnlockManager` (native coin spend) |
| Coins | `features/coins/*` | `CoinLedger` (immutable, idempotent) |
| Bailout | `features/bailout/bailout_screen.dart` | `SessionEngine.executeBailout` (hold-to-confirm + atomic spend) |
| Shockwave Alarm | `features/alarm/alarm_setup_screen.dart` | `ShockwaveAlarmEngine` + native `AlarmActivity` puzzle |
| Recovery | `features/session/recovery_screen.dart` | `BootRecoveryReceiver` + `recoverIfNeeded()` |

## Debug tools

Debug builds expose fast-forward timer / simulate shorts / reset data in
Settings → DEBUG. The native bridge **refuses** every `debug_*` method when
the APK is not `FLAG_DEBUGGABLE` — these tools cannot exist in release
builds.

## Testing (TRD §81–84, §124–132)

Acceptance flows to verify on real devices (emulators are insufficient for
accessibility/OEM behavior):

1. Study 30m → open blocked app → lock screen → back/home/recent apps →
   restriction holds.
2. Detox 60m → kill app process → reopen → session restored, remaining
   time correct.
3. Reboot during active session → enforcement resumes, session NOT
   completed.
4. Watch 5 full rewarded ads → 5 coins → unlock 5 min → expiry resumes
   enforcement.
5. Shorts escalation: 5 warnings across DIFFERENT apps (shared counter) →
   Cage 30 min → auto-release.
6. Disable Accessibility mid-session → RECOVERY state + notification →
   restore → session resumes.
7. Bailout with < 500 coins → blocked; with ≥ 500 → hold-to-confirm →
   session ends, ledger shows −500.
8. Change system time → timer unaffected (elapsedRealtime).
9. Alarm with wrong answer ×3 → keeps ringing; correct answer → stops.
10. OEM matrix: Pixel, Samsung, Xiaomi, OnePlus, Oppo/Vivo at minimum.
