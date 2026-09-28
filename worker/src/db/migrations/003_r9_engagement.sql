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
