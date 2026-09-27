/**
 * MAXLEVEL DETOX — manual bKash payment gateway (v2.2 Phase D, port-plan
 * item 17 — "EPS-style, with kill-switch").
 *
 * App routes:
 *   GET  /api/v1/payments/bkash/instructions   number + instructions + plans
 *   POST /api/v1/payments/bkash/init           { planId } -> PENDING payment
 *   POST /api/v1/payments/bkash/submit         { paymentId, trxId, senderNumber? }
 *   GET  /api/v1/payments/bkash/status         user's recent payments
 *
 * Flow (deliberately manual, no third-party money API):
 *   1. init    -> the app shows a unique short reference (MLD-XXXXXXXX) and
 *                 the merchant bKash number from remote config.
 *   2. user    -> sends the amount via their bKash app, typing the reference
 *                 in the transfer memo.
 *   3. submit  -> user enters the bKash TrxID; payment moves to IN_REVIEW.
 *   4. admin   -> verifies the transfer against the merchant statement and
 *                 either grants the plan's PRO days (VERIFIED) or rejects
 *                 with a reason (REJECTED + security event).
 *
 * Security notes:
 *  - `paymentsEnabled` (remote config) is a global kill-switch; an empty
 *    bkashNumber also disables the gateway.
 *  - References are server-generated (8 random hex chars, UNIQUE) — the
 *    client can never choose one, so payments cannot be spoofed or squatted.
 *  - Amounts are always read from the SERVER's plan row, never from the
 *    request body.
 *  - TrxID format is strictly validated; the reviewer compares it against
 *    the merchant statement before granting anything.
 *  - All D1 statements are parameterized.
 */

import { BkashPaymentRow, Context, SubscriptionPlanRow } from '../types';
import { ok, fail } from '../utils/response';
import { randomToken } from '../utils/crypto';
import { readJsonBody, validateFields } from '../middleware/validation';
import { readPublishedConfig } from '../services/config';
import { toApiBkashPayment, toApiPlan } from '../services/serializers';

const TRX_RE = /^[A-Za-z0-9-]{6,24}$/;
const PHONE_RE = /^[+0-9][0-9 ]{7,19}$/;
/** How long an unsubmitted PENDING payment stays alive. */
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Gateway state helper
// ---------------------------------------------------------------------------

interface GatewayState {
  enabled: boolean;
  bkashNumber: string;
  bkashInstructions: string;
}

async function gatewayState(c: Context): Promise<GatewayState> {
  const config = await readPublishedConfig(c.env);
  const number = config?.bkashNumber ?? '';
  const enabled =
    (config?.paymentsEnabled ?? false) && number.trim().length >= 8;
  return {
    enabled,
    bkashNumber: number,
    bkashInstructions: config?.bkashInstructions ?? '',
  };
}

// ---------------------------------------------------------------------------
// GET /payments/bkash/instructions
// ---------------------------------------------------------------------------

export async function bkashInstructions(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const state = await gatewayState(c);
  if (!state.enabled) {
    return ok(c, {
      enabled: false,
      number: '',
      instructions: '',
      plans: [],
    });
  }

  const rows = await c.env.DB.prepare(
    `SELECT * FROM subscription_plans
     WHERE is_active = 1 AND source = 'bkash' ORDER BY sort_order ASC, id ASC`
  ).all<SubscriptionPlanRow>();

  return ok(c, {
    enabled: true,
    number: state.bkashNumber,
    instructions: state.bkashInstructions,
    plans: rows.results.map((r) =>
      toApiPlan(
        r,
        ['monthly', 'quarterly', 'semiannual', 'yearly', 'trial'],
        ['play', 'bkash']
      )
    ),
  });
}

// ---------------------------------------------------------------------------
// POST /payments/bkash/init
// ---------------------------------------------------------------------------

