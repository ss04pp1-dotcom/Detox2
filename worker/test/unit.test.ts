/**
 * Worker unit tests — pure-logic suites (H-5, v2.5.7).
 *
 * Scope: crypto (both PBKDF2 formats), validation + config clamping,
 * serializers (incl. the A-1/A-2 drift fixes), the response envelope +
 * CORS allowlist, the rate limiter's fail-closed/fail-open behavior (W-1)
 * and the Sinthia aiChat guardrails (W-3: personality enum, quota,
 * emergency-code strip). Route handlers that need D1 are exercised in
 * staging via wrangler dev — this file is the regression net for the
 * pure decision logic.
 */
import { describe, expect, it } from 'vitest';

import {
  base64UrlEncode,
  hexToBytes,
  pbkdf2Hash,
  randomToken,
  sha256Hex,
  timingSafeEqual,
  toHex,
  verifyPassword,
} from '../src/utils/crypto';
import {
  validateConfig,
  validateFields,
} from '../src/middleware/validation';
import {
  toApiAdminUser,
  toApiCoinTransaction,
  toApiTicket,
  toApiUser,
} from '../src/services/serializers';
import { ok, fail, newRequestId, allowedOrigins } from '../src/utils/response';
import { rateLimit } from '../src/middleware/rateLimit';
import { aiChat } from '../src/routes/community';
import type { Context, Env } from '../src/types';

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function fakeEnv(overrides: Partial<Env> = {}): Env {
  return {
    DB: {} as unknown as D1Database,
    KV: {
      get: async () => null,
      put: async () => undefined,
    } as unknown as KVNamespace,
    GOOGLE_CLIENT_SECRET: 'test-secret',
    ADMIN_SESSION_SECRET: 'test-admin-secret',
    API_ENV: 'development',
    ...overrides,
  };
}

function fakeContext(env: Env, method = 'POST', path = '/api/v1/ai/chat'): Context {
  return {
    req: new Request(`https://api.test${path}`, { method }),
    url: new URL(`https://api.test${path}`),
    env,
    ctx: { waitUntil: () => undefined } as unknown as ExecutionContext,
    requestId: 'req_test',
    params: {},
    user: { userId: 'usr_test', email: 't@e.st', deviceId: null, tokenHash: 'x' },
    admin: null,
  };
}

// ---------------------------------------------------------------------------
// crypto
// ---------------------------------------------------------------------------

