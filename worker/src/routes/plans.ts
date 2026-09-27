/**
 * MAXLEVEL DETOX — plan catalog + device-bound free trial (v2.2 Phase D).
 *
 * App routes:
 *   GET  /api/v1/plans         active plan catalog (+ payments/trial config)
 *   GET  /api/v1/trial         trial eligibility / active status for this user
 *   POST /api/v1/trial/claim   claim the one-per-device free trial
 *
 * Security notes:
 *  - The trial is bound to the requesting DEVICE (trial_claims.device_id is
 *    UNIQUE). Unregistered sessions bind to the user id — still one claim.
 *  - `paymentsEnabled=false` in remote config is a hard kill-switch for the
 *    claim route (the same switch that disables the bKash gateway).
 *  - A user with an ACTIVE subscription cannot stack a trial on top.
 *  - All D1 statements are parameterized.
 */

import {
  Context,
  Plan,
  PlanKind,
  PlanSource,
  SubscriptionPlanRow,
  TrialClaimRow,
} from '../types';
import { ok, fail } from '../utils/response';
import { randomToken } from '../utils/crypto';
import { readJsonBody, validateFields } from '../middleware/validation';
import { readPublishedConfig } from '../services/config';
import { toApiPlan } from '../services/serializers';
import { clientIp } from '../middleware/rateLimit';
import { appendSecurityEvent } from '../services/audit';

const PLAN_KINDS: readonly PlanKind[] = ['monthly', 'quarterly', 'semiannual', 'yearly', 'trial'];
const PLAN_SOURCES: readonly PlanSource[] = ['play', 'bkash'];

function toPlan(row: SubscriptionPlanRow): Plan {
  return toApiPlan(row, PLAN_KINDS, PLAN_SOURCES);
}

// ---------------------------------------------------------------------------
// GET /plans
// ---------------------------------------------------------------------------

export async function getPlans(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const rows = await c.env.DB.prepare(
    `SELECT * FROM subscription_plans WHERE is_active = 1 ORDER BY sort_order ASC, id ASC`
  ).all<SubscriptionPlanRow>();

  const config = await readPublishedConfig(c.env);

  return ok(c, {
    plans: rows.results.map(toPlan),
    config: {
      // m13: `?? false` keeps /plans consistent with the actual gateway
      // kill-switch when no published config exists at all.
      paymentsEnabled: config?.paymentsEnabled ?? false,
      trialDays: config?.trialDays ?? 7,
    },
  });
}

// ---------------------------------------------------------------------------
// Trial helpers
// ---------------------------------------------------------------------------

/** Deterministic device key: the registered device when present, else the user. */
function effectiveDeviceKey(c: Context): string {
  const user = c.user;
  if (user === null) throw new Error('unauthenticated');
  return user.deviceId ?? `uid_${user.userId}`;
}

interface TrialView {
  eligible: boolean;
  active: boolean;
  claimedAt: string | null;
  expiresAt: string | null;
}

async function trialViewFor(c: Context): Promise<TrialView> {
  const claim = await c.env.DB.prepare(
    'SELECT * FROM trial_claims WHERE device_id = ?'
  )
    .bind(effectiveDeviceKey(c))
    .first<TrialClaimRow>();

  if (claim === null) {
    return { eligible: true, active: false, claimedAt: null, expiresAt: null };
  }
  const active = Date.parse(claim.expires_at) > Date.now();
  return {
    eligible: false,
    active,
    claimedAt: claim.claimed_at,
    expiresAt: claim.expires_at,
  };
}

// ---------------------------------------------------------------------------
// GET /trial
// ---------------------------------------------------------------------------

export async function getTrial(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  const config = await readPublishedConfig(c.env);
  const trial = await trialViewFor(c);
  return ok(c, {
    trial,
    trialDays: config?.trialDays ?? 7,
    // m13: `?? false` keeps /trial consistent with the actual gateway
    // kill-switch when no published config exists at all.
    paymentsEnabled: config?.paymentsEnabled ?? false,
  });
}

// ---------------------------------------------------------------------------
// POST /trial/claim
// ---------------------------------------------------------------------------

