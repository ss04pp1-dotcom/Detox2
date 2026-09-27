# R11 Changes — v2.5.8 Product Roadmap (Community, Widget, Remote Rules)

Three roadmap features shipped end-to-end, plus the moderation surface and
test coverage they require. Everything in this round is additive: no existing
route contract, DB column or enforcement behavior changed shape.

## 1. Dynamic Remote Rule Config — Reels/Shorts detection robustness

**Problem:** reels detection was signature-based (View IDs, content-desc
hints, URL shapes baked into `ReelsDetector`). When YouTube / Instagram /
TikTok / Facebook ship a UI update, signatures drift and detection silently
breaks until the next app release.

**Solution — a full server-pushed pipeline:**

| Layer | File | What it does |
|---|---|---|
| Worker model | `worker/src/services/detectionRules.ts` | frozen schema v1 (`schemaVersion`, `minAppVersion`, 7 platforms), strict validator (unknown platforms AND unknown rule keys rejected, per-list caps 25 entries / 3-64 chars / charset regexes, 20 KB doc cap), `DEFAULT_DETECTION_RULES` byte-equal to the compiled signatures |
| Worker routes | `worker/src/routes/detection.ts` | app: `GET /api/v1/detection/rules` (unseeded → defaults + version 0; corrupt → fail-safe defaults). Admin: `GET/PUT /admin/detection-rules`, `POST .../publish`, `POST .../reset` — the same DRAFT/PUBLISHED/ARCHIVED versioning model as remote config, every mutation audited (`DETECTION_RULES_*`) |
| D1 | `detection_rules` table (migration 005, seeded PUBLISHED v1) | one draft, one published, full history for rollback |
| Admin UI | `admin/src/pages/DetectionRules.tsx` | per-platform list editors, publish confirm modal, version history, reset-to-defaults |
| Flutter | `_syncDetectionRules()` (cold start + every 15 min) | fetch → `minAppVersion` gate (a ruleset for a newer app is skipped, last-known-good kept) → forward raw JSON |
| Kotlin | `reels/DetectionRules.kt` | strict re-validation of the payload (same rules as the worker — a bad doc is rejected whole), atomic `@Volatile` ruleset swap, DataStore cache restored at process start (`MldApp.onCreate`), `supportedPackages()` cache for the a11y hot path |
| Detector | `reels/ReelsDetector.kt` | data-driven strategies (`VIEW_ID`, `PACKAGE_GATE`, `TEXT_BFS`, `URL`) reading signatures via `DetectionRules.resolve(pkg)`; remote platform entries are COMPLETE REPLACEMENTS, absent platforms keep compiled defaults, `enabled:false` stands the platform down |

**Security boundary (deliberately NOT remotely configurable):** strategy
shapes, the bounded BFS budgets (500 nodes / 40 children), debounces, the
escalation ladder, and the package→platform mapping stay compiled. Rules can
only add/subtract signatures — never weaken enforcement.

## 2. Community Leaderboard + Clubs (DP competition)

**Worker** (`worker/src/routes/leaderboard.ts`, 623 lines):
- `POST /leaderboard/opt-in` — participation toggle + display mode
  (`name` | `anonymous`). Opt-in is the ONLY way onto any board; sync can
  never silently opt a user in.
- `POST /leaderboard/sync` — device DP snapshot mirror. Lifetime DP is
  **monotonic** (`max(server, device)` — a reinstall cannot wipe a standing,
  a tampered client cannot lower someone else's); window DP follows the
  device's own ISO week/month anchors (same anchor → max, new anchor →
  replace). Server-side sanity caps (lifetime ≤ 1M, week ≤ 20k, month ≤
  100k, streak ≤ 3,650).
- `GET /leaderboard/global?window=weekly|monthly|alltime` — top-50 + my rank.
- Clubs: `POST /clubs` (auto-leaves current, 6-char invite code, 5 collision
  retries), `GET /clubs/mine|search`, `POST /clubs/join/:code`, `POST
  /clubs/leave` (last-member GC), `GET /clubs/:id` (club leaderboard slice).
- **Privacy:** entries never expose user ids/emails; anonymous mode renders a
  deterministic pseudonym (`Detoxer #<FNV-1a 4-hex>`); hidden clubs never
  surface in search/join; the club invite code is returned to members only.
- R11 hardening: club name is re-validated after trim (3-40 chars), deleted
  accounts are scrubbed from `club_members`/`leaderboard_profiles`, and the
  scheduled handler sweeps memberless clubs older than 24h.

