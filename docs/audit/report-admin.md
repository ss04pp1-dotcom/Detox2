# MAXLEVEL DETOX — Admin Panel Audit Report (Task 3-d)

Auditor: admin-auditor (read-only). Date: 2026-02-18.
Scope: `work/maxlevel-detox/admin/` — every TS/TSX/config file read line-by-line, plus cross-reference against `worker/src` (routes/admin.ts, utils/response.ts, middleware/adminAuth.ts, middleware/validation.ts, services/serializers.ts, index.ts route table) to verify API-shape parity.

## Summary

| Metric | Value |
|---|---|
| Files audited (admin) | 38 (all 22 src TS/TSX + 8 build/config + README + _headers + index.html) |
| LOC covered (admin) | ~6,440 (src = 6,099; mock.ts 1,181; ui.tsx 874; RemoteConfig 563; Users 433 …) |
| Worker files cross-referenced | 6 (~2,300 LOC) |
| Findings | **CRITICAL: 5 · MAJOR: 10 · MINOR: 13** |
| Overall verdict | The SPA is internally clean (no XSS, disciplined error handling, tsc-clean) but **diverges badly from the real Worker's response shapes**. Mock mode masks all of it: ~9 of 16 pages are broken, crashing, or lying against the real backend. |

Severity legend: CRITICAL = crash / data-loss / core flow broken; MAJOR = feature broken or wrong data displayed; MINOR = UX/robustness/maintainability.

---

## Page / component map (all admin flows)

| Route | File | Flow | Permission (nav) | Notes |
|---|---|---|---|---|
| `/login` | pages/Login.tsx | email+password → `api.login` → token (memory+sessionStorage) + admin in sessionStorage → Navigate `from` | — | Error shown via ErrorNotice w/ requestId |
| `/` | pages/Dashboard.tsx | `GET /admin/overview` → 8 StatCards, health card (api/database/configService), recent audit table | VIEW_ANALYTICS | Health badge broken vs worker (C4) |
| `/live` | pages/Dashboard.tsx (LiveOperationsPage) | overview + 30s `setInterval(reload)` | VIEW_ANALYTICS | |
| `/users` | pages/Users.tsx (UsersPage) | search (`?q=` param initial only) + status filter + cursor pagination (limit 50) → row click → detail | VIEW_USERS | Filter stale-fetch bug (C1), worker shape gaps (M6) |
| `/users/:id` | pages/Users.tsx (UserDetailPage) | `GET /admin/users/:id` → account/subscription/devices/security/tickets; Suspend/Activate (ConfirmModal), Adjust Coins modal (integer, |n|≤10k, reason≥3) | VIEW_USERS / EDIT_USERS (buttons) | |
| `/devices` | pages/Devices.tsx | search + pagination, risk badge | VIEW_DEVICES | riskLevel absent on worker (M7) |
| `/subscriptions` | pages/Subscriptions.tsx | status filter + pagination, per-page stats | VIEW_USERS | field-name mismatches (M8) |
| `/plans` | pages/Plans.tsx | `GET /admin/plans` → edit modal (price BDT→minor, days, sort, toggles) → `PATCH /admin/plans/:id` | MANAGE_CONFIG | |
| `/payments` | pages/Payments.tsx | bKash queue (default filter IN_REVIEW), summary (separate `limit:1` call), Verify (ConfirmModal) / Reject (reason modal) | MANAGE_PAYMENTS | |
| `/config` | pages/RemoteConfig.tsx | `GET /admin/config` → draft editor (numeric bounds, multipliers, strings, toggles) → Save Draft (PUT) → Review diff → Publish (type "PUBLISH") → Rollback (type "ROLLBACK") | MANAGE_CONFIG | **Crashes when no draft exists (C3)** |
| `/flags` | pages/FeatureFlags.tsx | flag cards: toggle → ConfirmModal; rollout snap steps 0/1/5/10/25/50/100; min-version input (onBlur) → ConfirmModal | MANAGE_FLAGS | **confirm flips `enabled` (M1)** |
| `/app-versions` | pages/AppVersions.tsx | `GET /admin/app-versions` → policy card + edit form (semver-validated) → POST (type "PUBLISH") | MANAGE_CONFIG | **GET shape mismatch → page dead (M5)** |
| `/announcements` | pages/Announcements.tsx | list → create/edit modal (title/body/type/targetRule/start/end) → archive (status EXPIRED) | MANAGE_ANNOUNCEMENTS | **timezone loss (M2), create/archive broken vs worker (M3/M4)** |
| `/analytics`, `/analytics/enforcement`, `/analytics/revenue` | pages/Analytics.tsx | `GET /admin/analytics?range=7d/30d/90d`; recharts Line/Bar; retention table; by-plan table | VIEW_ANALYTICS | **shape mismatch → crashes (C5)** |
| `/support` | pages/Support.tsx | tickets table (status filter) → detail modal → status + optional reply → `PATCH /admin/support/tickets/:id` | MANAGE_SUPPORT | subject/userEmail/responses missing vs worker (M9/M10) |
| `/security` | pages/SecurityEvents.tsx | severity filter + pagination, metadata expand card | VIEW_AUDIT | **wire-key mismatch → crash (C2)** |
| `/audit` | pages/AuditLogs.tsx | adminId/action/from/to filters + pagination | VIEW_AUDIT | **wire-key mismatch → crash (C2)** |
| `/system` | pages/System.tsx (SystemPage) | `GET /admin/system/health` → worker/D1/KV probes | MANAGE_SYSTEM | health vocabulary mismatch (C4) |
| `/system/admins` | pages/System.tsx (AdminUsersPage) | list admins, create admin (email/name/password≥12/role) | MANAGE_SYSTEM | worker has no `name` column (M9); worker's role-change endpoint unused by UI |
| `/404` + `*` | App.tsx | redirect to /404 | — | |
| Shell | components/Layout.tsx | permission-filtered sidebar (7 groups), global search → `/users?q=`, mock badge, identity + logout | — | |
| Infra | api/client.ts (envelope unwrap, 401 handler), api/mock.ts (1181-line in-memory transport), auth/AuthContext.tsx (RBAC matrix + RequireAuth), lib/hooks.ts (useApiData/usePaginatedQuery), components/ui.tsx (Button/Card/Table/Modal/Toast…) | | | |

