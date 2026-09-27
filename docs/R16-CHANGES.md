# R16 — User-Reported Bug Fixes (v2.9.0)

Five fixes reported on-device after v2.8.0.

## 1. YouTube Shorts detection misses ("majhe majhe miss kore")

Root cause: detection relied solely on TYPE_WINDOW_CONTENT_CHANGED events +
the single `reel_watch_fragment_root` view id — UI-tree drift and event
batching/throttling left mid-scroll gaps, and the scan silently returned
null whenever the reels surface was NOT the "active window" root.

Fixes (DetoxAccessibilityService + ReelsDetector + DetectionRules):
- **Activity-name detection (new strategy)**: WINDOW_STATE_CHANGED's
  `className` (e.g. `com.google.android.youtube.shorts.ShortsActivity`)
  matched against a new `activityHints` signature list (lowercased
  contains). Instant (no tree walk) and drift-resistant — activity class
  names survive UI redesigns. Compiled defaults seeded for YouTube
  (`shortsactivity`), Facebook/lite (`reelsactivity`, `reelactivity`),
  Instagram (`clipsactivity`); remotely updatable via the existing
  detection-rules pipeline.
- **Multi-window root scan**: when `rootInActiveWindow` belongs to another
  package, fall back to the a11y window list to find the pkg's own root
  (closes the "player in another window layer" miss).
- **Scheduled re-scans** (+500 / +1500 / +3000 ms) after entering a
  monitored app — covers late-inflating shorts UI.
- In-session shorts cooldown 10 s -> 6 s.

## 2. Facebook Reels detected but never returned to the feed

Root cause: `ReelsRedirect.tryRevisitRoot` returned TRUE merely because
`startActivity` didn't throw — on Facebook the launcher-intent/CLEAR_TOP
revisit routinely does NOT clear the fullscreen reels activity, so the
caller skipped its HOME fallback and the user stayed on reels.

Fix (ReelsRedirect — closed-loop attempt ladder):
- **Rung 1 (new): BACK press** — the immersive reels/shorts player is a
  fullscreen surface on top of the app's own feed; BACK is exactly what a
  manual user does and is the most reliable in-app exit.
- Rung 2: click the app's own bottom-nav Home tab; Rung 3: root revisit;
  Rung 4: caller's HOME kick-out.
- Each detection advances the per-package rung (reset after 20 s clean);
  the scheduled re-scans from fix 1 double as verification — a redirect
  that did not stick re-detects and climbs the ladder automatically
  instead of silently "succeeding".

## 3. Uninstall protection never works

Root cause: the a11y scraper's bound required an ENFORCING SESSION — with
only schedules/app-limits/monk/lock-my-phone active it stood down
completely; and no uninstall shield existed outside lock-my-phone's own
device-admin usage.

Fixes:
- **Uninstall Protection toggle (Settings -> SECURITY)**: arms our
  force-lock device admin via the system dialog. While armed, Android
  itself refuses the standard uninstall paths (launcher drag, Play
  Store, Settings App Info), and the scraper now also runs — including
  against admin-DEACTIVATION screens.
- **Widened scraper bound**: interception now runs while ANY enforcement
  surface is live (session OR always-on schedules/app limits OR monk OR
  lock-my-phone OR shield armed). With nothing armed the user may freely
  uninstall (ethics bound unchanged, TRD §22).
- Bridge: `getUninstallProtection` / `requestUninstallProtection` /
  `disableUninstallProtection`.

## 4. Emergency exits killed the whole system

Root cause (the big one): TWO legacy emergency paths predated the r13
dialer-lockdown and were never migrated — EnforcementWall.openDialer()
and LockController.openDialer() both stood their wall down for 90 s and
opened a bare dialer WITHOUT starting EmergencyLockdown. Pressing
Emergency from the enforcement wall or the lock screen (the surfaces the
user actually sees when blocked) left the ENTIRE phone unenforced for
90 s — "ja issa kora jay".

Fixes:
- Both paths now start `EmergencyLockdown` (dialer-only kiosk, every
  non-dialer app bounces back) and open the dialer through it; the
  90-second stand-down windows are gone.
- LockController's re-assert loop and EnforcementWall.reassert stand
  down while the lockdown is live (they must never cover the dialer).
- Emergency-bounce violation recording throttled to 1 per app / 5 s
  (the bounce loop previously hammered the DataStore write path).

## 5. Custom duration picker advanced

The old "type total minutes" text field (min 10 study / min 30 detox) is
replaced by a shared `MLDDurationPicker` (mld_widgets.dart):
- HOURS and MINUTES set separately with steppers,
- from ONE minute (SessionEngine already accepts 1–1440),
- quick-pick chips (1m…3h), live total readout with range validation,
- used by both Study and Detox custom-duration chips.

## Detection-rules schema (worker + admin mirrors)

- `activityHints` added to PlatformRules (Kotlin + worker + admin
  types/defaults/field metadata). Validated like other text-hint lists
  (printable ASCII, <=25 entries, lowercase-normalized server-side).
- Worker DEFAULT_DETECTION_RULES updated in lockstep with Kotlin
  COMPILED_DEFAULTS.

## Version

2.8.0+28 -> **2.9.0+29** (pubspec, constants.dart, build.gradle).
