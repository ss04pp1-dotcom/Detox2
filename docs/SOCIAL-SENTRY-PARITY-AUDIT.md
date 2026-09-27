# Social Sentry parity audit (r9.2)

Source: `report-enforcement.md` (40 mechanisms), `report-modes.md`,
`report-engagement.md`, `MASTER-ANALYSIS.md`. `report-backend.md` was NOT
available, so the backend / monetisation layer is not audited here.
Method: static code reading of this repo. Nothing was run on a device.

Legend: OK = present and consistent with the report · FIXED = broken in r9,
fixed in r9.1 / r9.2 · PARTIAL = present, weaker than the reference ·
MISSING = not built · N/A = deliberately not applicable / not ported.

## A. Enforcement — the 40 mechanisms

| # | Mechanism | Status | Notes |
|---|---|---|---|
| 1 | A11y event pipeline | OK | state + content events; throttled |
| 2 | Engine heartbeat | OK | `EngineStateStore` |
| 3 | Engine 2 usage-stats poll 850 ms | FIXED / PARTIAL | sessions + monk, and (r9.5) schedules + app limits even with no session; not reels (needs view-ids = accessibility) |
| 4 | Engine 1 <-> 2 handoff | FIXED | stale persisted "running" flag made restart a no-op (r9.2) |
| 5 | AccessibilityGuard watchdog | FIXED | was one-shot; now periodic + idempotent (r9.2). No WorkManager twin |
| 6 | LockMyPhone guard | PARTIAL | covered by `ServiceRevival` inside the shared guard job, not its own job |
| 7 | MonkModeGuard | OK | job 9932-style, periodic |
| 8 | Engine-2 loop revives dead lock services | FIXED | `ServiceRevival` from the 5 s tick (r9.2) |
| 9 | DPM `lockNow()` loop | FIXED | 1.5 s loop existed; call mode added (r9.2) |
| 10 | Admin disable warning copy | OK | context-aware |
| 11 | Uninstall interception | FIXED | was ineffective (event text only); deep scan added (r9.2) |
| 12 | Multilingual keywords + OEM list | PARTIAL | EN + BN only (DE/FR/ES/IT skipped); OEM list now equals reference |
| 13 | Private-DNS tamper (Prime) | N/A | protects SS's DNS-filter feature, which MAXLEVEL lacks; left unwired on purpose |
| 14 | NotificationListener blocker | OK | |
| 15 | Boot recovery | OK | sessions, alarms, guard, LMP, monk |
| 16 | Update recovery | OK | `MY_PACKAGE_REPLACED` (no 8 s settle delay) |
| 17 | Auto-reblock monitor | OK | 5 s tick |
| 18 | Scoped cooldown store | N/A | coin-based temp unlock model instead |
| 19 | Schedule ticker | PARTIAL | evaluated lazily per foreground event (a11y or engine 2 since r9.5); no separate 60 s ticker |
| 20 | onUnbind tamper counter / telemetry | PARTIAL | hands off to engine 2; no disable counter |
| 21 | Permission snapshot | OK | `PermissionMonitor` |
| 22 | Settings-exemption window | OK | grace window (also honoured by uninstall interceptor since r9.2) |
| 23 | Back-key consumption | FIXED | needed `canRequestFilterKeyEvents` (r9.1); DOWN+UP consumed (r9.1) |
| 24 | A11y overlay stacking | OK | `EnforcementWall` / `A11yOverlayController` |
| 25 | Countdown-gated buttons | OK | |
| 26 | Force-stop + recents flush | FIXED | reels lockout + (r9.5) every hard block kills the app's background process |
| 27 | `exitTheDoom` | N/A | internal helper |
| 28 | Reels / Shorts detection | FIXED | in-session path used weak text hints (r9.1) |
| 29 | Escalating hard lockout | OK | 60 s reset window, 3rd attempt hard |
| 30 | FGS crash-loop protection | FIXED | counters never reset (r9.2). No WorkManager retries |
| 31 | FGS `onTimeout` | OK | |
| 32 | Task removal != termination | FIXED | (r9.5) LMP + engine 2 re-arm a restart alarm on task removal, like monk / enforcement |
| 33 | Recovery from persisted session | OK | |
| 34 | OEM detection + messaging | FIXED | autostart / background deep links added (r9.2); no OEM-specific warning copy |
| 35 | Study productivity allowlists | FIXED | (r9.5) 12 grouped allowlists (phone, camera, gallery, calculator, notes, AI, docs, scanner, classes, files, translate, Chrome) across common OEM package names |
| 36 | Stub-FGS bootstrap | N/A | direct start with try/catch instead |
| 37 | Pairip / Play Integrity | MISSING | separate Play Console work, not a code port |
| 38 | Signature permission for broadcasts | PARTIAL | receivers are non-exported; no custom signature permission |
| 39 | Dynamic a11y flag narrowing | N/A | stealth / battery trick, not wanted |
| 40 | "Protection degraded" notification | OK | `GuardNotifier` |