RBAC matrix in `AuthContext.tsx` (ROLE_PERMISSIONS) **exactly matches** the worker's `RBAC_MATRIX` (adminAuth.ts:52-86), and every nav permission matches the worker's `requireAdmin(...)` per route. Envelope `{success,data,requestId}` matches `ok()/fail()` in worker/src/utils/response.ts. Config numeric/multiplier/string bounds match worker `CONFIG_BOUNDS` exactly.

---

# CRITICAL findings

## [C1] src/lib/hooks.ts:85-117 — `usePaginatedQuery`: changing a filter while on page 0 never refetches → stale rows silently shown

**Evidence:**
```ts
// hooks.ts
const cursor = cursorStack[Math.min(page, cursorStack.length - 1)];   // line 90
useEffect(() => { setCursorStack([undefined]); setPage(0); }, [queryKey]);  // lines 85-88 (reset only)
useEffect(() => {                                                        // lines 92-117
  ...
  fetcherRef.current(cursor) ...
}, [cursor, page, tick]);                                                // line 117 — queryKey NOT a dep
```
When `queryKey` changes while the user is on page 0 with `cursorStack=[undefined]` (the default state — the overwhelmingly common case), the reset effect sets `cursorStack` to a new `[undefined]` and `page` to `0`, but the **values** of `cursor`, `page`, `tick` are unchanged, so the fetch effect is skipped. The new `fetcherRef.current` is never called. Consequences:
- Subscriptions, Security Events, Support, Payments: selecting a status/severity filter does **nothing** — the table keeps showing unfiltered rows with no loading state (there is no submit button on those pages).
- Users/Devices/AuditLogs: typing in search does nothing until Enter (form submit → `reload()` → `tick`).
- After a filter change, `nextCursor` is stale from the previous query; pressing Next fetches the new fetcher at the old cursor's offset (skipped/duplicated rows).
- Only changing filters *from page ≥ 2* accidentally works (page/cursor values change).

**Fix (exact):** include the query in the fetch deps and clear stale rows on reset:
```ts
useEffect(() => {
  setCursorStack([undefined]);
  setPage(0);
  setRows([]);
  setNextCursor(null);
}, [queryKey]);

useEffect(() => { ... }, [queryKey, cursor, page, tick]);  // add queryKey
```
(Guard: since `queryKey` and `cursor/page` change together, one fetch fires per filter change — the `cancelled` cleanup already discards races.)

