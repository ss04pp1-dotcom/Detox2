/**
 * TypeScript mirror of the FROZEN API CONTRACT v1 (see /home/z/my-project/worklog.md).
 * Every admin API response in the app is typed from this file.
 * Wire shapes use the exact array keys defined by the contract
 * (e.g. {users:[], nextCursor}); the client maps them onto the
 * generic PaginatedResponse<T> consumed by tables.
 */

// ---------------------------------------------------------------------------
// Envelope / errors
// ---------------------------------------------------------------------------

export type ApiErrorCode =
  | 'INVALID_REQUEST'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'RATE_LIMITED'
  | 'CONFLICT'
  | 'SERVER_ERROR'
  | 'SERVICE_UNAVAILABLE'
  | 'VALIDATION_FAILED';

// ---------------------------------------------------------------------------
// Auth / RBAC (frozen matrix)
// ---------------------------------------------------------------------------

export type AdminRole =
  | 'SUPER_ADMIN'
  | 'ADMIN'
  | 'SUPPORT'
  | 'ANALYST'
  | 'CONFIG_MANAGER'
  | 'READ_ONLY';

export type Permission =
  | 'VIEW_USERS'
  | 'EDIT_USERS'
  | 'VIEW_DEVICES'
  | 'MANAGE_CONFIG'
  | 'MANAGE_FLAGS'
  | 'MANAGE_ANNOUNCEMENTS'
  | 'VIEW_ANALYTICS'
  | 'VIEW_AUDIT'
  | 'MANAGE_SUPPORT'
  | 'MANAGE_PAYMENTS'
  | 'MANAGE_SYSTEM';

