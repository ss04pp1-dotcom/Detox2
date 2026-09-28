/**
 * Mock transport for demo mode (VITE_API_URL=mock).
 * Implements every frozen /admin/* route with realistic in-memory data,
 * envelope-shaped errors (ApiError), latency simulation, cursor pagination
 * and the same validation rules the real backend enforces (config bounds,
 * required fields, conflicts). All mutations reset on page reload.
 */
import { ApiError } from './error';
import {
  CONFIG_NUMERIC_BOUNDS,
  CONFIG_STRING_BOUNDS,
  type AdminRole,
  type AdminUser,
  type AnalyticsRange,
  type AnalyticsResponse,
  type Announcement,
  type AnnouncementInput,
  type AnnouncementPatch,
  type AppConfig,
  type AppVersionPolicy,
  type AuditAction,
  type AuditLog,
  type BkashPayment,
  type BkashPaymentStatus,
  type ConfigDoc,
  type ConfigResponse,
  type ConfigVersionSummary,
  type Club,
  type DetectionRulesDoc,
  type DetectionRulesResponse,
  type Device,
  type DauPoint,
  type FeatureFlag,
  type FlagPatch,
  type LeaderboardEntry,
  type LoginResponse,
  type OverviewResponse,
  type Plan,
  type PlanPatch,
  type RetentionCohort,
  type SecurityEvent,
  type SecuritySeverity,
  type Subscription,
  type SystemHealth,
  type Ticket,
  type TicketPatch,
  type TicketStatus,
  type User,
  type UserStatus,
} from './types';

// ---------------------------------------------------------------------------
// Deterministic RNG (stable demo data across reloads)
// ---------------------------------------------------------------------------

