/**
 * MAXLEVEL DETOX — app (mobile client) routes.
 *
 * Implements every APP ROUTE from the frozen API contract v1:
 *   POST   /api/v1/auth/google          Google ID-token login (+dev-mode in dev)
 *   POST   /api/v1/auth/refresh         rotate + revoke refresh token
 *   POST   /api/v1/auth/logout
 *   GET    /api/v1/me
 *   POST   /api/v1/devices/register     (max 5 devices per user)
 *   POST   /api/v1/devices/heartbeat
 *   GET    /api/v1/config               active published config (versioned)
 *   GET    /api/v1/flags                per-device rollout hashing
 *   GET    /api/v1/app/version
 *   GET    /api/v1/announcements?since=
 *   POST   /api/v1/events               idempotent batch (INSERT OR IGNORE)
 *   POST   /api/v1/subscription/verify  Google Play Developer API
 *   GET    /api/v1/subscription
 *   DELETE /api/v1/me                   deletion_pending
 *
 * All D1 statements are parameterized (.bind) — zero SQL interpolation.
 */

import {
  ConfigDoc,
  Context,
  Env,
  FeatureFlagRow,
  SubscriptionRow,
  UserRow,
} from '../types';
import { ok, fail } from '../utils/response';
import {
  base64UrlEncode,
  pbkdf2Hash,
  pemToDer,
  randomToken,
  sha256Hex,
  verifyPassword,
} from '../utils/crypto';
import { isRecord, readJsonBody, validateFields } from '../middleware/validation';
import { appendSecurityEvent } from '../services/audit';
import { toApiSubscription, toApiUser } from '../services/serializers';

const ACCESS_TOKEN_TTL_S = 3600; // 1 hour
const REFRESH_TOKEN_TTL_S = 30 * 24 * 60 * 60; // 30 days
const MAX_DEVICES_PER_USER = 5;
/** v2.5.7 (W-5): max concurrently-live sessions per user. */
const MAX_LIVE_SESSIONS = 10;
const MAX_EVENTS_PER_BATCH = 100;
const MAX_EVENT_PAYLOAD_JSON = 10_000;
/** Play Store package name — must match the shipped Android applicationId
 * (mobile/android/app/build.gradle: `com.maxleveldet0x`, deliberately
 * obfuscated). A mismatch makes Play answer 404 for every purchase lookup. */
const PACKAGE_NAME = 'com.sonexdev.detox';

const TOKEN_RE = /^[0-9a-f]{64}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EVENT_ID_RE = /^evt_[A-Za-z0-9:_-]{1,124}$/;

const EVENT_TYPES: readonly string[] = [
  'SESSION_STARTED',
  'SESSION_COMPLETED',
  'SESSION_BAILED_OUT',
  'TEMP_UNLOCK_STARTED',
  'TEMP_UNLOCK_EXPIRED',
  'SHORTS_WARNING',
  'CAGE_ACTIVATED',
  'CAGE_RELEASED',
  'ALARM_TRIGGERED',
  'ALARM_COMPLETED',
  'PERMISSION_CHANGED',
  'RECOVERY_TRIGGERED',
];

// ---------------------------------------------------------------------------
// Google ID token verification (v2.5.7 / W-8: local JWKS first)
// ---------------------------------------------------------------------------

interface VerifiedGoogleIdentity {
  email: string;
  sub: string | null;
}

interface Jwk {
  kid?: string;
  n?: string;
  e?: string;
  kty?: string;
  alg?: string;
  use?: string;
}

function base64UrlToBytes(input: string): Uint8Array {
  const pad = '='.repeat((4 - (input.length % 4)) % 4);
  const b64 = input.replace(/-/g, '+').replace(/_/g, '/') + pad;
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Google's JWKS, cached in KV for 24h (W-8). */
async function fetchGoogleJwks(env: Env): Promise<Jwk[]> {
  const KV_KEY = 'google_jwks_v1';
  const cached = await env.KV.get(KV_KEY);
  if (cached !== null) {
    try {
      const parsed: unknown = JSON.parse(cached);
      if (Array.isArray(parsed)) return parsed as Jwk[];
    } catch {
      // fall through to a fresh fetch
    }
  }
  const res = await fetch('https://www.googleapis.com/oauth2/v3/certs');
  if (!res.ok) throw new Error(`jwks fetch returned HTTP ${res.status}`);
  const data: unknown = await res.json();
  if (!isRecord(data) || !Array.isArray(data.keys)) throw new Error('malformed jwks');
  const keys = data.keys as Jwk[];
  try {
    await env.KV.put(KV_KEY, JSON.stringify(keys), { expirationTtl: 24 * 60 * 60 });
  } catch {
    // cache write is best-effort
  }
  return keys;
}

/** Verify a Google ID token LOCALLY (JWKS + RS256 + aud/iss/exp claims). */
async function verifyGoogleIdTokenLocally(
  idToken: string,
  expectedAud: string,
  env: Env
): Promise<VerifiedGoogleIdentity | null> {
  const parts = idToken.split('.');
  if (parts.length !== 3) return null;
  const [headerB64, payloadB64, sigB64] = parts;

  let header: Record<string, unknown>;
  let claims: Record<string, unknown>;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlToBytes(headerB64))) as Record<string, unknown>;
    claims = JSON.parse(new TextDecoder().decode(base64UrlToBytes(payloadB64))) as Record<string, unknown>;
  } catch {
    return null;
  }

  // Required claim checks (Google's documented guidance).
  if (header.alg !== 'RS256') return null;
  if (claims.iss !== 'accounts.google.com' && claims.iss !== 'https://accounts.google.com') return null;
  if (claims.aud !== expectedAud) return null;
  const exp = typeof claims.exp === 'number' ? claims.exp : Number(claims.exp);
  if (!Number.isFinite(exp) || exp * 1000 <= Date.now()) return null;
  const emailVerified = claims.email_verified;
  if (emailVerified !== true && emailVerified !== 'true') return null;
  if (typeof claims.email !== 'string' || !EMAIL_RE.test(claims.email)) return null;

  // Signature: find the JWK by kid and verify RSASSA-PKCS1-v1_5 over header.payload.
  const jwks = await fetchGoogleJwks(env);
  const kid = typeof header.kid === 'string' ? header.kid : null;
  const jwk =
    jwks.find((k) => k.kid === kid) ??
    jwks.find((k) => k.kty === 'RSA' && (k.alg === undefined || k.alg === 'RS256') && k.use !== 'enc');
  if (jwk === undefined || jwk.n === undefined || jwk.e === undefined) return null;

  const key = await crypto.subtle.importKey(
    'jwk',
    { kty: 'RSA', n: jwk.n, e: jwk.e, alg: 'RS256', ext: true, key_ops: [] },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify']
  );
  const ok = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    key,
    base64UrlToBytes(sigB64),
    new TextEncoder().encode(`${headerB64}.${payloadB64}`)
  );
  if (!ok) return null;

  return {
    email: claims.email.toLowerCase(),
    sub: typeof claims.sub === 'string' ? claims.sub : null,
  };
}