## [C2] src/api/client.ts:288-297 — Wire-key mismatch vs worker: Audit Logs and Security Events pages **crash** (white screen) against the real API

**Evidence:**
```ts
// client.ts
listAuditLogs: async (params) => {
  const res = await get<AuditWireResponse>('/admin/audit-logs', params);
  return { items: res.logs, nextCursor: res.nextCursor };        // reads .logs
},
listSecurityEvents: async (params) => {
  const res = await get<SecurityEventsWireResponse>('/admin/security-events', params);
  return { items: res.events, nextCursor: res.nextCursor };      // reads .events
},
```
Worker actually returns (`worker/src/routes/admin.ts:955` and `:992`):
```ts
return ok(c, { auditLogs: page.map(...), nextCursor });       // NOT { logs }
return ok(c, { securityEvents: page.map(...), nextCursor });  // NOT { events }
```
So `res.logs` / `res.events` are `undefined` → `setRows(undefined)` → `Table` does `sortedRows.length` / `[...rows]` → TypeError during render → no ErrorBoundary exists → React unmounts the entire tree (blank page). The mock returns `{logs}`/`{events}` so demo mode hides this.

**Fix (exact):** in `client.ts` read the worker keys (and keep mock in sync):
```ts
const res = await get<{ auditLogs: AuditLog[]; nextCursor: string | null }>('/admin/audit-logs', params);
return { items: res.auditLogs, nextCursor: res.nextCursor };
...
const res = await get<{ securityEvents: SecurityEvent[]; nextCursor: string | null }>('/admin/security-events', params);
return { items: res.securityEvents, nextCursor: res.nextCursor };
```
and update `AuditWireResponse`/`SecurityEventsWireResponse` in `types.ts` (or make the mock emit the same keys). Also consider `items: res.auditLogs ?? []` as belt-and-braces, and add a top-level ErrorBoundary in `main.tsx`.

## [C3] src/pages/RemoteConfig.tsx:80-106 — Remote Config page dies when no draft exists (i.e. after every publish) → config management permanently stuck on skeleton

**Evidence:**
```ts
useEffect(() => {
  if (data !== null && draft === null) {
    const { _version, ...rest } = data.draft as ConfigDoc & { _version?: number };  // data.draft may be null!
```
`ConfigResponse.draft` is `ConfigDoc | null`. The worker returns `draft: null` whenever no DRAFT row exists (`adminGetConfig`, worker admin.ts:596-597) — which is the state **after every publish** (`adminPublishConfig` flips the draft row to PUBLISHED, worker admin.ts:666-673) and on any fresh install before the first draft. Destructuring `null` throws `TypeError: Cannot destructure property '_version' of null` inside the effect → `setDraft` never runs → `loading || draft === null` renders `<Skeleton className="h-96" />` forever. The key product-control page becomes unusable (until someone creates a draft via raw API). Mock hides it because the mock always seeds a draft (version 8).

**Fix (exact):**
```ts
useEffect(() => {
  if (data !== null && draft === null) {
    const source: AppConfig = data.draft ?? data.published;   // fall back to published when no draft
    const { _version, ...rest } = source as AppConfig & { _version?: number };
    void _version;
    const normalized: AppConfig = { ...rest, /* existing ?? defaults */ };
    setDraft(normalized);
    setDirty(data.draft === null); // optionally mark dirty so Save creates the new draft
  }
}, [data, draft]);
```

## [C4] src/api/types.ts:461 + src/components/ui.tsx:246-250 — Health-status vocabulary mismatch: every healthy service renders as **“Down”** (red) on Dashboard, Live Operations and System pages against the real worker

**Evidence:**
```ts
// types.ts:461
export type HealthStatus = 'healthy' | 'degraded' | 'down';
// ui.tsx:246-250
export function HealthBadge({ status }: { status: HealthStatus }): JSX.Element {
  if (status === 'healthy') return <Badge tone="success" ...>Healthy</Badge>;
  if (status === 'degraded') return <Badge tone="warning" ...>Degraded</Badge>;
  return <Badge tone="danger" ...>Down</Badge>;              // ← 'operational' lands here
}
```
Worker returns `'operational'` / `'unseeded'` (`worker/src/routes/admin.ts:250-256` overview; `:1141-1148` system/health: `worker: 'operational', database: 'operational'|'down', kv: 'operational'|'down'`). All operational services display as red “Down” — an operator-facing lie in a security console (mock returns `'healthy'`, so demo looks perfect).

