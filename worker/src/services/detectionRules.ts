/**
 * MAXLEVEL DETOX — dynamic detection-rules model + frozen defaults
 * (v2.5.8 roadmap: Reels/Shorts detection robustness).
 *
 * WHY: reels detection is signature-based (per-app View IDs / content-desc
 * hints / URL shapes). When YouTube, Instagram, TikTok or Facebook ship a UI
 * update, those signatures drift and detection silently breaks until the
 * next app release. This module defines the server-side ruleset that the
 * admin panel can publish so clients pull fresh signatures WITHOUT an app
 * update.
 *
 * SECURITY MODEL (mirrors RuntimeConfig's boundary):
 *   1. DEFAULT_DETECTION_RULES is compiled in and always safe — it is
 *      byte-for-byte the shipped behavior of ReelsDetector v2.5.8.
 *   2. A stored/published ruleset is validated with a STRICT schema:
 *      platform whitelist, string charset/pattern checks, per-list and
 *      total-size caps, unknown keys rejected. Anything invalid fails the
 *      whole document (never a partial apply).
 *   3. The client (Kotlin DetectionRuleStore) merges: remote platform
 *      entries REPLACE the compiled signatures for that platform;
 *      platforms absent from the remote doc keep their compiled defaults;
 *      a platform with enabled:false is skipped entirely.
 */

import { isRecord } from '../middleware/validation';

// ---------------------------------------------------------------------------
// Types (frozen contract v1)
// ---------------------------------------------------------------------------

/** Canonical platform keys — each maps to one DETECTION STRATEGY SHAPE.
 *  `tiktok` covers both TikTok package names (package gate), `instagram`
 *  covers both Instagram package names (shared signature set). */
export type PlatformId =
  | 'youtube'
  | 'tiktok'
  | 'facebook'
  | 'facebook_lite'
  | 'instagram'
  | 'chrome'
  | 'chrome_beta';

export interface PlatformRules {
  enabled: boolean;
  /** Any match -> confirmed FEED surface. */
  feedViewIds?: string[];
  /** Presence distinguishes "immersed" from "pivot visible" (analytics only). */
  immersiveViewIds?: string[];
  /** Event text pre-check (cheap path before any node walk). */
  eventTextHints?: string[];
  /** BFS content-description hint families (facebook confirmation model). */
  reelDetailsHints?: string[];
  navHints?: string[];
  reelsHints?: string[];
  fullscreenHints?: string[];
  /** Half-screen geometry gate (facebook_lite inline-video disambiguation). */
  immersiveGate?: boolean;
  /** Browser URL shapes, lowercased contains-match (chrome). */
  urlShapes?: string[];
  /** Secondary package namespaces (instagram lite container id). */
  liteFeedViewIds?: string[];
  /** Package-agnostic ids matched without the package prefix (reel_recycler). */
  sharedFeedViewIds?: string[];
  /** v2.9 r16: activity-class-name hints (lowercased contains-match on the
   *  WINDOW_STATE_CHANGED className, e.g. `shortsactivity`) — the most
   *  drift-resistant shorts signal (activity names survive UI redesigns). */
  activityHints?: string[];
}

export interface DetectionRulesDoc {
  schemaVersion: number;
  minAppVersion: string;
  platforms: Partial<Record<PlatformId, PlatformRules>>;
}

export type DetectionRulesValidationResult =
  | { ok: true; rules: DetectionRulesDoc; clamped: string[] }
  | { ok: false; errors: string[] };

// ---------------------------------------------------------------------------
// Frozen limits (strict — a ruleset is a security-adjacent surface)
// ---------------------------------------------------------------------------

export const DETECTION_RULES_LIMITS = {
  maxPlatforms: 7,
  /** Per signature list. */
  maxListEntries: 25,
  /** Single signature entry. */
  minEntryLength: 3,
  maxEntryLength: 64,
  /** Whole serialized doc (defensive; ~10x the default size). */
  maxDocBytes: 20_000,
} as const;

export const DETECTION_PLATFORMS: readonly PlatformId[] = [
  'youtube',
  'tiktok',
  'facebook',
  'facebook_lite',
  'instagram',
  'chrome',
  'chrome_beta',
];

/** View ids: android resource-name fragment — short (`reel_recycler`),
 *  pkg-qualified (`com.google.android.youtube:id/reel_watch_fragment_root`). */
const VIEW_ID_RE = /^[A-Za-z][A-Za-z0-9_./:]{2,63}$/;
/** Text hints: printable ASCII (content descriptions the apps ship). */
const TEXT_HINT_RE = /^[\x20-\x7E]+$/;
/** URL shapes: lowercase URL fragment. */
const URL_SHAPE_RE = /^[a-z0-9./:_-]+$/;
const SEMVER_RE = /^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/;

