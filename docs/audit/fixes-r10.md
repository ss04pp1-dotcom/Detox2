# v2.5.7 Fix Round — Full Audit Response (r10)

Date: 2026-09-26 · Scope: every finding from the v2.5.6 full-codebase audit
(42 findings: 4 Critical, 6 High, 8+4 Worker, 8 Mobile, 4 Admin, 6 Low).

Verification status per component:
- worker — `tsc --noEmit` clean; **vitest suite 28/28 green** (`npm test`)
- admin — `tsc --noEmit` clean; `vite build` clean
- mobile — flutter analyze deferred to CI (no SDK in the fix sandbox);
  edited files pass structural checks and an independent cross-component
  integration review (route/handler/method-name/SQL-column matching)
  found zero mismatches.

---

## Critical

### C-1 — keystore + password committed (repo hygiene)
- `mobile/android/key.properties` and `mobile/android/app/mld-release.keystore`
  **removed from the tree** (`.gitignore` already covered them).
- `mobile/android/key.properties.example` added as the template.
- `docs/RELEASE-CHECKLIST.md` §1 documents Play key rotation + the
  `git filter-repo` history purge procedure.
- NOTE: rotation itself is a Play Console action — see the checklist.

### C-2 — admin logout never revoked the KV session
- `admin/src/api/client.ts`: new `api.logout()` → `POST /admin/auth/logout`.
- `admin/src/auth/AuthContext.tsx`: `logout()` now fire-and-forget calls it
  before clearing local state; the stale "there is no admin logout route"
  comment is gone.

### C-3 — server/device coin ledgers disconnected
- New `worker/src/routes/coins.ts`: `GET /coins?since=` (grants view),
  `POST /coins/earn` + `POST /coins/spend` (idempotent device mirrors;
  negative-balance mirrors are skipped, never fake a ledger row).
- Kotlin `CoinLedger.applyGrant()` — idempotent by server transactionId
  (`coin_srv_<tx>` PK); `NativeBridge` handler `applyServerCoinGrants`
  validates ids and never trusts an arbitrary amount without one.
- Flutter: `main.dart` reconciles grants on bootstrap + every 15-min sync;
  ad earns / temp-unlock / bailout spends are mirrored best-effort.
- Admin "Coins Adjust" now actually reaches the user's balance.

### C-4 — deletion requests never completed
- `worker/src/index.ts` scheduled handler: 30-day deletion purge — sessions
  deleted, devices REMOVED, account anonymized
  (`deleted_<id>@invalid`, name/google_sub/referral_code cleared,
  status DELETED), `ACCOUNT_DELETION_COMPLETED` security event.
  Rows are anonymized (not hard-deleted) to keep the append-only history
  and FK references intact.

## High

### H-1 — referral pipeline was dead code
- `users.referral_code` column (004) — codes resolve via unique index.
- New `POST /referral/apply` (once per account, 7-day window,
  Google-verified qualification, self/circular rejection).
- `POST /referral/claim` now GRANTS the PRO days (extends/creates the
  subscription) instead of only writing a credit row nothing read.
- Flutter Referral tab: "Have a friend's code?" apply UI.

