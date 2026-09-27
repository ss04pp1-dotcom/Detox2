/**
 * MAXLEVEL DETOX — admin surface for the community leaderboard + clubs
 * (v2.5.8 roadmap).
 *
 * The app routes (leaderboard.ts) are deliberately read-mostly and
 * privacy-minimal; moderation power lives HERE, behind RBAC + audit:
 *
 *   GET  /admin/leaderboard                  VIEW_ANALYTICS
 *        opted-in counts, mirrored DP totals, top boards + search
 *   POST /admin/leaderboard/users/:id/reset  EDIT_USERS
 *        zero-out a farmed/tampered profile (also opts the user out)
 *   GET  /admin/clubs                        VIEW_USERS
 *        paginated club list w/ member counts, search, hidden filter
 *   POST /admin/clubs/:id/hide               EDIT_USERS
 *        make a club invisible + unjoinable (confirm-gated)
 *   POST /admin/clubs/:id/restore            EDIT_USERS
 *        unhide (confirm-gated)
 *   POST /admin/clubs/:id/delete             EDIT_USERS
 *        remove a club and its memberships entirely (confirm-gated)
 *
 * PRIVACY NOTE: admin leaderboard rows expose user ids and display names
 * (the admin contract already sees emails via /admin/users) — but the
 * ANONYMOUS display mode is still respected for the displayName field:
 * the moderation row shows the pseudonym + the real display_name only
 * when the user chose 'name' mode.
 */

import { Context } from '../types';
import { ok, fail } from '../utils/response';
import { readJsonBody, validateFields } from '../middleware/validation';
import { appendAudit } from '../services/audit';

// ---------------------------------------------------------------------------
// GET /admin/leaderboard
// ---------------------------------------------------------------------------

export async function adminGetLeaderboard(c: Context): Promise<Response> {
  const db = c.env.DB;

  const q = (c.url.searchParams.get('q') ?? '').trim();
  const window = c.url.searchParams.get('window') ?? 'alltime';
  if (!['weekly', 'monthly', 'alltime'].includes(window)) {
    return fail(c, 'VALIDATION_FAILED', 'window must be weekly, monthly or alltime', 400);
  }
  const col = window === 'weekly' ? 'week_dp' : window === 'monthly' ? 'month_dp' : 'lifetime_dp';

  const [totalsRow, clubsRow, boardRows] = await Promise.all([
    db.prepare(
      `SELECT COUNT(*) AS profiles,
              SUM(opted_in) AS opted_in,
              SUM(lifetime_dp) AS lifetime_dp,
              MAX(updated_at) AS last_sync
       FROM leaderboard_profiles`
    ).first<{ profiles: number; opted_in: number | null; lifetime_dp: number | null; last_sync: string | null }>(),
    db.prepare(`SELECT COUNT(*) AS clubs, SUM(hidden) AS hidden FROM clubs`).first<{
      clubs: number;
      hidden: number | null;
    }>(),
    (q.length >= 2
      ? db.prepare(
          `SELECT lp.user_id, u.display_name, u.email, u.status, lp.level_name,
                  lp.streak_days, lp.week_dp, lp.month_dp, lp.lifetime_dp,
                  lp.opted_in, lp.display_mode, lp.updated_at
           FROM leaderboard_profiles lp
           JOIN users u ON u.id = lp.user_id
           WHERE lp.opted_in = 1 AND (LOWER(u.display_name) LIKE ? ESCAPE ? OR lp.user_id = ?)
           ORDER BY lp.${col} DESC, lp.lifetime_dp DESC
           LIMIT 50`
        ).bind(`%${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`.toLowerCase(), '\\', q)
      : db.prepare(
          `SELECT lp.user_id, u.display_name, u.email, u.status, lp.level_name,
                  lp.streak_days, lp.week_dp, lp.month_dp, lp.lifetime_dp,
                  lp.opted_in, lp.display_mode, lp.updated_at
           FROM leaderboard_profiles lp
           JOIN users u ON u.id = lp.user_id
           WHERE lp.opted_in = 1
           ORDER BY lp.${col} DESC, lp.lifetime_dp DESC
           LIMIT 50`
        )
    ).all<BoardRow>(),
  ]);

  return ok(c, {
    totals: {
      profiles: totalsRow?.profiles ?? 0,
      optedIn: totalsRow?.opted_in ?? 0,
      lifetimeDp: totalsRow?.lifetime_dp ?? 0,
      lastSync: totalsRow?.last_sync,
      clubs: clubsRow?.clubs ?? 0,
      hiddenClubs: clubsRow?.hidden ?? 0,
    },
    window,
    entries: boardRows.results.map((r, i) => ({
      rank: i + 1,
      userId: r.user_id,
      displayName:
        r.display_mode === 'anonymous'
          ? `Detoxer #${pseudonym(r.user_id)}`
          : (r.display_name ?? '').trim() || `User #${pseudonym(r.user_id)}`,
      email: r.email,
      userStatus: r.status,
      levelName: r.level_name,
      streakDays: r.streak_days,
      value: window === 'weekly' ? r.week_dp : window === 'monthly' ? r.month_dp : r.lifetime_dp,
      lifetimeDp: r.lifetime_dp,
      optedIn: r.opted_in === 1,
      displayMode: r.display_mode,
      updatedAt: r.updated_at,
    })),
  });
}

