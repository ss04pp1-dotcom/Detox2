/**
 * MAXLEVEL DETOX — shared type definitions.
 *
 * This module is the single source of truth for:
 *  - Worker bindings (`Env`)
 *  - The per-request router `Context`
 *  - API-facing domain types (frozen API contract v1)
 *  - D1 row shapes (must stay in sync with src/db/schema.sql)
 *
 * No runtime code lives here — types only.
 */

// ---------------------------------------------------------------------------
// Environment / bindings
// ---------------------------------------------------------------------------

export type ApiEnvironment = 'development' | 'staging' | 'production';

/** Worker bindings, from wrangler.toml ([vars], D1, KV) and wrangler secrets. */
export interface Env {
  /** D1 database binding — the system of record. */
  DB: D1Database;
  /** KV namespace — rate limiting, admin sessions, config/token caches. */
  KV: KVNamespace;
  /**
   * Google OAuth secret (wrangler secret). Per the frozen task contract it is
   * ALSO used as the placeholder expected `aud` for Google ID-token
   * verification when GOOGLE_CLIENT_ID is not configured. When unset (and no
   * GOOGLE_CLIENT_ID) AND API_ENV === 'development', dev-mode credentials
   * ("dev:<email>") are accepted. See README before enabling production.
   */
  GOOGLE_CLIENT_SECRET: string;
  /** HMAC secret used to make admin session KV values tamper-evident. */
  ADMIN_SESSION_SECRET: string;
  /** Deployment environment (from [vars]). */
  API_ENV: ApiEnvironment;
  /** Optional: the real Google OAuth client ID for the aud check. */
  GOOGLE_CLIENT_ID?: string;
  /** Optional: comma-separated CORS origin allowlist override (admin origin). */
  ADMIN_ORIGIN?: string;
  /** Optional: Google Play service account email (subscription verification). */
  GOOGLE_PLAY_SA_EMAIL?: string;
  /** Optional: Google Play service account private key, PEM (subscription verification). */
  GOOGLE_PLAY_SA_PRIVATE_KEY?: string;
  /** Optional (v2.5 r9): LLM API key for the Sinthia companion proxy. When
   *  unset the /ai/chat route serves the scripted persona layer only. */
  AI_API_KEY?: string;
}

// ---------------------------------------------------------------------------
// Request context / router plumbing
// ---------------------------------------------------------------------------

/** Authenticated app-user context injected by middleware/auth.ts. */
export interface UserContext {
  userId: string;
  email: string;
  deviceId: string | null;
  /** SHA-256 hex of the presented bearer token (session PK). */
  tokenHash: string;
}

export type AdminRole =
  | 'SUPER_ADMIN'
  | 'ADMIN'
  | 'SUPPORT'
  | 'ANALYST'
  | 'CONFIG_MANAGER'
  | 'READ_ONLY';

/** Authenticated admin context injected by middleware/adminAuth.ts. */
export interface AdminContext {
  adminId: string;
  email: string;
  /** Current role, always re-read from D1 (not the KV snapshot). */
  role: AdminRole;
}

/** Per-request context threaded through middleware and handlers. */
export interface Context {
  req: Request;
  url: URL;
  env: Env;
  ctx: ExecutionContext;
  requestId: string;
  params: Record<string, string>;
  user: UserContext | null;
  admin: AdminContext | null;
}

/** Middleware may short-circuit by returning a Response. */
export type Middleware = (c: Context) => Promise<Response | void>;

/** Route handlers always return a Response (frozen envelope). */
export type Handler = (c: Context) => Promise<Response>;

// ---------------------------------------------------------------------------
// Frozen error codes (API contract v1)
// ---------------------------------------------------------------------------

export type ErrorCode =
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
// Domain enums (must match schema CHECK constraints)
// ---------------------------------------------------------------------------

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED' | 'DELETED';
export type ConfigStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type AnnouncementStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
export type AnnouncementType = 'INFO' | 'UPDATE' | 'WARNING' | 'MAINTENANCE' | 'PROMOTION';
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED';
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type SecuritySeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type SubscriptionStatus = 'ACTIVE' | 'CANCELED' | 'EXPIRED' | 'PENDING';
export type AdminStatus = 'ACTIVE' | 'DISABLED';
// v2.2 Phase D — monetization enums.
export type PlanKind = 'monthly' | 'quarterly' | 'semiannual' | 'yearly' | 'trial';
export type PlanSource = 'play' | 'bkash';
export type BkashPaymentStatus =
  | 'PENDING'
  | 'IN_REVIEW'
  | 'VERIFIED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELED';

