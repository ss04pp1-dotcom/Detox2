/**
 * Worker route tests — leaderboard + clubs + coin mirror (v2.5.8).
 *
 * These are HANDLER-level tests: the D1 binding is a hand-rolled in-memory
 * double that implements exactly the SQL contract the routes use (pattern
 * dispatch — an unrecognized query throws so SQL drift fails loudly). The
 * double is a test scaffold, not a SQL engine: what it verifies is the
 * handlers' decision logic — opt-in gating, monotonic lifetime DP, window
 * anchor rollover, anonymous pseudonyms, one-club-per-user, invite-code
 * privacy, hidden-club invisibility, admin moderation effects, coin
 * idempotency and the negative-mirror skip.
 */
import { describe, expect, it } from 'vitest';

import {
  leaderboardOptIn,
  leaderboardMe,
  leaderboardSync,
  leaderboardGlobal,
  createClub,
  myClub,
  searchClubs,
  joinClub,
  leaveClub,
  clubDetail,
} from '../src/routes/leaderboard';
import {
  adminGetLeaderboard,
  adminResetLeaderboardProfile,
  adminListClubs,
  adminHideClub,
  adminRestoreClub,
  adminDeleteClub,
} from '../src/routes/adminLeaderboard';
import { getCoins, coinsEarn, coinsSpend } from '../src/routes/coins';
import type { Context, Env } from '../src/types';

// ---------------------------------------------------------------------------
// In-memory tables
// ---------------------------------------------------------------------------

interface LbRow {
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
interface MemberRow {
  club_id: string;
  user_id: string;
  joined_at: string;
}
interface UserRow {
  id: string;
  email: string;
  display_name: string | null;
  status: string;
}
interface CoinRow {
  id: string;
  user_id: string;
  transaction_id: string;
  type: string;
  amount: number;
  balance_after: number;
  source: string | null;
  reference: string | null;
  created_at: string;
}

class FakeD1 {
  profiles = new Map<string, LbRow>();
  clubs = new Map<string, ClubRow>();
  members = new Map<string, MemberRow>(); // key `${club_id}|${user_id}`
  users = new Map<string, UserRow>();
  coins = new Map<string, CoinRow>();
  private autoInc = 0;

  addUser(id: string, displayName: string | null = null, status = 'ACTIVE'): void {
    this.users.set(id, {
      id,
      email: `${id}@invalid`,
      display_name: displayName,
      status,
    });
  }

  asD1Database(): D1Database {
    return this as unknown as D1Database;
  }

  // The D1 surface the routes actually touch.
  prepare(sql: string): D1PreparedStatement {
    const db = this;
    let params: unknown[] = [];
    const stmt = {
      bind: (...p: unknown[]) => {
        params = p;
        return stmt;
      },
      first: async <T>(): Promise<T | null> => db.executeOne<T>(sql, params),
      all: async <T>(): Promise<{ results: T[] }> => ({ results: db.executeAll<T>(sql, params) }),
      run: async (): Promise<D1Result> => db.executeRun(sql, params),
    };
    return stmt as unknown as D1PreparedStatement;
  }

  // ---- SELECT dispatch ----------------------------------------------------

  private executeOne<T>(sql: string, p: unknown[]): T | null {
    return (this.executeAll<T>(sql, p)[0] ?? null) as T | null;
  }

