/**
 * MAXLEVEL DETOX — audit log + security event services.
 *
 * audit_logs and security_events are APPEND-ONLY. Besides the code never
 * issuing UPDATE/DELETE against them, the schema installs BEFORE UPDATE /
 * BEFORE DELETE triggers that ABORT any such statement (defense in depth).
 * Both helpers swallow and log insertion errors so that a logging failure
 * never breaks the response path — the action is still observable in logs.
 */

import { Env, SecuritySeverity } from '../types';
import { randomToken } from '../utils/crypto';

/** Frozen AUDIT ACTIONS (contract v1). Extensions are marked at call sites. */
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
  | 'ROLE_CHANGED';

/**
 * Append an immutable audit entry.
 * @param env          worker bindings
 * @param adminId      acting admin id (or 'unknown' for failed logins)
 * @param action       one of the frozen AUDIT ACTIONS
 * @param resourceType e.g. 'config' | 'user' | 'flag'
 * @param resourceId   identifies the affected resource (nullable)
 * @param result       'SUCCESS' | 'FAILURE' | detail string
 * @param requestId    correlation id of the triggering request
 */
export async function appendAudit(
  env: Env,
  adminId: string,
  action: AuditAction | string,
  resourceType: string,
  resourceId: string | null,
  result: string,
  requestId: string
): Promise<void> {
  try {
    await env.DB.prepare(
      `INSERT INTO audit_logs (id, admin_id, action, resource_type, resource_id, result, request_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        `aud_${randomToken(12)}`,
        adminId,
        action,
        resourceType,
        resourceId,
        result,
        requestId,
        new Date().toISOString()
      )
      .run();
  } catch (err) {
    console.error('audit_logs insert failed', action, err);
  }
}

/**
 * Append an immutable security event (e.g. failed admin logins, deletion
 * requests, suspected abuse).
 */
export async function appendSecurityEvent(
  env: Env,
  userId: string | null,
  deviceId: string | null,
  eventType: string,
  severity: SecuritySeverity,
  metadata: Record<string, unknown>
): Promise<void> {
  try {
    await env.DB.prepare(
      `INSERT INTO security_events (id, user_id, device_id, event_type, severity, metadata, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        `sec_${randomToken(12)}`,
        userId,
        deviceId,
        eventType,
        severity,
        JSON.stringify(metadata),
        new Date().toISOString()
      )
      .run();
  } catch (err) {
    console.error('security_events insert failed', eventType, err);
  }
}
