# MAXLEVEL DETOX — Architecture

## 1. System map (PRD §64)

```
                         MAXLEVEL DETOX
                              │
              ┌───────────────┴────────────────┐
              │                                │
        MOBILE PRODUCT                    CLOUD PRODUCT
              │                                │
        Flutter UI                         Cloudflare
              │                              │
        MethodChannel                    Worker API
              │                              │
        Kotlin Native                       D1
              │                              │
   ┌──────────┼──────────┐              KV / DO
   │          │          │                  │
Study      Detox      Shorts             Admin
Mode       Mode       Blocker            Panel
   │          │          │                  │
   └──────────┼──────────┘                  │
              │                             │
       Enforcement Engine          Cloudflare Pages
              │
       Android OS Services
```

**Golden rule:** «Cloudflare manages the product. Kotlin manages the phone.
Flutter manages the experience.»

## 2. Repository layout

```
maxlevel-detox/
├── mobile/                    Flutter + Kotlin Android app
│   ├── lib/                   UI, navigation, design system, API client
│   └── android/…/kotlin/      Enforcement engine (security boundary)
│       ├── enforcement/       SessionEngine, PolicyEngine, LockController,
│       │                      EnforcementService, ViolationManager, models
│       ├── accessibility/     DetoxAccessibilityService, ShortsDetector
│       ├── coins/             CoinLedger (immutable, idempotent)
│       ├── unlock/            TempUnlockManager
│       ├── alarm/             ShockwaveAlarmEngine + native puzzle activity
│       ├── recovery/          BootRecoveryReceiver, EnforcementReceiver,
│       │                      PermissionMonitor
│       ├── storage/           StateRepository (DataStore), MldDatabase (Room)
│       ├── config/            RuntimeConfig (bounded remote config)
│       └── bridge/            NativeBridge (MethodChannel + EventChannel)
├── worker/                    Cloudflare Worker API (TypeScript, no deps)
│   └── src/{routes,middleware,services,utils,db}
├── admin/                     Admin SPA (React+TS+Vite+Tailwind+recharts)
└── docs/                      this file + SECURITY_MODEL.md
```

## 3. Session state machine (unified superset)

```
IDLE ──▶ ARMED ──▶ STARTING ──▶ ACTIVE ⇄ TEMP_UNLOCK
                        │            │
                        │            ├──(shorts escalation)──▶ CAGE*
                        │            ├──(end time)──▶ COMPLETING ──▶ COMPLETED ──▶ IDLE
                        │            └──(bailout: atomic coin spend)──▶ BAILOUT ──▶ IDLE
                        └──(process death / permission loss)──▶ RECOVERY ──▶ ACTIVE
                        any ──(unvalidated state)──▶ ERROR (safe hold, never auto-unlock)

* Cage is tracked as independent persisted state (cageActive + elapsed
  bounds), not a session status — it can outlive / exist without a session.
```

- Timer authority: `SystemClock.elapsedRealtime()` offsets persisted in
  DataStore; recalculation after restart = `endElapsed - elapsedRealtime()`.
- `WARNING` is a shorts-level counter (shared, daily, cross-platform), not
  a session status.
- `PENALTY` is the bailout coin-spend step inside `executeBailout()`.

## 4. Enforcement loop

```
foreground package event (accessibility)
  → package + session + cage + tempUnlock + graceWindow (DataStore reads)
  → PolicyEngine.evaluate (order: emergency → system → cage → settings →
    session allowlist → temp unlock → categories → strictness default)
  → BLOCK ⇒ performGlobalAction(HOME) + LockScreenActivity + violation
  → Shorts target app ⇒ ShortsDetector (bounded node scan, in-memory)
       ⇒ warning 1..N (shared counter) ⇒ N+1 ⇒ Cage (persisted + alarm)
```

Completion is **lazy + prompted**: every policy read and a periodic
foreground-service sweep evaluate `elapsedRealtime >= endElapsed`; exact
alarms (where permitted) make the completion notification prompt.

