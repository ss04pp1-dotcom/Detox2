/**
 * API client for the frozen /admin/* contract.
 *
 * - Same-origin by default (`/api/v1` prefix; dev proxy -> wrangler :8787).
 * - VITE_API_URL can point at a full base (e.g. https://api.maxleveldetox.com/api/v1).
 * - VITE_API_URL=mock switches every call to the in-browser demo transport.
 *
 * Every response is unwrapped from the envelope {success, data, requestId}.
 * Failures throw ApiError (code + message + requestId). A 401 clears the
 * session and redirects to /login (admin tokens live in KV with expiry —
 * there is no refresh flow for admins).
 */
import { mockRequest } from './mock';
import { ApiError } from './error';
import type {
  AdminUser,
  AnalyticsRange,
  AnalyticsResponse,
  Announcement,
  AnnouncementInput,
  AnnouncementPatch,
  AppConfig,
  AppVersionPolicy,
  AuditLog,
  BkashPayment,
  BkashPaymentsSummary,
  BkashPaymentsWireResponse,
  CoinAdjustment,
  ConfigDoc,
  ConfigResponse,
  Device,
  DetectionRulesDoc,
  DetectionRulesResponse,
  ClubsResponse,
  LeaderboardResponse,
  LeaderboardWindow,
  FeatureFlag,
  FlagPatch,
  LoginResponse,
  OverviewResponse,
  PaginatedResponse,
  Plan,
  PlanPatch,
  PlansWireResponse,
  SecurityEvent,
  Subscription,
  SystemHealth,
  Ticket,
  TicketPatch,
  User,
  UserDetailResponse,
  UserStatus,
} from './types';
import type {
  AdminUsersWireResponse,
  AnnouncementsWireResponse,
  AuditWireResponse,
  DevicesWireResponse,
  FlagsWireResponse,
  SecurityEventsWireResponse,
  SubscriptionsWireResponse,
  TicketsWireResponse,
  UsersWireResponse,
} from './types';

export { ApiError, toApiError } from './error';

function resolveBaseUrl(): string {
  const env = import.meta.env.VITE_API_URL?.trim();
  if (env === 'mock') return 'mock';
  const target = env && env.length > 0 ? env : 'https://mld-api.salman61902.workers.dev/api/v1';
  let clean = target.replace(/\/+$/, '');
  if (!clean.endsWith('/api/v1')) {
    clean = `${clean}/api/v1`;
  }
  return clean;
}

const resolvedApiUrl = resolveBaseUrl();

/** True when running the fully client-side demo dataset. */
export const IS_MOCK = resolvedApiUrl === 'mock';

/** Resolved API base (informational, shown on the System page). */
export const API_BASE_URL: string = IS_MOCK ? 'mock://in-memory' : resolvedApiUrl;

// ---------------------------------------------------------------------------
// Token store — memory first, mirrored into sessionStorage so a reload
// keeps the session for the tab lifetime. Never persisted to localStorage.
// ---------------------------------------------------------------------------

const TOKEN_KEY = 'mld_admin_token';
let memoryToken: string | null = null;

export function setToken(token: string | null): void {
  memoryToken = token;
  if (token === null) sessionStorage.removeItem(TOKEN_KEY);
  else sessionStorage.setItem(TOKEN_KEY, token);
}

export function getToken(): string | null {
  if (memoryToken !== null) return memoryToken;
  memoryToken = sessionStorage.getItem(TOKEN_KEY);
  return memoryToken;
}

let unauthorizedHandler: (() => void) | null = null;

