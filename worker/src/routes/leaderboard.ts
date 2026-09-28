/**
 * MAXLEVEL DETOX — community leaderboard + clubs (v2.5.8 roadmap).
 *
 * App routes (all requireUser):
 *   POST /leaderboard/opt-in    toggle participation + display mode
 *   GET  /leaderboard/me        my profile, global rank, club snapshot
 *   POST /leaderboard/sync      device DP snapshot mirror (opt-in gated client-side)
 *   GET  /leaderboard/global    top-50 + my rank (weekly|monthly|alltime)
 *   POST /clubs                 create a club (auto-leaves any current club)
 *   GET  /clubs/mine            my club + member count
 *   GET  /clubs/search?q=       find by name / exact invite code
 *   POST /clubs/join/:code      join by invite code (auto-leaves current)
 *   POST /clubs/leave           leave my current club
 *   GET  /clubs/:id             club detail + club leaderboard
 *
 * PRIVACY MODEL:
 *  - Participation is OPT-IN (leaderboard_profiles.opted_in). The client only
 *    mirrors DP snapshots after the user opts in; rows never opted in are
 *    invisible everywhere.
 *  - Leaderboard entries never expose user ids or emails. Display mode
 *    'anonymous' replaces the name with a deterministic pseudonym.
 *  - DP is a progression currency with zero monetary value: the mirror is
 *    clamped and sanity-capped server-side; lifetime DP is monotonic
 *    (max(server, device)) so a reinstall cannot wipe — or farm — a standing.
 */

import { Context } from '../types';
import { ok, fail } from '../utils/response';
import { randomToken } from '../utils/crypto';
import { readJsonBody, validateFields } from '../middleware/validation';

// ---------------------------------------------------------------------------
// Row shapes (local — joined projections)
// ---------------------------------------------------------------------------

interface LeaderboardProfileRow {
  user_id: string;
  lifetime_dp: number;
  week_dp: number;
  week_anchor: string;
  month_dp: number;
  month_anchor: string;
  streak_days: number;
  level_name: string | null;
  opted_in: number;
  display_mode: string;
  updated_at: string;
}

interface ClubRow {
  id: string;
  name: string;
  description: string | null;
  invite_code: string;
  created_by: string;
  hidden: number;
  created_at: string;
}

type LeaderboardWindow = 'weekly' | 'monthly' | 'alltime';

const WINDOWS: readonly LeaderboardWindow[] = ['weekly', 'monthly', 'alltime'];
const TOP_N = 50;

const DP_LIMITS = {
  lifetime: { min: 0, max: 1_000_000 },
  week: { min: 0, max: 20_000 },
  month: { min: 0, max: 100_000 },
  streak: { min: 0, max: 3_650 },
  levelNameMax: 32,
} as const;

const WEEK_ANCHOR_RE = /^\d{4}-W\d{2}$/;
const MONTH_ANCHOR_RE = /^\d{4}-\d{2}$/;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Deterministic 4-hex pseudonym seed (no crypto dep needed). */
function anonTag(userId: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < userId.length; i++) {
    h ^= userId.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0').slice(0, 4).toUpperCase();
}

function displayLabel(
  userId: string,
  displayName: string | null,
  mode: string
): string {
  if (mode === 'anonymous') return `Detoxer #${anonTag(userId)}`;
  return (displayName ?? '').trim().length > 0
    ? (displayName as string).trim().slice(0, 40)
    : `Detoxer #${anonTag(userId)}`;
}

function windowValue(p: LeaderboardProfileRow, window: LeaderboardWindow): number {
  if (window === 'weekly') return p.week_dp;
  if (window === 'monthly') return p.month_dp;
  return p.lifetime_dp;
}

function windowColumn(window: LeaderboardWindow): string {
  if (window === 'weekly') return 'week_dp';
  if (window === 'monthly') return 'month_dp';
  return 'lifetime_dp';
}

async function getProfile(
  c: Context,
  userId: string
): Promise<LeaderboardProfileRow | null> {
  return await c.env.DB.prepare('SELECT * FROM leaderboard_profiles WHERE user_id = ?')
    .bind(userId)
    .first<LeaderboardProfileRow>();
}

async function myRank(
  c: Context,
  window: LeaderboardWindow,
  myValue: number
): Promise<number> {
  const col = windowColumn(window);
  const row = await c.env.DB.prepare(
    `SELECT COUNT(*) AS better FROM leaderboard_profiles
     WHERE opted_in = 1 AND ${col} > ?`
  )
    .bind(myValue)
    .first<{ better: number }>();
  return (row?.better ?? 0) + 1;
}

