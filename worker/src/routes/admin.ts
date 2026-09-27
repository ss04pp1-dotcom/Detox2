/**
 * MAXLEVEL DETOX — admin routes (Cloudflare Worker).
 *
 * Implements every ADMIN ROUTE from the frozen API contract v1:
 *   POST  /api/v1/admin/auth/login          PBKDF2 login -> KV session
 *   GET   /api/v1/admin/overview            metrics + health + recent audit
 *   GET   /api/v1/admin/users               cursor pagination + search
 *   GET   /api/v1/admin/users/:id           detail (devices/subscription/events/tickets)
 *   PATCH /api/v1/admin/users/:id           status change (audited)
 *   POST  /api/v1/admin/users/:id/coins/adjust   ADMIN_ADJUSTMENT ledger row (audited)
 *   GET   /api/v1/admin/devices
 *   GET   /api/v1/admin/subscriptions
 *   GET   /api/v1/admin/config              draft + published + version history
 *   PUT   /api/v1/admin/config              save draft (validated + clamped)
 *   POST  /api/v1/admin/config/publish      confirm-gated publish (audited)
 *   POST  /api/v1/admin/config/rollback     re-publish old version as new (audited)
 *   GET   /api/v1/admin/flags
 *   PATCH /api/v1/admin/flags/:key          (audited)
 *   GET/POST/PATCH /api/v1/admin/announcements (audited)
 *   GET   /api/v1/admin/analytics?range=    aggregates from events table
 *   GET   /api/v1/admin/audit-logs          filters + cursor
 *   GET   /api/v1/admin/security-events     filters + cursor
 *   GET/PATCH /api/v1/admin/support/tickets
 *   GET/POST /api/v1/admin/app-versions     (audited)
 *   GET   /api/v1/admin/system/health
 *   GET/POST /api/v1/admin/admin-users      SUPER_ADMIN only (audited)
 *
 * RBAC: every route runs behind requireAdmin(<permission>) except login.
 * ALL D1 statements are parameterized (.bind) — zero SQL interpolation.
 */

import {
  AdminRole,
  BkashPaymentRow,
  Context,
  SubscriptionPlanRow,
  UserStatus,
} from '../types';
import { ok, fail } from '../utils/response';
import {
  hmacSha256Hex,
  pbkdf2Hash,
  randomToken,
  sha256Hex,
  verifyPassword,
} from '../utils/crypto';
import { isRecord, readJsonBody, validateFields } from '../middleware/validation';
import { bearerToken } from '../middleware/auth';
import { appendAudit, appendSecurityEvent } from '../services/audit';
import {
  toApiUser,
  toApiDevice,
  toApiSubscription,
  toApiFeatureFlag,
  toApiAnnouncement,
  toApiAuditLog,
  toApiSecurityEvent,
  toApiTicket,
  toApiAppVersion,
  toApiAdminUser,
  toApiPlan,
  toApiBkashPayment,
} from '../services/serializers';
import { validateConfig } from '../middleware/validation';
import {
  ADMIN_ROLES,
} from '../middleware/adminAuth';

const ADMIN_SESSION_TTL_S = 8 * 60 * 60; // 8 hours
const DEFAULT_PAGE_LIMIT = 50;
const MAX_PAGE_LIMIT = 100;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const USER_STATUSES: readonly UserStatus[] = ['ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED'];
const TICKET_STATUSES = ['OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'] as const;
const TICKET_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const;
const ANNOUNCEMENT_TYPES = ['INFO', 'UPDATE', 'WARNING', 'MAINTENANCE', 'PROMOTION'] as const;

/**
 * m8: fixed dummy PBKDF2 hash burned on unknown-email logins so the
 * response timing matches a real password check and admin accounts cannot
 * be enumerated via latency. v2.5.7 (W-4): regenerated in the 300k 3-part
 * format so the timing parity matches the new iteration count.
 */
const DUMMY_PASSWORD_HASH =
  '300000$7f126790a3e0d880dcc5b77f4a82219c$8b43df15e1a4188d3122820acd1f9e902e9e1f87b559262c57b7b190a9a8aeda';
/** v2.5.7 (W-4): per-account brute-force lockout — 5 fails -> 15 min. */
const MAX_FAILED_LOGINS = 5;
const LOCKOUT_MINUTES = 15;
/** m18: semver pattern shared by app-version creation and flag minimumVersion. */
const SEMVER_RE = /^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/;

// ---------------------------------------------------------------------------
// Cursor helpers (keyset pagination, opaque to the client)
// ---------------------------------------------------------------------------

function encodeCursor(createdAt: string, id: string): string {
  return btoa(`${createdAt}|${id}`);
}

function decodeCursor(cursor: string): { createdAt: string; id: string } | null {
  try {
    const raw = atob(cursor);
    const sep = raw.lastIndexOf('|');
    if (sep <= 0) return null;
    const createdAt = raw.slice(0, sep);
    const id = raw.slice(sep + 1);
    if (createdAt.length === 0 || id.length === 0) return null;
    return { createdAt, id };
  } catch {
    return null;
  }
}

function parseLimit(url: URL): number {
  const raw = Number(url.searchParams.get('limit') ?? String(DEFAULT_PAGE_LIMIT));
  if (!Number.isInteger(raw) || raw <= 0) return DEFAULT_PAGE_LIMIT;
  return Math.min(raw, MAX_PAGE_LIMIT);
}

// ---------------------------------------------------------------------------
// POST /admin/auth/login
// ---------------------------------------------------------------------------