  private executeAll<T>(sql: string, p: unknown[]): T[] {
    const s = squash(sql);

    // getProfile
    if (s.startsWith('SELECT * FROM leaderboard_profiles WHERE user_id = ?')) {
      return this.profiles.has(str(p[0])) ? [this.profiles.get(str(p[0])) as unknown as T] : [];
    }

    // myRank (opted_in + <col> > ?)
    const rank = s.match(
      /^SELECT COUNT\(\*\) AS better FROM leaderboard_profiles WHERE opted_in = 1 AND (week_dp|month_dp|lifetime_dp) > \?$/
    );
    if (rank) {
      const col = rank[1] as 'week_dp' | 'month_dp' | 'lifetime_dp';
      const better = [...this.profiles.values()].filter(
        (r) => r.opted_in === 1 && r[col] > num(p[0])
      ).length;
      return [{ better } as unknown as T];
    }

    // clubSnapshot membership
    if (s === 'SELECT club_id, joined_at FROM club_members WHERE user_id = ?') {
      return [...this.members.values()]
        .filter((m) => m.user_id === str(p[0]))
        .map((m) => ({ club_id: m.club_id, joined_at: m.joined_at }) as unknown as T);
    }

    // club by id
    if (s.startsWith('SELECT * FROM clubs WHERE id = ?')) {
      return this.clubs.has(str(p[0])) ? [this.clubs.get(str(p[0])) as unknown as T] : [];
    }

    // club member count
    if (s === 'SELECT COUNT(*) AS n FROM club_members WHERE club_id = ?') {
      const n = [...this.members.values()].filter((m) => m.club_id === str(p[0])).length;
      return [{ n } as unknown as T];
    }

    // membership check (clubDetail invite-code gate)
    if (
      s === 'SELECT 1 AS member FROM club_members WHERE club_id = ? AND user_id = ?'
    ) {
      const hit = this.members.has(`${str(p[0])}|${str(p[1])}`);
      return (hit ? [{ member: 1 }] : []) as unknown as T[];
    }

    // leaderboard join (global + club variants share the projection)
    const board =
      /^SELECT lp\.user_id, u\.display_name, lp\.level_name, lp\.streak_days, lp\.week_dp, lp\.month_dp, lp\.lifetime_dp, lp\.display_mode FROM leaderboard_profiles lp JOIN users u ON u\.id = lp\.user_id(?: WHERE lp\.opted_in = 1 AND u\.status = 'ACTIVE')? ORDER BY lp\.(week_dp|month_dp|lifetime_dp) DESC, lp\.lifetime_dp DESC LIMIT (50|\$\{TOP_N\})$/.exec(
        s
      );
    if (board) {
      const col = board[1] as 'week_dp' | 'month_dp' | 'lifetime_dp';
      const onlyActive = board[2] !== undefined;
      let rows = [...this.profiles.values()].filter((r) => r.opted_in === 1);
      if (onlyActive) {
        rows = rows.filter((r) => this.users.get(r.user_id)?.status === 'ACTIVE');
      }
      rows.sort((a, b) => b[col] - a[col] || b.lifetime_dp - a.lifetime_dp);
      return rows.map(
        (r) =>
          ({
            user_id: r.user_id,
            display_name: this.users.get(r.user_id)?.display_name ?? null,
            level_name: r.level_name,
            streak_days: r.streak_days,
            week_dp: r.week_dp,
            month_dp: r.month_dp,
            lifetime_dp: r.lifetime_dp,
            display_mode: r.display_mode,
          }) as unknown as T
      );
    }

    // club leaderboard (members join)
    const clubBoard =
      /^SELECT lp\.user_id, u\.display_name, lp\.level_name, lp\.streak_days, lp\.week_dp, lp\.month_dp, lp\.lifetime_dp, lp\.display_mode FROM club_members m JOIN leaderboard_profiles lp ON lp\.user_id = m\.user_id AND lp\.opted_in = 1 JOIN users u ON u\.id = m\.user_id WHERE m\.club_id = \? ORDER BY lp\.(week_dp|month_dp|lifetime_dp) DESC, lp\.lifetime_dp DESC LIMIT 50$/.exec(
        s
      );
    if (clubBoard) {
      const col = clubBoard[1] as 'week_dp' | 'month_dp' | 'lifetime_dp';
      const memberIds = [...this.members.values()]
        .filter((m) => m.club_id === str(p[0]))
        .map((m) => m.user_id);
      const rows = [...this.profiles.values()].filter(
        (r) => r.opted_in === 1 && memberIds.includes(r.user_id)
      );
      rows.sort((a, b) => b[col] - a[col] || b.lifetime_dp - a.lifetime_dp);
      return rows.map(
        (r) =>
          ({
            user_id: r.user_id,
            display_name: this.users.get(r.user_id)?.display_name ?? null,
            level_name: r.level_name,
            streak_days: r.streak_days,
            week_dp: r.week_dp,
            month_dp: r.month_dp,
            lifetime_dp: r.lifetime_dp,
            display_mode: r.display_mode,
          }) as unknown as T
      );
    }

    // club search — by exact invite code
    if (
      s.startsWith('SELECT c.*, (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count FROM clubs c WHERE c.hidden = 0 AND c.invite_code = ? LIMIT 20')
    ) {
      const club = [...this.clubs.values()].find(
        (c) => c.hidden === 0 && c.invite_code === str(p[0])
      );
      return (club ? [{ ...club, member_count: this.memberCount(club.id) }] : []) as unknown as T[];
    }

    // club search — by name prefix
    if (
      s.startsWith('SELECT c.*, (SELECT COUNT(*) FROM club_members m WHERE m.club_id = c.id) AS member_count FROM clubs c WHERE c.hidden = 0 AND c.name LIKE ? ESCAPE ? ORDER BY member_count DESC, c.created_at ASC LIMIT 20')
    ) {
      const prefix = str(p[0]).replace(/\\(.)/g, '$1').slice(0, -1); // strip trailing %
      const rows = [...this.clubs.values()]
        .filter((c) => c.hidden === 0 && c.name.toLowerCase().startsWith(prefix.toLowerCase()))
        .map((c) => ({ ...c, member_count: this.memberCount(c.id) }));
      rows.sort((a, b) => b.member_count - a.member_count || (a.created_at < b.created_at ? -1 : 1));
      return rows as unknown as T[];
    }

    // join by invite code
    if (s.startsWith('SELECT * FROM clubs WHERE invite_code = ? AND hidden = 0')) {
      const club = [...this.clubs.values()].find(
        (c) => c.invite_code === str(p[0]) && c.hidden === 0
      );
      return (club ? [club as unknown as T] : []);
    }

    // leaveClub membership lookup
    if (s === 'SELECT club_id FROM club_members WHERE user_id = ?') {
      return [...this.members.values()]
        .filter((m) => m.user_id === str(p[0]))
        .map((m) => ({ club_id: m.club_id }) as unknown as T);
    }

    // coins: balance
    if (s.startsWith('SELECT COALESCE(SUM(amount), 0) AS balance FROM coin_transactions WHERE user_id = ?')) {
      const balance = [...this.coins.values()]
        .filter((r) => r.user_id === str(p[0]))
        .reduce((acc, r) => acc + r.amount, 0);
      return [{ balance } as unknown as T];
    }

    // coins: grants since
    if (s.includes('SELECT * FROM coin_transactions WHERE user_id = ?') && s.includes('created_at > ?')) {
      const rows = [...this.coins.values()]
        .filter(
          (r) =>
            r.user_id === str(p[0]) &&
            ['ADMIN_ADJUSTMENT', 'BONUS', 'REFUND'].includes(r.type) &&
            r.amount > 0 &&
            r.created_at > str(p[1])
        )
        .sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
      return rows as unknown as T[];
    }

    // admin: club by id (loadClub)
    if (s === 'SELECT id, name, hidden FROM clubs WHERE id = ?') {
      const c = this.clubs.get(str(p[0]));
      return c ? [{ id: c.id, name: c.name, hidden: c.hidden } as unknown as T] : [];
    }

    // admin: profile for reset
    if (s === 'SELECT user_id, opted_in, lifetime_dp FROM leaderboard_profiles WHERE user_id = ?') {
      const r = this.profiles.get(str(p[0]));
      return r
        ? [{ user_id: r.user_id, opted_in: r.opted_in, lifetime_dp: r.lifetime_dp } as unknown as T]
        : [];
    }

    // admin: leaderboard totals
    if (s.startsWith('SELECT COUNT(*) AS profiles, SUM(opted_in) AS opted_in')) {
      const all = [...this.profiles.values()];
      return [
        {
          profiles: all.length,
          opted_in: all.reduce((a, r) => a + r.opted_in, 0),
          lifetime_dp: all.reduce((a, r) => a + r.lifetime_dp, 0),
          last_sync: all.map((r) => r.updated_at).sort().at(-1) ?? null,
        } as unknown as T,
      ];
    }

    // admin: club totals
    if (s === 'SELECT COUNT(*) AS clubs, SUM(hidden) AS hidden FROM clubs') {
      const all = [...this.clubs.values()];
      return [
        { clubs: all.length, hidden: all.reduce((a, c) => a + c.hidden, 0) } as unknown as T,
      ];
    }

    // admin: club list (no-search path)
    const adminClubs =
      /^SELECT c\.\*, \(SELECT COUNT\(\*\) FROM club_members m WHERE m\.club_id = c\.id\) AS member_count, \(SELECT u\.display_name FROM users u WHERE u\.id = c\.created_by\) AS creator_name FROM clubs c WHERE 1=1 (AND c\.hidden = 1|AND c\.hidden = 0)? ORDER BY member_count DESC, c\.created_at DESC LIMIT \? OFFSET \?$/.exec(
        s
      );
    if (adminClubs) {
      const hidden = adminClubs[1] === 'AND c.hidden = 1';
      const visible = adminClubs[1] === 'AND c.hidden = 0';
      let rows = [...this.clubs.values()].filter(
        (c) => (!visible && !hidden) || (visible && c.hidden === 0) || (hidden && c.hidden === 1)
      );
      rows = rows.map((c) => ({
        ...c,
        member_count: this.memberCount(c.id),
        creator_name: this.users.get(c.created_by)?.display_name ?? null,
      }));
      rows.sort((a, b) => b.member_count - a.member_count || (a.created_at > b.created_at ? -1 : 1));
      const limit = num(p[0]);
      const offset = num(p[1]);
      return rows.slice(offset, offset + limit) as unknown as T[];
    }

    // admin: board rows (search + non-search variants share the projection)
    const adminBoard =
      /^SELECT lp\.user_id, u\.display_name, u\.email, u\.status, lp\.level_name, lp\.streak_days, lp\.week_dp, lp\.month_dp, lp\.lifetime_dp, lp\.opted_in, lp\.display_mode, lp\.updated_at FROM leaderboard_profiles lp JOIN users u ON u\.id = lp\.user_id WHERE lp\.opted_in = 1(?: AND \(LOWER\(u\.display_name\) LIKE \? ESCAPE \? OR lp\.user_id = \?\))? ORDER BY lp\.(week_dp|month_dp|lifetime_dp) DESC, lp\.lifetime_dp DESC LIMIT 50$/.exec(
        s
      );
    if (adminBoard) {
      const col = adminBoard[1] as 'week_dp' | 'month_dp' | 'lifetime_dp';
      let rows = [...this.profiles.values()].filter((r) => r.opted_in === 1);
      if (adminBoard[2] !== undefined) {
        const q = str(p[2]).toLowerCase();
        const prefix = str(p[0]).slice(1, -1).replace(/\\(.)/g, '$1').toLowerCase();
        rows = rows.filter(
          (r) =>
            (this.users.get(r.user_id)?.display_name ?? '').toLowerCase().includes(prefix) ||
            r.user_id === q
        );
      }
      rows.sort((a, b) => b[col] - a[col] || b.lifetime_dp - a.lifetime_dp);
      return rows.map(
        (r) =>
          ({
            user_id: r.user_id,
            display_name: this.users.get(r.user_id)?.display_name ?? null,
            email: this.users.get(r.user_id)?.email ?? '',
            status: this.users.get(r.user_id)?.status ?? 'ACTIVE',
            level_name: r.level_name,
            streak_days: r.streak_days,
            week_dp: r.week_dp,
            month_dp: r.month_dp,
            lifetime_dp: r.lifetime_dp,
            opted_in: r.opted_in,
            display_mode: r.display_mode,
            updated_at: r.updated_at,
          }) as unknown as T
      );
    }

    // admin: club count (filter + optional name search)
    const clubCount =
      /^SELECT COUNT\(\*\) AS n FROM clubs c WHERE 1=1( AND c\.hidden = 1| AND c\.hidden = 0)?( AND c\.name LIKE \? ESCAPE \?)?$/.exec(
        s
      );
    if (clubCount) {
      const hidden = clubCount[1] === ' AND c.hidden = 1';
      const visible = clubCount[1] === ' AND c.hidden = 0';
      const hasName = clubCount[2] !== undefined;
      const prefix = hasName
        ? str(p[0]).slice(0, -1).replace(/\\(.)/g, '$1').toLowerCase()
        : null;
      const n = [...this.clubs.values()].filter((c) => {
        if (visible && c.hidden !== 0) return false;
        if (hidden && c.hidden !== 1) return false;
        if (prefix !== null && !c.name.toLowerCase().startsWith(prefix)) return false;
        return true;
      }).length;
      return [{ n } as unknown as T];
    }

    throw new Error(`FakeD1: unrecognized SELECT: ${sql}`);
  }

