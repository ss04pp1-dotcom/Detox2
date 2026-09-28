# MAXLEVEL DETOX — Admin Panel

Professional SaaS control center for the MAXLEVEL DETOX ecosystem (React SPA on
Cloudflare Pages). Dark, desktop-first, data-oriented.

- React 18 + TypeScript (strict) + Vite 5
- Tailwind CSS 3 (custom dark design tokens)
- react-router-dom 6, recharts, lucide-react
- Consumes the frozen API contract at `/api/v1/admin/*`

## Quick start

```bash
cd admin
npm install
npm run dev          # http://localhost:5173 (proxies /api -> localhost:8787)
```

Start the Worker first (`worker/README.md`, `npm run dev` in `worker/`), then
log in with the seeded SUPER_ADMIN (`admin@maxleveldetox.com` /
`ChangeMe_2026!`).

## Demo mode (no backend needed)

```bash
VITE_API_URL=mock npm run dev
```

Runs entirely on built-in mock data (any admin email + password `demo1234`).
A "Mock data" badge reminds you that nothing is real.

## Configuration

| Variable | Meaning |
|---|---|
| `VITE_API_URL` | API base. Empty = same origin. `mock` = demo data. Real: `https://api.maxleveldetox.com/api/v1` |

## Build & deploy (Cloudflare Pages)

```bash
npm run build         # tsc + vite build -> dist/
npx wrangler pages deploy dist --project-name mld-admin
```

Or connect the Git repo in the Cloudflare Pages dashboard (build command
`npm run build`, output dir `dist`). Attach the custom domain
`admin.maxleveldetox.com`.

`public/_headers` ships production security headers (CSP, nosniff, HSTS,
frame-ancestors 'none', locked-down Permissions-Policy).

**Strongly recommended:** put Cloudflare Access (email OTP + MFA) in front of
`admin.maxleveldetox.com` as an additional layer. The API enforces RBAC +
admin sessions regardless — Access is defense in depth.

## Security notes

- No secrets ever live in this SPA. All privileged operations go through the
  Worker, which authorizes every request against the RBAC matrix.
- Admin sessions are opaque KV-backed tokens; the SPA keeps them in memory +
  sessionStorage only (closing the tab logs out).
- Every mutating UI action confirms dangerous operations and surfaces
  `requestId` on errors for support correlation.
- Tables paginate server-side (cursor based) — the browser never loads
  thousands of rows.

## Pages

| Route | Purpose | Permission |
|---|---|---|
| `/` | Dashboard — metrics, health, recent audit | VIEW_ANALYTICS |
| `/live` | Live operations (30s auto-refresh) | VIEW_ANALYTICS |
| `/users`, `/users/:id` | User list/detail, suspend/activate, coin adjust | VIEW_USERS / EDIT_USERS |
| `/devices` | Registered devices + risk badges | VIEW_DEVICES |
| `/subscriptions` | Play-verified entitlements | VIEW_USERS |
| `/config` | Remote Config draft/publish/rollback (type-to-confirm) | MANAGE_CONFIG |
| `/flags` | Feature flags + staged rollout | MANAGE_FLAGS |
| `/app-versions` | Version gating + force update | MANAGE_CONFIG |
| `/announcements` | Announcement management | MANAGE_ANNOUNCEMENTS |
| `/analytics` | Product / Enforcement / Revenue analytics | VIEW_ANALYTICS |
| `/support` | Ticket triage | MANAGE_SUPPORT |
| `/security` | Security events feed | VIEW_AUDIT |
| `/audit` | Append-only audit logs | VIEW_AUDIT |
| `/system` | API health probes | MANAGE_SYSTEM |
| `/system/admins` | Admin users (SUPER_ADMIN only) | MANAGE_SYSTEM |
