/**
 * MAXLEVEL DETOX — D1 row -> API type serializers.
 * Single shared mapping so app and admin routes return identical shapes.
 */

import {
  AdminStatus,
  AdminUser,
  AdminUserRow,
  Announcement,
  AnnouncementRow,
  AnnouncementStatus,
  AnnouncementType,
  AppVersion,
  AppVersionRow,
  AuditLog,
  AuditLogRow,
  BkashPayment,
  BkashPaymentRow,
  BkashPaymentStatus,
  CoinTransactionRow,
  Device,
  DeviceRow,
  EventRecord,
  EventRow,
  FeatureFlag,
  FeatureFlagRow,
  Plan,
  PlanKind,
  PlanSource,
  SecurityEvent,
  SecurityEventRow,
  SecuritySeverity,
  Subscription,
  SubscriptionRow,
  SubscriptionStatus,
  SubscriptionPlanRow,
  Ticket,
  TicketRow,
  TicketPriority,
  TicketStatus,
  User,
  UserRow,
  UserStatus,
} from '../types';

function asEnum<T extends string>(value: string, allowed: readonly T[], fallback: T): T {
  return (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
}

const USER_STATUSES: readonly UserStatus[] = ['ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED'];
const SUB_STATUSES: readonly SubscriptionStatus[] = ['ACTIVE', 'CANCELED', 'EXPIRED', 'PENDING'];
const ANN_TYPES: readonly AnnouncementType[] = ['INFO', 'UPDATE', 'WARNING', 'MAINTENANCE', 'PROMOTION'];
const ANN_STATUSES: readonly AnnouncementStatus[] = ['DRAFT', 'ACTIVE', 'ARCHIVED'];
const TICKET_STATUSES: readonly TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'];
const TICKET_PRIORITIES: readonly TicketPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
const SEVERITIES: readonly SecuritySeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const ADMIN_STATUSES: readonly AdminStatus[] = ['ACTIVE', 'DISABLED'];
const DEFAULT_PLAN_KINDS: readonly PlanKind[] = ['monthly', 'quarterly', 'semiannual', 'yearly', 'trial'];
const DEFAULT_PLAN_SOURCES: readonly PlanSource[] = ['play', 'bkash'];
const BKASH_STATUSES: readonly BkashPaymentStatus[] = [
  'PENDING',
  'IN_REVIEW',
  'VERIFIED',
  'REJECTED',
  'EXPIRED',
  'CANCELED',
];

export function toApiUser(row: UserRow): User {
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name,
    // v2.9.6 r22 (007): signup profile fields. `undefined` (column not yet
    // migrated on an old DB) and null both surface as null in the API.
    age: row.age ?? null,
    grade: row.grade ?? null,
    status: asEnum(row.status, USER_STATUSES, 'ACTIVE'),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    lastSeenAt: row.last_seen_at,
    deletionPending: row.deletion_pending_at !== null,
  };
}

export function toApiDevice(row: DeviceRow): Device {
  return {
    id: row.id,
    userId: row.user_id,
    platform: row.platform,
    manufacturer: row.manufacturer,
    model: row.model,
    androidVersion: row.android_version,
    appVersion: row.app_version,
    registeredAt: row.registered_at,
    lastSeenAt: row.last_seen_at,
    status: row.status,
    permissionSummary: row.permission_summary,
    activeSession: row.active_session_flag === 1,
  };
}

export function toApiSubscription(row: SubscriptionRow): Subscription {
  return {
    id: row.id,
    userId: row.user_id,
    productId: row.product_id,
    purchaseToken: row.purchase_token,
    plan: row.plan,
    status: asEnum(row.status, SUB_STATUSES, 'PENDING'),
    startDate: row.start_date,
    expiryDate: row.expiry_date,
    lastVerified: row.last_verified,
  };
}

/** v2.2 Phase D — plan catalog row -> API shape. */
export function toApiPlan(
  row: SubscriptionPlanRow,
  kinds: readonly PlanKind[] = DEFAULT_PLAN_KINDS,
  sources: readonly PlanSource[] = DEFAULT_PLAN_SOURCES
): Plan {
  return {
    id: row.id,
    productId: row.product_id,
    plan: asEnum(row.plan, kinds, 'monthly'),
    source: asEnum(row.source, sources, 'play'),
    durationDays: row.duration_days,
    priceMinor: row.price_minor,
    currency: row.currency,
    displayName: row.display_name,
    description: row.description,
    isPopular: row.is_popular === 1,
    isActive: row.is_active === 1,
    sortOrder: row.sort_order,
  };
}