**Admin moderation** (`worker/src/routes/adminLeaderboard.ts` + `admin/src/pages/Leaderboard.tsx`):
`GET /admin/leaderboard` (totals + top-50 per window + search),
`POST /admin/leaderboard/users/:id/reset` (zeroes a farmed profile + forces
opt-out, reason-gated, audited), `GET /admin/clubs` (paginated, filter,
search), `POST /admin/clubs/:id/hide|restore|delete` (confirm-gated,
audited; delete removes memberships). Anonymous display mode is honored in
the admin board too.

**Flutter** (`community_screen.dart` Leaderboard tab): opt-in wall with
name/anonymous choice, immediate first sync, window switcher, global +
club boards with `isMe` highlight, club create/join-by-code/**search by
name** (debounced sheet), leave + GC, invite-code copy. The periodic sync
mirrors the DP snapshot only while the opt-in flag is set locally.

**Kotlin:** `ProgressEngine.leaderboardSnapshotJson()` — read-only DP
projection (`{lifetimeDp, weekDp, weekAnchor, monthDp, monthAnchor,
streakDays, levelName}`); `NativeBridge.getLeaderboardSnapshot` exposes it.

## 3. Distraction Trend home-screen widget

**Kotlin** (`widgets/TrendWidget.kt` + `widget_trend.xml` + `widget_trend_info.xml`):
- The 6th home-screen widget: a 7-day bar chart with two series the user
  switches by TAPPING the chart (per-instance pref): **distraction
  screen-time** (daily distracting-app minutes) and **reels skipped** (daily
  blocked shorts attempts).
- Pure-Canvas chart (no chart dependency), transparent over the card, today
  highlighted, zero-days visible as stubs; resize-aware
  (`onAppWidgetOptionsChanged`), per-instance prefs cleaned on delete.
- `TrendSampler` keeps the distraction series honest: UsageStats is
  cumulative, so the daily stat is a HIGH-WATER mark sampled at most every
  10 min — fed by the ForegroundAppMonitorService sweep AND the widget's own
  30-min launcher tick (works with the monitor stood down).
- Room v4 migration adds `daily_stats.distractingMinutes`
  (`MIGRATION_3_4`, high-water bump via `bump(..., "distractingMinutesMax")`).
- Reads NATIVE state only (works with Flutter dead); zero enforcement
  authority; every render guarded so the launcher can never crash.
- Flutter: 6th pin tile in the Widgets settings screen (`pinWidget 'trend'`).

## Tests (58 total, up from 41)

- `worker/test/detectionRules.test.ts` (13) — the validator contract
  (defaults, unknown keys/platforms, caps, charset, tiktok exemption).
- `worker/test/leaderboard.test.ts` (17, NEW) — handler-level: opt-in
  gating, sync-cannot-opt-in, monotonic lifetime, week-anchor rollover,
  anchor validation, anonymous pseudonym + no id leak, club create/join/
  leave/GC, invite-code privacy for non-members, hidden-club invisibility,
  coin earn idempotency + negative-mirror skip, admin reset/hide/restore/
  delete effects, admin board pseudonym honoring — against a hand-rolled
  in-memory D1 double (pattern dispatch; unknown SQL fails loudly).

## Deployment notes (see RELEASE-CHECKLIST.md)

1. `wrangler d1 migrations apply DB` — **migration 005** (new tables only:
   `detection_rules`, `leaderboard_profiles`, `clubs`, `club_members` +
   indexes + the seeded PUBLISHED ruleset v1). Safe on existing deployments.
2. Worker + admin redeploy as usual; the app picks everything up within one
   15-minute sync cycle — no forced update needed.
3. Room 3→4 migration ships in the APK (v2.5.8, versionCode 22).

## File inventory (this round)

New: `DetectionRules.kt`, `TrendWidget.kt`, `widget_trend.xml`,
`widget_trend_info.xml`, `worker/{routes/detection.ts, routes/leaderboard.ts,
routes/adminLeaderboard.ts, services/detectionRules.ts, db/migrations/005_r11_features.sql}`,
`worker/test/{detectionRules.test.ts, leaderboard.test.ts}`,
`admin/src/pages/{DetectionRules.tsx, Leaderboard.tsx}`.
Modified: router (`index.ts` + 6 new admin routes + scheduled club sweep),
schema.sql, audit action union, admin client/types/mock/App/Layout,
NativeBridge (applyDetectionRules / getDetectionRulesStatus /
getLeaderboardSnapshot), ProgressEngine, ReelsDetector (data-driven),
MldApp (rules restore), MldWidgets (6th provider), widgets_screen, main.dart
(sync loops + minAppVersion gate), community_screen (leaderboard tab),
api_client / native_bridge (leaderboard + detection methods), versions
2.5.6 → 2.5.8 (versionCode 22).
