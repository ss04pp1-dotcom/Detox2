# MAXLEVEL DETOX — Admin Panel Audit Fixes (Task 7-b)

Applier: admin-fixer. Date: 2026-02-18.
Scope: `work/maxlevel-detox/admin/` only (src/*). No files under `mobile/` or `worker/` were touched.
Source of fixes: `work/audit/report-admin.md` (all 5 CRITICAL, all 11 MAJOR, and the MINOR items listed in the task brief).

Verification: `npm ci` (177 packages) → `npm run build` (`tsc && vite build`) **passes with zero errors** (exit 0). `npx tsc --noEmit` → exit 0. Mock transport smoke-tested via node (see "Runtime verification" at the end).

---

## CRITICAL fixes

### [C1] `src/lib/hooks.ts` — usePaginatedQuery stale-filter fetch
- Reset effect (was lines 85-88) now also clears stale rows and cursor: `setRows([]); setNextCursor(null);` in addition to `setCursorStack([undefined]); setPage(0);`.
- Fetch effect deps changed from `[cursor, page, tick]` to **`[queryKey, cursor, page, tick]`** so changing a filter while on page 0 (the default state) actually refetches with the new fetcher. The existing `cancelled` cleanup discards any transient race when queryKey and cursor/page change together.
- Effect: status/severity filters on Subscriptions / Security Events / Support / Payments now apply immediately; search no longer needs Enter; "Next" no longer paginates a new query at the old cursor offset.

### [C2] `src/api/client.ts` + `src/api/types.ts` + `src/api/mock.ts` — audit/security wire keys
- `client.ts listAuditLogs`: reads `res.auditLogs ?? []` (was `res.logs`) → no more `setRows(undefined)` white-screen crash against the worker.
- `client.ts listSecurityEvents`: reads `res.securityEvents ?? []` (was `res.events`).
- `types.ts AuditWireResponse`: `{ logs: AuditLog[] }` → `{ auditLogs: AuditLog[] }`.
- `types.ts SecurityEventsWireResponse`: `{ events: SecurityEvent[] }` → `{ securityEvents: SecurityEvent[] }`.
- **Mock kept in sync** (per the report's "keep mock in sync" note): `/admin/audit-logs` now returns `{ auditLogs, nextCursor }`, `/admin/security-events` returns `{ securityEvents, nextCursor }`.
- **Extra finding (fixed):** a runtime probe of the mock revealed that *every* mock paginated route returned the internal `{ list, nextCursor }` shape (`paginate()`'s raw result) while the client reads the frozen contract keys — so demo mode was broken/crashing on Users, Devices, Subscriptions and Tickets as well (the audit report's claim that "the mock returns {logs}/{events}" was inaccurate — it returned `{list}`). Fixed the mock to emit the frozen wire keys on those four routes too:
  - `/admin/users` → `{ users, nextCursor }`
  - `/admin/devices` → `{ devices, nextCursor }`
  - `/admin/subscriptions` → `{ subscriptions, nextCursor }`
  - `/admin/support/tickets` → `{ tickets, nextCursor }`
  (`/admin/payments/bkash` already emitted `{ payments, … }` explicitly.)

### [C3] `src/pages/RemoteConfig.tsx` — page lockup when no draft exists
- Init effect now falls back to the published config: `const source: ConfigDoc = data.draft ?? data.published;` before destructuring `_version` out — no more `TypeError: Cannot destructure property '_version' of null` after every publish / on fresh installs.
- `setDirty(false)` on init (clean) so the editor renders published values, is not stuck on the skeleton, and the first edit + "Save Draft" creates the new draft (per task instruction; the worker re-creates a DRAFT row on PUT).
- Simplified the destructure cast (`source as ConfigDoc & { _version?: number }` → plain destructure) and `_versionOf()` (no cast) since `ConfigDoc` now declares `_version` (see m1).

### [C4] `src/api/types.ts` + `src/components/ui.tsx` — health vocabulary
- `HealthStatus` widened: `'healthy' | 'degraded' | 'down'` → **`'healthy' | 'operational' | 'degraded' | 'down' | 'unseeded'`**.
- `HealthBadge` (ui.tsx): `'healthy' | 'operational'` → success "Healthy"; `'degraded'` → warning "Degraded"; `'unseeded'` → warning "Unseeded"; anything else → danger "Down". Operational services no longer render as red "Down" on Dashboard / Live Operations / System.

### [C5] `src/pages/Analytics.tsx` + `src/api/types.ts` — analytics shape mismatch
- `AnalyticsResponse.retention` typed as `RetentionCohort[] | RetentionSummary` with new `RetentionSummary { weeklyActiveDevices: number }` (the worker's actual shape).
- Product Analytics renders `RetentionTable` **only when `Array.isArray(retention)`**; otherwise renders a "Weekly active devices: N" stat (`retention?.weeklyActiveDevices ?? 0`) — no more TypeError → whole-app unmount.
- `RevenueSummary` fields made optional (`mrrEstimateUsd?`, `estMonthlyUsd?` new alias, `premiumGrowth?`, `byPlan?`) to model the worker's smaller shape.
- Revenue page: all accesses via optional chaining (`data?.revenue?.byPlan?.find(...)`, `data?.revenue?.premiumGrowth ?? []`); MRR card shows `mrrEstimateUsd ?? estMonthlyUsd` and `'—'` when neither is present (was misleading `$0`).

---

## MAJOR fixes

### [M1] `src/pages/FeatureFlags.tsx` — confirm no longer flips `enabled`
- Pending state is now `{ flag: FeatureFlag; patch: FlagPatch } | null` — the patch contains **only the field(s) being changed**:
  - toggle → `patch: { enabled: !flag.enabled }`
  - rollout step → `patch: { rolloutPercentage: step }`
  - min-version blur → `patch: { minimumVersion: value }`
- `apply(patch)` sends exactly that patch (`api.updateFlag(pending.flag.key, patch)`) — a rollout change no longer silently disables the flag.
- Modal text reflects the intent via a new `describePatch()` helper ("Set ON/OFF?", "Set rollout to 25%?", "Set minimum version to 1.2.0?").
- Imported `FlagPatch` from `../api/types`.

### [M2] `src/pages/Announcements.tsx` — timezone loss on start/end
- Imported `fromLocalInputValue` from `../lib/format`.
- `submit()` builds a `payload` with `startAt: fromLocalInputValue(form.startAt) ?? form.startAt` and `endAt: form.endAt === null ? null : fromLocalInputValue(form.endAt) ?? form.endAt`, used for **both** create and update. Schedules no longer shift by the admin's UTC offset (worker TZ=UTC).

### [M3/M4] `src/pages/Announcements.tsx` + `src/api/types.ts` + `src/api/mock.ts` — status vocabulary & field names
- `AnnouncementStatus` widened to include **`'ACTIVE'` and `'ARCHIVED'`** (worker vocabulary) alongside DRAFT/PUBLISHED/SCHEDULED/EXPIRED.
- Archive button now sends `{ status: 'ARCHIVED' }` (was `'EXPIRED'` → 400 against the worker).
- `Announcement` type gains optional `startTime?` / `endTime?` (worker serializer names); new `annStart()` / `annEnd()` helpers read `a.startTime ?? a.startAt` / `a.endTime ?? a.endAt` for the list dates, the edit form prefill, and rendering.
- Status badge treats `'PUBLISHED'` **or `'ACTIVE'`** as the green/live state; Archive button hidden for both EXPIRED and ARCHIVED.
- Mock PATCH `/admin/announcements` now accepts `ARCHIVED` and `ACTIVE` statuses (validation list widened) so demo Archive works.
- (Worker-side endAt-optional is handled by the separate worker agent — out of my scope.)

### [M5] `src/pages/AppVersions.tsx` — dual GET shape + effect init
- `policy` is derived with `useMemo`: `Array.isArray((data as AppVersionPolicy & { versions?: AppVersionPolicy[] }).versions) ? versions[0] ?? null : data` — handles both the SPA's flat policy object and the worker's `{ versions: [...] }` wrapper. No more `undefined` form init / permanently-disabled Save.
- Form initialization moved from render-time `setState` into a **`useEffect` keyed on `[policy, initialized]`** (removed the render-phase state writes).
- "Current Policy" card renders from `policy` fields.

### [M6] `src/pages/Users.tsx` + `src/api/types.ts` + `src/components/ui.tsx` — users list data gaps
- `STATUS_FILTERS`: removed `PENDING`, added `BANNED` (worker's `USER_STATUSES` are ACTIVE/SUSPENDED/BANNED/DELETED — selecting Pending 400'd against the worker).
- `UserStatus` widened to `'ACTIVE' | 'SUSPENDED' | 'PENDING' | 'BANNED' | 'DELETED'`.
- `UserStatusBadge` (ui.tsx): renders **BANNED explicitly (danger)** and unknown/legacy statuses as **neutral** with the raw label (previously everything unknown fell through to a misleading "Pending" warning badge).
- Plan badge: new `PlanBadge` helper maps any truthy plan (`'MONTHLY' | 'YEARLY' | 'PREMIUM' | …`, `UserPlan` widened incl. `null`) → PREMIUM (accent), else FREE (neutral); used in both the list and the user-detail Account card.
- `User.coinBalance` made optional; list column renders `u.coinBalance == null ? '—' : formatCount(...)`, sortValue `?? 0`; detail "Coin Balance" and the Adjust-Coins modal show `?? '—'`.

### [M7] `src/pages/Devices.tsx` + `src/api/types.ts` — false "Low risk"
- `Device.riskLevel` made optional (`DeviceRisk | null | undefined`).
- `RiskBadge` renders a neutral **`—`** when `riskLevel` is `undefined`/`null` — no more false "Low risk" safety signal when the worker omits the field.

### [M8] `src/pages/Subscriptions.tsx` + `src/api/types.ts` — field names & $NaN MRR
- `Subscription` gains optional worker aliases `startDate?` / `lastVerified?`; `priceUsd` / `autoRenewing` made optional.
- New `startedOf()` (`s.startDate ?? s.startedAt`) and `verifiedOf()` (`s.lastVerified ?? s.lastVerifiedAt`) helpers used by the Started / Last Verified columns.
- Price column: `s.priceUsd != null ? formatUsd(s.priceUsd) : '—'`.
- "Page MRR" stat: computed only when at least one row on the page carries a price (`rows.some(s => s.priceUsd != null)`), else shows `'—'` — no more `$NaN`.
- "Auto-renewing" stat guarded with `s.autoRenewing === true`.
- Also applied the same alias in Users.tsx user-detail "Last Verified" (`lastVerifiedAt ?? lastVerified`).

### [M9] Display fallbacks across pages
- `src/pages/AuditLogs.tsx`: Admin column → `l.adminEmail ?? l.adminId` (`AuditLog.adminEmail` made optional).
- `src/pages/Dashboard.tsx` (RecentAuditTable, used on Dashboard + Live Ops): Admin column → `r.adminEmail ?? r.adminId`.
- `src/pages/SecurityEvents.tsx`: Event column → `e.eventType ?? e.type` (`SecurityEvent.eventType?` added; user column already had `e.userEmail ?? e.userId ?? '—'`).
- `src/pages/Users.tsx` SecurityRows: Event column → `e.eventType ?? e.type` (user-detail security table).
- `src/pages/Support.tsx`: Category column and modal title → `t.category ?? t.subject ?? '—'` via new `categoryOf()` helper (`Ticket.category?` / `subject` optional); User column → `t.userEmail ?? t.userId`.
- `src/pages/Users.tsx` TicketRows: Category → `t.category ?? t.subject ?? '—'`.
- `src/pages/System.tsx` AdminUsers: Name column → `a.name ?? '—'` (`AdminUser.name` made optional — the worker has no name column).

### [M10] `src/pages/Support.tsx` + `src/api/types.ts` — ticket responses model
- `Ticket.responses` made optional and a new optional `response?: string | null` field added (the worker's single overwritten reply).
- The ticket modal now renders previous replies: `active.responses ?? []` thread when present; otherwise, if `active.response` is a non-empty string it is shown as the "Latest reply" block. Both models normalized.

### [M11] `src/pages/Users.tsx` — global search sync from URL
- Added `useEffect(() => { const q = params.get('q'); if (q !== null) setSearch(q); }, [params])` so the Layout topbar search (`/users?q=…`) works while the Users page is already mounted.

---

## MINOR fixes

### [m1] `src/api/mock.ts` — config doc shape
- `toDoc()` now returns `{ ...stored.config, _version: stored.version }` — aligned to the worker's `_version` key, doc metadata (`version`/`status`/`createdAt`/`updatedAt`/`updatedBy`/`publishedAt`) dropped so demo mode shows real version numbers and the editable draft never carries doc metadata into the PUT payload.
- `types.ts ConfigDoc` redefined: extends AppConfig with optional `_version` + tolerated-legacy optional metadata fields (previously required `version/status/createdAt/…`).

### [m2] `src/pages/Login.tsx` — preserve the real ApiError
- Replaced the `error`/`requestId` string state with a single `apiError: ApiError | null` state holding the **original** `toApiError(err)` (code/message/requestId/status). Network/5xx failures no longer display as `UNAUTHORIZED`.

### [m3] `src/pages/Plans.tsx` — reject empty price
- Save validation now rejects an empty price input first: `edit.priceTaka.trim() === '' || …` — an empty field can no longer silently become a 0-BDT plan.

### [m4] `src/pages/RemoteConfig.tsx` — numeric clamp on blur, not per keystroke
- Added `rawInputs` state (per-field raw string override for numeric + multiplier inputs).
- `setField`/`setMultiplier` now store the raw (unclamped) number while typing; new `commitField`/`commitMultiplier` clamp/round **on blur** and clear the raw override. Inputs display `rawInputs[key] ?? draft[key]`. Typing "5" for cage duration no longer snaps to 300 mid-entry.

### [m5] `src/pages/AppVersions.tsx` — format updatedAt
- "Updated" row renders `formatDateTime(policy.updatedAt)` instead of the raw ISO string.

### [m6] `src/auth/AuthContext.tsx` — hasPermission guard
- `const perms: readonly Permission[] | undefined = ROLE_PERMISSIONS[admin.role]; return perms !== undefined && perms.includes(permission);` — a restored session with an unmapped/legacy role no longer crashes Layout on first render.

### [m7] `src/api/mock.ts` — audit date filters accept full ISO strings
- `from`/`to` parsing: `value.includes('T') ? new Date(value) : new Date(\`${value}T00:00:00\`)` (and `T23:59:59` for `to`) — the SPA sends ISO strings; date-only values still work.

### [m8] `src/api/mock.ts` — semver regex aligned
- App-versions POST validation (minimum/latest) now uses `/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/` (pre-release accepted, matching the SPA + worker). The same regex was aligned in the mock's flag `minimumVersion` check and `validateConfig`'s `minSupportedVersion` check for consistency (demo-only code).

### [m9] `src/lib/hooks.ts` + `src/pages/Users.tsx` — debounced search
- New `useDebounced<T>(value, delayMs = 300)` hook in lib/hooks.ts.
- Users page computes `debouncedSearch = useDebounced(search, 300)` and uses it for both the `queryKey` and the fetcher's `q` param — with C1 fixed, typing no longer fires a request per keystroke.

### [m12/audit hardening] `src/main.tsx` — top-level ErrorBoundary
- New `ErrorBoundary` class component (getDerivedStateFromError + componentDidCatch logging) wrapping the entire provider tree inside `<StrictMode>`. Render-time TypeErrors now show a "Something went wrong" fallback with a Reload button instead of a blank page.

### [m13-lite]
- No `rid()`/`requestIdOf` consolidation refactor performed (explicitly out of scope per task). Verified zero tsc errors with `noUnusedLocals`/`noUnusedParameters` on.

---

## Other adjustments required by the type changes (all in admin/src)
- `src/api/mock.ts`: audit adminId filter uses `(l.adminEmail ?? '').toLowerCase()`; coins-adjust route uses `user.coinBalance ?? 0` (field now optional); ticket PATCH appends via `ticket.responses = [...(ticket.responses ?? []), …]` (field now optional).
- `src/api/types.ts`: `AdminUser.name` optional (worker has no name column); `UserPlan` widened; `Ticket.userEmail/description` optional.

## Intentionally skipped (with reason)
- **m10 (Payments summary deps/memoization)** — not in the applied-fix list for this task; left untouched.
- **m11 (_headers CSP connect-src)** — deployment-config decision (needs the real staging host decision), not a code fix; left untouched.
- **m12 (Modal focus trap)** — replaced by the mandated top-level ErrorBoundary item; full a11y focus trap not in the applied list.
- **m13 (full rid() refactor)** — explicitly instructed to skip ("m13-lite: do NOT do the full rid() refactor").
- **Worker-side portions** of C5/M3/M4/M6/M7/M8/M9/M10 (extending the worker to return the frozen richer shapes, endAt-optional, coinBalance, riskLevel, ticket threads, adminEmail joins) — assigned to the separate worker agent; my scope was SPA-side crash-proofing and normalization only.
- **Devices user column fallback (`d.userEmail`)** — the worker omits `userEmail` on devices, but this was not in the prescribed fix list (only riskLevel was); left as-is to stay surgical.

## Runtime verification
- `npm ci` → 177 packages installed.
- `npm run build` → `tsc` clean + `vite build` ✓ built in ~4s (exit 0). `npx tsc --noEmit` → exit 0.
- Mock transport probed via node (`--experimental-strip-types`): users/audit-logs/security-events/devices/tickets/subscriptions all return the frozen wire keys with non-empty rows; `GET /admin/config` returns `_version` (8/7) with no doc metadata; announcement create with ISO `startAt` + `endAt: null` succeeds and PATCH `{status:'ARCHIVED'}` works; analytics returns the retention cohort array + full revenue shape; audit `from` ISO-string filter now filters (10 rows for a 2-day window); app-versions POST accepts `1.2.0-beta.1`.