**Fix (exact):** widen the union and map it:
```ts
export type HealthStatus = 'healthy' | 'operational' | 'degraded' | 'down' | 'unseeded';
// ui.tsx
if (status === 'healthy' || status === 'operational') return <Badge tone="success" ...>Healthy</Badge>;
if (status === 'degraded' || status === 'unseeded') return <Badge tone="warning" ...>{status === 'unseeded' ? 'Unseeded' : 'Degraded'}</Badge>;
```
(Or normalize in `client.ts` when unwrapping overview/health responses.)

## [C5] src/pages/Analytics.tsx:116,122-131,203-211 — Analytics response-shape mismatch: Product Analytics and Revenue Analytics **crash** (retention is an object, not an array; revenue lacks `mrrEstimateUsd`/`premiumGrowth`/`byPlan`)

**Evidence:** SPA types expect:
```ts
retention: RetentionCohort[];               // types.ts:334
revenue: RevenueSummary;                    // { mrrEstimateUsd, premiumUsers, premiumGrowth: DauPoint[], byPlan: PlanBreakdown[] }
```
Worker returns (`admin.ts:900-918`):
```ts
retention: { weeklyActiveDevices: retentionRow?.n ?? 0 },          // single object
revenue:  { premiumUsers: premiumRow?.n ?? 0, estMonthlyUsd: ... } // no mrrEstimateUsd/premiumGrowth/byPlan
```
- `Analytics.tsx:116` `<RetentionTable rows={data?.retention ?? []} />` → receives the object → `Table` does `[...rows]`/`rows.map` → **TypeError → whole app unmounts** (Product Analytics page).
- `Analytics.tsx:205-206` `data?.revenue.byPlan.find(...)` → `byPlan` undefined → **TypeError → crash** (Revenue page); `:203` `formatUsd(data?.revenue.mrrEstimateUsd ?? 0)` → `$0` (misleading); `:211` `premiumGrowth ?? []` → empty chart.
- Enforcement Analytics happens to survive (sessions/shorts/tempUnlocks keys match).

**Fix (exact):** either change the SPA to the worker shape (`retention.weeklyActiveDevices`, `revenue.estMonthlyUsd`) or (better) extend the worker to return the frozen shapes the SPA/mock implement (cohort array + full RevenueSummary). Minimum crash-proofing in the SPA:
```ts
<RetentionTable rows={Array.isArray(data?.retention) ? data.retention : []} />
{data?.revenue?.byPlan?.find(...) ?? 0}
```

---

# MAJOR findings

## [M1] src/pages/FeatureFlags.tsx:82,103,132-139 — Confirming a rollout % or minimum-version change silently FLIPS the flag’s enabled state

**Evidence:**
```tsx
onClick={() => setPending({ ...flag, rolloutPercentage: step })}          // line 82 (rollout button)
onBlur={... setPending({ ...flag, minimumVersion: e.target.value.trim() })} // line 103
...
onConfirm={() => {
  void apply({
    enabled: !pending.enabled,          // ← ALWAYS flips, even for rollout/minVersion-only changes
    rolloutPercentage: pending.rolloutPercentage,
    minimumVersion: pending.minimumVersion,
  });
}}
```
An operator who only wants 25% rollout on an enabled flag gets the flag **disabled** (and the modal even announces it: `Apply {pending.enabled ? 'OFF' : 'ON'}` — line 121). Applies to both mock and worker.

**Fix (exact):** track what changed:
```tsx
const [pending, setPending] = useState<{ flag: FeatureFlag; patch: FlagPatch } | null>(null);
// toggle: setPending({ flag, patch: { enabled: !flag.enabled } })
// rollout: setPending({ flag, patch: { rolloutPercentage: step } })
// minVersion: setPending({ flag, patch: { minimumVersion: value } })
onConfirm={() => pending && void apply(pending.patch)}
```

## [M2] src/pages/Announcements.tsx:46-53,81-99 — startAt/endAt sent as local `datetime-local` strings (no timezone) → schedules shift by the admin's UTC offset

