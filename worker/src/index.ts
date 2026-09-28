/**
 * MAXLEVEL DETOX — Worker entry point + router.
 *
 * Hand-rolled router (zero runtime deps):
 *   fetch -> requestId -> route match -> middleware chain -> handler -> envelope
 *
 * Also maintains per-day KV counters (req_count_<date>_<env>,
 * err_count_<date>_<env>) used by the admin overview dashboard. These are
 * best-effort observability counters (KV has no atomic increment), not
 * billing-grade metrics.
 */

import { Context, Env, Handler, Middleware } from './types';
import { fail, newRequestId, preflightResponse } from './utils/response';
import { rateLimit } from './middleware/rateLimit';
import { requireUser } from './middleware/auth';
import { requireAdmin, requireAdminSession } from './middleware/adminAuth';
import { appendSecurityEvent } from './services/audit';

import {
  authGoogle,
  authRegister,
  authLogin,
  authRefresh,
  authLogout,
  getMe,
  updateMe,
  devicesRegister,
  devicesHeartbeat,
  getConfig,
  getFlags,
  getAppVersion,
  getAnnouncements,
  postEvents,
  subscriptionVerify,
  getSubscription,
  deleteMe,
} from './routes/app';

// v2.2 Phase D — plan catalog, device-bound trial, manual bKash gateway.
import {
  getPlans,
  getTrial,
  claimTrial,
} from './routes/plans';
import {
  bkashInstructions,
  bkashInit,
  bkashSubmit,
  bkashCancel,
  bkashStatus,
} from './routes/payments';

// v2.5 r9 — Social Sentry parity engagement: Sinthia AI chat proxy,
// community commits, friends, referral.
import {
  aiChat,
  listCommits,
  createCommit,
  cheerCommit,
  reportCommit,
  listFriends,
  searchUsers,
  sendFriendRequest,
  acceptFriendRequest,
  removeFriend,
  getReferral,
  applyReferral,
  claimReferral,
} from './routes/community';

// v2.5.7 audit-fix round (C-3 + H-2): server coin mirror + user-side
// support tickets.
import { getCoins, coinsEarn, coinsSpend } from './routes/coins';
import { createSupportTicket, listMySupportTickets } from './routes/support';

// v2.5.8 roadmap — dynamic detection rules + community leaderboard/clubs.
import {
  getDetectionRules,
  adminGetDetectionRules,
  adminSaveDetectionRulesDraft,
  adminPublishDetectionRules,
  adminResetDetectionRules,
} from './routes/detection';
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
} from './routes/leaderboard';

// v2.5.8 roadmap — admin moderation surface for leaderboard + clubs.
import {
  adminGetLeaderboard,
  adminResetLeaderboardProfile,
  adminListClubs,
  adminHideClub,
  adminRestoreClub,
  adminDeleteClub,
} from './routes/adminLeaderboard';

import {
  adminLogin,
  adminLogout,
  adminOverview,
  adminListUsers,
  adminGetUser,
  adminPatchUser,
  adminAdjustCoins,
  adminListDevices,
  adminListSubscriptions,
  adminPatchCommit,
  adminGetConfig,
  adminSaveConfigDraft,
  adminPublishConfig,
  adminRollbackConfig,
  adminListFlags,
  adminPatchFlag,
  adminListAnnouncements,
  adminCreateAnnouncement,
  adminPatchAnnouncement,
  adminAnalytics,
  adminAuditLogs,
  adminSecurityEvents,
  adminListTickets,
  adminPatchTicket,
  adminGetAppVersions,
  adminPostAppVersion,
  adminSystemHealth,
  adminListAdminUsers,
  adminCreateAdminUser,
  adminChangeRole,
  adminListPlans,
  adminPatchPlan,
  adminListBkashPayments,
  adminVerifyBkashPayment,
  adminRejectBkashPayment,
} from './routes/admin';

