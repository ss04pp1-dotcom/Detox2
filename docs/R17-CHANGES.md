# R17 — Session Kiosk (total lockdown) + emergency re-assert fix

Version: 2.9.1+30 · Follows: R16 (v2.9.0)

## User report (v2.9.0 testing)

1. "Study Mode and Detox Mode use app pinning — because of that the home
   button works, the back button works and the notification panel works.
   I want NOTHING to work in these modes. Even locked, the same. No way
   out at all."
2. "The system fail after exiting through Emergency still isn't fixed."

## Root causes found

- **App pinning was the trap layer** (`KioskController` →
  `Activity.startLockTask()`): user-consent screen pinning is escapable by
  design (hold Back + Recents), was never applied to STUDY sessions,
  never covered the launcher or the shade, and while pinned the emergency
  dialer `startActivity` is **blocked by the OS** (non-allowlisted
  activity cannot come forward) — a real contributor to the emergency
  bug.
- **Nothing re-asserted enforcement when the emergency lockdown ended.**
  The r16 EmergencyLockdown bounced non-dialer apps *while live*, but on
  END/expiry the user could sit on the launcher (a SYSTEM_ALLOW surface)
  with no wall at all.
- The notification shade was only defended reactively (ShadeGuard BACK
  after the fact) on the launcher/allowed surfaces.

## The fix — SessionKiosk (new: `overlay/SessionKiosk.kt`)

Pinning is **removed** (`KioskController` rewritten as a thin facade; no
`startLockTask`/`stopLockTask` anywhere). Three cooperating surfaces:

1. **WALL** — a full-screen `TYPE_ACCESSIBILITY_OVERLAY` window (floats
   above status + nav bars, `FLAG_LAYOUT_NO_LIMITS`, cutout short-edges).
   Shown whenever the foreground is anything other than our own app, a
   session-allowed study app, an input method, the dialer family, or a
   real block decision: launcher, Settings, unknown apps, SystemUI
   (shade / recents / power dialog) all get covered instantly. Home
   gestures, recents swipes and shade pulls land on our window and are
   consumed. Wall content: mode title, live countdown, subject (STUDY),
   up to 6 allowlist app shortcuts (STUDY), OPEN SESSION, Emergency.
2. **STRIPS** — top (status-bar height) + bottom (nav-bar height)
   overlays while our own app or a STUDY-allowlisted app is foreground:
   the app stays usable; the shade and the home/gesture areas are covered.
3. **KEY FILTER** — `DetoxAccessibilityService.onKeyEvent` now consumes
   BACK / APP_SWITCH / HOME while the wall, strips, or any
   EnforcementWall is showing.

Armed only while a session is `ACTIVE` with **no** stand-down window:
temp-unlock, study break (PAUSED), RECOVERY/CAGE statuses, settings
grace window, emergency lockdown (EmergencyLockdown.start now calls
`SessionKiosk.hideAll` so the dialer shows instantly).

## Emergency system-fail fix

- `EmergencyLockdown.start` → immediate kiosk stand-down (dialer visible).
- `EmergencyLockdown.end` → immediate `SessionKiosk.sync` re-assert
  (wall over the launcher / strips over our app).
- Emergency auto-expiry calls `end()` → the same re-assert.
- `EnforcementService` 30 s sweep now runs `SessionKiosk.sync` every
  tick — self-healing for missed a11y events / OEM window removals /
  emergency expiry.
- With pinning gone, the dialer launch itself can never be OS-blocked.

## Supporting changes

- `DetoxAccessibilityService`: kiosk bound on connect; the foreground
  pipeline consults the kiosk after `PolicyEngine.evaluate` (returns
  early when walled); key filter extended; kiosk unbound on destroy.
  Kiosk-walled packages record a throttled LOW `kiosk_wall` violation
  (one per pkg / 30 s) for Insights.
- `ShadeGuard`: kiosk surfaces count as live enforcement; never fights
  the emergency lockdown.
- `KioskController`: pinning removed; facade forwards to SessionKiosk
  (historical call sites unchanged: SessionEngine, TempUnlockManager,
  MainActivity).
- `MainActivity`: onResume/onPause now drive strips via the facade.
- Flutter `ActiveSessionScreen`: `PopScope(canPop: false)` on the main
  session view and the study-break view — in-app BACK cannot leave the
  session surface.
- Escape-attempt visibility: kiosk walls log `KIOSK_WALL` diagnostics.

## Test plan (needs a device)

1. Start a DETOX session → leave the app → the launcher is COVERED by
   the STUDY/DETOX LOCK wall with a live countdown; home/recents/shade
   gestures do nothing; BACK does nothing.
2. STUDY session → wall shows allowlist shortcuts; opening one works
   under the strips; the shade cannot be pulled inside it.
3. Emergency from the wall / app / lock screen → dialer opens
   immediately; other apps bounce back; END EMERGENCY (banner) or the
   15-min cap → the kiosk wall returns instantly.
4. Session end (natural / bailout / coins) → wall + strips disappear.
5. Temp unlock window / study break → kiosk lifts; expiry re-arms.

## Files changed

- NEW `mobile/android/.../overlay/SessionKiosk.kt`
- `mobile/android/.../enforcement/KioskController.kt` (pinning removed)
- `mobile/android/.../accessibility/DetoxAccessibilityService.kt`
- `mobile/android/.../safety/EmergencyLockdown.kt`
- `mobile/android/.../enforcement/EnforcementService.kt`
- `mobile/android/.../guard/ShadeGuard.kt`
- `mobile/lib/features/session/active_session_screen.dart`
- `mobile/pubspec.yaml`, `mobile/lib/core/constants.dart`,
  `mobile/android/app/build.gradle` (2.9.1+30)
