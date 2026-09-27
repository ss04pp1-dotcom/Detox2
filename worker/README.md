# MAXLEVEL DETOX — Cloudflare Worker API

Serverless backend for the MAXLEVEL DETOX ecosystem: accounts, devices, remote
configuration, feature flags, announcements, subscription verification,
analytics events, support tickets, and the full admin API (RBAC + audit logs).

**Golden rule: the server manages the product. The device manages enforcement.
Core Study/Detox enforcement never depends on this API being reachable.**

- Runtime: Cloudflare Workers (TypeScript, zero runtime dependencies)
- Database: Cloudflare D1 (`worker/src/db/schema.sql`)
- KV: rate limiting, admin sessions, config/Play token caches
- API base: `https://api.maxleveldetox.com/api/v1`

---

## 1. Layout

```
worker/
├── wrangler.toml              # 3 environments + bindings
├── src/
│   ├── index.ts               # router, middleware chain, observability
│   ├── types.ts               # Env bindings + shared domain types
│   ├── middleware/
│   │   ├── auth.ts            # user bearer sessions (D1, SHA-256 at rest)
│   │   ├── adminAuth.ts       # admin KV sessions + RBAC matrix
│   │   ├── rateLimit.ts       # KV sliding window
│   │   └── validation.ts      # schema checks + CONFIG_BOUNDS
│   ├── routes/
│   │   ├── app.ts             # /auth, /me, /devices, /config, /flags,
│   │   │                      # /announcements, /events, /subscription
│   │   └── admin.ts           # /admin/* (RBAC-gated, audited)
│   ├── services/
│   │   ├── audit.ts           # append-only audit + security events
│   │   └── serializers.ts     # row -> API shape
│   ├── utils/
│   │   ├── crypto.ts          # PBKDF2, HMAC, SHA-256, token generation
│   │   └── response.ts        # frozen envelope + CORS + security headers
│   └── db/
│       ├── schema.sql         # full schema + seeds
│       └── migrations/001_initial.sql
└── .dev.vars.example          # local secrets template
```

## 2. Deploy (first time)

```bash
cd worker
npm install
npx wrangler login

# D1 + KV (one per environment)
npx wrangler d1 create mld-db-dev        # note database_id into wrangler.toml
npx wrangler kv namespace create MLD_KV_DEV

# Migrate + seed
npx wrangler d1 execute mld-db-dev --local --file=src/db/schema.sql
# (repeat per environment; for remote: drop --local and use the prod DB name)

# Secrets
npx wrangler secret put ADMIN_SESSION_SECRET   # long random string (openssl rand -hex 32)
npx wrangler secret put GOOGLE_CLIENT_SECRET   # used as placeholder aud until GOOGLE_CLIENT_ID exists

# Run locally
npm run dev                                     # wrangler dev on :8787

# Deploy
npx wrangler deploy                             # or: deploy --env staging / --env production
```

Attach the custom domain `api.maxleveldetox.com` in the Cloudflare dashboard
(Workers Routes / Custom Domains) for the production environment.

## 3. Seed admin login

The schema seeds one SUPER_ADMIN:

- Email: `admin@maxleveldetox.com`
- Password: `ChangeMe_2026!`

**Change it before going anywhere near production.** To set a new password,
generate a hash and update the row:

```bash
node -e "
const { webcrypto } = require('crypto');
(async () => {
  const pw = process.argv[1];
  const salt = webcrypto.getRandomValues(new Uint8Array(16));
  const km = await webcrypto.subtle.importKey('raw', new TextEncoder().encode(pw), 'PBKDF2', false, ['deriveBits']);
  const bits = await webcrypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 100000 }, km, 256);
  const hex = (b) => Buffer.from(b).toString('hex');
  console.log(hex(salt) + '\$' + hex(bits));
})();
" 'YOUR_NEW_PASSWORD'
```

Then: `npx wrangler d1 execute mld-db-prod --command "UPDATE admin_users SET password_hash='<output>' WHERE email='admin@maxleveldetox.com'"`

## 4. Environments

| Env | Worker name | D1 | Purpose |
|---|---|---|---|
| development | mld-api-dev | mld-db-dev (local) | `wrangler dev`, `.dev.vars` |
| staging | mld-api-staging | mld-db-staging | integration testing |
| production | mld-api | mld-db-prod | live traffic |

Fill in the real `database_id` / KV ids in `wrangler.toml` after creating them.
Never point development at production data.

## 5. Security model (summary)

- **Envelope**: every response is `{success, data|error, requestId}` — see
  `src/utils/response.ts`. No stack traces ever reach the client.
- **CORS**: strict allowlist (`https://admin.maxleveldetox.com` + dev localhost).
  No wildcard on authenticated routes.
- **Headers**: CSP `default-src 'none'`, nosniff, HSTS, strict referrer policy.
- **SQL**: 100% parameterized D1 statements (`.bind`). No interpolation.
- **Secrets**: only via `wrangler secret` — never in git, never in the admin SPA.
- **Admin auth**: PBKDF2-SHA256 (100k iterations) passwords; opaque KV sessions
  signed with `ADMIN_SESSION_SECRET` (HMAC tamper-evidence); role re-read from
  D1 on every request so revocations are immediate.
- **RBAC**: frozen matrix in `middleware/adminAuth.ts` — SUPER_ADMIN, ADMIN,
  SUPPORT, ANALYST, CONFIG_MANAGER, READ_ONLY.
- **Audit**: every sensitive admin action appends to `audit_logs` (append-only,
  enforced by DB triggers).
- **Rate limits**: KV sliding window — auth 10/min/IP, events 60/min/device,
  heartbeat 4/min/device, admin 120/min/admin + dedicated admin-login limiter.
- **Idempotency**: events dedupe by `event_id` PK (`INSERT OR IGNORE`);
  coin ledger append-only with unique `transaction_id`.

**Recommended**: put Cloudflare Access (email OTP + MFA) in front of
`admin.maxleveldetox.com` as an additional layer — defense in depth.

## 6. Google Sign-In

`POST /auth/google` verifies the Google ID token via the tokeninfo endpoint
(`aud`, `email_verified`, `exp` checks). Set the real client id:

```bash
npx wrangler secret put GOOGLE_CLIENT_SECRET
# or better, the dedicated var once you have it:
# [vars] GOOGLE_CLIENT_ID in wrangler.toml
```

In **development** only (no secret configured), `credential: "dev:<email>"`
logs in / creates that user for local testing. This path is hard-disabled in
staging/production.

## 7. Google Play subscriptions

`POST /subscription/verify` calls the Play Developer API with a service-account
JWT (RS256, cached access token). Configure:

```bash
npx wrangler secret put GOOGLE_PLAY_SA_EMAIL
npx wrangler secret put GOOGLE_PLAY_SA_PRIVATE_KEY   # PEM, PKCS#8
```

Until configured the route returns an honest `SERVICE_UNAVAILABLE` — it never
fakes a verification result. Client claims are never trusted.

## 8. Test the envelope

```bash
curl -s http://localhost:8787/health
curl -s -X POST http://localhost:8787/api/v1/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@maxleveldetox.com","password":"ChangeMe_2026!"}'
```

Every response carries `requestId` — users attach it to support tickets and it
maps 1:1 to a structured `console.log` line in Worker logs.

## 9. Local D1 reset

```bash
npx wrangler d1 execute mld-db-dev --local --file=src/db/schema.sql
# schema.sql uses IF NOT EXISTS + INSERT OR IGNORE, so re-running is safe.
```