## 5. Data ownership

| Data | Owner | Location |
|---|---|---|
| Active session, cage, unlock windows, warning counter | Device (authority) | DataStore |
| Coin ledger (immutable), violations, history, alarms, daily stats | Device (authority) | Room |
| Accounts, devices, subscriptions, config versions, flags, audit, tickets | Cloud | D1 |
| Analytics events | Device → Cloud (idempotent batch, offline queue) | SharedPreferences queue → D1 |

Local enforcement state is **never** cloud-owned; cloud product state is
**never** an enforcement input beyond bounded, validated config.

## 6. Environments & CI/CD

- Worker: `development` / `staging` / `production` in `wrangler.toml`
- Admin: `VITE_API_URL` (`mock` for demo mode without a backend)
- CI (recommended, GitHub Actions): lint → typecheck → unit tests → build →
  staging deploy → integration tests → manual approval → production deploy
  (matches TRD §91 / Backend §62).

## 7. Definition of done

See PRD §62 and the QA checklist in `mobile/README.md`. Backend DoD lives in
`worker/README.md`; admin build is verified (`tsc` + `vite build` pass).

---

## v2.0 ADDENDUM — Phase A: Enforcement Hardening (dual-engine architecture)

Ported from the Social Sentry competitive analysis (see /apk-analysis
reports). Six new capabilities, all native Kotlin, no new dependencies
(JobScheduler + AlarmManager instead of WorkManager).

### Component map

| Component | Package | Role |
|---|---|---|
| `EngineStateStore` | `monitor/` | SharedPreferences hot-path store: engine-1 heartbeat, engine-2 state, guard clean-streak, FGS restart counters (crash-loop backoff) |
| `ForegroundAppMonitorService` | `monitor/` | ENGINE 2 — specialUse FGS polling UsageStats events @850ms; enforces PolicyEngine verdicts when engine 1 is dead; lazy completion; self-yields after engine 1 healthy 30s |
| `AccessibilityGuardJobService` + `GuardEvaluator` | `guard/` | Persisted JobScheduler watchdog (job 9401, 15-min floor): distinguishes BLACKOUT (a11y granted + heartbeat stale → engine 2 + degraded notification) from PERMISSION REVOKED (engine 2 + RECOVERY flow); 3-clean-day self-disable |
| `GuardNotifier` | `guard/` | "Protection degraded" escalation notifications with Fix Now deep-link, 5-min per-cause cooldown |
| `UninstallInterceptor` | `guard/` | During enforcing sessions: scrapes OEM settings/installer events (18 packages) for app-label + uninstall/force-stop keywords (EN+BN) → GLOBAL_ACTION_BACK + re-check + lockout after 2 attempts |
| `MldDeviceAdminReceiver` + `LockMyPhoneService` + `LockMyPhoneController` | `lock/` | Lock-my-phone mode: DeviceAdmin force-lock ONLY policy; lockNow() loop @1.5s while interactive; SCREEN_ON/USER_PRESENT instant re-lock + attempt log; wall-clock-authoritative persisted session (survives reboot); validated-stop only via bailout coin spend through the immutable ledger |
| `A11yOverlayController` | `overlay/` | TYPE_ACCESSIBILITY_OVERLAY blocking fallback — no SYSTEM_ALERT_WINDOW needed (survives overlay-permission revocation) |

### Engine handoff state machine

```
                +----------------------------+
                | engine 1 (accessibility)   |
                | heartbeat @20s (throttled) |
                +------------+---------------+
                    healthy  |  stale >60s / unbind / destroy
              +---------------+----------------+
              v                                v
     engine 2 yields                    AccessibilityGuardJob /
     (after 30s of                       a11y onUnbind/onDestroy
      sustained health)                          |
              ^                                v
              |                   +------------+---------------+
              +-------------------+ engine 2 (UsageStats FGS)  |
                                  | poll @850ms, same Policy   |
                                  | verdicts, LockScreen UX    |
                                  +----------------------------+
```

