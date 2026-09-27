/**
 * MAXLEVEL DETOX — published-config reader for app routes.
 *
 * Reads the newest PUBLISHED config_versions row and normalizes it through
 * the frozen validator, so absent keys fall back to DEFAULT_CONFIG (legacy
 * pre-v2.2 configs keep working) and the result is always a complete,
 * range-checked ConfigDoc.
 */

import { ConfigDoc, Env } from '../types';
import { validateConfig, DEFAULT_CONFIG } from '../middleware/validation';

interface ConfigVersionRow {
  version: number;
  config_json: string;
}

/**
 * Returns the published config with defaults filled in, or null when no
 * published config exists at all.
 */
export async function readPublishedConfig(env: Env): Promise<ConfigDoc | null> {
  const row = await env.DB.prepare(
    `SELECT version, config_json FROM config_versions WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1`
  ).first<ConfigVersionRow>();
  if (row === null) return null;

  try {
    const parsed: unknown = JSON.parse(row.config_json);
    const result = validateConfig(parsed);
    if (result.ok) return result.config;
    // m13/m14: stored configs were validated at save time; if one is somehow
    // stale or corrupt, fail safe to the frozen defaults directly (the old
    // validateConfig({}) dance could never succeed — every integer key is
    // "missing" — so the fallback actually returned null).
    return DEFAULT_CONFIG;
  } catch {
    // Corrupt JSON fails safe to the frozen defaults as well.
    return DEFAULT_CONFIG;
  }
}
