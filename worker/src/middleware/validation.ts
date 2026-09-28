/**
 * MAXLEVEL DETOX — input validation.
 *
 * Small dependency-free schema validator (required fields, type checks,
 * string length caps, numeric ranges, email format) plus the frozen
 * CONFIG BOUNDS validator with server-side clamping and rejection of
 * unknown keys. All failures produce the VALIDATION_FAILED envelope.
 */

import { ConfigDoc, Context } from '../types';

/** Global cap for any single string field (frozen security rule). */
export const MAX_STRING_LENGTH = 10_000;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ---------------------------------------------------------------------------
// Basic guards
// ---------------------------------------------------------------------------

export function isRecord(x: unknown): x is Record<string, unknown> {
  return typeof x === 'object' && x !== null && !Array.isArray(x);
}

/** Safely read and parse a JSON object body; null when not an object. */
export async function readJsonBody(c: Context): Promise<Record<string, unknown> | null> {
  try {
    const data: unknown = await c.req.json();
    return isRecord(data) ? data : null;
  } catch {
    return null;
  }
}

/** Parse a stored JSON string into a record; null on failure. */
export function parseJsonObject(s: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(s);
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Field schema validator
// ---------------------------------------------------------------------------

export interface FieldSpec {
  type: 'string' | 'number' | 'boolean' | 'email' | 'object';
  required?: boolean;
  /** Strings: min/max length (default cap MAX_STRING_LENGTH). */
  minLength?: number;
  maxLength?: number;
  /** Numbers: inclusive range + integer constraint. */
  min?: number;
  max?: number;
  integer?: boolean;
  /** Strings: allowed values. */
  enumValues?: readonly string[];
}

export interface ValidationResult {
  ok: boolean;
  /** Validated (and for emails, normalized) values. */
  value: Record<string, unknown>;
  errors: string[];
}

export function validateFields(
  body: unknown,
  schema: Record<string, FieldSpec>
): ValidationResult {
  if (!isRecord(body)) {
    return { ok: false, value: {}, errors: ['Request body must be a JSON object'] };
  }

  const errors: string[] = [];
  const value: Record<string, unknown> = {};

  for (const [name, spec] of Object.entries(schema)) {
    const raw = body[name];
    const present = raw !== undefined && raw !== null;

    if (!present) {
      if (spec.required === true) errors.push(`${name} is required`);
      continue;
    }

    switch (spec.type) {
      case 'string': {
        if (typeof raw !== 'string') {
          errors.push(`${name} must be a string`);
          break;
        }
        const minLength = spec.minLength ?? 0;
        const maxLength = spec.maxLength ?? MAX_STRING_LENGTH;
        if (raw.length < minLength) {
          errors.push(`${name} must be at least ${minLength} characters`);
          break;
        }
        if (raw.length > maxLength) {
          errors.push(`${name} exceeds maximum length of ${maxLength}`);
          break;
        }
        if (spec.enumValues !== undefined && !spec.enumValues.includes(raw)) {
          errors.push(`${name} must be one of: ${spec.enumValues.join(', ')}`);
          break;
        }
        value[name] = raw;
        break;
      }
      case 'email': {
        if (typeof raw !== 'string') {
          errors.push(`${name} must be an email string`);
          break;
        }
        const email = raw.trim().toLowerCase();
        if (email.length > 254 || !EMAIL_RE.test(email)) {
          errors.push(`${name} must be a valid email address`);
          break;
        }
        value[name] = email;
        break;
      }
      case 'number': {
        if (typeof raw !== 'number' || !Number.isFinite(raw)) {
          errors.push(`${name} must be a number`);
          break;
        }
        if (spec.integer === true && !Number.isInteger(raw)) {
          errors.push(`${name} must be an integer`);
          break;
        }
        if (spec.min !== undefined && raw < spec.min) {
          errors.push(`${name} must be >= ${spec.min}`);
          break;
        }
        if (spec.max !== undefined && raw > spec.max) {
          errors.push(`${name} must be <= ${spec.max}`);
          break;
        }
        value[name] = raw;
        break;
      }
      case 'boolean': {
        if (typeof raw !== 'boolean') {
          errors.push(`${name} must be a boolean`);
          break;
        }
        value[name] = raw;
        break;
      }
      case 'object': {
        if (!isRecord(raw)) {
          errors.push(`${name} must be an object`);
          break;
        }
        value[name] = raw;
        break;
      }
    }
  }

  return { ok: errors.length === 0, value, errors };
}

// ---------------------------------------------------------------------------
// Remote config — frozen CONFIG BOUNDS (server-side validation + clamping)
// ---------------------------------------------------------------------------

export const CONFIG_BOUNDS: Record<string, { min: number; max: number }> = {
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
  // v2.2 Phase D — monetization + growth pacing.
  trialDays: { min: 3, max: 14 },
  breakPassesPerWeek: { min: 0, max: 5 },
  insightNudgeHour: { min: 17, max: 22 },
};

/** v2.1 Phase C — float-bounded DP multipliers (validated separately). */
export const CONFIG_DOUBLE_BOUNDS: Record<string, { min: number; max: number }> = {
  dpMultiplierFocus: { min: 0.5, max: 3.0 },
  dpMultiplierReels: { min: 0.5, max: 3.0 },
  dpMultiplierNeutral: { min: 0.5, max: 3.0 },
};

/** v2.2 Phase D — bounded string config fields (length-capped, type-checked). */
export const CONFIG_STRING_BOUNDS: Record<string, { min: number; max: number }> = {
  bkashNumber: { min: 0, max: 32 },
  bkashInstructions: { min: 0, max: 2000 },
};

/** Frozen default config (also the v1 seed in the database). */
export const DEFAULT_CONFIG: ConfigDoc = {
  shortsWarningCount: 5,
  cageDurationSeconds: 1800,
  tempUnlockCoins: 5,
  tempUnlockMinutes: 5,
  bailoutCoins: 500,
  defaultStudyMinutes: 60,
  defaultDetoxMinutes: 120,
  minSupportedVersion: '1.0.0',
  maintenanceMode: false,
  gamificationEnabled: true,
  dpMultiplierFocus: 1.0,
  dpMultiplierReels: 1.0,
  dpMultiplierNeutral: 1.0,
  dpDailyCap: 150,
  dpWeeklyCap: 800,
  dpMonthlyCap: 3000,
  // v2.2 Phase D — payments kill-switch defaults ON; the bKash fields are
  // empty until the operator fills them from the admin panel.
  paymentsEnabled: true,
  bkashNumber: '',
  bkashInstructions: '',
  trialDays: 7,
  breakPassesPerWeek: 2,
  insightNudgeEnabled: true,
  insightNudgeHour: 20,
};

const SEMVER_RE = /^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/;

export type ConfigValidationResult =
  | { ok: true; config: ConfigDoc; clamped: string[] }
  | { ok: false; errors: string[] };

/**
 * Validate a config document:
 *  - rejects unknown keys (strict),
 *  - rejects wrong types,
 *  - CLAMPS out-of-range integers to the frozen bounds (recording what moved),
 *  - validates minSupportedVersion as semver and maintenanceMode as boolean.
 */
export function validateConfig(input: unknown): ConfigValidationResult {
  if (!isRecord(input)) {
    return { ok: false, errors: ['config must be a JSON object'] };
  }

  const errors: string[] = [];
  const clamped: string[] = [];

  const allowed = new Set<string>([
    ...Object.keys(CONFIG_BOUNDS),
    ...Object.keys(CONFIG_DOUBLE_BOUNDS),
    ...Object.keys(CONFIG_STRING_BOUNDS),
    'gamificationEnabled',
    'minSupportedVersion',
    'maintenanceMode',
    'paymentsEnabled',
    'insightNudgeEnabled',
  ]);
  for (const key of Object.keys(input)) {
    if (!allowed.has(key)) errors.push(`Unknown config key: ${key}`);
  }

  const clampInt = (key: keyof typeof CONFIG_BOUNDS): number => {
    const bounds = CONFIG_BOUNDS[key];
    const raw = input[key];
    if (typeof raw !== 'number' || !Number.isFinite(raw) || !Number.isInteger(raw)) {
      errors.push(`${key} must be an integer between ${bounds.min} and ${bounds.max}`);
      return bounds.min;
    }
    if (raw < bounds.min) {
      clamped.push(`${key}: ${raw} clamped to ${bounds.min}`);
      return bounds.min;
    }
    if (raw > bounds.max) {
      clamped.push(`${key}: ${raw} clamped to ${bounds.max}`);
      return bounds.max;
    }
    return raw;
  };

  const config: ConfigDoc = {
    shortsWarningCount: clampInt('shortsWarningCount'),
    cageDurationSeconds: clampInt('cageDurationSeconds'),
    tempUnlockCoins: clampInt('tempUnlockCoins'),
    tempUnlockMinutes: clampInt('tempUnlockMinutes'),
    bailoutCoins: clampInt('bailoutCoins'),
    defaultStudyMinutes: clampInt('defaultStudyMinutes'),
    defaultDetoxMinutes: clampInt('defaultDetoxMinutes'),
    minSupportedVersion: DEFAULT_CONFIG.minSupportedVersion,
    maintenanceMode: false,
    gamificationEnabled: DEFAULT_CONFIG.gamificationEnabled,
    dpMultiplierFocus: DEFAULT_CONFIG.dpMultiplierFocus,
    dpMultiplierReels: DEFAULT_CONFIG.dpMultiplierReels,
    dpMultiplierNeutral: DEFAULT_CONFIG.dpMultiplierNeutral,
    dpDailyCap: clampInt('dpDailyCap'),
    dpWeeklyCap: clampInt('dpWeeklyCap'),
    dpMonthlyCap: clampInt('dpMonthlyCap'),
    // v2.2 Phase D defaults; overwritten below when present + valid.
    paymentsEnabled: DEFAULT_CONFIG.paymentsEnabled,
    bkashNumber: DEFAULT_CONFIG.bkashNumber,
    bkashInstructions: DEFAULT_CONFIG.bkashInstructions,
    trialDays: clampInt('trialDays'),
    breakPassesPerWeek: clampInt('breakPassesPerWeek'),
    insightNudgeEnabled: DEFAULT_CONFIG.insightNudgeEnabled,
    insightNudgeHour: clampInt('insightNudgeHour'),
  };

  // v2.1 Phase C — DP multipliers (float clamping).
  const clampDouble = (key: keyof typeof CONFIG_DOUBLE_BOUNDS): number => {
    const bounds = CONFIG_DOUBLE_BOUNDS[key];
    const raw = input[key];
    if (typeof raw !== 'number' || !Number.isFinite(raw)) {
      errors.push(`${key} must be a number between ${bounds.min} and ${bounds.max}`);
      return bounds.min;
    }
    if (raw < bounds.min) {
      clamped.push(`${key}: ${raw} clamped to ${bounds.min}`);
      return bounds.min;
    }
    if (raw > bounds.max) {
      clamped.push(`${key}: ${raw} clamped to ${bounds.max}`);
      return bounds.max;
    }
    return Math.round(raw * 100) / 100;
  };
  config.dpMultiplierFocus = clampDouble('dpMultiplierFocus');
  config.dpMultiplierReels = clampDouble('dpMultiplierReels');
  config.dpMultiplierNeutral = clampDouble('dpMultiplierNeutral');

  const minVersion = input.minSupportedVersion;
  if (typeof minVersion !== 'string' || !SEMVER_RE.test(minVersion)) {
    errors.push('minSupportedVersion must be a semver string (e.g. "1.0.0")');
  } else {
    config.minSupportedVersion = minVersion;
  }

  const maintenance = input.maintenanceMode;
  if (typeof maintenance !== 'boolean') {
    errors.push('maintenanceMode must be a boolean');
  } else {
    config.maintenanceMode = maintenance;
  }

  const gamification = input.gamificationEnabled;
  if (typeof gamification !== 'boolean') {
    errors.push('gamificationEnabled must be a boolean');
  } else {
    config.gamificationEnabled = gamification;
  }

  // v2.2 Phase D — payments + growth booleans.
  const payments = input.paymentsEnabled;
  if (typeof payments !== 'boolean') {
    errors.push('paymentsEnabled must be a boolean');
  } else {
    config.paymentsEnabled = payments;
  }

  const insightNudge = input.insightNudgeEnabled;
  if (typeof insightNudge !== 'boolean') {
    errors.push('insightNudgeEnabled must be a boolean');
  } else {
    config.insightNudgeEnabled = insightNudge;
  }

  // v2.2 Phase D — bounded string fields (absent -> default, present but
  // wrong type -> error, over-length -> clamped).
  for (const [key, bounds] of Object.entries(CONFIG_STRING_BOUNDS)) {
    if (!(key in input)) continue;
    const raw = input[key];
    if (typeof raw !== 'string') {
      errors.push(`${key} must be a string`);
      continue;
    }
    if (raw.length > bounds.max) {
      clamped.push(`${key}: length ${raw.length} clamped to ${bounds.max}`);
      (config as unknown as Record<string, unknown>)[key] = raw.slice(0, bounds.max);
    } else {
      (config as unknown as Record<string, unknown>)[key] = raw;
    }
  }

  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, config, clamped };
}
