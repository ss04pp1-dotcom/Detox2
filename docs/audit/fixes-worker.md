# MAXLEVEL DETOX — Worker Audit Fix Changelog

**Task ID:** 7-a · **Agent:** worker-fixer (TypeScript/Cloudflare Workers) · **Date:** 2026-09-21
**Scope:** `worker/` only (`src/**`, `wrangler.toml`). `mobile/` and `admin/` untouched.
**Source:** `/home/z/my-project/work/audit/report-worker.md` (Task 3-c, r9.5 audit).
**Verification:** `npx tsc --noEmit` (strict, `noUnusedLocals`/`noUnusedParameters`/`noImplicitReturns`) — **0 errors** (baseline was 0). All changes diffed against the original zip (`/home/z/my-project/upload/MAXLEVEL-DETOX-complete-project-v2_5_5-r9_5.zip`); exactly 8 intended files changed (`src/index.ts`, `src/routes/app.ts`, `src/routes/admin.ts`, `src/routes/community.ts`, `src/routes/plans.ts`, `src/services/config.ts`, `src/middleware/adminAuth.ts`, `wrangler.toml`).

---

## ⚠️ Important cross-cutting finding: M2 was a FALSE POSITIVE (tool-display redaction artifact)

**TL;DR: `worker/src/utils/crypto.ts:117` was NEVER mangled — the shipped `pemToDer` regex is already correct at the byte level. No change to `pemToDer` was needed or made.**

Evidence (byte-level, immune to display filtering):

1. Codepoint dump of `crypto.ts` line 117 in the work tree AND in the original zip (extracted to `/tmp/zipcheck`) both decode to:
   `    .replace(/-----BEGIN PRIVATE KEY-----/g, '')` — 48 chars, codepoints `45,45,45,45,45,66,69,71,73,78,…`.
2. The sandbox's tool **output display filter** redacts the literal `-----BEGIN PRIVATE KEY-----` (but not the `-----END PRIVATE KEY-----` marker) as `[REDACTED:ssh_private_key]` whenever it appears in Read/cat/grep output. The auditor (and initially this agent) saw the filtered rendering and reasonably concluded a secret-scanner had mangled a character-class regex.
3. The audit's corroborating "evidence" — `[REDACTED:ssh_private_key]` in `.dev.vars.example:13` — is **also** a display artifact: a byte-level check of that line shows `# GOOGLE_PLAY_SA_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"` (an attempted scripted repair correctly aborted because the pattern was not present in the actual bytes; file md5 unchanged, `531761ad837d26fadbae293829da9a1d`).
4. Note the audit's own "Fix (exact)" for M2 shows *identical* before/after code — because the report text itself was filtered when written; the author could not express the literal header string.

**Practical consequence:** `POST /subscription/verify` was never broken by the regex. What *was* still worth fixing from M2 is applied: the `crypto.subtle.importKey` call in `getPlayAccessToken` is now wrapped in try/catch → a genuinely bad/corrupt service-account key yields the honest `SERVICE_UNAVAILABLE` (503) instead of a 500.

**Warning for other agents:** whenever an audit/Read output shows `[REDACTED:ssh_private_key]`, verify the real bytes with `node -e "...codePointAt(0)..."` before "fixing" anything — the file on disk is probably fine.

---

## MAJOR fixes

### M1 — `POST /auth/logout` could never succeed (missing `requireUser`)
- **File:** `src/index.ts:124-126`
- **Change:** route chain changed from `[rateLimit('auth','ip')]` to `[requireUser, rateLimit('auth','ip')]`. `authLogout` (app.ts) is unchanged — with `c.user` injected it now deletes the session row and returns `{loggedOut:true}`.

