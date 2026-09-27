/**
 * MAXLEVEL DETOX — admin authentication + RBAC middleware.
 *
 * Admin sessions are opaque 32-byte tokens stored in KV:
 *   key:   admin_sess_<sha256hex(token)>
 *   value: JSON { adminId, role, exp, sig }  (TTL = session lifetime)
 * `sig` is an HMAC-SHA256(adminId|role|exp, ADMIN_SESSION_SECRET) making the
 * KV value tamper-evident. On every request the admin's role/status are
 * re-read from D1, so disabling an admin or changing its role takes effect
 * immediately even while a KV session is still alive.
 *
 * RBAC matrix is the frozen contract v1 matrix (see RBAC_MATRIX below).
 */

import {
  AdminContext,
  AdminRole,
  Context,
  Middleware,
} from '../types';
import { fail } from '../utils/response';
import { hmacSha256Hex, sha256Hex, timingSafeEqual } from '../utils/crypto';
import { isRecord } from '../middleware/validation';
import { bearerToken } from '../middleware/auth';

// ---------------------------------------------------------------------------
// RBAC — frozen contract v1
// ---------------------------------------------------------------------------

export type Permission =
  | 'VIEW_USERS'
  | 'EDIT_USERS'
  | 'VIEW_DEVICES'
  | 'MANAGE_CONFIG'
  | 'MANAGE_FLAGS'
  | 'MANAGE_ANNOUNCEMENTS'
  | 'VIEW_ANALYTICS'
  | 'VIEW_AUDIT'
  | 'MANAGE_SUPPORT'
  | 'MANAGE_PAYMENTS'
  | 'MANAGE_SYSTEM';

export const ADMIN_ROLES: readonly AdminRole[] = [
  'SUPER_ADMIN',
  'ADMIN',
  'SUPPORT',
  'ANALYST',
  'CONFIG_MANAGER',
  'READ_ONLY',
];

const RBAC_MATRIX: Record<AdminRole, readonly Permission[]> = {
  // SUPER_ADMIN: everything.
  SUPER_ADMIN: [
    'VIEW_USERS',
    'EDIT_USERS',
    'VIEW_DEVICES',
    'MANAGE_CONFIG',
    'MANAGE_FLAGS',
    'MANAGE_ANNOUNCEMENTS',
    'VIEW_ANALYTICS',
    'VIEW_AUDIT',
    'MANAGE_SUPPORT',
    'MANAGE_PAYMENTS',
    'MANAGE_SYSTEM',
  ],
  // ADMIN: everything except MANAGE_SYSTEM; cannot manage admin users.
  ADMIN: [
    'VIEW_USERS',
    'EDIT_USERS',
    'VIEW_DEVICES',
    'MANAGE_CONFIG',
    'MANAGE_FLAGS',
    'MANAGE_ANNOUNCEMENTS',
    'VIEW_ANALYTICS',
    'VIEW_AUDIT',
    'MANAGE_SUPPORT',
    'MANAGE_PAYMENTS',
  ],
  // SUPPORT: reviews the bKash payment queue (v2.2 Phase D).
  SUPPORT: ['VIEW_USERS', 'EDIT_USERS', 'VIEW_DEVICES', 'VIEW_AUDIT', 'MANAGE_SUPPORT', 'MANAGE_PAYMENTS'],
  ANALYST: ['VIEW_USERS', 'VIEW_DEVICES', 'VIEW_ANALYTICS', 'VIEW_AUDIT'],
  CONFIG_MANAGER: ['VIEW_USERS', 'VIEW_DEVICES', 'MANAGE_CONFIG', 'MANAGE_FLAGS', 'MANAGE_ANNOUNCEMENTS'],
  // READ_ONLY: all VIEW_* permissions.
  READ_ONLY: ['VIEW_USERS', 'VIEW_DEVICES', 'VIEW_ANALYTICS', 'VIEW_AUDIT'],
};

/** Frozen RBAC matrix check. */
export function hasPermission(role: AdminRole, permission: Permission): boolean {
  const perms = RBAC_MATRIX[role];
  return perms !== undefined && perms.includes(permission);
}

// ---------------------------------------------------------------------------
// Middleware factory
// ---------------------------------------------------------------------------

