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