/** v2.2 Phase D — bKash payment row -> API shape (never leaks reviewer data). */
export function toApiBkashPayment(row: BkashPaymentRow): BkashPayment {
  return {
    id: row.id,
    userId: row.user_id,
    planId: row.plan_id,
    reference: row.reference,
    amountMinor: row.amount_minor,
    currency: row.currency,
    trxId: row.trx_id,
    senderNumber: row.sender_number,
    status: asEnum(row.status, BKASH_STATUSES, 'PENDING'),
    submittedAt: row.submitted_at,
    reviewedAt: row.reviewed_at,
    rejectReason: row.reject_reason,
    createdAt: row.created_at,
  };
}

/** v2.5.7 (C-3): coin ledger row -> API shape (server coin mirror). */
export function toApiCoinTransaction(row: CoinTransactionRow): {
  id: string;
  transactionId: string;
  type: string;
  amount: number;
  balanceAfter: number;
  source: string | null;
  reference: string | null;
  createdAt: string;
} {
  return {
    id: row.id,
    transactionId: row.transaction_id,
    type: row.type,
    amount: row.amount,
    balanceAfter: row.balance_after,
    source: row.source,
    reference: row.reference,
    createdAt: row.created_at,
  };
}

export function toApiFeatureFlag(row: FeatureFlagRow): FeatureFlag {
  return {
    key: row.key,
    enabled: row.enabled === 1,
    rolloutPercentage: row.rollout_percentage,
    minimumVersion: row.minimum_version,
    environment: row.environment,
  };
}

export function toApiAnnouncement(row: AnnouncementRow): Announcement {
  return {
    id: row.id,
    title: row.title,
    body: row.body,
    type: asEnum(row.type, ANN_TYPES, 'INFO'),
    status: asEnum(row.status, ANN_STATUSES, 'DRAFT'),
    targetRule: row.target_rule,
    startTime: row.start_at,
    endTime: row.end_at,
    createdAt: row.created_at,
  };
}

export function toApiAuditLog(row: AuditLogRow): AuditLog {
  return {
    id: row.id,
    adminId: row.admin_id,
    action: row.action,
    resourceType: row.resource_type,
    resourceId: row.resource_id,
    result: row.result,
    requestId: row.request_id,
    createdAt: row.created_at,
  };
}

export function toApiSecurityEvent(row: SecurityEventRow): SecurityEvent {
  return {
    id: row.id,
    userId: row.user_id,
    deviceId: row.device_id,
    eventType: row.event_type,
    severity: asEnum(row.severity, SEVERITIES, 'LOW'),
    metadata: row.metadata,
    createdAt: row.created_at,
  };
}

export function toApiTicket(row: TicketRow): Ticket {
  return {
    id: row.id,
    userId: row.user_id,
    // v2.5.7 (A-1): the admin list query joins users.email AS user_email —
    // serialize it so the SPA stops showing raw user ids.
    userEmail: (row as TicketRow & { user_email?: string | null }).user_email ?? null,
    category: row.category,
    description: row.description,
    appVersion: row.app_version,
    requestId: row.request_id,
    status: asEnum(row.status, TICKET_STATUSES, 'OPEN'),
    priority: asEnum(row.priority, TICKET_PRIORITIES, 'LOW'),
    assignedTo: row.assigned_to,
    // v2.5.7 (A-2): the admin reply is persisted by adminPatchTicket —
    // serialize it back so the detail drawer can show previous responses.
    response: row.response ?? null,
    respondedAt: row.responded_at ?? null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export function toApiAppVersion(row: AppVersionRow): AppVersion {
  return {
    id: row.id,
    minimum: row.minimum,
    latest: row.latest,
    forceUpdate: row.force_update === 1,
    message: row.message ?? '',
    createdAt: row.created_at,
  };
}

export function toApiEvent(row: EventRow): EventRecord {
  return {
    eventId: row.event_id,
    deviceId: row.device_id,
    userId: row.user_id,
    type: row.type,
    payload: row.payload,
    occurredAt: row.occurred_at,
    receivedAt: row.received_at,
  };
}

export function toApiAdminUser(row: AdminUserRow): AdminUser {
  return {
    id: row.id,
    email: row.email,
    // v2.5.7 (A-1): display name now persisted (004) and surfaced.
    name: row.name ?? null,
    role: asEnum<'SUPER_ADMIN' | 'ADMIN' | 'SUPPORT' | 'ANALYST' | 'CONFIG_MANAGER' | 'READ_ONLY'>(
      row.role,
      ['SUPER_ADMIN', 'ADMIN', 'SUPPORT', 'ANALYST', 'CONFIG_MANAGER', 'READ_ONLY'],
      'READ_ONLY'
    ),
    status: asEnum(row.status, ADMIN_STATUSES, 'DISABLED'),
    createdAt: row.created_at,
    lastLoginAt: row.last_login_at,
  };
}