/**
 * Verify a Google ID token. v2.5.7 (W-8): LOCAL JWKS verification first
 * (fast, cheap, Google's recommended method), falling back to the legacy
 * tokeninfo endpoint when local verification cannot complete (e.g. JWKS
 * fetch failed). Returns null on any authentication failure; throws only
 * on transport failure of BOTH paths.
 */
async function verifyGoogleIdToken(
  idToken: string,
  expectedAud: string,
  env: Env
): Promise<VerifiedGoogleIdentity | null> {
  try {
    const local = await verifyGoogleIdTokenLocally(idToken, expectedAud, env);
    if (local !== null) return local;
  } catch (err) {
    console.warn('google jwks verification unavailable, falling back to tokeninfo', err);
  }

  // Legacy fallback: the tokeninfo endpoint (rate-limited, deprecation-prone).
  const res = await fetch(
    `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`
  );
  if (res.status === 400 || res.status === 401) return null;
  if (!res.ok) throw new Error(`tokeninfo returned HTTP ${res.status}`);

  const data: unknown = await res.json();
  if (!isRecord(data)) return null;
  if (data.aud !== expectedAud) return null;

  const emailVerified = data.email_verified;
  if (emailVerified !== true && emailVerified !== 'true') return null;

  const exp = typeof data.exp === 'string' ? Number(data.exp) : data.exp;
  if (typeof exp !== 'number' || !Number.isFinite(exp) || exp * 1000 <= Date.now()) return null;

  if (typeof data.email !== 'string' || !EMAIL_RE.test(data.email)) return null;

  return {
    email: data.email.toLowerCase(),
    sub: typeof data.sub === 'string' ? data.sub : null,
  };
}

async function issueUserSession(c: Context, user: UserRow): Promise<Response> {
  const now = new Date().toISOString();
  const accessToken = randomToken(32);
  const refreshToken = randomToken(32);
  const accessExpiresAt = new Date(Date.now() + ACCESS_TOKEN_TTL_S * 1000).toISOString();
  const sessionExpiresAt = new Date(Date.now() + REFRESH_TOKEN_TTL_S * 1000).toISOString();
  const accessHash = await sha256Hex(accessToken);
  const refreshHash = await sha256Hex(refreshToken);

  await c.env.DB.batch([
    // housekeeping: purge fully-expired sessions for this user
    c.env.DB.prepare('DELETE FROM user_sessions WHERE user_id = ? AND expires_at < ?').bind(
      user.id,
      now
    ),
    // v2.5.7 (W-5): cap live sessions per user.
    c.env.DB.prepare(
      `DELETE FROM user_sessions WHERE user_id = ? AND token_hash NOT IN (
         SELECT token_hash FROM user_sessions WHERE user_id = ?
         ORDER BY created_at DESC LIMIT ?
       )`
    ).bind(user.id, user.id, MAX_LIVE_SESSIONS),
    c.env.DB.prepare(
      `INSERT INTO user_sessions (token_hash, user_id, refresh_token_hash, access_expires_at, expires_at, created_at, device_id)
       VALUES (?, ?, ?, ?, ?, ?, NULL)`
    )
      .bind(accessHash, user.id, refreshHash, accessExpiresAt, sessionExpiresAt, now),
  ]);

  return ok(c, {
    user: toApiUser(user),
    tokens: { accessToken, refreshToken, accessExpiresIn: ACCESS_TOKEN_TTL_S },
  });
}

// ---------------------------------------------------------------------------
// POST /auth/register (Email + Password Signup)
// ---------------------------------------------------------------------------