interface EntryRow {
  user_id: string;
  display_name: string | null;
  level_name: string | null;
  streak_days: number;
  week_dp: number;
  month_dp: number;
  lifetime_dp: number;
  display_mode: string;
}

function toEntry(r: EntryRow, window: LeaderboardWindow, meId: string, rank: number) {
  return {
    rank,
    displayName: displayLabel(r.user_id, r.display_name, r.display_mode),
    levelName: r.level_name,
    streakDays: r.streak_days,
    value:
      window === 'weekly' ? r.week_dp : window === 'monthly' ? r.month_dp : r.lifetime_dp,
    isMe: r.user_id === meId,
  };
}

async function clubSnapshot(c: Context, userId: string): Promise<Record<string, unknown> | null> {
  const membership = await c.env.DB.prepare(
    'SELECT club_id, joined_at FROM club_members WHERE user_id = ?'
  )
    .bind(userId)
    .first<{ club_id: string; joined_at: string }>();
  if (membership === null) return null;

  const club = await c.env.DB.prepare('SELECT * FROM clubs WHERE id = ?')
    .bind(membership.club_id)
    .first<ClubRow>();
  if (club === null) return null;

  const countRow = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM club_members WHERE club_id = ?'
  )
    .bind(club.id)
    .first<{ n: number }>();

  return {
    id: club.id,
    name: club.name,
    description: club.description,
    inviteCode: club.invite_code,
    memberCount: countRow?.n ?? 1,
    joinedAt: membership.joined_at,
  };
}

// ---------------------------------------------------------------------------
// POST /leaderboard/opt-in
// ---------------------------------------------------------------------------

export async function leaderboardOptIn(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    optedIn: { type: 'boolean', required: true },
    displayMode: { type: 'string', required: false, enumValues: ['name', 'anonymous'] },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const optedIn = v.value.optedIn as boolean;
  const displayMode = (v.value.displayMode as string | undefined) ?? 'name';
  const now = new Date().toISOString();

  await c.env.DB.prepare(
    `INSERT INTO leaderboard_profiles
       (user_id, opted_in, display_mode, updated_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET
       opted_in = excluded.opted_in,
       display_mode = CASE WHEN ? THEN excluded.display_mode ELSE display_mode END,
       updated_at = excluded.updated_at`
  )
    .bind(c.user.userId, optedIn ? 1 : 0, displayMode, now, true)
    .run();

  return ok(c, { optedIn, displayMode });
}

// ---------------------------------------------------------------------------
// GET /leaderboard/me
// ---------------------------------------------------------------------------

export async function leaderboardMe(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const profile = await getProfile(c, c.user.userId);
  const optedIn = profile !== null && profile.opted_in === 1;

  let globalRank: number | null = null;
  if (optedIn) {
    globalRank = await myRank(c, 'alltime', profile!.lifetime_dp);
  }

  return ok(c, {
    optedIn,
    displayMode: profile?.display_mode ?? 'name',
    profile:
      profile === null
        ? null
        : {
            lifetimeDp: profile.lifetime_dp,
            weekDp: profile.week_dp,
            weekAnchor: profile.week_anchor,
            monthDp: profile.month_dp,
            monthAnchor: profile.month_anchor,
            streakDays: profile.streak_days,
            levelName: profile.level_name,
            updatedAt: profile.updated_at,
          },
    globalRank,
    club: optedIn ? await clubSnapshot(c, c.user.userId) : null,
  });
}

// ---------------------------------------------------------------------------
// POST /leaderboard/sync — device DP snapshot mirror
// ---------------------------------------------------------------------------

