/**
 * MAXLEVEL DETOX — KV sliding-window rate limiting.
 *
 * Frozen contract v1 limits:
 *   /auth/*      10/min/IP
 *   /events      60/min/device
 *   /heartbeat    4/min/device
 *   /config,/flags 30/min/device
 *   admin       120/min/admin
 *
 * Algorithm: sliding-window counter — the weighted count of the previous
 * 60s window is combined with the current window count, giving a smooth
 * approximation of a true sliding window with only 2 KV reads + 1 KV write.
 * Keys: rl:<bucket>:<windowIndex> with a TTL of ~2 windows.
 *
 * Ordering rules (frozen contract):
 *   - rate limit BEFORE auth on /auth/*
 *   - rate limit AFTER device auth on /events and /heartbeat
 *   - admin limits run AFTER adminAuth (keyed by adminId)
 *
 * If KV is unavailable the limiter FAILS OPEN (logs a warning) so an
 * infrastructure hiccup cannot take the whole API down. This is a documented
 * availability-over-strictness tradeoff; see README security notes.
 */

import { Context, Middleware } from '../types';
import { fail } from '../utils/response';

export interface RateLimitRule {
  limit: number;
  windowMs: number;
}

/** Frozen rate limits (per 60s window). */
export const RATE_LIMITS = {
  auth: { limit: 10, windowMs: 60_000 },
  events: { limit: 60, windowMs: 60_000 },
  heartbeat: { limit: 4, windowMs: 60_000 },
  config: { limit: 30, windowMs: 60_000 },
  admin: { limit: 120, windowMs: 60_000 },
  /** Extra brute-force guard on the admin login endpoint (per IP). */
  adminLogin: { limit: 10, windowMs: 60_000 },
  /** Generic per-IP bucket for unauthenticated public routes. */
  public: { limit: 30, windowMs: 60_000 },
} as const;

/**
 * v2.5.7 (W-1): buckets that FAIL CLOSED when KV is unavailable. These are
 * the credential-bearing routes where an infrastructure hiccup must not
 * open a brute-force window. Everything else still fails open (documented
 * availability-over-strictness tradeoff for the data-plane routes).
 */
const STRICT_BUCKETS: ReadonlySet<RateLimitName> = new Set(['auth', 'adminLogin', 'admin'] as const);

export type RateLimitName = keyof typeof RATE_LIMITS;

/** Client IP as seen by Cloudflare (fallback chain for local dev). */
export function clientIp(c: Context): string {
  const cf = c.req.headers.get('CF-Connecting-IP');
  if (cf !== null && cf.length > 0) return cf;
  const xff = c.req.headers.get('X-Forwarded-For');
  if (xff !== null) {
    const first = xff.split(',')[0]?.trim();
    if (first !== undefined && first.length > 0) return first;
  }
  return 'unknown';
}

function rateLimitKey(name: RateLimitName, keyBy: 'ip' | 'device' | 'admin', c: Context): string {
  let subject: string;
  switch (keyBy) {
    case 'ip':
      subject = `ip:${clientIp(c)}`;
      break;
    case 'device':
      // Session device id, falling back to user id, then IP.
      subject = `dev:${c.user?.deviceId ?? c.user?.userId ?? clientIp(c)}`;
      break;
    case 'admin':
      subject = `adm:${c.admin?.adminId ?? clientIp(c)}`;
      break;
  }
  return `${name}:${subject}`;
}

/**
 * Build a rate-limit middleware.
 * @param name  one of the frozen buckets (see RATE_LIMITS)
 * @param keyBy what identifies the caller at this point in the chain
 */
export function rateLimit(name: RateLimitName, keyBy: 'ip' | 'device' | 'admin'): Middleware {
  const { limit, windowMs } = RATE_LIMITS[name];
  return async (c: Context): Promise<Response | void> => {
    const bucket = rateLimitKey(name, keyBy, c);
    const now = Date.now();
    const currentWindow = Math.floor(now / windowMs);
    const previousWindow = currentWindow - 1;
    const currentKey = `rl:${bucket}:${currentWindow}`;
    const previousKey = `rl:${bucket}:${previousWindow}`;

    try {
      const [currentRaw, previousRaw] = await Promise.all([
        c.env.KV.get(currentKey),
        c.env.KV.get(previousKey),
      ]);
      const current = currentRaw === null ? 0 : Number(currentRaw);
      const previous = previousRaw === null ? 0 : Number(previousRaw);

      const elapsedInWindow = now - currentWindow * windowMs;
      const previousWeight = 1 - elapsedInWindow / windowMs;
      const effective = current + previous * previousWeight;

      if (effective >= limit) {
        const retryAfterSec = Math.max(1, Math.ceil((windowMs - elapsedInWindow) / 1000));
        return fail(c, 'RATE_LIMITED', `Rate limit exceeded for ${name}`, 429, {
          'Retry-After': String(retryAfterSec),
        });
      }

      await c.env.KV.put(currentKey, String(current + 1), {
        expirationTtl: Math.ceil((windowMs * 2) / 1000),
      });
      return void 0;
    } catch (err) {
      // v2.5.7 (W-1): credential-bearing buckets fail CLOSED — a KV outage
      // must not become a brute-force window on /auth/* or the admin panel.
      if (STRICT_BUCKETS.has(name)) {
        console.error(c.requestId, 'rate limiter KV unavailable, failing CLOSED for', name, err);
        return fail(
          c,
          'SERVICE_UNAVAILABLE',
          'Rate limiter is temporarily unavailable — try again shortly',
          503,
          { 'Retry-After': '10' }
        );
      }
      console.warn(c.requestId, 'rate limiter KV unavailable, failing open', name, err);
      return void 0;
    }
  };
}
