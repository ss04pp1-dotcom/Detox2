# MAXLEVEL DETOX — v2.5.8 Audit-Fix + Roadmap Delivery

This is the fully audited and repaired build of MAXLEVEL-DETOX-complete-project-v2_5_5-r9_5. Every layer (Kotlin enforcement engine, Flutter UI, Cloudflare Worker backend, React admin panel) received a line-by-line audit, and every CRITICAL/MAJOR finding plus the safe MINOR findings were fixed. Full evidence trails live in `docs/audit/`.

**v2.5.8 adds the three product-roadmap features on top of the v2.5.7 fix
round** (details in `docs/R11-CHANGES.md`): the opt-in community DP
leaderboard + invite-code clubs (app tab + admin moderation page), the
interactive Distraction Trend home-screen widget (7-day screen-time vs
reels-skipped chart, tap to switch), and the dynamic remote detection-rule
config (server-pushed reels/shorts signatures — admin panel → worker →
15-min client sync → validated atomic swap in the detector — so a Facebook/
Instagram/YouTube UI rename never needs an app release). Versions moved
2.5.6 → 2.5.8 (versionCode 22); worker tests grew to 58.

## What's in this delivery

| Item | Location |
|---|---|
| Release APK (signed, arm64-v8a) | `app-release-v2.5.6-arm64.apk` (delivered alongside this zip) |
| Fixed project source | this zip (mobile/ + worker/ + admin/ + docs/) |
| Audit reports (4) | `docs/audit/report-{kotlin,flutter,worker,admin}.md` |
| Fix changelogs (4) | `docs/audit/fixes-{kotlin,flutter,worker,admin}.md` |

## Fix summary (see changelogs for the complete evidence)

- **Kotlin engine** — 3 CRITICAL / 12 MAJOR / 20 MINOR found; fixed: back-gesture dismissing enforcement walls, alarm weekday off-by-one, background FGS crash loop, setAppRule no-op, dead kiosk/DNS-tamper layers wired or removed, engine-2 stand-down inversion, listener/threading/leak cluster, AdMob id injectable, SinthiaCheckIn usage-scan rate, and more.
- **Flutter UI** — 1 CRITICAL / 8 MAJOR / 20 MINOR found; fixed: Google Sign-In entry point actually exists now (see below), remote-config pipeline wired end-to-end, coin-faucet bricking + reward race, bailout double-navigation + hold-to-confirm double-start, Monk deactivation path, account PRO-tier parse, ~18 minor hardening items.
- **Worker backend** — 7 MAJOR / 28 MINOR found; fixed: /auth/logout 401, Play PACKAGE_NAME mismatch (purchases could never verify), flag env-case mismatch, bKash double-grant races, subscription expiry cron, seed-admin lockout, refresh-replay race, LIKE-escape, admin logout route, timing-oracle equalization, etc.
- **Admin panel** — 5 CRITICAL / 11 MAJOR / 13 MINOR found; fixed: stale-filter refetch, audit/security/analytics wire-key crashes vs the real API, RemoteConfig draft crash loop, health vocabulary, mock/wire parity, plus display fields.

## Before you ship to production

1. **Google Sign-In needs your OAuth Web Client ID** (the app currently shows a configuration dialog):
   `flutter build apk --release --target-platform android-arm64 --dart-define=MLD_GOOGLE_SERVER_CLIENT_ID=<your-web-client-id>.apps.googleusercontent.com`
   The idToken audience must match the Worker's `GOOGLE_CLIENT_ID` secret (`wrangler secret put GOOGLE_CLIENT_ID`). Without sign-in, Play purchases can't be verified server-side and PRO is never granted.
2. **AdMob**: both the App ID (gradle `-PMLD_ADMOB_APP_ID=ca-app-pub-XXXX~YYYY`) and the rewarded unit (`--dart-define=MLD_REWARDED_UNIT=ca-app-pub-XXXX/YYYY`) still default to Google's public SAMPLE values — replace both before a Play release or the coin economy will have no ad fills.
3. **API base URL** defaults to `https://api.maxleveldetox.com/api/v1` — override per build with `--dart-define=MLD_API_BASE=https://<your-worker-host>/api/v1` and deploy the Worker (`cd worker && wrangler deploy`), run the D1 migrations (schema.sql + migrations 001-005), rotate the seed super-admin password (worker README §3), and set the secrets listed in `.dev.vars.example`.
4. **Keystore**: releases sign with `android/key.properties` + `android/app/mld-release.keystore` (present, unchanged from your original). Keep them private; losing them means losing update-in-place for `com.maxleveldet0x`.

## Building the APK yourself

Requirements: Flutter 3.44.x (project locks Dart ≥3.6 / Flutter ≥3.27), JDK 17+ (full JDK, not a JRE — jlink is needed), Android SDK platform 36 + build-tools 35.0.0.

```
cd mobile
flutter pub get
flutter build apk --release --target-platform android-arm64
# → build/app/outputs/flutter-apk/app-release.apk
```

Notes: the app has no native C/C++ code; the NDK is only needed by AGP to strip the Flutter engine's shipped-unstripped libflutter.so (163 MB → ~11 MB). On a normal machine AGP auto-installs the NDK on first build. The delivered APK is 2.5.6 / versionCode 21 and installs over any earlier r3+ build in place.

## Verification already performed

- `worker/`: `npx tsc --noEmit` strict — 0 errors (after fixes).
- `admin/`: `npm run build` (tsc + vite) — exit 0 (after fixes).
- `mobile/`: `flutter analyze` — 0 errors (3 pre-existing dead-code warnings documented); `flutter build apk --release` — BUILD SUCCESSFUL; APK verified with `apksigner` (release cert CN=MaxLevel Detox) and `aapt` (com.maxleveldet0x, versionCode 21, versionName 2.5.6, arm64-v8a native code).
