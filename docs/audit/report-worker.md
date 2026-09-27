# MAXLEVEL DETOX — Cloudflare Worker Backend Audit Report

**Task ID:** 3-c · **Agent:** worker-auditor (read-only) · **Date:** 2026-09-21
**Scope:** `worker/` — every TypeScript file read line-by-line, plus SQL schema/migrations, `wrangler.toml`, `package.json`, `tsconfig.json`, `.dev.vars.example`, `worker/README.md`; cross-checked against `docs/SECURITY_MODEL.md` and `docs/ARCHITECTURE.md`.
**Method:** Full manual read of all 16 TS files; route/middleware flow tracing; grep sweeps for SQL interpolation, secrets, dead tables; strict `tsc --noEmit` verified in an isolated copy (`/tmp/worker-check` — project untouched). Zero source files modified.

---

## 1. Summary

| Metric | Value |
|---|---|
| TypeScript files audited | 16 (all of `worker/src`) |
| TypeScript LOC covered | 5,800 (100%) |
| SQL files audited | 4 (`schema.sql` 486 L + migrations 001/002/003, 508 L) |
| Config/docs audited | `wrangler.toml`, `package.json`, `tsconfig.json`, `.dev.vars.example`, `.gitignore`, `worker/README.md` |
| Routes mapped | 66 API routes + `/health` + global OPTIONS |
| **CRITICAL findings** | **0** |
| **MAJOR findings** | **7** |
| **MINOR findings** | **28** |
| Typecheck (`tsc --noEmit`, strict) | PASSES (0 errors) |

**Verdict:** The security core is genuinely solid — 100% parameterized D1, HMAC-signed KV admin sessions with per-request role re-read from D1, timing-safe secret comparison, append-only audit/coin tables with DB triggers, exact-match CORS allowlist, CSP/HSTS/nosniff on every response, no stack traces, no committed secrets, no cookie auth (CSRF N/A). **No auth bypass, no SQL injection, no IDOR on user-scoped data, no secret leakage found.** However, the audit found **7 MAJOR defects**: a broken user logout (session revocation impossible), a secret-scanner-mangled PEM parser that kills Google Play verification, a wrong Play package name, a case-mismatch that disables every seeded feature flag, a double-grant race in bKash verification, subscriptions that never expire server-side, and the committed default super-admin password with no forced rotation.

---

## 2. Endpoint Map (66 routes)

Auth legend: **U** = `requireUser` (D1 session, SHA-256 bearer); **A:\<perm\>** = `requireAdmin(permission)` (KV session + HMAC + fresh D1 role); **P** = public. RL = rate limit bucket applied.

### 2.1 App routes (`src/index.ts:120-163`, handlers in `routes/app.ts`, `routes/plans.ts`, `routes/payments.ts`, `routes/community.ts`)

| # | Method | Path | Auth | Rate limit | Handler | What it does |
|---|---|---|---|---|---|---|
| 1 | POST | `/api/v1/auth/google` | P | auth/ip (10/min) | `authGoogle` | Verifies Google ID token via tokeninfo (aud/email_verified/exp); creates/updates user; issues access(1h)+refresh(30d) tokens, stores SHA-256 hashes; dev-mode `dev:<email>` only when no aud configured **and** API_ENV=development |
| 2 | POST | `/api/v1/auth/refresh` | P | auth/ip | `authRefresh` | Rotates refresh token atomically (D1 batch delete+insert) |
| 3 | POST | `/api/v1/auth/logout` | ⚠ none (BUG M1) | auth/ip | `authLogout` | Intended: delete session row. **Always returns 401** — `requireUser` missing from chain |
| 4 | GET | `/api/v1/me` | U | none | `getMe` | User profile + latest subscription + device count |
| 5 | DELETE | `/api/v1/me` | U | none (m6) | `deleteMe` | Soft deletion request (`deletion_pending_at`) + security event |
| 6 | POST | `/api/v1/devices/register` | U | public/device | `devicesRegister` | Registers device (max 5), binds session.device_id |
| 7 | POST | `/api/v1/devices/heartbeat` | U | heartbeat/device (4/min) | `devicesHeartbeat` | Updates device app version/permissions/session flag; returns configChanged + announcement count |
| 8 | GET | `/api/v1/config` | U | config/device (30/min) | `getConfig` | Raw published config JSON + version (no server-side clamp — m15) |
| 9 | GET | `/api/v1/flags` | U | config/device | `getFlags` | Per-device rollout via SHA-256 bucket; env filter **case-broken (M4)** |
| 10 | GET | `/api/v1/app/version` | P | public/ip | `getAppVersion` | Latest `app_versions` row (force-update gate pre-login) |
| 11 | GET | `/api/v1/announcements?since=` | U | config/device | `getAnnouncements` | ACTIVE announcements in window, ≤100 |
| 12 | POST | `/api/v1/events` | U | events/device (60/min) | `postEvents` | Idempotent batch ≤100, `INSERT OR IGNORE` on event_id PK, type allowlist, payload ≤10k |
| 13 | POST | `/api/v1/subscription/verify` | U | public/device | `subscriptionVerify` | Play Developer API re-validation (SA JWT RS256, KV-cached token) before upsert — **broken by M2/M3** |
| 14 | GET | `/api/v1/subscription` | U | none (m7) | `getSubscription` | Latest subscription row (stale ACTIVE — M6) |
| 15 | GET | `/api/v1/plans` | U | config/device | `getPlans` | Active plan catalog + paymentsEnabled/trialDays from config |
| 16 | GET | `/api/v1/trial` | U | config/device | `getTrial` | Trial eligibility for device/user key |
| 17 | POST | `/api/v1/trial/claim` | U | auth/device (10/min) | `claimTrial` | One-per-device trial (UNIQUE device_id), blocked by kill-switch + active sub; writes ACTIVE subscription row |
| 18 | GET | `/api/v1/payments/bkash/instructions` | U | config/device | `bkashInstructions` | bKash number + instructions + bkash plans (gated by paymentsEnabled && number ≥8 chars) |
| 19 | POST | `/api/v1/payments/bkash/init` | U | public/device | `bkashInit` | Creates PENDING payment; server-generated `MLD-XXXXXXXX` reference; amount from plan row; ≤3 open payments |
| 20 | POST | `/api/v1/payments/bkash/submit` | U | public/device | `bkashSubmit` | TrxID (`^[A-Za-z0-9-]{6,24}$`) + optional phone; PENDING→IN_REVIEW; 24h TTL→EXPIRED |
| 21 | POST | `/api/v1/payments/bkash/cancel` | U | public/device | `bkashCancel` | PENDING/IN_REVIEW/EXPIRED→CANCELED |
| 22 | GET | `/api/v1/payments/bkash/status` | U | config/device | `bkashStatus` | User's last 20 payments + plan names |
| 23 | POST | `/api/v1/ai/chat` | U | public/device | `aiChat` | Sinthia LLM proxy (OpenAI gpt-4o-mini when `AI_API_KEY` set) with scripted fallback; strips bare 6-digit numbers |
| 24 | GET | `/api/v1/community/commits` | U | config/device | `listCommits` | Public commitment feed (active only), hasCheered flags |
| 25 | POST | `/api/v1/community/commits` | U | public/device | `createCommit` | Create public commitment (1-90 days, reason ≤280, ≤3 open) |
| 26 | POST | `/api/v1/community/commits/:id/cheer` | U | public/device | `cheerCommit` | INSERT OR IGNORE cheer + recount (batch) |
| 27 | GET | `/api/v1/friends` | U | none (m7) | `listFriends` | Accepted friends (streakDays hardcoded 0) |
| 28 | GET | `/api/v1/friends/search?q=` | U | public/device | `searchUsers` | display_name LIKE search (unescaped wildcards — m9) |
| 29 | POST | `/api/v1/friends/requests/:id` | U | public/device | `sendFriendRequest` | 10/day quota, INSERT OR IGNORE |
| 30 | POST | `/api/v1/friends/requests/:id/accept` | U | public/device | `acceptFriendRequest` | pending→accepted (404 check broken — m10) |
| 31 | POST | `/api/v1/friends/:id/remove` | U | public/device | `removeFriend` | Deletes both directions |
| 32 | GET | `/api/v1/referral` | U | config/device | `getReferral` | Referral code + stats (**dead feature — m11**) |
| 33 | POST | `/api/v1/referral/claim` | U | auth/device | `claimReferral` | Marks qualified referral claimed + credit row (**always 404 — m11**) |

