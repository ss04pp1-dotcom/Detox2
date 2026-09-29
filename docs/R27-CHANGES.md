# R27 Changes — v2.9.11 (Un-cage the cage + volume keys clean)

User feedback this round (verbatim intent):
1. "Volume button চাপলে কি হবে — এটা কে বলেছে করতে? এটা বাদ দাও। আমি
   volume বাটনের সাথে সমস্যার কথা বলছিলাম." — no behaviour may be
   attached to the volume buttons. The volume multi-press BUG (one
   press = several steps) is the real report and stays fixed.
2. "কেসের স্ক্রিন Flutter UI-এর মতো কপি করো — যেন কেসের মধ্য থেকেই
   ads দেখা, temporary unlock এরকম কাজগুলো করা যায়." — the cage
   screen must be a FUNCTIONAL copy of the session screen, not a stuck
   dead-end surface.

## Volume keys — NO app behaviour, only the bug fix

- `accessibility_service_config.xml`: `flagRequestFilterKeyEvents`
  removed from the static config (the capability
  `canRequestFilterKeyEvents` stays) — the flag is applied DYNAMICALLY.
- `DetoxAccessibilityService.kt`:
  - NEW `syncKeyFilterFlag()` — arms `FLAG_REQUEST_FILTER_KEY_EVENTS`
    ONLY while a mode is enforcing (session/cage/monk/lock), disarms at
    idle. At idle the input dispatcher never round-trips key events
    through the service at all — the root cause of OEM pipelines
    replaying one volume press as several ("ekbar chap dile onekbar
    chap").
  - Instant arming at every mode start: `SessionEngine.startSession`,
    `EnforcementWall.startCage`, `MonkModeManager.activate`,
    `LockMyPhoneController.startSeconds` (via the new
    `syncKeyFilterSoon()`); instant disarm at finalize/monk end; the
    2 s kiosk watchdog loop backstops both directions every tick.
  - NEW `onVolumeKeyDebounce()` — while enforcing, a REPLAYED volume
    ACTION_DOWN inside a 200 ms window is consumed so one press is
    always exactly one step. Hold-to-ramp (repeatCount > 0) and
    ACTION_UP pass untouched. NO coins, NO toasts, NO UI — pure bug
    fix. Volume buttons carry zero app behaviour.
- NOTE: an earlier local-only draft (r26, never released) had a
  "VolumeGuard" coin tax on volume-up — it was removed before this
  release per the user's explicit request; nothing of it ships here.

## The cage is now a functional screen (both moods)

Flutter `_CageView` (`active_session_screen.dart`):
- The Study-mood dead-end ("STUDY MODE IS LOCKED — no controls") is
  GONE. Both Study and Detox cages now show the session screen's working
  control set:
  - **End** — haptic press feedback (vibration), then the same bailout
    flow as the session screen's End (500 coins, hold-to-confirm).
    Hidden when the cage outlived its session (nothing left to end).
  - **Details** — the cage details sheet (works in both moods now).
  - **TEMPORARY UNLOCK · 5 coins = 5 min** — opens the temp-unlock
    screen and now actually WORKS during the cage (see native changes);
    the button flips to "UNLOCK ACTIVE · m:ss" while a window runs.
  - **WATCH AD · EARN +1 COIN** — a rewarded ad plays right over the
    cage surface (preloaded on mount); the reward lands in the native
    ledger and the COINS tile updates live. This is the coin faucet the
    unlock needs when the balance is short.
- Prime-owned sessions still show only the TOTP emergency give-up
  (no unlock, no bailout) — that contract is unchanged.

Native changes that make the unlock real during a cage:
- `TempUnlockManager.kt`: the `CAGE_ACTIVE` refusal is REMOVED — a
  temporary unlock can now be bought from inside the cage (Prime refusal
  unchanged).
- `DetoxAccessibilityService.kt` cage gate: packages covered by an
  ACTIVE temp-unlock window are allowed through during the cage
  (mirrors PolicyEngine's TEMP_ALLOW rule outside the cage). When the
  window expires, full cage enforcement resumes automatically.
- `EnforcementWall.clearCage()` (new) + `SessionEngine.finalizeLocked`:
  ending a session (bailout / natural completion / give-up) now also
  clears the IN-MEMORY cage, so paying to leave ends BOTH the session
  and the cage — no zombie cage blocking apps for the rest of the burst
  window.

## Version

- 2.9.9+38 → **2.9.11+40** (pubspec.yaml, constants.dart,
  app/build.gradle). The 2.9.10 number is deliberately skipped (it was
  only ever a local draft containing the rejected volume-tax feature).
