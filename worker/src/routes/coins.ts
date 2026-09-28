/**
 * MAXLEVEL DETOX — server coin mirror (v2.5.7, audit C-3).
 *
 * App routes (all requireUser):
 *   GET  /api/v1/coins?since=   server ledger view + grants since a timestamp
 *   POST /api/v1/coins/earn     idempotent mirror of a device ad-reward
 *   POST /api/v1/coins/spend    best-effort mirror of a device spend
 *
 * DESIGN (deliberate two-ledger mirror):
 *  - The DEVICE Room ledger (Kotlin CoinLedger) stays the enforcement
 *    authority for spends — enforcement must work offline (TRD §59).
 *  - The SERVER ledger is the authority for GRANTS (admin adjustments,
 *    bonuses, refunds): the device pulls grants via GET /coins and applies
 *    them idempotently (native PK "coin_srv_<transactionId>").
 *  - Device-side earns/spends are mirrored to the server best-effort so the
 *    admin panel reflects reality. A mirror that would drive the server
 *    balance negative is SKIPPED (the append-only CHECK constraint must
 *    hold) — drift is accepted and visible only server-side.
 *  - All D1 statements are parameterized.
 */

import { Context, CoinTransactionRow } from '../types';
import { ok, fail } from '../utils/response';
import { randomToken } from '../utils/crypto';
import { readJsonBody, validateFields } from '../middleware/validation';
import { toApiCoinTransaction } from '../services/serializers';

/** Spend types a device may mirror (enforcement spends only). */
const SPEND_TYPES: readonly string[] = ['TEMP_UNLOCK_SPEND', 'BAILOUT_SPEND'];

// ---------------------------------------------------------------------------
// GET /coins?since=<iso>
// ---------------------------------------------------------------------------

export async function getCoins(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const sinceParam = c.url.searchParams.get('since');
  if (sinceParam !== null && Number.isNaN(Date.parse(sinceParam))) {
    return fail(c, 'VALIDATION_FAILED', 'since must be an ISO 8601 timestamp', 400);
  }

  const balanceRow = await c.env.DB.prepare(
    'SELECT COALESCE(SUM(amount), 0) AS balance FROM coin_transactions WHERE user_id = ?'
  )
    .bind(c.user.userId)
    .first<{ balance: number }>();

  // Grants for the device to apply (positive admin/bonus/refund rows).
  const grantRows = await c.env.DB.prepare(
    `SELECT * FROM coin_transactions
     WHERE user_id = ? AND type IN ('ADMIN_ADJUSTMENT', 'BONUS', 'REFUND') AND amount > 0
       AND created_at > ?
     ORDER BY created_at ASC LIMIT 200`
  )
    .bind(c.user.userId, sinceParam ?? '1970-01-01T00:00:00.000Z')
    .all<CoinTransactionRow>();

  return ok(c, {
    balance: balanceRow?.balance ?? 0,
    grants: grantRows.results.map(toApiCoinTransaction),
    serverTime: new Date().toISOString(),
  });
}

// ---------------------------------------------------------------------------
// POST /coins/earn — idempotent device ad-reward mirror
// ---------------------------------------------------------------------------

export async function coinsEarn(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    reference: { type: 'string', required: true, minLength: 4, maxLength: 128 },
    amount: { type: 'number', required: false, min: 1, max: 10, integer: true },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const reference = v.value.reference as string;
  const amount = (v.value.amount as number | undefined) ?? 1;

  const balanceRow = await c.env.DB.prepare(
    'SELECT COALESCE(SUM(amount), 0) AS balance FROM coin_transactions WHERE user_id = ?'
  )
    .bind(c.user.userId)
    .first<{ balance: number }>();
  const balance = balanceRow?.balance ?? 0;

  const txId = `dev_ad_${reference}`;
  const res = await c.env.DB.prepare(
    `INSERT OR IGNORE INTO coin_transactions
       (id, user_id, transaction_id, type, amount, balance_after, source, reference, created_at)
     VALUES (?, ?, ?, 'AD_REWARD', ?, ?, 'device', ?, ?)`
  )
    .bind(
      `ctx_${randomToken(16)}`,
      c.user.userId,
      txId,
      amount,
      balance + amount,
      reference,
      new Date().toISOString()
    )
    .run();

  return ok(c, { recorded: (res.meta.changes ?? 0) > 0 });
}

// ---------------------------------------------------------------------------
// POST /coins/spend — best-effort device spend mirror
// ---------------------------------------------------------------------------

export async function coinsSpend(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    type: { type: 'string', required: true, enumValues: SPEND_TYPES },
    amount: { type: 'number', required: true, min: 1, max: 10_000, integer: true },
    reference: { type: 'string', required: true, minLength: 4, maxLength: 128 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const type = v.value.type as string;
  const amount = v.value.amount as number;
  const reference = v.value.reference as string;

  const balanceRow = await c.env.DB.prepare(
    'SELECT COALESCE(SUM(amount), 0) AS balance FROM coin_transactions WHERE user_id = ?'
  )
    .bind(c.user.userId)
    .first<{ balance: number }>();
  const balance = balanceRow?.balance ?? 0;

  // The server is a MIRROR for spends, never the authority: if the mirror
  // would go negative (offline ad rewards the server never saw), skip the
  // row instead of violating the append-only CHECK constraint.
  if (balance - amount < 0) {
    return ok(c, { recorded: false, reason: 'mirror_skipped' });
  }

  const txId = `dev_spend_${type}_${reference}`;
  const res = await c.env.DB.prepare(
    `INSERT OR IGNORE INTO coin_transactions
       (id, user_id, transaction_id, type, amount, balance_after, source, reference, created_at)
     VALUES (?, ?, ?, ?, ?, ?, 'device', ?, ?)`
  )
    .bind(
      `ctx_${randomToken(16)}`,
      c.user.userId,
      txId,
      type,
      -amount,
      balance - amount,
      reference,
      new Date().toISOString()
    )
    .run();

  return ok(c, { recorded: (res.meta.changes ?? 0) > 0 });
}