### M2 — PEM parser "mangled" + unguarded key import
- **File:** `src/routes/app.ts:824-838` (`getPlayAccessToken`)
- **Change:** `crypto.subtle.importKey(...)` wrapped in try/catch; on failure logs `play service-account key import failed` and returns `null`, which `subscriptionVerify` maps to its existing honest 503 envelope ("Subscription verification is not configured…"). `pemToDer` itself required **no change** (see the cross-cutting finding above — the audit finding was a display-filter false positive; verified byte-identical to the shipped zip).

### M3 — Wrong Play `PACKAGE_NAME`
- **File:** `src/routes/app.ts:42-45`
- **Change:** `const PACKAGE_NAME = 'com.maxleveldetox.app'` → `'com.maxleveldet0x'` (matches `mobile/android/app/build.gradle` `applicationId`); stale TODO comment replaced with an explanatory comment.

### M4 — Feature-flag environment case mismatch
- **File:** `src/routes/app.ts:598-600` (`getFlags`)
- **Change:** `row.environment !== c.env.API_ENV.toUpperCase()` → `row.environment !== c.env.API_ENV` (both sides lowercase, matching the DB CHECK constraint). Seeded `production` flags now report their real enabled state.

### M5 — bKash verify/reject double-grant & grant-on-reject races
- **File:** `src/routes/admin.ts:1554-1605` (`adminVerifyBkashPayment`), `1650-1664` (`adminRejectBkashPayment`)
- **Change (verify):** reordered to *claim-first*: compute `subscriptionId`/`newExpiry` (preserving the extend-from-current-expiry logic), then run the guarded `UPDATE bkash_payments SET status='VERIFIED', reviewed_by=?, reviewed_at=?, subscription_id=?, updated_at=? WHERE id=? AND status='IN_REVIEW'` and **check `claim.meta.changes === 0` → 409 CONFLICT "Payment was already processed by another reviewer"** *before* the subscription UPDATE/INSERT grant runs. `bkash_payments.subscription_id` has no FK (schema.sql:316), so recording the target subscription id in the claim is safe even moments before the subscription row is written. Audit trail (BKASH_PAYMENT_VERIFIED) and response shape preserved.
  - *Note on "ideally in a DB.batch":* the claim cannot be batched together with the grant — a D1 batch executes all statements unconditionally, so the grant would run even when the claim matched 0 rows (the exact bug being fixed). The claim is therefore a standalone guarded statement (checked) and the grant follows as a single atomic statement; a crash between them leaves a VERIFIED payment with a detectable/recoverable missing grant — never a double grant.
- **Change (reject):** the previously unguarded `UPDATE … WHERE id=?` is now a claim guarded by the observed state: `WHERE id=? AND status=?` (bound to the just-read `payment.status`), with `meta.changes === 0` → 409 CONFLICT. Preserves existing semantics (reject allowed from PENDING/IN_REVIEW/EXPIRED/CANCELED) while making concurrent verify-vs-reject interleavings impossible.

### M6 — Subscriptions never expire server-side
- **(a) Honest status:** `src/routes/app.ts:1007-1025` (`getSubscription`) and `370-380` (`getMe`) — a row with `status='ACTIVE'` and `expiry_date <= now` is mapped to `status:'EXPIRED'` in the response (read-time honesty even before the cron flips the stored row).
- **(b) Cron:** `wrangler.toml:15-19` — new top-level `[triggers] crons = ["*/30 * * * *"]`; `src/index.ts:360-381` — new `scheduled` export handler running `UPDATE subscriptions SET status='EXPIRED', updated_at=? WHERE status='ACTIVE' AND expiry_date IS NOT NULL AND expiry_date < ?` via `ctx.waitUntil`, with success/error logging.
- **(c) Premium-count predicates:** `src/routes/admin.ts:247-250` (`adminOverview.premiumUsers`) and `1027-1030` (`adminAnalytics.revenue.premiumUsers`) — both queries now include `AND (expiry_date IS NULL OR expiry_date > ?)` bound to `nowIso` (new `nowIso` locals at admin.ts:239 and 1006).