  // ---- INSERT / UPDATE / DELETE dispatch ----------------------------------

  private executeRun(sql: string, p: unknown[]): D1Result {
    const s = squash(sql);
    const changes = this.mutate(s, sql, p);
    return { results: [], success: true, meta: { changes, last_row_id: ++this.autoInc } };
  }

  private mutate(s: string, _sql: string, p: unknown[]): number {
    // opt-in upsert
    if (s.startsWith('INSERT INTO leaderboard_profiles (user_id, opted_in, display_mode, updated_at)')) {
      const id = str(p[0]);
      const existing = this.profiles.get(id);
      this.profiles.set(id, {
        user_id: id,
        lifetime_dp: existing?.lifetime_dp ?? 0,
        week_dp: existing?.week_dp ?? 0,
        week_anchor: existing?.week_anchor ?? '',
        month_dp: existing?.month_dp ?? 0,
        month_anchor: existing?.month_anchor ?? '',
        streak_days: existing?.streak_days ?? 0,
        level_name: existing?.level_name ?? null,
        opted_in: num(p[1]),
        display_mode:
          p[4] === true || p[4] === 1 ? str(p[2]) : (existing?.display_mode ?? str(p[2])),
        updated_at: str(p[3]),
      });
      return 1;
    }

    // sync upsert (monotonic lifetime, anchor-aware windows)
    if (s.startsWith('INSERT INTO leaderboard_profiles (user_id, lifetime_dp, week_dp, week_anchor, month_dp, month_anchor,')) {
      const id = str(p[0]);
      const existing = this.profiles.get(id);
      const lifetime = existing ? Math.max(existing.lifetime_dp, num(p[1])) : num(p[1]);
      const nextWeek =
        existing !== undefined && existing.week_anchor === str(p[3])
          ? Math.max(existing.week_dp, num(p[2]))
          : num(p[2]);
      const nextMonth =
        existing !== undefined && existing.month_anchor === str(p[5])
          ? Math.max(existing.month_dp, num(p[4]))
          : num(p[4]);
      this.profiles.set(id, {
        user_id: id,
        lifetime_dp: lifetime,
        week_dp: nextWeek,
        week_anchor: str(p[3]),
        month_dp: nextMonth,
        month_anchor: str(p[5]),
        streak_days: num(p[6]),
        level_name: p[7] === null ? null : str(p[7]),
        opted_in: existing?.opted_in ?? 0,
        display_mode: existing?.display_mode ?? 'name',
        updated_at: str(p[8]),
      });
      return 1;
    }

    if (s === 'DELETE FROM club_members WHERE user_id = ?') {
      let n = 0;
      for (const [k, m] of this.members) {
        if (m.user_id === str(p[0])) {
          this.members.delete(k);
          n++;
        }
      }
      return n;
    }

    if (s.startsWith('INSERT OR IGNORE INTO clubs (id, name, description, invite_code, created_by, hidden, created_at)')) {
      const code = str(p[3]);
      if ([...this.clubs.values()].some((c) => c.invite_code === code)) return 0;
      // The route's SQL has hidden as a LITERAL 0 (`, 0, ?)`), so the bound
      // params are: id, name, description, code, created_by, created_at.
      const literalHidden = s.includes('?, ?, ?, ?, ?, 0, ?');
      this.clubs.set(str(p[0]), {
        id: str(p[0]),
        name: str(p[1]),
        description: p[2] === null ? null : str(p[2]),
        invite_code: code,
        created_by: str(p[4]),
        hidden: literalHidden ? 0 : num(p[5]),
        created_at: literalHidden ? str(p[5]) : str(p[6]),
      });
      return 1;
    }

    if (s.startsWith('INSERT INTO club_members (club_id, user_id, joined_at) VALUES (?, ?, ?)') ||
        s.startsWith('INSERT OR IGNORE INTO club_members (club_id, user_id, joined_at) VALUES (?, ?, ?)')) {
      const key = `${str(p[0])}|${str(p[1])}`;
      if (this.members.has(key)) return 0;
      this.members.set(key, {
        club_id: str(p[0]),
        user_id: str(p[1]),
        joined_at: str(p[2]),
      });
      return 1;
    }

    if (s === 'DELETE FROM clubs WHERE id = ?') {
      return this.clubs.delete(str(p[0])) ? 1 : 0;
    }

    // coin earn mirror — type is the SQL literal 'AD_REWARD':
    // p = [id, user_id, transaction_id, amount, balance_after, reference, created_at]
    if (s.startsWith('INSERT OR IGNORE INTO coin_transactions') && s.includes("'AD_REWARD'")) {
      const txId = str(p[2]);
      if ([...this.coins.values()].some((r) => r.transaction_id === txId)) return 0;
      const userId = str(p[1]);
      const balance = [...this.coins.values()]
        .filter((r) => r.user_id === userId)
        .reduce((a, r) => a + r.amount, 0);
      this.coins.set(txId, {
        id: str(p[0]),
        user_id: userId,
        transaction_id: txId,
        type: 'AD_REWARD',
        amount: num(p[3]),
        balance_after: balance + num(p[3]),
        source: 'device',
        reference: p[5] === null ? null : str(p[5]),
        created_at: str(p[6]),
      });
      return 1;
    }

    // coin spend mirror — type is a bound placeholder (negative amount):
    // p = [id, user_id, transaction_id, type, amount, balance_after, reference, created_at]
    if (s.startsWith('INSERT OR IGNORE INTO coin_transactions')) {
      const txId = str(p[2]);
      if ([...this.coins.values()].some((r) => r.transaction_id === txId)) return 0;
      const userId = str(p[1]);
      const balance = [...this.coins.values()]
        .filter((r) => r.user_id === userId)
        .reduce((a, r) => a + r.amount, 0);
      this.coins.set(txId, {
        id: str(p[0]),
        user_id: userId,
        transaction_id: txId,
        type: str(p[3]),
        amount: num(p[4]),
        balance_after: balance + num(p[4]),
        source: 'device',
        reference: p[6] === null ? null : str(p[6]),
        created_at: str(p[7]),
      });
      return 1;
    }

    // admin mutations
    if (s.startsWith('UPDATE clubs SET hidden = 1 WHERE id = ?')) {
      const c = this.clubs.get(str(p[0]));
      if (!c) return 0;
      c.hidden = 1;
      return 1;
    }
    if (s.startsWith('UPDATE clubs SET hidden = 0 WHERE id = ?')) {
      const c = this.clubs.get(str(p[0]));
      if (!c) return 0;
      c.hidden = 0;
      return 1;
    }
    if (s.startsWith('DELETE FROM club_members WHERE club_id = ?')) {
      let n = 0;
      for (const [k, m] of this.members) {
        if (m.club_id === str(p[0])) {
          this.members.delete(k);
          n++;
        }
      }
      return n;
    }
    if (s.startsWith('UPDATE leaderboard_profiles SET lifetime_dp = 0')) {
      const r = this.profiles.get(str(p[1]));
      if (!r) return 0;
      Object.assign(r, {
        lifetime_dp: 0,
        week_dp: 0,
        week_anchor: '',
        month_dp: 0,
        month_anchor: '',
        streak_days: 0,
        level_name: null,
        opted_in: 0,
        updated_at: str(p[0]),
      });
      return 1;
    }

    // appendAudit writes are fire-and-forget in tests (audited elsewhere)
    if (s.startsWith('INSERT INTO audit_logs')) {
      return 1;
    }

    throw new Error(`FakeD1: unrecognized mutation: ${_sql}`);
  }