### 2.2 Admin routes (`src/index.ts:169-217`, handlers in `routes/admin.ts`)

All admin routes: `rateLimit('admin', …)` + `requireAdmin(<perm>)` except login. RBAC matrix (`middleware/adminAuth.ts:52-86`): SUPER_ADMIN=all; ADMIN=all minus MANAGE_SYSTEM; SUPPORT=VIEW/EDIT_USERS, VIEW_DEVICES, VIEW_AUDIT, MANAGE_SUPPORT, MANAGE_PAYMENTS; ANALYST/READ_ONLY=VIEW_*; CONFIG_MANAGER=VIEW_USERS/DEVICES + MANAGE_CONFIG/FLAGS/ANNOUNCEMENTS.

| # | Method | Path | Permission | Handler | Notes |
|---|---|---|---|---|---|
| 34 | POST | `/admin/auth/login` | public | `adminLogin` | PBKDF2 verify; KV session `admin_sess_<sha256>` with HMAC sig, 8h TTL; audited + security event on failure. RL: adminLogin/ip + admin/ip |
| 35 | GET | `/admin/overview` | VIEW_ANALYTICS | `adminOverview` | Metrics + D1/KV health + recent audit |
| 36 | GET | `/admin/users` | VIEW_USERS | `adminListUsers` | Keyset cursor + email LIKE search (escaped) |
| 37 | GET | `/admin/users/:id` | VIEW_USERS | `adminGetUser` | Devices/subscription/security events/tickets |
| 38 | PATCH | `/admin/users/:id` | EDIT_USERS | `adminPatchUser` | Status change (DELETED blocked); audited + security event |
| 39 | POST | `/admin/users/:id/coins/adjust` | EDIT_USERS | `adminAdjustCoins` | ADMIN_ADJUSTMENT ledger row; ±10000 cap; balance check (race — m28) |
| 40 | GET | `/admin/devices` | VIEW_DEVICES | `adminListDevices` | Cursor + search |
| 41 | GET | `/admin/subscriptions` | VIEW_USERS | `adminListSubscriptions` | Cursor + status filter + summary |
| 42 | GET | `/admin/config` | MANAGE_CONFIG | `adminGetConfig` | Draft + published + 50-version history |
| 43 | PUT | `/admin/config` | MANAGE_CONFIG | `adminSaveConfigDraft` | validateConfig (strict keys, clamp) → DRAFT upsert; audited |
| 44 | POST | `/admin/config/publish` | MANAGE_CONFIG | `adminPublishConfig` | confirm:true; archive+publish (2 statements, not batched — m16/m26) |
| 45 | POST | `/admin/config/rollback` | MANAGE_CONFIG | `adminRollbackConfig` | Re-publishes old version as NEW row (history preserved) |
| 46 | GET | `/admin/flags` | MANAGE_FLAGS | `adminListFlags` | All flags raw |
| 47 | PATCH | `/admin/flags/:key` | MANAGE_FLAGS | `adminPatchFlag` | enabled/rollout(allowlist)/minimumVersion (no semver check — m18); audited |
| 48 | GET | `/admin/announcements` | MANAGE_ANNOUNCEMENTS | `adminListAnnouncements` | ≤limit |
| 49 | POST | `/admin/announcements` | MANAGE_ANNOUNCEMENTS | `adminCreateAnnouncement` | Type allowlist + ISO date validation; inserted ACTIVE |
| 50 | PATCH | `/admin/announcements/:id` | MANAGE_ANNOUNCEMENTS | `adminPatchAnnouncement` | status/title/body/dates (dates unvalidated — m17); audited |
| 51 | GET | `/admin/analytics?range=` | VIEW_ANALYTICS | `adminAnalytics` | DAU/session/shorts/premium aggregates from events |
| 52 | GET | `/admin/audit-logs` | VIEW_AUDIT | `adminAuditLogs` | Filters + cursor |
| 53 | GET | `/admin/security-events` | VIEW_AUDIT | `adminSecurityEvents` | Filters + cursor |
| 54 | GET | `/admin/support/tickets` | MANAGE_SUPPORT | `adminListTickets` | Cursor + status |
| 55 | PATCH | `/admin/support/tickets/:id` | MANAGE_SUPPORT | `adminPatchTicket` | status/priority/assignedTo/response (validated) |
| 56 | GET | `/admin/app-versions` | MANAGE_CONFIG | `adminGetAppVersions` | Last 20 |
| 57 | POST | `/admin/app-versions` | MANAGE_CONFIG | `adminPostAppVersion` | semver-validated; audited |
| 58 | GET | `/admin/system/health` | MANAGE_SYSTEM | `adminSystemHealth` | D1/KV probes |
| 59 | GET | `/admin/plans` | MANAGE_CONFIG | `adminListPlans` | Full catalog incl. inactive |
| 60 | PATCH | `/admin/plans/:id` | MANAGE_CONFIG | `adminPatchPlan` | price/days/active/popular/name/sort; audited as PLAN_UPDATED |
| 61 | GET | `/admin/payments/bkash` | MANAGE_PAYMENTS | `adminListBkashPayments` | Review queue + summary |
| 62 | POST | `/admin/payments/bkash/:id/verify` | MANAGE_PAYMENTS | `adminVerifyBkashPayment` | Grants plan days, extends from current expiry (**race M5**) |
| 63 | POST | `/admin/payments/bkash/:id/reject` | MANAGE_PAYMENTS | `adminRejectBkashPayment` | Reason required; audited + MEDIUM security event |
| 64 | GET | `/admin/admin-users` | MANAGE_SYSTEM (SUPER_ADMIN only) | `adminListAdminUsers` | No password_hash leaked (serializer filters) |
| 65 | POST | `/admin/admin-users` | MANAGE_SYSTEM | `adminCreateAdminUser` | PBKDF2 hash, role allowlist, pwd ≥12; audited |
| 66 | PATCH | `/admin/admin-users/:id` | MANAGE_SYSTEM | `adminChangeRole` | Self-demotion guard; audited |

