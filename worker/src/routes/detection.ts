/**
 * MAXLEVEL DETOX — dynamic detection-rules routes (v2.5.8 roadmap).
 *
 * App routes (requireUser):
 *   GET /api/v1/detection/rules   published ruleset + version for the client
 *                                 (compiled-default fallback when unseeded)
 *
 * Admin routes (MANAGE_CONFIG):
 *   GET  /admin/detection-rules            draft + published + defaults + history
 *   PUT  /admin/detection-rules            validate + upsert the DRAFT row
 *   POST /admin/detection-rules/publish    archive current, publish draft
 *   POST /admin/detection-rules/reset      overwrite draft with frozen defaults
 *
 * The app endpoint deliberately answers 200 with the DEFAULT doc + version 0
 * when nothing is published yet: the client must always receive a complete,
 * valid ruleset (offline-first / unseeded-safe), and version 0 tells it "you
 * are on compiled defaults".
 */

import { Context } from '../types';
import { ok, fail } from '../utils/response';
import { readJsonBody, validateFields } from '../middleware/validation';
import { appendAudit } from '../services/audit';
import {
  DEFAULT_DETECTION_RULES,
  validateDetectionRules,
  DetectionRulesDoc,
} from '../services/detectionRules';

interface DetectionRulesRow {
  version: number;
  rules_json: string;
  status: string;
  created_by: string | null;
  created_at: string;
  published_at: string | null;
}

interface DetectionRulesVersionRow {
  version: number;
  status: string;
  created_by: string | null;
  created_at: string;
  published_at: string | null;
}

/** Parse + strictly validate a stored row; null when corrupt or invalid. */
function parseStoredRules(
  requestId: string,
  version: number,
  rawJson: string
): DetectionRulesDoc | null {
  try {
    const parsed: unknown = JSON.parse(rawJson);
    const result = validateDetectionRules(parsed);
    if (result.ok) return result.rules;
    console.error(requestId, 'stored detection rules invalid, version', version, result.errors);
    return null;
  } catch {
    console.error(requestId, 'stored detection rules corrupt, version', version);
    return null;
  }
}

function versionSummary(r: DetectionRulesVersionRow): Record<string, unknown> {
  return {
    version: r.version,
    status: r.status,
    createdBy: r.created_by,
    createdAt: r.created_at,
    publishedAt: r.published_at,
  };
}

// ---------------------------------------------------------------------------
// App: GET /detection/rules
// ---------------------------------------------------------------------------

export async function getDetectionRules(c: Context): Promise<Response> {
  const row = await c.env.DB.prepare(
    `SELECT version, rules_json FROM detection_rules WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1`
  ).first<{ version: number; rules_json: string }>();

  if (row === null) {
    // Unseeded deployment: hand back the compiled defaults with version 0.
    return ok(c, { version: 0, rules: DEFAULT_DETECTION_RULES });
  }

  const parsed = parseStoredRules(c.requestId, row.version, row.rules_json);
  if (parsed === null) {
    // Corrupt published row fails SAFE to the frozen defaults (same policy
    // as readPublishedConfig).
    return ok(c, { version: 0, rules: DEFAULT_DETECTION_RULES, fallback: true });
  }
  return ok(c, { version: row.version, rules: parsed });
}

// ---------------------------------------------------------------------------
// Admin: GET /detection-rules
// ---------------------------------------------------------------------------

export async function adminGetDetectionRules(c: Context): Promise<Response> {
  const db = c.env.DB;
  const [draftRow, publishedRow, versionsRows] = await Promise.all([
    db.prepare(
      `SELECT * FROM detection_rules WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1`
    ).first<DetectionRulesRow>(),
    db.prepare(
      `SELECT * FROM detection_rules WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1`
    ).first<DetectionRulesRow>(),
    db.prepare(
      `SELECT version, status, created_by, created_at, published_at FROM detection_rules ORDER BY version DESC LIMIT 50`
    ).all<DetectionRulesVersionRow>(),
  ]);

  let draft: DetectionRulesDoc | null = null;
  let draftVersion: number | null = null;
  if (draftRow !== null) {
    draft = parseStoredRules(c.requestId, draftRow.version, draftRow.rules_json);
    if (draft === null) return fail(c, 'SERVER_ERROR', 'Draft detection rules are corrupt', 500);
    draftVersion = draftRow.version;
  }

  let published: DetectionRulesDoc | null = null;
  let publishedVersion: number | null = null;
  if (publishedRow !== null) {
    published = parseStoredRules(c.requestId, publishedRow.version, publishedRow.rules_json);
    if (published === null) {
      return fail(c, 'SERVER_ERROR', 'Published detection rules are corrupt', 500);
    }
    publishedVersion = publishedRow.version;
  }

  return ok(c, {
    draft: draft === null ? null : { ...draft, _version: draftVersion },
    published: published === null ? null : { ...published, _version: publishedVersion },
    defaults: DEFAULT_DETECTION_RULES,
    versions: versionsRows.results.map(versionSummary),
  });
}

// ---------------------------------------------------------------------------
// Admin: PUT /detection-rules (upsert draft)
// ---------------------------------------------------------------------------