function mulberry32(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(0x4d4c4478);
function randInt(min: number, max: number): number {
  return min + Math.floor(rand() * (max - min + 1));
}

const NOW = Date.now();
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
function isoAgo(ms: number): string {
  return new Date(NOW - ms).toISOString();
}
function isoDaysAgo(days: number, hoursIntoDay = 0): string {
  return new Date(NOW - days * DAY - hoursIntoDay * HOUR).toISOString();
}

let ridCounter = 0;
function rid(): string {
  ridCounter += 1;
  const time = Math.floor(NOW / 1000).toString(16).padStart(8, '0');
  const seq = ridCounter.toString(16).padStart(8, '0');
  return `req_${(time + seq).slice(0, 16)}`;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Admins (password for every demo account: demo1234)
// ---------------------------------------------------------------------------

const MOCK_PASSWORD = 'demo1234';

const admins: AdminUser[] = [
  { id: 'adm_001', email: 'super@maxleveldetox.com', name: 'Aarav Shah', role: 'SUPER_ADMIN', createdAt: isoDaysAgo(420), lastLoginAt: isoAgo(3 * HOUR) },
  { id: 'adm_002', email: 'admin@maxleveldetox.com', name: 'Neha Kulkarni', role: 'ADMIN', createdAt: isoDaysAgo(300), lastLoginAt: isoAgo(2 * DAY) },
  { id: 'adm_003', email: 'support@maxleveldetox.com', name: 'Rahul Bose', role: 'SUPPORT', createdAt: isoDaysAgo(210), lastLoginAt: isoAgo(26 * HOUR) },
  { id: 'adm_004', email: 'analyst@maxleveldetox.com', name: 'Ishita Rao', role: 'ANALYST', createdAt: isoDaysAgo(120), lastLoginAt: isoAgo(5 * DAY) },
  { id: 'adm_005', email: 'config@maxleveldetox.com', name: 'Dev Menon', role: 'CONFIG_MANAGER', createdAt: isoDaysAgo(90), lastLoginAt: isoAgo(8 * HOUR) },
  { id: 'adm_006', email: 'readonly@maxleveldetox.com', name: 'Sara Ali', role: 'READ_ONLY', createdAt: isoDaysAgo(30), lastLoginAt: isoDaysAgo(4) },
];

let currentAdminEmail = 'super@maxleveldetox.com';

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

interface UserSeed {
  id: string;
  email: string;
  name: string;
  status: UserStatus;
  plan: 'FREE' | 'PREMIUM';
  coins: number;
  lastSeenHours: number | null;
  createdDaysAgo: number;
}

const USER_SEEDS: UserSeed[] = [
  { id: 'usr_01', email: 'arjun.mehta@gmail.com', name: 'Arjun Mehta', status: 'ACTIVE', plan: 'PREMIUM', coins: 640, lastSeenHours: 0.1, createdDaysAgo: 214 },
  { id: 'usr_02', email: 'priya.sharma@outlook.com', name: 'Priya Sharma', status: 'ACTIVE', plan: 'FREE', coins: 45, lastSeenHours: 1.4, createdDaysAgo: 178 },
  { id: 'usr_03', email: 'rohan.iyer@yahoo.com', name: 'Rohan Iyer', status: 'SUSPENDED', plan: 'FREE', coins: 0, lastSeenHours: 312, createdDaysAgo: 160 },
  { id: 'usr_04', email: 'sneha.patel@gmail.com', name: 'Sneha Patel', status: 'ACTIVE', plan: 'PREMIUM', coins: 1210, lastSeenHours: 0.3, createdDaysAgo: 155 },
  { id: 'usr_05', email: 'vikram.rao@gmail.com', name: 'Vikram Rao', status: 'ACTIVE', plan: 'FREE', coins: 12, lastSeenHours: 20, createdDaysAgo: 141 },
  { id: 'usr_06', email: 'ananya.ghosh@gmail.com', name: 'Ananya Ghosh', status: 'PENDING', plan: 'FREE', coins: 0, lastSeenHours: null, createdDaysAgo: 0.2 },
  { id: 'usr_07', email: 'karan.malhotra@proton.me', name: 'Karan Malhotra', status: 'ACTIVE', plan: 'FREE', coins: 87, lastSeenHours: 4.5, createdDaysAgo: 98 },
  { id: 'usr_08', email: 'meera.nair@gmail.com', name: 'Meera Nair', status: 'ACTIVE', plan: 'PREMIUM', coins: 320, lastSeenHours: 2.2, createdDaysAgo: 87 },
  { id: 'usr_09', email: 'aditya.verma@gmail.com', name: 'Aditya Verma', status: 'SUSPENDED', plan: 'PREMIUM', coins: 500, lastSeenHours: 500, createdDaysAgo: 76 },
  { id: 'usr_10', email: 'divya.krishnan@gmail.com', name: 'Divya Krishnan', status: 'ACTIVE', plan: 'FREE', coins: 23, lastSeenHours: 9.0, createdDaysAgo: 64 },
  { id: 'usr_11', email: 'siddharth.jain@gmail.com', name: 'Siddharth Jain', status: 'ACTIVE', plan: 'FREE', coins: 156, lastSeenHours: 6.7, createdDaysAgo: 41 },
  { id: 'usr_12', email: 'ishaan.kapoor@yahoo.com', name: 'Ishaan Kapoor', status: 'ACTIVE', plan: 'FREE', coins: 8, lastSeenHours: 30, createdDaysAgo: 22 },
  { id: 'usr_13', email: 'lakshmi.menon@gmail.com', name: 'Lakshmi Menon', status: 'ACTIVE', plan: 'PREMIUM', coins: 980, lastSeenHours: 0.8, createdDaysAgo: 15 },
  { id: 'usr_14', email: 'farhan.qureshi@gmail.com', name: 'Farhan Qureshi', status: 'PENDING', plan: 'FREE', coins: 0, lastSeenHours: null, createdDaysAgo: 0.05 },
];

let users: User[] = USER_SEEDS.map((s) => ({
  id: s.id,
  email: s.email,
  displayName: s.name,
  status: s.status,
  plan: s.plan,
  coinBalance: s.coins,
  deviceCount: 0, // filled after devices are seeded
  lastSeenAt: s.lastSeenHours === null ? null : isoAgo(s.lastSeenHours * HOUR),
  createdAt: isoDaysAgo(s.createdDaysAgo),
}));

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

interface DeviceSeed {
  id: string;
  userId: string;
  model: string;
  manufacturer: string;
  android: string;
  app: string;
  lastSeenHours: number;
  active: boolean;
  risk: 'LOW' | 'MEDIUM' | 'HIGH';
}

const DEVICE_SEEDS: DeviceSeed[] = [
  { id: 'dev_8f21ab', userId: 'usr_01', model: 'Pixel 8', manufacturer: 'Google', android: '15', app: '1.2.0', lastSeenHours: 0.1, active: true, risk: 'LOW' },
  { id: 'dev_2c94de', userId: 'usr_01', model: 'Galaxy Tab S9', manufacturer: 'Samsung', android: '14', app: '1.1.2', lastSeenHours: 71, active: false, risk: 'LOW' },
  { id: 'dev_77b3fa', userId: 'usr_02', model: 'Galaxy S23', manufacturer: 'Samsung', android: '14', app: '1.2.0', lastSeenHours: 1.4, active: true, risk: 'LOW' },
  { id: 'dev_10cc82', userId: 'usr_03', model: 'Redmi Note 12', manufacturer: 'Xiaomi', android: '13', app: '1.0.4', lastSeenHours: 312, active: false, risk: 'HIGH' },
  { id: 'dev_5aa1e7', userId: 'usr_04', model: 'OnePlus 12', manufacturer: 'OnePlus', android: '15', app: '1.2.0', lastSeenHours: 0.3, active: true, risk: 'LOW' },
  { id: 'dev_9d33b1', userId: 'usr_04', model: 'Pixel 6a', manufacturer: 'Google', android: '14', app: '1.2.0', lastSeenHours: 5.1, active: true, risk: 'LOW' },
  { id: 'dev_41f0c6', userId: 'usr_05', model: 'Vivo V30', manufacturer: 'Vivo', android: '14', app: '1.1.0', lastSeenHours: 20, active: false, risk: 'MEDIUM' },
  { id: 'dev_bb7e20', userId: 'usr_07', model: 'Moto G84', manufacturer: 'Motorola', android: '13', app: '1.2.0', lastSeenHours: 4.5, active: true, risk: 'LOW' },
  { id: 'dev_62d518', userId: 'usr_08', model: 'Galaxy A55', manufacturer: 'Samsung', android: '14', app: '1.1.2', lastSeenHours: 2.2, active: true, risk: 'LOW' },
  { id: 'dev_e8a4f3', userId: 'usr_09', model: 'Redmi Note 11', manufacturer: 'Xiaomi', android: '12', app: '1.0.1', lastSeenHours: 500, active: false, risk: 'HIGH' },
  { id: 'dev_37c9d2', userId: 'usr_10', model: 'Pixel 7a', manufacturer: 'Google', android: '14', app: '1.2.0', lastSeenHours: 9.0, active: true, risk: 'LOW' },
  { id: 'dev_f4b6a8', userId: 'usr_11', model: 'iQOO Z9', manufacturer: 'Vivo', android: '14', app: '1.1.0', lastSeenHours: 6.7, active: true, risk: 'LOW' },
  { id: 'dev_90e2c4', userId: 'usr_12', model: 'Galaxy M34', manufacturer: 'Samsung', android: '14', app: '1.0.4', lastSeenHours: 30, active: false, risk: 'MEDIUM' },
  { id: 'dev_1a6b7d', userId: 'usr_13', model: 'OnePlus Nord 3', manufacturer: 'OnePlus', android: '14', app: '1.2.0', lastSeenHours: 0.8, active: true, risk: 'LOW' },
  { id: 'dev_c55f90', userId: 'usr_13', model: 'Pixel 9', manufacturer: 'Google', android: '15', app: '1.2.0', lastSeenHours: 26, active: true, risk: 'LOW' },
  { id: 'dev_6e08b2', userId: 'usr_14', model: 'Realme 12 Pro', manufacturer: 'Realme', android: '14', app: '1.2.0', lastSeenHours: 0.05, active: true, risk: 'LOW' },
  { id: 'dev_af3d47', userId: 'usr_02', model: 'Redmi 13C', manufacturer: 'Xiaomi', android: '13', app: '1.1.0', lastSeenHours: 190, active: false, risk: 'LOW' },
  { id: 'dev_28d1c9', userId: 'usr_05', model: 'Galaxy S21', manufacturer: 'Samsung', android: '13', app: '1.0.4', lastSeenHours: 410, active: false, risk: 'LOW' },
];

let devices: Device[] = DEVICE_SEEDS.map((s) => ({
  id: s.id,
  userId: s.userId,
  userEmail: users.find((u) => u.id === s.userId)?.email ?? 'unknown',
  model: s.model,
  manufacturer: s.manufacturer,
  androidVersion: s.android,
  appVersion: s.app,
  lastSeenAt: isoAgo(s.lastSeenHours * HOUR),
  status: s.active ? 'ACTIVE' : 'INACTIVE',
  riskLevel: s.risk,
}));

for (const d of devices) {
  const owner = users.find((u) => u.id === d.userId);
  if (owner) owner.deviceCount += 1;
}

// ---------------------------------------------------------------------------
// Subscriptions
// ---------------------------------------------------------------------------

let subscriptions: Subscription[] = [
  { id: 'sub_01', userId: 'usr_01', userEmail: 'arjun.mehta@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'ACTIVE', startedAt: isoDaysAgo(94), expiryDate: isoAgo(-6 * DAY), lastVerifiedAt: isoAgo(2 * HOUR), priceUsd: 4.99, autoRenewing: true },
  { id: 'sub_02', userId: 'usr_04', userEmail: 'sneha.patel@gmail.com', productId: 'com.maxleveldetox.premium.yearly', plan: 'YEARLY', status: 'ACTIVE', startedAt: isoDaysAgo(150), expiryDate: isoAgo(-215 * DAY), lastVerifiedAt: isoAgo(1 * HOUR), priceUsd: 39.99, autoRenewing: true },
  { id: 'sub_03', userId: 'usr_08', userEmail: 'meera.nair@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'ACTIVE', startedAt: isoDaysAgo(57), expiryDate: isoAgo(-3 * DAY), lastVerifiedAt: isoAgo(9 * HOUR), priceUsd: 4.99, autoRenewing: true },
  { id: 'sub_04', userId: 'usr_13', userEmail: 'lakshmi.menon@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'ACTIVE', startedAt: isoDaysAgo(12), expiryDate: isoAgo(-18 * DAY), lastVerifiedAt: isoAgo(4 * HOUR), priceUsd: 4.99, autoRenewing: true },
  { id: 'sub_05', userId: 'usr_09', userEmail: 'aditya.verma@gmail.com', productId: 'com.maxleveldetox.premium.yearly', plan: 'YEARLY', status: 'CANCELLED', startedAt: isoDaysAgo(70), expiryDate: isoDaysAgo(20), lastVerifiedAt: isoDaysAgo(20), priceUsd: 39.99, autoRenewing: false },
  { id: 'sub_06', userId: 'usr_02', userEmail: 'priya.sharma@outlook.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'EXPIRED', startedAt: isoDaysAgo(60), expiryDate: isoDaysAgo(30), lastVerifiedAt: isoDaysAgo(30), priceUsd: 4.99, autoRenewing: false },
  { id: 'sub_07', userId: 'usr_05', userEmail: 'vikram.rao@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'PENDING', startedAt: isoDaysAgo(0.3), expiryDate: null, lastVerifiedAt: null, priceUsd: 4.99, autoRenewing: false },
  { id: 'sub_08', userId: 'usr_07', userEmail: 'karan.malhotra@proton.me', productId: 'com.maxleveldetox.premium.yearly', plan: 'YEARLY', status: 'EXPIRED', startedAt: isoDaysAgo(400), expiryDate: isoDaysAgo(35), lastVerifiedAt: isoDaysAgo(35), priceUsd: 39.99, autoRenewing: false },
  { id: 'sub_09', userId: 'usr_10', userEmail: 'divya.krishnan@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'GRACE', startedAt: isoDaysAgo(45), expiryDate: isoAgo(-1 * DAY), lastVerifiedAt: isoDaysAgo(4), priceUsd: 4.99, autoRenewing: true },
  { id: 'sub_10', userId: 'usr_11', userEmail: 'siddharth.jain@gmail.com', productId: 'com.maxleveldetox.premium.monthly', plan: 'MONTHLY', status: 'EXPIRED', startedAt: isoDaysAgo(25), expiryDate: isoDaysAgo(3), lastVerifiedAt: isoDaysAgo(3), priceUsd: 4.99, autoRenewing: false },
];

// ---------------------------------------------------------------------------
// Remote config
// ---------------------------------------------------------------------------

const PUBLISHED_DEFAULTS: AppConfig = {
  shortsWarningCount: 5,
  cageDurationSeconds: 1800,
  tempUnlockCoins: 5,
  tempUnlockMinutes: 5,
  bailoutCoins: 500,
  defaultStudyMinutes: 60,
  defaultDetoxMinutes: 120,
  minSupportedVersion: '1.0.0',
  maintenanceMode: false,
  gamificationEnabled: true,
  dpMultiplierFocus: 1.0,
  dpMultiplierReels: 1.0,
  dpMultiplierNeutral: 1.0,
  dpDailyCap: 150,
  dpWeeklyCap: 800,
  dpMonthlyCap: 3000,
  // v2.2 Phase D
  paymentsEnabled: true,
  bkashNumber: '01711-000111',
  bkashInstructions: 'Send the exact amount from your bKash app, then paste the reference code into the transfer memo before submitting the TrxID here.',
  trialDays: 7,
  breakPassesPerWeek: 2,
  insightNudgeEnabled: true,
  insightNudgeHour: 20,
};

interface StoredConfig {
  version: number;
  config: AppConfig;
  createdAt: string;
  updatedAt: string;
  updatedBy: string;
  publishedAt: string | null;
}

const configStore: StoredConfig[] = [
  { version: 3, config: { ...PUBLISHED_DEFAULTS, shortsWarningCount: 4, bailoutCoins: 300 }, createdAt: isoDaysAgo(120), updatedAt: isoDaysAgo(120), updatedBy: 'super@maxleveldetox.com', publishedAt: isoDaysAgo(120) },
  { version: 4, config: { ...PUBLISHED_DEFAULTS, tempUnlockCoins: 4 }, createdAt: isoDaysAgo(95), updatedAt: isoDaysAgo(95), updatedBy: 'config@maxleveldetox.com', publishedAt: isoDaysAgo(95) },
  { version: 5, config: { ...PUBLISHED_DEFAULTS, defaultStudyMinutes: 45 }, createdAt: isoDaysAgo(60), updatedAt: isoDaysAgo(60), updatedBy: 'config@maxleveldetox.com', publishedAt: isoDaysAgo(60) },
  { version: 6, config: { ...PUBLISHED_DEFAULTS, cageDurationSeconds: 1500 }, createdAt: isoDaysAgo(34), updatedAt: isoDaysAgo(34), updatedBy: 'super@maxleveldetox.com', publishedAt: isoDaysAgo(34) },
  { version: 7, config: { ...PUBLISHED_DEFAULTS }, createdAt: isoDaysAgo(12), updatedAt: isoDaysAgo(11), updatedBy: 'config@maxleveldetox.com', publishedAt: isoDaysAgo(11) },
  { version: 8, config: { ...PUBLISHED_DEFAULTS, cageDurationSeconds: 2400, tempUnlockCoins: 6, defaultStudyMinutes: 90 }, createdAt: isoDaysAgo(2), updatedAt: isoAgo(5 * HOUR), updatedBy: 'config@maxleveldetox.com', publishedAt: null },
];

let publishedVersion = 7;
let draftVersion: number | null = 8; // null when no draft exists

function toDoc(stored: StoredConfig, _status: 'PUBLISHED' | 'DRAFT'): ConfigDoc {
  // Mirrors the worker: the config payload plus the `_version` doc key —
  // no doc metadata fields leak into the editable draft.
  return { ...stored.config, _version: stored.version };
}

function currentConfig(): ConfigResponse {
  const published = configStore.find((c) => c.version === publishedVersion);
  if (!published) throw new ApiError('SERVER_ERROR', 'Mock published config missing', rid(), 500);
  const draft = draftVersion !== null ? configStore.find((c) => c.version === draftVersion) : undefined;
  const versions: ConfigVersionSummary[] = configStore
    .slice()
    .sort((a, b) => b.version - a.version)
    .map((c) => ({
      version: c.version,
      status: c.version === publishedVersion ? 'PUBLISHED' : c.version === draftVersion ? 'DRAFT' : 'ARCHIVED',
      createdAt: c.createdAt,
      updatedAt: c.updatedAt,
      updatedBy: c.updatedBy,
      publishedAt: c.publishedAt,
    }));
  return { draft: draft ? toDoc(draft, 'DRAFT') : null, published: toDoc(published, 'PUBLISHED'), versions };
}

// ---- detection rules (v2.5.8) mock state ----

const DETECTION_PLATFORM_KEYS: readonly string[] = ['youtube', 'tiktok', 'facebook', 'facebook_lite', 'instagram'];

const DETECTION_DEFAULTS_MOCK: DetectionRulesDoc = {
  schemaVersion: 1,
  minAppVersion: '1.0.0',
  platforms: {
    youtube: { enabled: true, feedViewIds: ['reel_watch_fragment_root'], immersiveViewIds: ['pivot_bar'], activityHints: ['shortsactivity'] },
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
    facebook_lite: { enabled: true, feedViewIds: ['video_view'], immersiveGate: true, activityHints: ['reelsactivity', 'reelactivity'] },
    instagram: { enabled: true, feedViewIds: ['root_clips_layout'], liteFeedViewIds: ['clips_viewer_video_container'], sharedFeedViewIds: ['reel_recycler'], activityHints: ['clipsactivity'] },
  },
};

interface StoredDetectionRules {
  version: number;
  rules: DetectionRulesDoc;
  createdAt: string;
  updatedBy: string;
  publishedAt: string | null;
}

const detectionRulesStore: StoredDetectionRules[] = [
  { version: 1, rules: JSON.parse(JSON.stringify(DETECTION_DEFAULTS_MOCK)) as DetectionRulesDoc, createdAt: isoDaysAgo(20), updatedBy: 'seed', publishedAt: isoDaysAgo(20) },
];

let detectionPublished: number | null = 1;
let detectionDraft: number | null = null;

// ---- community leaderboard + clubs (v2.5.8) mock state ----

const leaderboardMock: LeaderboardEntry[] = [
  { rank: 1, userId: 'usr_demo000001', displayName: 'MonkModeMiraj', email: 'miraj@demo.invalid', userStatus: 'ACTIVE', levelName: 'Monk', streakDays: 41, value: 620, lifetimeDp: 4820, optedIn: true, displayMode: 'name', updatedAt: isoDaysAgo(0) },
  { rank: 2, userId: 'usr_demo000002', displayName: 'Detoxer #A41C', email: 'anon2@demo.invalid', userStatus: 'ACTIVE', levelName: 'Focused', streakDays: 12, value: 410, lifetimeDp: 2310, optedIn: true, displayMode: 'anonymous', updatedAt: isoDaysAgo(0) },
  { rank: 3, userId: 'usr_demo000003', displayName: 'ReelsSlayer99', email: 'slayer@demo.invalid', userStatus: 'ACTIVE', levelName: 'Grinding', streakDays: 5, value: 380, lifetimeDp: 1750, optedIn: true, displayMode: 'name', updatedAt: isoDaysAgo(1) },
  { rank: 4, userId: 'usr_demo000004', displayName: 'EarlyBirdBD', email: 'early@demo.invalid', userStatus: 'ACTIVE', levelName: 'Awake', streakDays: 23, value: 240, lifetimeDp: 990, optedIn: true, displayMode: 'name', updatedAt: isoDaysAgo(1) },
  { rank: 5, userId: 'usr_demo000005', displayName: 'Ghost Grinder', email: 'ghost@demo.invalid', userStatus: 'SUSPENDED', levelName: 'Grinding', streakDays: 0, value: 9999, lifetimeDp: 9999, optedIn: true, displayMode: 'name', updatedAt: isoDaysAgo(0) },
];

const clubsMock: Club[] = [
  { id: 'clb_demo000001', name: '5AM Study Hall', description: 'Dhaka early risers, no doom scrolling before dawn.', inviteCode: '5AMHALL', createdBy: 'usr_demo000001', creatorName: 'MonkModeMiraj', hidden: false, memberCount: 18, createdAt: isoDaysAgo(30) },
  { id: 'clb_demo000002', name: 'Gym Crew Chattogram', description: null, inviteCode: 'GYMCHAT', createdBy: 'usr_demo000004', creatorName: 'EarlyBirdBD', hidden: false, memberCount: 7, createdAt: isoDaysAgo(12) },
  { id: 'clb_demo000003', name: 'Spam Club Buy Followers', description: 'viagra-casino-crypto.info', inviteCode: 'SPAM01', createdBy: 'usr_demo000005', creatorName: 'Ghost Grinder', hidden: true, memberCount: 3, createdAt: isoDaysAgo(2) },
];

function currentDetectionRules(): DetectionRulesResponse {
  const published = detectionPublished !== null ? detectionRulesStore.find((d) => d.version === detectionPublished) : undefined;
  const draft = detectionDraft !== null ? detectionRulesStore.find((d) => d.version === detectionDraft) : undefined;
  return {
    draft: draft ? { ...draft.rules, _version: draft.version } : null,
    published: published ? { ...published.rules, _version: published.version } : null,
    defaults: JSON.parse(JSON.stringify(DETECTION_DEFAULTS_MOCK)) as DetectionRulesDoc,
    versions: detectionRulesStore
      .slice()
      .sort((a, b) => b.version - a.version)
      .map((d) => ({
        version: d.version,
        status: d.version === detectionPublished ? 'PUBLISHED' : d.version === detectionDraft ? 'DRAFT' : 'ARCHIVED',
        createdBy: d.updatedBy,
        createdAt: d.createdAt,
        publishedAt: d.publishedAt,
      })),
  };
}

function validateConfig(config: AppConfig): string | null {
  const keys = Object.keys(CONFIG_NUMERIC_BOUNDS) as Array<keyof typeof CONFIG_NUMERIC_BOUNDS>;
  for (const key of keys) {
    const value = config[key];
    const bounds = CONFIG_NUMERIC_BOUNDS[key];
    if (!Number.isFinite(value) || value < bounds.min || value > bounds.max) {
      return `${key} must be between ${bounds.min} and ${bounds.max}`;
    }
  }
  for (const key of Object.keys(CONFIG_STRING_BOUNDS) as Array<keyof typeof CONFIG_STRING_BOUNDS>) {
    const value = config[key];
    if (typeof value !== 'string' || value.length > CONFIG_STRING_BOUNDS[key].max) {
      return `${key} must be a string of at most ${CONFIG_STRING_BOUNDS[key].max} characters`;
    }
  }
  if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(config.minSupportedVersion)) {
    return 'minSupportedVersion must be a semantic version like 1.0.0';
  }
  return null;
}

// ---------------------------------------------------------------------------
// Feature flags
// ---------------------------------------------------------------------------

let flags: FeatureFlag[] = [
  { key: 'SHOCKWAVE_ALARM', enabled: true, rolloutPercentage: 100, minimumVersion: '1.0.0', environment: 'ALL', description: 'Shockwave alarm enforcement mode with escalating alerts.' },
  { key: 'SHORTS_BLOCKER', enabled: true, rolloutPercentage: 100, minimumVersion: '1.0.0', environment: 'ALL', description: 'YouTube Shorts detection + warning counters + cage.' },
  { key: 'CAGE', enabled: true, rolloutPercentage: 100, minimumVersion: '1.0.0', environment: 'ALL', description: 'Full-screen cage lock after repeated shorts warnings.' },
  { key: 'TEMP_UNLOCK', enabled: true, rolloutPercentage: 100, minimumVersion: '1.0.0', environment: 'ALL', description: 'Coin-priced temporary unlocks of the cage.' },
  { key: 'GOOGLE_DRIVE_BACKUP', enabled: true, rolloutPercentage: 25, minimumVersion: '1.1.0', environment: 'ALL', description: 'Google Drive backup of coins and session history.' },
  { key: 'PREMIUM', enabled: false, rolloutPercentage: 0, minimumVersion: '1.0.0', environment: 'ALL', description: 'Premium subscription paywall and upsells.' },
  { key: 'NEW_ONBOARDING', enabled: true, rolloutPercentage: 10, minimumVersion: '1.2.0', environment: 'ALL', description: 'Redesigned 5-screen onboarding with pact signature step.' },
  { key: 'BETA_FEATURES', enabled: false, rolloutPercentage: 5, minimumVersion: '1.2.0', environment: 'DEV', description: 'Unreleased experimental features (dev builds only).' },
];

// ---------------------------------------------------------------------------
// Announcements
// ---------------------------------------------------------------------------

let announcements: Announcement[] = [
  { id: 'ann_01', title: 'Scheduled maintenance this Sunday', body: 'MAXLEVEL DETOX will be unavailable on Sunday 02:00–03:30 UTC while we upgrade the config service. Active sessions on your phone are never interrupted.', type: 'MAINTENANCE', targetRule: 'all', startAt: isoAgo(2 * DAY), endAt: isoAgo(-5 * DAY), status: 'PUBLISHED', createdAt: isoDaysAgo(4), createdBy: 'admin@maxleveldetox.com' },
  { id: 'ann_02', title: 'v1.2.0 is live', body: 'Shockwave Alarm improvements, faster cage release, and a smoother onboarding. Update from Google Play to get the latest build.', type: 'UPDATE', targetRule: 'app_version_below_1.2.0', startAt: isoDaysAgo(6), endAt: isoAgo(-9 * DAY), status: 'PUBLISHED', createdAt: isoDaysAgo(7), createdBy: 'admin@maxleveldetox.com' },
  { id: 'ann_03', title: 'Study streak challenge', body: 'Complete a study session every day this week and earn 25 bonus coins. Streaks reset Monday 00:00 local time.', type: 'PROMOTION', targetRule: 'all', startAt: isoAgo(1 * DAY), endAt: isoAgo(-6 * DAY), status: 'PUBLISHED', createdAt: isoDaysAgo(2), createdBy: 'admin@maxleveldetox.com' },
  { id: 'ann_04', title: 'Known issue: cage timer on Android 15', body: 'A small number of Android 15 devices report the cage timer resetting after app updates. Sessions continue safely — a fix ships in 1.2.1.', type: 'WARNING', targetRule: 'android_15', startAt: isoAgo(10 * HOUR), endAt: null, status: 'PUBLISHED', createdAt: isoAgo(11 * HOUR), createdBy: 'support@maxleveldetox.com' },
  { id: 'ann_05', title: 'Welcome to MAXLEVEL DETOX', body: 'Discipline today, freedom tomorrow. Take back control of your attention.', type: 'INFO', targetRule: 'all', startAt: isoDaysAgo(200), endAt: isoDaysAgo(180), status: 'EXPIRED', createdAt: isoDaysAgo(200), createdBy: 'super@maxleveldetox.com' },
  { id: 'ann_06', title: 'Weekend focus marathon', body: 'Double coins for completed detox sessions this weekend.', type: 'PROMOTION', targetRule: 'free_users', startAt: isoAgo(-2 * DAY), endAt: isoAgo(-4 * DAY), status: 'DRAFT', createdAt: isoAgo(6 * HOUR), createdBy: 'admin@maxleveldetox.com' },
];

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

let auditLog: AuditLog[] = [];

function pushAudit(action: AuditAction, resourceType: string, resourceId: string | null, metadata: Record<string, unknown> | null): void {
  auditLog.unshift({
    id: `aud_${String(auditLog.length + 1).padStart(4, '0')}`,
    adminId: admins.find((a) => a.email === currentAdminEmail)?.id ?? 'adm_001',
    adminEmail: currentAdminEmail,
    action,
    resourceType,
    resourceId,
    result: 'SUCCESS',
    metadata,
    requestId: rid(),
    createdAt: new Date().toISOString(),
  });
}

interface AuditSeed {
  admin: string;
  action: AuditAction;
  resourceType: string;
  resourceId: string | null;
  result: 'SUCCESS' | 'FAILURE';
  metadata: Record<string, unknown> | null;
  hoursAgo: number;
}

const AUDIT_SEEDS: AuditSeed[] = [
  { admin: 'super@maxleveldetox.com', action: 'ADMIN_LOGIN', resourceType: 'auth', resourceId: null, result: 'SUCCESS', metadata: null, hoursAgo: 3 },
  { admin: 'config@maxleveldetox.com', action: 'ADMIN_LOGIN', resourceType: 'auth', resourceId: null, result: 'SUCCESS', metadata: null, hoursAgo: 8 },
  { admin: 'unknown@badactor.io', action: 'ADMIN_LOGIN_FAILED', resourceType: 'auth', resourceId: null, result: 'FAILURE', metadata: { ip: '203.0.113.44', attempts: 3 }, hoursAgo: 9 },
  { admin: 'config@maxleveldetox.com', action: 'CONFIG_SAVED', resourceType: 'config', resourceId: 'v8', result: 'SUCCESS', metadata: { changedFields: ['cageDurationSeconds', 'tempUnlockCoins', 'defaultStudyMinutes'] }, hoursAgo: 5 },
  { admin: 'super@maxleveldetox.com', action: 'CONFIG_PUBLISHED', resourceType: 'config', resourceId: 'v7', result: 'SUCCESS', metadata: { version: 7 }, hoursAgo: 11 * 24 },
  { admin: 'super@maxleveldetox.com', action: 'CONFIG_ROLLED_BACK', resourceType: 'config', resourceId: 'v6', result: 'SUCCESS', metadata: { from: 6, to: 5 }, hoursAgo: 34 * 24 },
  { admin: 'config@maxleveldetox.com', action: 'FLAG_UPDATED', resourceType: 'flag', resourceId: 'GOOGLE_DRIVE_BACKUP', result: 'SUCCESS', metadata: { rolloutPercentage: { from: 10, to: 25 } }, hoursAgo: 26 },
  { admin: 'admin@maxleveldetox.com', action: 'FLAG_UPDATED', resourceType: 'flag', resourceId: 'NEW_ONBOARDING', result: 'SUCCESS', metadata: { enabled: { from: false, to: true }, rolloutPercentage: { from: 0, to: 10 } }, hoursAgo: 3 * 24 },
  { admin: 'admin@maxleveldetox.com', action: 'ANNOUNCEMENT_CREATED', resourceType: 'announcement', resourceId: 'ann_03', result: 'SUCCESS', metadata: { title: 'Study streak challenge' }, hoursAgo: 2 * 24 },
  { admin: 'support@maxleveldetox.com', action: 'ANNOUNCEMENT_UPDATED', resourceType: 'announcement', resourceId: 'ann_04', result: 'SUCCESS', metadata: { status: { from: 'DRAFT', to: 'PUBLISHED' } }, hoursAgo: 11 },
  { admin: 'support@maxleveldetox.com', action: 'USER_STATUS_CHANGED', resourceType: 'user', resourceId: 'usr_03', result: 'SUCCESS', metadata: { from: 'ACTIVE', to: 'SUSPENDED', reason: 'Repeated integrity failures' }, hoursAgo: 13 * 24 },
  { admin: 'super@maxleveldetox.com', action: 'USER_STATUS_CHANGED', resourceType: 'user', resourceId: 'usr_09', result: 'SUCCESS', metadata: { from: 'ACTIVE', to: 'SUSPENDED', reason: 'Chargeback + VPN abuse' }, hoursAgo: 20 * 24 },
  { admin: 'support@maxleveldetox.com', action: 'COIN_ADJUSTMENT', resourceType: 'user', resourceId: 'usr_02', result: 'SUCCESS', metadata: { amount: 25, reason: 'Goodwill — ad reward not credited', balanceAfter: 45 }, hoursAgo: 30 },
  { admin: 'admin@maxleveldetox.com', action: 'COIN_ADJUSTMENT', resourceType: 'user', resourceId: 'usr_11', result: 'SUCCESS', metadata: { amount: -50, reason: 'Penalty reversal — duplicate bailout', balanceAfter: 156 }, hoursAgo: 2 * 24 },
  { admin: 'super@maxleveldetox.com', action: 'APP_VERSION_UPDATED', resourceType: 'app_version', resourceId: 'policy', result: 'SUCCESS', metadata: { before: { minimum: '0.9.0', latest: '1.1.0' }, after: { minimum: '1.0.0', latest: '1.2.0' } }, hoursAgo: 6 * 24 },
  { admin: 'super@maxleveldetox.com', action: 'ADMIN_CREATED', resourceType: 'admin', resourceId: 'adm_006', result: 'SUCCESS', metadata: { email: 'readonly@maxleveldetox.com', role: 'READ_ONLY' }, hoursAgo: 30 * 24 },
  { admin: 'super@maxleveldetox.com', action: 'ROLE_CHANGED', resourceType: 'admin', resourceId: 'adm_004', result: 'SUCCESS', metadata: { from: 'READ_ONLY', to: 'ANALYST' }, hoursAgo: 28 * 24 },
  { admin: 'analyst@maxleveldetox.com', action: 'ADMIN_LOGIN', resourceType: 'auth', resourceId: null, result: 'SUCCESS', metadata: null, hoursAgo: 5 * 24 },
  { admin: 'readonly@maxleveldetox.com', action: 'ADMIN_LOGIN', resourceType: 'auth', resourceId: null, result: 'SUCCESS', metadata: null, hoursAgo: 4 * 24 },
  { admin: 'admin@maxleveldetox.com', action: 'CONFIG_SAVED', resourceType: 'config', resourceId: 'v7', result: 'SUCCESS', metadata: { changedFields: ['minSupportedVersion'] }, hoursAgo: 12 * 24 },
  { admin: 'support@maxleveldetox.com', action: 'ADMIN_LOGIN', resourceType: 'auth', resourceId: null, result: 'SUCCESS', metadata: null, hoursAgo: 26 },
  { admin: 'config@maxleveldetox.com', action: 'FLAG_UPDATED', resourceType: 'flag', resourceId: 'BETA_FEATURES', result: 'FAILURE', metadata: { error: 'VALIDATION_FAILED', detail: 'rolloutPercentage must be <= 100' }, hoursAgo: 7 * 24 },
];

auditLog = AUDIT_SEEDS.map((s, i) => ({
  id: `aud_${String(i + 1).padStart(4, '0')}`,
  adminId: admins.find((a) => a.email === s.admin)?.id ?? 'unknown',
  adminEmail: s.admin,
  action: s.action,
  resourceType: s.resourceType,
  resourceId: s.resourceId,
  result: s.result,
  metadata: s.metadata,
  requestId: rid(),
  createdAt: isoAgo(s.hoursAgo * HOUR),
})).sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

// ---------------------------------------------------------------------------
// Security events
// ---------------------------------------------------------------------------

interface SecuritySeed {
  userId: string | null;
  type: string;
  severity: SecuritySeverity;
  metadata: Record<string, unknown>;
  hoursAgo: number;
}

const SECURITY_SEEDS: SecuritySeed[] = [
  { userId: 'usr_03', type: 'ROOT_DETECTION', severity: 'CRITICAL', metadata: { device: 'dev_10cc82', suBinary: true, magisk: 'hidden', appVersion: '1.0.4' }, hoursAgo: 2 },
  { userId: 'usr_09', type: 'INTEGRITY_FAILURE', severity: 'CRITICAL', metadata: { device: 'dev_e8a4f3', check: 'timer_tamper', detail: 'elapsedRealtime regression of 412s detected' }, hoursAgo: 7 },
  { userId: 'usr_01', type: 'PERMISSION_CHANGED', severity: 'MEDIUM', metadata: { device: 'dev_8f21ab', permission: 'accessibility', from: 'granted', to: 'revoked' }, hoursAgo: 12 },
  { userId: 'usr_05', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_41f0c6', reason: 'app_updated_mid_session', sessionState: 'ACTIVE', recovered: true }, hoursAgo: 15 },
  { userId: 'usr_07', type: 'PERMISSION_CHANGED', severity: 'HIGH', metadata: { device: 'dev_bb7e20', permission: 'usage_access', from: 'granted', to: 'revoked', activeSession: true }, hoursAgo: 20 },
  { userId: null, type: 'AUTH_TOKEN_REUSE', severity: 'HIGH', metadata: { ip: '198.51.100.17', attempts: 2, note: 'refresh token replay from new device fingerprint' }, hoursAgo: 26 },
  { userId: 'usr_04', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_9d33b1', reason: 'device_reboot', sessionState: 'ARMED', recovered: true }, hoursAgo: 30 },
  { userId: 'usr_12', type: 'EXCESSIVE_BAILOUTS', severity: 'MEDIUM', metadata: { device: 'dev_90e2c4', bailouts24h: 6, coinSpend: 3000 }, hoursAgo: 33 },
  { userId: 'usr_09', type: 'SUSPICIOUS_ACTIVITY', severity: 'HIGH', metadata: { device: 'dev_e8a4f3', pattern: 'vpn_rotation', distinctIps24h: 14 }, hoursAgo: 36 },
  { userId: 'usr_02', type: 'PERMISSION_CHANGED', severity: 'MEDIUM', metadata: { device: 'dev_77b3fa', permission: 'overlay', from: 'granted', to: 'granted', note: 're-granted mid detox session' }, hoursAgo: 40 },
  { userId: 'usr_13', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_1a6b7d', reason: 'process_death', sessionState: 'ACTIVE', recovered: true }, hoursAgo: 45 },
  { userId: 'usr_03', type: 'SUSPICIOUS_ACTIVITY', severity: 'CRITICAL', metadata: { device: 'dev_10cc82', pattern: 'accessibility_service_spoofing' }, hoursAgo: 50 },
  { userId: 'usr_11', type: 'INTEGRITY_FAILURE', severity: 'MEDIUM', metadata: { device: 'dev_f4b6a8', check: 'overlay_missing', detail: 'overlay permission revoked during cage' }, hoursAgo: 55 },
  { userId: 'usr_10', type: 'PERMISSION_CHANGED', severity: 'LOW', metadata: { device: 'dev_37c9d2', permission: 'notifications', from: 'granted', to: 'revoked' }, hoursAgo: 60 },
  { userId: 'usr_08', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_62d518', reason: 'boot_receiver', sessionState: 'CAGE', recovered: true }, hoursAgo: 66 },
  { userId: null, type: 'AUTH_TOKEN_REUSE', severity: 'MEDIUM', metadata: { ip: '203.0.113.9', attempts: 1 }, hoursAgo: 70 },
  { userId: 'usr_01', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_8f21ab', reason: 'timezone_change', sessionState: 'ACTIVE', recovered: true }, hoursAgo: 75 },
  { userId: 'usr_05', type: 'SUSPICIOUS_ACTIVITY', severity: 'MEDIUM', metadata: { device: 'dev_41f0c6', pattern: 'clock_manipulation', driftMinutes: 37 }, hoursAgo: 80 },
  { userId: 'usr_07', type: 'EXCESSIVE_BAILOUTS', severity: 'LOW', metadata: { device: 'dev_bb7e20', bailouts24h: 4, coinSpend: 2000 }, hoursAgo: 90 },
  { userId: 'usr_04', type: 'PERMISSION_CHANGED', severity: 'LOW', metadata: { device: 'dev_5aa1e7', permission: 'usage_access', from: 'granted', to: 'granted', note: 're-granted after reboot' }, hoursAgo: 96 },
  { userId: 'usr_12', type: 'INTEGRITY_FAILURE', severity: 'HIGH', metadata: { device: 'dev_90e2c4', check: 'foreground_service_killed', detail: 'enforcement service killed by vendor battery manager' }, hoursAgo: 100 },
  { userId: 'usr_13', type: 'SUSPICIOUS_ACTIVITY', severity: 'LOW', metadata: { device: 'dev_c55f90', pattern: 'rapid_device_rotation', devices48h: 3 }, hoursAgo: 110 },
  { userId: 'usr_02', type: 'RECOVERY_TRIGGERED', severity: 'LOW', metadata: { device: 'dev_77b3fa', reason: 'app_crash', sessionState: 'TEMP_UNLOCK', recovered: true }, hoursAgo: 120 },
  { userId: 'usr_09', type: 'ROOT_DETECTION', severity: 'HIGH', metadata: { device: 'dev_e8a4f3', suBinary: false, magisk: 'detected' }, hoursAgo: 130 },
  { userId: 'usr_11', type: 'PERMISSION_CHANGED', severity: 'MEDIUM', metadata: { device: 'dev_f4b6a8', permission: 'accessibility', from: 'revoked', to: 'granted', note: 're-granted to resume cage' }, hoursAgo: 140 },
  { userId: null, type: 'SUSPICIOUS_ACTIVITY', severity: 'MEDIUM', metadata: { ip: '192.0.2.77', pattern: 'credential_stuffing', blocked: true }, hoursAgo: 150 },
];

const securityEvents: SecurityEvent[] = SECURITY_SEEDS.map((s, i) => ({
  id: `sec_${String(i + 1).padStart(4, '0')}`,
  userId: s.userId,
  userEmail: s.userId === null ? null : users.find((u) => u.id === s.userId)?.email ?? null,
  type: s.type,
  severity: s.severity,
  metadata: s.metadata,
  createdAt: isoAgo(s.hoursAgo * HOUR),
}));

// ---------------------------------------------------------------------------
// Support tickets
// ---------------------------------------------------------------------------

interface TicketSeed {
  id: string;
  userId: string;
  subject: string;
  description: string;
  status: TicketStatus;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
  appVersion: string;
  hoursAgo: number;
  responses: Array<{ author: string; body: string; hoursAgo: number }>;
}

const TICKET_SEEDS: TicketSeed[] = [
  { id: 'tkt_001', userId: 'usr_02', subject: 'Cage not releasing after timer', description: 'My detox cage was supposed to end at 21:00 but the screen is still locked. It has been 40 minutes now. Please help, I need my phone for an emergency call.', status: 'IN_PROGRESS', priority: 'URGENT', appVersion: '1.2.0', hoursAgo: 3, responses: [{ author: 'support@maxleveldetox.com', body: 'Sorry about this! Can you confirm whether the timer on the cage screen is still counting down, or frozen? We are checking your device heartbeat now.', hoursAgo: 2 }] },
  { id: 'tkt_002', userId: 'usr_05', subject: 'Coins missing after rewarded ad', description: 'I watched 3 rewarded ads in a row but only 1 coin was credited. This happened yesterday around 8pm.', status: 'OPEN', priority: 'MEDIUM', appVersion: '1.1.0', hoursAgo: 8, responses: [] },
  { id: 'tkt_003', userId: 'usr_07', subject: 'Premium not activating after purchase', description: 'I bought the yearly plan through Google Play (order GPA.3344-8871-2210) but the app still shows the free plan and the cage limits.', status: 'WAITING_USER', priority: 'HIGH', appVersion: '1.2.0', hoursAgo: 26, responses: [{ author: 'support@maxleveldetox.com', body: 'Thanks for the order ID. Google Play reports the purchase as pending verification. Could you open Play Store > Payments > Subscriptions and confirm the status shows Active?', hoursAgo: 24 }] },
  { id: 'tkt_004', userId: 'usr_12', subject: 'App crashes on Android 12 when arming', description: 'Every time I tap ARM for a study session the app crashes. Device: Redmi Note 11, Android 12, MIUI 14.', status: 'IN_PROGRESS', priority: 'HIGH', appVersion: '1.0.4', hoursAgo: 30, responses: [{ author: 'support@maxleveldetox.com', body: 'Crash received — this looks like the accessibility service startup race fixed in 1.1.0. Please update and let us know if it recurs.', hoursAgo: 28 }] },
  { id: 'tkt_005', userId: 'usr_10', subject: 'Request data deletion', description: 'Please delete my account and all associated data as per the privacy policy.', status: 'RESOLVED', priority: 'LOW', appVersion: '1.2.0', hoursAgo: 3 * 24, responses: [{ author: 'support@maxleveldetox.com', body: 'Deletion request received. Your account is scheduled for permanent deletion within 7 days; a confirmation email has been sent.', hoursAgo: 2.5 * 24 }] },
  { id: 'tkt_006', userId: 'usr_01', subject: 'Temp unlock not working', description: 'I paid 5 coins for a temp unlock but the cage stayed locked and the coins were spent.', status: 'RESOLVED', priority: 'MEDIUM', appVersion: '1.2.0', hoursAgo: 4 * 24, responses: [{ author: 'support@maxleveldetox.com', body: 'We refunded 5 coins as a goodwill credit — the unlock failed due to a config sync race fixed in 1.2.0.', hoursAgo: 3.8 * 24 }] },
  { id: 'tkt_007', userId: 'usr_08', subject: 'Shockwave alarm too quiet', description: 'The shockwave alarm volume is much lower than my system alarm even at max. I overslept.', status: 'OPEN', priority: 'MEDIUM', appVersion: '1.2.0', hoursAgo: 5 * 24, responses: [] },
  { id: 'tkt_008', userId: 'usr_09', subject: 'Refund request', description: 'I want a refund for my yearly subscription. I was suspended without explanation.', status: 'CLOSED', priority: 'LOW', appVersion: '1.0.1', hoursAgo: 18 * 24, responses: [{ author: 'support@maxleveldetox.com', body: 'Your account was suspended for repeated integrity violations (timer tampering). Refund requests are handled through Google Play; this ticket is closed.', hoursAgo: 17 * 24 }] },
  { id: 'tkt_009', userId: 'usr_11', subject: 'Account suspended by mistake', description: 'I got suspended but I never did anything wrong. I think my little brother was playing with the phone.', status: 'IN_PROGRESS', priority: 'HIGH', appVersion: '1.1.0', hoursAgo: 6 * 24, responses: [{ author: 'support@maxleveldetox.com', body: 'We see excessive bailouts (6 in 24h) on your device. We can lift the suspension once — please enable app pinning so the session cannot be exited.', hoursAgo: 5.5 * 24 }] },
  { id: 'tkt_010', userId: 'usr_13', subject: 'Sync between devices', description: 'Coins earned on my Pixel 9 do not appear on my Nord 3. Both on the same account.', status: 'OPEN', priority: 'LOW', appVersion: '1.2.0', hoursAgo: 7 * 24, responses: [] },
];

let tickets: Ticket[] = TICKET_SEEDS.map((s) => ({
  id: s.id,
  userId: s.userId,
  userEmail: users.find((u) => u.id === s.userId)?.email ?? 'unknown',
  subject: s.subject,
  description: s.description,
  status: s.status,
  priority: s.priority,
  appVersion: s.appVersion,
  requestId: rid(),
  createdAt: isoAgo(s.hoursAgo * HOUR),
  updatedAt: isoAgo(Math.max(0.2, s.hoursAgo - (s.responses.length > 0 ? 0.5 : 0)) * HOUR),
  responses: s.responses.map((r, i) => ({ id: `${s.id}_r${i + 1}`, authorEmail: r.author, body: r.body, createdAt: isoAgo(r.hoursAgo * HOUR) })),
}));

// ---------------------------------------------------------------------------
// App version policy
// ---------------------------------------------------------------------------

let appVersionPolicy: AppVersionPolicy = {
  minimum: '1.0.0',
  latest: '1.2.0',
  forceUpdate: false,
  message: '',
  updatedAt: isoDaysAgo(6),
  updatedBy: 'super@maxleveldetox.com',
};

// ---------------------------------------------------------------------------
// Analytics generator
// ---------------------------------------------------------------------------

function makeAnalytics(range: AnalyticsRange): AnalyticsResponse {
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 90;
  const dau: DauPoint[] = [];
  const premiumGrowth: DauPoint[] = [];
  let premium = 3;
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(NOW - i * DAY);
    const date = d.toISOString().slice(0, 10);
    const weekend = d.getDay() === 0 || d.getDay() === 6;
    const base = weekend ? 12 : 9;
    const value = base + randInt(0, 3) + Math.floor((days - i) / 14);
    dau.push({ date, value });
    premium = Math.max(2, Math.min(6, premium + (rand() < 0.22 ? 1 : 0) - (rand() < 0.14 ? 1 : 0)));
    premiumGrowth.push({ date, value: premium });
  }
  const sessionsStarted = dau.reduce((sum, p) => sum + p.value * 3, 46) + randInt(0, 30);
  const completed = Math.floor(sessionsStarted * 0.62);
  const bailed = Math.floor(sessionsStarted * 0.14);
  const warnings = Math.floor(sessionsStarted * 2.6) + randInt(0, 40);
  const cages = Math.floor(warnings * 0.18);
  const tempUnlocks = Math.floor(cages * 0.55) + randInt(0, 6);

  const retention: RetentionCohort[] = [];
  for (let w = 5; w >= 0; w--) {
    const cohortDate = new Date(NOW - w * 7 * DAY);
    retention.push({
      cohort: cohortDate.toISOString().slice(0, 10),
      cohortSize: randInt(9, 14),
      day1: randInt(38, 58),
      day7: randInt(22, 38),
      day30: randInt(10, 22),
    });
  }

  const monthly = subscriptions.filter((s) => s.status === 'ACTIVE' && s.plan === 'MONTHLY').length;
  const yearly = subscriptions.filter((s) => s.status === 'ACTIVE' && s.plan === 'YEARLY').length;
  const premiumUsers = monthly + yearly;
  const mrr = monthly * 4.99 + yearly * (39.99 / 12);

  return {
    dau,
    sessions: { started: sessionsStarted, completed, bailed },
    shorts: { warnings, cages },
    tempUnlocks,
    retention,
    revenue: {
      mrrEstimateUsd: Math.round(mrr * 100) / 100,
      premiumUsers,
      premiumGrowth,
      byPlan: [
        { plan: 'MONTHLY', subscribers: monthly, monthlyValueUsd: 4.99 },
        { plan: 'YEARLY', subscribers: yearly, monthlyValueUsd: Math.round((39.99 / 12) * 100) / 100 },
      ],
    },
  };
}

// ---------------------------------------------------------------------------
// Pagination helper (cursor = plain offset in mock)
// ---------------------------------------------------------------------------

interface Page<T> {
  list: T[];
  nextCursor: string | null;
}

function paginate<T>(items: T[], params: URLSearchParams): Page<T> {
  const rawLimit = Number(params.get('limit') ?? '25');
  const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(Math.trunc(rawLimit), 1), 100) : 25;
  const rawCursor = params.get('cursor');
  const offset = rawCursor !== null && /^\d+$/.test(rawCursor) ? parseInt(rawCursor, 10) : 0;
  const list = items.slice(offset, offset + limit);
  const nextCursor = offset + limit < items.length ? String(offset + limit) : null;
  return { list, nextCursor };
}

function notFound(what: string): never {
  throw new ApiError('NOT_FOUND', `${what} not found.`, rid(), 404);
}

function badRequest(message: string): never {
  throw new ApiError('INVALID_REQUEST', message, rid(), 400);
}

function validationFailed(message: string): never {
  throw new ApiError('VALIDATION_FAILED', message, rid(), 422);
}

function bodyAs<T>(body: unknown): T {
  if (body === undefined || body === null || typeof body !== 'object') badRequest('Request body is required.');
  return body as T;
}

// ---------------------------------------------------------------------------
// v2.2 Phase D — plan catalog + bKash payments
// ---------------------------------------------------------------------------

const plans: Plan[] = [
  { id: 'pln_seed_play_1m', productId: 'maxlevel_monthly', plan: 'monthly', source: 'play', durationDays: 30, priceMinor: 29900, currency: 'BDT', displayName: 'Monthly PRO', description: 'All PRO perks, billed monthly through Google Play.', isPopular: false, isActive: true, sortOrder: 10 },
  { id: 'pln_seed_play_3m', productId: 'maxlevel_3monthly', plan: 'quarterly', source: 'play', durationDays: 90, priceMinor: 69900, currency: 'BDT', displayName: '3-Month PRO', description: 'Three months of PRO at a 22% saving vs monthly.', isPopular: false, isActive: true, sortOrder: 20 },
  { id: 'pln_seed_play_6m', productId: 'maxlevel_6monthly', plan: 'semiannual', source: 'play', durationDays: 180, priceMinor: 119900, currency: 'BDT', displayName: '6-Month PRO', description: 'Half a year of PRO at a 33% saving vs monthly.', isPopular: true, isActive: true, sortOrder: 30 },
  { id: 'pln_seed_play_1y', productId: 'maxlevel_yearly', plan: 'yearly', source: 'play', durationDays: 365, priceMinor: 199900, currency: 'BDT', displayName: 'Yearly PRO', description: 'A full year of PRO — the best value tier.', isPopular: false, isActive: true, sortOrder: 40 },
  { id: 'pln_seed_bkash_1m', productId: 'bkash_monthly_299', plan: 'monthly', source: 'bkash', durationDays: 30, priceMinor: 29900, currency: 'BDT', displayName: 'Monthly PRO (bKash)', description: 'Pay with bKash and get verified within a day.', isPopular: false, isActive: true, sortOrder: 50 },
  { id: 'pln_seed_bkash_3m', productId: 'bkash_3month_699', plan: 'quarterly', source: 'bkash', durationDays: 90, priceMinor: 69900, currency: 'BDT', displayName: '3-Month PRO (bKash)', description: 'Pay with bKash and get verified within a day.', isPopular: false, isActive: true, sortOrder: 60 },
  { id: 'pln_seed_bkash_6m', productId: 'bkash_6month_1199', plan: 'semiannual', source: 'bkash', durationDays: 180, priceMinor: 119900, currency: 'BDT', displayName: '6-Month PRO (bKash)', description: 'Pay with bKash and get verified within a day.', isPopular: false, isActive: true, sortOrder: 70 },
  { id: 'pln_seed_bkash_1y', productId: 'bkash_yearly_1999', plan: 'yearly', source: 'bkash', durationDays: 365, priceMinor: 199900, currency: 'BDT', displayName: 'Yearly PRO (bKash)', description: 'Pay with bKash and get verified within a day.', isPopular: false, isActive: true, sortOrder: 80 },
];

interface BkashSeed {
  id: string;
  userId: string;
  planId: string;
  reference: string;
  trxId: string | null;
  sender: string | null;
  status: BkashPaymentStatus;
  hoursAgo: number;
  rejectReason?: string;
}

const BKASH_SEEDS: BkashSeed[] = [
  { id: 'pay_0001', userId: 'usr_02', planId: 'pln_seed_bkash_1m', reference: 'MLD-8F2A91C0', trxId: '9AB7K2L3M4', sender: '+8801711223344', status: 'IN_REVIEW', hoursAgo: 2 },
  { id: 'pay_0002', userId: 'usr_05', planId: 'pln_seed_bkash_6m', reference: 'MLD-3D77B0E2', trxId: '8XQ1P0N7R2', sender: '+8801822334455', status: 'IN_REVIEW', hoursAgo: 5 },
  { id: 'pay_0003', userId: 'usr_08', planId: 'pln_seed_bkash_1y', reference: 'MLD-91C4E5F0', trxId: '7TM3Z9Q1W8', sender: '+8801933445566', status: 'IN_REVIEW', hoursAgo: 9 },
  { id: 'pay_0004', userId: 'usr_01', planId: 'pln_seed_bkash_3m', reference: 'MLD-4A18D66B', trxId: 'CN8V5H2J9K', sender: '+8801611227788', status: 'VERIFIED', hoursAgo: 26 },
  { id: 'pay_0005', userId: 'usr_11', planId: 'pln_seed_bkash_1m', reference: 'MLD-77E0B3C9', trxId: 'TRX-40417', sender: '+8801511229933', status: 'REJECTED', hoursAgo: 40, rejectReason: 'Transfer amount did not match the plan price.' },
  { id: 'pay_0006', userId: 'usr_04', planId: 'pln_seed_bkash_6m', reference: 'MLD-5C92A1D8', trxId: 'BQ2W7E4R9T', sender: '+8801733556677', status: 'VERIFIED', hoursAgo: 62 },
  { id: 'pay_0007', userId: 'usr_13', planId: 'pln_seed_bkash_1m', reference: 'MLD-2F61C8A4', trxId: null, sender: null, status: 'PENDING', hoursAgo: 4 },
  { id: 'pay_0008', userId: 'usr_07', planId: 'pln_seed_bkash_1y', reference: 'MLD-6B05F7E3', trxId: 'ZM4X8N2Q6V', sender: '+8801899887766', status: 'VERIFIED', hoursAgo: 90 },
];

const bkashPayments: BkashPayment[] = BKASH_SEEDS.map((s, i) => {
  const plan = plans.find((p) => p.id === s.planId);
  return {
    id: s.id,
    userId: s.userId,
    userEmail: users.find((u) => u.id === s.userId)?.email ?? null,
    planId: s.planId,
    planName: plan?.displayName ?? null,
    planDays: plan?.durationDays ?? null,
    reference: s.reference,
    amountMinor: plan?.priceMinor ?? 0,
    currency: plan?.currency ?? 'BDT',
    trxId: s.trxId,
    senderNumber: s.sender,
    status: s.status,
    submittedAt: s.trxId !== null ? isoAgo((s.hoursAgo - 1) * HOUR) : null,
    reviewedAt: s.status === 'VERIFIED' || s.status === 'REJECTED' ? isoAgo((s.hoursAgo - 3) * HOUR) : null,
    rejectReason: s.rejectReason ?? null,
    createdAt: isoAgo(s.hoursAgo * HOUR + (i % 5) * 11 * 60_000),
  };
});

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

function route(method: string, path: string, params: URLSearchParams, body: unknown): unknown {
  const M = method.toUpperCase();

  // ---- auth ----
  if (M === 'POST' && path === '/admin/auth/login') {
    const b = bodyAs<{ email?: unknown; password?: unknown }>(body);
    const email = typeof b.email === 'string' ? b.email.trim().toLowerCase() : '';
    const password = typeof b.password === 'string' ? b.password : '';
    if (!email || !password) badRequest('Email and password are required.');
    const admin = admins.find((a) => a.email.toLowerCase() === email);
    if (!admin || password !== MOCK_PASSWORD) {
      auditLog.unshift({
        id: `aud_${String(auditLog.length + 1).padStart(4, '0')}`,
        adminId: 'unknown',
        adminEmail: email || '(empty)',
        action: 'ADMIN_LOGIN_FAILED',
        resourceType: 'auth',
        resourceId: null,
        result: 'FAILURE',
        metadata: { reason: 'invalid_credentials' },
        requestId: rid(),
        createdAt: new Date().toISOString(),
      });
      throw new ApiError('UNAUTHORIZED', 'Invalid email or password.', rid(), 401);
    }
    admin.lastLoginAt = new Date().toISOString();
    currentAdminEmail = admin.email;
    const res: LoginResponse = { admin, token: `mock_${admin.id}_${Math.random().toString(36).slice(2)}`, expiresIn: 3600 };
    return res;
  }

  if (M === 'GET' && path === '/admin/overview') {
    const premium = users.filter((u) => u.plan === 'PREMIUM').length;
    const activeDevices = devices.filter((d) => d.status === 'ACTIVE').length;
    const res: OverviewResponse = {
      metrics: {
        totalUsers: users.length,
        activeUsers7d: users.filter((u) => u.lastSeenAt !== null && Date.now() - new Date(u.lastSeenAt).getTime() < 7 * DAY).length,
        activeDevices,
        premiumUsers: premium,
        newUsersToday: users.filter((u) => Date.now() - new Date(u.createdAt).getTime() < DAY).length,
        sessionsToday: 23,
        apiRequests24h: 4210,
        errorRate: 0.42,
      },
      health: { api: 'healthy', database: 'healthy', configService: 'healthy' },
      recentAudit: auditLog.slice(0, 8),
    };
    return res;
  }

  // ---- users ----
  if (M === 'GET' && path === '/admin/users') {
    const q = (params.get('q') ?? '').trim().toLowerCase();
    const status = params.get('status');
    let filtered = users;
    if (q) filtered = filtered.filter((u) => u.email.toLowerCase().includes(q) || (u.displayName ?? '').toLowerCase().includes(q) || u.id.includes(q));
    if (status) filtered = filtered.filter((u) => u.status === status);
    const sorted = filtered.slice().sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
    const page = paginate(sorted, params);
    return { users: page.list, nextCursor: page.nextCursor };
  }

  const userMatch = path.match(/^\/admin\/users\/([^/]+)$/);
  if (M === 'GET' && userMatch) {
    const user = users.find((u) => u.id === userMatch[1]);
    if (!user) notFound('User');
    return {
      user,
      devices: devices.filter((d) => d.userId === user.id),
      subscription: subscriptions.find((s) => s.userId === user.id) ?? null,
      securityEvents: securityEvents.filter((e) => e.userId === user.id).slice(0, 10),
      supportTickets: tickets.filter((t) => t.userId === user.id),
    };
  }
  if (M === 'PATCH' && userMatch) {
    const user = users.find((u) => u.id === userMatch[1]);
    if (!user) notFound('User');
    const b = bodyAs<{ status?: unknown }>(body);
    const status = b.status;
    if (status !== 'ACTIVE' && status !== 'SUSPENDED' && status !== 'PENDING') badRequest('status must be ACTIVE, SUSPENDED or PENDING.');
    const from = user.status;
    user.status = status;
    pushAudit('USER_STATUS_CHANGED', 'user', user.id, { from, to: status });
    return { user };
  }
  const coinsMatch = path.match(/^\/admin\/users\/([^/]+)\/coins\/adjust$/);
  if (M === 'POST' && coinsMatch) {
    const user = users.find((u) => u.id === coinsMatch[1]);
    if (!user) notFound('User');
    const b = bodyAs<{ amount?: unknown; reason?: unknown }>(body);
    const amount = b.amount;
    const reason = typeof b.reason === 'string' ? b.reason.trim() : '';
    if (typeof amount !== 'number' || !Number.isInteger(amount) || amount === 0) badRequest('amount must be a non-zero integer.');
    if (reason.length < 3) validationFailed('reason is required (min 3 characters).');
    const balanceBefore = user.coinBalance ?? 0;
    const balanceAfter = balanceBefore + amount;
    if (balanceAfter < 0) validationFailed(`Resulting balance cannot be negative (current: ${balanceBefore}, amount: ${amount}).`);
    user.coinBalance = balanceAfter;
    const adjustment = {
      id: `adj_${Date.now().toString(36)}`,
      userId: user.id,
      amount,
      reason,
      balanceBefore,
      balanceAfter,
      adminEmail: currentAdminEmail,
      createdAt: new Date().toISOString(),
    };
    pushAudit('COIN_ADJUSTMENT', 'user', user.id, { amount, reason, balanceBefore, balanceAfter });
    return { adjustment };
  }

  // ---- devices ----
  if (M === 'GET' && path === '/admin/devices') {
    const q = (params.get('q') ?? '').trim().toLowerCase();
    let filtered = devices;
    if (q) filtered = filtered.filter((d) => d.id.toLowerCase().includes(q) || d.userEmail.toLowerCase().includes(q) || d.model.toLowerCase().includes(q) || d.manufacturer.toLowerCase().includes(q));
    const sorted = filtered.slice().sort((a, b) => ((a.lastSeenAt ?? '') < (b.lastSeenAt ?? '') ? 1 : -1));
    const page = paginate(sorted, params);
    return { devices: page.list, nextCursor: page.nextCursor };
  }

  // ---- subscriptions ----
  if (M === 'GET' && path === '/admin/subscriptions') {
    const status = params.get('status');
    let filtered = subscriptions;
    if (status) filtered = filtered.filter((s) => s.status === status);
    const sorted = filtered.slice().sort((a, b) => (a.startedAt < b.startedAt ? 1 : -1));
    const page = paginate(sorted, params);
    return { subscriptions: page.list, nextCursor: page.nextCursor };
  }

  // ---- config ----
  if (M === 'GET' && path === '/admin/config') {
    return currentConfig();
  }
  if (M === 'PUT' && path === '/admin/config') {
    const b = bodyAs<{ config?: unknown }>(body);
    if (typeof b.config !== 'object' || b.config === null) badRequest('config object is required.');
    const config = b.config as AppConfig;
    const invalid = validateConfig(config);
    if (invalid) validationFailed(invalid);
    let draft = configStore.find((c) => c.version === draftVersion);
    if (!draft) {
      const nextVersion = Math.max(...configStore.map((c) => c.version)) + 1;
      draft = { version: nextVersion, config, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), updatedBy: currentAdminEmail, publishedAt: null };
      configStore.push(draft);
      draftVersion = nextVersion;
    } else {
      draft.config = { ...config };
      draft.updatedAt = new Date().toISOString();
      draft.updatedBy = currentAdminEmail;
    }
    pushAudit('CONFIG_SAVED', 'config', `v${draft.version}`, { changedFields: Object.keys(config).filter((k) => (config as unknown as Record<string, unknown>)[k] !== (published().config as unknown as Record<string, unknown>)[k]) });
    return { draft: toDoc(draft, 'DRAFT') };
  }
  if (M === 'POST' && path === '/admin/config/publish') {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to publish.');
    const draft = draftVersion !== null ? configStore.find((c) => c.version === draftVersion) : undefined;
    const source = draft ?? configStore.find((c) => c.version === publishedVersion);
    if (!source) notFound('Config');
    const previous = publishedVersion;
    source.publishedAt = new Date().toISOString();
    source.updatedAt = new Date().toISOString();
    source.updatedBy = currentAdminEmail;
    publishedVersion = source.version;
    draftVersion = null;
    pushAudit('CONFIG_PUBLISHED', 'config', `v${source.version}`, { version: source.version, previousVersion: previous });
    return { published: toDoc(source, 'PUBLISHED') };
  }
  if (M === 'POST' && path === '/admin/config/rollback') {
    const b = bodyAs<{ version?: unknown }>(body);
    if (typeof b.version !== 'number' || !Number.isInteger(b.version)) badRequest('version (integer) is required.');
    const target = configStore.find((c) => c.version === b.version);
    if (!target) notFound(`Config version ${String(b.version)}`);
    const nextVersion = Math.max(...configStore.map((c) => c.version)) + 1;
    const restored: StoredConfig = {
      version: nextVersion,
      config: { ...target.config },
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      updatedBy: currentAdminEmail,
      publishedAt: new Date().toISOString(),
    };
    configStore.push(restored);
    publishedVersion = nextVersion;
    draftVersion = null;
    pushAudit('CONFIG_ROLLED_BACK', 'config', `v${nextVersion}`, { from: target.version, to: nextVersion, restoredFrom: b.version });
    return { published: toDoc(restored, 'PUBLISHED') };
  }

  // ---- detection rules (v2.5.8) ----
  if (M === 'GET' && path === '/admin/detection-rules') {
    return currentDetectionRules();
  }
  if (M === 'PUT' && path === '/admin/detection-rules') {
    const b = bodyAs<{ rules?: unknown }>(body);
    if (typeof b.rules !== 'object' || b.rules === null) badRequest('rules object is required.');
    const rules = b.rules as DetectionRulesDoc;
    if (rules.schemaVersion !== 1) validationFailed('schemaVersion must be 1.');
    if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(String(rules.minAppVersion))) validationFailed('minAppVersion must be a semantic version like 1.0.0.');
    if (rules.platforms === undefined || typeof rules.platforms !== 'object') validationFailed('platforms object is required.');
    for (const [key, value] of Object.entries(rules.platforms ?? {})) {
      if (!DETECTION_PLATFORM_KEYS.includes(key)) validationFailed(`Unknown platform: ${key}`);
      if (typeof value !== 'object' || value === null) validationFailed(`platforms.${key} must be an object.`);
      const rule = value as { enabled?: unknown };
      if (typeof rule.enabled !== 'boolean') validationFailed(`platforms.${key}.enabled must be a boolean.`);
    }
    if (detectionDraft === null) {
      detectionRulesStore.push({ version: detectionRulesStore.length + 1, rules: JSON.parse(JSON.stringify(rules)) as DetectionRulesDoc, createdAt: new Date().toISOString(), updatedBy: currentAdminEmail, publishedAt: null });
      detectionDraft = detectionRulesStore.length;
    } else {
      const stored = detectionRulesStore.find((d) => d.version === detectionDraft);
      if (stored) {
        stored.rules = JSON.parse(JSON.stringify(rules)) as DetectionRulesDoc;
        stored.updatedBy = currentAdminEmail;
      }
    }
    pushAudit('DETECTION_RULES_SAVED', 'detection_rules', `v${detectionDraft ?? 0}`, { platforms: Object.keys(rules.platforms ?? {}).length });
    const saved = detectionRulesStore.find((d) => d.version === detectionDraft);
    return { draft: { ...(saved?.rules ?? rules), _version: detectionDraft ?? 0 } };
  }
  if (M === 'POST' && path === '/admin/detection-rules/publish') {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to publish.');
    const source = detectionRulesStore.find((d) => d.version === detectionDraft);
    if (!source) notFound('Detection rules draft');
    source.publishedAt = new Date().toISOString();
    detectionPublished = source.version;
    detectionDraft = null;
    pushAudit('DETECTION_RULES_PUBLISHED', 'detection_rules', `v${source.version}`, { version: source.version });
    return { published: { ...source.rules, _version: source.version } };
  }
  if (M === 'POST' && path === '/admin/detection-rules/reset') {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to reset.');
    detectionRulesStore.push({ version: detectionRulesStore.length + 1, rules: JSON.parse(JSON.stringify(DETECTION_DEFAULTS_MOCK)) as DetectionRulesDoc, createdAt: new Date().toISOString(), updatedBy: currentAdminEmail, publishedAt: null });
    detectionDraft = detectionRulesStore.length;
    pushAudit('DETECTION_RULES_RESET', 'detection_rules', `v${detectionDraft}`, { note: 'draft restored to defaults' });
    return { draft: { ...DETECTION_DEFAULTS_MOCK, _version: detectionDraft } };
  }

  // ---- community leaderboard + clubs (v2.5.8) ----
  if (M === 'GET' && path === '/admin/leaderboard') {
    const q = (params.get('q') ?? '').trim().toLowerCase();
    const windowParam = String(params.get('window') ?? 'alltime');
    const window: 'weekly' | 'monthly' | 'alltime' =
      windowParam === 'weekly' || windowParam === 'monthly' ? windowParam : 'alltime';
    let entries = leaderboardMock.filter((e) => e.optedIn);
    if (q.length >= 2) {
      entries = entries.filter((e) => e.displayName.toLowerCase().includes(q) || e.userId === q);
    }
    const valueOf = (e: LeaderboardEntry): number =>
      window === 'weekly' ? e.value : window === 'monthly' ? Math.round(e.value * 4.2) : e.lifetimeDp;
    entries = [...entries].sort((a, b) => valueOf(b) - valueOf(a) || b.lifetimeDp - a.lifetimeDp);
    return {
      totals: {
        profiles: leaderboardMock.length,
        optedIn: leaderboardMock.filter((e) => e.optedIn).length,
        lifetimeDp: leaderboardMock.reduce((a, e) => a + e.lifetimeDp, 0),
        lastSync: leaderboardMock.map((e) => e.updatedAt).sort().at(-1) ?? null,
        clubs: clubsMock.length,
        hiddenClubs: clubsMock.filter((c) => c.hidden).length,
      },
      window,
      entries: entries.slice(0, 50).map((e, i) => ({ ...e, rank: i + 1, value: valueOf(e) })),
    };
  }
  const lbResetMatch = path.match(/^\/admin\/leaderboard\/users\/([^/]+)\/reset$/);
  if (M === 'POST' && lbResetMatch) {
    const b = bodyAs<{ confirm?: unknown; reason?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to reset a profile.');
    if (typeof b.reason !== 'string' || b.reason.trim().length < 3) validationFailed('reason must be at least 3 characters.');
    const entry = leaderboardMock.find((e) => e.userId === decodeURIComponent(lbResetMatch[1] ?? ''));
    if (!entry) notFound('Leaderboard profile');
    entry.lifetimeDp = 0;
    entry.value = 0;
    entry.optedIn = false;
    entry.updatedAt = new Date().toISOString();
    pushAudit('LEADERBOARD_PROFILE_RESET', 'leaderboard_profile', entry.userId, { reason: b.reason });
    return { reset: true };
  }
  if (M === 'GET' && path === '/admin/clubs') {
    const q = (params.get('q') ?? '').trim().toLowerCase();
    const filter = String(params.get('filter') ?? 'visible');
    const page = Math.max(1, Number(params.get('page') ?? '1') || 1);
    const pageSize = 25;
    let rows = clubsMock.filter((c) => {
      if (filter === 'visible' && c.hidden) return false;
      if (filter === 'hidden' && !c.hidden) return false;
      if (q.length >= 2 && !c.name.toLowerCase().includes(q)) return false;
      return true;
    });
    rows = [...rows].sort((a, b) => b.memberCount - a.memberCount);
    return {
      clubs: rows.slice((page - 1) * pageSize, page * pageSize),
      page,
      pageSize,
      total: rows.length,
    };
  }
  const clubHideMatch = path.match(/^\/admin\/clubs\/([^/]+)\/hide$/);
  if (M === 'POST' && clubHideMatch) {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to hide a club.');
    const club = clubsMock.find((c) => c.id === decodeURIComponent(clubHideMatch[1] ?? ''));
    if (!club) notFound('Club');
    club.hidden = true;
    pushAudit('CLUB_HIDDEN', 'club', club.id, { name: club.name });
    return { hidden: true };
  }
  const clubRestoreMatch = path.match(/^\/admin\/clubs\/([^/]+)\/restore$/);
  if (M === 'POST' && clubRestoreMatch) {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to restore a club.');
    const club = clubsMock.find((c) => c.id === decodeURIComponent(clubRestoreMatch[1] ?? ''));
    if (!club) notFound('Club');
    club.hidden = false;
    pushAudit('CLUB_RESTORED', 'club', club.id, { name: club.name });
    return { hidden: false };
  }
  const clubDeleteMatch = path.match(/^\/admin\/clubs\/([^/]+)\/delete$/);
  if (M === 'POST' && clubDeleteMatch) {
    const b = bodyAs<{ confirm?: unknown }>(body);
    if (b.confirm !== true) badRequest('confirm must be true to delete a club.');
    const idx = clubsMock.findIndex((c) => c.id === decodeURIComponent(clubDeleteMatch[1] ?? ''));
    if (idx === -1) notFound('Club');
    const [removed] = clubsMock.splice(idx, 1);
    pushAudit('CLUB_DELETED', 'club', removed?.id ?? '', { name: removed?.name ?? '' });
    return { deleted: true };
  }

  // ---- flags ----
  if (M === 'GET' && path === '/admin/flags') {
    return { flags };
  }
  const flagMatch = path.match(/^\/admin\/flags\/([^/]+)$/);
  if (M === 'PATCH' && flagMatch) {
    const flag = flags.find((f) => f.key === flagMatch[1]);
    if (!flag) notFound('Flag');
    const b = bodyAs<FlagPatch>(body);
    if (b.enabled !== undefined) flag.enabled = b.enabled;
    if (b.rolloutPercentage !== undefined) {
      if (!Number.isInteger(b.rolloutPercentage) || b.rolloutPercentage < 0 || b.rolloutPercentage > 100) validationFailed('rolloutPercentage must be an integer between 0 and 100.');
      flag.rolloutPercentage = b.rolloutPercentage;
    }
    if (b.minimumVersion !== undefined) {
      if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(b.minimumVersion)) validationFailed('minimumVersion must be a semantic version like 1.0.0.');
      flag.minimumVersion = b.minimumVersion;
    }
    pushAudit('FLAG_UPDATED', 'flag', flag.key, { applied: b });
    return { flag };
  }

  // ---- announcements ----
  if (M === 'GET' && path === '/admin/announcements') {
    return { announcements: announcements.slice().sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)) };
  }
  if (M === 'POST' && path === '/admin/announcements') {
    const b = bodyAs<AnnouncementInput>(body);
    if (typeof b.title !== 'string' || b.title.trim().length < 3) validationFailed('title is required (min 3 characters).');
    if (typeof b.body !== 'string' || b.body.trim().length < 10) validationFailed('body is required (min 10 characters).');
    if (typeof b.startAt !== 'string' || Number.isNaN(Date.parse(b.startAt))) validationFailed('startAt must be a valid ISO datetime.');
    if (b.endAt !== null && (typeof b.endAt !== 'string' || Number.isNaN(Date.parse(b.endAt)))) validationFailed('endAt must be a valid ISO datetime or null.');
    if (b.endAt !== null && new Date(b.endAt).getTime() <= new Date(b.startAt).getTime()) validationFailed('endAt must be after startAt.');
    const announcement: Announcement = {
      id: `ann_${String(announcements.length + 1).padStart(2, '0')}`,
      title: b.title.trim(),
      body: b.body.trim(),
      type: b.type,
      targetRule: b.targetRule,
      startAt: b.startAt,
      endAt: b.endAt,
      status: 'DRAFT',
      createdAt: new Date().toISOString(),
      createdBy: currentAdminEmail,
    };
    announcements.unshift(announcement);
    pushAudit('ANNOUNCEMENT_CREATED', 'announcement', announcement.id, { title: announcement.title });
    return { announcement };
  }
  const annMatch = path.match(/^\/admin\/announcements\/([^/]+)$/);
  if (M === 'PATCH' && annMatch) {
    const announcement = announcements.find((a) => a.id === annMatch[1]);
    if (!announcement) notFound('Announcement');
    const b = bodyAs<AnnouncementPatch>(body);
    if (b.title !== undefined) announcement.title = b.title;
    if (b.body !== undefined) announcement.body = b.body;
    if (b.type !== undefined) announcement.type = b.type;
    if (b.targetRule !== undefined) announcement.targetRule = b.targetRule;
    if (b.startAt !== undefined) announcement.startAt = b.startAt;
    if (b.endAt !== undefined) announcement.endAt = b.endAt;
    if (b.status !== undefined) {
      const validStatuses: AnnouncementPatch['status'][] = ['DRAFT', 'PUBLISHED', 'ACTIVE', 'SCHEDULED', 'EXPIRED', 'ARCHIVED'];
      if (!validStatuses.includes(b.status)) badRequest('Invalid announcement status.');
      announcement.status = b.status;
    }
    announcement.createdAt = announcement.createdAt; // stable
    pushAudit('ANNOUNCEMENT_UPDATED', 'announcement', announcement.id, { applied: b });
    return { announcement };
  }

  // ---- analytics ----
  if (M === 'GET' && path === '/admin/analytics') {
    const range = params.get('range') ?? '30d';
    if (range !== '7d' && range !== '30d' && range !== '90d') badRequest('range must be 7d, 30d or 90d.');
    return makeAnalytics(range);
  }

  // ---- audit logs ----
  if (M === 'GET' && path === '/admin/audit-logs') {
    const adminId = (params.get('adminId') ?? '').trim().toLowerCase();
    const action = params.get('action');
    const from = params.get('from');
    const to = params.get('to');
    let filtered = auditLog;
    if (adminId) filtered = filtered.filter((l) => l.adminId.toLowerCase().includes(adminId) || (l.adminEmail ?? '').toLowerCase().includes(adminId));
    if (action) filtered = filtered.filter((l) => l.action === action);
    if (from) {
      // Accept full ISO strings (what the SPA sends) as well as date-only.
      const fromTs = from.includes('T') ? new Date(from).getTime() : new Date(`${from}T00:00:00`).getTime();
      if (!Number.isNaN(fromTs)) filtered = filtered.filter((l) => new Date(l.createdAt).getTime() >= fromTs);
    }
    if (to) {
      const toTs = to.includes('T') ? new Date(to).getTime() : new Date(`${to}T23:59:59`).getTime();
      if (!Number.isNaN(toTs)) filtered = filtered.filter((l) => new Date(l.createdAt).getTime() <= toTs);
    }
    const page = paginate(filtered, params);
    return { auditLogs: page.list, nextCursor: page.nextCursor };
  }

  // ---- security events ----
  if (M === 'GET' && path === '/admin/security-events') {
    const severity = params.get('severity');
    let filtered = securityEvents;
    if (severity) filtered = filtered.filter((e) => e.severity === severity);
    const page = paginate(filtered, params);
    return { securityEvents: page.list, nextCursor: page.nextCursor };
  }

  // ---- support tickets ----
  if (M === 'GET' && path === '/admin/support/tickets') {
    const status = params.get('status');
    let filtered = tickets;
    if (status) filtered = filtered.filter((t) => t.status === status);
    const sorted = filtered.slice().sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
    const page = paginate(sorted, params);
    return { tickets: page.list, nextCursor: page.nextCursor };
  }
  const ticketMatch = path.match(/^\/admin\/support\/tickets\/([^/]+)$/);
  if (M === 'PATCH' && ticketMatch) {
    const ticket = tickets.find((t) => t.id === ticketMatch[1]);
    if (!ticket) notFound('Ticket');
    const b = bodyAs<TicketPatch>(body);
    if (b.status !== undefined) {
      const valid: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'];
      if (!valid.includes(b.status)) badRequest('Invalid ticket status.');
      ticket.status = b.status;
    }
    if (b.response !== undefined) {
      if (typeof b.response !== 'string' || b.response.trim().length < 3) validationFailed('response must be at least 3 characters.');
      ticket.responses = [
        ...(ticket.responses ?? []),
        { id: `${ticket.id}_r${(ticket.responses ?? []).length + 1}`, authorEmail: currentAdminEmail, body: b.response.trim(), createdAt: new Date().toISOString() },
      ];
    }
    ticket.updatedAt = new Date().toISOString();
    return { ticket };
  }

  // ---- app versions ----
  if (M === 'GET' && path === '/admin/app-versions') {
    return appVersionPolicy;
  }
  if (M === 'POST' && path === '/admin/app-versions') {
    const b = bodyAs<AppVersionPolicy>(body);
    if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(String(b.minimum ?? ''))) validationFailed('minimum must be a semantic version like 1.0.0.');
    if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(String(b.latest ?? ''))) validationFailed('latest must be a semantic version like 1.2.0.');
    if (semverCompare(String(b.minimum), String(b.latest)) > 0) validationFailed('minimum cannot be greater than latest.');
    const before = { minimum: appVersionPolicy.minimum, latest: appVersionPolicy.latest, forceUpdate: appVersionPolicy.forceUpdate };
    appVersionPolicy = {
      minimum: String(b.minimum),
      latest: String(b.latest),
      forceUpdate: b.forceUpdate === true,
      message: typeof b.message === 'string' ? b.message : '',
      updatedAt: new Date().toISOString(),
      updatedBy: currentAdminEmail,
    };
    pushAudit('APP_VERSION_UPDATED', 'app_version', 'policy', { before, after: { minimum: appVersionPolicy.minimum, latest: appVersionPolicy.latest, forceUpdate: appVersionPolicy.forceUpdate } });
    return appVersionPolicy;
  }

  // ---- system ----
  if (M === 'GET' && path === '/admin/system/health') {
    const res: SystemHealth = { worker: 'healthy', d1LatencyMs: randInt(3, 14), kv: 'healthy' };
    return res;
  }

  // ---- admin users ----
  if (M === 'GET' && path === '/admin/admin-users') {
    return { admins };
  }
  if (M === 'POST' && path === '/admin/admin-users') {
    const b = bodyAs<{ email?: unknown; name?: unknown; password?: unknown; role?: unknown }>(body);
    const email = typeof b.email === 'string' ? b.email.trim().toLowerCase() : '';
    const name = typeof b.name === 'string' ? b.name.trim() : '';
    const password = typeof b.password === 'string' ? b.password : '';
    const role = b.role;
    const roles: AdminRole[] = ['SUPER_ADMIN', 'ADMIN', 'SUPPORT', 'ANALYST', 'CONFIG_MANAGER', 'READ_ONLY'];
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) validationFailed('A valid email is required.');
    if (name.length < 2) validationFailed('name is required (min 2 characters).');
    if (password.length < 8) validationFailed('password must be at least 8 characters.');
    if (typeof role !== 'string' || !roles.includes(role as AdminRole)) badRequest('role must be one of the six defined roles.');
    if (admins.some((a) => a.email.toLowerCase() === email)) {
      throw new ApiError('CONFLICT', 'An admin with this email already exists.', rid(), 409);
    }
    const admin: AdminUser = {
      id: `adm_${String(admins.length + 1).padStart(3, '0')}`,
      email,
      name,
      role: role as AdminRole,
      createdAt: new Date().toISOString(),
      lastLoginAt: null,
    };
    admins.push(admin);
    pushAudit('ADMIN_CREATED', 'admin', admin.id, { email, role });
    return { admin };
  }

  // ---- v2.2 Phase D: plans + bKash payments ----
  if (M === 'GET' && path === '/admin/plans') {
    return { plans: plans.slice().sort((a, b) => a.sortOrder - b.sortOrder) };
  }
  const planMatch = path.match(/^\/admin\/plans\/([^/]+)$/);
  if (M === 'PATCH' && planMatch) {
    const plan = plans.find((p) => p.id === planMatch[1]);
    if (!plan) notFound('Plan');
    const b = bodyAs<PlanPatch>(body);
    if (b.priceMinor !== undefined) {
      if (!Number.isInteger(b.priceMinor) || b.priceMinor < 0 || b.priceMinor > 100_000_000) {
        validationFailed('priceMinor must be an integer between 0 and 100000000.');
      }
      plan.priceMinor = b.priceMinor;
    }
    if (b.durationDays !== undefined) {
      if (!Number.isInteger(b.durationDays) || b.durationDays < 1 || b.durationDays > 400) {
        validationFailed('durationDays must be an integer between 1 and 400.');
      }
      plan.durationDays = b.durationDays;
    }
    if (b.isActive !== undefined) plan.isActive = b.isActive;
    if (b.isPopular !== undefined) plan.isPopular = b.isPopular;
    if (typeof b.displayName === 'string' && b.displayName.length > 0) plan.displayName = b.displayName;
    if (b.description !== undefined) plan.description = b.description;
    if (b.sortOrder !== undefined) plan.sortOrder = b.sortOrder;
    pushAudit('PLAN_UPDATED', 'subscription_plan', plan.id, { priceMinor: plan.priceMinor, durationDays: plan.durationDays, isActive: plan.isActive });
    return { plan };
  }

  if (M === 'GET' && path === '/admin/payments/bkash') {
    const status = params.get('status');
    let filtered = bkashPayments;
    if (status) filtered = filtered.filter((p) => p.status === status);
    const sorted = filtered.slice().sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
    const page = paginate(sorted, params);
    return {
      payments: page.list,
      nextCursor: page.nextCursor,
      summary: {
        inReview: bkashPayments.filter((p) => p.status === 'IN_REVIEW').length,
        verified: bkashPayments.filter((p) => p.status === 'VERIFIED').length,
        verifiedAmountMinor: bkashPayments
          .filter((p) => p.status === 'VERIFIED')
          .reduce((sum, p) => sum + p.amountMinor, 0),
      },
    };
  }
  const verifyMatch = path.match(/^\/admin\/payments\/bkash\/([^/]+)\/verify$/);
  if (M === 'POST' && verifyMatch) {
    const payment = bkashPayments.find((p) => p.id === verifyMatch[1]);
    if (!payment) notFound('Payment');
    if (payment.status === 'VERIFIED') {
      throw new ApiError('CONFLICT', 'Payment already verified.', rid(), 409);
    }
    if (payment.status !== 'IN_REVIEW') {
      throw new ApiError('CONFLICT', `Only IN_REVIEW payments can be verified (current: ${payment.status}).`, rid(), 409);
    }
    payment.status = 'VERIFIED';
    payment.reviewedAt = new Date().toISOString();
    pushAudit('BKASH_PAYMENT_VERIFIED', 'bkash_payment', payment.id, { trxId: payment.trxId, grantedDays: payment.planDays });
    return { payment, subscription: { id: `sub_mock_${payment.id}`, expiryDate: new Date(Date.now() + (payment.planDays ?? 30) * DAY).toISOString() } };
  }
  const rejectMatch = path.match(/^\/admin\/payments\/bkash\/([^/]+)\/reject$/);
  if (M === 'POST' && rejectMatch) {
    const payment = bkashPayments.find((p) => p.id === rejectMatch[1]);
    if (!payment) notFound('Payment');
    if (payment.status === 'VERIFIED') {
      throw new ApiError('CONFLICT', 'Verified payments cannot be rejected.', rid(), 409);
    }
    if (payment.status === 'REJECTED') {
      throw new ApiError('CONFLICT', 'Payment already rejected.', rid(), 409);
    }
    const b = bodyAs<{ reason?: unknown }>(body);
    const reason = typeof b.reason === 'string' ? b.reason.trim() : '';
    if (reason.length < 3) validationFailed('reason must be at least 3 characters.');
    payment.status = 'REJECTED';
    payment.rejectReason = reason;
    payment.reviewedAt = new Date().toISOString();
    pushAudit('BKASH_PAYMENT_REJECTED', 'bkash_payment', payment.id, { trxId: payment.trxId, reason });
    securityEvents.unshift({
      id: `sec_${String(securityEvents.length + 1).padStart(4, '0')}`,
      userId: payment.userId,
      userEmail: payment.userEmail,
      type: 'BKASH_PAYMENT_REJECTED',
      severity: 'MEDIUM',
      metadata: { paymentId: payment.id, trxId: payment.trxId, reason },
      createdAt: new Date().toISOString(),
    });
    return { payment };
  }

  throw new ApiError('NOT_FOUND', `No mock handler for ${M} ${path}`, rid(), 404);
}

function semverCompare(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) < (pb[i] ?? 0) ? -1 : 1;
  }
  return 0;
}

function published(): StoredConfig {
  const p = configStore.find((c) => c.version === publishedVersion);
  if (!p) throw new ApiError('SERVER_ERROR', 'Mock published config missing', rid(), 500);
  return p;
}

// ---------------------------------------------------------------------------
// Exported transport
// ---------------------------------------------------------------------------

export async function mockRequest<T>(method: string, fullPath: string, body?: unknown): Promise<T> {
  await delay(120 + Math.random() * 280);
  const [path, queryString] = fullPath.split('?');
  const params = new URLSearchParams(queryString ?? '');
  return route(method, path, params, body) as T;
}
