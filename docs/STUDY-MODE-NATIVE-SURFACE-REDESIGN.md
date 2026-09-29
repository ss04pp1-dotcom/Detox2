# Study Mode — Native Hard-Surface Redesign

This revision keeps the existing `SessionKiosk` enforcement architecture and redesigns the actual `TYPE_ACCESSIBILITY_OVERLAY` surface (`NativeLockKioskView`) instead of trying to make a normal Flutter screen behave like a kiosk.

## Enforcement remains native

- `SessionKiosk` owns the wall/strips and their lifecycle.
- `DetoxAccessibilityService` remains the key/foreground enforcement authority.
- `EnforcementService`, recovery, session persistence and policy engines are unchanged.
- The new native view is presentation only; it does not weaken the wall.

## Study Mode behavior

When Study is active, the user-facing surface is now explicitly **STUDY MODE**, not a separate "Study Lock" screen.

The Flutter active-session surface also removes Pause / End / Temporary Unlock for Study Mode. Detox retains its existing controls. The only explicit safety action exposed on the hard surface is Emergency Call.

## Visual improvements

- Native hard surface uses the same MAXLEVEL DETOX navy/teal language as the Flutter design board.
- Larger visual hierarchy: brand header → mode hero → timer → stats → allowed apps → enforcement status.
- Added vector icons instead of emoji in the native surface.
- Rounded premium cards, accent borders, status pill, and cleaner timer typography.
- Dashboard shell no longer keeps bottom navigation mounted during any active session (Study, Detox and Cage), so no tab can be used as an in-app escape route.
- Brand header and dashboard status presentation were refined.

## Post-review fixes

- `ic_lock.xml`: replaced malformed arc path with the standard Material lock path (previous path could fail to inflate and crash the overlay).
- Detox session controls restored to the original set: End / Details + Temporary Unlock. (Pause was never a Detox control.)
- Prime commit: the `END WITH EMERGENCY CODE` (TOTP) exit is back for both Study and Detox; it is the only exit while Prime owns the session.
- Native surface card text is now mode-aware (Study / Detox / Cage engine).
- `MLDBrandHeader` status pill is opt-in via `statusLabel`; hidden by default so it never shows an unbacked "ready" state.
