-- ============================================================================
-- MAXLEVEL DETOX — Cloudflare D1 schema (frozen API contract v1)
-- ============================================================================
-- Conventions:
--   * All timestamps are ISO-8601 UTC strings.
--   * audit_logs and security_events are APPEND-ONLY (triggers abort UPDATE/DELETE).
--   * coin_transactions is append-only by convention + trigger (immutable ledger).
--   * All IDs are prefixed: usr_, dev_, ctx_, ann_, tkt_, aud_, sec_, apv_, adm_.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- users
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id                  TEXT PRIMARY KEY,
  email               TEXT NOT NULL UNIQUE,
  display_name        TEXT,
  status              TEXT NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED')),
  google_sub          TEXT,
  created_at          TEXT NOT NULL,
  updated_at          TEXT NOT NULL,
  last_seen_at        TEXT,
  deletion_pending_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users (last_seen_at);
CREATE INDEX IF NOT EXISTS idx_users_created   ON users (created_at);

-- ----------------------------------------------------------------------------
-- user_sessions — access + refresh tokens, stored as SHA-256 hex
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_sessions (
  token_hash         TEXT PRIMARY KEY,
  user_id            TEXT NOT NULL REFERENCES users (id),
  refresh_token_hash TEXT NOT NULL UNIQUE,
  access_expires_at  TEXT NOT NULL,
  expires_at         TEXT NOT NULL,
  created_at         TEXT NOT NULL,
  device_id          TEXT
);
CREATE INDEX IF NOT EXISTS idx_sessions_user   ON user_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON user_sessions (expires_at);

-- ----------------------------------------------------------------------------
-- devices — max 5 per user (enforced in the route layer)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
  id                  TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL REFERENCES users (id),
  platform            TEXT NOT NULL DEFAULT 'android',
  manufacturer        TEXT,
  model               TEXT,
  android_version     TEXT,
  app_version         TEXT,
  registered_at       TEXT NOT NULL,
  last_seen_at        TEXT,
  status              TEXT NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'REMOVED')),
  permission_summary  TEXT,
  active_session_flag INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_user      ON devices (user_id);
CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices (last_seen_at);

