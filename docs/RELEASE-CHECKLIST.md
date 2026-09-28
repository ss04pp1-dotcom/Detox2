# MAXLEVEL DETOX — Release Checklist (v2.5.7)

Every item here is a BLOCKER for the next Play Store upload. Work top to
bottom; each section is self-contained.

---

## 1. Signing-key incident recovery (audit C-1 — CRITICAL)

**What happened:** `mobile/android/key.properties` (with the literal
`storePassword`/`keyPassword`) and `mobile/android/app/mld-release.keystore`
were committed to this repository. Anyone who ever cloned/pulled the repo
can sign APKs with the app's exact identity (push fake updates to your
install base, distribute malicious clones).

**Status in this fix round:** both files are removed from the tree,
`.gitignore` already covered them, and `key.properties.example` documents
the template. That stops NEW exposure — it does NOT undo history.

**Required actions before the next release:**

1. Generate a FRESH upload keystore (see `mobile/android/key.properties.example`).
2. In Play Console → Setup → App signing, run **"Request key upgrade"**
   (Play's key-rotation process) so the old leaked key stops being accepted
   for updates. Google holds the app-signing key; only the upload key needs
   rotation unless you manage the app-signing key yourself — then rotate BOTH.
3. Purge the old files from git history:
   `git filter-repo --path mobile/android/key.properties --path mobile/android/app/mld-release.keystore --invert-paths`
   then force-push and have every collaborator re-clone (or at minimum
   rebase + verify with `git log --all --full-history -- '*keystore*'`).
4. Move the new credentials into CI secrets (GitHub Actions → Secrets) —
   never onto a laptop disk that gets backed up to a repo.
5. Treat the old password (`MaxLevelDetox2026!`) as burned everywhere it
   was ever used.

## 2. Google Play sensitive-permission declarations (audit K-6)

The app requests: Accessibility, Notification Listener, Device Admin,
Overlay, Usage Stats, `SCHEDULE_EXACT_ALARM`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Play's "Usage of Device
permissions" declaration form is **mandatory** for this combination.

Prepare BEFORE uploading:

- [ ] Permissions declaration form (Play Console → App content →
      Sensitive permissions) with a per-permission justification:
      *Accessibility* = enforcement blocking core; *Notification Listener* =
      notification guard; *Device Admin* = lock-my-phone; *Usage Stats* =
      dual-engine fallback monitor; *Overlay* = block wall.
- [ ] A short screen-recording demo video of each permission being granted
      and doing its enforcement job (Play reviewers ask for these).
- [ ] Exact-alarm justification: the app is a screen-time/alarm product —
      the Shockwave Alarm fires user-scheduled wakeups. In code the
      graceful fallback already exists (inexact `setWindow` when
      `canScheduleExactAlarms()` is false — ShockwaveAlarmEngine.kt), so
      the app keeps working if the declaration is rejected; still write
      the justification.
- [ ] Data-safety form: server stores email (Google sign-in), device
      model/manufacturer/Android version, enforcement analytics events,
      purchase tokens, support tickets. No content, no node text, no
      keystrokes (TRD §115).
- [ ] Account-deletion policy link in the store listing (the in-app flow
      exists: Settings → Account → Delete; the server purge job now
      actually completes it — audit C-4).

## 3. AdMob configuration (audit L-5)

The build defaults to Google's PUBLIC SAMPLE AdMob App ID
(`ca-app-pub-3940256099942544~3347511713`) and sample rewarded unit.
Shipping with the sample ID means **no ads and no revenue** in production
(it is test inventory only).

- [ ] `./gradlew assembleRelease -PMLD_ADMOB_APP_ID=ca-app-pub-XXXX~YYYY`
- [ ] `flutter build apk --dart-define=MLD_REWARDED_UNIT=ca-app-pub-XXXX/YYYY`
- [ ] After the first release build, grep the merged manifest for the
      sample App ID to prove it is gone:
      `grep -r "3940256099942544" build/app/outputs/ || echo CLEAN`

## 4. Backend secrets & environment (worker/README.md §3 recap)

- [ ] Rotate the seed super-admin password (`adm_seed_superadmin`) BEFORE
      first production login — the route refuses it outside development,
      but rotate anyway.
- [ ] `wrangler secret put GOOGLE_CLIENT_ID` (real OAuth Web client id).
- [ ] `wrangler secret put ADMIN_SESSION_SECRET` (32+ random bytes).
- [ ] `wrangler secret put GOOGLE_PLAY_SA_EMAIL` + `GOOGLE_PLAY_SA_PRIVATE_KEY`
      (Play Developer API service account) or subscription verify stays 503.
- [ ] Apply DB migrations: `wrangler d1 migrations apply DB --env production`
      (004_r10_fixes.sql adds admin name/lockout columns, users.referral_code,
      community moderation columns; **005_r11_features.sql** (v2.5.8) adds the
      detection_rules / leaderboard_profiles / clubs / club_members tables —
      new tables only, safe on existing deployments, and seeds the PUBLISHED
      detection-rules v1 doc).
- [ ] Optional: `wrangler secret put AI_API_KEY` (Sinthia server-LLM layer;
      the per-user daily quota now bounds the spend).

## 5. applicationId note (audit L-5)

The Android `applicationId` is deliberately obfuscated: `com.maxleveldet0x`
(zero instead of an "o"). It is set in `mobile/android/app/build.gradle`
and MUST match `PACKAGE_NAME` in `worker/src/routes/app.ts` — a mismatch
makes Play answer 404 for every purchase lookup. Keep both in sync, and
quote the exact spelling in support macros.

## 6. Pre-upload smoke test

- [ ] `cd worker && npm run types && npm test`
- [ ] `cd admin && npm run typecheck && npm run build`
- [ ] `cd mobile && flutter analyze` (warnings budget: 0 new)
- [ ] `cd mobile/android && ./gradlew assembleDebug`
- [ ] Fresh install → onboarding → pact → session start/complete.
- [ ] Sign in with Google → paywall → restore purchases.
- [ ] Settings → Report a Problem → ticket appears in admin queue → reply
      shows in the app (pull refresh).