export async function adminLogin(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    email: { type: 'string', required: true, minLength: 5, maxLength: 254 },
    password: { type: 'string', required: true, minLength: 8, maxLength: 128 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const email = (v.value.email as string).trim().toLowerCase();
  const password = v.value.password as string;
  if (!EMAIL_RE.test(email)) {
    return fail(c, 'VALIDATION_FAILED', 'Invalid email format', 400);
  }

  const row = await c.env.DB.prepare(
    'SELECT id, email, password_hash, role, status, failed_login_count, locked_until FROM admin_users WHERE email = ?'
  )
    .bind(email)
    .first<{
      id: string;
      email: string;
      password_hash: string;
      role: string;
      status: string;
      failed_login_count: number;
      locked_until: string | null;
    }>();

  // M7: the committed seed super-admin credential is dev-only. Refuse it
  // outright outside development so the public repo default can never take
  // over a real deployment (rotate it per worker/README.md §3).
  if (c.env.API_ENV !== 'development' && row?.id === 'adm_seed_superadmin') {
    await appendSecurityEvent(c.env, null, null, 'AUTH_FAILURE', 'HIGH', {
      scope: 'admin',
      email,
      reason: 'seed_admin_locked_out',
      requestId: c.requestId,
    });
    return fail(
      c,
      'FORBIDDEN',
      'Seed admin password must be rotated before use (see worker/README.md §3)',
      403
    );
  }

  // v2.5.7 (W-4): per-account lockout. The per-IP limiter cannot stop a
  // distributed slow guess across many IPs; the account-side counter does.
  if (row !== null && row.locked_until !== null && Date.parse(row.locked_until) > Date.now()) {
    const retryInMin = Math.max(1, Math.ceil((Date.parse(row.locked_until) - Date.now()) / 60_000));
    await appendSecurityEvent(c.env, null, null, 'AUTH_FAILURE', 'MEDIUM', {
      scope: 'admin',
      email,
      reason: 'account_locked',
      requestId: c.requestId,
    });
    return fail(
      c,
      'RATE_LIMITED',
      `Account locked after too many failed attempts — try again in ${retryInMin} minute(s)`,
      429,
      { 'Retry-After': String(retryInMin * 60) }
    );
  }

  // m8: timing parity — burn a full PBKDF2 verification against a fixed
  // dummy hash when the email is unknown, so response timing cannot be used
  // to enumerate admin accounts.
  const passwordOk = row !== null
    ? await verifyPassword(password, row.password_hash)
    : await verifyPassword(password, DUMMY_PASSWORD_HASH);
  // Constant-ish response regardless of which factor failed.
  if (row === null || !passwordOk || row.status !== 'ACTIVE') {
    // v2.5.7 (W-4): count the failure and lock the account at the threshold.
    if (row !== null) {
      const failedCount = (row.failed_login_count ?? 0) + 1;
      const locked = failedCount >= MAX_FAILED_LOGINS;
      await c.env.DB.prepare(
        `UPDATE admin_users
            SET failed_login_count = ?,
                locked_until = CASE WHEN ? THEN ? ELSE locked_until END
          WHERE id = ?`
      )
        .bind(
          failedCount,
          locked ? 1 : 0,
          new Date(Date.now() + LOCKOUT_MINUTES * 60_000).toISOString(),
          row.id
        )
        .run();
      if (locked) {
        await appendSecurityEvent(c.env, null, null, 'ADMIN_ACCOUNT_LOCKED', 'HIGH', {
          scope: 'admin',
          email,
          failedCount,
          requestId: c.requestId,
        });
      }
    }
    await appendSecurityEvent(c.env, null, null, 'AUTH_FAILURE', 'MEDIUM', {
      scope: 'admin',
      email,
      reason: row === null ? 'unknown_admin' : row.status !== 'ACTIVE' ? 'disabled' : 'bad_password',
      requestId: c.requestId,
    });
    await appendAudit(c.env, row?.id ?? 'unknown', 'ADMIN_LOGIN_FAILED', 'admin_session', email, 'FAILURE', c.requestId);
    return fail(c, 'UNAUTHORIZED', 'Invalid email or password', 401);
  }

  // v2.5.7 (W-4): successful login resets the failure counters.
  if ((row.failed_login_count ?? 0) !== 0 || row.locked_until !== null) {
    await c.env.DB.prepare(
      'UPDATE admin_users SET failed_login_count = 0, locked_until = NULL WHERE id = ?'
    )
      .bind(row.id)
      .run();
  }

  if (!c.env.ADMIN_SESSION_SECRET) {
    return fail(c, 'SERVER_ERROR', 'ADMIN_SESSION_SECRET is not configured', 500);
  }

  const role = (ADMIN_ROLES as readonly string[]).includes(row.role) ? (row.role as AdminRole) : 'READ_ONLY';
  const token = randomToken(32);
  const exp = Date.now() + ADMIN_SESSION_TTL_S * 1000;
  const sig = await hmacSha256Hex(`${row.id}|${role}|${exp}`, c.env.ADMIN_SESSION_SECRET);
  const tokenHash = await sha256Hex(token);
  await c.env.KV.put(
    `admin_sess_${tokenHash}`,
    JSON.stringify({ adminId: row.id, role, exp, sig }),
    { expirationTtl: ADMIN_SESSION_TTL_S }
  );

  await c.env.DB.prepare('UPDATE admin_users SET last_login_at = ? WHERE id = ?')
    .bind(new Date().toISOString(), row.id)
    .run();
  await appendAudit(c.env, row.id, 'ADMIN_LOGIN', 'admin_session', row.id, 'SUCCESS', c.requestId);

  return ok(c, {
    admin: { id: row.id, email: row.email, role },
    token,
    expiresIn: ADMIN_SESSION_TTL_S,
  });
}

// ---------------------------------------------------------------------------
// POST /admin/auth/logout — revoke the KV admin session (m22)
// ---------------------------------------------------------------------------

export async function adminLogout(c: Context): Promise<Response> {
  // requireAdminSession already validated the bearer token and injected
  // c.admin; the same token hash names the KV session key to delete.
  const token = bearerToken(c);
  if (token === null) return fail(c, 'UNAUTHORIZED', 'Missing bearer token', 401);
  const tokenHash = await sha256Hex(token);
  await c.env.KV.delete(`admin_sess_${tokenHash}`);
  await appendAudit(c.env, c.admin!.adminId, 'ADMIN_LOGOUT', 'admin_session', c.admin!.adminId, 'SUCCESS', c.requestId);
  return ok(c, { loggedOut: true });
}

// ---------------------------------------------------------------------------
// GET /admin/overview
// ---------------------------------------------------------------------------

interface CountRow {
  n: number;
}

export async function adminOverview(c: Context): Promise<Response> {
  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  const dayAgo = new Date(now - 24 * 3600_000).toISOString();
  const weekAgo = new Date(now - 7 * 24 * 3600_000).toISOString();
  const todayStart = new Date();
  todayStart.setUTCHours(0, 0, 0, 0);

  const db = c.env.DB;
  const [
    totalUsers,
    activeUsers7d,
    activeDevices,
    premiumUsers,
    newUsersToday,
    sessionsToday,
    events24h,
  ] = await Promise.all([
    db.prepare("SELECT COUNT(*) AS n FROM users WHERE status != 'DELETED'").first<CountRow>(),
    db.prepare('SELECT COUNT(*) AS n FROM users WHERE last_seen_at >= ?').bind(weekAgo).first<CountRow>(),
    db.prepare('SELECT COUNT(*) AS n FROM devices WHERE last_seen_at >= ?').bind(weekAgo).first<CountRow>(),
    // M6: an ACTIVE row past its expiry is not a paying subscriber.
    db.prepare(
      "SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'ACTIVE' AND (expiry_date IS NULL OR expiry_date > ?)"
    ).bind(nowIso).first<CountRow>(),
    db.prepare('SELECT COUNT(*) AS n FROM users WHERE created_at >= ?').bind(todayStart.toISOString()).first<CountRow>(),
    db.prepare("SELECT COUNT(*) AS n FROM events WHERE type = 'SESSION_STARTED' AND occurred_at >= ?")
      .bind(todayStart.toISOString()).first<CountRow>(),
    db.prepare('SELECT COUNT(*) AS n FROM events WHERE received_at >= ?').bind(dayAgo).first<CountRow>(),
  ]);

  // Request/error counters maintained by index.ts in KV (per UTC day).
  const dateKey = todayStart.toISOString().slice(0, 10);
  const reqCountStr = await c.env.KV.get(`req_count_${dateKey}_${c.env.API_ENV}`);
  const errCountStr = await c.env.KV.get(`err_count_${dateKey}_${c.env.API_ENV}`);
  const reqCount = Number(reqCountStr ?? '0');
  const errCount = Number(errCountStr ?? '0');
  const errorRate = reqCount > 0 ? Math.round((errCount / reqCount) * 10000) / 100 : 0;

  // Health probes.
  let database = 'operational';
  let d1LatencyMs = -1;
  try {
    const t0 = Date.now();
    await db.prepare('SELECT 1 AS one').first<CountRow>();
    d1LatencyMs = Date.now() - t0;
    if (d1LatencyMs > 1000) database = 'degraded';
  } catch {
    database = 'down';
  }
  let kv = 'operational';
  try {
    await c.env.KV.put('health_probe', String(Date.now()), { expirationTtl: 60 });
  } catch {
    kv = 'down';
  }
  const configRow = await db.prepare(
    "SELECT version FROM config_versions WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1"
  ).first<{ version: number }>();
  const configService = configRow !== null ? 'operational' : 'unseeded';

  const recentAuditRows = await db
    .prepare('SELECT * FROM audit_logs ORDER BY created_at DESC, id DESC LIMIT 8')
    .all<Record<string, unknown>>();
  const recentAudit = recentAuditRows.results.map((r) => toApiAuditLog(r as never));

  return ok(c, {
    metrics: {
      totalUsers: totalUsers?.n ?? 0,
      activeUsers7d: activeUsers7d?.n ?? 0,
      activeDevices: activeDevices?.n ?? 0,
      premiumUsers: premiumUsers?.n ?? 0,
      newUsersToday: newUsersToday?.n ?? 0,
      sessionsToday: sessionsToday?.n ?? 0,
      apiRequests24h: reqCount || (events24h?.n ?? 0),
      errorRate,
    },
    health: {
      api: 'operational',
      database,
      kv,
      d1LatencyMs,
      configService,
    },
    recentAudit,
  });
}

// ---------------------------------------------------------------------------
// GET /admin/users
// ---------------------------------------------------------------------------

interface UserListRow {
  id: string;
  email: string;
  display_name: string | null;
  status: string;
  created_at: string;
  last_seen_at: string | null;
  device_count: number;
  plan: string | null;
}

export async function adminListUsers(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const q = (c.url.searchParams.get('q') ?? '').trim().toLowerCase();
  const status = c.url.searchParams.get('status');
  const cursorRaw = c.url.searchParams.get('cursor');

  if (status !== null && !USER_STATUSES.includes(status as UserStatus)) {
    return fail(c, 'VALIDATION_FAILED', 'Invalid status filter', 400);
  }

  let where = 'u.status != ?';
  const params: unknown[] = ['DELETED'];
  if (status !== null) {
    where = 'u.status = ?';
    params[0] = status;
  }
  if (q.length > 0) {
    if (q.length > 100) return fail(c, 'VALIDATION_FAILED', 'Query too long', 400);
    where += ' AND LOWER(u.email) LIKE ? ESCAPE ?';
    params.push(`%${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`, '\\');
  }
  let orderBy = 'ORDER BY u.created_at DESC, u.id DESC';
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (u.created_at < ? OR (u.created_at = ? AND u.id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT u.id, u.email, u.display_name, u.status, u.created_at, u.last_seen_at,
            (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id) AS device_count,
            (SELECT s.plan FROM subscriptions s WHERE s.user_id = u.id AND s.status = 'ACTIVE' LIMIT 1) AS plan
     FROM users u
     WHERE ${where}
     ${orderBy}
     LIMIT ?`
  )
    .bind(...params, limit + 1)
    .all<UserListRow>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const nextCursor =
    hasMore && page.length > 0 ? encodeCursor(page[page.length - 1].created_at, page[page.length - 1].id) : null;

  return ok(c, {
    users: page.map((r) => ({
      ...toApiUser({
        id: r.id,
        email: r.email,
        display_name: r.display_name,
        status: r.status,
        created_at: r.created_at,
        updated_at: r.created_at,
        last_seen_at: r.last_seen_at,
        deletion_pending_at: null,
      }),
      deviceCount: r.device_count,
      plan: r.plan,
    })),
    nextCursor,
  });
}

// ---------------------------------------------------------------------------
// GET /admin/users/:id
// ---------------------------------------------------------------------------

export async function adminGetUser(c: Context): Promise<Response> {
  const userId = c.params.id;
  const row = await c.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(userId).first<Record<string, unknown>>();
  if (row === null) return fail(c, 'NOT_FOUND', 'User not found', 404);

  const [devices, subscription, securityEvents, tickets] = await Promise.all([
    c.env.DB.prepare('SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC').bind(userId).all<Record<string, unknown>>(),
    c.env.DB.prepare("SELECT * FROM subscriptions WHERE user_id = ? AND status = 'ACTIVE' LIMIT 1").bind(userId).first<Record<string, unknown>>(),
    c.env.DB.prepare('SELECT * FROM security_events WHERE user_id = ? ORDER BY created_at DESC LIMIT 10').bind(userId).all<Record<string, unknown>>(),
    c.env.DB.prepare('SELECT * FROM support_tickets WHERE user_id = ? ORDER BY created_at DESC LIMIT 10').bind(userId).all<Record<string, unknown>>(),
  ]);

  return ok(c, {
    user: toApiUser(row as never),
    devices: devices.results.map((d) => toApiDevice(d as never)),
    subscription: subscription === null ? null : toApiSubscription(subscription as never),
    securityEvents: securityEvents.results.map((e) => toApiSecurityEvent(e as never)),
    supportTickets: tickets.results.map((t) => toApiTicket(t as never)),
  });
}

// ---------------------------------------------------------------------------
// PATCH /admin/users/:id  { status }
// ---------------------------------------------------------------------------

export async function adminPatchUser(c: Context): Promise<Response> {
  const userId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    status: { type: 'string', required: true, maxLength: 20 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const status = v.value.status as UserStatus;
  if (!USER_STATUSES.includes(status)) {
    return fail(c, 'VALIDATION_FAILED', `status must be one of ${USER_STATUSES.join(', ')}`, 400);
  }
  if (status === 'DELETED') {
    // Hard-delete is never performed from the panel; users self-request deletion.
    return fail(c, 'INVALID_REQUEST', 'Use the deletion request workflow instead of DELETED', 400);
  }

  const existing = await c.env.DB.prepare('SELECT id, status FROM users WHERE id = ?').bind(userId).first<{ id: string; status: string }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'User not found', 404);

  await c.env.DB.prepare('UPDATE users SET status = ?, updated_at = ? WHERE id = ?')
    .bind(status, new Date().toISOString(), userId)
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'USER_STATUS_CHANGED', 'user', userId, `${existing.status} -> ${status}`, c.requestId);
  if (status === 'SUSPENDED' || status === 'BANNED') {
    await appendSecurityEvent(c.env, userId, null, 'ACCOUNT_SUSPENDED', 'MEDIUM', { by: c.admin!.adminId, requestId: c.requestId });
  }

  const row = await c.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(userId).first<Record<string, unknown>>();
  return ok(c, { user: toApiUser(row as never) });
}

// ---------------------------------------------------------------------------
// POST /admin/users/:id/coins/adjust  { amount, reason }
// ---------------------------------------------------------------------------

export async function adminAdjustCoins(c: Context): Promise<Response> {
  const userId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    amount: { type: 'number', required: true },
    reason: { type: 'string', required: true, minLength: 3, maxLength: 500 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const amount = v.value.amount as number;
  if (!Number.isInteger(amount) || amount === 0 || Math.abs(amount) > 10_000) {
    return fail(c, 'VALIDATION_FAILED', 'amount must be a non-zero integer with |amount| <= 10000', 400);
  }
  const reason = v.value.reason as string;

  const user = await c.env.DB.prepare('SELECT id, status FROM users WHERE id = ?').bind(userId).first<{ id: string; status: string }>();
  if (user === null) return fail(c, 'NOT_FOUND', 'User not found', 404);

  const balanceRow = await c.env.DB.prepare(
    'SELECT COALESCE(SUM(amount), 0) AS balance FROM coin_transactions WHERE user_id = ?'
  ).bind(userId).first<{ balance: number }>();
  const balance = balanceRow?.balance ?? 0;
  const balanceAfter = balance + amount;
  if (balanceAfter < 0) {
    return fail(c, 'INVALID_REQUEST', 'Adjustment would make the balance negative', 400);
  }

  const txId = `ctx_${randomToken(16)}`;
  await c.env.DB.prepare(
    `INSERT INTO coin_transactions (id, user_id, transaction_id, type, amount, balance_after, source, reference, created_at)
     VALUES (?, ?, ?, 'ADMIN_ADJUSTMENT', ?, ?, 'admin', ?, ?)`
  )
    .bind(txId, userId, `adj_${c.requestId}`, amount, balanceAfter, reason.slice(0, 500), new Date().toISOString())
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'COIN_ADJUSTMENT', 'user', userId, `amount=${amount} reason=${reason}`, c.requestId);

  return ok(c, { adjustment: { transactionId: txId, amount, balanceAfter } });
}

// ---------------------------------------------------------------------------
// GET /admin/devices
// ---------------------------------------------------------------------------

interface DeviceListRow {
  id: string;
  user_id: string;
  user_email: string | null;
  model: string;
  manufacturer: string;
  android_version: string;
  app_version: string;
  last_seen_at: string | null;
  status: string;
}

export async function adminListDevices(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const q = (c.url.searchParams.get('q') ?? '').trim().toLowerCase();
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (q.length > 0) {
    if (q.length > 100) return fail(c, 'VALIDATION_FAILED', 'Query too long', 400);
    where += ' AND (LOWER(d.model) LIKE ? ESCAPE ? OR LOWER(d.id) LIKE ? ESCAPE ? OR LOWER(u.email) LIKE ? ESCAPE ?)';
    const esc = `%${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`;
    params.push(esc, '\\', esc, '\\', esc, '\\');
  }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (d.registered_at < ? OR (d.registered_at = ? AND d.id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT d.id, d.user_id, u.email AS user_email, d.model, d.manufacturer,
            d.android_version, d.app_version, d.last_seen_at, d.status, d.registered_at
     FROM devices d LEFT JOIN users u ON u.id = d.user_id
     WHERE ${where}
     ORDER BY d.registered_at DESC, d.id DESC
     LIMIT ?`
  )
    .bind(...params, limit + 1)
    .all<DeviceListRow & { registered_at: string }>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const nextCursor =
    hasMore && page.length > 0 ? encodeCursor(page[page.length - 1].registered_at, page[page.length - 1].id) : null;

  return ok(c, {
    devices: page.map((r) => ({
      ...toApiDevice({
        id: r.id,
        user_id: r.user_id,
        platform: 'android',
        manufacturer: r.manufacturer,
        model: r.model,
        android_version: r.android_version,
        app_version: r.app_version,
        registered_at: r.registered_at,
        last_seen_at: r.last_seen_at,
        status: r.status,
        permission_summary: null,
        active_session_flag: 0,
      }),
      userEmail: r.user_email,
    })),
    nextCursor,
  });
}

// ---------------------------------------------------------------------------
// v2.5.7 (F-2): community moderation — hide/unhide a reported commitment.
// Reports surface as COMMUNITY_CONTENT_REPORTED security events (3+ flags);
// this endpoint performs the actual hide. Audited.
// ---------------------------------------------------------------------------

export async function adminPatchCommit(c: Context): Promise<Response> {
  const commitId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    hidden: { type: 'boolean', required: true },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const hidden = v.value.hidden === true;

  const claim = await c.env.DB.prepare(
    'UPDATE community_commits SET hidden = ? WHERE id = ?'
  )
    .bind(hidden ? 1 : 0, commitId)
    .run();
  if ((claim.meta.changes ?? 0) === 0) {
    return fail(c, 'NOT_FOUND', 'Commitment not found', 404);
  }

  await appendAudit(
    c.env,
    c.admin!.adminId,
    hidden ? 'COMMUNITY_COMMIT_HIDDEN' : 'COMMUNITY_COMMIT_UNHIDDEN',
    'community_commit',
    commitId,
    'moderation',
    c.requestId
  );

  const row = await c.env.DB.prepare('SELECT * FROM community_commits WHERE id = ?')
    .bind(commitId)
    .first<Record<string, unknown>>();
  return ok(c, {
    commit:
      row === null
        ? null
        : {
            id: (row as { id: string }).id,
            hidden: (row as { hidden: number }).hidden === 1,
            reportCount: (row as { report_count: number }).report_count,
          },
  });
}

// ---------------------------------------------------------------------------
// GET /admin/subscriptions
// ---------------------------------------------------------------------------

export async function adminListSubscriptions(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const status = c.url.searchParams.get('status');
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (status !== null && status.length > 0) {
    where += ' AND s.status = ?';
    params.push(status);
  }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (s.created_at < ? OR (s.created_at = ? AND s.id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT s.*, u.email AS user_email FROM subscriptions s
     LEFT JOIN users u ON u.id = s.user_id
     WHERE ${where}
     ORDER BY s.created_at DESC, s.id DESC
     LIMIT ?`
  )
    .bind(...params, limit + 1)
    .all<Record<string, unknown>>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const last = page[page.length - 1] as { created_at: string; id: string } | undefined;
  const nextCursor = hasMore && last ? encodeCursor(last.created_at, last.id) : null;

  const activeCount = await c.env.DB.prepare(
    "SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'ACTIVE'"
  ).first<CountRow>();

  return ok(c, {
    subscriptions: page.map((r) => ({
      ...toApiSubscription(r as never),
      userEmail: (r as { user_email?: string }).user_email ?? null,
    })),
    nextCursor,
    summary: { activeSubscriptions: activeCount?.n ?? 0 },
  });
}

// ---------------------------------------------------------------------------
// Config: GET / PUT (draft) / publish / rollback
// ---------------------------------------------------------------------------

interface ConfigVersionRowJson {
  version: number;
  config_json: string;
  status: string;
  created_by: string | null;
  created_at: string;
  published_at: string | null;
}

/**
 * m16: parse a stored config_json row; null (and a server-side log) when the
 * row is corrupt, so the admin config routes answer a clean 500 envelope
 * instead of letting JSON.parse throw into the catch-all handler.
 */
function parseStoredConfigJson(
  requestId: string,
  version: number,
  json: string
): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(json);
    return isRecord(parsed) ? parsed : null;
  } catch {
    console.error(requestId, 'config_versions row is not valid JSON, version', version);
    return null;
  }
}

export async function adminGetConfig(c: Context): Promise<Response> {
  const db = c.env.DB;
  const [draftRow, publishedRow, versionsRows] = await Promise.all([
    db.prepare("SELECT * FROM config_versions WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1").first<ConfigVersionRowJson>(),
    db.prepare("SELECT * FROM config_versions WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1").first<ConfigVersionRowJson>(),
    db.prepare('SELECT version, status, created_by, created_at, published_at FROM config_versions ORDER BY version DESC LIMIT 50').all<Omit<ConfigVersionRowJson, 'config_json'>>(),
  ]);

  // m16: a corrupt stored row must surface as a clean 500 envelope.
  let draft: Record<string, unknown> | null = null;
  if (draftRow !== null) {
    const parsed = parseStoredConfigJson(c.requestId, draftRow.version, draftRow.config_json);
    if (parsed === null) return fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500);
    draft = { ...parsed, _version: draftRow.version };
  }
  let published: Record<string, unknown> | null = null;
  if (publishedRow !== null) {
    const parsed = parseStoredConfigJson(c.requestId, publishedRow.version, publishedRow.config_json);
    if (parsed === null) return fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500);
    published = { ...parsed, _version: publishedRow.version };
  }

  return ok(c, {
    draft,
    published,
    versions: versionsRows.results.map((r) => ({
      version: r.version,
      status: r.status,
      createdBy: r.created_by,
      createdAt: r.created_at,
      publishedAt: r.published_at,
    })),
  });
}

export async function adminSaveConfigDraft(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  if (body === null || !('config' in body) || !isRecord(body.config)) {
    return fail(c, 'VALIDATION_FAILED', 'Body must contain a "config" object', 400);
  }
  const result = validateConfig(body.config);
  if (!result.ok) return fail(c, 'VALIDATION_FAILED', result.errors.join('; '), 400);

  const configJson = JSON.stringify(result.config);
  const now = new Date().toISOString();
  const existing = await c.env.DB.prepare(
    "SELECT version FROM config_versions WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1"
  ).first<{ version: number }>();

  let version: number;
  if (existing === null) {
    const res = await c.env.DB.prepare(
      `INSERT INTO config_versions (config_json, status, created_by, created_at)
       VALUES (?, 'DRAFT', ?, ?)`
    )
      .bind(configJson, c.admin!.adminId, now)
      .run();
    version = Number(res.meta.last_row_id ?? 0);
  } else {
    version = existing.version;
    await c.env.DB.prepare(
      "UPDATE config_versions SET config_json = ?, created_by = ?, created_at = ? WHERE version = ? AND status = 'DRAFT'"
    )
      .bind(configJson, c.admin!.adminId, now, version)
      .run();
  }

  await appendAudit(c.env, c.admin!.adminId, 'CONFIG_SAVED', 'config', String(version), result.clamped.join('; ') || 'ok', c.requestId);
  return ok(c, { draft: { ...result.config, _version: version }, clamped: result.clamped });
}

export async function adminPublishConfig(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, { confirm: { type: 'boolean', required: true } });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (v.value.confirm !== true) {
    return fail(c, 'INVALID_REQUEST', 'Publishing requires confirm: true', 400);
  }

  const draft = await c.env.DB.prepare(
    "SELECT * FROM config_versions WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1"
  ).first<ConfigVersionRowJson>();
  if (draft === null) return fail(c, 'NOT_FOUND', 'No draft configuration to publish', 404);

  // m16: guard the stored draft JSON before validating it.
  const draftJson = parseStoredConfigJson(c.requestId, draft.version, draft.config_json);
  if (draftJson === null) return fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500);
  const result = validateConfig(draftJson);
  if (!result.ok) return fail(c, 'VALIDATION_FAILED', `Draft is invalid: ${result.errors.join('; ')}`, 400);

  const now = new Date().toISOString();
  // Archive the currently published version, then publish the draft. Two
  // statements, no transaction guard needed beyond D1 serial behavior for
  // single-statement ops; a crash between them leaves an archived config with
  // no published one, which clients handle via "unseeded" health + defaults.
  await c.env.DB.prepare(
    "UPDATE config_versions SET status = 'ARCHIVED' WHERE status = 'PUBLISHED'"
  ).run();
  await c.env.DB.prepare(
    "UPDATE config_versions SET status = 'PUBLISHED', published_at = ? WHERE version = ? AND status = 'DRAFT'"
  )
    .bind(now, draft.version)
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'CONFIG_PUBLISHED', 'config', String(draft.version), 'ok', c.requestId);
  return ok(c, { published: { ...result.config, _version: draft.version } });
}

export async function adminRollbackConfig(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, { version: { type: 'number', required: true } });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const targetVersion = v.value.version as number;
  if (!Number.isInteger(targetVersion) || targetVersion <= 0) {
    return fail(c, 'VALIDATION_FAILED', 'version must be a positive integer', 400);
  }

  const target = await c.env.DB.prepare(
    'SELECT * FROM config_versions WHERE version = ?'
  ).bind(targetVersion).first<ConfigVersionRowJson>();
  if (target === null) return fail(c, 'NOT_FOUND', 'Config version not found', 404);

  // m16: guard the archived version's JSON before re-publishing it.
  const targetJson = parseStoredConfigJson(c.requestId, target.version, target.config_json);
  if (targetJson === null) return fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500);
  const result = validateConfig(targetJson);
  if (!result.ok) {
    return fail(c, 'SERVER_ERROR', `Archived version failed validation: ${result.errors.join('; ')}`, 500);
  }

  const now = new Date().toISOString();
  // Rollback NEVER destroys history: the old version's config is re-published
  // as a brand-new version row.
  await c.env.DB.prepare(
    "UPDATE config_versions SET status = 'ARCHIVED' WHERE status = 'PUBLISHED'"
  ).run();
  const res = await c.env.DB.prepare(
    `INSERT INTO config_versions (config_json, status, created_by, created_at, published_at)
     VALUES (?, 'PUBLISHED', ?, ?, ?)`
  )
    .bind(JSON.stringify(result.config), c.admin!.adminId, now, now)
    .run();
  const newVersion = Number(res.meta.last_row_id ?? 0);

  await appendAudit(c.env, c.admin!.adminId, 'CONFIG_ROLLED_BACK', 'config', String(newVersion), `restored from v${targetVersion}`, c.requestId);
  return ok(c, { published: { ...result.config, _version: newVersion }, restoredFrom: targetVersion });
}

// ---------------------------------------------------------------------------
// Feature flags
// ---------------------------------------------------------------------------

export async function adminListFlags(c: Context): Promise<Response> {
  const rows = await c.env.DB.prepare('SELECT * FROM feature_flags ORDER BY key').all<Record<string, unknown>>();
  return ok(c, { flags: rows.results.map((r) => toApiFeatureFlag(r as never)) });
}

export async function adminPatchFlag(c: Context): Promise<Response> {
  const key = c.params.key;
  const existing = await c.env.DB.prepare('SELECT key FROM feature_flags WHERE key = ?').bind(key).first<{ key: string }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'Feature flag not found', 404);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    enabled: { type: 'boolean', required: false },
    rolloutPercentage: { type: 'number', required: false },
    minimumVersion: { type: 'string', required: false, maxLength: 32 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (Object.keys(v.value).length === 0) {
    return fail(c, 'VALIDATION_FAILED', 'At least one of enabled, rolloutPercentage, minimumVersion is required', 400);
  }

  const sets: string[] = [];
  const params: unknown[] = [];
  if ('enabled' in v.value) {
    sets.push('enabled = ?');
    params.push(v.value.enabled === true ? 1 : 0);
  }
  if ('rolloutPercentage' in v.value) {
    const pct = v.value.rolloutPercentage as number;
    const ALLOWED = [0, 1, 5, 10, 25, 50, 100];
    if (!ALLOWED.includes(pct)) {
      return fail(c, 'VALIDATION_FAILED', `rolloutPercentage must be one of ${ALLOWED.join(', ')}`, 400);
    }
    sets.push('rollout_percentage = ?');
    params.push(pct);
  }
  if ('minimumVersion' in v.value) {
    // m18: reject non-semver strings before they poison the version gate.
    const minimumVersion = v.value.minimumVersion as string;
    if (!SEMVER_RE.test(minimumVersion)) {
      return fail(c, 'VALIDATION_FAILED', 'minimumVersion must be a semver string (e.g. "1.2.0")', 400);
    }
    sets.push('minimum_version = ?');
    params.push(minimumVersion);
  }
  sets.push('updated_by = ?', 'updated_at = ?');
  params.push(c.admin!.adminId, new Date().toISOString(), key);

  await c.env.DB.prepare(`UPDATE feature_flags SET ${sets.join(', ')} WHERE key = ?`).bind(...params).run();

  await appendAudit(c.env, c.admin!.adminId, 'FLAG_UPDATED', 'flag', key, JSON.stringify(v.value), c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM feature_flags WHERE key = ?').bind(key).first<Record<string, unknown>>();
  return ok(c, { flag: toApiFeatureFlag(row as never) });
}

// ---------------------------------------------------------------------------
// Announcements
// ---------------------------------------------------------------------------

export async function adminListAnnouncements(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const rows = await c.env.DB.prepare(
    'SELECT * FROM announcements ORDER BY created_at DESC LIMIT ?'
  ).bind(limit).all<Record<string, unknown>>();
  return ok(c, { announcements: rows.results.map((r) => toApiAnnouncement(r as never)) });
}

export async function adminCreateAnnouncement(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    title: { type: 'string', required: true, minLength: 3, maxLength: 200 },
    body: { type: 'string', required: true, minLength: 3, maxLength: 5000 },
    type: { type: 'string', required: true, maxLength: 20 },
    targetRule: { type: 'string', required: false, maxLength: 200 },
    startAt: { type: 'string', required: true, maxLength: 40 },
    // CONTRACT (admin audit): endAt is OPTIONAL — null/absent means an
    // open-ended announcement (the SPA sends null for open-ended).
    endAt: { type: 'string', required: false, maxLength: 40 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const type = v.value.type as string;
  if (!(ANNOUNCEMENT_TYPES as readonly string[]).includes(type)) {
    return fail(c, 'VALIDATION_FAILED', `type must be one of ${ANNOUNCEMENT_TYPES.join(', ')}`, 400);
  }
  const startAt = v.value.startAt as string;
  const endAt = v.value.endAt !== undefined ? (v.value.endAt as string) : null;
  const startMs = Date.parse(startAt);
  if (Number.isNaN(startMs)) {
    return fail(c, 'VALIDATION_FAILED', 'startAt must be a valid ISO 8601 date', 400);
  }
  if (endAt !== null) {
    const endMs = Date.parse(endAt);
    if (Number.isNaN(endMs) || endMs <= startMs) {
      return fail(c, 'VALIDATION_FAILED', 'endAt must be a valid ISO date greater than startAt', 400);
    }
  }

  const id = `ann_${randomToken(12)}`;
  await c.env.DB.prepare(
    `INSERT INTO announcements (id, title, body, type, status, target_rule, start_at, end_at, created_by, created_at)
     VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)`
  )
    .bind(id, v.value.title, v.value.body, type, (v.value.targetRule as string) ?? 'all', startAt, endAt, c.admin!.adminId, new Date().toISOString())
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'ANNOUNCEMENT_CREATED', 'announcement', id, type, c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM announcements WHERE id = ?').bind(id).first<Record<string, unknown>>();
  return ok(c, { announcement: toApiAnnouncement(row as never) });
}

export async function adminPatchAnnouncement(c: Context): Promise<Response> {
  const id = c.params.id;
  const existing = await c.env.DB.prepare(
    'SELECT id, status, start_at, end_at FROM announcements WHERE id = ?'
  ).bind(id).first<{ id: string; status: string; start_at: string | null; end_at: string | null }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'Announcement not found', 404);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    status: { type: 'string', required: false, maxLength: 20 },
    title: { type: 'string', required: false, minLength: 3, maxLength: 200 },
    body: { type: 'string', required: false, minLength: 3, maxLength: 5000 },
    startAt: { type: 'string', required: false, maxLength: 40 },
    // CONTRACT: endAt may also be patched to an explicit null (open-ended).
    endAt: { type: 'string', required: false, maxLength: 40 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (Object.keys(v.value).length === 0 && !(body !== null && 'endAt' in body)) {
    return fail(c, 'VALIDATION_FAILED', 'No fields to update', 400);
  }

  // m17 + CONTRACT: validate any patched date and cross-check the effective
  // window (patched value vs stored counterpart) BEFORE writing anything.
  const patchStart = 'startAt' in v.value ? (v.value.startAt as string) : null;
  const patchEnd = 'endAt' in v.value ? (v.value.endAt as string) : null;
  const clearEnd = body !== null && 'endAt' in body && body.endAt === null;
  const effectiveStart = patchStart !== null ? patchStart : existing.start_at;
  const effectiveEnd = clearEnd ? null : patchEnd !== null ? patchEnd : existing.end_at;
  if (patchStart !== null && Number.isNaN(Date.parse(patchStart))) {
    return fail(c, 'VALIDATION_FAILED', 'startAt must be a valid ISO 8601 date', 400);
  }
  if (patchEnd !== null && Number.isNaN(Date.parse(patchEnd))) {
    return fail(c, 'VALIDATION_FAILED', 'endAt must be a valid ISO 8601 date', 400);
  }
  if (
    effectiveEnd !== null &&
    effectiveStart !== null &&
    Date.parse(effectiveEnd) <= Date.parse(effectiveStart)
  ) {
    return fail(c, 'VALIDATION_FAILED', 'endAt must be greater than startAt', 400);
  }

  const sets: string[] = [];
  const params: unknown[] = [];
  if ('status' in v.value) {
    const status = v.value.status as string;
    if (!['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(status)) {
      return fail(c, 'VALIDATION_FAILED', 'status must be DRAFT, ACTIVE or ARCHIVED', 400);
    }
    sets.push('status = ?');
    params.push(status);
  }
  if ('title' in v.value) { sets.push('title = ?'); params.push(v.value.title); }
  if ('body' in v.value) { sets.push('body = ?'); params.push(v.value.body); }
  if (patchStart !== null) { sets.push('start_at = ?'); params.push(patchStart); }
  if (patchEnd !== null) {
    sets.push('end_at = ?');
    params.push(patchEnd);
  } else if (clearEnd) {
    // Explicit null clears the end date -> open-ended announcement.
    sets.push('end_at = ?');
    params.push(null);
  }
  params.push(id);

  await c.env.DB.prepare(`UPDATE announcements SET ${sets.join(', ')} WHERE id = ?`).bind(...params).run();
  await appendAudit(c.env, c.admin!.adminId, 'ANNOUNCEMENT_UPDATED', 'announcement', id, JSON.stringify(v.value), c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM announcements WHERE id = ?').bind(id).first<Record<string, unknown>>();
  return ok(c, { announcement: toApiAnnouncement(row as never) });
}

// ---------------------------------------------------------------------------
// GET /admin/analytics?range=7d|30d|90d
// ---------------------------------------------------------------------------

export async function adminAnalytics(c: Context): Promise<Response> {
  const range = c.url.searchParams.get('range') ?? '30d';
  const days = range === '7d' ? 7 : range === '90d' ? 90 : 30;
  const since = new Date(Date.now() - days * 24 * 3600_000).toISOString();
  const nowIso = new Date().toISOString();

  const db = c.env.DB;
  const [dauRows, sessionRows, shortsRows, tempUnlockRows, premiumRow, retentionRow] = await Promise.all([
    db.prepare(
      `SELECT DATE(received_at) AS date, COUNT(DISTINCT COALESCE(device_id, user_id)) AS value
       FROM events WHERE received_at >= ? GROUP BY DATE(received_at) ORDER BY date`
    ).bind(since).all<{ date: string; value: number }>(),
    db.prepare(
      `SELECT type, COUNT(*) AS n FROM events
       WHERE type IN ('SESSION_STARTED', 'SESSION_COMPLETED', 'SESSION_BAILED_OUT') AND received_at >= ?
       GROUP BY type`
    ).bind(since).all<{ type: string; n: number }>(),
    db.prepare(
      `SELECT type, COUNT(*) AS n FROM events
       WHERE type IN ('SHORTS_WARNING', 'CAGE_ACTIVATED') AND received_at >= ?
       GROUP BY type`
    ).bind(since).all<{ type: string; n: number }>(),
    db.prepare(
      "SELECT COUNT(*) AS n FROM events WHERE type = 'TEMP_UNLOCK_STARTED' AND received_at >= ?"
    ).bind(since).first<CountRow>(),
    // M6: an ACTIVE row past its expiry is not a paying subscriber.
    db.prepare(
      "SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'ACTIVE' AND (expiry_date IS NULL OR expiry_date > ?)"
    ).bind(nowIso).first<CountRow>(),
    db.prepare(
      `SELECT COUNT(DISTINCT user_id) AS n FROM devices WHERE last_seen_at >= ?`
    ).bind(new Date(Date.now() - 7 * 24 * 3600_000).toISOString()).first<CountRow>(),
  ]);

  const sessionMap = new Map(sessionRows.results.map((r) => [r.type, r.n] as const));
  const shortsMap = new Map(shortsRows.results.map((r) => [r.type, r.n] as const));

  // v2.5.7 (A-4): REAL revenue math. The old premiumUsers * 3.99 was a USD
  // fiction — the plan catalog prices in BDT paisa across 4 tiers. Sum the
  // monthly-equivalent of each active subscription's plan (normalized to a
  // 30-day month); trials contribute nothing.
  const revenueRow = await db.prepare(
    `SELECT COALESCE(SUM(
       CASE
         WHEN p.duration_days IS NULL OR p.duration_days < 30 THEN 0
         ELSE (CAST(s2.price_minor AS REAL) / p.duration_days) * 30
       END
     ), 0) AS monthly_bdt
     FROM subscriptions s2
     JOIN subscription_plans p ON p.product_id = s2.product_id
     WHERE s2.status = 'ACTIVE' AND (s2.expiry_date IS NULL OR s2.expiry_date > ?)`
  ).bind(nowIso).first<{ monthly_bdt: number }>();
  const estMonthlyBdt = Math.round(revenueRow?.monthly_bdt ?? 0);

  return ok(c, {
    range,
    dau: dauRows.results,
    sessions: {
      started: sessionMap.get('SESSION_STARTED') ?? 0,
      completed: sessionMap.get('SESSION_COMPLETED') ?? 0,
      bailed: sessionMap.get('SESSION_BAILED_OUT') ?? 0,
    },
    shorts: {
      warnings: shortsMap.get('SHORTS_WARNING') ?? 0,
      cages: shortsMap.get('CAGE_ACTIVATED') ?? 0,
    },
    tempUnlocks: tempUnlockRows?.n ?? 0,
    retention: { weeklyActiveDevices: retentionRow?.n ?? 0 },
    revenue: {
      premiumUsers: premiumRow?.n ?? 0,
      // v2.5.7 (A-4): BDT monthly recurring estimate from the plan catalog
      // (smallest unit: paisa -> taka). estMonthlyUsd kept only as a
      // deprecated alias so older SPA builds do not break.
      estMonthlyBdt,
      estMonthlyUsd: null,
    },
  });
}

// ---------------------------------------------------------------------------
// GET /admin/audit-logs
// ---------------------------------------------------------------------------

export async function adminAuditLogs(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const adminId = c.url.searchParams.get('adminId');
  const action = c.url.searchParams.get('action');
  const from = c.url.searchParams.get('from');
  const to = c.url.searchParams.get('to');
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (adminId !== null && adminId.length > 0) { where += ' AND admin_id = ?'; params.push(adminId); }
  if (action !== null && action.length > 0) { where += ' AND action = ?'; params.push(action); }
  if (from !== null && !Number.isNaN(Date.parse(from))) { where += ' AND created_at >= ?'; params.push(from); }
  if (to !== null && !Number.isNaN(Date.parse(to))) { where += ' AND created_at <= ?'; params.push(to); }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (created_at < ? OR (created_at = ? AND id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT * FROM audit_logs WHERE ${where} ORDER BY created_at DESC, id DESC LIMIT ?`
  ).bind(...params, limit + 1).all<Record<string, unknown> & { created_at: string; id: string }>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const nextCursor = hasMore && page.length > 0 ? encodeCursor(page[page.length - 1].created_at, page[page.length - 1].id) : null;

  return ok(c, { auditLogs: page.map((r) => toApiAuditLog(r as never)), nextCursor });
}

// ---------------------------------------------------------------------------
// GET /admin/security-events
// ---------------------------------------------------------------------------

export async function adminSecurityEvents(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const severity = c.url.searchParams.get('severity');
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (severity !== null && severity.length > 0) {
    if (!['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(severity)) {
      return fail(c, 'VALIDATION_FAILED', 'Invalid severity filter', 400);
    }
    where += ' AND severity = ?';
    params.push(severity);
  }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (created_at < ? OR (created_at = ? AND id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT * FROM security_events WHERE ${where} ORDER BY created_at DESC, id DESC LIMIT ?`
  ).bind(...params, limit + 1).all<Record<string, unknown> & { created_at: string; id: string }>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const nextCursor = hasMore && page.length > 0 ? encodeCursor(page[page.length - 1].created_at, page[page.length - 1].id) : null;

  return ok(c, { securityEvents: page.map((r) => toApiSecurityEvent(r as never)), nextCursor });
}

// ---------------------------------------------------------------------------
// Support tickets
// ---------------------------------------------------------------------------

export async function adminListTickets(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const status = c.url.searchParams.get('status');
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (status !== null && status.length > 0) {
    if (!(TICKET_STATUSES as readonly string[]).includes(status)) {
      return fail(c, 'VALIDATION_FAILED', 'Invalid status filter', 400);
    }
    where += ' AND status = ?';
    params.push(status);
  }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (created_at < ? OR (created_at = ? AND id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT t.*, u.email AS user_email FROM support_tickets t
     LEFT JOIN users u ON u.id = t.user_id
     WHERE ${where} ORDER BY t.created_at DESC, t.id DESC LIMIT ?`
  ).bind(...params, limit + 1).all<Record<string, unknown> & { created_at: string; id: string }>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const nextCursor = hasMore && page.length > 0 ? encodeCursor(page[page.length - 1].created_at, page[page.length - 1].id) : null;

  return ok(c, { tickets: page.map((r) => toApiTicket(r as never)), nextCursor });
}

export async function adminPatchTicket(c: Context): Promise<Response> {
  const id = c.params.id;
  const existing = await c.env.DB.prepare('SELECT id FROM support_tickets WHERE id = ?').bind(id).first<{ id: string }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'Ticket not found', 404);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    status: { type: 'string', required: false, maxLength: 20 },
    priority: { type: 'string', required: false, maxLength: 10 },
    assignedTo: { type: 'string', required: false, maxLength: 254 },
    response: { type: 'string', required: false, maxLength: 5000 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (Object.keys(v.value).length === 0) {
    return fail(c, 'VALIDATION_FAILED', 'No fields to update', 400);
  }

  const sets: string[] = [];
  const params: unknown[] = [];
  if ('status' in v.value) {
    const status = v.value.status as string;
    if (!(TICKET_STATUSES as readonly string[]).includes(status)) {
      return fail(c, 'VALIDATION_FAILED', `status must be one of ${TICKET_STATUSES.join(', ')}`, 400);
    }
    sets.push('status = ?');
    params.push(status);
  }
  if ('priority' in v.value) {
    const priority = v.value.priority as string;
    if (!(TICKET_PRIORITIES as readonly string[]).includes(priority)) {
      return fail(c, 'VALIDATION_FAILED', `priority must be one of ${TICKET_PRIORITIES.join(', ')}`, 400);
    }
    sets.push('priority = ?');
    params.push(priority);
  }
  if ('assignedTo' in v.value) { sets.push('assigned_to = ?'); params.push(v.value.assignedTo); }
  if ('response' in v.value) { sets.push('response = ?', 'responded_at = ?'); params.push(v.value.response, new Date().toISOString()); }
  sets.push('updated_at = ?');
  params.push(new Date().toISOString(), id);

  await c.env.DB.prepare(`UPDATE support_tickets SET ${sets.join(', ')} WHERE id = ?`).bind(...params).run();

  const row = await c.env.DB.prepare('SELECT * FROM support_tickets WHERE id = ?').bind(id).first<Record<string, unknown>>();
  return ok(c, { ticket: toApiTicket(row as never) });
}

// ---------------------------------------------------------------------------
// App versions
// ---------------------------------------------------------------------------

export async function adminGetAppVersions(c: Context): Promise<Response> {
  const rows = await c.env.DB.prepare(
    'SELECT * FROM app_versions ORDER BY created_at DESC LIMIT 20'
  ).all<Record<string, unknown>>();
  return ok(c, { versions: rows.results.map((r) => toApiAppVersion(r as never)) });
}

export async function adminPostAppVersion(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    minimum: { type: 'string', required: true, maxLength: 32 },
    latest: { type: 'string', required: true, maxLength: 32 },
    forceUpdate: { type: 'boolean', required: true },
    message: { type: 'string', required: false, maxLength: 500 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  if (!SEMVER_RE.test(v.value.minimum as string) || !SEMVER_RE.test(v.value.latest as string)) {
    return fail(c, 'VALIDATION_FAILED', 'minimum and latest must be semver strings', 400);
  }

  const id = `apv_${randomToken(10)}`;
  await c.env.DB.prepare(
    `INSERT INTO app_versions (id, minimum, latest, force_update, message, created_by, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  )
    .bind(id, v.value.minimum, v.value.latest, v.value.forceUpdate === true ? 1 : 0, (v.value.message as string) ?? null, c.admin!.adminId, new Date().toISOString())
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'APP_VERSION_UPDATED', 'app_version', id, `min=${v.value.minimum} latest=${v.value.latest} force=${v.value.forceUpdate}`, c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM app_versions WHERE id = ?').bind(id).first<Record<string, unknown>>();
  return ok(c, { version: toApiAppVersion(row as never) });
}

// ---------------------------------------------------------------------------
// GET /admin/system/health
// ---------------------------------------------------------------------------

export async function adminSystemHealth(c: Context): Promise<Response> {
  const t0 = Date.now();
  let d1Ok = true;
  try {
    await c.env.DB.prepare('SELECT 1 AS one').first<CountRow>();
  } catch {
    d1Ok = false;
  }
  const d1LatencyMs = Date.now() - t0;

  let kvOk = true;
  try {
    await c.env.KV.put('health_probe', String(Date.now()), { expirationTtl: 60 });
  } catch {
    kvOk = false;
  }

  return ok(c, {
    worker: 'operational',
    database: d1Ok ? 'operational' : 'down',
    d1LatencyMs,
    kv: kvOk ? 'operational' : 'down',
    environment: c.env.API_ENV,
    timestamp: new Date().toISOString(),
  });
}

// ---------------------------------------------------------------------------
// GET/POST /admin/admin-users  (MANAGE_SYSTEM — SUPER_ADMIN only)
// ---------------------------------------------------------------------------

export async function adminListAdminUsers(c: Context): Promise<Response> {
  const rows = await c.env.DB.prepare('SELECT * FROM admin_users ORDER BY created_at').all<Record<string, unknown>>();
  return ok(c, { admins: rows.results.map((r) => toApiAdminUser(r as never)) });
}

export async function adminCreateAdminUser(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    email: { type: 'string', required: true, minLength: 5, maxLength: 254 },
    password: { type: 'string', required: true, minLength: 12, maxLength: 128 },
    role: { type: 'string', required: true, maxLength: 20 },
    // v2.5.7 (A-1): the SPA always collected a Name — now it is persisted.
    name: { type: 'string', required: false, minLength: 2, maxLength: 64 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const email = (v.value.email as string).trim().toLowerCase();
  if (!EMAIL_RE.test(email)) return fail(c, 'VALIDATION_FAILED', 'Invalid email format', 400);
  const role = v.value.role as AdminRole;
  if (!(ADMIN_ROLES as readonly string[]).includes(role)) {
    return fail(c, 'VALIDATION_FAILED', `role must be one of ${ADMIN_ROLES.join(', ')}`, 400);
  }

  const existing = await c.env.DB.prepare('SELECT id FROM admin_users WHERE email = ?').bind(email).first<{ id: string }>();
  if (existing !== null) return fail(c, 'CONFLICT', 'An admin with this email already exists', 409);

  const id = `adm_${randomToken(12)}`;
  const passwordHash = await pbkdf2Hash(v.value.password as string);
  await c.env.DB.prepare(
    `INSERT INTO admin_users (id, email, password_hash, role, status, name, created_at, created_by)
     VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)`
  )
    .bind(
      id,
      email,
      passwordHash,
      role,
      (v.value.name as string | undefined)?.trim() || null,
      new Date().toISOString(),
      c.admin!.adminId
    )
    .run();

  await appendAudit(c.env, c.admin!.adminId, 'ADMIN_CREATED', 'admin_user', id, `role=${role}`, c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM admin_users WHERE id = ?').bind(id).first<Record<string, unknown>>();
  return ok(c, { admin: toApiAdminUser(row as never) });
}

export async function adminChangeRole(c: Context): Promise<Response> {
  const targetId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, { role: { type: 'string', required: true, maxLength: 20 } });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const role = v.value.role as AdminRole;
  if (!(ADMIN_ROLES as readonly string[]).includes(role)) {
    return fail(c, 'VALIDATION_FAILED', `role must be one of ${ADMIN_ROLES.join(', ')}`, 400);
  }

  const existing = await c.env.DB.prepare('SELECT id, role FROM admin_users WHERE id = ?').bind(targetId).first<{ id: string; role: string }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'Admin not found', 404);

  if (existing.id === c.admin!.adminId && role !== 'SUPER_ADMIN') {
    return fail(c, 'INVALID_REQUEST', 'You cannot demote your own SUPER_ADMIN account', 400);
  }

  await c.env.DB.prepare('UPDATE admin_users SET role = ? WHERE id = ?').bind(role, targetId).run();
  await appendAudit(c.env, c.admin!.adminId, 'ROLE_CHANGED', 'admin_user', targetId, `${existing.role} -> ${role}`, c.requestId);

  const row = await c.env.DB.prepare('SELECT * FROM admin_users WHERE id = ?').bind(targetId).first<Record<string, unknown>>();
  return ok(c, { admin: toApiAdminUser(row as never) });
}

// ===========================================================================
// v2.2 PHASE D — plans catalog + bKash payment review
// ===========================================================================

const BKASH_PAYMENT_STATUSES = ['PENDING', 'IN_REVIEW', 'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELED'] as const;

interface CountRow {
  n: number;
}

// ---------------------------------------------------------------------------
// GET /admin/plans — full catalog (including inactive)
// ---------------------------------------------------------------------------

export async function adminListPlans(c: Context): Promise<Response> {
  const rows = await c.env.DB.prepare(
    'SELECT * FROM subscription_plans ORDER BY sort_order ASC, id ASC'
  ).all<SubscriptionPlanRow>();
  return ok(c, { plans: rows.results.map((r) => toApiPlan(r)) });
}

// ---------------------------------------------------------------------------
// PATCH /admin/plans/:id — edit price/days/visibility (audited)
// ---------------------------------------------------------------------------

export async function adminPatchPlan(c: Context): Promise<Response> {
  const planId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    priceMinor: { type: 'number', required: false, min: 0, max: 100_000_000, integer: true },
    durationDays: { type: 'number', required: false, min: 1, max: 400, integer: true },
    isActive: { type: 'boolean', required: false },
    isPopular: { type: 'boolean', required: false },
    displayName: { type: 'string', required: false, minLength: 1, maxLength: 64 },
    description: { type: 'string', required: false, maxLength: 300 },
    sortOrder: { type: 'number', required: false, min: 0, max: 999, integer: true },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const existing = await c.env.DB.prepare(
    'SELECT * FROM subscription_plans WHERE id = ?'
  ).bind(planId).first<SubscriptionPlanRow>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'Plan not found', 404);

  const priceMinor = (v.value.priceMinor as number | undefined) ?? existing.price_minor;
  const durationDays = (v.value.durationDays as number | undefined) ?? existing.duration_days;
  const isActive = (v.value.isActive as boolean | undefined) ?? existing.is_active === 1;
  const isPopular = (v.value.isPopular as boolean | undefined) ?? existing.is_popular === 1;
  const displayName = (v.value.displayName as string | undefined) ?? existing.display_name;
  const description =
    v.value.description !== undefined ? (v.value.description as string | null) : existing.description;
  const sortOrder = (v.value.sortOrder as number | undefined) ?? existing.sort_order;

  await c.env.DB.prepare(
    `UPDATE subscription_plans
     SET price_minor = ?, duration_days = ?, is_active = ?, is_popular = ?,
         display_name = ?, description = ?, sort_order = ?, updated_at = ?
     WHERE id = ?`
  )
    .bind(
      priceMinor,
      durationDays,
      isActive ? 1 : 0,
      isPopular ? 1 : 0,
      displayName,
      description,
      sortOrder,
      new Date().toISOString(),
      planId
    )
    .run();

  const changes: string[] = [];
  if (priceMinor !== existing.price_minor) changes.push(`price ${existing.price_minor}->${priceMinor}`);
  if (durationDays !== existing.duration_days) changes.push(`days ${existing.duration_days}->${durationDays}`);
  if (isActive !== (existing.is_active === 1)) changes.push(`active ${isActive}`);
  await appendAudit(
    c.env,
    c.admin!.adminId,
    'PLAN_UPDATED',
    'subscription_plan',
    planId,
    changes.length > 0 ? changes.join(', ') : 'no material change',
    c.requestId
  );

  const row = await c.env.DB.prepare('SELECT * FROM subscription_plans WHERE id = ?')
    .bind(planId)
    .first<SubscriptionPlanRow>();
  return ok(c, { plan: row !== null ? toApiPlan(row) : null });
}

// ---------------------------------------------------------------------------
// GET /admin/payments/bkash — review queue
// ---------------------------------------------------------------------------

export async function adminListBkashPayments(c: Context): Promise<Response> {
  const limit = parseLimit(c.url);
  const status = c.url.searchParams.get('status');
  const cursorRaw = c.url.searchParams.get('cursor');

  let where = '1 = 1';
  const params: unknown[] = [];
  if (status !== null && status.length > 0) {
    if (!(BKASH_PAYMENT_STATUSES as readonly string[]).includes(status)) {
      return fail(c, 'VALIDATION_FAILED', 'Invalid status filter', 400);
    }
    where += ' AND b.status = ?';
    params.push(status);
  }
  if (cursorRaw !== null && cursorRaw.length > 0) {
    const cur = decodeCursor(cursorRaw);
    if (cur === null) return fail(c, 'VALIDATION_FAILED', 'Invalid cursor', 400);
    where += ' AND (b.created_at < ? OR (b.created_at = ? AND b.id < ?))';
    params.push(cur.createdAt, cur.createdAt, cur.id);
  }

  const rows = await c.env.DB.prepare(
    `SELECT b.*, u.email AS user_email, p.display_name AS plan_name, p.duration_days AS plan_days
     FROM bkash_payments b
     LEFT JOIN users u ON u.id = b.user_id
     LEFT JOIN subscription_plans p ON p.id = b.plan_id
     WHERE ${where}
     ORDER BY b.created_at DESC, b.id DESC
     LIMIT ?`
  )
    .bind(...params, limit + 1)
    .all<BkashPaymentRow & { user_email: string | null; plan_name: string | null; plan_days: number | null }>();

  const results = rows.results;
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const last = page[page.length - 1] as { created_at: string; id: string } | undefined;
  const nextCursor = hasMore && last ? encodeCursor(last.created_at, last.id) : null;

  const inReview = await c.env.DB.prepare(
    "SELECT COUNT(*) AS n FROM bkash_payments WHERE status = 'IN_REVIEW'"
  ).first<CountRow>();
  const verified = await c.env.DB.prepare(
    "SELECT COUNT(*) AS n FROM bkash_payments WHERE status = 'VERIFIED'"
  ).first<CountRow>();
  const bdtTotal = await c.env.DB.prepare(
    "SELECT COALESCE(SUM(amount_minor), 0) AS n FROM bkash_payments WHERE status = 'VERIFIED'"
  ).first<CountRow>();

  return ok(c, {
    payments: page.map((r) => ({
      ...toApiBkashPayment(r),
      userEmail: r.user_email ?? null,
      planName: r.plan_name ?? null,
      planDays: r.plan_days ?? null,
    })),
    nextCursor,
    summary: {
      inReview: inReview?.n ?? 0,
      verified: verified?.n ?? 0,
      verifiedAmountMinor: bdtTotal?.n ?? 0,
    },
  });
}

// ---------------------------------------------------------------------------
// POST /admin/payments/bkash/:id/verify — grant PRO days (audited)
// ---------------------------------------------------------------------------

export async function adminVerifyBkashPayment(c: Context): Promise<Response> {
  const paymentId = c.params.id;

  const payment = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ?'
  ).bind(paymentId).first<BkashPaymentRow>();
  if (payment === null) return fail(c, 'NOT_FOUND', 'Payment not found', 404);
  if (payment.status === 'VERIFIED') {
    return fail(c, 'CONFLICT', 'Payment already verified', 409);
  }
  if (payment.status !== 'IN_REVIEW') {
    return fail(c, 'CONFLICT', `Only IN_REVIEW payments can be verified (current: ${payment.status})`, 409);
  }

  const plan = await c.env.DB.prepare(
    'SELECT * FROM subscription_plans WHERE id = ?'
  ).bind(payment.plan_id).first<SubscriptionPlanRow>();
  if (plan === null) return fail(c, 'SERVER_ERROR', 'Plan row missing for payment', 500);

  const now = new Date();
  const nowIso = now.toISOString();

  // Extend from the CURRENT expiry when the user still has active time,
  // otherwise from now — never let a verify eat paid days.
  const current = await c.env.DB.prepare(
    `SELECT id, expiry_date FROM subscriptions
     WHERE user_id = ? AND status = 'ACTIVE' AND expiry_date IS NOT NULL AND expiry_date > ?
     ORDER BY updated_at DESC LIMIT 1`
  )
    .bind(payment.user_id, nowIso)
    .first<{ id: string; expiry_date: string }>();

  const baseMs = current !== null ? Date.parse(current.expiry_date) : now.getTime();
  const newExpiry = new Date(baseMs + plan.duration_days * 24 * 60 * 60 * 1000).toISOString();

  const subscriptionId = current !== null ? current.id : `sub_${randomToken(12)}`;

  // M5: claim the payment FIRST via the guarded UPDATE. A concurrent reviewer
  // (or a concurrent reject) that flips the status first makes this match 0
  // rows, and we bail BEFORE granting anything — no double grant, no
  // grant-on-reject. (bkash_payments.subscription_id has no FK, so it is safe
  // to record the target subscription id here even moments before the
  // subscription row itself is written below.)
  const claim = await c.env.DB.prepare(
    `UPDATE bkash_payments
     SET status = 'VERIFIED', reviewed_by = ?, reviewed_at = ?, subscription_id = ?, updated_at = ?
     WHERE id = ? AND status = 'IN_REVIEW'`
  )
    .bind(c.admin!.adminId, nowIso, subscriptionId, nowIso, paymentId)
    .run();
  if ((claim.meta.changes ?? 0) === 0) {
    return fail(c, 'CONFLICT', 'Payment was already processed by another reviewer', 409);
  }

  // Only after a successful claim: grant the PRO days (single atomic
  // statement — a crash here leaves a VERIFIED payment with a recoverable
  // missing grant, never a double grant).
  if (current !== null) {
    await c.env.DB.prepare(
      `UPDATE subscriptions SET expiry_date = ?, updated_at = ?, last_verified = ? WHERE id = ?`
    )
      .bind(newExpiry, nowIso, nowIso, subscriptionId)
      .run();
  } else {
    await c.env.DB.prepare(
      `INSERT INTO subscriptions (id, user_id, product_id, purchase_token, plan, status,
                                  start_date, expiry_date, last_verified, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)`
    )
      .bind(
        subscriptionId,
        payment.user_id,
        plan.product_id,
        `bkash_${payment.id}`,
        plan.plan,
        nowIso,
        newExpiry,
        nowIso,
        nowIso,
        nowIso
      )
      .run();
  }

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'BKASH_PAYMENT_VERIFIED',
    'bkash_payment',
    paymentId,
    `trx=${payment.trx_id ?? 'n/a'} amount=${payment.amount_minor} grantedDays=${plan.duration_days} sub=${subscriptionId}`,
    c.requestId
  );

  const updated = await c.env.DB.prepare('SELECT * FROM bkash_payments WHERE id = ?')
    .bind(paymentId)
    .first<BkashPaymentRow>();
  return ok(c, {
    payment: updated !== null ? toApiBkashPayment(updated) : null,
    subscription: { id: subscriptionId, expiryDate: newExpiry },
  });
}

// ---------------------------------------------------------------------------
// POST /admin/payments/bkash/:id/reject — audited + security event
// ---------------------------------------------------------------------------

export async function adminRejectBkashPayment(c: Context): Promise<Response> {
  const paymentId = c.params.id;
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    reason: { type: 'string', required: true, minLength: 3, maxLength: 300 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const reason = v.value.reason as string;

  const payment = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ?'
  ).bind(paymentId).first<BkashPaymentRow>();
  if (payment === null) return fail(c, 'NOT_FOUND', 'Payment not found', 404);
  if (payment.status === 'VERIFIED') {
    return fail(c, 'CONFLICT', 'Verified payments cannot be rejected — refund via Play/bKash instead', 409);
  }
  if (payment.status === 'REJECTED') {
    return fail(c, 'CONFLICT', 'Payment already rejected', 409);
  }

  const nowIso = new Date().toISOString();
  // M5: claim-first guard on the reject path too — only reject while the
  // status is still the one we read above. A concurrent verify (or any other
  // status flip) makes this match 0 rows and the reject bails with 409
  // instead of overwriting the other reviewer's decision.
  const claim = await c.env.DB.prepare(
    `UPDATE bkash_payments
     SET status = 'REJECTED', reviewed_by = ?, reviewed_at = ?, reject_reason = ?, updated_at = ?
     WHERE id = ? AND status = ?`
  )
    .bind(c.admin!.adminId, nowIso, reason, nowIso, paymentId, payment.status)
    .run();
  if ((claim.meta.changes ?? 0) === 0) {
    return fail(c, 'CONFLICT', 'Payment was already processed by another reviewer', 409);
  }

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'BKASH_PAYMENT_REJECTED',
    'bkash_payment',
    paymentId,
    `trx=${payment.trx_id ?? 'n/a'} reason=${reason}`,
    c.requestId
  );
  // A rejected manual payment is a potential fraud attempt — record it.
  await appendSecurityEvent(
    c.env,
    payment.user_id,
    payment.device_id,
    'BKASH_PAYMENT_REJECTED',
    'MEDIUM',
    { paymentId, trxId: payment.trx_id, reason }
  );

  const updated = await c.env.DB.prepare('SELECT * FROM bkash_payments WHERE id = ?')
    .bind(paymentId)
    .first<BkashPaymentRow>();
  return ok(c, { payment: updated !== null ? toApiBkashPayment(updated) : null });
}