**Evidence:** the form stores raw input values (`"2026-02-18T14:30"`) and submits them directly:
```ts
startAt: toLocalInputValue(new Date().toISOString()),   // line 51 — "YYYY-MM-DDTHH:mm" (local)
...
await api.createAnnouncement(form);                    // line 86 — sent verbatim
```
`lib/format.ts:67-72` provides `fromLocalInputValue()` (→ ISO UTC) precisely for this, but it is **never imported/used** in Announcements.tsx. On the Worker (V8, TZ=UTC) `Date.parse("2026-02-18T14:30")` = 14:30 UTC — a Dhaka (UTC+6) admin's 14:30 local announcement actually starts at 20:30 local. The mock parses in the browser's local TZ, hiding the bug.

**Fix (exact):** convert on submit:
```ts
await api.createAnnouncement({ ...form, startAt: fromLocalInputValue(form.startAt) ?? form.startAt, endAt: form.endAt === null ? null : fromLocalInputValue(form.endAt) });
```
(same for `updateAnnouncement`), import `fromLocalInputValue` from `'../lib/format'`.

## [M3] src/pages/Announcements.tsx:81-99 + worker admin.ts:792 — Creating an announcement without an end date always fails (400) against the real worker

**Evidence:** SPA sends `endAt: null` for the optional end (line 46-53, 211), but the worker requires it:
```ts
endAt: { type: 'string', required: true, maxLength: 40 },   // worker admin.ts:792
// validateFields: raw === null → "endAt is required" (validation.ts:83-86)
```
Mock accepts `null`, so demo works; prod create of an open-ended announcement returns `VALIDATION_FAILED: endAt is required` (toast only). **Fix:** worker should accept `endAt: null` (required:false + `endMs` check only when present) — or SPA must send `endAt = startAt + long horizon`, which is worse. Recommended server-side fix; client workaround none.

## [M4] worker serializers.ts:164-176 + admin.ts:844 vs pages/Announcements.tsx — Announcement status vocabulary & field-name mismatches: Archive is broken, list dates blank, edit form clears start

**Evidence:**
- Worker status set is `DRAFT|ACTIVE|ARCHIVED` (serializers ANN_STATUSES:53; patch validation admin.ts:844). SPA sends `{ status: 'EXPIRED' }` on Archive (Announcements.tsx:105) → 400 `status must be DRAFT, ACTIVE or ARCHIVED`. **Archive button broken in prod.**
- Worker returns `startTime`/`endTime` (serializers:172-173); SPA reads `a.startAt`/`a.endAt` (Announcements.tsx:153) → every list row shows `— → no end`.
- SPA badges check `a.status === 'PUBLISHED'` (line 149); worker returns `'ACTIVE'` → never green.
- Editing loads `toLocalInputValue(a.startAt)` → `''` (field missing) → `valid` (line 120) false → Save disabled.

**Fix:** align on one vocabulary. Client-side quick fix: map worker→SPA on read (`startAt = a.startTime ?? a.startAt`), send `{status:'ARCHIVED'}` when archiving; long-term fix the worker serializer/contract to the SPA's frozen names (`startAt`, `endAt`, `PUBLISHED`, `EXPIRED`) or update SPA types wholesale.

## [M5] worker admin.ts:1084-1089 vs pages/AppVersions.tsx:27,37-43 — App Versions GET returns `{versions: [...]}` (array) but the SPA expects a single policy object → page non-functional against the worker

**Evidence:**
```ts
// worker admin.ts:1088
return ok(c, { versions: rows.results.map((r) => toApiAppVersion(r as never)) });
// SPA AppVersions.tsx:27
const { data, ... } = useApiData(() => api.getAppVersionPolicy(), []);   // expects AppVersionPolicy
// line 37-43: setMinimum(data.minimum) → undefined; line 45: semverOk → false → Save disabled forever
```
“Current Policy” card renders `undefined` values; the edit form initializes with `undefined` → `semverOk` false → **Save Version Policy permanently disabled**. Mock returns a single policy object, hiding it. **Fix:** either worker adds `GET /admin/app-versions` returning the latest policy row (`versions[0]`) as a flat object, or SPA reads `data.versions?.[0]`:
```ts
const policy = Array.isArray((data as any)?.versions) ? (data as any).versions[0] : data;
```