### H-2 — no path to create a support ticket
- New `worker/src/routes/support.ts`: `POST /support/tickets`
  (3 open / 5 per day caps, category enum) + `GET /support/tickets`
  (user's own tickets incl. the admin reply).
- New Flutter `SupportScreen` (Settings → Report a Problem) with
  category + description form and reply display.

### H-3 — trial farming (5 devices/account, unlimited per phone)
- `claimTrial` now stacks three guards: per-account lifetime,
  per-google_sub lifetime (JOIN across accounts), per-IP KV quota
  (3 / 7 days) + `TRIAL_QUOTA_IP` security event.

### H-4 — email prefix leaked as public display name
- New accounts default to `User #<4-hex>`, never the email local part.
- New `PATCH /me` rename route (validated: 2-40 chars, no @/URL/seps).
- Account screen: display-name edit dialog; search now exposes
  user-chosen handles only.

### H-5 — zero tests anywhere
- Worker: vitest installed, `npm test` script; 28 unit tests cover
  crypto (both PBKDF2 formats), validation + config clamping,
  serializers (incl. the A-1/A-2 shapes), response envelope + CORS,
  rate-limit fail-closed/fail-open (W-1) and aiChat guardrails (W-3).
- Kotlin/Flutter suites deferred to CI planning (checklist §6) — the
  regression net now exists for the highest-risk pure logic.

### H-6 — offline purchases never auto-verified
- `BillingManager` connect-time restore now always emits (the `notify`
  flag is gone entirely); the periodic Flutter sync re-arms
  `restorePurchases()` because the event channel has no replay and a
  cold-start race could drop the first emit.

## Worker (W-1 … W-8)

- **W-1** `rateLimit.ts`: `auth`/`adminLogin`/`admin` buckets FAIL CLOSED
  (503 + Retry-After) when KV is unavailable; data-plane buckets keep the
  documented fail-open trade-off. Regression-tested.
- **W-2** `payments.ts`: reference entropy 2^32 → 2^48 (`randomToken(6)`)
  + 3-attempt UNIQUE-collision retry with a clean 409 envelope.
- **W-3** `community.ts aiChat`: personality is a fixed enum
  (balanced/roast/caring/strict), streak/minutes clamped, per-user daily
  LLM quota (30/day, KV), max_tokens 220→160, anti-instruction line in the
  system prompt, quota-exceeded + KV-outage scripted fallbacks.
- **W-4** `crypto.ts`: PBKDF2 100k → 300k in a portable
  `<iterations>$<salt>$<hash>` format — legacy 2-part hashes still verify
  (seed admin keeps working); dummy-timing hash regenerated at 300k;
  per-account lockout (5 fails → 15 min) with `ADMIN_ACCOUNT_LOCKED`
  security event and login-time reset.
- **W-5** `authGoogle`: live-session cap (10/user) — oldest sessions
  revoked on login.
- **W-6** scheduled handler: stale PENDING bKash payments (>24h) flip to
  EXPIRED every cron run.
- **W-7** BillingManager sets `obfuscatedAccountId(userId)` at launch;
  `subscriptionVerify` cross-checks the echoed
  `obfuscatedExternalAccountId` → 409 + `PURCHASE_ACCOUNT_MISMATCH` on a
  cross-account grab.
- **W-8** Google ID tokens now verify LOCALLY (JWKS cached 24h in KV,
  RS256 + aud/iss/exp checks) with the tokeninfo endpoint kept only as a
  fallback.

## Mobile (K-1 … K-6, F-1/F-2)

- **K-1** Broadcaster listener dispatches `pushState()` onto the bridge
  scope (Default) — the seven runBlocking DataStore reads no longer run
  on the main thread every onResume.
- **K-2** Auth tokens moved to `flutter_secure_storage`
  (EncryptedSharedPreferences) with a one-time migration that wipes the
  old plaintext `mld_auth` prefs entry.
- **K-3** New native `getDeviceInfo` (Build.MANUFACTURER/MODEL/RELEASE)
  feeds `/devices/register` — the admin Devices table shows real data.
- **K-4** Forced updates now show a non-dismissible dialog
  (`barrierDismissible: false` + `PopScope(canPop: false)`) with an
  "Open Play Store" button (native market:// + https fallback). Soft
  updates keep the SnackBar.
- **K-5** New `CrashReporter` (dependency-free): uncaught-exception hook
  persists the last crash, DiagLog errors mirror into a capped on-disk
  log, previous-run crashes resurface, and `buildSystemReport` includes
  both — the System Health screenshot finally carries crash history.
- **K-6** Exact-alarm fallback already existed in code
  (`canScheduleExactAlarms()` → inexact `setWindow`); the missing piece
  was the Play declarations documentation — now
  `docs/RELEASE-CHECKLIST.md` §2.
- **F-1** `ApiClient` records `lastErrorCode`/`lastErrorMessage`
  (incl. a distinct OFFLINE state); login/support/referral surfaces map
  codes to specific messages instead of a generic failure.
- **F-2** Community moderation: 2 commitments/day quota, hidden commits
  filtered from the feed, `POST /community/commits/:id/report`
  (flag icon on every non-mine commit, 3+ reports → security event) and
  `PATCH /admin/community/commits/:id` (MANAGE_SUPPORT) to hide/unhide.

## Admin (A-1 … A-4)

- **A-1** `toApiTicket` now returns `userEmail` (the list query's join);
  `admin_users.name` stored (004) + serialized; the SPA Name input is
  functional.
- **A-2** `toApiTicket` returns `response` + `respondedAt`; the SPA
  detail drawer already renders `response` — admin replies are visible
  after reload now.
- **A-3** 30-min idle auto-logout in `AuthContext` (sessionStorage
  token + revoke-on-logout from C-2 shrink the theft window to ≤30 min
  of tab inactivity).
- **A-4** `adminAnalytics` computes a REAL monthly-recurring figure from
  the plan catalog (`SUM(price_minor / duration_days * 30)` over active
  subscriptions, BDT); the Revenue page shows `৳` (estMonthlyUsd stays
  only as a null deprecated alias for older builds).

## Low (L-1 … L-6)

- **L-1** Dependency upgrades deliberately deferred (Billing 8 / wrangler 4
  are breaking-change releases) — tracked in RELEASE-CHECKLIST.
- **L-2** Both remaining `!!` operators replaced with safe-call fallbacks
  (EngineStateStore.isNextDay, SinthiaCheckIn.messageFor).
- **L-3** Coin spend IDs carry an identityHashCode + random suffix
  (same-millisecond double-spend can no longer PK-crash); Play API token
  fetch is single-flight per isolate.
- **L-4** Friend requests: duplicates now answer honestly (409 with the
  actual situation: pending/already-sent/already-friends); the fake
  `streakDays: 0` placeholder is gone from the API and the UI subtitle.
- **L-5** `android.enableJetifier=false`; AdMob sample-ID guard + the
  `com.maxleveldet0x` applicationId note live in RELEASE-CHECKLIST §3/§5.
- **L-6** `.github/workflows/ci.yml`: worker (types+tests), admin
  (typecheck+build), mobile (flutter analyze) on every PR.

## Schema / migration

`worker/src/db/migrations/004_r10_fixes.sql` (+ the same columns inline in
`schema.sql` for fresh installs):
`admin_users.name/failed_login_count/locked_until`,
`users.referral_code` (+unique partial index),
`community_commits.hidden/report_count` (+report index).
Apply with `wrangler d1 migrations apply DB --env <env>`.