/** Same deterministic pseudonym the app routes use (FNV-1a 4-hex). */
function pseudonym(userId: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < userId.length; i++) {
    h ^= userId.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0').slice(0, 4).toUpperCase();
}

interface BoardRow {
  user_id: string;
  display_name: string | null;
  email: string;
  status: string;
  level_name: string | null;
  streak_days: number;
  week_dp: number;
  month_dp: number;
  lifetime_dp: number;
  opted_in: number;
  display_mode: string;
  updated_at: string;
}

// ---------------------------------------------------------------------------
// POST /admin/leaderboard/users/:id/reset
// ---------------------------------------------------------------------------

export async function adminResetLeaderboardProfile(c: Context): Promise<Response> {
  const userId = c.params.id;
  if (!/^usr_[A-Za-z0-9]{8,64}$/.test(userId)) {
    return fail(c, 'VALIDATION_FAILED', 'Invalid user id', 400);
  }

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    confirm: { type: 'boolean', required: true },
    reason: { type: 'string', required: true, minLength: 3, maxLength: 300 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (v.value.confirm !== true) {
    return fail(c, 'INVALID_REQUEST', 'Reset requires confirm: true', 400);
  }
  const reason = v.value.reason as string;

  const existing = await c.env.DB.prepare(
    'SELECT user_id, opted_in, lifetime_dp FROM leaderboard_profiles WHERE user_id = ?'
  )
    .bind(userId)
    .first<{ user_id: string; opted_in: number; lifetime_dp: number }>();
  if (existing === null) return fail(c, 'NOT_FOUND', 'No leaderboard profile for that user', 404);

  // Zero the mirror AND force opt-out: the profile becomes invisible and a
  // tampered/farmed standing is wiped. The device stays the DP authority —
  // the user may opt in again and re-climb from their true device numbers
  // only after the monotonic max accepts them back (lifetime max(server=0,
  // device) = device, so a legit device simply re-syncs).
  await c.env.DB.prepare(
    `UPDATE leaderboard_profiles
     SET lifetime_dp = 0, week_dp = 0, week_anchor = '', month_dp = 0, month_anchor = '',
         streak_days = 0, level_name = NULL, opted_in = 0, updated_at = ?
     WHERE user_id = ?`
  )
    .bind(new Date().toISOString(), userId)
    .run();

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'LEADERBOARD_PROFILE_RESET',
    'leaderboard_profile',
    userId,
    `was lifetime_dp=${existing.lifetime_dp}; reason=${reason}`,
    c.requestId
  );

  return ok(c, { reset: true, userId });
}

// ---------------------------------------------------------------------------
// GET /admin/clubs
// ---------------------------------------------------------------------------

export async function adminListClubs(c: Context): Promise<Response> {
  const db = c.env.DB;
  const q = (c.url.searchParams.get('q') ?? '').trim();
  const filter = c.url.searchParams.get('filter') ?? 'visible'; // visible | hidden | all
  if (!['visible', 'hidden', 'all'].includes(filter)) {
    return fail(c, 'VALIDATION_FAILED', 'filter must be visible, hidden or all', 400);
  }
  const page = Math.max(1, Math.min(500, Number(c.url.searchParams.get('page') ?? '1') || 1));
  const pageSize = 25;

  const hiddenCond = filter === 'all' ? '' : filter === 'hidden' ? 'AND c.hidden = 1' : 'AND c.hidden = 0';
  const nameCond = q.length >= 2 ? 'AND c.name LIKE ? ESCAPE ?' : '';
  const escaped = `%${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`;

  const countRow = await (q.length >= 2
    ? db.prepare(
        `SELECT COUNT(*) AS n FROM clubs c WHERE 1=1 ${hiddenCond} ${nameCond}`
      ).bind(...(q.length >= 2 ? [escaped, '\\'] : []))
    : db.prepare(`SELECT COUNT(*) AS n FROM clubs c WHERE 1=1 ${hiddenCond}`)
  ).first<{ n: number }>();

  const rows = await (q.length >= 2
    ? db.prepare(
        `SELECT c.*,
                (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count,
                (SELECT u.display_name FROM users u WHERE u.id = c.created_by) AS creator_name
         FROM clubs c
         WHERE 1=1 ${hiddenCond} ${nameCond}
         ORDER BY member_count DESC, c.created_at DESC
         LIMIT ? OFFSET ?`
      ).bind(escaped, '\\', pageSize, (page - 1) * pageSize)
    : db.prepare(
        `SELECT c.*,
                (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count,
                (SELECT u.display_name FROM users u WHERE u.id = c.created_by) AS creator_name
         FROM clubs c
         WHERE 1=1 ${hiddenCond}
         ORDER BY member_count DESC, c.created_at DESC
         LIMIT ? OFFSET ?`
      ).bind(pageSize, (page - 1) * pageSize)
  ).all<{
    id: string;
    name: string;
    description: string | null;
    invite_code: string;
    created_by: string;
    hidden: number;
    created_at: string;
    member_count: number;
    creator_name: string | null;
  }>();

  return ok(c, {
    clubs: rows.results.map((r) => ({
      id: r.id,
      name: r.name,
      description: r.description,
      inviteCode: r.invite_code,
      createdBy: r.created_by,
      creatorName: r.creator_name,
      hidden: r.hidden === 1,
      memberCount: r.member_count,
      createdAt: r.created_at,
    })),
    page,
    pageSize,
    total: countRow?.n ?? 0,
  });
}