const BASE = '/api/v1';

interface Route {
  method: string;
  /** Path pattern relative to BASE, e.g. '/users/:id'. */
  pattern: string[];
  middlewares: Middleware[];
  handler: Handler;
}

function route(method: string, path: string, middlewares: Middleware[], handler: Handler): Route {
  return { method, pattern: path.split('/').filter((s) => s.length > 0), middlewares, handler };
}

// ---------------------------------------------------------------------------
// App (mobile client) routes
// ---------------------------------------------------------------------------

const APP_ROUTES: Route[] = [
  route('POST', '/auth/google', [rateLimit('auth', 'ip')], authGoogle),
  route('POST', '/auth/register', [rateLimit('auth', 'ip')], authRegister),
  route('POST', '/auth/login', [rateLimit('auth', 'ip')], authLogin),
  route('POST', '/auth/refresh', [rateLimit('auth', 'ip')], authRefresh),
  // M1: requireUser must run before authLogout — without it the handler
  // always answered 401 and the server-side session was never revoked.
  route('POST', '/auth/logout', [requireUser, rateLimit('auth', 'ip')], authLogout),

  route('GET', '/me', [requireUser, rateLimit('config', 'device')], getMe),
  // v2.5.7 (H-4): self-serve display-name rename.
  route('PATCH', '/me', [requireUser, rateLimit('auth', 'device')], updateMe),
  // m6: auth-bucket limiter + idempotent short-circuit in the handler keep
  // repeated deletion requests from bloating the append-only event tables.
  route('DELETE', '/me', [requireUser, rateLimit('auth', 'device')], deleteMe),

  route('POST', '/devices/register', [requireUser, rateLimit('public', 'device')], devicesRegister),
  route('POST', '/devices/heartbeat', [requireUser, rateLimit('heartbeat', 'device')], devicesHeartbeat),

  route('GET', '/config', [requireUser, rateLimit('config', 'device')], getConfig),
  route('GET', '/flags', [requireUser, rateLimit('config', 'device')], getFlags),
  route('GET', '/app/version', [rateLimit('public', 'ip')], getAppVersion),
  route('GET', '/announcements', [requireUser, rateLimit('config', 'device')], getAnnouncements),

  route('POST', '/events', [requireUser, rateLimit('events', 'device')], postEvents),

  route('POST', '/subscription/verify', [requireUser, rateLimit('public', 'device')], subscriptionVerify),
  // m7: uniform config-bucket limiter on the cheap per-token reads.
  route('GET', '/subscription', [requireUser, rateLimit('config', 'device')], getSubscription),

  // v2.2 Phase D — plans + trial + manual bKash gateway.
  route('GET', '/plans', [requireUser, rateLimit('config', 'device')], getPlans),
  route('GET', '/trial', [requireUser, rateLimit('config', 'device')], getTrial),
  route('POST', '/trial/claim', [requireUser, rateLimit('auth', 'device')], claimTrial),
  route('GET', '/payments/bkash/instructions', [requireUser, rateLimit('config', 'device')], bkashInstructions),
  route('POST', '/payments/bkash/init', [requireUser, rateLimit('public', 'device')], bkashInit),
  route('POST', '/payments/bkash/submit', [requireUser, rateLimit('public', 'device')], bkashSubmit),
  route('POST', '/payments/bkash/cancel', [requireUser, rateLimit('public', 'device')], bkashCancel),
  route('GET', '/payments/bkash/status', [requireUser, rateLimit('config', 'device')], bkashStatus),

  // v2.5 r9 — engagement (Social Sentry parity).
  route('POST', '/ai/chat', [requireUser, rateLimit('public', 'device')], aiChat),
  route('GET', '/community/commits', [requireUser, rateLimit('config', 'device')], listCommits),
  route('POST', '/community/commits', [requireUser, rateLimit('public', 'device')], createCommit),
  route('POST', '/community/commits/:id/cheer', [requireUser, rateLimit('public', 'device')], cheerCommit),
  // v2.5.7 (F-2): moderation report path.
  route('POST', '/community/commits/:id/report', [requireUser, rateLimit('public', 'device')], reportCommit),
  // m7: uniform config-bucket limiter on the cheap per-token reads.
  route('GET', '/friends', [requireUser, rateLimit('config', 'device')], listFriends),
  route('GET', '/friends/search', [requireUser, rateLimit('public', 'device')], searchUsers),
  route('POST', '/friends/requests/:id', [requireUser, rateLimit('public', 'device')], sendFriendRequest),
  route('POST', '/friends/requests/:id/accept', [requireUser, rateLimit('public', 'device')], acceptFriendRequest),
  route('POST', '/friends/:id/remove', [requireUser, rateLimit('public', 'device')], removeFriend),
  route('GET', '/referral', [requireUser, rateLimit('config', 'device')], getReferral),
  // v2.5.7 (H-1): the referred friend attaches a code — auth bucket (it is
  // a one-shot, credential-adjacent action).
  route('POST', '/referral/apply', [requireUser, rateLimit('auth', 'device')], applyReferral),
  route('POST', '/referral/claim', [requireUser, rateLimit('auth', 'device')], claimReferral),

  // v2.5.7 (C-3): server coin mirror — grants flow DOWN to the device,
  // device earns/spends are mirrored UP best-effort.
  route('GET', '/coins', [requireUser, rateLimit('config', 'device')], getCoins),
  route('POST', '/coins/earn', [requireUser, rateLimit('events', 'device')], coinsEarn),
  route('POST', '/coins/spend', [requireUser, rateLimit('events', 'device')], coinsSpend),

  // v2.5.7 (H-2): user-side support tickets (the admin queue finally has
  // a producer). Create is auth-bucket limited + logical caps in-handler.
  route('POST', '/support/tickets', [requireUser, rateLimit('auth', 'device')], createSupportTicket),
  route('GET', '/support/tickets', [requireUser, rateLimit('config', 'device')], listMySupportTickets),

  // v2.5.8 roadmap — dynamic reels/shorts detection rules (server-pushed
  // signatures so an FB/IG/YT UI update never needs an app release).
  route('GET', '/detection/rules', [requireUser, rateLimit('config', 'device')], getDetectionRules),

  // v2.5.8 roadmap — community leaderboard + clubs (DP competition).
  route('POST', '/leaderboard/opt-in', [requireUser, rateLimit('auth', 'device')], leaderboardOptIn),
  route('GET', '/leaderboard/me', [requireUser, rateLimit('config', 'device')], leaderboardMe),
  route('POST', '/leaderboard/sync', [requireUser, rateLimit('events', 'device')], leaderboardSync),
  route('GET', '/leaderboard/global', [requireUser, rateLimit('config', 'device')], leaderboardGlobal),
  route('POST', '/clubs', [requireUser, rateLimit('auth', 'device')], createClub),
  // NOTE: static club segments must be registered BEFORE '/clubs/:id' —
  // the matcher is first-match-wins.
  route('GET', '/clubs/mine', [requireUser, rateLimit('config', 'device')], myClub),
  route('GET', '/clubs/search', [requireUser, rateLimit('public', 'device')], searchClubs),
  route('POST', '/clubs/join/:code', [requireUser, rateLimit('auth', 'device')], joinClub),
  route('POST', '/clubs/leave', [requireUser, rateLimit('auth', 'device')], leaveClub),
  route('GET', '/clubs/:id', [requireUser, rateLimit('config', 'device')], clubDetail),
];