export async function bkashInit(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body ?? {}, {
    planId: { type: 'string', required: true, minLength: 4, maxLength: 64 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const planId = v.value.planId as string;

  const state = await gatewayState(c);
  if (!state.enabled) {
    return fail(c, 'SERVICE_UNAVAILABLE', 'bKash payments are currently disabled', 503);
  }

  const plan = await c.env.DB.prepare(
    `SELECT * FROM subscription_plans WHERE id = ? AND source = 'bkash' AND is_active = 1`
  )
    .bind(planId)
    .first<SubscriptionPlanRow>();
  if (plan === null) {
    return fail(c, 'NOT_FOUND', 'Plan not found or not payable via bKash', 404);
  }

  // Cap concurrent open payments per user (anti-spam on the review queue).
  const open = await c.env.DB.prepare(
    `SELECT COUNT(*) AS n FROM bkash_payments
     WHERE user_id = ? AND status IN ('PENDING', 'IN_REVIEW')`
  )
    .bind(c.user.userId)
    .first<{ n: number }>();
  if ((open?.n ?? 0) >= 3) {
    return fail(c, 'CONFLICT', 'Too many open payments — wait for review or cancel one', 409);
  }

  const nowIso = new Date().toISOString();
  const paymentId = `pay_${randomToken(12)}`;
  // v2.5.7 (W-2): the old MLD-XXXXXXXX had only 4 bytes of entropy (2^32) —
  // the UNIQUE(reference) constraint meant a birthday-bound collision (~50%
  // at 77k payments) surfaced as a raw 500 mid-checkout. 12 hex chars
  // (48 bits) plus a bounded retry makes a collision practically impossible.
  let reference = `MLD-${randomToken(6).toUpperCase()}`;
  let inserted = false;
  for (let attempt = 0; attempt < 3 && !inserted; attempt++) {
    try {
      await c.env.DB.prepare(
        `INSERT INTO bkash_payments
           (id, user_id, device_id, plan_id, reference, amount_minor, currency,
            status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)`
      )
        .bind(
          paymentId,
          c.user.userId,
          c.user.deviceId,
          plan.id,
          reference,
          plan.price_minor,
          plan.currency,
          nowIso,
          nowIso
        )
        .run();
      inserted = true;
    } catch (err) {
      const uniqueViolation =
        err instanceof Error && err.message.toUpperCase().includes('UNIQUE');
      if (!uniqueViolation) throw err;
      reference = `MLD-${randomToken(6).toUpperCase()}`;
    }
  }
  if (!inserted) {
    return fail(
      c,
      'CONFLICT',
      'Could not allocate a payment reference — please retry in a moment',
      409
    );
  }

  const created = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ?'
  )
    .bind(paymentId)
    .first<BkashPaymentRow>();

  return ok(c, {
    payment: created !== null ? toApiBkashPayment(created) : null,
    number: state.bkashNumber,
    instructions: state.bkashInstructions,
  });
}

// ---------------------------------------------------------------------------
// POST /payments/bkash/submit
// ---------------------------------------------------------------------------

export async function bkashSubmit(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body ?? {}, {
    paymentId: { type: 'string', required: true, minLength: 8, maxLength: 64 },
    trxId: { type: 'string', required: true, minLength: 6, maxLength: 24 },
    senderNumber: { type: 'string', required: false, minLength: 8, maxLength: 20 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const paymentId = v.value.paymentId as string;
  const trxId = v.value.trxId as string;
  const senderNumber = v.value.senderNumber as string | undefined;

  if (!TRX_RE.test(trxId)) {
    return fail(c, 'VALIDATION_FAILED', 'trxId must be 6-24 letters, digits or dashes', 400);
  }
  if (senderNumber !== undefined && !PHONE_RE.test(senderNumber)) {
    return fail(c, 'VALIDATION_FAILED', 'senderNumber format is invalid', 400);
  }

  const payment = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ? AND user_id = ?'
  )
    .bind(paymentId, c.user.userId)
    .first<BkashPaymentRow>();
  if (payment === null) {
    return fail(c, 'NOT_FOUND', 'Payment not found', 404);
  }
  if (payment.status !== 'PENDING') {
    return fail(c, 'CONFLICT', `Payment is already ${payment.status}`, 409);
  }
  if (Date.parse(payment.created_at) + PENDING_TTL_MS < Date.now()) {
    await c.env.DB.prepare(
      `UPDATE bkash_payments SET status = 'EXPIRED', updated_at = ? WHERE id = ?`
    )
      .bind(new Date().toISOString(), paymentId)
      .run();
    return fail(c, 'CONFLICT', 'Payment window expired — start a new one', 409);
  }

  const nowIso = new Date().toISOString();
  await c.env.DB.prepare(
    `UPDATE bkash_payments
     SET trx_id = ?, sender_number = ?, status = 'IN_REVIEW', submitted_at = ?, updated_at = ?
     WHERE id = ? AND status = 'PENDING'`
  )
    .bind(trxId, senderNumber ?? null, nowIso, nowIso, paymentId)
    .run();

  const updated = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ?'
  )
    .bind(paymentId)
    .first<BkashPaymentRow>();

  return ok(c, { payment: updated !== null ? toApiBkashPayment(updated) : null });
}

// ---------------------------------------------------------------------------
// POST /payments/bkash/cancel
// ---------------------------------------------------------------------------

export async function bkashCancel(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body ?? {}, {
    paymentId: { type: 'string', required: true, minLength: 8, maxLength: 64 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const paymentId = v.value.paymentId as string;

  const payment = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ? AND user_id = ?'
  )
    .bind(paymentId, c.user.userId)
    .first<BkashPaymentRow>();
  if (payment === null) return fail(c, 'NOT_FOUND', 'Payment not found', 404);
  if (payment.status === 'VERIFIED' || payment.status === 'REJECTED') {
    return fail(c, 'CONFLICT', `Reviewed payments cannot be canceled (${payment.status})`, 409);
  }
  if (payment.status === 'CANCELED') {
    return ok(c, { payment: toApiBkashPayment(payment) });
  }

  const nowIso = new Date().toISOString();
  await c.env.DB.prepare(
    `UPDATE bkash_payments SET status = 'CANCELED', updated_at = ? WHERE id = ?`
  )
    .bind(nowIso, paymentId)
    .run();

  const updated = await c.env.DB.prepare(
    'SELECT * FROM bkash_payments WHERE id = ?'
  )
    .bind(paymentId)
    .first<BkashPaymentRow>();
  return ok(c, { payment: updated !== null ? toApiBkashPayment(updated) : null });
}

// ---------------------------------------------------------------------------
// GET /payments/bkash/status
// ---------------------------------------------------------------------------

export async function bkashStatus(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const rows = await c.env.DB.prepare(
    `SELECT b.*, p.display_name AS plan_name FROM bkash_payments b
     LEFT JOIN subscription_plans p ON p.id = b.plan_id
     WHERE b.user_id = ? ORDER BY b.created_at DESC LIMIT 20`
  )
    .bind(c.user.userId)
    .all<BkashPaymentRow & { plan_name: string | null }>();

  return ok(c, {
    payments: rows.results.map((r) => ({
      ...toApiBkashPayment(r),
      planName: r.plan_name ?? null,
    })),
  });
}
