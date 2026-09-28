/**
 * MAXLEVEL DETOX — cryptography helpers (Web Crypto API only, zero deps).
 *
 * - Tokens: 32 random bytes, hex encoded (64 chars).
 * - Storage: only SHA-256(token) is ever stored in D1/KV.
 * - Passwords: PBKDF2-SHA256, stored as "<iterations>$<saltHex>$<hashHex>"
 *   (v2.5.7 / W-4: iterations are part of the stored string so they can be
 *   raised without invalidating existing hashes — legacy 2-part strings
 *   from the 100k era still verify).
 * - Timing-safe comparison for secrets.
 */

/**
 * v2.5.7 (W-4): 300 000 iterations — OWASP recommends 600k for
 * PBKDF2-SHA256 but Workers CPU limits make that impractical on the login
 * hot path; 300k is a 3x hardening over the previous 100k while keeping
 * verify latency acceptable for the low-volume admin login route.
 */
const PBKDF2_ITERATIONS = 300_000;
/** Legacy iteration count — implicit for 2-part stored hashes. */
const PBKDF2_LEGACY_ITERATIONS = 100_000;
const SALT_BYTES = 16;
const KEY_BYTES = 32;

const encoder = new TextEncoder();

export function toHex(bytes: Uint8Array): string {
  let hex = '';
  for (let i = 0; i < bytes.length; i++) hex += bytes[i].toString(16).padStart(2, '0');
  return hex;
}

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.length % 2 === 0 ? hex : `0${hex}`;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = Number.parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/** Cryptographically random token, hex encoded (default 32 bytes = 64 chars). */
export function randomToken(bytes = 32): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return toHex(buf);
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(input));
  return toHex(new Uint8Array(digest));
}

export async function hmacSha256Hex(input: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(input));
  return toHex(new Uint8Array(sig));
}

/** Constant-time string comparison (same-length hex strings). */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<string> {
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    encoder.encode(password),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    keyMaterial,
    KEY_BYTES * 8
  );
  return toHex(new Uint8Array(bits));
}

/**
 * Hash a password with PBKDF2-SHA256.
 * Returns the portable string "<iterations>$<saltHex>$<hashHex>" (v2.5.7).
 * Regenerate helper (README has the full command):
 *   node -e "..." with crypto.webcrypto.subtle PBKDF2, 300000 iters, 16B salt, 32B key.
 */
export async function pbkdf2Hash(password: string, iterations = PBKDF2_ITERATIONS): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const hash = await pbkdf2(password, salt, iterations);
  return `${iterations}$${toHex(salt)}$${hash}`;
}

/**
 * Verify a password against a stored value. Accepts BOTH formats:
 *   v2.5.7+: "<iterations>$<saltHex>$<hashHex>"
 *   legacy:  "<saltHex>$<hashHex>" (100 000 iterations)
 */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split('$');
  if (parts.length !== 3 && parts.length !== 2) return false;
  const legacy = parts.length === 2;
  const iterations = legacy
    ? PBKDF2_LEGACY_ITERATIONS
    : Number.parseInt(parts[0], 10);
  if (!Number.isInteger(iterations) || iterations < 1_000 || iterations > 10_000_000) {
    return false;
  }
  const [saltHex, expected] = legacy
    ? [parts[0].toLowerCase(), parts[1].toLowerCase()]
    : [parts[1].toLowerCase(), parts[2].toLowerCase()];
  if (!/^[0-9a-f]{2,128}$/.test(saltHex) || !/^[0-9a-f]{2,128}$/.test(expected)) return false;
  const actual = await pbkdf2(password, hexToBytes(saltHex), iterations);
  return timingSafeEqual(actual, expected);
}

/** base64url encode (for JWTs) from a string or byte array. */
export function base64UrlEncode(data: Uint8Array | string): string {
  let bin: string;
  if (typeof data === 'string') {
    bin = data;
  } else {
    bin = '';
    for (let i = 0; i < data.length; i++) bin += String.fromCharCode(data[i]);
  }
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Parse a PKCS#8 PEM private key into DER bytes (for RS256 signing). */
export function pemToDer(pem: string): Uint8Array {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, '')
    .replace(/-----END PRIVATE KEY-----/g, '')
    .replace(/\s+/g, '');
  const bin = atob(b64);
  const der = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) der[i] = bin.charCodeAt(i);
  return der;
}
