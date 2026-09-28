-- ============================================================================
-- 006_user_passwords.sql — Email/password auth for mobile users
-- ============================================================================
ALTER TABLE users ADD COLUMN password_hash TEXT;