export async function leaderboardSync(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    lifetimeDp: { type: 'number', required: true, min: DP_LIMITS.lifetime.min, max: DP_LIMITS.lifetime.max, integer: true },
    weekDp: { type: 'number', required: true, min: DP_LIMITS.week.min, max: DP_LIMITS.week.max, integer: true },
    weekAnchor: { type: 'string', required: true, minLength: 7, maxLength: 10 },
    monthDp: { type: 'number', required: true, min: DP_LIMITS.month.min, max: DP_LIMITS.month.max, integer: true },
    monthAnchor: { type: 'string', required: true, minLength: 7, maxLength: 10 },
    streakDays: { type: 'number', required: true, min: DP_LIMITS.streak.min, max: DP_LIMITS.streak.max, integer: true },
    levelName: { type: 'string', required: false, maxLength: DP_LIMITS.levelNameMax },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  const lifetimeDp = v.value.lifetimeDp as number;
  const weekDp = v.value.weekDp as number;
  const weekAnchor = v.value.weekAnchor as string;
  const monthDp = v.value.monthDp as number;
  const monthAnchor = v.value.monthAnchor as string;
  const streakDays = v.value.streakDays as number;
  const levelName = ((v.value.levelName as string | undefined) ?? '').trim().slice(0, DP_LIMITS.levelNameMax) || null;

  if (!WEEK_ANCHOR_RE.test(weekAnchor) || !MONTH_ANCHOR_RE.test(monthAnchor)) {
    return fail(c, 'VALIDATION_FAILED', 'weekAnchor must be YYYY-Www, monthAnchor must be YYYY-MM', 400);
  }

  const existing = await getProfile(c, c.user.userId);
  const now = new Date().toISOString();

  // Monotonic lifetime DP (max) — a reinstall cannot wipe a standing and a
  // tampered client cannot lower someone else's; window DP follows the
  // device's own anchors: a NEW anchor replaces the window (fresh period),
  // the same anchor keeps the max.
  const lifetime = existing === null ? lifetimeDp : Math.max(existing.lifetime_dp, lifetimeDp);
  const nextWeek =
    existing !== null && existing.week_anchor === weekAnchor
      ? Math.max(existing.week_dp, weekDp)
      : weekDp;
  const nextMonth =
    existing !== null && existing.month_anchor === monthAnchor
      ? Math.max(existing.month_dp, monthDp)
      : monthDp;

  await c.env.DB.prepare(
    `INSERT INTO leaderboard_profiles
       (user_id, lifetime_dp, week_dp, week_anchor, month_dp, month_anchor,
        streak_days, level_name, opted_in, display_mode, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'name', ?)
     ON CONFLICT(user_id) DO UPDATE SET
       lifetime_dp  = excluded.lifetime_dp,
       week_dp      = excluded.week_dp,
       week_anchor  = excluded.week_anchor,
       month_dp     = excluded.month_dp,
       month_anchor = excluded.month_anchor,
       streak_days  = excluded.streak_days,
       level_name   = excluded.level_name,
       updated_at   = excluded.updated_at`
  )
    .bind(
      c.user.userId,
      lifetime,
      nextWeek,
      weekAnchor,
      nextMonth,
      monthAnchor,
      streakDays,
      levelName,
      now
    )
    .run();

  return ok(c, { recorded: true, lifetimeDp: lifetime });
}

// ---------------------------------------------------------------------------
// GET /leaderboard/global?window=
// ---------------------------------------------------------------------------

export async function leaderboardGlobal(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const windowParam = c.url.searchParams.get('window') ?? 'alltime';
  if (!(WINDOWS as readonly string[]).includes(windowParam)) {
    return fail(c, 'VALIDATION_FAILED', 'window must be weekly, monthly or alltime', 400);
  }
  const window = windowParam as LeaderboardWindow;
  const col = windowColumn(window);

  const rows = await c.env.DB.prepare(
    `SELECT lp.user_id, u.display_name, lp.level_name, lp.streak_days,
            lp.week_dp, lp.month_dp, lp.lifetime_dp, lp.display_mode
     FROM leaderboard_profiles lp
     JOIN users u ON u.id = lp.user_id
     WHERE lp.opted_in = 1 AND u.status = 'ACTIVE'
     ORDER BY lp.${col} DESC, lp.lifetime_dp DESC
     LIMIT ${TOP_N}`
  ).all<EntryRow>();

  const me = await getProfile(c, c.user.userId);
  const meOptedIn = me !== null && me.opted_in === 1;
  const meValue = me === null ? 0 : windowValue(me, window);
  const meRank = meOptedIn ? await myRank(c, window, meValue) : null;

  return ok(c, {
    window,
    entries: rows.results.map((r, i) => toEntry(r, window, c.user!.userId, i + 1)),
    me: meOptedIn
      ? {
          rank: meRank,
          value: meValue,
          lifetimeDp: me!.lifetime_dp,
          streakDays: me!.streak_days,
          levelName: me!.level_name,
        }
      : null,
  });
}