export async function adminSaveDetectionRulesDraft(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  if (body === null || !('rules' in body)) {
    return fail(c, 'VALIDATION_FAILED', 'Body must contain a "rules" object', 400);
  }
  const result = validateDetectionRules(body.rules);
  if (!result.ok) {
    return fail(c, 'VALIDATION_FAILED', result.errors.join('; '), 400);
  }

  const rulesJson = JSON.stringify(result.rules);
  const now = new Date().toISOString();
  const existing = await c.env.DB.prepare(
    `SELECT version FROM detection_rules WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1`
  ).first<{ version: number }>();

  let version: number;
  if (existing === null) {
    const res = await c.env.DB.prepare(
      `INSERT INTO detection_rules (rules_json, status, created_by, created_at)
       VALUES (?, 'DRAFT', ?, ?)`
    )
      .bind(rulesJson, c.admin!.adminId, now)
      .run();
    version = Number(res.meta.last_row_id ?? 0);
  } else {
    version = existing.version;
    await c.env.DB.prepare(
      `UPDATE detection_rules SET rules_json = ?, created_by = ?, created_at = ?
       WHERE version = ? AND status = 'DRAFT'`
    )
      .bind(rulesJson, c.admin!.adminId, now, version)
      .run();
  }

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'DETECTION_RULES_SAVED',
    'detection_rules',
    String(version),
    result.clamped.join('; ') || 'ok',
    c.requestId
  );
  return ok(c, { draft: { ...result.rules, _version: version }, clamped: result.clamped });
}

// ---------------------------------------------------------------------------
// Admin: POST /detection-rules/publish
// ---------------------------------------------------------------------------

export async function adminPublishDetectionRules(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, { confirm: { type: 'boolean', required: true } });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (v.value.confirm !== true) {
    return fail(c, 'INVALID_REQUEST', 'Publishing requires confirm: true', 400);
  }

  const draft = await c.env.DB.prepare(
    `SELECT * FROM detection_rules WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1`
  ).first<DetectionRulesRow>();
  if (draft === null) {
    return fail(c, 'NOT_FOUND', 'No draft ruleset to publish', 404);
  }

  const draftRules = parseStoredRules(c.requestId, draft.version, draft.rules_json);
  if (draftRules === null) {
    return fail(c, 'SERVER_ERROR', 'Draft detection rules are corrupt', 500);
  }
  const recheck = validateDetectionRules(draftRules);
  if (!recheck.ok) {
    return fail(c, 'VALIDATION_FAILED', `Draft is invalid: ${recheck.errors.join('; ')}`, 400);
  }

  const now = new Date().toISOString();
  // Archive the currently published ruleset, then publish the draft (same
  // two-step shape as adminPublishConfig).
  await c.env.DB.prepare(
    `UPDATE detection_rules SET status = 'ARCHIVED' WHERE status = 'PUBLISHED'`
  ).run();
  await c.env.DB.prepare(
    `UPDATE detection_rules SET status = 'PUBLISHED', published_at = ?
     WHERE version = ? AND status = 'DRAFT'`
  )
    .bind(now, draft.version)
    .run();

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'DETECTION_RULES_PUBLISHED',
    'detection_rules',
    String(draft.version),
    'ok',
    c.requestId
  );
  return ok(c, { published: { ...draftRules, _version: draft.version } });
}

// ---------------------------------------------------------------------------
// Admin: POST /detection-rules/reset (draft <- frozen defaults)
// ---------------------------------------------------------------------------

export async function adminResetDetectionRules(c: Context): Promise<Response> {
  const body = await readJsonBody(c);
  const v = validateFields(body, { confirm: { type: 'boolean', required: true } });
  if (!v.ok) return fail(c, 'VALIDATION_FAILED', v.errors.join('; '), 400);
  if (v.value.confirm !== true) {
    return fail(c, 'INVALID_REQUEST', 'Reset requires confirm: true', 400);
  }

  const rulesJson = JSON.stringify(DEFAULT_DETECTION_RULES);
  const now = new Date().toISOString();
  const existing = await c.env.DB.prepare(
    `SELECT version FROM detection_rules WHERE status = 'DRAFT' ORDER BY version DESC LIMIT 1`
  ).first<{ version: number }>();

  let version: number;
  if (existing === null) {
    const res = await c.env.DB.prepare(
      `INSERT INTO detection_rules (rules_json, status, created_by, created_at)
       VALUES (?, 'DRAFT', ?, ?)`
    )
      .bind(rulesJson, c.admin!.adminId, now)
      .run();
    version = Number(res.meta.last_row_id ?? 0);
  } else {
    version = existing.version;
    await c.env.DB.prepare(
      `UPDATE detection_rules SET rules_json = ?, created_by = ?, created_at = ?
       WHERE version = ? AND status = 'DRAFT'`
    )
      .bind(rulesJson, c.admin!.adminId, now, version)
      .run();
  }

  await appendAudit(
    c.env,
    c.admin!.adminId,
    'DETECTION_RULES_RESET',
    'detection_rules',
    String(version),
    'draft restored to frozen defaults',
    c.requestId
  );
  return ok(c, { draft: { ...DEFAULT_DETECTION_RULES, _version: version } });
}