// ---------------------------------------------------------------------------
// Admin routes — RBAC gate FIRST, then the admin-bucket rate limit, so the
// limiter is keyed by adminId (adm:<id>) as documented in rateLimit.ts.
// Login is the exception: it is public and IP-keyed.
// ---------------------------------------------------------------------------

const ADMIN_ROUTES_LIST: Route[] = [
  route('POST', '/admin/auth/login', [rateLimit('adminLogin', 'ip'), rateLimit('admin', 'ip')], adminLogin),
  // m22: server-side admin-session revocation (lightweight session check —
  // any authenticated admin role may log itself out).
  route('POST', '/admin/auth/logout', [requireAdminSession, rateLimit('admin', 'admin')], adminLogout),

  route('GET', '/admin/overview', [requireAdmin('VIEW_ANALYTICS'), rateLimit('admin', 'admin')], adminOverview),

  route('GET', '/admin/users', [requireAdmin('VIEW_USERS'), rateLimit('admin', 'admin')], adminListUsers),
  route('GET', '/admin/users/:id', [requireAdmin('VIEW_USERS'), rateLimit('admin', 'admin')], adminGetUser),
  route('PATCH', '/admin/users/:id', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminPatchUser),
  route('POST', '/admin/users/:id/coins/adjust', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminAdjustCoins),

  route('GET', '/admin/devices', [requireAdmin('VIEW_DEVICES'), rateLimit('admin', 'admin')], adminListDevices),
  route('GET', '/admin/subscriptions', [requireAdmin('VIEW_USERS'), rateLimit('admin', 'admin')], adminListSubscriptions),

  route('GET', '/admin/config', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminGetConfig),
  route('PUT', '/admin/config', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminSaveConfigDraft),
  route('POST', '/admin/config/publish', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminPublishConfig),
  route('POST', '/admin/config/rollback', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminRollbackConfig),

  // v2.5.8 roadmap — dynamic detection-rules management (same draft/publish
  // model as remote config).
  route('GET', '/admin/detection-rules', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminGetDetectionRules),
  route('PUT', '/admin/detection-rules', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminSaveDetectionRulesDraft),
  route('POST', '/admin/detection-rules/publish', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminPublishDetectionRules),
  route('POST', '/admin/detection-rules/reset', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminResetDetectionRules),

  // v2.5.8 roadmap — leaderboard + clubs moderation. Reads need
  // VIEW_ANALYTICS/VIEW_USERS; every mutation is confirm-gated + audited.
  route('GET', '/admin/leaderboard', [requireAdmin('VIEW_ANALYTICS'), rateLimit('admin', 'admin')], adminGetLeaderboard),
  route('POST', '/admin/leaderboard/users/:id/reset', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminResetLeaderboardProfile),
  route('GET', '/admin/clubs', [requireAdmin('VIEW_USERS'), rateLimit('admin', 'admin')], adminListClubs),
  route('POST', '/admin/clubs/:id/hide', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminHideClub),
  route('POST', '/admin/clubs/:id/restore', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminRestoreClub),
  route('POST', '/admin/clubs/:id/delete', [requireAdmin('EDIT_USERS'), rateLimit('admin', 'admin')], adminDeleteClub),

  route('GET', '/admin/flags', [requireAdmin('MANAGE_FLAGS'), rateLimit('admin', 'admin')], adminListFlags),
  route('PATCH', '/admin/flags/:key', [requireAdmin('MANAGE_FLAGS'), rateLimit('admin', 'admin')], adminPatchFlag),

  route('GET', '/admin/announcements', [requireAdmin('MANAGE_ANNOUNCEMENTS'), rateLimit('admin', 'admin')], adminListAnnouncements),
  route('POST', '/admin/announcements', [requireAdmin('MANAGE_ANNOUNCEMENTS'), rateLimit('admin', 'admin')], adminCreateAnnouncement),
  route('PATCH', '/admin/announcements/:id', [requireAdmin('MANAGE_ANNOUNCEMENTS'), rateLimit('admin', 'admin')], adminPatchAnnouncement),

  route('GET', '/admin/analytics', [requireAdmin('VIEW_ANALYTICS'), rateLimit('admin', 'admin')], adminAnalytics),

  route('GET', '/admin/audit-logs', [requireAdmin('VIEW_AUDIT'), rateLimit('admin', 'admin')], adminAuditLogs),
  route('GET', '/admin/security-events', [requireAdmin('VIEW_AUDIT'), rateLimit('admin', 'admin')], adminSecurityEvents),

  route('GET', '/admin/support/tickets', [requireAdmin('MANAGE_SUPPORT'), rateLimit('admin', 'admin')], adminListTickets),
  route('PATCH', '/admin/support/tickets/:id', [requireAdmin('MANAGE_SUPPORT'), rateLimit('admin', 'admin')], adminPatchTicket),
  // v2.5.7 (F-2): community moderation (hide reported commitments).
  route('PATCH', '/admin/community/commits/:id', [requireAdmin('MANAGE_SUPPORT'), rateLimit('admin', 'admin')], adminPatchCommit),

  route('GET', '/admin/app-versions', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminGetAppVersions),
  route('POST', '/admin/app-versions', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminPostAppVersion),

  route('GET', '/admin/system/health', [requireAdmin('MANAGE_SYSTEM'), rateLimit('admin', 'admin')], adminSystemHealth),

  // v2.2 Phase D — plan catalog editing + bKash payment review queue.
  route('GET', '/admin/plans', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminListPlans),
  route('PATCH', '/admin/plans/:id', [requireAdmin('MANAGE_CONFIG'), rateLimit('admin', 'admin')], adminPatchPlan),
  route('GET', '/admin/payments/bkash', [requireAdmin('MANAGE_PAYMENTS'), rateLimit('admin', 'admin')], adminListBkashPayments),
  route('POST', '/admin/payments/bkash/:id/verify', [requireAdmin('MANAGE_PAYMENTS'), rateLimit('admin', 'admin')], adminVerifyBkashPayment),
  route('POST', '/admin/payments/bkash/:id/reject', [requireAdmin('MANAGE_PAYMENTS'), rateLimit('admin', 'admin')], adminRejectBkashPayment),

  route('GET', '/admin/admin-users', [requireAdmin('MANAGE_SYSTEM'), rateLimit('admin', 'admin')], adminListAdminUsers),
  route('POST', '/admin/admin-users', [requireAdmin('MANAGE_SYSTEM'), rateLimit('admin', 'admin')], adminCreateAdminUser),
  route('PATCH', '/admin/admin-users/:id', [requireAdmin('MANAGE_SYSTEM'), rateLimit('admin', 'admin')], adminChangeRole),
];

