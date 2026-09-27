/**
 * MAXLEVEL DETOX — response envelope, CORS and security headers.
 *
 * Frozen contract v1:
 *   success: {"success":true,"data":{...},"requestId":"req_<16hex>"}
 *   error:   {"success":false,"error":{"code":"<CODE>","message":"..."},"requestId":"req_<16hex>"}
 *
 * EVERY response goes through ok()/fail() (or preflightResponse for OPTIONS)
 * so the envelope, security headers and CORS allowlist are applied uniformly.
 * No wildcard CORS. No stack traces in responses. Ever.
 */

import { Context, Env, ErrorCode } from '../types';

export const DEFAULT_ADMIN_ORIGIN = 'https://admin.maxleveldetox.com';
export const DEV_ADMIN_ORIGIN = 'http://localhost:5173';

/** Generate a request id: "req_" + 16 hex chars (8 random bytes). */
export function newRequestId(): string {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  let hex = '';
  for (let i = 0; i < bytes.length; i++) hex += bytes[i].toString(16).padStart(2, '0');
  return `req_${hex}`;
}

/**
 * CORS origin allowlist. Sources:
 *  - the ADMIN_ORIGIN env var (comma-separated), and
 *  - the default admin origin, and
 *  - http://localhost:5173 (caprilegacy local admin dev) in development only.
 * There is never a "*" — the browser Origin header must match exactly.
 */
export function allowedOrigins(env: Env): string[] {
  const list: string[] = [DEFAULT_ADMIN_ORIGIN];
  if (env.API_ENV === 'development') list.push(DEV_ADMIN_ORIGIN);
  if (env.ADMIN_ORIGIN) {
    for (const raw of env.ADMIN_ORIGIN.split(',')) {
      const origin = raw.trim();
      if (origin.length > 0) list.push(origin);
    }
  }
  return [...new Set(list)];
}

function applySecurityHeaders(headers: Headers): void {
  headers.set('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'");
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  headers.set('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
}

function applyCorsHeaders(headers: Headers, req: Request, env: Env): void {
  const origin = req.headers.get('Origin');
  if (origin !== null && allowedOrigins(env).includes(origin)) {
    headers.set('Access-Control-Allow-Origin', origin);
    headers.set('Vary', 'Origin');
  }
}

/** Combined base headers: content type + security + conditional CORS. */
export function buildHeaders(
  c: Pick<Context, 'req' | 'env' | 'requestId'>,
  extra?: Record<string, string>
): Headers {
  const headers = new Headers(extra);
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('X-Request-Id', c.requestId);
  applySecurityHeaders(headers);
  applyCorsHeaders(headers, c.req, c.env);
  return headers;
}

/** JSON response with the frozen envelope applied by ok()/fail(). */
export function jsonResponse(
  c: Pick<Context, 'req' | 'env' | 'requestId'>,
  body: unknown,
  status: number,
  extraHeaders?: Record<string, string>
): Response {
  return new Response(JSON.stringify(body), { status, headers: buildHeaders(c, extraHeaders) });
}

/** Success envelope: {"success":true,"data":...,"requestId":...} */
export function ok(c: Context, data: unknown, status = 200): Response {
  return jsonResponse(c, { success: true, data, requestId: c.requestId }, status);
}

/** Error envelope: {"success":false,"error":{"code","message"},"requestId":...} */
export function fail(
  c: Context,
  code: ErrorCode,
  message: string,
  status: number,
  extraHeaders?: Record<string, string>
): Response {
  return jsonResponse(
    c,
    { success: false, error: { code, message }, requestId: c.requestId },
    status,
    extraHeaders
  );
}

/** CORS preflight for the admin SPA. 204 with allowlisted CORS headers only. */
export function preflightResponse(c: Context): Response {
  const headers = new Headers();
  applySecurityHeaders(headers);
  const origin = c.req.headers.get('Origin');
  if (origin !== null && allowedOrigins(c.env).includes(origin)) {
    headers.set('Access-Control-Allow-Origin', origin);
    headers.set('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
    headers.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    headers.set('Access-Control-Max-Age', '86400');
    headers.set('Vary', 'Origin');
  }
  return new Response(null, { status: 204, headers });
}