// ---------------------------------------------------------------------------
// POST /clubs — create
// ---------------------------------------------------------------------------

export async function createClub(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const body = await readJsonBody(c);
  const v = validateFields(body, {
    name: { type: 'string', required: true, minLength: 3, maxLength: 40 },
    description: { type: 'string', required: false, maxLength: 200 },
  });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);

  // Re-validate AFTER trim: a raw "   x   " passes the length spec but
  // would store a 1-char name (same guard searchClubs gets for free).
  const name = (v.value.name as string).trim();
  if (name.length < 3 || name.length > 40) {
    return fail(c, 'VALIDATION_FAILED', 'name must be 3-40 characters (trimmed)', 400);
  }
  const description = ((v.value.description as string | undefined) ?? '').trim();
  const now = new Date().toISOString();

  // One club per user: auto-leave any current membership first (the client
  // flow surfaces this, and club membership is a low-stakes surface).
  await c.env.DB.prepare('DELETE FROM club_members WHERE user_id = ?')
    .bind(c.user.userId)
    .run();

  const clubId = `clb_${randomToken(12)}`;
  let inviteCode = '';
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = randomToken(4).replace(/[^A-Z0-9]/gi, '').toUpperCase().padEnd(6, 'X').slice(0, 6);
    const res = await c.env.DB.prepare(
      `INSERT OR IGNORE INTO clubs (id, name, description, invite_code, created_by, hidden, created_at)
       VALUES (?, ?, ?, ?, ?, 0, ?)`
    )
      .bind(clubId, name, description.length > 0 ? description : null, candidate, c.user.userId, now)
      .run();
    if ((res.meta.changes ?? 0) > 0) {
      inviteCode = candidate;
      break;
    }
  }
  if (inviteCode === '') {
    return fail(c, 'SERVER_ERROR', 'Could not allocate an invite code, try again', 500);
  }

  await c.env.DB.prepare(
    'INSERT INTO club_members (club_id, user_id, joined_at) VALUES (?, ?, ?)'
  )
    .bind(clubId, c.user.userId, now)
    .run();

  return ok(c, {
    club: {
      id: clubId,
      name,
      description: description.length > 0 ? description : null,
      inviteCode,
      memberCount: 1,
      joinedAt: now,
    },
  });
}

// ---------------------------------------------------------------------------
// GET /clubs/mine
// ---------------------------------------------------------------------------

export async function myClub(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);
  return ok(c, { club: await clubSnapshot(c, c.user.userId) });
}

// ---------------------------------------------------------------------------
// GET /clubs/search?q=
// ---------------------------------------------------------------------------

export async function searchClubs(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const q = (c.url.searchParams.get('q') ?? '').trim();
  if (q.length < 2 || q.length > 48) {
    return fail(c, 'VALIDATION_FAILED', 'q must be 2-48 characters', 400);
  }

  // Invite codes are matched exactly; names with a prefix LIKE. Hidden clubs
  // never surface. (m9 pattern: bound ESCAPE char, escaped metacharacters.)
  const codeMatch = /^[A-Z0-9]{6}$/.test(q.toUpperCase()) ? q.toUpperCase() : null;

  const rows = await (codeMatch !== null
    ? c.env.DB.prepare(
        `SELECT c.*, (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count
         FROM clubs c
         WHERE c.hidden = 0 AND c.invite_code = ?
         LIMIT 20`
      ).bind(codeMatch)
    : c.env.DB.prepare(
        `SELECT c.*, (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count
         FROM clubs c
         WHERE c.hidden = 0 AND c.name LIKE ? ESCAPE ?
         ORDER BY member_count DESC, c.created_at ASC
         LIMIT 20`
      ).bind(`${q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`, '\\')
  ).all<ClubRow & { member_count: number }>();

  return ok(c, {
    clubs: rows.results.map((r) => ({
      id: r.id,
      name: r.name,
      description: r.description,
      inviteCode: r.invite_code,
      memberCount: r.member_count,
    })),
  });
}

// ---------------------------------------------------------------------------
// POST /clubs/join/:code
// ---------------------------------------------------------------------------

