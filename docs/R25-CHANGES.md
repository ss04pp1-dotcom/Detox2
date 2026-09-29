# R25 — v2.9.9 (versionCode 38)

User-reported round on top of v2.9.8 (r24): "starting Study Mode throws
me out of the app into the cage, and the cage design is nothing like the
Study Mode Flutter screen; the account shows no Gmail and no name."

## Root causes found

1. The cage was served by the NATIVE `EnforcementWall` cage wall
   (red "CAGE" + monospace timer), and the a11y cage gate kicked the
   user to the launcher for EVERY app except the dialer — including OUR
   OWN APP. The Flutter `_CageView` (which mirrors the active mood's
   session screen) was unreachable: the user could never open the app
   during a cage.
2. The shorts burst counter is PERSISTED; attempts made before a
   session could carry a near-threshold count into it, so the first
   in-session detection could fire the 1-minute cage — perceived as
   "starting Study Mode lands me in the cage".
3. The Profile & Account screen displayed only a display name (generic
   "Detox Warrior #ID" for accounts without one) and never showed the
   signed-in Gmail, age or class.

## Fixes

### 1. The cage is served IN THE APP (cage = active Mood's screen)
- `DetoxAccessibilityService` cage gate: our own package is now ALLOWED
  during a cage (same standing as the dialer) — no HOME kick, no wall.
  The Flutter cage surface takes over the whole in-app experience and
  mirrors the running mood's session screen 1:1 with the cage countdown.
- Reels burst HARD step: instead of `GLOBAL_ACTION_HOME` + wall, the
  cage timer is armed via `EnforcementWall.startCage()` (timer, no wall)
  and `launchCageSurface()` brings MainActivity forward (singleTop) so
  the user lands straight on the mood-mirroring cage screen.
- The native cage wall still covers every OTHER app if the user leaves
  (the cage gate reasserts it) — enforcement is unchanged outside the
  app. Dialer stays allowed; Emergency stays reachable.

### 2. Fresh session = fresh shorts ladder
- `ReelsEscalationManager.resetBurst()` (new): wipes the persisted
  consecutive counter at session start; daily counters untouched.
- Called from `SessionEngine.startSession` (best-effort, never blocks
  the start).

### 3. Account card shows the signed-in identity
- `_loadAccountInfo()` (replaces `_loadDisplayName`): loads email,
  display name, age and class from `/me`; the local signup mirror
  (`mld_profile_*`) is the offline fallback.
- Profile header: the signed-in Gmail is the identity line (was the
  generic "Cloud Sync Enabled"); age and class render as chips when
  present (e.g. `Class 9`, `Age 14`).

## Preserved (nothing removed)
Ads (RewardedAd), Discipline Coins, the 5-coins charge, Temporary
Unlock, End/Details controls, Prime TOTP give-up, Emergency Call — all
exactly as before. Login persistence, onboarding-first signup
(Gmail/name/age/class), and the volume key fix from r24 carry over.

## Verification
- `dart_check2.py` 54/54 OK · `kt_balance.py` 73/73 OK
- worker `tsc --noEmit` clean · vitest 60/60 PASS · admin `tsc` clean
- XML 24/24 well-formed