interface AdminSessionValue {
  adminId: string;
  role: AdminRole;
  exp: number;
  sig: string;
}

interface AdminDbRow {
  id: string;
  email: string;
  role: string;
  status: string;
}

function parseSessionValue(raw: string): AdminSessionValue | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    !isRecord(parsed) ||
    typeof parsed.adminId !== 'string' ||
    typeof parsed.role !== 'string' ||
    typeof parsed.exp !== 'number' ||
    typeof parsed.sig !== 'string'
  ) {
    return null;
  }
  return {
    adminId: parsed.adminId,
    role: parsed.role as AdminRole,
    exp: parsed.exp,
    sig: parsed.sig,
  };
}

/**
 * Build the adminAuth middleware for a required permission.
 * Note: /admin/admin-users and /admin/system routes use MANAGE_SYSTEM which
 * only SUPER_ADMIN holds — that is how "SUPER_ADMIN only" is enforced.
 */
export function requireAdmin(permission: Permission): Middleware {
  return async (c: Context): Promise<Response | void> => {
    const resolved = await resolveAdminSession(c);
    if (resolved instanceof Response) return resolved;
    if (!hasPermission(resolved.role, permission)) {
      return fail(c, 'FORBIDDEN', `Role ${resolved.role} does not have permission ${permission}`, 403);
    }
    c.admin = resolved;
    return void 0;
  };
}

/**
 * m22: lightweight admin-session check with NO permission requirement —
 * used by POST /admin/auth/logout, where any authenticated admin role may
 * revoke its own KV session. Performs the exact same session validation,
 * HMAC tamper-evidence and fresh D1 role/status re-read as requireAdmin.
 */
export const requireAdminSession: Middleware = async (c: Context): Promise<Response | void> => {
  const resolved = await resolveAdminSession(c);
  if (resolved instanceof Response) return resolved;
  c.admin = resolved;
  return void 0;
};

/**
 * Shared session resolution behind requireAdmin/requireAdminSession:
 * bearer token -> KV lookup -> HMAC signature check -> fresh D1 role/status.
 * Returns the AdminContext on success, or the error Response on failure.
 */
async function resolveAdminSession(c: Context): Promise<AdminContext | Response> {
  const token = bearerToken(c);
  if (token === null) return fail(c, 'UNAUTHORIZED', 'Missing bearer token', 401);
  if (!/^[0-9a-f]{64}$/.test(token)) return fail(c, 'UNAUTHORIZED', 'Invalid token format', 401);

  const hash = await sha256Hex(token);
  const raw = await c.env.KV.get(`admin_sess_${hash}`);
  if (raw === null) return fail(c, 'UNAUTHORIZED', 'Invalid or expired admin session', 401);

  const sess = parseSessionValue(raw);
  if (sess === null) return fail(c, 'UNAUTHORIZED', 'Malformed admin session', 401);
  if (sess.exp <= Date.now()) return fail(c, 'UNAUTHORIZED', 'Admin session expired', 401);

  if (!c.env.ADMIN_SESSION_SECRET) {
    return fail(c, 'SERVER_ERROR', 'ADMIN_SESSION_SECRET is not configured', 500);
  }
  // Tamper-evidence: the stored value must be signed with the server secret.
  const expectedSig = await hmacSha256Hex(
    `${sess.adminId}|${sess.role}|${sess.exp}`,
    c.env.ADMIN_SESSION_SECRET
  );
  if (!timingSafeEqual(expectedSig, sess.sig)) {
    return fail(c, 'UNAUTHORIZED', 'Invalid admin session signature', 401);
  }

  // Fresh role/status from D1 (role changes take effect immediately).
  const row = await c.env.DB.prepare(
    'SELECT id, email, role, status FROM admin_users WHERE id = ?'
  )
    .bind(sess.adminId)
    .first<AdminDbRow>();
  if (row === null) return fail(c, 'UNAUTHORIZED', 'Admin account no longer exists', 401);
  if (row.status !== 'ACTIVE') return fail(c, 'FORBIDDEN', 'Admin account is disabled', 403);

  const role = (ADMIN_ROLES as readonly string[]).includes(row.role) ? (row.role as AdminRole) : 'READ_ONLY';
  return { adminId: row.id, email: row.email, role };
}