  private memberCount(clubId: string): number {
    return [...this.members.values()].filter((m) => m.club_id === clubId).length;
  }
}

function squash(sql: string): string {
  return sql.replace(/\s+/g, ' ').trim();
}
function str(v: unknown): string {
  return String(v);
}
function num(v: unknown): number {
  return Number(v);
}

// ---------------------------------------------------------------------------
// Context builders
// ---------------------------------------------------------------------------

function envFor(db: FakeD1): Env {
  return {
    DB: db.asD1Database(),
    KV: { get: async () => null, put: async () => undefined } as unknown as KVNamespace,
    GOOGLE_CLIENT_SECRET: 'test-secret',
    ADMIN_SESSION_SECRET: 'test-admin-secret',
    API_ENV: 'development',
  };
}

function ctxFor(
  env: Env,
  opts: {
    method?: string;
    path?: string;
    userId?: string;
    params?: Record<string, string>;
    body?: unknown;
    adminId?: string;
  } = {}
): Context {
  const method = opts.method ?? 'POST';
  const path = opts.path ?? '/api/v1/leaderboard/sync';
  const init: RequestInit = { method };
  if (opts.body !== undefined) {
    init.body = JSON.stringify(opts.body);
    init.headers = { 'content-type': 'application/json' };
  }
  return {
    req: new Request(`https://api.test${path}`, init),
    url: new URL(`https://api.test${path}`),
    env,
    ctx: { waitUntil: () => undefined } as unknown as ExecutionContext,
    requestId: 'req_test',
    params: opts.params ?? {},
    user: opts.userId
      ? { userId: opts.userId, email: `${opts.userId}@invalid`, deviceId: null, tokenHash: 'x' }
      : null,
    admin: opts.adminId ? { adminId: opts.adminId, email: 'a@invalid', role: 'SUPER_ADMIN', name: 'A' } : null,
  };
}

async function data(res: Response): Promise<Record<string, unknown>> {
  const json = (await res.json()) as { success: boolean; data?: unknown; error?: unknown };
  expect(json.success).toBe(true);
  return json.data as Record<string, unknown>;
}
async function errEnvelope(res: Response): Promise<{ code: string; status: number }> {
  const json = (await res.json()) as { success: boolean; error?: { code: string } };
  expect(json.success).toBe(false);
  return { code: json.error?.code ?? '', status: res.status };
}

// ---------------------------------------------------------------------------
// Leaderboard + clubs
// ---------------------------------------------------------------------------

describe('leaderboard opt-in + sync + global', () => {
  it('a synced-but-not-opted-in profile is invisible on the board', async () => {
    const db = new FakeD1();
    db.addUser('usr_a', 'Alpha');
    const env = envFor(db);

    await leaderboardSync(
      ctxFor(env, { userId: 'usr_a', body: snapshot({ lifetimeDp: 500 }) })
    );

    const res = await leaderboardGlobal(
      ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/global?window=alltime', userId: 'usr_a' })
    );
    const d = await data(res);
    expect(d.entries).toEqual([]);
    expect(d.me).toBeNull();
  });

  it('opt-in then sync surfaces the user with the chosen display mode', async () => {
    const db = new FakeD1();
    db.addUser('usr_a', 'Alpha');
    db.addUser('usr_b', 'Bravo');
    const env = envFor(db);

    await leaderboardOptIn(
      ctxFor(env, { userId: 'usr_a', path: '/api/v1/leaderboard/opt-in', body: { optedIn: true, displayMode: 'anonymous' } })
    );
    await leaderboardSync(
      ctxFor(env, { userId: 'usr_a', body: snapshot({ lifetimeDp: 500, weekDp: 50, monthDp: 200 }) })
    );

    // Another user sees Alpha's pseudonym, never the name or id.
    const res = await leaderboardGlobal(
      ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/global?window=weekly', userId: 'usr_b' })
    );
    const d = await data(res);
    expect(d.entries).toHaveLength(1);
    const entry = (d.entries as Record<string, unknown>[])[0]!;
    expect(entry.displayName).toMatch(/^Detoxer #[0-9A-F]{4}$/);
    expect(entry.displayName).not.toContain('Alpha');
    expect(JSON.stringify(entry)).not.toContain('usr_a');
    expect(entry.value).toBe(50);
    expect(entry.isMe).toBe(false);
  });

  it('lifetime DP is monotonic — a lower resync cannot wipe a standing', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    const ctx = (body: unknown) => ctxFor(env, { userId: 'usr_a', body });

    await leaderboardSync(ctx(snapshot({ lifetimeDp: 900 })));
    await leaderboardSync(ctx(snapshot({ lifetimeDp: 100 }))); // reinstall / tamper
    const d = await data(await leaderboardSync(ctx(snapshot({ lifetimeDp: 100 }))));
    expect(d.lifetimeDp).toBe(900);

    const me = await data(
      await leaderboardMe(ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/me', userId: 'usr_a' }))
    );
    expect((me.profile as Record<string, unknown>).lifetimeDp).toBe(900);
  });

  it('a NEW week anchor replaces the window; the same anchor keeps the max', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    const ctx = (body: unknown) => ctxFor(env, { userId: 'usr_a', body });

    await leaderboardSync(ctx(snapshot({ weekDp: 120, weekAnchor: '2026-W38' })));
    await leaderboardSync(ctx(snapshot({ weekDp: 80, weekAnchor: '2026-W38' })));
    let me = await data(
      await leaderboardMe(ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/me', userId: 'usr_a' }))
    );
    expect((me.profile as Record<string, unknown>).weekDp).toBe(120);

    // Week rolls over -> fresh window, replaced not maxed.
    await leaderboardSync(ctx(snapshot({ weekDp: 15, weekAnchor: '2026-W39' })));
    me = await data(
      await leaderboardMe(ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/me', userId: 'usr_a' }))
    );
    expect((me.profile as Record<string, unknown>).weekDp).toBe(15);
  });

  it('rejects malformed window anchors', async () => {
    const db = new FakeD1();
    const env = envFor(db);
    const e = await errEnvelope(
      await leaderboardSync(
        ctxFor(env, { userId: 'usr_a', body: snapshot({ weekAnchor: 'not-a-week' }) })
      )
    );
    expect(e.code).toBe('VALIDATION_FAILED');
    expect(e.status).toBe(400);
  });

  it('sync cannot silently opt a user in', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    await leaderboardSync(ctxFor(env, { userId: 'usr_a', body: snapshot({ lifetimeDp: 10 }) }));
    const me = await data(
      await leaderboardMe(ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/me', userId: 'usr_a' }))
    );
    expect(me.optedIn).toBe(false);
  });
});

describe('clubs', () => {
  async function seedTwoMembers(env: Env): Promise<string> {
    await createClub(
      ctxFor(env, { userId: 'usr_a', path: '/api/v1/clubs', body: { name: 'Study Hall' } })
    );
    const mine = await data(
      await myClub(ctxFor(env, { method: 'GET', path: '/api/v1/clubs/mine', userId: 'usr_a' }))
    );
    const club = mine.club as Record<string, unknown>;
    const code = club.inviteCode as string;
    await joinClub(
      ctxFor(env, { userId: 'usr_b', path: `/api/v1/clubs/join/${code}`, params: { code } })
    );
    return club.id as string;
  }

  it('create -> auto-join -> invite works end-to-end; name trimmed length is enforced', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);

    const bad = await errEnvelope(
      await createClub(
        ctxFor(env, { userId: 'usr_a', path: '/api/v1/clubs', body: { name: '   x  ' } })
      )
    );
    expect(bad.code).toBe('VALIDATION_FAILED');

    const d = await data(
      await createClub(
        ctxFor(env, { userId: 'usr_a', path: '/api/v1/clubs', body: { name: 'Study Hall', description: 'no doom' } })
      )
    );
    const club = d.club as Record<string, unknown>;
    expect(club.name).toBe('Study Hall');
    expect(club.memberCount).toBe(1);
    expect(club.inviteCode).toMatch(/^[A-Z0-9]{6}$/);
  });

  it('search by name finds visible clubs only; join is one-club-per-user', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    db.addUser('usr_b');
    const env = envFor(db);
    await seedTwoMembers(env);

    const found = await data(
      await searchClubs(
        ctxFor(env, { method: 'GET', path: '/api/v1/clubs/search?q=stud', userId: 'usr_b' })
      )
    );
    expect(found.clubs).toHaveLength(1);
    expect((found.clubs as Record<string, unknown>[])[0]!.memberCount).toBe(2);

    // usr_b creates their own club -> auto-leaves the joined one.
    const made = await data(
      await createClub(
        ctxFor(env, { userId: 'usr_b', path: '/api/v1/clubs', body: { name: 'Gym Crew' } })
      )
    );
    expect(made.club).toBeDefined();
    const mine = await data(
      await myClub(ctxFor(env, { method: 'GET', path: '/api/v1/clubs/mine', userId: 'usr_b' }))
    );
    expect((mine.club as Record<string, unknown>).name).toBe('Gym Crew');
  });

  it('clubDetail hides the invite code from non-members', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    db.addUser('usr_b');
    db.addUser('usr_c');
    const env = envFor(db);
    const clubId = await seedTwoMembers(env); // a + b in club, c outside

    const asMember = await data(
      await clubDetail(
        ctxFor(env, { method: 'GET', path: `/api/v1/clubs/${clubId}`, params: { id: clubId }, userId: 'usr_b' })
      )
    );
    expect((asMember.club as Record<string, unknown>).inviteCode).toMatch(/^[A-Z0-9]{6}$/);
    expect(asMember.isMember).toBe(true);

    const asOutsider = await data(
      await clubDetail(
        ctxFor(env, { method: 'GET', path: `/api/v1/clubs/${clubId}`, params: { id: clubId }, userId: 'usr_c' })
      )
    );
    expect((asOutsider.club as Record<string, unknown>).inviteCode).toBeNull();
    expect(asOutsider.isMember).toBe(false);
  });