// ---------------------------------------------------------------------------
// POST /admin/clubs/:id/hide | restore | delete
// ---------------------------------------------------------------------------

async function loadClub(
  c: Context,
  clubId: string
): Promise<{ id: string; name: string; hidden: number } | null> {
  return await c.env.DB.prepare('SELECT id, name, hidden FROM clubs WHERE id = ?')
    .bind(clubId)
    .first<{ id: string; name: string; hidden: number }>();
}

function clubIdOk(id: string): boolean {
  return /^clb_[A-Za-z0-9]{8,32}$/.test(id);
}

async function readConfirm(c: Context): Promise<{ ok: true } | { ok: false; res: Response }> {
  const body = await readJsonBody(c);
  const v = validateFields(body, { confirm: { type: 'boolean', required: true } });
  if (!v.ok) return { ok: false, res: fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400) };
  if (v.value.confirm !== true) {
    return { ok: false, res: fail(c, 'INVALID_REQUEST', 'This action requires confirm: true', 400) };
  }
  return { ok: true };
}

export async function adminHideClub(c: Context): Promise<Response> {
  const clubId = c.params.id;
  if (!clubIdOk(clubId)) return fail(c, 'VALIDATION_FAILED', 'Invalid club id', 400);
  const gate = await readConfirm(c);
  if (!gate.ok) return gate.res;

  const club = await loadClub(c, clubId);
  if (club === null) return fail(c, 'NOT_FOUND', 'Club not found', 404);

  await c.env.DB.prepare('UPDATE clubs SET hidden = 1 WHERE id = ?').bind(clubId).run();
  await appendAudit(
    c.env,
    c.admin!.adminId,
    'CLUB_HIDDEN',
    'club',
    clubId,
    `name="${club.name}"`,
    c.requestId
  );
  return ok(c, { hidden: true, id: clubId });
}

export async function adminRestoreClub(c: Context): Promise<Response> {
  const clubId = c.params.id;
  if (!clubIdOk(clubId)) return fail(c, 'VALIDATION_FAILED', 'Invalid club id', 400);
  const gate = await readConfirm(c);
  if (!gate.ok) return gate.res;

  const club = await loadClub(c, clubId);
  if (club === null) return fail(c, 'NOT_FOUND', 'Club not found', 404);

  await c.env.DB.prepare('UPDATE clubs SET hidden = 0 WHERE id = ?').bind(clubId).run();
  await appendAudit(
    c.env,
    c.admin!.adminId,
    'CLUB_RESTORED',
    'club',
    clubId,
    `name="${club.name}"`,
    c.requestId
  );
  return ok(c, { hidden: false, id: clubId });
}

export async function adminDeleteClub(c: Context): Promise<Response> {
  const clubId = c.params.id;
  if (!clubIdOk(clubId)) return fail(c, 'VALIDATION_FAILED', 'Invalid club id', 400);
  const gate = await readConfirm(c);
  if (!gate.ok) return gate.res;

  const club = await loadClub(c, clubId);
  if (club === null) return fail(c, 'NOT_FOUND', 'Club not found', 404);

  // Memberships first (FK-free tables, but keep the order anyway), then the
  // club row. Users simply find themselves clubless on next /clubs/mine.
  await c.env.DB.prepare('DELETE FROM club_members WHERE club_id = ?').bind(clubId).run();
  await c.env.DB.prepare('DELETE FROM clubs WHERE id = ?').bind(clubId).run();
  await appendAudit(
    c.env,
    c.admin!.adminId,
    'CLUB_DELETED',
    'club',
    clubId,
    `name="${club.name}"`,
    c.requestId
  );
  return ok(c, { deleted: true, id: clubId });
}