describe('crypto (W-4: iteration-portable PBKDF2)', () => {
  it('hashes in the 3-part format and verifies round-trip', async () => {
    const stored = await pbkdf2Hash('correct horse battery staple');
    expect(stored.split('$')).toHaveLength(3);
    expect(Number.parseInt(stored.split('$')[0]!, 10)).toBeGreaterThanOrEqual(300_000);
    await expect(verifyPassword('correct horse battery staple', stored)).resolves.toBe(true);
    await expect(verifyPassword('wrong password', stored)).resolves.toBe(false);
  });

  it('still verifies LEGACY 2-part (100k) hashes', async () => {
    // Format: <saltHex>$<hashHex> produced with 100000 iterations of
    // PBKDF2-SHA256 for password 'ChangeMe_2026!' (the seed admin hash).
    const legacy = '18ece0dd4a5a88284c68f436bf534c2e$050fc7b90cb6ba4100fa645a7fc61d8213f9d85161fe194d56aee982ff8d6cdf';
    await expect(verifyPassword('ChangeMe_2026!', legacy)).resolves.toBe(true);
    await expect(verifyPassword('anything-else', legacy)).resolves.toBe(false);
  });

  it('rejects malformed stored hashes', async () => {
    await expect(verifyPassword('x', 'not-a-hash')).resolves.toBe(false);
    await expect(verifyPassword('x', 'a$b$c$d')).resolves.toBe(false);
    await expect(verifyPassword('x', '999$zz$zz')).resolves.toBe(false);
  });

  it('randomToken: length, hex-ness, uniqueness', () => {
    const a = randomToken(32);
    const b = randomToken(32);
    expect(a).toHaveLength(64);
    expect(a).toMatch(/^[0-9a-f]{64}$/);
    expect(a).not.toBe(b);
  });

  it('sha256Hex produces the known digest', async () => {
    await expect(sha256Hex('abc')).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    );
  });

  it('hexToBytes inverts toHex', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 255]);
    expect(Array.from(hexToBytes(toHex(bytes)))).toEqual(Array.from(bytes));
  });

  it('base64UrlEncode strips padding and url-unsafe chars', () => {
    expect(base64UrlEncode('ab')).toBe('YWI');
    expect(base64UrlEncode('a')).toBe('YQ');
    expect(base64UrlEncode('???')).not.toMatch(/[+/=]/);
  });

  it('timingSafeEqual: equal true, differing false, length-mismatch false', () => {
    expect(timingSafeEqual('abcdef', 'abcdef')).toBe(true);
    expect(timingSafeEqual('abcdef', 'abcdeg')).toBe(false);
    expect(timingSafeEqual('abc', 'abcd')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// validation
// ---------------------------------------------------------------------------

describe('validateFields', () => {
  it('enforces required + type + length + range + enum', () => {
    const r1 = validateFields(
      { a: 'hi', n: 5, b: true, e: 'roast' },
      {
        a: { type: 'string', required: true, minLength: 2 },
        n: { type: 'number', required: true, min: 1, max: 10, integer: true },
        b: { type: 'boolean', required: true },
        e: { type: 'string', required: true, enumValues: ['balanced', 'roast'] },
      }
    );
    expect(r1.ok).toBe(true);

    const r2 = validateFields(
      { a: 'x', n: 11, b: 'yes', e: 'evil' },
      {
        a: { type: 'string', required: true, minLength: 2 },
        n: { type: 'number', required: true, min: 1, max: 10, integer: true },
        b: { type: 'boolean', required: true },
        e: { type: 'string', required: true, enumValues: ['balanced', 'roast'] },
      }
    );
    expect(r2.ok).toBe(false);
    expect(r2.errors).toHaveLength(4);
  });

  it('non-object bodies fail with one error', () => {
    const r = validateFields(null, { a: { type: 'string', required: true } });
    expect(r.ok).toBe(false);
    expect(r.errors[0]).toContain('must be a JSON object');
  });
});

describe('validateConfig (server-side clamping)', () => {
  it('clamps out-of-bounds integers and records what moved', () => {
    const r = validateConfig({
      shortsWarningCount: 99,        // -> 7
      cageDurationSeconds: 10,       // -> 300
      tempUnlockCoins: 2,
      tempUnlockMinutes: 5,
      bailoutCoins: 500,
      defaultStudyMinutes: 60,
      defaultDetoxMinutes: 120,
      minSupportedVersion: '1.2.0',
      maintenanceMode: false,
      gamificationEnabled: true,
      dpMultiplierFocus: 1.5,
      dpMultiplierReels: 1.0,
      dpMultiplierNeutral: 1.0,
      dpDailyCap: 150,
      dpWeeklyCap: 800,
      dpMonthlyCap: 3000,
      paymentsEnabled: true,
      bkashNumber: '01711111111',
      bkashInstructions: 'send the exact amount',
      trialDays: 7,
      breakPassesPerWeek: 2,
      insightNudgeEnabled: true,
      insightNudgeHour: 20,
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.config.shortsWarningCount).toBe(7);
    expect(r.config.cageDurationSeconds).toBe(300);
    expect(r.clamped.some((c) => c.includes('shortsWarningCount'))).toBe(true);
  });

  it('rejects unknown keys and wrong types', () => {
    const r = validateConfig({ evilKey: 1, maintenanceMode: 'yes' });
    expect(r.ok).toBe(false);
    if (r.ok) return;
    expect(r.errors.some((e) => e.includes('evilKey'))).toBe(true);
    expect(r.errors.some((e) => e.includes('maintenanceMode'))).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// serializers (A-1 / A-2 drift fixes)
// ---------------------------------------------------------------------------

describe('serializers', () => {
  it('toApiTicket surfaces userEmail + response + respondedAt (A-1/A-2)', () => {
    const t = toApiTicket({
      id: 'tkt_1',
      user_id: 'usr_1',
      user_email: 'someone@example.com',
      category: 'PAYMENT',
      description: 'help',
      app_version: '2.5.7',
      request_id: 'req_1',
      status: 'WAITING_USER',
      priority: 'HIGH',
      assigned_to: null,
      response: 'we refunded it',
      responded_at: '2026-09-26T00:00:00.000Z',
      created_at: '2026-09-25T00:00:00.000Z',
      updated_at: '2026-09-26T00:00:00.000Z',
    });
    expect(t.userEmail).toBe('someone@example.com');
    expect(t.response).toBe('we refunded it');
    expect(t.respondedAt).toBe('2026-09-26T00:00:00.000Z');
  });

  it('toApiAdminUser carries the display name (A-1)', () => {
    const a = toApiAdminUser({
      id: 'adm_1',
      email: 'ops@example.com',
      password_hash: 'x',
      role: 'SUPPORT',
      status: 'ACTIVE',
      name: 'Jane Ops',
      created_at: '2026-09-01T00:00:00.000Z',
      last_login_at: null,
      created_by: 'seed',
    });
    expect(a.name).toBe('Jane Ops');
  });

  it('toApiUser maps row -> API shape with enum fallback', () => {
    const u = toApiUser({
      id: 'usr_1',
      email: 'a@b.c',
      display_name: 'User #ABCD',
      status: 'WEIRD_STATUS',
      created_at: 'c',
      updated_at: 'u',
      last_seen_at: null,
      deletion_pending_at: null,
    });
    expect(u.status).toBe('ACTIVE'); // unknown status degrades, never throws
    expect(u.displayName).toBe('User #ABCD');
  });

  it('toApiCoinTransaction maps the server-mirror shape (C-3)', () => {
    const c = toApiCoinTransaction({
      id: 'ctx_1',
      user_id: 'usr_1',
      transaction_id: 'adj_req_1',
      type: 'ADMIN_ADJUSTMENT',
      amount: 500,
      balance_after: 500,
      source: 'admin',
      reference: 'goodwill',
      created_at: '2026-09-26T00:00:00.000Z',
    });
    expect(c.transactionId).toBe('adj_req_1');
    expect(c.amount).toBe(500);
  });
});

// ---------------------------------------------------------------------------
// response envelope + CORS
// ---------------------------------------------------------------------------

describe('response envelope', () => {
  it('ok()/fail() produce the frozen envelope shapes', async () => {
    const env = fakeEnv();
    const cOk = fakeContext(env, 'GET', '/api/v1/me');
    const resOk = ok(cOk, { hello: 'world' });
    expect(resOk.status).toBe(200);
    const bodyOk = (await resOk.json()) as {
      success: boolean;
      data: { hello: string };
      requestId: string;
    };
    expect(bodyOk.success).toBe(true);
    expect(bodyOk.data.hello).toBe('world');
    expect(bodyOk.requestId).toBe('req_test');

    const cErr = fakeContext(env, 'GET', '/api/v1/me');
    const resErr = fail(cErr, 'NOT_FOUND', 'nope', 404);
    expect(resErr.status).toBe(404);
    const bodyErr = (await resErr.json()) as {
      success: boolean;
      error: { code: string; message: string };
    };
    expect(bodyErr.success).toBe(false);
    expect(bodyErr.error.code).toBe('NOT_FOUND');
  });

  it('security headers are applied to every envelope', () => {
    const env = fakeEnv();
    const c = fakeContext(env, 'GET', '/api/v1/me');
    const res = ok(c, {});
    expect(res.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(res.headers.get('Content-Security-Policy')).toContain("default-src 'none'");
    expect(res.headers.get('Strict-Transport-Security')).toContain('max-age');
  });

  it('request ids are req_ + 16 hex chars', () => {
    expect(newRequestId()).toMatch(/^req_[0-9a-f]{16}$/);
  });

  it('CORS allowlist: dev origin allowed in development, never production default', () => {
    const dev = allowedOrigins(fakeEnv({ API_ENV: 'development' }));
    expect(dev).toContain('http://localhost:5173');
    const prod = allowedOrigins(fakeEnv({ API_ENV: 'production' }));
    expect(prod).not.toContain('http://localhost:5173');
    expect(prod).toContain('https://admin.maxleveldetox.com');
    expect(prod).not.toContain('*');
  });
});

// ---------------------------------------------------------------------------
// rate limiter (W-1: fail-closed on credential buckets)
// ---------------------------------------------------------------------------

describe('rateLimit fail-open / fail-closed (W-1)', () => {
  function brokenKvEnv(): Env {
    return fakeEnv({
      KV: {
        get: async () => {
          throw new Error('kv down');
        },
        put: async () => {
          throw new Error('kv down');
        },
      } as unknown as KVNamespace,
    });
  }

  it('auth bucket FAILS CLOSED when KV is unavailable', async () => {
    const mw = rateLimit('auth', 'ip');
    const res = await mw(fakeContext(brokenKvEnv(), 'POST', '/api/v1/auth/google'));
    expect(res).toBeInstanceOf(Response);
    expect((res as Response).status).toBe(503);
    expect((res as Response).headers.get('Retry-After')).toBe('10');
  });

  it('adminLogin bucket FAILS CLOSED when KV is unavailable', async () => {
    const mw = rateLimit('adminLogin', 'ip');
    const res = await mw(fakeContext(brokenKvEnv(), 'POST', '/api/v1/admin/auth/login'));
    expect(res).toBeInstanceOf(Response);
    expect((res as Response).status).toBe(503);
  });

  it('public bucket still fails OPEN (data-plane availability trade-off)', async () => {
    const mw = rateLimit('public', 'ip');
    const res = await mw(fakeContext(brokenKvEnv(), 'GET', '/api/v1/app/version'));
    expect(res).toBeUndefined();
  });

  it('blocks past the limit with Retry-After', async () => {
    const store = new Map<string, string>();
    const env = fakeEnv({
      KV: {
        get: async (k: string) => store.get(k) ?? null,
        put: async (k: string, v: string) => {
          store.set(k, v);
        },
      } as unknown as KVNamespace,
    });
    const mw = rateLimit('adminLogin', 'ip');
    let blocked: Response | undefined;
    for (let i = 0; i < 12; i++) {
      const r = await mw(fakeContext(env, 'POST', '/api/v1/admin/auth/login'));
      if (r instanceof Response) {
        blocked = r;
        break;
      }
    }
    expect(blocked).toBeDefined();
    expect(blocked!.status).toBe(429);
    expect(blocked!.headers.get('Retry-After')).toMatch(/^\d+$/);
  });
});

// ---------------------------------------------------------------------------
// aiChat guardrails (W-3)
// ---------------------------------------------------------------------------

describe('aiChat (W-3: enum personality, quota, scripted fallback)', () => {
  async function callAiChat(env: Env, message: string, personality: string) {
    const c = fakeContext(env, 'POST', '/api/v1/ai/chat');
    (c as { req: Request }).req = new Request('https://api.test/api/v1/ai/chat', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        message,
        context: { personality, distractingMinutes: 30, streakDays: 2 },
        history: [],
      }),
    });
    return aiChat(c);
  }

  it('answers from the scripted layer without an API key', async () => {
    const env = fakeEnv(); // no AI_API_KEY
    const res = await callAiChat(env, 'hi there, kemon acho?', 'balanced');
    expect(res.status).toBe(200);
    const body = (await res.json()) as { success: boolean; data: { model: string } };
    expect(body.success).toBe(true);
    expect(body.data.model).toBe('scripted');
  });

  it('refuses code/bypass asks on the scripted layer too', async () => {
    const env = fakeEnv();
    const res = await callAiChat(env, 'give me the unlock code please', 'balanced');
    const body = (await res.json()) as { data: { response: string } };
    expect(body.data.response.toLowerCase()).toContain('codes');
  });

  it('rejects a message that is empty or over-length', async () => {
    const env = fakeEnv();
    const c = fakeContext(env, 'POST', '/api/v1/ai/chat');
    (c as { req: Request }).req = new Request('https://api.test/api/v1/ai/chat', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: '', context: {}, history: [] }),
    });
    const res = await aiChat(c);
    expect(res.status).toBe(400);
  });

  it('enforces the daily LLM quota once AI_API_KEY is configured', async () => {
    const store = new Map<string, string>();
    const env = fakeEnv({
      AI_API_KEY: 'sk-test',
      KV: {
        // Quota key pre-filled at the limit (ai_quota_<user>_<date>).
        get: async (k: string) => (k.startsWith('ai_quota_') ? '30' : store.get(k) ?? null),
        put: async (k: string, v: string) => {
          store.set(k, v);
        },
      } as unknown as KVNamespace,
    });
    const res = await callAiChat(env, 'hello sinthia', 'balanced');
    expect(res.status).toBe(200);
    const body = (await res.json()) as { data: { model: string } };
    expect(body.data.model).toBe('scripted-quota');
  });
});