// ---------------------------------------------------------------------------
// Frozen defaults — identical to ReelsDetector's compiled signatures
// ---------------------------------------------------------------------------

const SHORTS_URL_SHAPES: readonly string[] = [
  'youtube.com/shorts',
  'm.youtube.com/shorts',
  'facebook.com/reel/',
  'facebook.com/reels/',
  'instagram.com/reel/',
  'instagram.com/reels/',
];

export const DEFAULT_DETECTION_RULES: DetectionRulesDoc = {
  schemaVersion: 1,
  minAppVersion: '1.0.0',
  platforms: {
    youtube: {
      enabled: true,
      feedViewIds: ['reel_watch_fragment_root'],
      immersiveViewIds: ['pivot_bar'],
      activityHints: ['shortsactivity'],
    },
    // Package gate — the whole app IS the feed; applies to both the global
    // and the regional TikTok package.
    tiktok: { enabled: true },
    facebook: {
      enabled: true,
      eventTextHints: ['Reel details', 'Reels tab details'],
      reelDetailsHints: ['Reel details', 'Reels tab details'],
      navHints: ['Navigate to your Reels profile'],
      reelsHints: ['Reels'],
      fullscreenHints: ['Fullscreen'],
      activityHints: ['reelsactivity', 'reelactivity'],
    },
    facebook_lite: {
      enabled: true,
      feedViewIds: ['video_view'],
      immersiveGate: true,
      activityHints: ['reelsactivity', 'reelactivity'],
    },
    // One signature set for BOTH Instagram packages (full + lite), exactly
    // like the compiled detector's shared strategy.
    instagram: {
      enabled: true,
      feedViewIds: ['root_clips_layout'],
      liteFeedViewIds: ['clips_viewer_video_container'],
      sharedFeedViewIds: ['reel_recycler'],
      activityHints: ['clipsactivity'],
    },
    chrome: { enabled: true, urlShapes: [...SHORTS_URL_SHAPES] },
    chrome_beta: { enabled: true, urlShapes: [...SHORTS_URL_SHAPES] },
  },
};

// ---------------------------------------------------------------------------
// Validator
// ---------------------------------------------------------------------------

interface ListSpec {
  key: keyof PlatformRules;
  re: RegExp;
  /** Lowercase-normalize entries before storing. */
  lowercase?: boolean;
}

const VIEW_ID_LISTS: readonly ListSpec[] = [
  { key: 'feedViewIds', re: VIEW_ID_RE },
  { key: 'immersiveViewIds', re: VIEW_ID_RE },
  { key: 'liteFeedViewIds', re: VIEW_ID_RE },
  { key: 'sharedFeedViewIds', re: VIEW_ID_RE },
];

const TEXT_LISTS: readonly ListSpec[] = [
  { key: 'eventTextHints', re: TEXT_HINT_RE },
  { key: 'reelDetailsHints', re: TEXT_HINT_RE },
  { key: 'navHints', re: TEXT_HINT_RE },
  { key: 'reelsHints', re: TEXT_HINT_RE },
  { key: 'fullscreenHints', re: TEXT_HINT_RE },
  { key: 'activityHints', re: TEXT_HINT_RE, lowercase: true },
];

const URL_LISTS: readonly ListSpec[] = [
  { key: 'urlShapes', re: URL_SHAPE_RE, lowercase: true },
];

function validateList(
  platform: string,
  rawList: unknown,
  spec: ListSpec,
  errors: string[],
  clamped: string[],
): string[] | undefined {
  if (!Array.isArray(rawList)) {
    errors.push(`${platform}.${String(spec.key)} must be an array of strings`);
    return undefined;
  }
  if (rawList.length > DETECTION_RULES_LIMITS.maxListEntries) {
    errors.push(
      `${platform}.${String(spec.key)} exceeds ${DETECTION_RULES_LIMITS.maxListEntries} entries`
    );
    return undefined;
  }
  const out: string[] = [];
  const seen = new Set<string>();
  for (const entry of rawList) {
    if (typeof entry !== 'string') {
      errors.push(`${platform}.${String(spec.key)} entries must be strings`);
      continue;
    }
    let value = entry.trim();
    if (spec.lowercase === true) value = value.toLowerCase();
    if (
      value.length < DETECTION_RULES_LIMITS.minEntryLength ||
      value.length > DETECTION_RULES_LIMITS.maxEntryLength
    ) {
      errors.push(
        `${platform}.${String(spec.key)} entry "${entry}" must be ${DETECTION_RULES_LIMITS.minEntryLength}-${DETECTION_RULES_LIMITS.maxEntryLength} chars`
      );
      continue;
    }
    if (!spec.re.test(value)) {
      errors.push(`${platform}.${String(spec.key)} entry "${entry}" has invalid characters`);
      continue;
    }
    if (!seen.has(value)) {
      seen.add(value);
      out.push(value);
    } else {
      clamped.push(`${platform}.${String(spec.key)}: duplicate "${value}" dropped`);
    }
  }
  return out;
}