### Bridge additions (Flutter contract)

`getEngineStatus`, `getGuardStatus`, `requestDeviceAdmin`,
`getDeviceAdminState`, `startLockMyPhone` (1..480 min, gated on pact +
admin), `getLockMyPhoneStatus`, `stopLockMyPhoneValidated` (spends
bailoutCoins through the immutable ledger — the only exit besides time).

### Deliberate non-ports (from the reference app)

- Universal master emergency code (their `SENTRYUNIVERSAL23` TOTP secret
  works on ANY install worldwide — a global backdoor; ours has per-install
  secrets only)
- Client-side credential admin backdoors
- QUERY_ALL_PACKAGES (we keep queries-based launcher lists)
- Location collection

## v2.0 ADDENDUM — Phase B: Blocking Mode Systems

Ported from the reference app's crown jewels (see apk-analysis reports),
adapted to our honesty constraints. 11 new Kotlin files, 10 modified.

### B1 — ReelsDetector (per-app signature strategies)

```
reels/ReelsDetector.kt
  YOUTUBE    view-id reel_watch_fragment_root (+pivot_bar immersion check)
  TIKTOK     package gate = instant home
  FACEBOOK   "Reel details" event-text pre-check -> BFS <=500 nodes over
             content-desc; decision = reelDetails && nav && (reels||fullscreen)
  FB LITE    view-id video_view
  INSTAGRAM  root_clips_layout / clips_viewer_video_container / reel_recycler
  CHROME     url_bar text match (6 shorts URL shapes, in-memory only)
  + partial-id fallback for OEM/version drift, 500ms global debounce,
    user keyword allowlist (in-memory)
```

### B2 — ReelsEscalationManager (the friction ladder)

```
attempt 1  (60s consecutive window)  -> toast
attempt 2                             -> ReelsOverlayActivity SOFT
                                        (1/2-min allowance unlocks +
                                        emergency-pass button + leave)
attempt 3+                            -> ReelsOverlayActivity HARD
                                        10s countdown -> ringtone ->
                                        notification -> killBackgroundProcesses
quota: daily allowance (5..120 min, default 30; date-key rollover)
       + 3 emergency passes/day (1 min each)
unblock windows: elapsedRealtime, one at a time, per-package, reels-scoped
                 (never weakens session policy)
```

### B3 — Monk Mode (allowlist-only lockdown)

```
monk/MonkModeManager        FSM {LOCKED, ALLOWED_APP}; SP wall-clock session
monk/MonkModeLockService    FGS; ticks 1500ms LOCKED / 800ms ALLOWED_APP
                            (interactive-only); UsageEvents 5s window;
                            audio-mode listener (API 31+): RINGING/IN_CALL ->
                            ALLOWED_APP, NORMAL -> re-lock; lockNow();
                            onTaskRemoved alarm re-kick
monk/MonkModeOverlayActivity  no-dismiss surface, self-finishes at expiry
                              or when an allowed app / call takes over
monk/MonkModeGuardJobService  persisted JobScheduler job 9402
PolicyEngine: MONK_BLOCK decision — enforced by BOTH engines (engine 2
              polices without a session); default launcher always allowed
NO give-up path: duration expiry or emergency call only
```

### B4 — Safety Pause (chosen friction, not blockade)

```
safety/SafetyPauseManager  per-app config (3..60s, default 5), 30s
                           re-trigger suppression, weekly insight counters
safety/SafetyPauseActivity calm countdown; BACK swallowed; releases
                           itself; onUserLeaveHint = closed-early insight
No DeviceAdmin, no force-stop, no escalation — strength 2 by design
```

### B5 — Prime Commit + Emergency TOTP