Non-API: `GET /health` (no auth, no D1 — env + ok); `OPTIONS *` → `preflightResponse` (204, allowlist-conditional CORS). `HEAD` treated as `GET` (`index.ts:297`).

---

## 3. Findings

### MAJOR

---

#### [MAJOR-1] `POST /auth/logout` can never succeed — user session revocation is broken
**File:** `worker/src/index.ts:123` + `worker/src/routes/app.ts:296-302`
**Description:** The logout route's middleware chain contains only `rateLimit('auth','ip')` — **`requireUser` is missing**, so `c.user` is always `null` when the handler runs. The handler's first line then rejects every request with 401, and the session row is never deleted. The mobile app *does* call this endpoint (`mobile/lib/data/api_client.dart:44`, used from `account_screen.dart:83`), then discards its local tokens — but the server-side access **and 30-day refresh token remain valid**. A user who logs out on a shared device believes they are safe; in reality the session persists server-side until expiry, and there is no other user-facing revocation path (no "logout all devices", no password to change — Google-only auth).
**Evidence:**
```ts
// index.ts:123 — no requireUser in the chain
route('POST', '/auth/logout', [rateLimit('auth', 'ip')], authLogout),

// routes/app.ts:296-302
export async function authLogout(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401); // always taken
  await c.env.DB.prepare('DELETE FROM user_sessions WHERE token_hash = ?')
    .bind(c.user.tokenHash)
    .run();
  return ok(c, { loggedOut: true });
}
```
**Fix (exact):**
```ts
// index.ts:123
route('POST', '/auth/logout', [requireUser, rateLimit('auth', 'ip')], authLogout),
```

---

#### [MAJOR-2] `pemToDer` corrupted by a secret-scanner — Google Play verification always throws
**File:** `worker/src/utils/crypto.ts:115-124`
**Description:** The PKCS#8 PEM parser's first `.replace()` was mangled by an automated redaction pass (the placeholder `[REDACTED:ssh_private_key]` also appears in `.dev.vars.example:13`, proving a repo-wide scanner ran). The result is a **regex character class** `/[REDACTED:ssh_private_key]/g` that strips the letters `{R,E,D,A,C,T,:,s,h,_,p,r,i,v,a,t,e,k,y}` from the *entire* PEM — it neither removes the `-----BEGIN PRIVATE KEY-----` header (only its `E` characters) nor leaves the base64 body intact. `atob()` then throws on the residual `-` characters, `crypto.subtle.importKey` never runs, and the exception propagates out of `getPlayAccessToken` (which has no try/catch around key import) through `subscriptionVerify` (whose try/catch only wraps `fetchPlayPurchase`) to the router's 500 handler. **Every `POST /subscription/verify` call fails with 500 once `GOOGLE_PLAY_SA_PRIVATE_KEY` is configured** — PRO entitlements can never be granted via Play. (`tsc` passes because the mangled regex is syntactically valid.)
**Evidence:**
```ts
// utils/crypto.ts:116-119 (as shipped)
const b64 = pem
  .replace(/[REDACTED:ssh_private_key]/g, '')      // ← mangled; was /-----BEGIN PRIVATE KEY-----/g
  .replace(/-----END PRIVATE KEY-----/g, '')
  .replace(/\s+/g, '');
```
**Fix (exact):**
```ts
const b64 = pem
  .replace(/-----BEGIN PRIVATE KEY-----/g, '')
  .replace(/-----END PRIVATE KEY-----/g, '')
  .replace(/\s+/g, '');
```
Also wrap the key import in `getPlayAccessToken` (`routes/app.ts:775-781`) in try/catch returning `null` so a bad key yields the honest `SERVICE_UNAVAILABLE` (503) instead of 500.

---