### M7 — Seed super-admin lockout outside development
- **File:** `src/routes/admin.ts:140-156` (`adminLogin`)
- **Change:** after the SELECT, if `API_ENV !== 'development'` and `row?.id === 'adm_seed_superadmin'`, appends a HIGH-severity `AUTH_FAILURE` security event (`reason:'seed_admin_locked_out'`) and returns `403 FORBIDDEN — "Seed admin password must be rotated before use (see worker/README.md §3)"`. Dev behavior unchanged.

---

## MINOR fixes

- **m1 (refresh replay race):** `src/routes/app.ts:304-329` — rotation is now *claim-first*: guarded `DELETE FROM user_sessions WHERE token_hash=? AND refresh_token_hash=?` awaited and checked (`meta.changes === 0` → `401 "Refresh token already used"`), and only then the new session pair is INSERTed. The previous `DB.batch([DELETE, INSERT])` let two concurrent replays both succeed because a 0-row DELETE is not an error.
- **m2 (concurrent signup 500):** `src/routes/app.ts:175-230` — the user INSERT is wrapped in try/catch; on a UNIQUE-constraint error (message contains `UNIQUE`) the row is re-SELECTed and the session is issued (200). Status checks (BANNED/SUSPENDED/DELETED) and the last-seen/deletion-cancel UPDATE now run for both the fresh-insert path (skipping the redundant UPDATE via `createdNow`) and the race-recovery path.
- **m3 (cross-user subscription echo):** `src/routes/app.ts:978-999` — final SELECT in `subscriptionVerify` is now `WHERE purchase_token=? AND user_id=?`; if no own row exists but another user owns the token, returns `409 CONFLICT — "This purchase is already linked to another account — contact support"`.
- **m6 (DELETE /me spam):** `src/index.ts:129-131` adds `rateLimit('auth','device')` to the route; `src/routes/app.ts:1031-1047` short-circuits idempotently when `deletion_pending_at` is already set (no extra security_event row, no timestamp rewrite).
- **m7 (missing rate limits):** `src/index.ts:128` (GET /me), `144-145` (GET /subscription), `162-163` (GET /friends) — all now carry `rateLimit('config','device')` like every other app read route.
- **m8 (admin login timing oracle):** `src/routes/admin.ts:79-85, 158-163` — fixed dummy PBKDF2 hash `DUMMY_PASSWORD_HASH` (32-hex zero salt `$` 64-hex zero digest, passes `verifyPassword`'s format checks, burns the same 100k iterations) is verified when the email is unknown, equalizing latency.
- **m9 (LIKE wildcard escape):** `src/routes/community.ts:336-344` — `searchUsers` now uses `WHERE display_name LIKE ? ESCAPE ?` with `%${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%` and `'\\'` (the admin.ts:294-295 pattern).
- **m10 (acceptFriendRequest 404):** `src/routes/community.ts:402-405` — replaced the always-true `!result.success` check with `(result.meta.changes ?? 0) === 0 → 404 'No pending request'`.
- **m13/m14 (config fallback):** `src/services/config.ts:11, 28-41` — the dead `validateConfig({})` dance is gone; a corrupt/stale stored config (validation failure **or** JSON.parse throw) now returns `DEFAULT_CONFIG` directly. `src/routes/plans.ts:52-60, 106-115` — `paymentsEnabled` default changed `?? true` → `?? false` in both `/plans` and `/trial`, matching `gatewayState()` in payments.ts (payments.ts needed no change — it already used `?? false`).
- **m16 (unguarded JSON.parse):** `src/routes/admin.ts:641-658` — new `parseStoredConfigJson(requestId, version, json)` helper (logs server-side, returns null on corrupt JSON); used by `adminGetConfig` (`668-680`, both draft and published → `fail('SERVER_ERROR','Configuration is corrupt',500)`), `adminPublishConfig` (`744-748`), and `adminRollbackConfig` (`783-789`).
- **m17 (patch announcement dates):** `src/routes/admin.ts:926-989` — `adminPatchAnnouncement` now SELECTs `start_at, end_at` too, validates any patched `startAt`/`endAt` with `Date.parse`, and cross-checks the *effective* window (patched value or stored counterpart) `endAt > startAt` before writing.
- **m18 (minimumVersion semver):** `src/routes/admin.ts:849-857` — `adminPatchFlag` rejects non-semver `minimumVersion` with 400; the shared `SEMVER_RE` (admin.ts:86-87) also replaces the inline regex in `adminPostAppVersion` (admin.ts:1240).
- **m19 (compareVersions NaN):** `src/routes/app.ts:565-568` — returns **-1** (not 0) on a NaN segment. **Deliberate deviation from the instruction's literal "return 1":** the only call site is `compareVersions(appVersionParam, row.minimum_version) < 0 → flag disabled`; returning 1 (like 0) still fails the `< 0` test, i.e. changes nothing. The audit's stated *intent* — "treat unparseable as too old → flag off" — requires a negative return, so the flag gate now fails CLOSED on garbage versions. (Returning 1 would have been a no-op fix.)
- **m20 (decodeURIComponent throw):** `src/index.ts:248-256` — `matchRoute` wraps `decodeURIComponent` in try/catch; a malformed percent-encoding marks the route as no-match (→ 404) instead of throwing into the catch-all 500.
- **m21 (admin limiter keyed by IP):** `src/index.ts:172-229` — every admin route (except login) now lists `[requireAdmin(<perm>), rateLimit('admin','admin')]` so the limiter keys by `adm:<adminId>`, matching the rateLimit.ts documentation. Section comment updated.
- **m22 (admin logout):** new route `POST /api/v1/admin/auth/logout` (`src/index.ts:180-182`) chained `[requireAdminSession, rateLimit('admin','admin')]`; `src/middleware/adminAuth.ts:141-207` — `requireAdmin` refactored over a shared `resolveAdminSession()` (identical checks/messages/behavior) plus a new exported `requireAdminSession` middleware (session + HMAC + fresh D1 role/status, no permission requirement — any authenticated admin role may log itself out); `src/routes/admin.ts:205-227` — `adminLogout` handler deletes `admin_sess_<sha256(token)>` from KV, appends a `ADMIN_LOGOUT` audit row, returns `{loggedOut:true}`.
- **m25 (GOOGLE_CLIENT_ID fail-closed):** `src/routes/app.ts:120-137` — in staging/production without `GOOGLE_CLIENT_ID`, `authGoogle` logs a loud `console.warn` (with the `wrangler secret put` fix) and returns `503 SERVICE_UNAVAILABLE — "Google authentication is not configured (GOOGLE_CLIENT_ID missing)"`. Development keeps the historical placeholder behavior (secret-as-aud, `dev:<email>` when nothing configured).

## CONTRACT item (from the admin audit)

- **adminCreateAnnouncement endAt optional:** `src/routes/admin.ts:888-918` — `endAt` is `required:false`; absent/`null` inserts `NULL` (open-ended; the app-facing `getAnnouncements` already treats `end_at IS NULL` as "no end"). When present it must parse and be `> startAt`. `ANNOUNCEMENT_TYPES` validation untouched; serializer field names (`startTime`/`endTime`) untouched.
- **adminPatchAnnouncement likewise:** `src/routes/admin.ts:926-989` — patching `endAt: null` explicitly (raw-body key check, since the field validator treats null as absent) clears the end date; patched dates are validated and cross-checked (see m17).

---

## Intentionally skipped (with reasons)

| Item | Reason |
|---|---|
| M2's `pemToDer` regex "fix" | **False positive** — shipped bytes are already the correct literal-header regex (see cross-cutting finding). Nothing to fix; verified against the original zip. |
| audit MINOR-4 (subscriptions.plan stores raw productId), MINOR-5 (device cap race / no device-remove route), MINOR-11 (dead referral surface), MINOR-12 (AI prompt caps), MINOR-15 (/config raw JSON), MINOR-23 (KV limiter atomicity), MINOR-24 (ticket intake / events retention cron), MINOR-26 (unique-race 500s elsewhere), MINOR-27 (workers_dev=false / placeholder ids), MINOR-28 (coin balance_after race) | **Not in Task 7-a's fix list.** The task enumerated exactly which majors/minors to apply; these were excluded. (M6b's cron also partially addresses MINOR-24's subscription-expiry half; events retention remains open.) |
| `workers_dev = false` (mentioned in M7/report) | Deployment-time decision tied to custom-domain attach; report itself says "once the custom domain is attached". Left as-is (also MINOR-27, out of scope). |
| `.dev.vars.example:13` "scanner artifact" | Display-filter artifact too — actual bytes are the correct PEM example; no repair was needed (verified; file untouched). |

## Files changed (8)

1. `worker/src/index.ts` — M1, m6, m7, m20, m21, m22 (route wiring), M6b (scheduled handler)
2. `worker/src/routes/app.ts` — M3, M4, M2 (importKey guard), M6a, m1, m2, m3, m6, m19, m25
3. `worker/src/routes/admin.ts` — M5, M6c, M7, m8, m16, m17, m18, m22 (adminLogout), CONTRACT (endAt optional)
4. `worker/src/routes/community.ts` — m9, m10
5. `worker/src/routes/plans.ts` — m13 (`?? false`)
6. `worker/src/services/config.ts` — m13/m14 (DEFAULT_CONFIG fallback)
7. `worker/src/middleware/adminAuth.ts` — m22 (requireAdminSession + shared resolveAdminSession refactor; requireAdmin behavior unchanged)
8. `worker/wrangler.toml` — M6b (`[triggers] crons = ["*/30 * * * *"]`)

Untouched (verified identical to the zip): `src/utils/crypto.ts`, `src/utils/response.ts`, `src/middleware/{auth,rateLimit,validation}.ts`, `src/services/{audit,serializers}.ts`, `src/types.ts`, `src/db/**` (no schema/migration changes were required — `announcements.end_at` was already nullable), `payments.ts` (already `?? false`), `.dev.vars.example`, `README.md`, `package.json` (typescript@5 was already a devDependency and node_modules was present after `npm i -D typescript@5`).

## Verification

- `cd worker && npx tsc --noEmit` → **exit 0, zero diagnostics** (strict; baseline 0 errors preserved).
- Full recursive diff vs the shipped zip confirms exactly the 8 intended files changed and no accidental edits/duplicates.
- Route table re-checked: 67 routes (66 + new `POST /admin/auth/logout`); every admin route except login is `[requireAdmin(…), rateLimit('admin','admin')]`.
- `npx wrangler deploy --dry-run --env development` → config parses, bundle builds (153.97 KiB), bindings resolve. (The "Unexpected fields found in top-level field: migrations_dir" warning is pre-existing — that line was not touched.)
- SQLite smoke tests (`node:sqlite`, in-memory) of every new/changed statement, all passing:
  - M6 cron `UPDATE … WHERE status='ACTIVE' AND expiry_date < ?` → 1 row flipped; premium-count predicate → 0 after expiry.
  - M5 verify claim: first claim matches 1 row; replay claim matches 0 (→409); reject claim binding the previously-read status after a concurrent verify matches 0 (→409).
  - m1 guarded `DELETE … WHERE token_hash=? AND refresh_token_hash=?`: first rotation 1 row, concurrent replay 0 rows (→401).
  - m9 escaped LIKE + `ESCAPE '\'`: `q="100%_cool"` still finds the literal name, while a bare `%` query no longer enumerates every user (2 rows unescaped vs 1 with the fix).