export async function claimTrial(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body ?? {}, {
    confirm: { type: 'boolean', required: true },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (v.value.confirm !== true) {
    return fail(c, 'INVALID_REQUEST', 'confirm must be true', 400);
  }

  const config = await readPublishedConfig(c.env);
  if (config === null) {
    return fail(c, 'SERVICE_UNAVAILABLE', 'No published configuration available', 503);
  }
  if (!config.paymentsEnabled) {
    return fail(c, 'SERVICE_UNAVAILABLE', 'Trials are temporarily disabled', 503);
  }

  const deviceKey = effectiveDeviceKey(c);

  // One claim per device (UNIQUE constraint is the final arbiter).
  const existing = await c.env.DB.prepare(
    'SELECT id FROM trial_claims WHERE device_id = ?'
  )
    .bind(deviceKey)
    .first<{ id: string }>();
  if (existing !== null) {
    return fail(c, 'CONFLICT', 'Free trial already claimed on this device', 409);
  }

  // ---------------------------------------------------------------------
  // v2.5.7 (H-3): trial-abuse hardening. The old device-only binding let
  // one account claim 5 trials (one per registered device) and one phone
  // claim unlimited trials (new Google account = new user + device id).
  // Three independent lifetime guards now stack on top of the device key.
  // ---------------------------------------------------------------------

  // (1) One trial per ACCOUNT, across every device it ever registered.
  const byAccount = await c.env.DB.prepare(
    'SELECT id FROM trial_claims WHERE user_id = ?'
  )
    .bind(c.user.userId)
    .first<{ id: string }>();
  if (byAccount !== null) {
    return fail(c, 'CONFLICT', 'Free trial already claimed on this account', 409);
  }

  // (2) One trial per GOOGLE IDENTITY (google_sub) — fresh app accounts on
  // the same Play/Google login can no longer farm trials.
  const me = await c.env.DB.prepare('SELECT google_sub FROM users WHERE id = ?')
    .bind(c.user.userId)
    .first<{ google_sub: string | null }>();
  const mySub = me?.google_sub ?? null;
  if (mySub !== null) {
    const byGoogleSub = await c.env.DB.prepare(
      `SELECT t.id FROM trial_claims t
       JOIN users u ON u.id = t.user_id
       WHERE u.google_sub = ? LIMIT 1`
    )
      .bind(mySub)
      .first<{ id: string }>();
    if (byGoogleSub !== null) {
      return fail(c, 'CONFLICT', 'Free trial already claimed for this Google account', 409);
    }
  }

  // (3) Per-IP quota (KV, 7-day window) — catches factory-reset farming on
  // one connection even with fresh Google accounts each time.
  try {
    const ipKey = `trial_ip_${clientIp(c)}`;
    const ipCount = Number((await c.env.KV.get(ipKey)) ?? '0');
    if (Number.isFinite(ipCount) && ipCount >= 3) {
      await appendSecurityEvent(c.env, c.user.userId, c.user.deviceId, 'TRIAL_QUOTA_IP', 'MEDIUM', {
        requestId: c.requestId,
      });
      return fail(c, 'CONFLICT', 'Trial limit reached for this network', 409);
    }
    await c.env.KV.put(ipKey, String(ipCount + 1), { expirationTtl: 7 * 24 * 60 * 60 });
  } catch {
    // KV unavailable — guards (1)+(2) still hold; the quota is additive.
  }

  // No stacking on an active subscription.
  const activeSub = await c.env.DB.prepare(
    `SELECT id, expiry_date FROM subscriptions
     WHERE user_id = ? AND status = 'ACTIVE' AND (expiry_date IS NULL OR expiry_date > ?)
     ORDER BY updated_at DESC LIMIT 1`
  )
    .bind(c.user.userId, new Date().toISOString())
    .first<{ id: string; expiry_date: string | null }>();
  if (activeSub !== null) {
    return fail(c, 'CONFLICT', 'An active subscription already exists', 409);
  }

  const now = new Date();
  const expires = new Date(now.getTime() + config.trialDays * 24 * 60 * 60 * 1000);
  const nowIso = now.toISOString();
  const claimId = `trl_${randomToken(12)}`;
  const subscriptionId = `sub_${randomToken(12)}`;

  // trial_claims first (UNIQUE device guard), then the subscription row.
  await c.env.DB.prepare(
    `INSERT INTO trial_claims (id, user_id, device_id, claimed_at, expires_at)
     VALUES (?, ?, ?, ?, ?)`
  )
    .bind(claimId, c.user.userId, deviceKey, nowIso, expires.toISOString())
    .run();

  await c.env.DB.prepare(
    `INSERT INTO subscriptions (id, user_id, product_id, purchase_token, plan, status,
                                start_date, expiry_date, last_verified, created_at, updated_at)
     VALUES (?, ?, 'trial', ?, 'trial', 'ACTIVE', ?, ?, ?, ?, ?)`
  )
    .bind(
      subscriptionId,
      c.user.userId,
      `trial_${claimId}`,
      nowIso,
      expires.toISOString(),
      nowIso,
      nowIso,
      nowIso
    )
    .run();

  return ok(c, {
    subscription: {
      id: subscriptionId,
      userId: c.user.userId,
      productId: 'trial',
      purchaseToken: `trial_${claimId}`,
      plan: 'trial',
      status: 'ACTIVE' as const,
      startDate: nowIso,
      expiryDate: expires.toISOString(),
      lastVerified: nowIso,
    },
    trial: {
      eligible: false,
      active: true,
      claimedAt: nowIso,
      expiresAt: expires.toISOString(),
    },
  });
}