export interface AdminUser {
  id: string;
  email: string;
  /** v2.5.7 (A-1): stored (schema 004) and serialized by the worker. */
  name?: string | null;
  role: AdminRole;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface LoginResponse {
  admin: AdminUser;
  token: string;
  expiresIn: number; // seconds
}

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'PENDING' | 'BANNED' | 'DELETED';
/** The SPA's frozen vocabulary is 'FREE' | 'PREMIUM'; the worker's user list
 *  sends the subscription plan ('MONTHLY' | 'YEARLY' | …) or null. */
export type UserPlan = 'FREE' | 'PREMIUM' | 'MONTHLY' | 'QUARTERLY' | 'SEMIANNUAL' | 'YEARLY' | 'TRIAL' | null;

export interface User {
  id: string;
  email: string;
  displayName: string | null;
  status: UserStatus;
  plan: UserPlan;
  coinBalance?: number | null;
  deviceCount: number;
  lastSeenAt: string | null;
  createdAt: string;
}

export interface CoinAdjustment {
  id: string;
  userId: string;
  amount: number;
  reason: string;
  balanceBefore: number;
  balanceAfter: number;
  adminEmail: string;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

export type DeviceStatus = 'ACTIVE' | 'INACTIVE';
export type DeviceRisk = 'LOW' | 'MEDIUM' | 'HIGH';

export interface Device {
  id: string; // dev_...
  userId: string;
  userEmail: string;
  model: string;
  manufacturer: string;
  androidVersion: string;
  appVersion: string;
  lastSeenAt: string | null;
  status: DeviceStatus;
  /** Absent on the worker serializer — the UI renders a neutral '—'. */
  riskLevel?: DeviceRisk | null;
}

// ---------------------------------------------------------------------------
// Subscriptions
// ---------------------------------------------------------------------------

export type SubscriptionStatus = 'ACTIVE' | 'PENDING' | 'EXPIRED' | 'CANCELLED' | 'GRACE';
export type SubscriptionPlan = 'MONTHLY' | 'YEARLY';

export interface Subscription {
  id: string;
  userId: string;
  userEmail: string;
  productId: string;
  plan: SubscriptionPlan;
  status: SubscriptionStatus;
  /** SPA field; the worker sends `startDate` — read both. */
  startedAt: string;
  startDate?: string;
  expiryDate: string | null;
  /** SPA field; the worker sends `lastVerified` — read both. */
  lastVerifiedAt: string | null;
  lastVerified?: string | null;
  priceUsd?: number | null;
  autoRenewing?: boolean | null;
}

// ---------------------------------------------------------------------------
// Remote config (draft / publish / rollback)
// ---------------------------------------------------------------------------

export interface AppConfig {
  shortsWarningCount: number;
  cageDurationSeconds: number;
  tempUnlockCoins: number;
  tempUnlockMinutes: number;
  bailoutCoins: number;
  defaultStudyMinutes: number;
  defaultDetoxMinutes: number;
  minSupportedVersion: string;
  maintenanceMode: boolean;
  // v2.1 Phase C — progress layer (DP economy pacing; never enforcement).
  gamificationEnabled: boolean;
  dpMultiplierFocus: number;
  dpMultiplierReels: number;
  dpMultiplierNeutral: number;
  dpDailyCap: number;
  dpWeeklyCap: number;
  dpMonthlyCap: number;
  // v2.2 Phase D — growth & monetization.
  paymentsEnabled: boolean;
  bkashNumber: string;
  bkashInstructions: string;
  trialDays: number;
  breakPassesPerWeek: number;
  insightNudgeEnabled: boolean;
  insightNudgeHour: number;
}

export type NumericConfigKey =
  | 'shortsWarningCount'
  | 'cageDurationSeconds'
  | 'tempUnlockCoins'
  | 'tempUnlockMinutes'
  | 'bailoutCoins'
  | 'defaultStudyMinutes'
  | 'defaultDetoxMinutes'
  | 'dpDailyCap'
  | 'dpWeeklyCap'
  | 'dpMonthlyCap'
  | 'trialDays'
  | 'breakPassesPerWeek'
  | 'insightNudgeHour';

export type StringConfigKey = 'bkashNumber' | 'bkashInstructions';

export const CONFIG_STRING_BOUNDS: Record<StringConfigKey, { max: number }> = {
  bkashNumber: { max: 32 },
  bkashInstructions: { max: 2000 },
};

export type MultiplierConfigKey =
  | 'dpMultiplierFocus'
  | 'dpMultiplierReels'
  | 'dpMultiplierNeutral';

export const CONFIG_MULTIPLIER_BOUNDS: Record<MultiplierConfigKey, { min: number; max: number }> = {
  dpMultiplierFocus: { min: 0.5, max: 3.0 },
  dpMultiplierReels: { min: 0.5, max: 3.0 },
  dpMultiplierNeutral: { min: 0.5, max: 3.0 },
};

/** Frozen config bounds — validated by server AND this UI. */
export const CONFIG_NUMERIC_BOUNDS: Record<NumericConfigKey, { min: number; max: number }> = {
  shortsWarningCount: { min: 3, max: 7 },
  cageDurationSeconds: { min: 300, max: 7200 },
  tempUnlockCoins: { min: 1, max: 50 },
  tempUnlockMinutes: { min: 1, max: 15 },
  bailoutCoins: { min: 50, max: 5000 },
  defaultStudyMinutes: { min: 10, max: 480 },
  defaultDetoxMinutes: { min: 30, max: 1440 },
  dpDailyCap: { min: 50, max: 1000 },
  dpWeeklyCap: { min: 200, max: 5000 },
  dpMonthlyCap: { min: 1000, max: 20000 },
  trialDays: { min: 3, max: 14 },
  breakPassesPerWeek: { min: 0, max: 5 },
  insightNudgeHour: { min: 17, max: 22 },
};

export type ConfigDocStatus = 'PUBLISHED' | 'DRAFT';

/**
 * A config document as the worker returns it: the app config plus the
 * `_version` doc key. Legacy/mock metadata fields are tolerated but never
 * required (the mock aligns to `_version` and omits the rest).
 */
export interface ConfigDoc extends AppConfig {
  _version?: number;
  version?: number;
  status?: ConfigDocStatus;
  createdAt?: string;
  updatedAt?: string;
  updatedBy?: string;
  publishedAt?: string | null;
}

export interface ConfigVersionSummary {
  version: number;
  status: 'PUBLISHED' | 'DRAFT' | 'ARCHIVED';
  createdAt: string;
  updatedAt: string;
  updatedBy: string;
  publishedAt: string | null;
}

export interface ConfigResponse {
  draft: ConfigDoc | null;
  published: ConfigDoc;
  versions: ConfigVersionSummary[];
}

// ---------------------------------------------------------------------------
// v2.5.8 — dynamic detection rules (server-pushed reels/shorts signatures)
// ---------------------------------------------------------------------------

export type DetectionPlatformId =
  | 'youtube'
  | 'tiktok'
  | 'facebook'
  | 'facebook_lite'
  | 'instagram'
  | 'chrome'
  | 'chrome_beta';

export interface PlatformRules {
  enabled: boolean;
  feedViewIds?: string[];
  immersiveViewIds?: string[];
  eventTextHints?: string[];
  reelDetailsHints?: string[];
  navHints?: string[];
  reelsHints?: string[];
  fullscreenHints?: string[];
  immersiveGate?: boolean;
  urlShapes?: string[];
  liteFeedViewIds?: string[];
  sharedFeedViewIds?: string[];
  /** v2.9 r16: activity-class-name hints (lowercased contains-match, e.g.
   *  `shortsactivity`) — drift-resistant WINDOW_STATE_CHANGED signal. */
  activityHints?: string[];
}

export interface DetectionRulesDoc {
  schemaVersion: number;
  minAppVersion: string;
  platforms: Partial<Record<DetectionPlatformId, PlatformRules>>;
  _version?: number;
}

export interface DetectionRulesResponse {
  draft: DetectionRulesDoc | null;
  published: DetectionRulesDoc | null;
  defaults: DetectionRulesDoc;
  versions: Array<{
    version: number;
    status: 'PUBLISHED' | 'DRAFT' | 'ARCHIVED';
    createdBy: string | null;
    createdAt: string;
    publishedAt: string | null;
  }>;
}

/** Per-platform field metadata shared by the editor UI. */
export const DETECTION_LIST_FIELDS: Array<{
  key: keyof PlatformRules;
  label: string;
  hint: string;
  platforms: DetectionPlatformId[];
}> = [
  {
    key: 'feedViewIds',
    label: 'Feed view IDs',
    hint: 'Any match confirms a shorts feed (short id or pkg:id/name).',
    platforms: ['youtube', 'facebook_lite', 'instagram'],
  },
  {
    key: 'immersiveViewIds',
    label: 'Immersive view IDs',
    hint: 'Presence switches the analytics label (pivot vs immersed).',
    platforms: ['youtube'],
  },
  {
    key: 'liteFeedViewIds',
    label: 'Lite-package view IDs',
    hint: 'Checked against the Instagram LITE package namespace.',
    platforms: ['instagram'],
  },
  {
    key: 'sharedFeedViewIds',
    label: 'Shared (unprefixed) view IDs',
    hint: 'Matched across package namespaces.',
    platforms: ['instagram'],
  },
  {
    key: 'eventTextHints',
    label: 'Event-text hints',
    hint: 'Cheap pre-check on the accessibility event text (contains).',
    platforms: ['facebook'],
  },
  {
    key: 'reelDetailsHints',
    label: 'Reel-details hints',
    hint: 'BFS content descriptions (contains) — required flag.',
    platforms: ['facebook'],
  },
  {
    key: 'navHints',
    label: 'Navigation hints',
    hint: 'BFS content descriptions (contains) — required flag.',
    platforms: ['facebook'],
  },
  {
    key: 'reelsHints',
    label: 'Reels hints',
    hint: 'BFS content descriptions (equals or ends-with).',
    platforms: ['facebook'],
  },
  {
    key: 'fullscreenHints',
    label: 'Fullscreen hints',
    hint: 'BFS content descriptions (contains).',
    platforms: ['facebook'],
  },
  {
    key: 'activityHints',
    label: 'Activity-name hints',
    hint: 'Lowercased contains-match on the window-state activity class (e.g. shortsactivity) — most drift-resistant.',
    platforms: ['youtube', 'facebook', 'facebook_lite', 'instagram'],
  },
  {
    key: 'urlShapes',
    label: 'URL shapes',
    hint: 'Lowercase URL fragments matched against the url_bar text.',
    platforms: ['chrome', 'chrome_beta'],
  },
];

// ---------------------------------------------------------------------------
// Feature flags
// ---------------------------------------------------------------------------

export type FlagEnvironment = 'ALL' | 'DEV' | 'STAGING' | 'PROD';

export interface FeatureFlag {
  key: string;
  enabled: boolean;
  rolloutPercentage: number;
  minimumVersion: string;
  environment: FlagEnvironment;
  description: string;
}

export interface FlagPatch {
  enabled?: boolean;
  rolloutPercentage?: number;
  minimumVersion?: string;
}

// ---------------------------------------------------------------------------
// Announcements
// ---------------------------------------------------------------------------

export type AnnouncementType = 'INFO' | 'UPDATE' | 'WARNING' | 'MAINTENANCE' | 'PROMOTION';
/** SPA vocabulary plus the worker serializer's ACTIVE/ARCHIVED states. */
export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'ACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'ARCHIVED';

export interface Announcement {
  id: string;
  title: string;
  body: string;
  type: AnnouncementType;
  targetRule: string;
  startAt: string;
  endAt: string | null;
  /** Worker serializer field names — read via `startTime ?? startAt`. */
  startTime?: string;
  endTime?: string | null;
  status: AnnouncementStatus;
  createdAt: string;
  createdBy: string;
}

export interface AnnouncementInput {
  title: string;
  body: string;
  type: AnnouncementType;
  targetRule: string;
  startAt: string;
  endAt: string | null;
}

export interface AnnouncementPatch extends Partial<AnnouncementInput> {
  status?: AnnouncementStatus;
}

// ---------------------------------------------------------------------------
// Analytics
// ---------------------------------------------------------------------------

export type AnalyticsRange = '7d' | '30d' | '90d';

export interface DauPoint {
  date: string; // YYYY-MM-DD
  value: number;
}

export interface RetentionCohort {
  cohort: string;
  cohortSize: number;
  day1: number; // percent 0-100
  day7: number;
  day30: number;
}

export interface PlanBreakdown {
  plan: string;
  subscribers: number;
  monthlyValueUsd: number;
}

export interface RevenueSummary {
  /** v2.5.7 (A-4): real plan-catalog-derived monthly recurring in BDT
   *  paisa (smallest unit). The USD field is a deprecated alias the worker
   *  now returns as null. */
  estMonthlyBdt?: number | null;
  /** Deprecated — kept so older builds type-check; the worker sends null. */
  mrrEstimateUsd?: number | null;
  estMonthlyUsd?: number | null;
  premiumUsers: number;
  premiumGrowth?: DauPoint[];
  byPlan?: PlanBreakdown[];
}

/** Worker shape for retention (a single weekly-active stat, not cohorts). */
export interface RetentionSummary {
  weeklyActiveDevices: number;
}

export interface AnalyticsResponse {
  dau: DauPoint[];
  sessions: { started: number; completed: number; bailed: number };
  shorts: { warnings: number; cages: number };
  tempUnlocks: number;
  /** Cohort table (mock/SPA) or the worker's weekly summary object. */
  retention: RetentionCohort[] | RetentionSummary;
  revenue: RevenueSummary;
}

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

export type AuditAction =
  | 'ADMIN_LOGIN'
  | 'ADMIN_LOGIN_FAILED'
  | 'CONFIG_SAVED'
  | 'CONFIG_PUBLISHED'
  | 'CONFIG_ROLLED_BACK'
  | 'DETECTION_RULES_SAVED'
  | 'DETECTION_RULES_PUBLISHED'
  | 'DETECTION_RULES_RESET'
  | 'LEADERBOARD_PROFILE_RESET'
  | 'CLUB_HIDDEN'
  | 'CLUB_RESTORED'
  | 'CLUB_DELETED'
  | 'FLAG_UPDATED'
  | 'ANNOUNCEMENT_CREATED'
  | 'ANNOUNCEMENT_UPDATED'
  | 'USER_STATUS_CHANGED'
  | 'COIN_ADJUSTMENT'
  | 'APP_VERSION_UPDATED'
  | 'ADMIN_CREATED'
  | 'ROLE_CHANGED'
  | 'PLAN_UPDATED'
  | 'BKASH_PAYMENT_VERIFIED'
  | 'BKASH_PAYMENT_REJECTED';

export const AUDIT_ACTIONS: readonly AuditAction[] = [
  'ADMIN_LOGIN',
  'ADMIN_LOGIN_FAILED',
  'CONFIG_SAVED',
  'DETECTION_RULES_SAVED',
  'DETECTION_RULES_PUBLISHED',
  'DETECTION_RULES_RESET',
  'LEADERBOARD_PROFILE_RESET',
  'CLUB_HIDDEN',
  'CLUB_RESTORED',
  'CLUB_DELETED',
  'CONFIG_PUBLISHED',
  'CONFIG_ROLLED_BACK',
  'FLAG_UPDATED',
  'ANNOUNCEMENT_CREATED',
  'ANNOUNCEMENT_UPDATED',
  'USER_STATUS_CHANGED',
  'COIN_ADJUSTMENT',
  'APP_VERSION_UPDATED',
  'ADMIN_CREATED',
  'ROLE_CHANGED',
  'PLAN_UPDATED',
  'BKASH_PAYMENT_VERIFIED',
  'BKASH_PAYMENT_REJECTED',
];

export type AuditResult = 'SUCCESS' | 'FAILURE';

export interface AuditLog {
  id: string;
  adminId: string;
  /** Absent on the worker serializer — fall back to adminId when missing. */
  adminEmail?: string;
  action: AuditAction;
  resourceType: string;
  resourceId: string | null;
  result: AuditResult;
  metadata: Record<string, unknown> | null;
  requestId: string;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Security events
// ---------------------------------------------------------------------------

export type SecuritySeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export interface SecurityEvent {
  id: string;
  userId: string | null;
  userEmail: string | null;
  /** SPA field; the worker sends `eventType` — read both. */
  type: string;
  eventType?: string;
  severity: SecuritySeverity;
  metadata: Record<string, unknown>;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Support tickets
// ---------------------------------------------------------------------------

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED';
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface TicketResponseEntry {
  id: string;
  authorEmail: string;
  body: string;
  createdAt: string;
}

export interface Ticket {
  id: string;
  userId: string;
  userEmail?: string | null;
  /** SPA field; the worker sends `category` — read both. */
  subject?: string;
  category?: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  appVersion: string;
  requestId: string;
  createdAt: string;
  updatedAt: string;
  /** v2.5.7 (A-2): the worker now serializes the admin reply. */
  respondedAt?: string | null;
  /** SPA thread model; the worker stores a single overwritten `response`. */
  responses?: TicketResponseEntry[];
  response?: string | null;
}

export interface TicketPatch {
  status?: TicketStatus;
  response?: string;
}

// ---------------------------------------------------------------------------
// App version policy
// ---------------------------------------------------------------------------

export interface AppVersionPolicy {
  minimum: string;
  latest: string;
  forceUpdate: boolean;
  message: string;
  updatedAt?: string;
  updatedBy?: string;
}

// ---------------------------------------------------------------------------
// System health
// ---------------------------------------------------------------------------

export type HealthStatus = 'healthy' | 'operational' | 'degraded' | 'down' | 'unseeded';

export interface SystemHealth {
  worker: HealthStatus;
  d1LatencyMs: number;
  kv: HealthStatus;
}

// ---------------------------------------------------------------------------
// Overview (dashboard)
// ---------------------------------------------------------------------------

export interface OverviewMetrics {
  totalUsers: number;
  activeUsers7d: number;
  activeDevices: number;
  premiumUsers: number;
  newUsersToday: number;
  sessionsToday: number;
  apiRequests24h: number;
  errorRate: number; // percent
}

export interface OverviewResponse {
  metrics: OverviewMetrics;
  health: {
    api: HealthStatus;
    database: HealthStatus;
    configService: HealthStatus;
  };
  recentAudit: AuditLog[];
}

// ---------------------------------------------------------------------------
// Pagination
// ---------------------------------------------------------------------------

/** Normalized page consumed by the UI (array key differs per contract route). */
export interface PaginatedResponse<T> {
  items: T[];
  nextCursor: string | null;
}

// ---------------------------------------------------------------------------
// Wire shapes — exact response keys from the frozen contract
// ---------------------------------------------------------------------------

export interface UserDetailResponse {
  user: User;
  devices: Device[];
  subscription: Subscription | null;
  securityEvents: SecurityEvent[];
  supportTickets: Ticket[];
}

export interface UsersWireResponse {
  users: User[];
  nextCursor: string | null;
}

export interface DevicesWireResponse {
  devices: Device[];
  nextCursor: string | null;
}

export interface SubscriptionsWireResponse {
  subscriptions: Subscription[];
  nextCursor: string | null;
}

export interface AuditWireResponse {
  auditLogs: AuditLog[];
  nextCursor: string | null;
}

export interface SecurityEventsWireResponse {
  securityEvents: SecurityEvent[];
  nextCursor: string | null;
}

export interface TicketsWireResponse {
  tickets: Ticket[];
  nextCursor: string | null;
}

export interface FlagsWireResponse {
  flags: FeatureFlag[];
}

export interface AnnouncementsWireResponse {
  announcements: Announcement[];
}

export interface AdminUsersWireResponse {
  admins: AdminUser[];
}

// ---------------------------------------------------------------------------
// v2.2 Phase D — plan catalog + bKash payment review
// ---------------------------------------------------------------------------

export type PlanKind = 'monthly' | 'quarterly' | 'semiannual' | 'yearly' | 'trial';
export type PlanSource = 'play' | 'bkash';

export interface Plan {
  id: string;
  productId: string;
  plan: PlanKind;
  source: PlanSource;
  durationDays: number;
  priceMinor: number;
  currency: string;
  displayName: string;
  description: string | null;
  isPopular: boolean;
  isActive: boolean;
  sortOrder: number;
}

export interface PlanPatch {
  priceMinor?: number;
  durationDays?: number;
  isActive?: boolean;
  isPopular?: boolean;
  displayName?: string;
  description?: string | null;
  sortOrder?: number;
}

export type BkashPaymentStatus = 'PENDING' | 'IN_REVIEW' | 'VERIFIED' | 'REJECTED' | 'EXPIRED' | 'CANCELED';

export interface BkashPayment {
  id: string;
  userId: string;
  userEmail: string | null;
  planId: string;
  planName: string | null;
  planDays: number | null;
  reference: string;
  amountMinor: number;
  currency: string;
  trxId: string | null;
  senderNumber: string | null;
  status: BkashPaymentStatus;
  submittedAt: string | null;
  reviewedAt: string | null;
  rejectReason: string | null;
  createdAt: string;
}

export interface BkashPaymentsSummary {
  inReview: number;
  verified: number;
  verifiedAmountMinor: number;
}

export interface PlansWireResponse {
  plans: Plan[];
}

export interface BkashPaymentsWireResponse {
  payments: BkashPayment[];
  nextCursor: string | null;
  summary: BkashPaymentsSummary;
}

// ---------------------------------------------------------------------------
// v2.5.8 — community leaderboard + clubs moderation
// ---------------------------------------------------------------------------

export type LeaderboardWindow = 'weekly' | 'monthly' | 'alltime';

export interface LeaderboardTotals {
  profiles: number;
  optedIn: number;
  lifetimeDp: number;
  lastSync: string | null;
  clubs: number;
  hiddenClubs: number;
}

export interface LeaderboardEntry {
  rank: number;
  userId: string;
  displayName: string;
  email: string;
  userStatus: UserStatus;
  levelName: string | null;
  streakDays: number;
  value: number;
  lifetimeDp: number;
  optedIn: boolean;
  displayMode: 'name' | 'anonymous';
  updatedAt: string;
}

export interface LeaderboardResponse {
  totals: LeaderboardTotals;
  window: LeaderboardWindow;
  entries: LeaderboardEntry[];
}

export interface Club {
  id: string;
  name: string;
  description: string | null;
  inviteCode: string;
  createdBy: string;
  creatorName: string | null;
  hidden: boolean;
  memberCount: number;
  createdAt: string;
}

export interface ClubsResponse {
  clubs: Club[];
  page: number;
  pageSize: number;
  total: number;
}
