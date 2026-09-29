# R28 — v2.9.12+41: the cage is ACTUALLY functional (dead-end wall fixed)

User report (verbatim intent): "Case e eishob kisui hoy nai, ager motoi ase,
shadharon design. Abar ads dekha screen eita to kaj i kore na. Jodi na paro
taile ager motoi koro — main screen theke ber hole cage diye atkabe."

## Root causes found (why r27 "did nothing" from the user's seat)

1. **The native cage wall was a dead end.** All r27 functional controls
   (End / Details / Temporary Unlock / Watch Ad) live on the FLUTTER cage
   surface inside the app. But the moment the user left the app during the
   cage (one home press — the natural instinct during a punishment), the
   a11y cage gate re-asserted the NATIVE wall (NativeLockKioskView
   `isCage=true`): the old red "CAGE MODE" kiosk with NO controls and NO
   path back into the app. That is the "shadharon design" the user saw.

2. **The wall never hid over our own app.** The cage wall is a
   TYPE_ACCESSIBILITY_OVERLAY — it floats ABOVE MainActivity. The cage
   gate's own-package branch early-returned without removing it, so once
   re-asserted it covered even the functional Flutter cage until the
   60 s expired.

3. **Ads failed on first tap.** `showAndEarn()` failed instantly with
   `failedToLoad` whenever the tap arrived while the single preload attempt
   was still in flight (or had lost the `MobileAds.initialize` race) —
   "ads dekha screen kaj kore na".

4. **Temporary Unlock was dead in a session-less cage.** The burst cage
   can trigger outside any session (5 rapid shorts attempts). Native
   validation refused with SESSION_NOT_ACTIVE → the cage's unlock button
   did nothing.

## Fixes

- **EnforcementWall**: new `hideIfCage()`; the cage kiosk now gets
  `onOpenApp` = hide wall + bring MainActivity forward (one tap from the
  wall → the functional Flutter cage). Blocking unchanged — the cage gate
  re-asserts the wall on any exit attempt.
- **NativeLockKioskView**: renders the previously-DEAD `onOpenApp`
  parameter as a prominent "OPEN CONTROLS" button (cage) / "OPEN APP"
  (session wall), with a new `ic_lock_open` drawable.
- **DetoxAccessibilityService**: own-package cage-gate branch + own-window
  TYPE_WINDOW_STATE_CHANGED event now call `EnforcementWall.hideIfCage()` —
  the wall can never sit on top of our own cage screen.
- **TempUnlockManager**: unlock is buyable when EITHER a session enforces
  OR the cage is active (spend reference falls back to "cage").
- **AdRewardManager**: preload retries up to 3× (1.2 s apart);
  `showAndEarn()` waits up to 8 s for the in-flight load instead of
  failing instantly; `main.dart` starts the first preload right after
  `MobileAds.initialize()` at app startup.
- Blocking semantics ("main screen theke ber hole cage diye atkabe") are
  PRESERVED: leaving the app during the cage → wall + HOME, exactly as
  before — the wall is just no longer a dead end.

Version: 2.9.11+40 → 2.9.12+41.
