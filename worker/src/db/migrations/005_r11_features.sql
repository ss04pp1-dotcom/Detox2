-- ============================================================================
-- 005_r11_features.sql (v2.5.8 roadmap round) — three product-roadmap
-- features:
--   1. Dynamic remote detection rules (server-pushed reels/shorts
--      signatures — View IDs / text hints / URL shapes updatable without an
--      app update).
--   2. Community leaderboard (opt-in, device-mirrored DP stats).
--   3. Clubs (small invite-code groups with their own leaderboard slice).
--
-- Safe on existing deployments (new tables only, IF NOT EXISTS).
-- ============================================================================

-- (1) Detection rules — same DRAFT/PUBLISHED/ARCHIVED versioning model as
--     config_versions, so the admin panel gets draft -> publish -> rollback
--     semantics and full history for free.
CREATE TABLE IF NOT EXISTS detection_rules (
  version      INTEGER PRIMARY KEY AUTOINCREMENT,
  rules_json   TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'DRAFT'
               CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  created_by   TEXT,
  created_at   TEXT NOT NULL,
  published_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_detection_rules_status ON detection_rules (status, version);

-- (2) Leaderboard profiles — the device ProgressEngine is the DP authority;
--     the server mirrors the snapshot (same philosophy as the coin mirror).
--     Only rows with opted_in = 1 ever appear in a leaderboard response.
--     lifetime_dp is monotonic per user (max(server, device)) so a reinstall
--     cannot wipe a standing; window DP (week/month) follows the device's
--     own anchors and is sanity-capped.
CREATE TABLE IF NOT EXISTS leaderboard_profiles (
  user_id      TEXT PRIMARY KEY,
  lifetime_dp  INTEGER NOT NULL DEFAULT 0 CHECK (lifetime_dp BETWEEN 0 AND 1000000),
  week_dp      INTEGER NOT NULL DEFAULT 0 CHECK (week_dp BETWEEN 0 AND 20000),
  week_anchor  TEXT NOT NULL DEFAULT '',
  month_dp     INTEGER NOT NULL DEFAULT 0 CHECK (month_dp BETWEEN 0 AND 100000),
  month_anchor TEXT NOT NULL DEFAULT '',
  streak_days  INTEGER NOT NULL DEFAULT 0 CHECK (streak_days BETWEEN 0 AND 3650),
  level_name   TEXT,
  opted_in     INTEGER NOT NULL DEFAULT 0,
  display_mode TEXT NOT NULL DEFAULT 'name'
               CHECK (display_mode IN ('name', 'anonymous')),
  updated_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lb_alltime ON leaderboard_profiles (opted_in, lifetime_dp DESC);
CREATE INDEX IF NOT EXISTS idx_lb_weekly ON leaderboard_profiles (opted_in, week_dp DESC);
CREATE INDEX IF NOT EXISTS idx_lb_monthly ON leaderboard_profiles (opted_in, month_dp DESC);

-- (3) Clubs — invite-code groups (one club membership per user, enforced in
--     the route layer). Club leaderboard = leaderboard_profiles of members.
CREATE TABLE IF NOT EXISTS clubs (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  description TEXT,
  invite_code TEXT NOT NULL UNIQUE,
  created_by  TEXT NOT NULL,
  hidden      INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_clubs_code ON clubs (invite_code);

CREATE TABLE IF NOT EXISTS club_members (
  club_id   TEXT NOT NULL,
  user_id   TEXT NOT NULL,
  joined_at TEXT NOT NULL,
  PRIMARY KEY (club_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_club_members_user ON club_members (user_id);

-- Seed: detection rules v1 (PUBLISHED) — the frozen defaults, identical to
-- the compiled-in signatures of ReelsDetector v2.5.8. Published so legacy
-- clients can fetch immediately; the app treats version 0 / missing rows
-- as "use compiled defaults" anyway.
INSERT OR IGNORE INTO detection_rules (version, rules_json, status, created_by, created_at, published_at)
VALUES (
  1,
  '{"schemaVersion":1,"minAppVersion":"1.0.0","platforms":{"youtube":{"enabled":true,"feedViewIds":["reel_watch_fragment_root"],"immersiveViewIds":["pivot_bar"]},"tiktok":{"enabled":true},"facebook":{"enabled":true,"eventTextHints":["Reel details","Reels tab details"],"reelDetailsHints":["Reel details","Reels tab details"],"navHints":["Navigate to your Reels profile"],"reelsHints":["Reels"],"fullscreenHints":["Fullscreen"]},"facebook_lite":{"enabled":true,"feedViewIds":["video_view"],"immersiveGate":true},"instagram":{"enabled":true,"feedViewIds":["root_clips_layout"],"liteFeedViewIds":["clips_viewer_video_container"],"sharedFeedViewIds":["reel_recycler"]}}}',
  'PUBLISHED',
  'seed',
  '2026-09-26T00:00:00.000Z',
  '2026-09-26T00:00:00.000Z'
);
