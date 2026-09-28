/**
 * MAXLEVEL DETOX — user-side support tickets (v2.5.7, audit H-2).
 *
 * App routes (all requireUser):
 *   POST /api/v1/support/tickets          create a ticket (report a problem)
 *   GET  /api/v1/support/tickets          list MY tickets incl. admin replies
 *
 * This completes the previously dead support subsystem: the admin queue
 * (GET/PATCH /admin/support/tickets) and the triage UI already existed, but
 * there was no path for a ticket to ever be created.
 *
 * Anti-abuse:
 *  - Max 3 OPEN/IN_PROGRESS/WAITING_USER tickets per user (concurrency cap).
 *  - Max 5 tickets per user per 24h (daily cap).
 *  - Category is an enum; description length-capped; app version captured
 *    for triage context.
 *  - Route uses the auth rate-limit bucket on top of the logical caps.
 *  - All D1 statements are parameterized.
 */

import { Context } from '../types';
import { ok, fail } from '../utils/response';
import { randomToken } from '../utils/crypto';
import { readJsonBody, validateFields } from '../middleware/validation';
import { toApiTicket } from '../services/serializers';
import { TicketRow } from '../types';

const TICKET_CATEGORIES = [
  'PAYMENT',
  'BILLING_PLAY',
  'ENFORCEMENT',
  'ACCOUNT',
  'BUG',
  'FEEDBACK',
  'OTHER',
] as const;

const MAX_OPEN_TICKETS = 3;
const MAX_TICKETS_PER_DAY = 5;

// ---------------------------------------------------------------------------
// POST /support/tickets
// ---------------------------------------------------------------------------

export async function createSupportTicket(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    category: { type: 'string', required: true, enumValues: TICKET_CATEGORIES },
    description: { type: 'string', required: true, minLength: 10, maxLength: 2000 },
    appVersion: { type: 'string', required: false, minLength: 1, maxLength: 50 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  const category = v.value.category as string;
  const description = (v.value.description as string).trim();
  const appVersion = (v.value.appVersion as string | undefined) ?? null;

  const now = new Date().toISOString();
  const dayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();

  const [openRow, dayRow] = await Promise.all([
    c.env.DB.prepare(
      `SELECT COUNT(*) AS n FROM support_tickets
       WHERE user_id = ? AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER')`
    )
      .bind(c.user.userId)
      .first<{ n: number }>(),
    c.env.DB.prepare(
      'SELECT COUNT(*) AS n FROM support_tickets WHERE user_id = ? AND created_at >= ?'
    )
      .bind(c.user.userId, dayAgo)
      .first<{ n: number }>(),
  ]);

  if ((openRow?.n ?? 0) >= MAX_OPEN_TICKETS) {
    return fail(
      c,
      'CONFLICT',
      `You already have ${MAX_OPEN_TICKETS} open tickets — wait for a reply first`,
      409
    );
  }
  if ((dayRow?.n ?? 0) >= MAX_TICKETS_PER_DAY) {
    return fail(c, 'RATE_LIMITED', 'Daily ticket limit reached — try again tomorrow', 429);
  }

  const id = `tkt_${randomToken(12)}`;
  await c.env.DB.prepare(
    `INSERT INTO support_tickets
       (id, user_id, category, description, app_version, request_id, status, priority, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 'MEDIUM', ?, ?)`
  )
    .bind(id, c.user.userId, category, description, appVersion, c.requestId, now, now)
    .run();

  const row = await c.env.DB.prepare('SELECT * FROM support_tickets WHERE id = ?')
    .bind(id)
    .first<TicketRow>();
  return ok(c, { ticket: row !== null ? toApiTicket(row) : null }, 201);
}

// ---------------------------------------------------------------------------
// GET /support/tickets — my tickets incl. the admin reply (pull model)
// ---------------------------------------------------------------------------

export async function listMySupportTickets(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const rows = await c.env.DB.prepare(
    `SELECT * FROM support_tickets WHERE user_id = ?
     ORDER BY (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER')) DESC, updated_at DESC
     LIMIT 20`
  )
    .bind(c.user.userId)
    .all<TicketRow>();

  return ok(c, { tickets: rows.results.map(toApiTicket) });
}