// ---------------------------------------------------------------------------
// API-facing types (frozen contract v1)
// ---------------------------------------------------------------------------

export interface User {
  id: string;
  email: string;
  displayName: string | null;
  status: UserStatus;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string | null;
  deletionPending: boolean;
}

export interface Device {
  id: string;
  userId: string;
  platform: string;
  manufacturer: string | null;
  model: string | null;
  androidVersion: string | null;
  appVersion: string | null;
  registeredAt: string;
  lastSeenAt: string | null;
  status: string;
  permissionSummary: string | null;
  activeSession: boolean;
}

export interface AdminUser {
  id: string;
  email: string;
  /** v2.5.7 (A-1): display name — now stored (004) and serialized. */
  name?: string | null;
  role: AdminRole;
  status: AdminStatus;
  createdAt: string;
  lastLoginAt: string | null;
}

/** Remote config document — validated/clamped against frozen CONFIG BOUNDS. */
export interface ConfigDoc {
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
  // v2.2 Phase D — growth & monetization. `paymentsEnabled` is the global
  // kill-switch for BOTH the bKash gateway and the trial claim route.
  paymentsEnabled: boolean;
  bkashNumber: string;
  bkashInstructions: string;
  trialDays: number;
  breakPassesPerWeek: number;
  insightNudgeEnabled: boolean;
  insightNudgeHour: number;
}

export interface FeatureFlag {
  key: string;
  enabled: boolean;
  rolloutPercentage: number;
  minimumVersion: string | null;
  environment: string;
}

export interface Announcement {
  id: string;
  title: string;
  body: string;
  type: AnnouncementType;
  status: AnnouncementStatus;
  targetRule: string | null;
  startTime: string | null;
  endTime: string | null;
  createdAt: string;
}

export interface AuditLog {
  id: string;
  adminId: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string;
  requestId: string | null;
  createdAt: string;
}

export interface SecurityEvent {
  id: string;
  userId: string | null;
  deviceId: string | null;
  eventType: string;
  severity: SecuritySeverity;
  metadata: string | null;
  createdAt: string;
}