## B. Modes (report-modes)

| Mode | Status | Notes |
|---|---|---|
| Focus / Study | FIXED / PARTIAL | timed session, alarms; study break (pause/resume, bounded) + subjects + per-subject time with a 04:00 study day (r9.4). Reboot no longer stretches a session (r9.4). No presence sync (needs the backend) |
| Force Mode | PARTIAL | Detox + Schedules cover it; no whitelist/blocklist `modeType`, no default productivity sets |
| Prime | PARTIAL | TOTP give-up, replay guard, relapse log; no DNS / uninstall lock owned by the commit, simpler than SS's 41-field profile |
| Monk | OK | call handling via audio-mode listener on API 31+, usage-event tick below |
| Lock My Phone | FIXED / PARTIAL | call mode (r9.2); scheduled one-shot + weekly recurring windows and the Start-button fix (r9.3). No lock-overlay UI, no call-never-connected trap (there is no dialer access to abuse) |
| Safety (pause) | OK | 30 s re-trigger suppression, BACK swallowed; no usage-shaming stats (intentional) |
| Schedules | PARTIAL | profile windows, overnight wrap; no snapshot/restore overlay of settings |
| App limits | OK | usage-based limit, escape via coin temp-unlock or emergency TOTP |
| Reels blocker | OK | per-app signatures, 3 passes/day, quota, ladder, ringtone; no per-app scan delays |
| Temp unblock | N/A | coin-based instead of minute budget + midnight reset |
| Emergency codes | OK | per-user secret TOTP, 20-min replay guard, no universal master code |
| Scroll limiter | N/A | dormant in the reference too |

## C. Engagement (report-engagement)

| System | Status | Notes |
|---|---|---|
| Sinthia chat (LLM via Worker) | OK | 4 personalities, roast mode, 6-digit redaction, context snapshot |
| Hourly check-ins | OK | `SinthiaCheckIn` |
| Aura / DP economy, levels, caps | OK | 11 levels; caps live in `ProgressEngine` (numbers not re-checked against 1500/8000/30000) |
| Streaks, freezes, recovery grace, relapse | OK | |
| Tasks / routines economy | OK | |
| Community commits, friends, referral | OK | worker routes + screen |
| Clubs, leaderboard, club chat | MISSING | |
| Sinthia conversation history | OK | persisted natively (`getCompanionHistory` / `clearCompanionHistory`); an earlier version of this audit wrongly listed it as missing |
| Widgets | PARTIAL | 5 providers, but no distraction-trend widget (has brain-rot) |
| Brain-rot stages + HUD | OK | |
| Pause allowance | OK | break passes |
| Aura decay, abuse-escalation copy, shame graphs | N/A | rejected on ethical grounds |

## D. Not in the port plan (Social Sentry has them, MAXLEVEL does not)
Adult-content blocker, NSFW image shield (ML Kit), browser protection, custom
website blocker, DNS-filter setup, FAHH mode, scroll limiter. None of these
were in the port plan phases A–D.

## E. Priority for a future build
1. ~~Lock My Phone scheduled / recurring starts (+ UI)~~ — done in r9.3.
2. ~~Study pause / resume + subjects~~ — done in r9.4.
3. ~~Engine 2 enforcing schedules and app limits~~ — done in r9.5.
4. ~~`onTaskRemoved` re-kick for LMP / engine 2~~ — done in r9.5. WorkManager-backed FGS retry is still open (needs a new dependency).
5. ~~Default productivity allowlists~~ — done in r9.5 (Study setup; Detox has none by design).
6. Clubs / leaderboard (needs Worker + D1 + UI), distraction-trend widget.