```
prime/PrimeCommitManager   owns a MAXLEVEL DETOX session (1..24h);
                           while active: temp unlock + bailout REFUSED;
                           give-up = TOTP + 20-min replay burn + relapse
                           violation (recorded BEFORE session end) +
                           finalizeForPrimeGiveUp (no coin spend)
unlock/EmergencyCodeManager  per-install Base32 secret (SecureRandom),
                             RFC-6238 TOTP (6 digits, HmacSHA1, 300s step,
                             +/-1), purpose-namespaced replay ledger,
                             otpauth:// enrollment URL.
                             NO shared/master secret exists anywhere.
```

### Bridge additions (Flutter contract)

`getReelsStatus`, `setReelsDailyLimitMinutes`, `useReelsEmergencyPass`,
`activateMonkMode` (1..720 min, gated on pact + device admin),
`getMonkModeStatus`, `setSafetyPauseEnabled`, `setSafetyPauseApps`,
`setSafetyPauseSeconds`, `getSafetyPauseStatus`, `enrollEmergencyCodes`,
`getEmergencyCodeStatus`, `activatePrimeCommit`, `giveUpPrimeCommit`,
`getPrimeCommitStatus`.

### Recovery additions

- `MldApp.onCreate`: monk re-arm (service + guard) on process resurrection
- `BootRecoveryReceiver`: monk re-arm after reboot + prime reconcile
- `EnforcementService` sweep: `primeCommit.reconcileIfNeeded()`
- `ForegroundAppMonitorService`: enforces `MONK_BLOCK` without a session

---

## v2.1 Phase C addendum — Progress layer (gamification)

### C0 — Design stance

The progress layer ports the competitor's *mechanics* (report-engagement
§4–§5) while rejecting its *ethics*. Ported: unified progression currency
with reason-class multipliers + daily/weekly/monthly caps, streak freezes +
24h recovery grace, 7-day check-in cycle, milestone/level curve, relapse
ledger. Rejected: escalating abuse copy, parasocial AI persona, public
shame graphs, aura decay, any monetization tie-in.

**Structural invariant:** the progress layer is a parallel observer. It
never gates, weakens, extends or terminates enforcement; its only writes
are its own DataStore keys and Room tables. DP is progression-only — it
can never buy unlocks, bailouts or time (coins remain the only economy
that touches escape hatches).

### C1 — Components

```
gamification/GamificationModels   MldLevel curve (11 levels, 0..6000
                                  lifetime DP), DpReason table (15
                                  reasons × class FOCUS/REELS/NEUTRAL),
                                  streak milestones (3/7/14/30/60/100d),
                                  7-day check-in cycle [10..30],
                                  persisted state shapes (Dp/Streak/
                                  Protection/CheckIn)
gamification/ProgressEngine       single-writer owner of the whole
                                  domain: award path (multiplier → caps →
                                  ledger → level-up), uncapped milestone
                                  path, lazy day rollover (clean/missed/
                                  broke semantics), auto-freeze (2/month)
                                  on missed days, 24h recovery-grace state
                                  machine, relapse logging, check-in claim,
                                  ledger self-heal at init (dp_awards SUM
                                  is the source of truth for lifetime DP)
gamification/GamificationNotifier  mld_progress channel; factual and
                                  respectful tone policy (no guilt
                                  weaponization)
```

### C2 — Clean-day semantics

`clean(day) = active(day) && !broke(day)` where active = ≥1 of (completed
session, alarm solved, monk completed, check-in) and broke = any of
(bailout, cage, relapse event). At the daily rollover: clean → streak++ +
CLEAN_DAY award; missed (inactive) → auto-consume a monthly freeze or
reset; broke → reset. Prime give-up and grace-expired protection loss are
immediate relapses (row in `relapse_events` + streak reset + notification).

### C3 — Award hooks (all fire-and-forget, try/catch isolated)