export async function joinClub(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const code = (c.params.code ?? '').toUpperCase();
  if (!/^[A-Z0-9]{6}$/.test(code)) {
    return fail(c, 'VALIDATION_FAILED', 'Invite codes are 6 letters/digits', 400);
  }

  const club = await c.env.DB.prepare(
    'SELECT * FROM clubs WHERE invite_code = ? AND hidden = 0'
  )
    .bind(code)
    .first<ClubRow>();
  if (club === null) return fail(c, 'NOT_FOUND', 'No club found for that invite code', 404);

  // One club per user — leaving the previous membership is part of joining.
  await c.env.DB.prepare('DELETE FROM club_members WHERE user_id = ?')
    .bind(c.user.userId)
    .run();
  await c.env.DB.prepare(
    `INSERT OR IGNORE INTO club_members (club_id, user_id, joined_at) VALUES (?, ?, ?)`
  )
    .bind(club.id, c.user.userId, new Date().toISOString())
    .run();

  return ok(c, {
    club: {
      id: club.id,
      name: club.name,
      description: club.description,
      inviteCode: club.invite_code,
      joinedAt: new Date().toISOString(),
    },
  });
}

// ---------------------------------------------------------------------------
// POST /clubs/leave
// ---------------------------------------------------------------------------

export async function leaveClub(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const membership = await c.env.DB.prepare(
    'SELECT club_id FROM club_members WHERE user_id = ?'
  )
    .bind(c.user.userId)
    .first<{ club_id: string }>();
  if (membership === null) {
    return fail(c, 'NOT_FOUND', 'You are not in a club', 404);
  }

  await c.env.DB.prepare('DELETE FROM club_members WHERE user_id = ?')
    .bind(c.user.userId)
    .run();

  // Housekeeping: drop clubs that lost their last member (creator included).
  const remaining = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM club_members WHERE club_id = ?'
  )
    .bind(membership.club_id)
    .first<{ n: number }>();
  if ((remaining?.n ?? 1) === 0) {
    await c.env.DB.prepare('DELETE FROM clubs WHERE id = ?')
      .bind(membership.club_id)
      .run();
  }

  return ok(c, { left: true });
}

// ---------------------------------------------------------------------------
// GET /clubs/:id — club detail + leaderboard
// ---------------------------------------------------------------------------

export async function clubDetail(c: Context): Promise<Response> {
  if (c.user === null) return fail(c, 'UNAUTHORIZED', 'Not authenticated', 401);

  const clubId = c.params.id ?? '';
  if (!/^clb_[A-Za-z0-9]{8,32}$/.test(clubId)) {
    return fail(c, 'VALIDATION_FAILED', 'Invalid club id', 400);
  }

  const club = await c.env.DB.prepare('SELECT * FROM clubs WHERE id = ? AND hidden = 0')
    .bind(clubId)
    .first<ClubRow>();
  if (club === null) return fail(c, 'NOT_FOUND', 'Club not found', 404);

  // The invite code is the club's join secret: only members (who already
  // have it) get it back. Non-members can still see the public club view.
  const membership = await c.env.DB.prepare(
    'SELECT 1 AS member FROM club_members WHERE club_id = ? AND user_id = ?'
  )
    .bind(clubId, c.user.userId)
    .first<{ member: number }>();
  const isMember = membership !== null;
  const inviteCode = isMember ? club.invite_code : null;

  const windowParam = c.url.searchParams.get('window') ?? 'alltime';
  if (!(WINDOWS as readonly string[]).includes(windowParam)) {
    return fail(c, 'VALIDATION_FAILED', 'window must be weekly, monthly or alltime', 400);
  }
  const window = windowParam as LeaderboardWindow;
  const col = windowColumn(window);

  const rows = await c.env.DB.prepare(
    `SELECT lp.user_id, u.display_name, lp.level_name, lp.streak_days,
            lp.week_dp, lp.month_dp, lp.lifetime_dp, lp.display_mode
     FROM club_members m
     JOIN leaderboard_profiles lp ON lp.user_id = m.user_id AND lp.opted_in = 1
     JOIN users u ON u.id = m.user_id
     WHERE m.club_id = ?
     ORDER BY lp.${col} DESC, lp.lifetime_dp DESC
     LIMIT ${TOP_N}`
  )
    .bind(clubId)
    .all<EntryRow>();

  const countRow = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM club_members WHERE club_id = ?'
  )
    .bind(clubId)
    .first<{ n: number }>();

  return ok(c, {
    club: {
      id: club.id,
      name: club.name,
      description: club.description,
      inviteCode,
      memberCount: countRow?.n ?? 0,
    },
    isMember,
    window,
    entries: rows.results.map((r, i) => toEntry(r, window, c.user!.userId, i + 1)),
  });
}