#### [MAJOR-3] Play API `PACKAGE_NAME` does not match the shipped `applicationId`
**File:** `worker/src/routes/app.ts:42-43` vs `mobile/android/app/build.gradle:47`
**Description:** The Play Developer API purchase lookup is hardcoded to `com.maxleveldetox.app`, but the Android app's `applicationId` is `com.maxleveldet0x` (deliberately obfuscated per `build.gradle:40-47`). Google will return HTTP 404 for every purchase-token lookup under the wrong package, so `subscriptionVerify` would answer `NOT_FOUND` ("Purchase not found on Google Play") for **every legitimate purchase** even after M2 is fixed.
**Evidence:**
```ts
// routes/app.ts:42-43
/** TODO(GOOGLE PLAY): replace with the real Play Store application id. */
const PACKAGE_NAME = 'com.maxleveldetox.app';
```
```groovy
// mobile/android/app/build.gradle:47
applicationId = "com.maxleveldet0x"
```
**Fix (exact):**
```ts
const PACKAGE_NAME = 'com.maxleveldet0x';
```
(Consider making it a `[vars]` entry `PLAY_PACKAGE_NAME` so the obfuscated id isn't hardcoded in two repos.)

---

#### [MAJOR-4] Feature-flag environment filter is case-mismatched — every seeded flag is reported disabled
**File:** `worker/src/routes/app.ts:537-541`
**Description:** `getFlags` compares the DB's `environment` column (CHECK-constrained to lowercase `'development'|'staging'|'production'`, seeded `'production'` — `schema.sql:141-142,385-393`) against `c.env.API_ENV.toUpperCase()` (`'PRODUCTION'`). The strings never match, so **every flag with `enabled=1` is force-disabled in every environment** (the exempt value `'ALL'` is unreachable because the CHECK constraint rejects it). `SHOCKWAVE_ALARM`, `SHORTS_BLOCKER`, `CAGE`, `TEMP_UNLOCK` etc. all report `false`. No consumer inside this repo currently calls `/flags` (the mobile client defines `fetchFlags` but never invokes it — `api_client.dart:123`), which is why this has gone unnoticed, but it is a frozen-contract endpoint returning wrong data for 100% of seeded rows, and any flag-gated rollout will silently kill core features the day a client starts consuming it.
**Evidence:**
```ts
// routes/app.ts:538-541
let enabled = row.enabled === 1;
if (enabled && row.environment !== 'ALL' && row.environment !== c.env.API_ENV.toUpperCase()) {
  enabled = false;   // always taken: 'production' !== 'PRODUCTION'
}
```
**Fix (exact):**
```ts
if (enabled && row.environment !== 'ALL' && row.environment !== c.env.API_ENV) {
  enabled = false;
}
```
(API_ENV values are already lowercase `'development'|'staging'|'production'`, matching the CHECK constraint.)

---

#### [MAJOR-5] bKash verify: non-atomic multi-step grant → double PRO-day grant / grant-on-reject race
**File:** `worker/src/routes/admin.ts:1384-1474` (see also `1480-1507` for the reject-side race)
**Description:** `adminVerifyBkashPayment` performs: (1) SELECT payment, (2) SELECT plan, (3) SELECT current subscription, (4) UPDATE/INSERT subscription (grant days), (5) UPDATE payment `WHERE id=? AND status='IN_REVIEW'` — **five separate statements with no batch/transaction, and the row-count of the final guard update is never checked**. Two concurrent verifies (double-click, two admins) both observe `IN_REVIEW`, both execute step 4, and both extend the subscription — the user is granted the plan's days **twice**. Similarly, a concurrent verify+reject interleaving can leave the payment `REJECTED` while the subscription was already granted. A crash between (4) and (5) leaves an `IN_REVIEW` payment whose days were already granted; re-verifying grants them again.
**Evidence:**
```ts
// admin.ts:1449-1455 — guard exists but its effect is never checked
await c.env.DB.prepare(
  `UPDATE bkash_payments
   SET status = 'VERIFIED', reviewed_by = ?, reviewed_at = ?, subscription_id = ?, updated_at = ?
   WHERE id = ? AND status = 'IN_REVIEW'`
)
  .bind(c.admin!.adminId, nowIso, subscriptionId, nowIso, paymentId)
  .run();   // ← result.meta.changes ignored; grant already happened above
```
**Fix (exact):** claim the payment **first** and bail if it was already claimed, then grant inside the same D1 batch:
```ts
const claim = await c.env.DB.prepare(
  `UPDATE bkash_payments
   SET status = 'VERIFIED', reviewed_by = ?, reviewed_at = ?, subscription_id = ?, updated_at = ?
   WHERE id = ? AND status = 'IN_REVIEW'`
).bind(c.admin!.adminId, nowIso, subscriptionId, nowIso, paymentId).run();
if ((claim.meta.changes ?? 0) === 0) {
  return fail(c, 'CONFLICT', 'Payment was already processed by another reviewer', 409);
}
```
Ordering: compute `subscriptionId`/`newExpiry` first, run the claim above, and only then execute the subscription INSERT/UPDATE (ideally both statements in one `c.env.DB.batch([...])`).

---

#### [MAJOR-6] Subscriptions never transition to EXPIRED server-side — stale ACTIVE entitlements and inflated metrics
**Files:** `worker/src/routes/plans.ts:175-190` (trial insert), `worker/src/routes/admin.ts:1420-1447` (bKash grant), `worker/src/routes/app.ts:931-939` (`getSubscription`), `admin.ts:197` (`premiumUsers` metric)
**Description:** Trial claims and bKash verifications insert `status='ACTIVE'` subscription rows with an `expiry_date`, but **no code path ever marks them EXPIRED** — there is no cron trigger in `wrangler.toml` and no `scheduled` handler in `index.ts`. (Play-verified rows are refreshed only if the client re-POSTs `/subscription/verify`.) Consequences: (a) `GET /subscription` and `GET /me` keep returning `status:'ACTIVE'` (with a past `expiryDate`) forever — any consumer trusting `status` grants PRO indefinitely, violating the "honest server" principle in SECURITY_MODEL §6; (b) `/admin/overview.premiumUsers` and `/admin/analytics.revenue.premiumUsers` count every expired trial/bKash row as a paying subscriber (`WHERE status = 'ACTIVE'` with no expiry predicate — `admin.ts:197,891`); (c) `adminListUsers`' `plan` subquery (`admin.ts:308`) shows long-dead subscriptions as the user's active plan.
**Evidence:**
```ts
// plans.ts:176-178 — trial inserted ACTIVE; nothing ever flips it
`INSERT INTO subscriptions (id, user_id, product_id, purchase_token, plan, status,
                            start_date, expiry_date, last_verified, created_at, updated_at)
 VALUES (?, ?, 'trial', ?, 'trial', 'ACTIVE', ?, ?, ?, ?, ?)`

// admin.ts:197 — counts expired trials as premium
db.prepare("SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'ACTIVE'").first<CountRow>(),
```
**Fix (exact, two parts):**
1. Serve honest status — in `getSubscription`/`getMe` map stale rows:
```ts
if (row !== null && row.status === 'ACTIVE' && row.expiry_date !== null
    && Date.parse(row.expiry_date) <= Date.now()) {
  row = { ...row, status: 'EXPIRED' };
}
```
2. Add a cron — `wrangler.toml`:
```toml
[triggers]
crons = ["*/30 * * * *"]
```
plus in `index.ts`:
```ts
async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext) {
  ctx.waitUntil(env.DB.prepare(
    `UPDATE subscriptions SET status = 'EXPIRED', updated_at = ? WHERE status = 'ACTIVE' AND expiry_date IS NOT NULL AND expiry_date < ?`
  ).bind(new Date().toISOString(), new Date().toISOString()).run());
}
```
And add `AND (expiry_date IS NULL OR expiry_date > ?)` to the premium-count queries.

---

#### [MAJOR-7] Committed default super-admin password with no forced rotation
**File:** `worker/src/db/schema.sql:358-371` (also `migrations/001_initial.sql:295-308`, `worker/README.md:75-99`)
**Description:** The schema seeds `admin@maxleveldetox.com` / `ChangeMe_2026!` (PBKDF2 hash committed) as an ACTIVE SUPER_ADMIN. The README warns to rotate it, but **nothing enforces rotation**: if the DB is initialized from the seed and the operator forgets, anyone with the public repo (or this audit report) has full SUPER_ADMIN — user management, config publish, coin minting, bKash verification (free PRO), admin creation. There is no first-login forced change, no expiry on the seed credential, and no startup check. Combined with M-A note that `workers_dev = true` exposes the API on a predictable `*.workers.dev` host even before the custom domain is attached, this is the most plausible full-takeover path in the deployment.
**Evidence:**
```sql
-- schema.sql:362-371
INSERT OR IGNORE INTO admin_users (id, email, password_hash, role, status, created_at, created_by)
VALUES (
  'adm_seed_superadmin',
  'admin@maxleveldetox.com',
  '18ece0dd4a5a88284c68f436bf534c2e$050fc7b90cb6ba4100fa645a7fc61d8213f9d85161fe194d56aee982ff8d6cdf',
  'SUPER_ADMIN', 'ACTIVE', '2026-09-16T00:00:00.000Z', 'seed'
);
```
**Fix (exact):** refuse seed-credential logins outside development — in `adminLogin` (`routes/admin.ts:131`) add before the password check:
```ts
if (c.env.API_ENV !== 'development' && row?.id === 'adm_seed_superadmin') {
  return fail(c, 'FORBIDDEN', 'Seed admin password must be rotated before use (see worker/README.md §3)', 403);
}
```
plus set `workers_dev = false` in the production env blocks of `wrangler.toml` once the custom domain is attached.

---

### MINOR

---

#### [MINOR-1] Refresh-token concurrent replay race mints two sessions
**File:** `worker/src/routes/app.ts:271-285`
Sequential replay correctly 401s (row deleted), but two *concurrent* refreshes both pass the SELECT, then both run `DB.batch([DELETE (0 rows), INSERT new pair])` — the DELETE matching 0 rows is not an error, so both INSERTs succeed and one stolen/replayed refresh token yields two live sessions. No reuse-detection (RIP graveyard) exists. **Fix:** make the batch's DELETE a guarded claim and check it, e.g. `DELETE FROM user_sessions WHERE token_hash = ? AND refresh_token_hash = ?` won't help (same row) — instead run the guarded conditional delete first and check `meta.changes > 0` before inserting, or add a `rotated_at` marker and reject rows with `rotated_at IS NOT NULL`.
```ts
// suggested: claim first
const claim = await c.env.DB.prepare(
  'DELETE FROM user_sessions WHERE token_hash = ? AND refresh_token_hash = ?'
).bind(row.token_hash, refreshHash).run();
if ((claim.meta.changes ?? 0) === 0) return fail(c, 'UNAUTHORIZED', 'Refresh token already used', 401);
```

#### [MINOR-2] Concurrent first-login signup → 500 instead of 409/200
**File:** `worker/src/routes/app.ts:159-167`
Two simultaneous `POST /auth/google` for a brand-new email both pass the `SELECT` (null), both INSERT; the second violates `users.email UNIQUE` and surfaces as a 500 "unexpected error" (envelope-safe, but wrong code and no session issued). **Fix:** catch the constraint error and re-SELECT, or use `INSERT ... ON CONFLICT(email) DO NOTHING` + re-read.

#### [MINOR-3] `subscriptionVerify` echoes another user's subscription row
**File:** `worker/src/routes/app.ts:921-924`
The upsert keys on `purchase_token` (never reassigns `user_id` — good), but the closing `SELECT * FROM subscriptions WHERE purchase_token = ?` is **not** user-scoped, so a caller presenting someone else's token receives that user's subscription object (status/dates/product). No entitlement is transferred (`GET /subscription` is user-scoped), and the caller already knew the token, so impact is low. **Fix:** `... WHERE purchase_token = ? AND user_id = ?` and return 404 `CONFLICT`-style guidance when the row belongs to another account.

#### [MINOR-4] `subscriptions.plan` stores the raw `productId`
**File:** `worker/src/routes/app.ts:898-907`
`VALUES (?1, ?2, ?3, ?4, ?3, ...)` binds `productId` into both `product_id` and `plan` columns, so Play-verified rows carry `plan='maxlevel_monthly'` instead of the schema's `monthly|quarterly|semiannual|yearly|trial` vocabulary (bKash/trial paths do it correctly). **Fix:** look up the plan kind from `subscription_plans` by `product_id` and bind that, defaulting to `'monthly'`.

#### [MINOR-5] Device registration race + no device-removal route
**File:** `worker/src/routes/app.ts:348-360` (count-then-insert), `src/index.ts:128` (routes)
Two concurrent registers can both pass the `< 5` count and insert a 6th device (read-modify-write without transaction). Also, the 409 message says "Remove a device first" but **no route exists to remove a device** (user or admin side) — the cap is a dead end requiring direct DB surgery. **Fix:** enforce with a trigger (`SELECT COUNT(*) ... > 5 → RAISE(ABORT)`) or a transactional re-check; add `DELETE /devices/:id` (owner-scoped).

#### [MINOR-6] `DELETE /me` has no rate limit — unbounded append-only writes
**File:** `worker/src/index.ts:126`, `worker/src/routes/app.ts:945-955`
An authenticated user can spam deletion requests; each one appends a `security_events` row (append-only, trigger-protected) and rewrites `deletion_pending_at` — a cheap DB-bloat DoS vector. **Fix:** add `rateLimit('auth', 'device')` to the route and short-circuit when `deletion_pending_at` is already set.

#### [MINOR-7] Missing rate limits on `GET /me`, `GET /subscription`, `GET /friends`
**File:** `worker/src/index.ts:125,139,156`
Cheap D1 queries, but unthrottled per token; a compromised token can hammer these. **Fix:** add `rateLimit('config', 'device')` (or a dedicated read bucket) for uniformity with every other app route.

#### [MINOR-8] Admin login timing oracle (account enumeration)
**File:** `worker/src/routes/admin.ts:123-140`
PBKDF2 (100k iterations, ~50-150 ms) runs only when the email exists; unknown emails return in ~1 ms. The comment claims "Constant-ish response" but it isn't. **Fix:** when `row === null`, burn a dummy verification against a fixed hash (e.g. the seed hash) before responding.

#### [MINOR-9] `searchUsers` LIKE wildcards unescaped + email-prefix disclosure in the community feed
**Files:** `worker/src/routes/community.ts:336-342`, `worker/src/routes/app.ts:161`
(a) `%${q}%` is bound safely (no SQLi) but `%`/`_` are not escaped, so `q=%` enumerates up to 20 users per call — note `adminListUsers` *does* escape (`admin.ts:294-295`); copy that pattern here. (b) `display_name` defaults to the email local-part at signup (`app.ts:161`) and there is no route to change it, so non-anonymous commits publish every user's email prefix to all authenticated users, and `searchUsers` makes those prefixes enumerable. **Fix:** escape LIKE metachars; set `display_name` to an opaque name (e.g. `mld_<randomToken(4)>`) or add `PATCH /me {displayName}`.

#### [MINOR-10] `acceptFriendRequest` 404 branch is unreachable
**File:** `worker/src/routes/community.ts:394-400`
`D1Result.success` is `true` whenever the statement *executes* — including when the UPDATE matches 0 rows — so accepting a non-existent/already-handled request returns `{ok:true}` instead of 404. **Fix:** `if ((result.meta.changes ?? 0) === 0) return fail(c, 'NOT_FOUND', 'No pending request', 404);`

#### [MINOR-11] Referral system is dead code
**File:** `worker/src/routes/community.ts:474-504` (and schema tables `referrals`, `referral_credits`)
Nothing in the worker ever `INSERT`s into `referrals` or sets `status='qualified'` (verified by grep — the only writes are the `UPDATE ... 'claimed'` inside `claimReferral` itself). Therefore `/referral/claim` can only ever 404, `/referral` always reports zero, and `referral_credits` rows — which nothing ever consumes — are never linked to a subscription extension. Per SECURITY_MODEL §70-74 the referral-cash graph was deliberately *not* ported, so this half-wired surface is best either finished (qualify on referred-user first login + grant days on claim) or removed. **Fix (minimum):** return `501 NOT_IMPLEMENTED`-style envelope or delete the routes; if kept, add the qualification hook in `authGoogle` (`INSERT INTO referrals ... status='pending'` for a `?ref=<code>` signup) and consume credits in the expiry-extension helper.

#### [MINOR-12] `/ai/chat`: unbounded client-controlled system-prompt fields + uncapped LLM spend
**File:** `worker/src/routes/community.ts:50-83,106-126`
`ctx.personality` (and any other context values) are `String(...)`-ed into the system prompt with **no length cap** (only `message` is capped at 2000) — prompt injection is self-inflicted only, but a 2 MB personality string inflates every request's token cost. The only throttle is `rateLimit('public','device')` = 30 LLM calls/min/device (≈43k/day/device) with no daily cap. Model/endpoint are hardcoded (`gpt-4o-mini`, OpenAI). **Fix:** clamp each context field (e.g. `personality.slice(0,64)`, numeric coercion for minutes/streak) and add a per-user daily counter in KV (or drop `AI_API_KEY` — the scripted layer is the designed fallback).

#### [MINOR-13] Payments kill-switch defaults are inconsistent between routes
**Files:** `worker/src/routes/plans.ts:55-56,108-109` vs `worker/src/routes/payments.ts:58-59`
When no published config exists (`readPublishedConfig → null`), `/plans` and `/trial` report `paymentsEnabled: true` while the actual gate in `gatewayState()` treats null as **false** (plus requires `bkashNumber ≥ 8`). The UI would advertise payments that init/claim then refuse. **Fix:** make both read `?? false`, or (better) fix MINOR-14 so null never happens.

#### [MINOR-14] `readPublishedConfig` "fail-safe to defaults" branch is dead code
**File:** `worker/src/services/config.ts:32-38`
`validateConfig({})` always fails (every integer key missing → error), so the corrupt-row fallback evaluates `ok:false` and returns **null**, not `DEFAULT_CONFIG`, contradicting the comment. **Fix:**
```ts
if (result.ok) return result.config;
return DEFAULT_CONFIG;   // instead of the validateConfig({}) dance
```
This also resolves MINOR-13's divergence at the source.

#### [MINOR-15] `GET /config` returns the raw stored JSON — no validation/clamping
**File:** `worker/src/routes/app.ts:483-503`
Unlike `readPublishedConfig` (validate + fill defaults), the app-facing `/config` returns the stored JSON as-is; a legacy v1 config (the seed) omits every Phase C/D key. The native `RuntimeConfig` clamps client-side (per SECURITY_MODEL), so this is contract inconsistency rather than a bypass, but `/config` and `/plans` can now disagree about the same values. **Fix:** run the row through `validateConfig` (or `readPublishedConfig`) and return the normalized doc + version.

#### [MINOR-16] Unguarded `JSON.parse` in admin config routes → 500s
**File:** `worker/src/routes/admin.ts:597-598` (`adminGetConfig`), `:658` (`adminPublishConfig`), `:694` (`adminRollbackConfig`)
A corrupt `config_json` row (hand-edited DB, partial write) throws and produces a 500 envelope. `getConfig` in app.ts handles this correctly with try/catch — mirror it. **Fix:** wrap in try/catch returning `fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500)` after logging, or `VALIDATION_FAILED` for publish/rollback.

#### [MINOR-17] `adminPatchAnnouncement` accepts invalid dates (create validates, patch doesn't)
**File:** `worker/src/routes/admin.ts:852-853`
`startAt`/`endAt` are bound without `Date.parse` validation or an `endAt > startAt` check, unlike `adminCreateAnnouncement` (`:802-806`). Garbage dates silently poison the app's `start_at <= now <= end_at` window comparisons. **Fix:** apply the same validation when either field is patched (including cross-checking against the stored counterpart).

#### [MINOR-18] `adminPatchFlag` doesn't validate `minimumVersion` as semver
**File:** `worker/src/routes/admin.ts:757-759`
Any ≤32-char string is accepted (e.g. `"later"`), which then flows into `compareVersions` — which returns 0 for NaN (MINOR-19) — so the gate silently no-ops. **Fix:** reuse the `SEMVER_RE` check from `adminPostAppVersion` (`:1101`).

#### [MINOR-19] `compareVersions` returns 0 (equal) on non-numeric segments
**File:** `worker/src/routes/app.ts:514-525`
`Number.isNaN → return 0` means an invalid client-supplied `appVersion` query param is treated as satisfying every `minimum_version`. **Fix:** return `1` (treat unparseable as too old → flag off) or validate `appVersionParam` against a semver regex before use.

#### [MINOR-20] `decodeURIComponent` on path params can throw → 500
**File:** `worker/src/index.ts:237`
`decodeURIComponent('%')` throws `URIError`; the outer catch converts it to a 500 envelope (no stack leak), but a malformed path should be a 400. **Fix:** wrap in try/catch inside `matchRoute` and treat failure as no-match.

#### [MINOR-21] Admin rate limit is keyed by IP, not admin — doc/impl mismatch
**Files:** `worker/src/index.ts:172-216` (rateLimit before requireAdmin), `worker/src/middleware/rateLimit.ts:18-19,72`
The rateLimit.ts header says "admin limits run AFTER adminAuth (keyed by adminId)", but every admin route lists `rateLimit('admin','admin')` **before** `requireAdmin`, so `c.admin` is null and the bucket falls back to `clientIp`. Effect: 120/min per IP (a shared office NAT trips it; one attacker IP is capped the same either way). **Fix:** swap the middleware order (`[requireAdmin(...), rateLimit('admin','admin')]`) so the key is `adm:<adminId>` as documented, or fix the comment.

#### [MINOR-22] No admin logout/revocation endpoint; KV session caveats
**Files:** route list (`src/index.ts:169-217` — no `/admin/auth/logout`), `middleware/adminAuth.ts:148-155`
Admin sessions live 8h with no server-side revocation path (role/disable changes *do* take effect immediately via the D1 re-read — good). Additionally, KV is eventually consistent (up to ~60s cross-colo), so a login immediately followed by an API call from another PoP can 401. **Fix:** add `POST /admin/auth/logout` that deletes `admin_sess_<hash>`; consider D1-backed sessions or Durable Objects if strict consistency is ever required.

#### [MINOR-23] Rate limiter: non-atomic KV counters + documented fail-open
**File:** `worker/src/middleware/rateLimit.ts:93-119`
The read-increment-write on KV races under concurrency (bursts can exceed the limit), and KV errors fail open (documented availability tradeoff). Acceptable for abuse mitigation, not for precise enforcement. **Fix (optional):** use Durable Object counters or `KV.list`-free atomic primitive; at minimum keep the fail-open logging (already present).

#### [MINOR-24] No support-ticket intake route; no events retention; no cron at all
**Files:** route map (§2), `wrangler.toml` (no `[triggers]`)
`support_tickets` is admin-list/patch only — no user-facing `POST /tickets` exists anywhere (mobile client has none either), so the Support page manages a table nothing populates. The `events` table grows unbounded (60 events/min/device × devices) with no retention job. **Fix:** add `POST /tickets` (user-scoped, rate-limited) if the support flow is wanted; add a daily cron to prune `events` older than N days and to expire subscriptions (see MAJOR-6).

#### [MINOR-25] Google `aud` falls back to `GOOGLE_CLIENT_SECRET` — production login can't succeed until `GOOGLE_CLIENT_ID` is set
**File:** `worker/src/routes/app.ts:118-121` (+ `types.ts:26-32`)
When only `GOOGLE_CLIENT_SECRET` is configured (the documented deploy path), the expected audience is the *secret*, which real Google ID tokens never match → every production login 401s. This is a documented placeholder, but it's a deploy trap: the check silently "works" while rejecting everyone. **Fix:** require `GOOGLE_CLIENT_ID` in staging/production (fail closed at startup/log with a loud warning), keep the secret for a different purpose or drop it.

#### [MINOR-26] Unique-constraint races surface as 500s (trial claim, bKash reference, commit id)
**Files:** `worker/src/routes/plans.ts:168-173`, `payments.ts:144-163`, `community.ts:249-264`
Concurrent duplicate `trial/claim`s (UNIQUE `device_id`), a 4-byte reference collision (`MLD-XXXXXXXX`, 16^8 space — birthday collision plausible at ~ tens of thousands of payments), or `commit`/`referral` id collisions throw and 500. **Fix:** catch the constraint error and return 409 (trial), and retry reference generation on collision (bKash).

#### [MINOR-27] `wrangler.toml`: placeholder IDs + `workers_dev = true` in production
**File:** `worker/wrangler.toml:23,27` (and every env block: `REPLACE_WITH_*_D1_DATABASE_ID`, `REPLACE_WITH_*_KV_NAMESPACE_ID`), `:9,38,55,83`
Deploys fail until real IDs are filled (fine), but `workers_dev = true` on the production env keeps a public `mld-api.<account>.workers.dev` origin live alongside the custom domain — an extra surface to firewall (CORS will still gate browsers, but the API itself is reachable). Bindings themselves (`DB`, `KV`) match `Env` usage exactly; `migrations_dir` matches the shipped migrations; 3 envs each redeclare bindings correctly. No cron triggers exist (see M6/m24). **Fix:** set `workers_dev = false` for production once the route/custom domain is attached.

#### [MINOR-28] `adminAdjustCoins` read-modify-write race can mis-state `balance_after`
**File:** `worker/src/routes/admin.ts:426-441`
Balance is SELECTed, checked, then a row is INSERTed with a precomputed `balance_after`. Two concurrent adjustments for the same user can both pass the `>= 0` check and record contradictory/stale `balance_after` values (the DB CHECK only validates the *column*, not the true sum; `SUM(amount)` remains the source of truth so this is bookkeeping drift, not mintable coins). **Fix:** wrap in `DB.batch` with a re-SELECT inside the transaction, or enforce with a SQLite `AFTER INSERT` trigger that recomputes `balance_after` from the ledger.

---

## 4. Explicit security checks performed (no findings)

| Check | Result | Evidence |
|---|---|---|
| SQL injection | **None** — every statement is `.prepare(...).bind(...)`; all dynamic SQL fragments (`${where}`, `${sets.join(', ')}`) are composed exclusively of hardcoded literals with `?` placeholders (grep-swept all 16 files) | e.g. `admin.ts:294-314`, `admin.ts:764` |
| Auth bypass / missing auth on routes | **None** — every route except `/auth/google`, `/auth/refresh`, `/admin/auth/login`, `/app/version`, `/health` carries `requireUser` or `requireAdmin` (the one broken chain is M1, which fails *closed*) | `index.ts:120-217` |
| IDOR on user-scoped reads | **None** — devices/payments/bkash/tickets/subscriptions all filter by `c.user.userId`; heartbeat's device override re-checks ownership (`app.ts:435-436`); the only cross-user echo is the low-impact MINOR-3 | `payments.ts:203-207,255-259`, `app.ts:431-437` |
| XSS | **None** — JSON-only API, `Content-Type: application/json; charset=utf-8` + `X-Content-Type-Options: nosniff` + CSP `default-src 'none'` on every response; no HTML templating anywhere | `utils/response.ts:46-72` |
| CSRF | **N/A by design** — no cookie auth anywhere; state changes require the `Authorization: Bearer` header, which browsers never attach cross-origin without JS (and CORS is exact-match allowlisted, no `Access-Control-Allow-Credentials`) | `response.ts:53-59,106-118` |
| Secrets in code | **None committed** — `wrangler.toml` has placeholders only; `.dev.vars.example` has empty/dummy values; the two `[REDACTED:ssh_private_key]` artifacts are scanner output, not secrets (one of them *caused* MAJOR-2) | `wrangler.toml`, `.dev.vars.example` |
| Weak token generation | **None** — all tokens are `crypto.getRandomValues` 32-byte hex; only SHA-256 hashes stored (D1 sessions, KV admin sessions) | `utils/crypto.ts:33-42` |
| Timing-unsafe comparison | **None exploitable** — password + admin-session HMAC sig use constant-time `timingSafeEqual`; bearer tokens are looked up by hash in D1 (hash-then-lookup is the safe pattern); the admin-login PBKDF2 timing gap is MINOR-8 | `crypto.ts:57-62,93-100` |
| Admin RBAC gaps | **None** — frozen matrix, `requireAdmin` re-reads role+status from D1 every request (instant revocation), unknown roles demote to READ_ONLY, MANAGE_SYSTEM is SUPER_ADMIN-only; SUPPORT holding EDIT_USERS/MANAGE_PAYMENTS matches the documented matrix | `adminAuth.ts:141-185` |
| Stack traces / PII in errors | **None** — single catch-all in `index.ts:317-321` logs server-side only, returns generic 500 envelope; `requestId` correlation preserved | `index.ts:317-336` |
| Rewarded-ad coin verification (server) | **Not implemented — by design**: coins are a device-local Room ledger; the server ledger only receives audited `ADMIN_ADJUSTMENT` rows; no client-callable coin-granting endpoint exists (SECURITY_MODEL §3 "optional future mode") | `schema.sql:91-116`, route map |
| Emergency-code sync (server) | **Not implemented** — per-install TOTP lives on-device only; no server route stores or validates emergency codes; the only server-side mitigation is `stripEmergencyCode` scrubbing 6-digit numbers from Sinthia LLM output | SECURITY_MODEL §4, `community.ts:128-131` |
| Entitlement-after-verification | **Correct for Play**: the route calls the Play Developer API before any upsert and honestly returns 503 when unconfigured (never fakes success); bKash grants only after human review; trial is config-gated. The *implementation* bugs are M2/M3/M5/M6 | `app.ts:849-925`, `payments.ts`, `plans.ts` |
| Envelope/contract consistency | Good — every response via `ok()`/`fail()` with frozen `{success,data|error,requestId}` shape; `Retry-After` on 429s; consistent error codes | `utils/response.ts:85-103` |
| Type safety | `tsc --noEmit` strict passes with 0 errors (verified in isolated copy; project untouched) | §1 |

**Positive observations worth preserving during fixes:** D1 `batch()` used for all genuinely-atomic user flows (refresh rotation, device bind, cheers); `INSERT OR IGNORE` idempotency for events and cheers; append-only triggers on `coin_transactions`/`audit_logs`/`security_events`; LIKE-escaping done correctly in all *admin* search paths; keyset pagination with opaque base64 cursors everywhere (no OFFSET scans); `parseLimit` capped at 100; KV fail-open only in the limiter (auth fails closed).

---

## 5. wrangler.toml / bindings / DB review

- **Bindings match code**: `DB` (D1) and `KV` (KV) names in all four blocks match `Env.DB`/`Env.KV` (`types.ts:20-24`). Vars `API_ENV`/`ADMIN_ORIGIN` are consumed by `response.ts`/`getFlags`/`adminOverview`. Secrets referenced (`ADMIN_SESSION_SECRET`, `GOOGLE_CLIENT_SECRET`, optional `GOOGLE_PLAY_SA_*`, `AI_API_KEY`) are all `wrangler secret` material — none committed.
- **Migrations**: `migrations_dir = "src/db/migrations"` with 001/002/003 present; `schema.sql` is the superset (001+002+003 concatenated, ending with the r9 engagement tables). Both paths are idempotent (`IF NOT EXISTS` + `INSERT OR IGNORE`), so the README's "run schema.sql" and `wrangler d1 migrations apply` compose safely.
- **No cron triggers** — see MAJOR-6 and MINOR-24 for the two jobs that are actually needed (subscription expiry, events retention).
- **Placeholders**: all `database_id`/KV `id`s are `REPLACE_WITH_*` — deploy blocks until filled (intentional).
- **`community_*` tables** lack foreign keys (no `REFERENCES users`), unlike every other table — orphanable rows if users are ever hard-deleted; also `commit_cheers`/`friends` use composite PKs correctly. Cosmetic.
- **`types.ts` nits**: `UserSessionRow.refresh_token_hash` typed `string | null` though the column is `NOT NULL` (harmless); `AppVersion.id: number` vs schema `TEXT` PK `apv_...` (the admin route inserts `apv_<token>` strings and the serializer never reads the id — harmless but wrong type).

---

## 6. Priority fix order (recommended)

1. **M1** logout middleware (one line) — restores user revocation.
2. **M2 + M3** PEM regex + package name — unblocks the entire Play monetization path.
3. **M5** bKash claim-before-grant — closes the double-grant money bug.
4. **M6** subscription expiry honesty + cron — entitlement/metrics integrity.
5. **M7** seed-admin lockout outside dev — cheapest full-takeover prevention.
6. **M4** flags env case — contract correctness before any client starts consuming `/flags`.
7. Then the MINOR list, starting with MINOR-14/13 (config fallback), MINOR-11 (dead referral surface), MINOR-9 (search escaping + display names), MINOR-10 (`meta.changes`), MINOR-21/22 (admin limiter order + logout).

---

*End of report. Generated by Task 3-c (worker-auditor). All file/line references are to the tree at `/home/z/my-project/work/maxlevel-detox/worker` as shipped (r9.5, zip `MAXLEVEL-DETOX-complete-project-v2_5_5-r9_5.zip`). No project files were modified during this audit.*