SessionEngine (completed/bailout/cage-served), ReelsEscalationManager
(intercepted reel, 30s anti-farm throttle), MonkModeManager (expiry),
PrimeCommitManager (kept/gave-up), SafetyPauseActivity (waited out),
ShockwaveAlarmEngine (puzzle solved), PermissionMonitor (protection
lost → grace armed / restored → cleared), NativeBridge feature setters
(one-time bonuses), EnforcementService sweep (rollover + grace expiry).

### C4 — Storage

Room v2 (`MIGRATION_1_2`): append-only `dp_awards` ledger +
`relapse_events`; `daily_stats` gains `dpEarned`, `alarmsCompleted`,
`monkCompletions`. DataStore: `dp_state`, `streak_state`,
`streak_protection_state`, `checkin_state`.

### C5 — Config (remote-tunable pacing, bounded)

`gamificationEnabled`, `dpMultiplier{Focus,Reels,Neutral}` (0.5..3.0),
`dpDailyCap` (50..1000), `dpWeeklyCap` (200..5000), `dpMonthlyCap`
(1000..20000). First earn day gets dailyCap + 100. Server (Worker
`/config` + Admin editor) and client (RuntimeConfig) both validate and
clamp — same frozen-bounds discipline as every other knob.

### Bridge additions (Flutter contract)

`getProgress`, `claimCheckIn`, `getDpHistory`, `getRelapseHistory`;
stateData carries a compact `progress` projection; debug-only
`debugAwardDp` / `debugResetProgress` (BuildConfigDebug-gated).

---

## Phase D addendum — v2.2.0 (growth & monetization)

Phase D ports the competitor's P2 tier (port-plan items 15–18, 20) with the
Phase C ethics decisions carried forward: PRO never gates enforcement, and
the community/referral-cash graph plus the shame-based "Brain Rot" widget
stay NOT ported.

### D0 — Stance: ethical monetization

The competitor paywalls per-app blocking and Prime Mode. MAXLEVEL DETOX
does the opposite: every safety feature (sessions, cage, monk, prime,
reels protection, emergency codes) is free forever. PRO is a supporter
tier: 90-day insight history (vs 7), the full widget family, a supporter
badge and early access. This is a product differentiator, not just an
ethics note — an addiction app that blocks your recovery tools until you
pay is a dark pattern we refuse.

### D1 — Plans + trial + Play Billing

- **Worker** `routes/plans.ts`: `GET /plans` (active catalog + payments
  config), `GET /trial`, `POST /trial/claim` — one device-bound trial
  (`trial_claims.device_id` UNIQUE), blocked by the global
  `paymentsEnabled` kill-switch and by any active subscription.
- **Kotlin** `billing/BillingManager.kt`: Play Billing wrapper for the 4
  frozen SKUs (`maxlevel_monthly/_3monthly/_6monthly/_yearly`,
  billing-ktx 7.1.1). It is a purchase LAUNCHER only: purchases surface
  through the `com.maxleveldetox/billing` EventChannel, Flutter forwards
  them to `POST /subscription/verify`, and only the Worker's Play
  Developer API check grants PRO. Acknowledgement happens client-side
  (refund hygiene), entitlement never does.
- **Flutter** `features/paywall/paywall_screen.dart`: perks, trial claim,
  plan cards (Play price when available, server price fallback), restore,
  bKash entry.

### D2 — bKash manual gateway (EPS-style, with kill-switch)

- **Worker** `routes/payments.ts`: `GET /payments/bkash/instructions`
  (config-gated: `paymentsEnabled` AND a non-empty `bkashNumber`),
  `POST .../init` (server reads amount from the plan row; server
  generates the unique `MLD-XXXXXXXX` reference; ≤3 open payments per
  user), `POST .../submit` (TrxID validated `^[A-Za-z0-9-]{6,24}$`,
  24h PENDING TTL → EXPIRED), `POST .../cancel`, `GET .../status`.