export async function authRegister(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    email: { type: 'string', required: true, minLength: 5, maxLength: 254 },
    password: { type: 'string', required: true, minLength: 6, maxLength: 128 },
    displayName: { type: 'string', required: false, minLength: 1, maxLength: 50 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const email = (v.value.email as string).trim().toLowerCase();
  if (!EMAIL_RE.test(email)) {
    return fail(c, 'VALIDATION_FAILED', 'Invalid email address', 400);
  }
  const password = v.value.password as string;
  const displayName =
    (v.value.displayName as string | undefined)?.trim() ||
    `User #${randomToken(2).toUpperCase()}`;

  // Check if user already exists
  const existing = await c.env.DB.prepare('SELECT id, status FROM users WHERE email = ?')
    .bind(email)
    .first<{ id: string; status: string }>();

  if (existing !== null) {
    return fail(c, 'CONFLICT', 'An account with this email already exists', 409);
  }

  const id = `usr_${randomToken(12)}`;
  const hash = await pbkdf2Hash(password);
  const now = new Date().toISOString();

  try {
    await c.env.DB.prepare(
      `INSERT INTO users (id, email, password_hash, display_name, status, created_at, updated_at, last_seen_at, deletion_pending_at)
       VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, NULL)`
    )
      .bind(id, email, hash, displayName, now, now, now)
      .run();
  } catch (err) {
    // Gracefully handle dynamic column addition if migration hasn't applied yet
    if (err instanceof Error && err.message.includes('no such column: password_hash')) {
      try {
        await c.env.DB.prepare('ALTER TABLE users ADD COLUMN password_hash TEXT').run();
        await c.env.DB.prepare(
          `INSERT INTO users (id, email, password_hash, display_name, status, created_at, updated_at, last_seen_at, deletion_pending_at)
           VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, NULL)`
        )
          .bind(id, email, hash, displayName, now, now, now)
          .run();
      } catch (retryErr) {
        console.error(c.requestId, 'failed to register after alter table', retryErr);
        return fail(c, 'SERVER_ERROR', 'Registration failed', 500);
      }
    } else {
      console.error(c.requestId, 'user registration insert error', err);
      return fail(c, 'SERVER_ERROR', 'Could not create account', 500);
    }
  }

  const newUser: UserRow = {
    id,
    email,
    password_hash: hash,
    display_name: displayName,
    status: 'ACTIVE',
    created_at: now,
    updated_at: now,
    last_seen_at: now,
    deletion_pending_at: null,
  };

  return issueUserSession(c, newUser);
}

// ---------------------------------------------------------------------------
// POST /auth/login (Email + Password Login)
// ---------------------------------------------------------------------------

export async function authLogin(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    email: { type: 'string', required: true, minLength: 5, maxLength: 254 },
    password: { type: 'string', required: true, minLength: 1, maxLength: 128 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const email = (v.value.email as string).trim().toLowerCase();
  const password = v.value.password as string;

  const user = await c.env.DB.prepare('SELECT * FROM users WHERE email = ?')
    .bind(email)
    .first<UserRow>();

  if (user === null || !user.password_hash) {
    return fail(c, 'UNAUTHORIZED', 'Invalid email or password', 401);
  }

  if (user.status === 'BANNED' || user.status === 'DELETED') {
    return fail(c, 'FORBIDDEN', 'Account is not accessible', 403);
  }
  if (user.status === 'SUSPENDED') {
    return fail(c, 'FORBIDDEN', 'Account is suspended', 403);
  }

  const match = await verifyPassword(password, user.password_hash);
  if (!match) {
    return fail(c, 'UNAUTHORIZED', 'Invalid email or password', 401);
  }

  const now = new Date().toISOString();
  await c.env.DB.prepare(
    `UPDATE users SET last_seen_at = ?, updated_at = ?, deletion_pending_at = NULL WHERE id = ?`
  )
    .bind(now, now, user.id)
    .run();
  user.last_seen_at = now;
  user.updated_at = now;
  user.deletion_pending_at = null;

  return issueUserSession(c, user);
}

// ---------------------------------------------------------------------------
// POST /auth/google
// ---------------------------------------------------------------------------

export async function authGoogle(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    credential: { type: 'string', required: true, minLength: 10, maxLength: 5000 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const credential = v.value.credential as string;

  const expectedAud: string | null = c.env.GOOGLE_CLIENT_ID ?? c.env.GOOGLE_CLIENT_SECRET ?? null;
  const devModeAllowed = expectedAud === null && c.env.API_ENV === 'development';

  let email: string;
  let googleSub: string | null = null;

  if (credential.startsWith('dev:')) {
    if (!devModeAllowed) {
      return fail(c, 'SERVICE_UNAVAILABLE', 'Google authentication is not configured', 503);
    }
    const devEmail = credential.slice('dev:'.length).trim().toLowerCase();
    if (!EMAIL_RE.test(devEmail) || devEmail.length > 254) {
      return fail(c, 'INVALID_REQUEST', 'Invalid dev credential (expected "dev:<email>")', 400);
    }
    email = devEmail;
  } else {
    let verified: VerifiedGoogleIdentity | null = null;
    try {
      if (expectedAud !== null) {
        verified = await verifyGoogleIdToken(credential, expectedAud, c.env);
      } else {
        // Fallback: verify directly with Google tokeninfo endpoint
        const res = await fetch(
          `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`
        );
        if (res.ok) {
          const data: unknown = await res.json();
          if (isRecord(data)) {
            const emailVerified = data.email_verified;
            if (
              (emailVerified === true || emailVerified === 'true') &&
              typeof data.email === 'string' &&
              EMAIL_RE.test(data.email)
            ) {
              verified = {
                email: data.email.toLowerCase(),
                sub: typeof data.sub === 'string' ? data.sub : null,
              };
            }
          }
        }
      }
    } catch (err) {
      console.error(c.requestId, 'google token verification failure', err);
      return fail(c, 'SERVICE_UNAVAILABLE', 'Google token verification failed', 503);
    }
    if (verified === null) return fail(c, 'UNAUTHORIZED', 'Invalid Google credential', 401);
    email = verified.email;
    googleSub = verified.sub;
  }

  const now = new Date().toISOString();
  let user: UserRow | null = await c.env.DB.prepare('SELECT * FROM users WHERE email = ?')
    .bind(email)
    .first<UserRow>();
  let createdNow = false;

  if (user === null) {
    const id = `usr_${randomToken(12)}`;
    const displayName = `User #${randomToken(2).toUpperCase()}`;
    try {
      await c.env.DB.prepare(
        `INSERT INTO users (id, email, display_name, status, google_sub, created_at, updated_at, last_seen_at, deletion_pending_at)
         VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, NULL)`
      )
        .bind(id, email, displayName, googleSub, now, now, now)
        .run();
      user = {
        id,
        email,
        display_name: displayName,
        status: 'ACTIVE',
        google_sub: googleSub,
        created_at: now,
        updated_at: now,
        last_seen_at: now,
        deletion_pending_at: null,
      };
      createdNow = true;
    } catch (err) {
      if (!(err instanceof Error) || !err.message.includes('UNIQUE')) throw err;
      user = await c.env.DB.prepare('SELECT * FROM users WHERE email = ?')
        .bind(email)
        .first<UserRow>();
      if (user === null) throw err;
    }
  }

  if (user.status === 'BANNED' || user.status === 'DELETED') {
    return fail(c, 'FORBIDDEN', 'Account is not accessible', 403);
  }
  if (user.status === 'SUSPENDED') {
    return fail(c, 'FORBIDDEN', 'Account is suspended', 403);
  }
  if (!createdNow) {
    await c.env.DB.prepare(
      `UPDATE users SET last_seen_at = ?, updated_at = ?, google_sub = COALESCE(?, google_sub), deletion_pending_at = NULL
       WHERE id = ?`
    )
      .bind(now, now, googleSub, user.id)
      .run();
    user = { ...user, last_seen_at: now, updated_at: now, deletion_pending_at: null };
  }

  return issueUserSession(c, user);
}

// ---------------------------------------------------------------------------
// POST /auth/refresh — rotation: revoke old session, issue a new pair
// ---------------------------------------------------------------------------

interface RefreshJoinRow {
  token_hash: string;
  user_id: string;
  expires_at: string;
  status: string;
}

export async function authRefresh(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    refreshToken: { type: 'string', required: true, minLength: 64, maxLength: 64 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const refreshToken = v.value.refreshToken as string;
  if (!TOKEN_RE.test(refreshToken)) {
    return fail(c, 'UNAUTHORIZED', 'Invalid refresh token', 401);
  }

  const refreshHash = await sha256Hex(refreshToken);
  const row = await c.env.DB.prepare(
    `SELECT s.token_hash AS token_hash, s.user_id AS user_id, s.expires_at AS expires_at, u.status AS status
     FROM user_sessions s JOIN users u ON u.id = s.user_id
     WHERE s.refresh_token_hash = ?`
  )
    .bind(refreshHash)
    .first<RefreshJoinRow>();

  if (row === null) return fail(c, 'UNAUTHORIZED', 'Invalid refresh token', 401);
  if (row.status !== 'ACTIVE') return fail(c, 'FORBIDDEN', 'Account is not active', 403);
  if (Date.parse(row.expires_at) <= Date.now()) {
    await c.env.DB.prepare('DELETE FROM user_sessions WHERE token_hash = ?')
      .bind(row.token_hash)
      .run();
    return fail(c, 'UNAUTHORIZED', 'Session expired', 401);
  }

  const now = new Date().toISOString();
  const accessToken = randomToken(32);
  const newRefreshToken = randomToken(32);
  const accessExpiresAt = new Date(Date.now() + ACCESS_TOKEN_TTL_S * 1000).toISOString();
  const sessionExpiresAt = new Date(Date.now() + REFRESH_TOKEN_TTL_S * 1000).toISOString();

  // Claim-first rotation (m1): the guarded DELETE only matches while this
  // refresh token is still the live one for the session. Two concurrent
  // replays cannot both mint a pair — the loser's DELETE matches 0 rows and
  // gets a 401 before any INSERT happens.
  const claim = await c.env.DB.prepare(
    'DELETE FROM user_sessions WHERE token_hash = ? AND refresh_token_hash = ?'
  )
    .bind(row.token_hash, refreshHash)
    .run();
  if ((claim.meta.changes ?? 0) === 0) {
    return fail(c, 'UNAUTHORIZED', 'Refresh token already used', 401);
  }

  await c.env.DB.prepare(
    `INSERT INTO user_sessions (token_hash, user_id, refresh_token_hash, access_expires_at, expires_at, created_at, device_id)
     VALUES (?, ?, ?, ?, ?, ?, NULL)`
  )
    .bind(
      await sha256Hex(accessToken),
      row.user_id,
      await sha256Hex(newRefreshToken),
      accessExpiresAt,
      sessionExpiresAt,
      now
    )
    .run();

  return ok(c, {
    tokens: { accessToken, refreshToken: newRefreshToken, accessExpiresIn: ACCESS_TOKEN_TTL_S },
  });
}

// ---------------------------------------------------------------------------
// POST /auth/logout
// ---------------------------------------------------------------------------

export async function authLogout(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  await c.env.DB.prepare('DELETE FROM user_sessions WHERE token_hash = ?')
    .bind(c.user.tokenHash)
    .run();
  return ok(c, { loggedOut: true });
}

// ---------------------------------------------------------------------------
// GET /me
// ---------------------------------------------------------------------------

export async function getMe(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const user = await c.env.DB.prepare('SELECT * FROM users WHERE id = ?')
    .bind(c.user.userId)
    .first<UserRow>();
  if (user === null) return fail(c, 'UNAUTHORIZED', 'User not found', 401);

  const [subscriptionRow, deviceCount] = await Promise.all([
    c.env.DB.prepare(
      'SELECT * FROM subscriptions WHERE user_id = ? ORDER BY updated_at DESC LIMIT 1'
    )
      .bind(user.id)
      .first<SubscriptionRow>(),
    c.env.DB.prepare(`SELECT COUNT(*) AS n FROM devices WHERE user_id = ? AND status != 'REMOVED'`)
      .bind(user.id)
      .first<{ n: number }>(),
  ]);

  // M6: honesty — never report a stale ACTIVE entitlement whose expiry has
  // already passed (mirror of getSubscription).
  let subscription = subscriptionRow;
  if (
    subscription !== null &&
    subscription.status === 'ACTIVE' &&
    subscription.expiry_date !== null &&
    Date.parse(subscription.expiry_date) <= Date.now()
  ) {
    subscription = { ...subscription, status: 'EXPIRED' };
  }

  return ok(c, {
    user: toApiUser(user),
    subscription: subscription !== null ? toApiSubscription(subscription) : null,
    deviceCount: deviceCount?.n ?? 0,
  });
}

// ---------------------------------------------------------------------------
// PATCH /me — v2.5.7 (H-4): let the user set their own display name.
// Replaces the email-prefix default with a user-chosen handle; validated
// (length, no emails/URLs/control chars) and audit-noted via security event
// only on failure — a rename is not a security-relevant action.
// ---------------------------------------------------------------------------

const DISPLAY_NAME_RE = /^[\p{L}\p{N}][\p{L}\p{N} .'_-]{1,39}$/u;

export async function updateMe(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    displayName: { type: 'string', required: true, minLength: 2, maxLength: 40 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const displayName = (v.value.displayName as string).trim();

  // Reject anything that smells like an email, URL or contains separators.
  if (
    !DISPLAY_NAME_RE.test(displayName) ||
    /@|https?:|\/|\\|\|/.test(displayName)
  ) {
    return fail(
      c,
      'VALIDATION_FAILED',
      'displayName must be 2-40 letters/digits (dots, spaces, _ - allowed)',
      400
    );
  }

  const now = new Date().toISOString();
  await c.env.DB.prepare(
    'UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?'
  )
    .bind(displayName, now, c.user.userId)
    .run();

  const user = await c.env.DB.prepare('SELECT * FROM users WHERE id = ?')
    .bind(c.user.userId)
    .first<UserRow>();
  if (user === null) return fail(c, 'UNAUTHORIZED', 'User not found', 401);
  return ok(c, { user: toApiUser(user) });
}

// ---------------------------------------------------------------------------
// POST /devices/register
// ---------------------------------------------------------------------------

export async function devicesRegister(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    model: { type: 'string', required: true, minLength: 1, maxLength: 100 },
    manufacturer: { type: 'string', required: true, minLength: 1, maxLength: 100 },
    androidVersion: { type: 'string', required: true, minLength: 1, maxLength: 50 },
    appVersion: { type: 'string', required: true, minLength: 1, maxLength: 50 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const countRow = await c.env.DB.prepare(
    `SELECT COUNT(*) AS n FROM devices WHERE user_id = ? AND status != 'REMOVED'`
  )
    .bind(c.user.userId)
    .first<{ n: number }>();
  if ((countRow?.n ?? 0) >= MAX_DEVICES_PER_USER) {
    return fail(
      c,
      'CONFLICT',
      `Device limit reached (max ${MAX_DEVICES_PER_USER}). Remove a device first.`,
      409
    );
  }

  const id = `dev_${randomToken(10)}`;
  const now = new Date().toISOString();
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO devices (id, user_id, platform, manufacturer, model, android_version, app_version, registered_at, last_seen_at, status, permission_summary, active_session_flag)
       VALUES (?, ?, 'ANDROID', ?, ?, ?, ?, ?, ?, 'ACTIVE', '{}', 0)`
    )
      .bind(
        id,
        c.user.userId,
        v.value.manufacturer,
        v.value.model,
        v.value.androidVersion,
        v.value.appVersion,
        now,
        now
      ),
    // bind the registering session to this device (used for device-keyed
    // rate limits and flag rollout hashing)
    c.env.DB.prepare('UPDATE user_sessions SET device_id = ? WHERE token_hash = ?').bind(
      id,
      c.user.tokenHash
    ),
  ]);

  return ok(c, { deviceId: id });
}

// ---------------------------------------------------------------------------
// POST /devices/heartbeat
// ---------------------------------------------------------------------------

function normalizePermissionSummary(
  input: unknown
): {
  accessibility: boolean | null;
  usageAccess: boolean | null;
  overlay: boolean | null;
  notifications: boolean | null;
} {
  const src = isRecord(input) ? input : {};
  const pick = (key: string): boolean | null =>
    typeof src[key] === 'boolean' ? (src[key] as boolean) : null;
  return {
    accessibility: pick('accessibility'),
    usageAccess: pick('usageAccess'),
    overlay: pick('overlay'),
    notifications: pick('notifications'),
  };
}

export async function devicesHeartbeat(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    appVersion: { type: 'string', required: true, minLength: 1, maxLength: 50 },
    permissionSummary: { type: 'object', required: false },
    activeSession: { type: 'boolean', required: false },
    deviceId: { type: 'string', required: false, minLength: 8, maxLength: 64 },
    configVersion: { type: 'number', required: false, integer: true, min: 0, max: 1_000_000 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const permissionSummary = normalizePermissionSummary(v.value.permissionSummary);
  const deviceId =
    typeof v.value.deviceId === 'string' ? v.value.deviceId : c.user.deviceId;
  const now = new Date().toISOString();

  if (deviceId !== null) {
    const device = await c.env.DB.prepare('SELECT id, user_id FROM devices WHERE id = ?')
      .bind(deviceId)
      .first<{ id: string; user_id: string }>();
    if (device === null) return fail(c, 'NOT_FOUND', 'Device not found', 404);
    if (device.user_id !== c.user.userId) {
      return fail(c, 'FORBIDDEN', 'Device belongs to another user', 403);
    }
    await c.env.DB.prepare(
      `UPDATE devices SET app_version = ?, permission_summary = ?, active_session_flag = ?, last_seen_at = ?, status = 'ACTIVE'
       WHERE id = ?`
    )
      .bind(
        v.value.appVersion,
        JSON.stringify(permissionSummary),
        v.value.activeSession === true ? 1 : 0,
        now,
        deviceId
      )
      .run();
  }

  await c.env.DB.prepare('UPDATE users SET last_seen_at = ? WHERE id = ?')
    .bind(now, c.user.userId)
    .run();

  const [configRow, annRow] = await Promise.all([
    c.env.DB.prepare(
      `SELECT version FROM config_versions WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1`
    ).first<{ version: number }>(),
    c.env.DB.prepare(
      `SELECT COUNT(*) AS n FROM announcements
       WHERE status = 'ACTIVE' AND (start_at IS NULL OR start_at <= ?) AND (end_at IS NULL OR end_at >= ?)`
    )
      .bind(now, now)
      .first<{ n: number }>(),
  ]);

  const publishedVersion = configRow?.version ?? 0;
  // If the client reports its cached config version we can answer precisely;
  // without it we conservatively tell the client to re-check.
  const configChanged =
    typeof v.value.configVersion === 'number'
      ? v.value.configVersion !== publishedVersion
      : true;

  return ok(c, { configChanged, announcementCount: annRow?.n ?? 0 });
}

// ---------------------------------------------------------------------------
// GET /config — active published config (versioned)
// ---------------------------------------------------------------------------

export async function getConfig(c: Context): Promise<Response> {
  const row = await c.env.DB.prepare(
    `SELECT version, config_json FROM config_versions WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1`
  ).first<{ version: number; config_json: string }>();
  if (row === null) {
    return fail(c, 'SERVICE_UNAVAILABLE', 'No published configuration available', 503);
  }
  const config: Record<string, unknown> | null = ((): Record<string, unknown> | null => {
    try {
      const parsed: unknown = JSON.parse(row.config_json);
      return isRecord(parsed) ? parsed : null;
    } catch {
      return null;
    }
  })();
  if (config === null) {
    console.error(c.requestId, 'published config is not valid JSON, version', row.version);
    return fail(c, 'SERVER_ERROR', 'Configuration is corrupt', 500);
  }
  return ok(c, { version: row.version, config: config as unknown as ConfigDoc });
}

// ---------------------------------------------------------------------------
// GET /flags — per-device rollout: hash(deviceId+key) % 100 < rolloutPercentage
// ---------------------------------------------------------------------------

async function hashBucket(input: string): Promise<number> {
  const hex = await sha256Hex(input);
  return Number.parseInt(hex.slice(0, 8), 16) % 100;
}

function compareVersions(a: string, b: string): number {
  const pa = a.split('.');
  const pb = b.split('.');
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const na = Number.parseInt(pa[i] ?? '0', 10);
    const nb = Number.parseInt(pb[i] ?? '0', 10);
    // m19: an unparseable segment (garbage client appVersion or a legacy
    // garbage minimum_version) must fail CLOSED — treat as "too old" so the
    // `< 0` check in getFlags disables the flag instead of satisfying it.
    if (Number.isNaN(na) || Number.isNaN(nb)) return -1;
    if (na !== nb) return na < nb ? -1 : 1;
  }
  return 0;
}

export async function getFlags(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const rows = await c.env.DB.prepare(
    'SELECT key, enabled, rollout_percentage, minimum_version, environment FROM feature_flags'
  ).all<FeatureFlagRow>();

  const deviceKey = c.user.deviceId ?? c.user.userId;
  const appVersionParam = c.url.searchParams.get('appVersion');
  const flags: Record<string, boolean> = {};

  for (const row of rows.results) {
    let enabled = row.enabled === 1;
    // M4: both sides are lowercase ('development'|'staging'|'production',
    // matching the DB CHECK constraint) — no case folding here.
    if (enabled && row.environment !== 'ALL' && row.environment !== c.env.API_ENV) {
      enabled = false;
    }
    if (enabled && row.rollout_percentage < 100) {
      const bucket = await hashBucket(`${deviceKey}:${row.key}`);
      if (bucket >= row.rollout_percentage) enabled = false;
    }
    if (
      enabled &&
      appVersionParam !== null &&
      row.minimum_version !== null &&
      compareVersions(appVersionParam, row.minimum_version) < 0
    ) {
      enabled = false;
    }
    flags[row.key] = enabled;
  }

  return ok(c, { flags });
}

// ---------------------------------------------------------------------------
// GET /app/version — public (force-update must work pre-login)
// ---------------------------------------------------------------------------

export async function getAppVersion(c: Context): Promise<Response> {
  const row = await c.env.DB.prepare(
    'SELECT minimum, latest, force_update, message FROM app_versions ORDER BY id DESC LIMIT 1'
  ).first<{ minimum: string; latest: string; force_update: number; message: string | null }>();
  if (row === null) {
    return ok(c, { minimum: '1.0.0', latest: '1.0.0', forceUpdate: false, message: '' });
  }
  return ok(c, {
    minimum: row.minimum,
    latest: row.latest,
    forceUpdate: row.force_update === 1,
    message: row.message ?? '',
  });
}

// ---------------------------------------------------------------------------
// GET /announcements?since=<iso>
// ---------------------------------------------------------------------------

export async function getAnnouncements(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const sinceParam = c.url.searchParams.get('since');
  if (sinceParam !== null && Number.isNaN(Date.parse(sinceParam))) {
    return fail(c, 'VALIDATION_FAILED', 'since must be an ISO 8601 timestamp', 400);
  }

  const now = new Date().toISOString();
  const baseWhere = `status = 'ACTIVE' AND (start_at IS NULL OR start_at <= ?) AND (end_at IS NULL OR end_at >= ?)`;
  const rows =
    sinceParam === null
      ? await c.env.DB.prepare(
          `SELECT id, title, body, type, start_at, end_at FROM announcements
           WHERE ${baseWhere}
           ORDER BY created_at DESC LIMIT 100`
        )
          .bind(now, now)
          .all<{
            id: string;
            title: string;
            body: string;
            type: string;
            start_at: string | null;
            end_at: string | null;
          }>()
      : await c.env.DB.prepare(
          `SELECT id, title, body, type, start_at, end_at FROM announcements
           WHERE ${baseWhere} AND created_at > ?
           ORDER BY created_at DESC LIMIT 100`
        )
          .bind(now, now, sinceParam)
          .all<{
            id: string;
            title: string;
            body: string;
            type: string;
            start_at: string | null;
            end_at: string | null;
          }>();

  return ok(c, {
    announcements: rows.results.map((r) => ({
      id: r.id,
      title: r.title,
      body: r.body,
      type: r.type,
      startTime: r.start_at,
      endTime: r.end_at,
    })),
  });
}

// ---------------------------------------------------------------------------
// POST /events — idempotent batch insert (event_id PK + INSERT OR IGNORE)
// ---------------------------------------------------------------------------

interface ValidEvent {
  eventId: string;
  type: string;
  payloadJson: string;
  occurredAt: string;
}

export async function postEvents(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const body = await readJsonBody(c);
  if (!isRecord(body) || !Array.isArray(body.events)) {
    return fail(c, 'VALIDATION_FAILED', 'events must be an array', 400);
  }
  const incoming: unknown[] = body.events;
  if (incoming.length === 0 || incoming.length > MAX_EVENTS_PER_BATCH) {
    return fail(
      c,
      'VALIDATION_FAILED',
      `events must contain between 1 and ${MAX_EVENTS_PER_BATCH} items`,
      400
    );
  }

  const now = new Date().toISOString();
  const errors: string[] = [];
  const valid: ValidEvent[] = [];
  const seen = new Set<string>();

  for (let i = 0; i < incoming.length; i++) {
    const item: unknown = incoming[i];
    if (!isRecord(item)) {
      errors.push(`events[${i}]: must be an object`);
      continue;
    }
    if (typeof item.eventId !== 'string' || !EVENT_ID_RE.test(item.eventId)) {
      errors.push(`events[${i}]: invalid eventId (expected "evt_...")`);
      continue;
    }
    if (seen.has(item.eventId)) {
      errors.push(`events[${i}]: duplicate eventId within batch`);
      continue;
    }
    if (typeof item.type !== 'string' || !EVENT_TYPES.includes(item.type)) {
      errors.push(`events[${i}]: unknown event type`);
      continue;
    }
    let payloadJson = '{}';
    if (item.payload !== undefined && item.payload !== null) {
      if (!isRecord(item.payload)) {
        errors.push(`events[${i}]: payload must be an object`);
        continue;
      }
      payloadJson = JSON.stringify(item.payload);
      if (payloadJson.length > MAX_EVENT_PAYLOAD_JSON) {
        errors.push(`events[${i}]: payload too large (>${MAX_EVENT_PAYLOAD_JSON} chars)`);
        continue;
      }
    }
    let occurredAt = now;
    if (item.occurredAt !== undefined && item.occurredAt !== null) {
      if (typeof item.occurredAt !== 'string' || Number.isNaN(Date.parse(item.occurredAt))) {
        errors.push(`events[${i}]: occurredAt must be an ISO 8601 timestamp`);
        continue;
      }
      occurredAt = item.occurredAt;
    }
    seen.add(item.eventId);
    valid.push({ eventId: item.eventId, type: item.type, payloadJson, occurredAt });
  }

  if (errors.length > 0) return fail(c, 'VALIDATION_FAILED', errors.join('; '), 400);

  const user = c.user; // non-null after the guard above; captured for the closure
  const statements = valid.map((e) =>
    c.env.DB.prepare(
      `INSERT OR IGNORE INTO events (event_id, device_id, user_id, type, payload, occurred_at, received_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).bind(e.eventId, user.deviceId, user.userId, e.type, e.payloadJson, e.occurredAt, now)
  );
  const results = await c.env.DB.batch(statements);

  let accepted = 0;
  for (const r of results) {
    if ((r.meta.changes ?? 0) > 0) accepted++;
  }
  return ok(c, { accepted, duplicates: valid.length - accepted });
}

// ---------------------------------------------------------------------------
// Google Play subscription verification
// ---------------------------------------------------------------------------

class PlayApiError extends Error {
  constructor(
    public readonly kind: 'purchase_not_found' | 'play_unavailable',
    message: string
  ) {
    super(message);
  }
}

type PlayPurchase = Record<string, unknown>;

/**
 * Obtain an OAuth2 access token for the Play Developer API using a service
 * account JWT (RS256) — cached in KV until shortly before expiry.
 *
 * TODO(GOOGLE PLAY): the service account credentials must be provided via
 *   wrangler secrets put GOOGLE_PLAY_SA_EMAIL
 *   wrangler secrets put GOOGLE_PLAY_SA_PRIVATE_KEY   (PEM, PKCS#8)
 * and the service account must be linked to the Play Console app with
 * "View app information and download bulk reports" + financial permissions.
 * Until both secrets exist we return null and the route answers with an
 * honest SERVICE_UNAVAILABLE envelope — we never fake a verification result.
 */
// v2.5.7 (L-3): single-flight token fetch. Parallel requests with a cold
// KV cache used to each sign a JWT and hit Google's token endpoint at the
// same time (thundering herd) — wasteful and rate-limit-prone. Within one
// isolate, the first caller's promise is shared by every concurrent caller.
let gplayTokenInflight: Promise<string | null> | null = null;

async function getPlayAccessToken(env: Env): Promise<string | null> {
  const saEmail = env.GOOGLE_PLAY_SA_EMAIL;
  const saKey = env.GOOGLE_PLAY_SA_PRIVATE_KEY;
  if (!saEmail || !saKey) return null;

  const cached = await env.KV.get('gplay_access_token');
  if (cached !== null) return cached;

  if (gplayTokenInflight !== null) return gplayTokenInflight;
  gplayTokenInflight = (async () => {
    try {
      return await fetchPlayOauthToken(saEmail, saKey, env);
    } finally {
      gplayTokenInflight = null;
    }
  })();
  return gplayTokenInflight;
}

async function fetchPlayOauthToken(
  saEmail: string,
  saKey: string,
  env: Env
): Promise<string | null> {

  const nowSec = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = {
    iss: saEmail,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token',
    exp: nowSec + 3600,
    iat: nowSec,
  };
  const headerB64 = btoaJson(header);
  const claimsB64 = btoaJson(claims);
  const unsigned = `${headerB64}.${claimsB64}`;

  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey(
      'pkcs8',
      pemToDer(saKey),
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign']
    );
  } catch (err) {
    // M2: a malformed/mangled service-account key must degrade to the honest
    // SERVICE_UNAVAILABLE (null -> 503 in subscriptionVerify), never a 500.
    console.error('play service-account key import failed', err);
    return null;
  }
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(unsigned)
  );
  const jwt = `${unsigned}.${base64UrlEncode(new Uint8Array(signature))}`;

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt,
    }),
  });
  if (!res.ok) {
    console.error('play oauth token request failed with HTTP', res.status);
    return null;
  }
  const data: unknown = await res.json();
  if (!isRecord(data) || typeof data.access_token !== 'string') return null;
  const expiresIn = typeof data.expires_in === 'number' ? data.expires_in : 3600;
  await env.KV.put('gplay_access_token', data.access_token, {
    expirationTtl: Math.max(60, expiresIn - 60),
  });
  return data.access_token;
}