-- ----------------------------------------------------------------------------
-- subscriptions — Google Play verified entitlements
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscriptions (
  id             TEXT PRIMARY KEY,
  user_id        TEXT NOT NULL REFERENCES users (id),
  product_id     TEXT NOT NULL,
  purchase_token TEXT NOT NULL UNIQUE,
  plan           TEXT NOT NULL,
  status         TEXT NOT NULL
                 CHECK (status IN ('ACTIVE', 'CANCELED', 'EXPIRED', 'PENDING')),
  start_date     TEXT,
  expiry_date    TEXT,
  last_verified  TEXT,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_user   ON subscriptions (user_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions (status);

-- ----------------------------------------------------------------------------
-- coin_transactions — IMMUTABLE server-side ledger.
-- balance = SUM(amount). balance_after is denormalized for fast audit display.
-- Types: AD_REWARD, TEMP_UNLOCK_SPEND, BAILOUT_SPEND, ADMIN_ADJUSTMENT,
--        REFUND, BONUS, EXPIRATION.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coin_transactions (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users (id),
  transaction_id  TEXT NOT NULL UNIQUE,
  type            TEXT NOT NULL
                  CHECK (type IN ('AD_REWARD', 'TEMP_UNLOCK_SPEND', 'BAILOUT_SPEND',
                                  'ADMIN_ADJUSTMENT', 'REFUND', 'BONUS', 'EXPIRATION')),
  amount          INTEGER NOT NULL CHECK (amount != 0),
  balance_after   INTEGER NOT NULL CHECK (balance_after >= 0),
  source          TEXT,
  reference       TEXT,
  created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_coins_user_created ON coin_transactions (user_id, created_at);

-- Ledger rows are never mutated or deleted.
CREATE TRIGGER IF NOT EXISTS trg_coins_no_update
  BEFORE UPDATE ON coin_transactions
BEGIN
  SELECT RAISE(ABORT, 'coin_transactions is append-only');
END;
CREATE TRIGGER IF NOT EXISTS trg_coins_no_delete
  BEFORE DELETE ON coin_transactions
BEGIN
  SELECT RAISE(ABORT, 'coin_transactions is append-only');
END;

-- ----------------------------------------------------------------------------
-- config_versions — DRAFT / PUBLISHED / ARCHIVED, full history retained
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS config_versions (
  version       INTEGER PRIMARY KEY AUTOINCREMENT,
  config_json   TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  created_by    TEXT,
  created_at    TEXT NOT NULL,
  published_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_config_status ON config_versions (status, version);

-- ----------------------------------------------------------------------------
-- feature_flags
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feature_flags (
  key                TEXT PRIMARY KEY,
  enabled            INTEGER NOT NULL DEFAULT 0,
  rollout_percentage INTEGER NOT NULL DEFAULT 100
                     CHECK (rollout_percentage IN (0, 1, 5, 10, 25, 50, 100)),
  minimum_version    TEXT,
  environment        TEXT NOT NULL DEFAULT 'production'
                     CHECK (environment IN ('development', 'staging', 'production')),
  updated_by         TEXT,
  updated_at         TEXT
);

-- ----------------------------------------------------------------------------
-- announcements
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS announcements (
  id          TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  body        TEXT NOT NULL,
  type        TEXT NOT NULL
              CHECK (type IN ('INFO', 'UPDATE', 'WARNING', 'MAINTENANCE', 'PROMOTION')),
  status      TEXT NOT NULL DEFAULT 'DRAFT'
              CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
  target_rule TEXT NOT NULL DEFAULT 'all',
  start_at    TEXT,
  end_at      TEXT,
  created_by  TEXT,
  created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_announcements_active ON announcements (status, start_at, end_at);

-- ----------------------------------------------------------------------------
-- events — idempotent product/enforcement analytics events
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS events (
  event_id     TEXT PRIMARY KEY,
  device_id    TEXT,
  user_id      TEXT,
  type         TEXT NOT NULL,
  payload      TEXT NOT NULL DEFAULT '{}',
  occurred_at  TEXT NOT NULL,
  received_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_received ON events (received_at);
CREATE INDEX IF NOT EXISTS idx_events_type     ON events (type, received_at);
CREATE INDEX IF NOT EXISTS idx_events_dau      ON events (received_at, device_id);

-- ----------------------------------------------------------------------------
-- support_tickets
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS support_tickets (
  id           TEXT PRIMARY KEY,
  user_id      TEXT REFERENCES users (id),
  category     TEXT,
  description  TEXT NOT NULL,
  app_version  TEXT,
  request_id   TEXT,
  status       TEXT NOT NULL DEFAULT 'OPEN'
               CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED')),
  priority     TEXT NOT NULL DEFAULT 'MEDIUM'
               CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
  assigned_to  TEXT,
  response     TEXT,
  responded_at TEXT,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tickets_status ON support_tickets (status, created_at);
CREATE INDEX IF NOT EXISTS idx_tickets_user   ON support_tickets (user_id);

-- ----------------------------------------------------------------------------
-- audit_logs — APPEND-ONLY administrative audit trail
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_logs (
  id            TEXT PRIMARY KEY,
  admin_id      TEXT NOT NULL,
  action        TEXT NOT NULL,
  resource_type TEXT,
  resource_id   TEXT,
  result        TEXT,
  request_id    TEXT,
  created_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_admin   ON audit_logs (admin_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_action  ON audit_logs (action, created_at);

CREATE TRIGGER IF NOT EXISTS trg_audit_no_update
  BEFORE UPDATE ON audit_logs
BEGIN
  SELECT RAISE(ABORT, 'audit_logs is append-only');
END;
CREATE TRIGGER IF NOT EXISTS trg_audit_no_delete
  BEFORE DELETE ON audit_logs
BEGIN
  SELECT RAISE(ABORT, 'audit_logs is append-only');
END;

-- ----------------------------------------------------------------------------
-- security_events — APPEND-ONLY
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS security_events (
  id         TEXT PRIMARY KEY,
  user_id    TEXT,
  device_id  TEXT,
  event_type TEXT NOT NULL,
  severity   TEXT NOT NULL
             CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  metadata   TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sec_created  ON security_events (created_at);
CREATE INDEX IF NOT EXISTS idx_sec_severity ON security_events (severity, created_at);
CREATE INDEX IF NOT EXISTS idx_sec_user     ON security_events (user_id, created_at);

CREATE TRIGGER IF NOT EXISTS trg_sec_no_update
  BEFORE UPDATE ON security_events
BEGIN
  SELECT RAISE(ABORT, 'security_events is append-only');
END;
CREATE TRIGGER IF NOT EXISTS trg_sec_no_delete
  BEFORE DELETE ON security_events
BEGIN
  SELECT RAISE(ABORT, 'security_events is append-only');
END;

-- ----------------------------------------------------------------------------
-- app_versions
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_versions (
  id           TEXT PRIMARY KEY,
  minimum      TEXT NOT NULL,
  latest       TEXT NOT NULL,
  force_update INTEGER NOT NULL DEFAULT 0,
  message      TEXT,
  created_by   TEXT,
  created_at   TEXT NOT NULL
);

-- ----------------------------------------------------------------------------
-- admin_users — panel accounts (separate from app users)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_users (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL
                CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'SUPPORT', 'ANALYST',
                                'CONFIG_MANAGER', 'READ_ONLY')),
  status        TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED')),
  created_at    TEXT NOT NULL,
  last_login_at TEXT,
  created_by    TEXT
);

-- ============================================================================
-- SEED DATA
-- ============================================================================

-- Seed SUPER_ADMIN. Password: ChangeMe_2026!
-- (PBKDF2-SHA256, 100000 iterations, 16-byte salt, 32-byte key,
--  stored as "<saltHex>$<hashHex>" — regenerate with the node one-liner
--  in worker/README.md and UPDATE this row before first production login.)
INSERT OR IGNORE INTO admin_users (id, email, password_hash, role, status, created_at, created_by)
VALUES (
  'adm_seed_superadmin',
  'admin@maxleveldetox.com',
  '18ece0dd4a5a88284c68f436bf534c2e$050fc7b90cb6ba4100fa645a7fc61d8213f9d85161fe194d56aee982ff8d6cdf',
  'SUPER_ADMIN',
  'ACTIVE',
  '2026-09-16T00:00:00.000Z',
  'seed'
);

-- Seed config v1 (published) — frozen defaults.
INSERT OR IGNORE INTO config_versions (version, config_json, status, created_by, created_at, published_at)
VALUES (
  1,
  '{"shortsWarningCount":5,"cageDurationSeconds":1800,"tempUnlockCoins":5,"tempUnlockMinutes":5,"bailoutCoins":500,"defaultStudyMinutes":60,"defaultDetoxMinutes":120,"minSupportedVersion":"1.0.0","maintenanceMode":false,"gamificationEnabled":true,"dpMultiplierFocus":1.0,"dpMultiplierReels":1.0,"dpMultiplierNeutral":1.0,"dpDailyCap":150,"dpWeeklyCap":800,"dpMonthlyCap":3000}',
  'PUBLISHED',
  'seed',
  '2026-09-16T00:00:00.000Z',
  '2026-09-16T00:00:00.000Z'
);

-- Seed feature flags — frozen defaults.
INSERT OR IGNORE INTO feature_flags (key, enabled, rollout_percentage, minimum_version, environment, updated_by, updated_at) VALUES
  ('SHOCKWAVE_ALARM',   1, 100, '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('SHORTS_BLOCKER',    1, 100, '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('CAGE',              1, 100, '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('TEMP_UNLOCK',       1, 100, '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('GOOGLE_DRIVE_BACKUP', 1, 100, '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('PREMIUM',           0, 0,   '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('NEW_ONBOARDING',    0, 0,   '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z'),
  ('BETA_FEATURES',     0, 5,   '1.0.0', 'production', 'seed', '2026-09-16T00:00:00.000Z');

-- Seed app version policy.
INSERT OR IGNORE INTO app_versions (id, minimum, latest, force_update, message, created_by, created_at)
VALUES ('apv_seed_1', '1.0.0', '1.0.0', 0, NULL, 'seed', '2026-09-16T00:00:00.000Z');
-- ============================================================================
-- MAXLEVEL DETOX — migration 002 (v2.2 Phase D: growth & monetization)
-- ============================================================================
-- Adds:
--   * subscription_plans — server-driven plan catalog (Play SKUs + bKash)
--   * trial_claims       — device-bound free-trial ledger (one per device)
--   * bkash_payments     — manual bKash payment lifecycle (EPS-style, with
--                          human verification in the admin queue)
-- All timestamps are ISO-8601 UTC strings. IDs are prefixed
-- pln_ / trl_ / pay_ per the schema conventions.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- subscription_plans — the plan catalog served by GET /plans
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscription_plans (
  id            TEXT PRIMARY KEY,
  product_id    TEXT NOT NULL UNIQUE,
  plan          TEXT NOT NULL
                CHECK (plan IN ('monthly', 'quarterly', 'semiannual', 'yearly', 'trial')),
  source        TEXT NOT NULL DEFAULT 'play'
                CHECK (source IN ('play', 'bkash')),
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 400),
  price_minor   INTEGER NOT NULL CHECK (price_minor >= 0),
  currency      TEXT NOT NULL DEFAULT 'BDT',
  display_name  TEXT NOT NULL,
  description   TEXT,
  is_popular    INTEGER NOT NULL DEFAULT 0,
  is_active     INTEGER NOT NULL DEFAULT 1,
  sort_order    INTEGER NOT NULL DEFAULT 100,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plans_active ON subscription_plans (is_active, sort_order);

-- ----------------------------------------------------------------------------
-- trial_claims — one free trial per device (device_id UNIQUE)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trial_claims (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users (id),
  device_id  TEXT NOT NULL UNIQUE,
  claimed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trials_user ON trial_claims (user_id);

-- ----------------------------------------------------------------------------
-- bkash_payments — manual bKash flow:
--   init (PENDING) -> user sends money -> submit TRX (IN_REVIEW)
--   -> admin verifies (VERIFIED, grants subscription days) or rejects
--   (REJECTED, with reason). Canceled/EXPIRED are terminal too.
-- `reference` is the short code the user copies into the bKash transfer
-- memo; it is unique so a payment can never be double-claimed.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bkash_payments (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users (id),
  device_id       TEXT,
  plan_id         TEXT NOT NULL REFERENCES subscription_plans (id),
  reference       TEXT NOT NULL UNIQUE,
  amount_minor    INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency        TEXT NOT NULL DEFAULT 'BDT',
  trx_id          TEXT,
  sender_number   TEXT,
  status          TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'IN_REVIEW', 'VERIFIED',
                                    'REJECTED', 'EXPIRED', 'CANCELED')),
  submitted_at    TEXT,
  reviewed_by     TEXT,
  reviewed_at     TEXT,
  reject_reason   TEXT,
  subscription_id TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bkash_user     ON bkash_payments (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_bkash_status   ON bkash_payments (status, created_at);
CREATE INDEX IF NOT EXISTS idx_bkash_review   ON bkash_payments (status, submitted_at);

-- ----------------------------------------------------------------------------
-- Seed plan catalog — representative BDT pricing (admin-editable).
-- Play SKUs follow the competitor's 4-tier shape; bKash tiers mirror them for
-- the manual gateway (Bangladesh market, port-plan item 17).
-- ----------------------------------------------------------------------------
INSERT OR IGNORE INTO subscription_plans
  (id, product_id, plan, source, duration_days, price_minor, currency,
   display_name, description, is_popular, is_active, sort_order, created_at, updated_at)
VALUES
  ('pln_seed_play_1m',  'maxlevel_monthly',  'monthly',    'play',  30,  29900, 'BDT',
   'Monthly PRO',   'All PRO perks, billed monthly through Google Play.',          0, 1, 10,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_play_3m',  'maxlevel_3monthly', 'quarterly',  'play',  90,  69900, 'BDT',
   '3-Month PRO',  'Three months of PRO at a 22% saving vs monthly.',             0, 1, 20,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_play_6m',  'maxlevel_6monthly', 'semiannual', 'play', 180, 119900, 'BDT',
   '6-Month PRO',  'Half a year of PRO at a 33% saving vs monthly.',              1, 1, 30,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_play_1y',  'maxlevel_yearly',   'yearly',     'play', 365, 199900, 'BDT',
   'Yearly PRO',   'A full year of PRO — the best value tier.',                   0, 1, 40,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_bkash_1m', 'bkash_monthly_299', 'monthly',    'bkash',  30,  29900, 'BDT',
   'Monthly PRO (bKash)',  'Pay with bKash and get verified within a day.',      0, 1, 50,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_bkash_3m', 'bkash_3month_699',  'quarterly',  'bkash',  90,  69900, 'BDT',
   '3-Month PRO (bKash)',  'Pay with bKash and get verified within a day.',      0, 1, 60,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_bkash_6m', 'bkash_6month_1199', 'semiannual', 'bkash', 180, 119900, 'BDT',
   '6-Month PRO (bKash)',  'Pay with bKash and get verified within a day.',      0, 1, 70,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z'),
  ('pln_seed_bkash_1y', 'bkash_yearly_1999', 'yearly',     'bkash', 365, 199900, 'BDT',
   'Yearly PRO (bKash)',   'Pay with bKash and get verified within a day.',      0, 1, 80,
   '2026-09-17T00:00:00.000Z', '2026-09-17T00:00:00.000Z');
-- ---------------------------------------------------------------------------
-- 003_r9_engagement.sql (v2.5 r9) — Social Sentry parity engagement tables:
-- community commits + cheers, friends, referrals + credits.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS community_commits (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL,
  display_name  TEXT,
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 90),
  reason        TEXT NOT NULL,
  is_anonymous  INTEGER NOT NULL DEFAULT 0,
  cheers_count  INTEGER NOT NULL DEFAULT 0,
  start_time    TEXT NOT NULL,
  end_time      TEXT NOT NULL,
  created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_commits_active ON community_commits (end_time);
CREATE INDEX IF NOT EXISTS idx_commits_user   ON community_commits (user_id);

CREATE TABLE IF NOT EXISTS commit_cheers (
  user_id    TEXT NOT NULL,
  commit_id  TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (user_id, commit_id)
);

CREATE TABLE IF NOT EXISTS friends (
  user_id      TEXT NOT NULL,
  friend_id    TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'pending'
               CHECK (status IN ('pending', 'accepted', 'rejected')),
  requested_at TEXT NOT NULL DEFAULT (datetime('now')),
  accepted_at  TEXT,
  PRIMARY KEY (user_id, friend_id)
);
CREATE INDEX IF NOT EXISTS idx_friends_friend ON friends (friend_id, status);

CREATE TABLE IF NOT EXISTS referrals (
  id          TEXT PRIMARY KEY,
  referrer_id TEXT NOT NULL,
  referred_id TEXT NOT NULL UNIQUE,
  status      TEXT NOT NULL DEFAULT 'pending'
              CHECK (status IN ('pending', 'qualified', 'claimed', 'rejected')),
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  claimed_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_referrals_referrer ON referrals (referrer_id, status);

CREATE TABLE IF NOT EXISTS referral_credits (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  kind       TEXT NOT NULL DEFAULT 'referral',
  pro_days   INTEGER NOT NULL DEFAULT 1,
  source     TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_referral_credits_user ON referral_credits (user_id);
-- ============================================================================
-- 004_r10_fixes.sql (v2.5.7 audit-fix round) — schema changes for the
-- post-audit fix batch. Safe to run on existing deployments (all ADD COLUMN
-- with defaults; new tables are IF NOT EXISTS).
--
-- Contents:
--   1. admin_users: display name + brute-force lockout columns (W-4, A-1).
--   2. users: referral_code column so referral codes are resolvable (H-1).
--   3. community_commits: moderation columns hidden/report_count (F-2).
--   4. support_tickets: user-visible response path already existed; nothing
--      to add here — the serializer now returns response/responded_at (A-2).
-- ============================================================================

-- (1) Admin accounts: human-readable name (the SPA already collects it) and
--     lockout counters for the per-account brute-force lock (W-4).
ALTER TABLE admin_users ADD COLUMN name TEXT;
ALTER TABLE admin_users ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE admin_users ADD COLUMN locked_until TEXT;

-- (2) Referral codes: deterministic code stored on the referrer's row so
--     POST /referral/apply can resolve a code -> referrer with an index
--     lookup instead of a full scan (H-1).
ALTER TABLE users ADD COLUMN referral_code TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_referral_code ON users (referral_code) WHERE referral_code IS NOT NULL;

-- (3) Community moderation: hide + report counters (F-2).
ALTER TABLE community_commits ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
ALTER TABLE community_commits ADD COLUMN report_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_commits_reported ON community_commits (report_count);
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
  '{"schemaVersion":1,"minAppVersion":"1.0.0","platforms":{"youtube":{"enabled":true,"feedViewIds":["reel_watch_fragment_root"],"immersiveViewIds":["pivot_bar"]},"tiktok":{"enabled":true},"facebook":{"enabled":true,"eventTextHints":["Reel details","Reels tab details"],"reelDetailsHints":["Reel details","Reels tab details"],"navHints":["Navigate to your Reels profile"],"reelsHints":["Reels"],"fullscreenHints":["Fullscreen"]},"facebook_lite":{"enabled":true,"feedViewIds":["video_view"],"immersiveGate":true},"instagram":{"enabled":true,"feedViewIds":["root_clips_layout"],"liteFeedViewIds":["clips_viewer_video_container"],"sharedFeedViewIds":["reel_recycler"]},"chrome":{"enabled":true,"urlShapes":["youtube.com/shorts","m.youtube.com/shorts","facebook.com/reel/","facebook.com/reels/","instagram.com/reel/","instagram.com/reels/"]},"chrome_beta":{"enabled":true,"urlShapes":["youtube.com/shorts","m.youtube.com/shorts","facebook.com/reel/","facebook.com/reels/","instagram.com/reel/","instagram.com/reels/"]}}}',
  'PUBLISHED',
  'seed',
  '2026-09-26T00:00:00.000Z',
  '2026-09-26T00:00:00.000Z'
);
