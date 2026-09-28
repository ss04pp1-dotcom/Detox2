# R20 (v2.9.4) — 3 user-reported bug fixes + junk removal

User reports against v2.9.3 (all in one message):

1. **"YouTube Shorts used to be detected well — now it's not, and the app
   kicks me out entirely."**
2. **"One volume-button press registers as several presses."**
3. **"Suddenly caged me for 'watching shorts' while I was in Chrome, not
   watching shorts."**
4. **"Remove all the junk code — keep only what's needed so it's bug-free."**

## Root causes found

### RC-1 — BACK-press machine-gun (report 1)
r18's unified redirect used **BACK-first for every platform** plus a
closed-loop verifier that re-ran the ladder every ~900 ms. When Shorts was
the app's root surface (launcher shortcut / restored task), BACK does not
close the player — so the loop kept pressing BACK until YouTube's
double-back-to-exit fired and the user was thrown out of the app, on every
re-entry.

### RC-2 — SystemUI events attacked on sight (report 2)
`handleForeground` fired `DISMISS_NOTIFICATION_SHADE` **plus a raw BACK**
on **every** `com.android.systemui` window-state change while any mode was
active — including the **volume dialog**, recents, power menu and heads-up
notifications. One volume press ⇒ stray global actions injected (the extra
BACK also "ate" real back presses in the app behind).

### RC-3 — Zombie cage (report 3)
Three stacked sources:
- `LockController.cage()` defaulted to a **30-minute** cage when no
  persisted cage existed;
- pre-r18 in-session shorts attempts persisted a **30-minute** cage
  (`SessionEngine.activateCage`, `cageDurationSeconds=1800`) — DataStore
  survives app updates, so a leftover kept re-asserting on the new build;
- `EnforcementWall.isCageActive()` also consulted that persisted snapshot,
  whose `elapsedRealtime`-based end **warps across reboots** (uptime resets)
  — a stale cage could read "active" for days and `reassertCage` covered
  every app the user opened. Chrome was never detected as shorts (it isn't
  even in the shorts platform config) — the cage found the user there.

## Fixes

### ReelsRedirect — per-platform ladder + rate limit (RC-1)
- **YouTube / Instagram (+ lite): HOME_TAB → ROOT_REVISIT → BACK(last)**.
  The bottom-nav Home tab is visible on the shorts/reels surface; clicking
  it lands on the feed — the exact requested behavior. BACK, the only rung
  that can exit the app, is demoted to last resort.
- **Facebook (+ lite): BACK → HOME_TAB → ROOT_REVISIT** (fullscreen player
  hides the nav bar; BACK is the only closer).
- **Per-package rate limit: min 2 s between navigate attempts** — content
  events, re-scans and the verifier can no longer hammer rungs.
- Episode verifier: **never navigates blind** — if the target app no longer
  owns a window, the episode closes and the loop stops (no BACK into the
  launcher, no exit-summing).
- Dead rung bookkeeping (`rungs`/`rungAt`/`advanceRung` — never read) and
  the now-unreferenced `navigateFromContext` removed.

### SystemUI branch — denylist + no raw BACK (RC-2)
- Mirrors ShadeGuard's `NOT_SHADE_HINTS` (volume, recents, globalactions,
  clipboard, ime, screenshot, bubbles, wallet, mediacontrol, workspaces,
  keyguard): denylisted classes are left alone.
- API 31+: **DISMISS only**; below 31: BACK only (the old DISMISS+BACK
  combo stole a back press from whatever was behind).

### Cage — one cage, in-memory, 60 s (RC-3)
- `EnforcementWall.isCageActive()` reads the **in-memory** countdown only.
- `LockController.cage(context, endElapsed)` — caller passes the end (the
  reels burst cage passes 60 s); the 30-minute default is gone. The
  activity fallback (`LockScreenActivity` CAGE) now receives the cage end
  via intent extra instead of mis-reading persisted state.
- `SessionEngine.recoverIfNeeded()` **clears any persisted cage** at
  process start / boot — kills legacy leftovers and reboot-warped snapshots
  in one shot. Nothing in production persists a cage any more.

### Chrome URL strategy — removed entirely (false-positive class)
- `DetectionRules`: chrome/chrome_beta platform entries, `Shape.URL`,
  `DEFAULT_URL_SHAPES`, `PKG_CHROME*` constants removed; `MAX_PLATFORMS`
  7 → 5 (matches the worker again).
- `ReelsDetector`: `detectByUrl` + `Surface.URL` removed.
- `ReelsRedirect.NO_SAFE_SURFACE` = TikTok packages only.
- Worker: platform whitelist + defaults + seeds (005 / full_schema /
  schema) cleaned; `urlShapes` stays an accepted **legacy** key (old stored
  docs still validate) but no longer counts as a signature.
- Admin: platform list/labels/fields/mock cleaned.
- An already-published ruleset still containing chrome is rejected **whole**
  by the app's strict validator → compiled defaults apply (fail-safe).

### Dead code removed ("habijabi")
- `ReelsOverlayActivity` — unreachable since r18 (nothing started it);
  file + manifest entry deleted. Its `onHardLockoutFinished` chain
  (ringtone / completion notification / force-stop) removed from
  `ReelsEscalationManager`; dead strings removed from `strings.xml`.
- `EnforcementWall` `KIND_SHORTS_LOCKOUT` surface (no callers) removed.
- Unused imports dropped across the touched files.

## Verification
- `flutter analyze` — No issues found
- `:app:compileReleaseKotlin` — BUILD SUCCESSFUL
- worker `tsc` clean; **vitest 60/60 PASS** (incl. 3 new r20 tests:
  urlShapes-no-longer-a-signature, chrome-platform-rejected, oversized-doc
  reworked for 5 platforms)
- admin `tsc` clean

Version: 2.9.3+32 → **2.9.4+33** (pubspec, constants.dart, build.gradle).

## On-device test checklist
1. YouTube Shorts (from feed AND from the launcher shortcut): toast +
   quiet landing on the Home feed — the app must NEVER exit, no matter how
   long you stay.
2. Volume presses during a Study/Detox session/monk/lock: volume HUD
   behaves like normal — no flicker, no stolen presses.
3. The 60-second burst cage (5 rapid re-entries) opens and **auto-closes
   at zero**; after it closes, switching apps (incl. Chrome) never
   re-cages.
4. If a stale cage from the old build was active: it clears on the first
   app launch after update.
5. Chrome browsing (any URL): never blocked as shorts.