function btoaJson(value: unknown): string {
  return btoa(JSON.stringify(value))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

async function fetchPlayPurchase(
  accessToken: string,
  productId: string,
  purchaseToken: string
): Promise<PlayPurchase> {
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
    `${encodeURIComponent(PACKAGE_NAME)}/purchases/subscriptions/` +
    `${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (res.status === 400 || res.status === 404) {
    throw new PlayApiError('purchase_not_found', `Google Play returned HTTP ${res.status}`);
  }
  if (!res.ok) {
    throw new PlayApiError('play_unavailable', `Google Play returned HTTP ${res.status}`);
  }
  const data: unknown = await res.json();
  if (!isRecord(data)) {
    throw new PlayApiError('play_unavailable', 'Malformed Play API response');
  }
  return data;
}

function mapPlayStatus(purchase: PlayPurchase): string {
  const raw = purchase.expiryTimeMillis;
  const expiryMs = typeof raw === 'string' ? Number(raw) : typeof raw === 'number' ? raw : 0;
  if (Number.isFinite(expiryMs) && expiryMs > Date.now()) {
    return purchase.autoRenewing === false ? 'CANCELED' : 'ACTIVE';
  }
  return 'EXPIRED';
}

export async function subscriptionVerify(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const body = await readJsonBody(c);
  const v = validateFields(body, {
    productId: { type: 'string', required: true, minLength: 1, maxLength: 200 },
    purchaseToken: { type: 'string', required: true, minLength: 16, maxLength: 2048 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const productId = v.value.productId as string;
  const purchaseToken = v.value.purchaseToken as string;

  const accessToken = await getPlayAccessToken(c.env);
  if (accessToken === null) {
    return fail(
      c,
      'SERVICE_UNAVAILABLE',
      'Subscription verification is not configured (Google Play service account credentials missing)',
      503
    );
  }

  let purchase: PlayPurchase;
  try {
    purchase = await fetchPlayPurchase(accessToken, productId, purchaseToken);
  } catch (err) {
    if (err instanceof PlayApiError && err.kind === 'purchase_not_found') {
      return fail(c, 'NOT_FOUND', 'Purchase not found on Google Play', 404);
    }
    console.error(
      c.requestId,
      'play api error',
      err instanceof Error ? err.message : String(err)
    );
    return fail(c, 'SERVICE_UNAVAILABLE', 'Google Play API is temporarily unavailable', 503);
  }

  // v2.5.7 (W-7): the client binds the purchase to the account at LAUNCH time
  // via setObfuscatedAccountId(userId) — when Google echoes it back we verify
  // it belongs to the caller, closing the first-come-first-serve entitlement
  // grab (two app accounts sharing one Play account).
  const obfuscatedAccountId =
    typeof purchase.obfuscatedExternalAccountId === 'string'
      ? purchase.obfuscatedExternalAccountId
      : null;
  if (obfuscatedAccountId !== null && obfuscatedAccountId !== c.user.userId) {
    await appendSecurityEvent(c.env, c.user.userId, c.user.deviceId, 'PURCHASE_ACCOUNT_MISMATCH', 'HIGH', {
      requestId: c.requestId,
      purchaseTokenPrefix: purchaseToken.slice(0, 12),
    });
    return fail(
      c,
      'CONFLICT',
      'This purchase was made from a different app account — contact support',
      409
    );
  }

  const now = new Date().toISOString();
  const status = mapPlayStatus(purchase);
  const rawStart = purchase.startTimeMillis;
  const startDate =
    typeof rawStart === 'string' || typeof rawStart === 'number'
      ? new Date(Number(rawStart)).toISOString()
      : null;
  const rawExpiry = purchase.expiryTimeMillis;
  const expiryDate =
    typeof rawExpiry === 'string' || typeof rawExpiry === 'number'
      ? new Date(Number(rawExpiry)).toISOString()
      : null;

  await c.env.DB.prepare(
    `INSERT INTO subscriptions (id, user_id, product_id, purchase_token, plan, status, start_date, expiry_date, last_verified, created_at, updated_at)
     VALUES (?1, ?2, ?3, ?4, ?3, ?5, ?6, ?7, ?8, ?8, ?8)
     ON CONFLICT(purchase_token) DO UPDATE SET
       status = ?5,
       expiry_date = ?7,
       last_verified = ?8,
       updated_at = ?8,
       start_date = COALESCE(?6, start_date),
       product_id = ?3`
  )
    .bind(
      `sub_${randomToken(12)}`,
      c.user.userId,
      productId,
      purchaseToken,
      status,
      startDate,
      expiryDate,
      now
    )
    .run();

  // m3: the final read is user-scoped — never echo another account's
  // subscription row back to the caller.
  const row = await c.env.DB.prepare(
    'SELECT * FROM subscriptions WHERE purchase_token = ? AND user_id = ?'
  )
    .bind(purchaseToken, c.user.userId)
    .first<SubscriptionRow>();
  if (row === null) {
    const ownedByOther = await c.env.DB.prepare(
      'SELECT 1 AS one FROM subscriptions WHERE purchase_token = ? AND user_id != ?'
    )
      .bind(purchaseToken, c.user.userId)
      .first<{ one: number }>();
    if (ownedByOther !== null) {
      return fail(
        c,
        'CONFLICT',
        'This purchase is already linked to another account — contact support',
        409
      );
    }
  }
  return ok(c, { subscription: row !== null ? toApiSubscription(row) : null });
}

// ---------------------------------------------------------------------------
// GET /subscription
// ---------------------------------------------------------------------------

export async function getSubscription(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  let row = await c.env.DB.prepare(
    'SELECT * FROM subscriptions WHERE user_id = ? ORDER BY updated_at DESC LIMIT 1'
  )
    .bind(c.user.userId)
    .first<SubscriptionRow>();
  // M6: honesty — an ACTIVE row whose expiry has already passed is EXPIRED,
  // even when the 30-min cron has not flipped the stored status yet.
  if (
    row !== null &&
    row.status === 'ACTIVE' &&
    row.expiry_date !== null &&
    Date.parse(row.expiry_date) <= Date.now()
  ) {
    row = { ...row, status: 'EXPIRED' };
  }
  return ok(c, { subscription: row !== null ? toApiSubscription(row) : null });
}

// ---------------------------------------------------------------------------
// DELETE /me — soft deletion request
// ---------------------------------------------------------------------------

export async function deleteMe(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  // m6: idempotent short-circuit — repeated requests must not keep appending
  // append-only security_events rows and rewriting the pending timestamp.
  const existing = await c.env.DB.prepare(
    'SELECT deletion_pending_at FROM users WHERE id = ?'
  )
    .bind(c.user.userId)
    .first<{ deletion_pending_at: string | null }>();
  if (existing === null) return fail(c, 'UNAUTHORIZED', 'User not found', 401);
  if (existing.deletion_pending_at !== null) {
    return ok(c, { status: 'deletion_pending' });
  }
  const now = new Date().toISOString();
  await c.env.DB.prepare('UPDATE users SET deletion_pending_at = ?, updated_at = ? WHERE id = ?')
    .bind(now, now, c.user.userId)
    .run();
  await appendSecurityEvent(c.env, c.user.userId, c.user.deviceId, 'ACCOUNT_DELETION_REQUESTED', 'MEDIUM', {
    requestId: c.requestId,
  });
  return ok(c, { status: 'deletion_pending' });
}