/**
 * Strict validation of a detection-rules document. Unknown platforms and
 * unknown per-platform keys are REJECTED (the doc is a complete replacement,
 * not a patch). Returns a normalized copy.
 */
export function validateDetectionRules(input: unknown): DetectionRulesValidationResult {
  if (!isRecord(input)) {
    return { ok: false, errors: ['rules must be a JSON object'] };
  }
  const errors: string[] = [];
  const clamped: string[] = [];

  const allowedPlatformKeys = new Set<string>(DETECTION_PLATFORMS);
  const platformsInput = input.platforms;
  if (!isRecord(platformsInput)) {
    return { ok: false, errors: ['rules.platforms must be an object'] };
  }

  const schemaVersion = input.schemaVersion;
  if (schemaVersion !== 1) {
    return { ok: false, errors: ['rules.schemaVersion must be 1'] };
  }

  const minAppVersion = input.minAppVersion;
  if (typeof minAppVersion !== 'string' || !SEMVER_RE.test(minAppVersion)) {
    errors.push('rules.minAppVersion must be a semver string (e.g. "1.0.0")');
  }

  if (Object.keys(platformsInput).length > DETECTION_RULES_LIMITS.maxPlatforms) {
    errors.push(`rules.platforms exceeds ${DETECTION_RULES_LIMITS.maxPlatforms} platforms`);
  }

  const allowedRuleKeys = new Set<string>([
    'enabled',
    ...VIEW_ID_LISTS.map((s) => String(s.key)),
    ...TEXT_LISTS.map((s) => String(s.key)),
    ...URL_LISTS.map((s) => String(s.key)),
    'immersiveGate',
  ]);

  const platforms: Partial<Record<PlatformId, PlatformRules>> = {};

  for (const [platformKey, rawRules] of Object.entries(platformsInput)) {
    if (!allowedPlatformKeys.has(platformKey)) {
      errors.push(`Unknown platform: ${platformKey}`);
      continue;
    }
    if (!isRecord(rawRules)) {
      errors.push(`platforms.${platformKey} must be an object`);
      continue;
    }
    for (const ruleKey of Object.keys(rawRules)) {
      if (!allowedRuleKeys.has(ruleKey)) {
        errors.push(`platforms.${platformKey}.${ruleKey} is not a known rule key`);
      }
    }

    const enabled = rawRules.enabled;
    if (typeof enabled !== 'boolean') {
      errors.push(`platforms.${platformKey}.enabled must be a boolean`);
      continue;
    }

    const rules: PlatformRules = { enabled };
    // Package-gate platforms (the whole app is the feed) need no lists;
    // every other platform must carry at least one signature list.
    let anySignature = platformKey === 'tiktok';

    for (const spec of [...VIEW_ID_LISTS, ...TEXT_LISTS, ...URL_LISTS]) {
      const key = String(spec.key);
      if (!(key in rawRules)) continue;
      const list = validateList(platformKey, rawRules[key], spec, errors, clamped);
      if (list === undefined) continue;
      if (list.length > 0) {
        (rules as unknown as Record<string, unknown>)[key] = list;
        anySignature = true;
      }
    }

    if ('immersiveGate' in rawRules) {
      const gate = rawRules.immersiveGate;
      if (typeof gate !== 'boolean') {
        errors.push(`platforms.${platformKey}.immersiveGate must be a boolean`);
      } else {
        rules.immersiveGate = gate;
      }
    }

    if (!anySignature) {
      errors.push(
        `platforms.${platformKey} has no signatures (enable one of viewIds/textHints/urlShapes, or use a package-gate platform)`
      );
      continue;
    }
    platforms[platformKey as PlatformId] = rules;
  }

  if (errors.length > 0) return { ok: false, errors };

  const doc: DetectionRulesDoc = {
    schemaVersion: 1,
    minAppVersion:
      typeof minAppVersion === 'string' && SEMVER_RE.test(minAppVersion)
        ? minAppVersion
        : DEFAULT_DETECTION_RULES.minAppVersion,
    platforms,
  };

  const size = JSON.stringify(doc).length;
  if (size > DETECTION_RULES_LIMITS.maxDocBytes) {
    return { ok: false, errors: [`rules document exceeds ${DETECTION_RULES_LIMITS.maxDocBytes} bytes`] };
  }

  return { ok: true, rules: doc, clamped };
}
