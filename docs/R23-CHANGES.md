# R23 Changes — v2.9.7 (Mood Board)

## Mood Board — mood-first launcher (new)

`mobile/lib/features/mood/mood_board_screen.dart` (new, 753 lines), routed at
`/moods` (`AppConstants.routeMoodBoard`).

- **Six moods, one board**: Study (teal), Detox (fuchsia), Monk (amber),
  Lockdown (red), Calm (safety cyan), Prime (violet) — every mood is a REAL
  enforcement mode wearing its signature accent from `tokens.dart`.
- **Premium presentation only** — zero new enforcement logic:
  - Study Mood **quick-start** reuses the exact `ActivationArgs` defaults of
    `StudySetupScreen._start()` (STUDY / STRICT / social+games+shorts+
    entertainment blocked / empty allowlist / no subject); only the duration
    (25 / 45 / 60 / 90 presets) is chosen on the board. The activation screen
    still runs the full native validation sequence (TRD §95).
  - Every other mood routes to its existing setup flow — no confirmation
    checkpoint is bypassed (UI/UX §16/§17/§85 intact).
- **Design**: built 100% on the token system (AppColors / AppSpacing /
  AppRadii / AppTypography + shared MLD widgets). Staggered entrance
  animation, press-scale cards with accent aura + selected glow, animated
  mood console (header + intensity pill + three fact chips), time-aware hero
  line, haptics on every selection.

## Dashboard wiring

- "No Active Session" hero: new primary CTA **PICK A MOOD** → Mood Board;
  the one-tap START STUDY / START DETOX shortcuts remain (secondary style).

## Version

- 2.9.6+35 → **2.9.7+36** (pubspec.yaml, constants.dart, app/build.gradle).

## Files

- NEW `mobile/lib/features/mood/mood_board_screen.dart`
- EDIT `mobile/lib/core/constants.dart` (route + version)
- EDIT `mobile/lib/core/router.dart` (import + route entry)
- EDIT `mobile/lib/features/dashboard/dashboard_screen.dart` (hero CTA)
- EDIT `mobile/pubspec.yaml`, `mobile/android/app/build.gradle` (version)