- **Admin**: `GET /admin/payments/bkash` review queue (new
  `MANAGE_PAYMENTS` permission — SUPER_ADMIN/ADMIN/SUPPORT), verify
  (grants plan days, extending from current expiry so paid days are
  never eaten) or reject (reason required; audited + MEDIUM security
  event). Plans CRUD under `MANAGE_CONFIG` (`GET/PATCH /admin/plans`).
- **Flutter** `features/payments/bkash_screen.dart`: pick plan →
  copy number/reference/amount → send from the bKash app → submit TrxID
  → IN_REVIEW → verified.

### D3 — Announcement delivery + offer routing (no Firebase)

Deliberately pull-based (the project carries no Firebase dependency):
Flutter fetches `/announcements` on app start and forwards them to
`InsightNotifier.deliverAnnouncements`, which posts unseen ones (cap 50
seen IDs, ≤3 per batch) as local notifications; PROMOTION items are
prefixed "Offer:". The dashboard surfaces one banner with a **24h dismiss
cooldown** (SharedPreferences) — the competitor's 1-hour upsell spam
loop is explicitly rejected.

### D4 — Home-screen widgets (the ethical subset)

`widgets/MldWidgets.kt`: four `AppWidgetProvider`s (Streak / Session /
Usage / Coins) sharing one RemoteViews layout (`res/layout/widget_stat.xml`,
design-system colors) + `WidgetUpdater` (updateAll / anyPinned /
requestPin). Widgets read native state (DataStore/Room/ledger) directly
and keep working when Flutter is dead; tapping opens the app; they hold
zero enforcement authority. `SessionEngine.Broadcaster` became
multi-listener (CopyOnWriteArrayList) so the bridge and widget updates
coexist. First pin awards the one-time `FEATURE_WIDGET` bonus (+10 DP).
The competitor's "Brain Rot" HUD widget is not ported.

### D5 — Break passes + daily insight nudge (item 20, reworked)

- `growth/BreakPassManager.kt`: weekly-capped 5-minute reels stand-down
  windows (default 2/week, remote-bounded 0..5, ISO-week anchor). One
  pass = one package; REFUSED during session/cage/prime/monk (those
  flows keep their own economics); free early end (honesty is free);
  lazy elapsedRealtime expiry (Phase B pattern). Wired into the a11y
  out-of-session path next to the paid unblock check.
- `growth/InsightNotifier.kt` + `InsightAlarmReceiver`: one neutral
  daily summary at the remote-config hour (17..22, default 20:05 local)
  — focus minutes, blocked attempts, streak. Quiet day → no
  notification. Scheduled via exact alarm (reschedules daily) +
  lazy sweep fallback. Copy is factual; the competitor's escalating
  abuse engine stays not ported.

### Config additions (bounded, both sides)

`paymentsEnabled`, `bkashNumber` (≤32 chars), `bkashInstructions`
(≤2000), `trialDays` (3..14), `breakPassesPerWeek` (0..5),
`insightNudgeEnabled`, `insightNudgeHour` (17..22). Client RuntimeConfig
mirrors only the growth-pacing subset (break passes + insight); payment
gateway state is learned per-request from the Worker.

### Storage + DB

DataStore: `break_pass_state`, `insight_seen_date`,
`announcements_seen_ids`. D1: new tables `subscription_plans`,
`trial_claims`, `bkash_payments` (migration `002_phase_d.sql`, seeded
8-plan catalog — 4 Play SKUs + 4 bKash mirrors, BDT pricing).

### Bridge additions (Flutter contract)

`getBreakPassStatus`, `useBreakPass`, `endBreakPassEarly`,
`getBillingProducts`, `launchPurchase`, `restorePurchases`,
`getWidgetStatus`, `pinWidget`, `updateWidgets`, `deliverAnnouncements`,
`checkDailyInsight`; EventChannel `com.maxleveldetox/billing`
(purchase events). Routes: `/pro`, `/payments/bkash`,
`/settings/widgets`.