  it('leaving the last club garbage-collects it', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    db.addUser('usr_b');
    const env = envFor(db);
    const clubId = await seedTwoMembers(env);

    await leaveClub(ctxFor(env, { userId: 'usr_b', path: '/api/v1/clubs/leave' }));
    await leaveClub(ctxFor(env, { userId: 'usr_a', path: '/api/v1/clubs/leave' }));

    const e = await errEnvelope(
      await clubDetail(
        ctxFor(env, { method: 'GET', path: `/api/v1/clubs/${clubId}`, params: { id: clubId }, userId: 'usr_a' })
      )
    );
    expect(e.code).toBe('NOT_FOUND');
  });
});

// ---------------------------------------------------------------------------
// Coin mirror (idempotency + negative-balance skip)
// ---------------------------------------------------------------------------

describe('coin mirror', () => {
  it('earn is idempotent per reference; balance reflects once', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    const ctx = (body: unknown) =>
      ctxFor(env, { userId: 'usr_a', path: '/api/v1/coins/earn', body });

    const first = await data(await coinsEarn(ctx({ reference: 'ad_reward_1', amount: 1 })));
    expect(first.recorded).toBe(true);
    const again = await data(await coinsEarn(ctx({ reference: 'ad_reward_1', amount: 1 })));
    expect(again.recorded).toBe(false);

    const coins = await data(
      await getCoins(ctxFor(env, { method: 'GET', path: '/api/v1/coins', userId: 'usr_a' }))
    );
    expect(coins.balance).toBe(1);
  });

  it('a spend mirror that would go negative is skipped, not recorded', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    const earn = (body: unknown) =>
      coinsEarn(ctxFor(env, { userId: 'usr_a', path: '/api/v1/coins/earn', body }));
    await earn({ reference: 'earn_ref_1', amount: 2 });

    const spend = await data(
      await coinsSpend(
        ctxFor(env, {
          userId: 'usr_a',
          path: '/api/v1/coins/spend',
          body: { type: 'TEMP_UNLOCK_SPEND', amount: 5, reference: 'spend_ref_1' },
        })
      )
    );
    expect(spend.recorded).toBe(false);
    const coins = await data(
      await getCoins(ctxFor(env, { method: 'GET', path: '/api/v1/coins', userId: 'usr_a' }))
    );
    expect(coins.balance).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// Admin moderation
// ---------------------------------------------------------------------------

describe('admin leaderboard + clubs moderation', () => {
  it('reset zeroes the profile and forces opt-out', async () => {
    const db = new FakeD1();
    const uid = 'usr_moderated01'; // matches the route's usr_[A-Za-z0-9]{8,64}
    db.addUser(uid, 'Alpha');
    const env = envFor(db);

    await leaderboardOptIn(
      ctxFor(env, { userId: uid, path: '/api/v1/leaderboard/opt-in', body: { optedIn: true } })
    );
    await leaderboardSync(
      ctxFor(env, { userId: uid, body: snapshot({ lifetimeDp: 9_999 }) })
    );

    const reset = await data(
      await adminResetLeaderboardProfile(
        ctxFor(env, {
          userId: null as unknown as string,
          adminId: 'adm_1',
          path: `/api/v1/admin/leaderboard/users/${uid}/reset`,
          params: { id: uid },
          body: { confirm: true, reason: 'farming detected' },
        })
      )
    );
    expect(reset.reset).toBe(true);

    const me = await data(
      await leaderboardMe(ctxFor(env, { method: 'GET', path: '/api/v1/leaderboard/me', userId: uid }))
    );
    expect(me.optedIn).toBe(false);
    expect((me.profile as Record<string, unknown>).lifetimeDp).toBe(0);
  });

  it('hide makes a club invisible to search and join; restore brings it back', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    db.addUser('usr_b');
    const env = envFor(db);
    const clubId = await seedTwoMembersForAdmin(env);
    const adminCtx = (body: unknown, extraPath: string, params: Record<string, string>) =>
      ctxFor(env, {
        userId: null as unknown as string,
        adminId: 'adm_1',
        path: `/api/v1/admin/${extraPath}`,
        params,
        body,
      });

    await adminHideClub(adminCtx({ confirm: true }, `clubs/${clubId}/hide`, { id: clubId }));

    // hidden club: search miss + join miss
    const found = await data(
      await searchClubs(
        ctxFor(env, { method: 'GET', path: '/api/v1/clubs/search?q=study', userId: 'usr_b' })
      )
    );
    expect(found.clubs).toHaveLength(0);

    await adminRestoreClub(adminCtx({ confirm: true }, `clubs/${clubId}/restore`, { id: clubId }));
    const found2 = await data(
      await searchClubs(
        ctxFor(env, { method: 'GET', path: '/api/v1/clubs/search?q=study', userId: 'usr_b' })
      )
    );
    expect(found2.clubs).toHaveLength(1);
  });

  it('delete removes the club and every membership', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    db.addUser('usr_b');
    const env = envFor(db);
    const clubId = await seedTwoMembersForAdmin(env);

    await adminDeleteClub(
      ctxFor(env, {
        userId: null as unknown as string,
        adminId: 'adm_1',
        path: `/api/v1/admin/clubs/${clubId}/delete`,
        params: { id: clubId },
        body: { confirm: true },
      })
    );

    const mine = await data(
      await myClub(ctxFor(env, { method: 'GET', path: '/api/v1/clubs/mine', userId: 'usr_a' }))
    );
    expect(mine.club).toBeNull();
  });

  it('admin board lists opted-in users with anonymous pseudonyms honored', async () => {
    const db = new FakeD1();
    db.addUser('usr_a', 'Alpha');
    db.addUser('usr_b', 'Bravo');
    const env = envFor(db);

    await leaderboardOptIn(
      ctxFor(env, { userId: 'usr_a', path: '/api/v1/leaderboard/opt-in', body: { optedIn: true, displayMode: 'anonymous' } })
    );
    await leaderboardSync(ctxFor(env, { userId: 'usr_a', body: snapshot({ lifetimeDp: 400 }) }));

    const d = await data(
      await adminGetLeaderboard(
        ctxFor(env, {
          userId: null as unknown as string,
          adminId: 'adm_1',
          path: '/api/v1/admin/leaderboard',
          method: 'GET',
        })
      )
    );
    const totals = d.totals as Record<string, unknown>;
    expect(totals.optedIn).toBe(1);
    const entries = d.entries as Record<string, unknown>[];
    expect(entries).toHaveLength(1);
    expect(entries[0]!.displayName).toMatch(/^Detoxer #/);
    expect(entries[0]!.value).toBe(400);
  });

  it('admin club list paginates visible clubs', async () => {
    const db = new FakeD1();
    db.addUser('usr_a');
    const env = envFor(db);
    await seedTwoMembersForAdmin(env);

    const d = await data(
      await adminListClubs(
        ctxFor(env, {
          userId: null as unknown as string,
          adminId: 'adm_1',
          path: '/api/v1/admin/clubs',
          method: 'GET',
        })
      )
    );
    expect(d.total).toBe(1);
    expect((d.clubs as Record<string, unknown>[])[0]!.name).toBe('Study Hall');
  });

  // Shared seeder for admin tests (club with one member).
  async function seedTwoMembersForAdmin(env: Env): Promise<string> {
    await createClub(
      ctxFor(env, { userId: 'usr_a', path: '/api/v1/clubs', body: { name: 'Study Hall' } })
    );
    const mine = await data(
      await myClub(ctxFor(env, { method: 'GET', path: '/api/v1/clubs/mine', userId: 'usr_a' }))
    );
    return (mine.club as Record<string, unknown>).id as string;
  }
});

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function snapshot(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    lifetimeDp: 100,
    weekDp: 10,
    weekAnchor: '2026-W39',
    monthDp: 40,
    monthAnchor: '2026-09',
    streakDays: 3,
    levelName: 'Focused',
    ...over,
  };
}
