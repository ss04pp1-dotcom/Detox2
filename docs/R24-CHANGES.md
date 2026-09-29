# R24 — v2.9.8 (versionCode 37)

Rebases the r22 four-task work (built on v2.9.5/6533798) onto the r23
Mood Board tree (a2fae03) and adapts it to the new per-mood session
design. No feature was removed — ads, coins, charges and temporary
unlock all stay exactly as they are.

## 1. Cage screen = active Mood's session screen (user request)

`_CageView` in `active_session_screen.dart` renders the SAME immersive
surface as the running mood and follows it dynamically:

- Study cage: icon, title, subject, CAGE chip, timer, stats — plus the
  same hard-lock container the Study session screen shows since r22
  ("STUDY MODE IS LOCKED — no pause, end or temporary unlock").
- Detox cage: the Detox screen's End / Details row and the
  TEMPORARY UNLOCK · 5 coins = 5 min button (or the Prime TOTP give-up
  when a Prime commit owns the session). End only renders while a live
  session exists; a cage that outlived its session shows Details alone.
- The clock counts the CAGE down; the status chip says CAGE; Pause is
  hidden during punishment; back-seal (PopScope) kept.

## 2. Login persistence (user bug: login asked again on every restart)

`main()` now awaits `restoreSession()` BEFORE `runApp` — the old
fire-and-forget restore inside `_bootstrapCloud` raced the splash
router and bounced logged-in users back to auth.

## 3. First-run order + signup profile

Splash -> Onboarding -> Sign in/up -> app (onboarding gate checked
before auth). The signup form collects Gmail (email), Name, AGE and
CLASS (Class 6-12 / University / Other). Worker migration `007` adds
`users.age` / `users.grade` with the same self-healing ALTER fallback
as `password_hash` (004); `/auth/register` validates age 5-100 and
grade <= 30 chars; `/me` returns them; the profile is mirrored to
local prefs as an offline fallback.

## 4. Volume key multi-step fix (one press = several volume steps)

`DetoxAccessibilityService.onKeyEvent` now switches on keyCode FIRST —
non-navigation keys (volume first of all) return false with ZERO work;
the five nav keys use a 250 ms TTL cache (`isAnyModeActiveCached`)
instead of the per-event blocking DataStore read that stalled the
synchronous a11y key-filter pipeline. The r20-era systemui shade
denylist and episode-verify fixes from the r23 tree are kept intact.
Alarm volume lock/restore behavior untouched.

## Merge notes

- Conflicts resolved: `build.gradle`, `constants.dart`, `pubspec.yaml`
  (version → 2.9.8 / +37), auto-merged: accessibility service, session
  screen, both `schema.sql`s.
- The remote's r22/r23 work (native hard-surface redesign, Mood Board,
  six enforcement modes, chrome seed removal) is carried over as-is.

## Verification

- `dart_check2.py` 54/54 OK · `kt_balance.py` 73/73 OK
- worker `tsc --noEmit` clean · vitest 60/60 PASS
- admin `tsc --noEmit` clean · XML 24/24 well-formed
