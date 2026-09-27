# R15 — Reference UI Redesign Merge (v2.8.0)

Merges the user-supplied reference UI redesign package
(`MAXLEVEL_DETOX_REFERENCE_UI_CODE_REDESIGN_FULL.zip`, delivered via gofile)
onto the v2.7.1 baseline.

## Source package audit

- Package base: v2.7.0 (one revision behind v2.7.1).
- Scope per its own `UI-REDESIGN-README.md`: UI/visual layer only; logic,
  Kotlin enforcement, NativeBridge, Worker/API, D1 and persistence claimed
  preserved. Verified by three-way diff (package vs v2.7.0 vs v2.7.1).
- Not build-verified by its authors (no Flutter SDK in their environment).
  This merge re-verifies and fixes (see below).

## What the redesign changed (all verified visual-only)

Mobile:
- `core/theme/tokens.dart` — new palette (deep navy #04101F background,
  reference blue #22A7FF primary, violet #8B4DFF premium, teal success,
  cyan info); slightly tightened display/heading/section sizes.
  `AppTypography.body(fontSize:)` optional param (v2.6.0 build fix) intact.
- `core/theme/mld_theme.dart` — Material defaults reworked: transparent
  scaffold (global `MLDAppBackdrop` shows through), app-bar theme, filled /
  outlined / text button themes, card theme, tab bar theme, chips, popups.
- `shared/mld_widgets.dart` — new shared components `MLDAppBackdrop`
  (gradient + grid painter + glow orbs), `MLDAppBarTitle` (vector logo mark),
  `MLDBrandHeader`; restyled `MLDButton` (gradient + glow), `MLDCard`,
  `MLDStatTile`, `MLDSectionHeader` (accent bar). All existing public
  widget APIs preserved (additive only).
- `main.dart` — app `builder:` wraps every route in `MLDAppBackdrop`.
- `dashboard_screen.dart` — gradient body, restyled NavigationBar
  (74dp, rounded icons), `MLDBrandHeader` on Home.
- `onboarding_screen.dart` — image-based pages using 5 new
  `assets/ui/*.png` boards; new copy for pages 4–5.
- 17 screens — mechanical `Text` → `MLDAppBarTitle` AppBar title swap.
- `pubspec.yaml` — `assets/ui/` asset dir registered. No new packages.
- Mode accents (study/detox/monk/lock/safety/prime) already existed in
  tokens since v2.5.x — unchanged.

Admin:
- `Layout.tsx` — glass sidebar (backdrop blur, active-item glow).
- `ui.tsx` — gradient primary/secondary buttons, glass `Card`/`StatCard`.
- `index.css` — layered radial/linear page background, `mld-glass`,
  `mld-page-glow` component layers.
- `tailwind.config.js` — deeper navy palette, brighter accent #6EA8FF,
  retooled glow shadow.

Nothing in worker/, .github/, or Kotlin android/ was touched by the
package — verified byte-identical to v2.7.0 for untouched paths.

## Merge strategy (per user instruction: new UI + most-correct functionality)

Base tree = v2.7.1 (keeps all r13 bug fixes and r14 lint/CI work), then
overlay the 33 redesigned files + assets + admin changes, then bump
versions. The only v2.7.0→v2.7.1 delta inside overlaid files was r14
`dart fix` const cosmetics — re-applied here by running `dart fix --apply`
again (45 fixes across 16 files).

## Fixes applied during merge (package was not build-verified)

1. `mld_theme.dart` — removed `padding:` from `TabBarThemeData`
   (parameter does not exist in Flutter 3.44 stable; compile error).
2. Removed dead code the package reintroduced from v2.7.0 (r14 had
   removed it): unused `_fmt` in lock_my_phone_screen.dart, unused `_fmt`
   in prime_commit_screen.dart, unused `_error` field + assignment in
   bkash_screen.dart.

## Verification

- `flutter analyze` → No issues found (CI analyze gate stays green).
- `admin: npx tsc --noEmit` → clean.
- worker/ untouched this revision (still at r14-verified state, 58/58 tests).

## Version

- 2.7.1+27 → **2.8.0+28** (pubspec, constants.dart, build.gradle).