/** Registered by AuthContext: clears state and forces /login on a 401. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler;
}

// ---------------------------------------------------------------------------
// Core request
// ---------------------------------------------------------------------------

interface Envelope<T> {
  success: boolean;
  data?: T;
  error?: { code: string; message: string };
  requestId: string;
}

function statusFallback(status: number): string {
  if (status === 401) return 'UNAUTHORIZED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 404) return 'NOT_FOUND';
  if (status === 429) return 'RATE_LIMITED';
  return 'SERVER_ERROR';
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  if (IS_MOCK) return mockRequest<T>(method, path, body);

  const base = resolvedApiUrl;
  let response: Response;
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const token = getToken();
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    response = await fetch(`${base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(
      'SERVICE_UNAVAILABLE',
      'Network error — unable to reach the API. Check your connection and try again.',
      'n/a',
      0,
    );
  }

  let json: Envelope<T> | null = null;
  try {
    json = (await response.json()) as Envelope<T>;
  } catch {
    json = null;
  }

  if (!response.ok || json === null || json.success !== true || json.data === undefined) {
    const code = json?.error?.code ?? statusFallback(response.status);
    const message = json?.error?.message ?? `Request failed with status ${response.status}`;
    const requestId = json?.requestId ?? 'n/a';
    if (response.status === 401) {
      setToken(null);
      unauthorizedHandler?.();
    }
    throw new ApiError(code, message, requestId, response.status);
  }
  return json.data;
}

type QueryParams = Record<string, string | number | undefined>;

function buildQuery(params: QueryParams): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded.length > 0 ? `?${encoded}` : '';
}

function get<T>(path: string, params?: QueryParams): Promise<T> {
  return request<T>('GET', params === undefined ? path : `${path}${buildQuery(params)}`);
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body);
}

function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('PATCH', path, body);
}

function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('PUT', path, body);
}

// ---------------------------------------------------------------------------
// Typed surface (mirrors the frozen admin routes)
// ---------------------------------------------------------------------------

export const api = {
  // auth
  login: (email: string, password: string): Promise<LoginResponse> =>
    post<LoginResponse>('/admin/auth/login', { email, password }),

  // v2.5.7 (C-2): revoke the KV session server-side. The route existed (m22)
  // but the SPA never called it — a stolen token stayed valid for its full
  // 8-hour lifetime after logout.
  logout: (): Promise<{ loggedOut: boolean }> =>
    post<{ loggedOut: boolean }>('/admin/auth/logout'),

  // overview
  getOverview: (): Promise<OverviewResponse> => get<OverviewResponse>('/admin/overview'),

  // users
  listUsers: async (params: { q?: string; status?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<User>> => {
    const res = await get<UsersWireResponse>('/admin/users', params);
    return { items: res.users, nextCursor: res.nextCursor };
  },

  getUser: (id: string): Promise<UserDetailResponse> =>
    get<UserDetailResponse>(`/admin/users/${encodeURIComponent(id)}`),

  updateUserStatus: async (id: string, status: UserStatus): Promise<User> => {
    const res = await patch<{ user: User }>(`/admin/users/${encodeURIComponent(id)}`, { status });
    return res.user;
  },

  adjustCoins: async (id: string, amount: number, reason: string): Promise<CoinAdjustment> => {
    const res = await post<{ adjustment: CoinAdjustment }>(
      `/admin/users/${encodeURIComponent(id)}/coins/adjust`,
      { amount, reason },
    );
    return res.adjustment;
  },

  // devices
  listDevices: async (params: { q?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<Device>> => {
    const res = await get<DevicesWireResponse>('/admin/devices', params);
    return { items: res.devices, nextCursor: res.nextCursor };
  },

  // subscriptions
  listSubscriptions: async (params: { status?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<Subscription>> => {
    const res = await get<SubscriptionsWireResponse>('/admin/subscriptions', params);
    return { items: res.subscriptions, nextCursor: res.nextCursor };
  },

  // remote config
  getConfig: (): Promise<ConfigResponse> => get<ConfigResponse>('/admin/config'),

  saveConfigDraft: async (config: AppConfig): Promise<ConfigDoc> => {
    const res = await put<{ draft: ConfigDoc }>('/admin/config', { config });
    return res.draft;
  },

  publishConfig: async (): Promise<ConfigDoc> => {
    const res = await post<{ published: ConfigDoc }>('/admin/config/publish', { confirm: true });
    return res.published;
  },

  rollbackConfig: async (version: number): Promise<ConfigDoc> => {
    const res = await post<{ published: ConfigDoc }>('/admin/config/rollback', { version });
    return res.published;
  },

  // v2.5.8 — dynamic detection rules (draft/publish, same model as config)
  getDetectionRules: (): Promise<DetectionRulesResponse> =>
    get<DetectionRulesResponse>('/admin/detection-rules'),

  saveDetectionRulesDraft: async (rules: DetectionRulesDoc): Promise<DetectionRulesDoc> => {
    const res = await put<{ draft: DetectionRulesDoc }>('/admin/detection-rules', { rules });
    return res.draft;
  },

  publishDetectionRules: async (): Promise<DetectionRulesDoc> => {
    const res = await post<{ published: DetectionRulesDoc }>('/admin/detection-rules/publish', { confirm: true });
    return res.published;
  },

  resetDetectionRules: async (): Promise<DetectionRulesDoc> => {
    const res = await post<{ draft: DetectionRulesDoc }>('/admin/detection-rules/reset', { confirm: true });
    return res.draft;
  },

  // v2.5.8 — community leaderboard + clubs moderation
  getLeaderboard: (params: { window?: LeaderboardWindow; q?: string } = {}): Promise<LeaderboardResponse> =>
    get<LeaderboardResponse>('/admin/leaderboard', params),

  resetLeaderboardProfile: async (userId: string, reason: string): Promise<{ reset: boolean }> => {
    const res = await post<{ reset: boolean }>(
      `/admin/leaderboard/users/${encodeURIComponent(userId)}/reset`,
      { confirm: true, reason }
    );
    return res;
  },

  listClubs: (params: { q?: string; filter?: 'visible' | 'hidden' | 'all'; page?: number } = {}): Promise<ClubsResponse> =>
    get<ClubsResponse>('/admin/clubs', params),

  hideClub: async (clubId: string): Promise<{ hidden: boolean }> => {
    const res = await post<{ hidden: boolean }>(`/admin/clubs/${encodeURIComponent(clubId)}/hide`, {
      confirm: true,
    });
    return res;
  },

  restoreClub: async (clubId: string): Promise<{ hidden: boolean }> => {
    const res = await post<{ hidden: boolean }>(`/admin/clubs/${encodeURIComponent(clubId)}/restore`, {
      confirm: true,
    });
    return res;
  },

  deleteClub: async (clubId: string): Promise<{ deleted: boolean }> => {
    const res = await post<{ deleted: boolean }>(`/admin/clubs/${encodeURIComponent(clubId)}/delete`, {
      confirm: true,
    });
    return res;
  },

  // feature flags
  listFlags: async (): Promise<FeatureFlag[]> => {
    const res = await get<FlagsWireResponse>('/admin/flags');
    return res.flags;
  },

  updateFlag: async (key: string, patchBody: FlagPatch): Promise<FeatureFlag> => {
    const res = await patch<{ flag: FeatureFlag }>(`/admin/flags/${encodeURIComponent(key)}`, patchBody);
    return res.flag;
  },

  // announcements
  listAnnouncements: async (): Promise<Announcement[]> => {
    const res = await get<AnnouncementsWireResponse>('/admin/announcements');
    return res.announcements;
  },

  createAnnouncement: async (input: AnnouncementInput): Promise<Announcement> => {
    const res = await post<{ announcement: Announcement }>('/admin/announcements', input);
    return res.announcement;
  },

  updateAnnouncement: async (id: string, patchBody: AnnouncementPatch): Promise<Announcement> => {
    const res = await patch<{ announcement: Announcement }>(`/admin/announcements/${encodeURIComponent(id)}`, patchBody);
    return res.announcement;
  },

  // analytics
  getAnalytics: (range: AnalyticsRange): Promise<AnalyticsResponse> =>
    get<AnalyticsResponse>('/admin/analytics', { range }),

  // audit
  listAuditLogs: async (params: { adminId?: string; action?: string; from?: string; to?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<AuditLog>> => {
    const res = await get<AuditWireResponse>('/admin/audit-logs', params);
    return { items: res.auditLogs ?? [], nextCursor: res.nextCursor };
  },

  // security events
  listSecurityEvents: async (params: { severity?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<SecurityEvent>> => {
    const res = await get<SecurityEventsWireResponse>('/admin/security-events', params);
    return { items: res.securityEvents ?? [], nextCursor: res.nextCursor };
  },

  // support
  listTickets: async (params: { status?: string; cursor?: string; limit?: number }): Promise<PaginatedResponse<Ticket>> => {
    const res = await get<TicketsWireResponse>('/admin/support/tickets', params);
    return { items: res.tickets, nextCursor: res.nextCursor };
  },

  updateTicket: async (id: string, patchBody: TicketPatch): Promise<Ticket> => {
    const res = await patch<{ ticket: Ticket }>(`/admin/support/tickets/${encodeURIComponent(id)}`, patchBody);
    return res.ticket;
  },

  // app versions
  getAppVersionPolicy: (): Promise<AppVersionPolicy> => get<AppVersionPolicy>('/admin/app-versions'),

  updateAppVersionPolicy: (policy: { minimum: string; latest: string; forceUpdate: boolean; message: string }): Promise<AppVersionPolicy> =>
    post<AppVersionPolicy>('/admin/app-versions', policy),

  // system
  getSystemHealth: (): Promise<SystemHealth> => get<SystemHealth>('/admin/system/health'),

  // v2.2 Phase D — plans + bKash payments
  listPlans: async (): Promise<Plan[]> => {
    const res = await get<PlansWireResponse>('/admin/plans');
    return res.plans;
  },

  updatePlan: async (id: string, updates: PlanPatch): Promise<Plan> => {
    const res = await patch<{ plan: Plan }>(`/admin/plans/${encodeURIComponent(id)}`, updates);
    return res.plan;
  },

  listBkashPayments: async (params: {
    status?: string;
    cursor?: string;
    limit?: number;
  }): Promise<{ items: BkashPayment[]; nextCursor: string | null; summary: BkashPaymentsSummary }> => {
    const res = await get<BkashPaymentsWireResponse>('/admin/payments/bkash', params);
    return { items: res.payments, nextCursor: res.nextCursor, summary: res.summary };
  },

  verifyBkashPayment: async (id: string): Promise<BkashPayment> => {
    const res = await post<{ payment: BkashPayment }>(
      `/admin/payments/bkash/${encodeURIComponent(id)}/verify`,
      { confirm: true },
    );
    return res.payment;
  },

  rejectBkashPayment: async (id: string, reason: string): Promise<BkashPayment> => {
    const res = await post<{ payment: BkashPayment }>(
      `/admin/payments/bkash/${encodeURIComponent(id)}/reject`,
      { reason },
    );
    return res.payment;
  },

  // admin users (SUPER_ADMIN only)
  listAdminUsers: async (): Promise<AdminUser[]> => {
    const res = await get<AdminUsersWireResponse>('/admin/admin-users');
    return res.admins;
  },

  createAdminUser: async (input: { email: string; name: string; password: string; role: AdminUser['role'] }): Promise<AdminUser> => {
    const res = await post<{ admin: AdminUser }>('/admin/admin-users', input);
    return res.admin;
  },
};
