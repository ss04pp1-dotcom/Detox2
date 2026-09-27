# R13 — v2.7.0: User-Reported Fixes

Four changes from first real-device feedback, plus build-hardening.

## 1. Reels/Shorts: in-app safe navigation (was: kick-out)

**Before:** detecting a shorts/reels feed performed `GLOBAL_ACTION_HOME` —
the user was thrown out of the app entirely.

**Now:** the app is navigated to its own safe surface and the user STAYS
inside it. New `reels/ReelsRedirect.kt` ladder (first success wins):

1. **Node click** — the a11y tree is scanned (bounded BFS, bottom ~42% of
   the window = nav zone) for the app's own bottom-nav Home tab (exact
   label match, English + Bengali) and clicked. The most native
   navigation possible.
2. **Root revisit** — the app's own launcher intent with
   `CLEAR_TOP|SINGLE_TOP`: its root activity (YouTube Home / FB News
   Feed / IG Feed) comes forward and the stacked shorts activity is
   finished. Same app, same task — never the launcher.
3. **HOME** — the old kick-out, fallback only.

Applies to BOTH detection paths (in-session ladder + out-of-session
escalation) and to the hard-lockout finish (previously force-stopped the
app; now navigates to the safe surface — force-stop remains the fallback
for platforms with no safe surface: TikTok / browser shorts tabs).

## 2. Emergency: dialer-only lockdown (was: enforcement effectively gone)

**Before:** the Emergency button opened the dialer — and around it the
phone behaved unenforced; the user could wander anywhere from there.

**Now:** pressing Emergency (session screen, monk overlay, anywhere)
starts the **EmergencyLockdown** (`safety/EmergencyLockdown.kt`) and opens
the dialer. While the lockdown is live:

- the phone is a **dialer and nothing else** — every other app is bounced
  straight back to the dialer by the a11y foreground pipeline (with a
  toast + violation record), and by the monk-mode tick while monk is
  active;
- enforcement never stands down — sessions, monk, schedules, limits all
  stay armed;
- our own app stays reachable so the user can deliberately END the
  emergency from the new `MLDEmergencyBanner` (dashboard home, active
  session, monk screens);
- **15-minute safety cap** (reboot-safe) so a forgotten lockdown can
  never trap anyone.

## 3. Insights: APP USAGE TODAY (user-requested)

"What ran, for how long" — a new card in Insights ranking every app by
today's real foreground minutes with share-of-day bars, a total
screen-time line, and an access hint. Fixes `UsageTracker`'s day window
to LOCAL midnight (the old UTC approximation started "today" at 06:00
Bangladesh time).

## 4. Loading screens (offline hardening)

Root cause: the production Worker is not deployed —
`api.maxleveldetox.com` does not resolve, so every cloud call failed
after a 12-second timeout; community tabs spun the whole time.

- HTTP timeout 12s → 6s;
- community empty states now say "or you may be offline — pull to retry".

## Build hardening

- `mergeReleaseNativeDebugMetadata` disabled (Play symbolication only;
  its unstripped-Flutter-engine zip blew the build disk budget).
- CI/build env: Flutter 3.44.9 / JDK 17 (Temurin) / AGP 8.7.3.

**Version:** 2.7.0 (versionCode 26).