export interface Ticket {
  id: string;
  userId: string | null;
  /** v2.5.7 (A-1): joined from users.email by the admin list query. */
  userEmail?: string | null;
  category: string | null;
  description: string | null;
  appVersion: string | null;
  requestId: string | null;
  status: TicketStatus;
  priority: TicketPriority;
  assignedTo: string | null;
  /** v2.5.7 (A-2): admin reply + timestamp now serialized. */
  response?: string | null;
  respondedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AppVersion {
  id: number;
  minimum: string;
  latest: string;
  forceUpdate: boolean;
  message: string;
  createdAt: string;
}

export interface EventRecord {
  eventId: string;
  deviceId: string | null;
  userId: string | null;
  type: string;
  payload: string | null;
  occurredAt: string;
  receivedAt: string;
}

export interface Subscription {
  id: string;
  userId: string;
  productId: string;
  purchaseToken: string;
  plan: string;
  status: SubscriptionStatus;
  startDate: string | null;
  expiryDate: string | null;
  lastVerified: string | null;
}

// v2.2 Phase D — plan catalog + trial + bKash payments.
export interface Plan {
  id: string;
  productId: string;
  plan: PlanKind;
  source: PlanSource;
  durationDays: number;
  /** Price in the smallest currency unit (paisa for BDT). */
  priceMinor: number;
  currency: string;
  displayName: string;
  description: string | null;
  isPopular: boolean;
  isActive: boolean;
  sortOrder: number;
}

export interface TrialStatus {
  eligible: boolean;
  active: boolean;
  claimedAt: string | null;
  expiresAt: string | null;
}

export interface BkashPayment {
  id: string;
  userId: string;
  planId: string;
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

// ---------------------------------------------------------------------------
// D1 row shapes (snake_case, mirrors src/db/schema.sql)
// ---------------------------------------------------------------------------

export interface UserRow {
  id: string;
  email: string;
  display_name: string | null;
  status: string;
  /** Not selected by list queries; toApiUser never reads it. */
  google_sub?: string | null;
  /** v2.5.7 (004): deterministic referral code (set lazily by GET /referral). */
  referral_code?: string | null;
  created_at: string;
  updated_at: string;
  last_seen_at: string | null;
  deletion_pending_at: string | null;
}

export interface UserSessionRow {
  token_hash: string;
  user_id: string;
  refresh_token_hash: string | null;
  access_expires_at: string;
  expires_at: string;
  created_at: string;
  device_id: string | null;
}

export interface DeviceRow {
  id: string;
  user_id: string;
  platform: string;
  manufacturer: string | null;
  model: string | null;
  android_version: string | null;
  app_version: string | null;
  registered_at: string;
  last_seen_at: string | null;
  status: string;
  permission_summary: string | null;
  active_session_flag: number;
}

export interface SubscriptionRow {
  id: string;
  user_id: string;
  product_id: string;
  purchase_token: string;
  plan: string;
  status: string;
  start_date: string | null;
  expiry_date: string | null;
  last_verified: string | null;
  created_at: string;
  updated_at: string;
}

export interface CoinTransactionRow {
  id: string;
  user_id: string;
  transaction_id: string;
  type: string;
  amount: number;
  balance_after: number;
  source: string;
  reference: string | null;
  created_at: string;
}

export interface ConfigVersionRow {
  version: number;
  config_json: string;
  status: string;
  created_by: string | null;
  created_at: string;
  published_at: string | null;
}

export interface FeatureFlagRow {
  key: string;
  enabled: number;
  rollout_percentage: number;
  minimum_version: string | null;
  environment: string;
  updated_by: string | null;
  updated_at: string;
}

export interface AnnouncementRow {
  id: string;
  title: string;
  body: string;
  type: string;
  status: string;
  target_rule: string | null;
  start_at: string | null;
  end_at: string | null;
  created_by: string | null;
  created_at: string;
}

export interface EventRow {
  event_id: string;
  device_id: string | null;
  user_id: string | null;
  type: string;
  payload: string | null;
  occurred_at: string;
  received_at: string;
}

export interface TicketRow {
  id: string;
  user_id: string | null;
  category: string | null;
  description: string | null;
  app_version: string | null;
  request_id: string | null;
  status: string;
  priority: string;
  assigned_to: string | null;
  /** v2.5.7 (A-2): present on SELECT * rows (schema + 004-era columns). */
  response?: string | null;
  responded_at?: string | null;
  created_at: string;
  updated_at: string;
}

export interface AuditLogRow {
  id: string;
  admin_id: string;
  action: string;
  resource_type: string;
  resource_id: string | null;
  result: string;
  request_id: string | null;
  created_at: string;
}

export interface SecurityEventRow {
  id: string;
  user_id: string | null;
  device_id: string | null;
  event_type: string;
  severity: string;
  metadata: string | null;
  created_at: string;
}

export interface AppVersionRow {
  id: number;
  minimum: string;
  latest: string;
  force_update: number;
  message: string | null;
  created_by: string | null;
  created_at: string;
}

export interface AdminUserRow {
  id: string;
  email: string;
  password_hash: string;
  role: string;
  status: string;
  /** v2.5.7 (004): display name + lockout counters. */
  name?: string | null;
  failed_login_count?: number;
  locked_until?: string | null;
  created_at: string;
  last_login_at: string | null;
  created_by: string | null;
}

export interface SubscriptionPlanRow {
  id: string;
  product_id: string;
  plan: string;
  source: string;
  duration_days: number;
  price_minor: number;
  currency: string;
  display_name: string;
  description: string | null;
  is_popular: number;
  is_active: number;
  sort_order: number;
  created_at: string;
  updated_at: string;
}

export interface TrialClaimRow {
  id: string;
  user_id: string;
  device_id: string;
  claimed_at: string;
  expires_at: string;
}

export interface BkashPaymentRow {
  id: string;
  user_id: string;
  device_id: string | null;
  plan_id: string;
  reference: string;
  amount_minor: number;
  currency: string;
  trx_id: string | null;
  sender_number: string | null;
  status: string;
  submitted_at: string | null;
  reviewed_by: string | null;
  reviewed_at: string | null;
  reject_reason: string | null;
  subscription_id: string | null;
  created_at: string;
  updated_at: string;
}
