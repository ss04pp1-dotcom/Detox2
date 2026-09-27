# MAXLEVEL DETOX

**A security-first Android discipline platform providing maximum practical
digital-detox and study enforcement on supported devices.**

> «Cloudflare manages the product. Kotlin manages the phone. Flutter
> manages the experience.»

> **NOTE (applicationId):** the Android package id is deliberately
> obfuscated — `com.maxleveldet0x` (with a **zero**, not the letter o).
> It is set in `mobile/android/app/build.gradle` and MUST stay in sync
> with `PACKAGE_NAME` in `worker/src/routes/app.ts` (a mismatch makes
> Play answer 404 for every purchase lookup). Quote the exact spelling
> in support macros.

> **v2.5.8 roadmap round:** three product features shipped on top of the
> v2.5.7 fix round (see `docs/R11-CHANGES.md`): the opt-in **community DP
> leaderboard + clubs**, the interactive **Distraction Trend** home-screen
> widget, and the **dynamic remote detection-rule config** (server-pushed
> reels/shorts signatures — a platform UI update is healed within one
> 15-minute sync cycle, no app release). Worker tests: 58.

> **v2.5.7 audit-fix round:** the full response to the v2.5.6 codebase
> audit (42 findings — all Critical/High/Medium/Low items) is documented
> in `docs/audit/fixes-r10.md`, with the pre-release blockers collected
> in `docs/RELEASE-CHECKLIST.md` (keystore rotation, Play declarations,
> AdMob ids, backend secrets, migrations).

## What's in this repository

| Package | Path | Stack | Status |
|---|---|---|---|
| Mobile app | `mobile/` | Flutter (UI) + Kotlin (enforcement engine) | Complete source, ready to build |
| API backend | `worker/` | Cloudflare Workers + D1 + KV, TypeScript, zero runtime deps | Complete source, deploy guide included |
| Admin panel | `admin/` | React 18 + TS + Vite + Tailwind + recharts | Complete source, **build verified** |
| Documentation | `docs/` | — | Architecture + Security model |

## The four products in one

1. **Study Mode** — timed focus session with locked policy
2. **Detox Mode** — longer strict restriction (BALANCED / STRICT / MAXLEVEL)
3. **Reels/Shorts Blocker** — independent; shared cross-platform warning
   counter; 5 warnings → **30-minute Cage**
4. **Shockwave Alarm** — independent; loud, native full-screen; stops only
   on a solved cognitive puzzle

Economy: 1 completed rewarded ad = 1 coin · 5 coins = 5-minute temporary
unlock · 500 coins = bailout. Immutable on-device ledger.

## Quick start

### Mobile
```bash
cd mobile && flutter pub get && flutter run
```
Details, prerequisites and the QA checklist: `mobile/README.md`.

### Backend
```bash
cd worker && npm install && npx wrangler login
npx wrangler d1 create mld-db-dev       # put the id into wrangler.toml
npx wrangler d1 execute mld-db-dev --local --file=src/db/schema.sql
npm run dev                              # api on :8787
```
Seeded admin: `admin@maxleveldetox.com` / `ChangeMe_2026!` (change first).
Full guide: `worker/README.md`.

### Admin
```bash
cd admin && npm install
npm run dev                              # proxies /api to :8787
VITE_API_URL=mock npm run dev            # demo mode, no backend needed
```
Full guide: `admin/README.md`.

## Architecture in one screen

```
Flutter (experience) → MethodChannel → Kotlin (enforcement)
                                        │
                            SystemClock.elapsedRealtime + DataStore/Room
                                        │
                          AccessibilityService + ForegroundService
                                        │
                                Android OS
                                  
Flutter → HTTPS → Cloudflare Worker (product) → D1/KV ← Cloudflare Pages admin
```

- **Offline-first**: backend outage never releases an active session.
- **Anti-bypass**: enforcement is native + persisted + recoverable. See the
  full bypass-attempt matrix in `docs/SECURITY_MODEL.md`.
- **Honest limits**: we claim *maximum practical enforcement*, never
  "unbypassable" — power-off and some system actions belong to Android.

## Documentation map

- `docs/ARCHITECTURE.md` — components, state machine, data ownership
- `docs/SECURITY_MODEL.md` — anti-bypass matrix, coin integrity, privacy,
  backend security, platform limits
- `mobile/README.md` — build/run, Play checklist, QA flows
- `worker/README.md` — deploy, secrets, seeds, security notes
- `admin/README.md` — pages, RBAC, mock mode, Cloudflare Pages deploy

## Version

v2.5.5 (r9.5) — see `docs/R9.5-CHANGES.md`, `docs/R9.4-CHANGES.md`, `docs/R9.3-CHANGES.md`, `docs/R9.2-CHANGES.md` (fixes) and
`docs/SOCIAL-SENTRY-PARITY-AUDIT.md` (40-mechanism audit); earlier: `docs/R9.1-CHANGES.md`.
Original baseline: v1.0.0 (PRD v2.0 / TRD / UI-UX / Backend+Admin documents).
