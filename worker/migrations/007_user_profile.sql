-- ============================================================================
-- 007_user_profile.sql — signup profile fields (v2.9.6 r22)
-- User-requested signup data: Gmail (email), name, AGE and CLASS (grade).
-- Both nullable: Google sign-ups provide email + name only; email sign-ups
-- collect all four from the registration form.
-- ============================================================================

ALTER TABLE users ADD COLUMN age INTEGER;
ALTER TABLE users ADD COLUMN grade TEXT;