const ALL_ROUTES: Route[] = [...APP_ROUTES, ...ADMIN_ROUTES_LIST];

// ---------------------------------------------------------------------------
// Matcher
// ---------------------------------------------------------------------------

function matchRoute(
  method: string,
  segments: string[]
): { route: Route; params: Record<string, string> } | null {
  for (const r of ALL_ROUTES) {
    if (r.method !== method) continue;
    if (r.pattern.length !== segments.length) continue;
    const params: Record<string, string> = {};
    let matched = true;
    for (let i = 0; i < r.pattern.length; i++) {
      const p = r.pattern[i];
      if (p.startsWith(':')) {
        // m20: a malformed percent-encoding (e.g. "%%") must be a no-match
        // (404), not an unhandled URIError that surfaces as a 500.
        try {
          params[p.slice(1)] = decodeURIComponent(segments[i]);
        } catch {
          matched = false;
          break;
        }
      } else if (p !== segments[i]) {
        matched = false;
        break;
      }
    }
    if (matched) return { route: r, params };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Observability counters (best-effort, per UTC day)
// ---------------------------------------------------------------------------

async function bumpCounter(env: Env, key: string): Promise<void> {
  try {
    const current = await env.KV.get(key);
    const next = String((Number(current ?? '0') || 0) + 1);
    await env.KV.put(key, next, { expirationTtl: 3 * 24 * 60 * 60 });
  } catch {
    // Observability must never break the request path.
  }
}

// ---------------------------------------------------------------------------
// fetch handler
// ---------------------------------------------------------------------------

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const started = Date.now();
    const requestId = newRequestId();
    const url = new URL(request.url);

    const c: Context = {
      req: request,
      url,
      env,
      ctx,
      requestId,
      params: {},
      user: null,
      admin: null,
    };

    let response: Response | undefined;

    try {
      // Health probe for external monitors (no auth, no D1).
      if (url.pathname === '/health' || url.pathname === '/api/v1/health') {
        response = new Response(
          JSON.stringify({ success: true, data: { status: 'ok', environment: env.API_ENV }, requestId }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      } else if (request.method === 'OPTIONS') {
        response = preflightResponse(c);
      } else {
        const method = request.method === 'HEAD' ? 'GET' : request.method;
        const rawPath = url.pathname;
        const routePath = rawPath.startsWith(BASE + '/')
          ? rawPath.slice(BASE.length)
          : rawPath === BASE
            ? ''
            : rawPath;
        const segments = routePath.split('/').filter((s) => s.length > 0);
        const matched = matchRoute(method, segments);

        if (matched === null) {
          response = fail(c, 'NOT_FOUND', `No route for ${method} ${url.pathname}`, 404);
        } else {
          c.params = matched.params;
          for (const mw of matched.route.middlewares) {
            const early = await mw(c);
            if (early instanceof Response) {
              response = early;
              break;
            }
          }
          if (response === undefined) {
            response = await matched.route.handler(c);
          }
        }
      }
    } catch (err) {
      // Never leak stack traces to clients.
      console.error(requestId, request.method, url.pathname, 'unhandled error', err);
      response = fail(c, 'SERVER_ERROR', 'An unexpected error occurred', 500);
    }

    if (response === undefined) {
      // Defensive: no handler path produced a response.
      response = fail(c, 'SERVER_ERROR', 'No response produced', 500);
    }

    // Observability: request + error counters and structured log line.
    const dateKey = new Date().toISOString().slice(0, 10);
    const reqKey = `req_count_${dateKey}_${env.API_ENV}`;
    ctx.waitUntil(bumpCounter(env, reqKey));
    if (response.status >= 400) {
      const errKey = `err_count_${dateKey}_${env.API_ENV}`;
      ctx.waitUntil(bumpCounter(env, errKey));
    }
    console.log(requestId, request.method, url.pathname, response.status, `${Date.now() - started}ms`);

    return response;
  },

  /**
   * M6: cron every 30 minutes (see [triggers] in wrangler.toml) — flip
   * subscriptions whose expiry_date has passed to EXPIRED so entitlements
   * and admin metrics stay honest server-side.
   *
   * v2.5.7 additions:
   *   - W-6: expire stale PENDING bKash payments (24h TTL was only checked
   *     lazily at submit-time; abandoned rows stayed PENDING forever).
   *   - C-4: purge deletion requests older than 30 days (anonymize the
   *     account, scrub sessions/devices, mark DELETED) — the deletion flag
   *     previously stayed pending forever, a Play Account Deletion policy
   *     violation.
   */
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const nowIso = new Date().toISOString();

    // (1) Flip expired ACTIVE subscriptions.
    ctx.waitUntil(
      env.DB.prepare(
        `UPDATE subscriptions SET status = 'EXPIRED', updated_at = ?
         WHERE status = 'ACTIVE' AND expiry_date IS NOT NULL AND expiry_date < ?`
      )
        .bind(nowIso, nowIso)
        .run()
        .then((res) => {
          console.log('scheduled: subscriptions flipped to EXPIRED:', res.meta.changes ?? 0);
        })
        .catch((err: unknown) => {
          console.error('scheduled: subscription expiry update failed', err);
        })
    );

    // (2) v2.5.7 (W-6): expire PENDING bKash payments older than 24h.
    const pendingCutoff = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
    ctx.waitUntil(
      env.DB.prepare(
        `UPDATE bkash_payments SET status = 'EXPIRED', updated_at = ?
         WHERE status = 'PENDING' AND created_at < ?`
      )
        .bind(nowIso, pendingCutoff)
        .run()
        .then((res) => {
          const n = res.meta.changes ?? 0;
          if (n > 0) console.log('scheduled: expired stale PENDING bKash payments:', n);
        })
        .catch((err: unknown) => {
          console.error('scheduled: bKash PENDING expiry failed', err);
        })
    );

    // (3) v2.5.7 (C-4): complete deletion requests past the 30-day grace
    // window. Rows are ANONYMIZED (never hard-deleted) so FK references and
    // the append-only event history stay intact: email -> deleted_<id>@invalid,
    // display name scrubbed, google_sub/referral_code cleared, sessions and
    // devices removed, status -> DELETED.
    const deletionCutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
    ctx.waitUntil(
      (async () => {
        const pending = await env.DB.prepare(
          `SELECT id FROM users
           WHERE deletion_pending_at IS NOT NULL AND deletion_pending_at < ? AND status != 'DELETED'
           LIMIT 500`
        )
          .bind(deletionCutoff)
          .all<{ id: string }>();

        for (const user of pending.results) {
          try {
            await env.DB.batch([
              env.DB.prepare('DELETE FROM user_sessions WHERE user_id = ?').bind(user.id),
              // v2.5.8: community surfaces must not keep a deleted user as a
              // club member (orphan-membership would block the club sweep)
              // or as an invisible leaderboard row.
              env.DB.prepare('DELETE FROM club_members WHERE user_id = ?').bind(user.id),
              env.DB.prepare('DELETE FROM leaderboard_profiles WHERE user_id = ?').bind(user.id),
              env.DB.prepare(
                `UPDATE devices SET status = 'REMOVED', permission_summary = NULL, last_seen_at = NULL
                 WHERE user_id = ?`
              ).bind(user.id),
              env.DB.prepare(
                `UPDATE users SET
                    email = 'deleted_' || ? || '@invalid',
                    display_name = NULL,
                    google_sub = NULL,
                    referral_code = NULL,
                    status = 'DELETED',
                    updated_at = ?
                 WHERE id = ? AND status != 'DELETED'`
              ).bind(user.id, nowIso, user.id),
            ]);
            await appendSecurityEvent(env, user.id, null, 'ACCOUNT_DELETION_COMPLETED', 'LOW', {
              scheduled: true,
            });
          } catch (err) {
            console.error('scheduled: deletion purge failed for', user.id, err);
          }
        }
        if (pending.results.length > 0) {
          console.log('scheduled: completed account deletions:', pending.results.length);
        }
      })().catch((err: unknown) => {
        console.error('scheduled: deletion purge job failed', err);
      })
    );

    // (4) v2.5.8 roadmap: sweep ORPHAN clubs — rows that lost their last
    // member outside the normal leaveClub path (deleted accounts, crashed
    // leaves). Clubs are cheap, but orphans keep reserved invite codes and
    // clutter admin search, so anything memberless for >24h is dropped.
    const orphanCutoff = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
    ctx.waitUntil(
      env.DB.prepare(
        `DELETE FROM clubs
         WHERE created_at < ?
           AND NOT EXISTS (SELECT 1 FROM club_members m WHERE m.club_id = clubs.id)`
      )
        .bind(orphanCutoff)
        .run()
        .then((res) => {
          const n = res.meta.changes ?? 0;
          if (n > 0) console.log('scheduled: swept orphan clubs:', n);
        })
        .catch((err: unknown) => {
          console.error('scheduled: orphan club sweep failed', err);
        })
    );
  },
};
