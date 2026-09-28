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
