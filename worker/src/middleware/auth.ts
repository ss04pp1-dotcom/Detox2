/**
 * MAXLEVEL DETOX — app-user authentication middleware.
 *
 * Extracts the bearer token, hashes it (SHA-256) and looks up the session in
 * the D1 `user_sessions` table. Only the token HASH is ever stored/queried.
 * On success injects `c.user`; otherwise returns the 401 UNAUTHORIZED envelope.
 */

import { Context, Middleware } from '../types';
import { fail } from '../utils/response';
import { sha256Hex } from '../utils/crypto';

const BEARER_TOKEN_RE = /^[0-9a-f]{64}$/;

interface SessionJoinRow {
  token_hash: string;
  device_id: string | null;
  access_expires_at: string;
  user_id: string;
  email: string;
  status: string;
}

/** Extract the raw bearer token from the Authorization header. */
export function bearerToken(c: Context): string | null {
  const header = c.req.headers.get('Authorization');
  if (header === null || !header.startsWith('Bearer ')) return null;
  const token = header.slice('Bearer '.length).trim();
  return token.length > 0 ? token : null;
}

/** Require an authenticated app user. Injects c.user on success. */
export const requireUser: Middleware = async (c: Context): Promise<Response | void> => {
  const token = bearerToken(c);
  if (token === null) return fail(c, 'UNAUTHORIZED', 'Missing bearer token', 401);
  if (!BEARER_TOKEN_RE.test(token)) return fail(c, 'UNAUTHORIZED', 'Invalid token format', 401);

  const tokenHash = await sha256Hex(token);
  const row = await c.env.DB.prepare(
    `SELECT s.token_hash AS token_hash, s.device_id AS device_id, s.access_expires_at AS access_expires_at,
            u.id AS user_id, u.email AS email, u.status AS status
     FROM user_sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = ?`
  )
    .bind(tokenHash)
    .first<SessionJoinRow>();

  if (row === null) return fail(c, 'UNAUTHORIZED', 'Invalid or expired session', 401);

  if (Date.parse(row.access_expires_at) <= Date.now()) {
    // Access token expired. The row may still hold a valid refresh token,
    // so it is kept — the client should POST /auth/refresh.
    return fail(c, 'UNAUTHORIZED', 'Access token expired', 401);
  }

  if (row.status === 'BANNED' || row.status === 'DELETED') {
    return fail(c, 'FORBIDDEN', 'Account is not accessible', 403);
  }
  if (row.status === 'SUSPENDED') {
    return fail(c, 'FORBIDDEN', 'Account is suspended', 403);
  }

  c.user = {
    userId: row.user_id,
    email: row.email,
    deviceId: row.device_id,
    tokenHash,
  };
  return void 0;
};