## [M6] worker serializers.ts:69-80, admin.ts:282,336 vs pages/Users.tsx — Users list data gaps vs worker: Coins column blank, plan badge wrong, “Pending” filter 400s, BANNED mislabeled

**Evidence:**
- Worker `User` has **no `coinBalance`** (serializers:69-80) → Users.tsx:62 `u.coinBalance` renders blank; UserDetail “Coin Balance” (Users.tsx:218) blank.
- Worker list adds `plan: r.plan` where `plan` is the subscription plan ('MONTHLY'/'YEARLY'/`null`, admin.ts:308,336), not `'FREE'|'PREMIUM'` → Users.tsx:59 badge shows raw “MONTHLY”/blank instead of PREMIUM.
- SPA status filter offers `PENDING` (Users.tsx:37); worker `USER_STATUSES = ['ACTIVE','SUSPENDED','BANNED','DELETED']` (admin.ts:72) → selecting Pending → 400 `Invalid status filter` (admin.ts:282-284) → ErrorNotice.
- Worker can return `status: 'BANNED'`; SPA `UserStatusBadge` (ui.tsx:231-235) falls through to the “Pending” (warning) badge.
**Fix:** worker should return `coinBalance` (SUM from coin_transactions) + `plan: 'FREE'|'PREMIUM'`; SPA should drop `PENDING` from filters (or worker accepts it) and render `BANNED` explicitly.

## [M7] worker serializers.ts:82-97 vs pages/Devices.tsx:11-15 — Worker Device has no `riskLevel` → every device renders “Low risk” (false safety signal)

**Evidence:** `toApiDevice` returns `{id, userId, platform, manufacturer, model, androidVersion, appVersion, registeredAt, lastSeenAt, status, permissionSummary, activeSession}` — no `riskLevel`. `RiskBadge` (`risk === 'HIGH' ? … : 'MEDIUM' ? … : 'success'`) maps `undefined` → success “Low risk”. Mock supplies riskLevel, so demo lies in the other direction. **Fix:** compute risk server-side (root/integrity events) and return `riskLevel`, or hide the Risk column when the field is absent (`d.riskLevel === undefined ? null : <RiskBadge …/>`).

## [M8] worker serializers.ts:99-111 vs pages/Subscriptions.tsx:24,38-41 — Subscriptions field-name mismatches → blank dates, `$NaN` MRR

**Evidence:** Worker returns `{…, plan, status, startDate, expiryDate, lastVerified}` (+`userEmail` from the join, admin.ts:566-569); SPA reads `startedAt`, `lastVerifiedAt`, `priceUsd`, `autoRenewing` (Subscriptions.tsx:39-41,24). Result: Started/Last Verified columns show `—`, Price shows `$NaN`, “Page MRR”/“Auto-renewing” stats are `NaN`/0. **Fix:** rename in the SPA (`startDate`/`lastVerified`), drop or worker-populate `priceUsd`/`autoRenewing` (or compute from `subscription_plans.priceMinor`).

## [M9] worker serializers.ts:178-255 vs pages/AuditLogs.tsx:34, SecurityEvents.tsx:27-29, Support.tsx:83-84,143, System.tsx:141, Layout — Missing display fields across audit/security/tickets/admin-users

