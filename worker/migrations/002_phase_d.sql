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
