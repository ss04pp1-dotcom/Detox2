# R12 — v2.6.0 Reference Design Release

Matched the Flutter UI to the user's two reference mockup images (the
"MAXLEVEL DETOX — Complete UI/UX Design" boards, v2.5.9 spec). The changes
are visual/layout only — no enforcement, storage, or backend semantics
changed.

## Design tokens (`lib/core/theme/tokens.dart`)
- Added the mode accent palette from the mockups — every enforcement mode
  now carries its own signature color, used consistently across its icon
  containers, chips, buttons and rings:
  - Study Mode — teal `#00D2A0` (+ dim `#00A080`)
  - Detox Mode — fuchsia `#D946EF` (+ dim `#A21FAF`)
  - Monk Mode — amber `#F59E0B` (+ dim `#D97706`)
  - Lock My Phone — red `#EF4444` (+ dim `#DC2626`)
  - Safety Pause — cyan `#22D3EE` (+ dim `#0891B2`)
  - Prime Commit — violet `#7C3AED` (+ dim `#6D28D9`)

## Shared components
- `MLDTimer` (`shared/mld_timer.dart`): the ring is now the mockup's
  signature visual — 10px rounded-cap stroke with a soft outer glow
  (blurred under-pass) and a two-stop sweep gradient on the arc.
- `MLDStatusChip` (new, `shared/mld_widgets.dart`): tinted pill with a
  colored dot — "RUNNING • 1H 25M LEFT", "TOTP VERIFIED", etc.
- `MLDModeTile` (new): quick-action grid tile — tinted icon container,
  title, one-line promise, each in its mode accent.

## Dashboard (`features/dashboard/dashboard_screen.dart`)
- Bottom navigation is now the mockup's five tabs: **Home, Insights,
  Community, Tasks, Settings** (previously Home/Focus/Detox/Insights/
  Settings). Study/Detox setups moved into Quick Actions.
- Home header: greeting ("Good morning.") + "Focus today, build tomorrow."
  + gradient LVL pill (mockup Screen 17).
- Stats row: Current Streak / DP Points / Shorts attempts stat cards.
- Today's Focus hero: the No Active Session card (mockup Screen 18) with
  the choose-a-mode CTA pair.
- Quick Actions: six-tile mode grid — Study Mode "Focus & Learn", Detox
  Mode "Full Digital Detox", Monk Mode "No Distractions", Lock Phone
  "Full Lock", Safety Pause "Think & Continue", Prime Commit "Ultimate
  Focus" (mockup Screen 25).
- More Tools list keeps every previous home entry point (Shorts Blocker,
  Shockwave Alarm, Coins, History, Sinthia, Tasks, Community) reachable.

## Active session (`features/session/active_session_screen.dart`)
- Mode icon in a tinted circle + mode title + status chip
  ("RUNNING • 1H 25M LEFT" / unlock-active variant) — mockup Screens 19/20.
- Glowing gradient ring timer with "Remaining" label.
- Control row: **Pause / End / Details** (mockup); Details opens a session
  summary sheet (mode, time, progress %, apps, breaks, warnings).
- Temporary Unlock and Emergency Call stay on the main surface (PRD §27:
  emergency is always discoverable).

## Hard modes
- Monk Mode (Screen 21): amber accent hero, "IN PROGRESS • Xh Ym" chip,
  "Emergency Call Available" chip, hold-to-confirm relabeled
  "END MONK MODE".
- Lock My Phone (Screen 28): red accent, status chip, glowing ring timer,
  "Use Coins" / "View Schedule" buttons. Native `statusJson` now also
  exposes `totalSeconds` (additive; defaults 0) so the ring shows real
  progress.
- Safety Pause (Screen 23 style): cyan accent hero card on the setup
  screen.
- Prime Commit (Screen 24): violet accent hero, "ACTIVE • Xh Ym LEFT"
  chip, big violet timer, "TOTP VERIFIED" chip.

## Completion (`features/session/completion_screen.dart`)
- Mockup Screen 27: "Session Completed!" + "Great job! You stayed
  focused." + three stat cards (Focus Time / Attempts Blocked / DP
  Balance) + honest bailout status banner + "BACK TO HOME".

## Version
- `versionName`/`versionCode`: 2.6.0 / 24 (pubspec `2.6.0+24`,
  `constants.appVersion` 2.6.0).