**Evidence (all vs real worker):**
- `AuditLog` has **no `adminEmail`** (serializers:178-189) → AuditLogs.tsx:34 and Dashboard RecentAuditTable (Dashboard.tsx:96) Admin column blank.
- `SecurityEvent` uses `eventType` (not `type`) and has **no `userEmail`** (serializers:191-201) → SecurityEvents.tsx:27 Event column blank; UserDetail SecurityRows type blank (Users.tsx:323).
- `Ticket` uses `category` (not `subject`), **no `userEmail`, no `responses`** (serializers:203-217) → Support.tsx:84 Category blank, User column blank; modal title/description rely on fields the worker doesn't send.
- `AdminUser` has **no `name`** (serializers:242-255; login response admin.ts:162-166 also lacks it) → System.tsx:141 Name column blank.
**Fix:** worker joins/serializers should emit these fields (they exist in the SPA's frozen types); at minimum the SPA should fall back (`l.adminEmail ?? l.adminId`).

## [M10] worker admin.ts:1070 vs pages/Support.tsx:134-165 — Ticket “responses” model mismatch: worker stores a single overwritten `response`; SPA implies a thread and never renders prior replies

**Evidence:** Worker patch does `sets.push('response = ?', 'responded_at = ?')` — one column, overwritten on each reply (admin.ts:1070). The SPA's `Ticket.responses: TicketResponseEntry[]` (types.ts:436) and the mock's seeded threads suggest a conversation, but the modal never displays `active.responses`, and against the worker the field doesn't exist — support staff cannot see previous replies, and each new reply replaces the old one. **Fix:** worker should keep a `ticket_responses` table (or SPA should display the single `response`); at minimum render `active.responses ?? []` in the modal.

## [M11] src/pages/Users.tsx:43 — Global search (Layout topbar) does nothing when the user is already on `/users`

**Evidence:**
```ts
const [params] = useSearchParams();
const [search, setSearch] = useState(params.get('q') ?? '');   // initial value only
```
`navigate('/users?q=…')` from Layout (Layout.tsx:120) while UsersPage is mounted does not remount the route → `search` state (and `queryKey`) never updates. **Fix:** sync from params:
```ts
useEffect(() => { const q = params.get('q'); if (q !== null) setSearch(q); }, [params]);
```
or key the route element: `<Route path="users" element={<UsersPage key={location.search} … />}>`.

---

# MINOR findings

## [m1] src/api/mock.ts:274-276 vs worker admin.ts:597-598 — Mock config docs use `version`/`status` while the worker uses `_version` → mock mode shows “Editing draft ?” / “Version ?” and the mock draft state carries doc metadata
`toDoc()` returns `{...config, version, status, createdAt, …}`; SPA `_versionOf()` (RemoteConfig.tsx:552-555) reads `_version` → `'?'` in demo mode, and `rest` (line 82) keeps `version/status/…` in draft state (mock tolerates; worker's strict `validateConfig` would 400 — only reachable in mock). **Fix:** make the mock return `{...config, _version: stored.version}` and drop the doc fields.

## [m2] src/pages/Login.tsx:58-64 — Login errors are always labeled `UNAUTHORIZED` even for network/5xx failures
`new ApiError('UNAUTHORIZED', error, requestId ?? 'n/a', 401)` — a `SERVICE_UNAVAILABLE` network error displays code `UNAUTHORIZED`. **Fix:** keep the original `ApiError` (`error={apiErr}`).

## [m3] src/pages/Plans.tsx:70-75,90 — Empty price input silently becomes a 0-BDT plan
`Number('') === 0` passes `priceTaka < 0` guard → `priceMinor: 0` saved. **Fix:** `if (edit.priceTaka.trim() === '' || …)` reject empty.

## [m4] src/pages/RemoteConfig.tsx:110-117 — Numeric fields clamp on every keystroke
Typing a new value snaps mid-entry (e.g. cage duration min 300: typing “5” immediately becomes 300). **Fix:** keep raw string state per field, clamp/round on blur or save.

## [m5] src/pages/AppVersions.tsx:37-43,83 — `setState` during render for form init (works but fragile under refactor); `data.updatedAt` rendered as raw ISO string instead of `formatDateTime`
**Fix:** move initialization into an effect keyed on `data`, format the timestamp.

## [m6] src/auth/AuthContext.tsx:103 — Restored session with an unknown role crashes `hasPermission`
`ROLE_PERMISSIONS[admin.role].includes(...)` — a tampered/legacy `sessionStorage.mld_admin_session` with an unmapped role throws (Layout crashes on first render). **Fix:** `const perms = ROLE_PERMISSIONS[admin.role]; return perms !== undefined && perms.includes(permission);` and validate the parsed role on restore.

## [m7] src/pages/AuditLogs.tsx:24-25 vs mock.ts:959-963 — Date filters send full ISO strings; the mock expects date-only and silently ignores them
Worker parses ISO fine (`Date.parse`), mock builds `` `${from}T00:00:00` `` from an ISO string → Invalid Date → filter skipped. Demo-only bug; **fix the mock** to `new Date(from)` when the value contains 'T'.

## [m8] src/api/mock.ts:1009-1011 vs worker admin.ts:1101 — Semver regex mismatch: mock rejects pre-release (`1.2.0-beta`), worker + SPA accept it
Demo shows VALIDATION_FAILED for values prod accepts. **Fix:** align mock regex to `/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/`.

## [m9] src/pages/Users.tsx:46-50 — No debounce on search input (with C1 fixed, every keystroke would fire a request)
**Fix:** debounce `queryKey` (300ms) or keep submit-only semantics deliberately.

## [m10] src/pages/Payments.tsx:55-63 — Summary fetched via a second `listBkashPayments({limit:1})` call re-run on every status change (deps `[status]`), and `reload` is recreated every render (deps are fresh hook-result objects)
**Fix:** drop `status` from summary deps (`[]`), memoize `reload` on `query.reload`/`summaryQuery.reload`.

## [m11] admin/public/_headers:6 — CSP `connect-src 'self' https://api.maxleveldetox.com` blocks any other API base (e.g. staging `mld-api-staging`) if `VITE_API_URL` points there
**Fix:** keep in sync with deployment targets or relax consciously.

## [m12] src/components/ui.tsx:415-453 — Modal has no focus trap / initial focus; Escape works
Keyboard users can tab behind the overlay. **Fix:** focus the dialog on open and trap Tab.

## [m13] Copy-pasted `rid()`/`requestIdOf()` helpers in 7 pages (Users.tsx:293, FeatureFlags.tsx:146, AppVersions.tsx:144, Announcements.tsx:231, Support.tsx:170, System.tsx:208, RemoteConfig.tsx:557) + dead `runWithToast` (ui.tsx:860)
**Fix:** export one `requestIdOf` from `api/error.ts` and use `runWithToast` (or delete it).

---

# Things checked and found CORRECT (positives)

- **XSS:** zero `dangerouslySetInnerHTML`/`innerHTML`/`eval`; all server strings rendered as React children (auto-escaped); metadata shown via `JSON.stringify` in `<pre>`. Clean.
- **Token storage:** memory + `sessionStorage` only (`client.ts:80-93`), never `localStorage`; cleared on 401; README documents the tradeoff; CSP limits script sources.
- **API URL injection:** every dynamic path segment passes through `encodeURIComponent` (client.ts:211,214,220,263,279,306,326,341,348); query built via `URLSearchParams`. Clean.
- **Envelope parity:** `{success, data, requestId}` matches worker `ok()/fail()` exactly; `ApiError` carries code/message/requestId/status; `toApiError` normalizes.
- **RBAC parity:** SPA `ROLE_PERMISSIONS` matrix is byte-identical to the worker's `RBAC_MATRIX`; every sidebar permission matches the worker's `requireAdmin(...)` per route (index.ts:170-216). Client-side gating is UX-only as documented — server enforces (adminAuth re-reads role/status from D1 per request).
- **Config bounds parity:** `CONFIG_NUMERIC_BOUNDS`/`CONFIG_MULTIPLIER_BOUNDS`/`CONFIG_STRING_BOUNDS` match worker `CONFIG_BOUNDS`/`CONFIG_DOUBLE_BOUNDS`/`CONFIG_STRING_BOUNDS` value-for-value (validation.ts:172-200).
- **Race/unmount safety:** `useApiData`/`usePaginatedQuery` use `cancelled` flags — no setState-after-unmount, stale responses discarded; `LiveOperations` interval cleaned up.
- **Async error handling:** every mutation is try/catch → toast with `requestId`; login failures surface requestId; no unhandled rejections found.
- **tsc build:** strict + noUnusedLocals/Parameters pass by inspection (no unused imports/locals found; `React.ReactNode` UMD type refs are legal); `tsconfig.node.json` covers vite.config.ts; vite proxy `/api → :8787` matches README.
- **Forms:** coin adjust (integer, ≠0, ≤10k, reason ≥3), plans (numeric ranges), app-versions (semver + type-to-confirm), remote config (inline bounds + diff + PUBLISH/ROLLBACK confirm) all have real validation.

# Priority fix order

1. C2 (wire keys — audit/security crash), C3 (config page lockout), C5 (analytics crash) — one afternoon of client+worker alignment.
2. C4 (health vocabulary), C1 (pagination filter bug) — small, high-visibility.
3. M1–M5 (flag flip, announcement create/archive/timezone, app-versions shape).
4. M6–M10 (field-name normalization pass across users/devices/subscriptions/tickets/audit).
5. Minors as convenient; add an app-level ErrorBoundary + normalize wire responses in `client.ts` as hardening.
