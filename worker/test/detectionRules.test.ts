/**
 * Detection-rules tests (v2.5.8 roadmap) — the validator is the security
 * boundary for server-pushed reels/shorts signatures, so it gets its own
 * regression net:
 *   - the frozen DEFAULT doc round-trips validation unchanged,
 *   - unknown platforms / rule keys are rejected (complete-replacement, not patch),
 *   - signature charset + per-list + doc-size caps hold,
 *   - duplicates are dropped and recorded,
 *   - package-gate platforms need no signature lists, signature platforms do.
 */
import { describe, expect, it } from 'vitest';

import {
  DEFAULT_DETECTION_RULES,
  DETECTION_RULES_LIMITS,
  validateDetectionRules,
} from '../src/services/detectionRules';

describe('validateDetectionRules (dynamic remote rule config)', () => {
  it('the frozen DEFAULT doc validates cleanly and unchanged', () => {
    const result = validateDetectionRules(DEFAULT_DETECTION_RULES);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.rules).toEqual(DEFAULT_DETECTION_RULES);
      expect(result.clamped).toEqual([]);
    }
  });

  it('rejects non-object and wrong schemaVersion', () => {
    expect(validateDetectionRules(null).ok).toBe(false);
    expect(validateDetectionRules('nope').ok).toBe(false);
    expect(validateDetectionRules({ schemaVersion: 2, platforms: {} }).ok).toBe(false);
    expect(validateDetectionRules({ platforms: {} }).ok).toBe(false);
  });

  it('rejects unknown platforms (complete replacement, not a patch)', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: {
        youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root'] },
        snapchat: { enabled: true, feedViewIds: ['some_id'] },
      },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('snapchat');
    }
  });

  it('rejects unknown per-platform rule keys', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: {
        youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root'], xpath: '//*' },
      },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('xpath');
    }
  });

  it('enforces view-id charset (android resource fragments only)', () => {
    const bad = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true, feedViewIds: ['reel root!'] } },
    });
    expect(bad.ok).toBe(false);

    // pkg-qualified form is allowed
    const good = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: {
        youtube: { enabled: true, feedViewIds: ['com.google.android.youtube:id/reel_watch_fragment_root'] },
      },
    });
    expect(good.ok).toBe(true);
  });

  it('lowercases legacy urlShapes entries and enforces their charset', () => {
    // v2.9.4 r20: the URL strategy is gone from the app; urlShapes remains
    // an ACCEPTED legacy key on the remaining platforms so old stored docs
    // still validate — but it no longer counts as a signature.
    const good = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root'], urlShapes: ['YouTube.COM/Shorts'] } },
    });
    expect(good.ok).toBe(true);
    if (good.ok) {
      expect(good.rules.platforms.youtube?.urlShapes).toEqual(['youtube.com/shorts']);
    }

    const bad = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root'], urlShapes: ['youtube.com/shorts?q=*'] } },
    });
    expect(bad.ok).toBe(false);
  });

  it('urlShapes alone is NOT a signature any more (r20, mirrors the app)', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true, urlShapes: ['youtube.com/shorts'] } },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('no signatures');
    }
  });

  it('rejects the removed chrome/chrome_beta platforms (r20)', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { chrome: { enabled: true, urlShapes: ['youtube.com/shorts'] } },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('Unknown platform: chrome');
    }
  });

  it('drops duplicate entries and records them as clamped', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: {
        youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root', 'reel_watch_fragment_root'] },
      },
    });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.rules.platforms.youtube?.feedViewIds).toEqual(['reel_watch_fragment_root']);
      expect(result.clamped.join(' ')).toContain('duplicate');
    }
  });

  it('caps per-list entries', () => {
    const many: string[] = [];
    for (let i = 0; i < DETECTION_RULES_LIMITS.maxListEntries + 1; i++) {
      many.push(`view_id_${i}`);
    }
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true, feedViewIds: many } },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('exceeds');
    }
  });

  it('package-gate platforms (tiktok) validate without signature lists', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { tiktok: { enabled: true } },
    });
    expect(result.ok).toBe(true);
  });

  it('signature platforms with no signatures are rejected', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { youtube: { enabled: true } },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('no signatures');
    }
  });

  it('rejects a bad minAppVersion (semver required)', () => {
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: 'latest',
      platforms: { tiktok: { enabled: true } },
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('minAppVersion');
    }
  });

  it('immersiveGate must be boolean when present', () => {
    const bad = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { facebook_lite: { enabled: true, feedViewIds: ['video_view'], immersiveGate: 'yes' } },
    });
    expect(bad.ok).toBe(false);

    const good = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms: { facebook_lite: { enabled: true, feedViewIds: ['video_view'], immersiveGate: false } },
    });
    expect(good.ok).toBe(true);
  });

  it('rejects oversized documents', () => {
    // 64-char entries (the per-entry max) x 25 entries (the per-list max)
    // across enough platforms to cross the 20 KB doc cap while every
    // individual rule stays valid.
    const long64 = 'a'.repeat(64);
    const shapes: string[] = [];
    for (let i = 0; i < DETECTION_RULES_LIMITS.maxListEntries; i++) {
      shapes.push(`${long64.slice(0, 61)}${String(i).padStart(3, '0')}`);
    }
    const viewIds = shapes.map((s) => s.replace(/[^A-Za-z0-9_]/g, '_'));
    const platforms: Record<string, unknown> = {
      facebook: {
        enabled: true,
        eventTextHints: shapes.map((s) => s.toUpperCase()),
        reelDetailsHints: shapes.map((s) => s.toUpperCase()),
        navHints: shapes.map((s) => s.toUpperCase()),
        reelsHints: shapes.map((s) => s.toUpperCase()),
        fullscreenHints: shapes.map((s) => s.toUpperCase()),
        activityHints: shapes,
      },
      youtube: {
        enabled: true,
        feedViewIds: viewIds,
        immersiveViewIds: viewIds,
      },
      instagram: {
        enabled: true,
        feedViewIds: viewIds,
        liteFeedViewIds: viewIds,
        sharedFeedViewIds: viewIds,
      },
      facebook_lite: { enabled: true, feedViewIds: viewIds },
    };
    const result = validateDetectionRules({
      schemaVersion: 1,
      minAppVersion: '1.0.0',
      platforms,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.join(' ')).toContain('exceeds');
    }
  });
});
