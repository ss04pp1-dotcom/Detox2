# R14 — v2.7.1: CI-Green Cleanup + Commits Posting Restored

Two goals: make the `ci` workflow green for the first time (it has been
red on every push since the repo existed — the mobile `flutter analyze`
gate exits 1 on any diagnostic), and close a real product gap found
during that cleanup.

## 1. Analyzer: 86 → 0 diagnostics

`flutter analyze --no-pub` is now completely clean (exit 0), so the CI
mobile job passes. Breakdown of what was fixed:

- **67 auto-fixes** (`dart fix --apply`): `prefer_const_constructors`
  (56), `prefer_const_declarations`, `unnecessary_import`,
  `dangling_library_doc_comments`, `deprecated_member_use` imports,
  `unnecessary_string_interpolations`,
  `prefer_const_literals_to_create_immutables` — pure const-migration
  and import hygiene across 27 files, no behavior change.
- **`use_build_context_synchronously`** (community referral share
  button): the async-gap `ScaffoldMessenger.of(context)` was guarded by
  the State's `mounted` but referenced the build-method context
  parameter — now guarded by `context.mounted` directly.
- **`invalid_use_of_visible_for_testing_member`** (5 sites):
  `ApiClient.lastErrorCode` / `lastErrorMessage` carried
  `@visibleForTesting` since v2.5.7 despite being documented and used as
  runtime UI surfaces (account / community / support screens show them).
  The annotations were simply wrong — removed (and the now-unused
  `foundation.dart` import in `api_client.dart`).
- **Dead code removed**: unused `_fmt` in lock-my-phone and
  prime-commit screens, unused `_error` field in the bKash screen,
  unused `p` local in onboarding, unused `_windowLabels` const and
  write-only `_displayMode` field in the community leaderboard tab.
- **`Radio.groupValue`/`onChanged` deprecated after v3.32**: companion
  persona picker migrated to the `RadioGroup<String>` ancestor pattern.
- **`unawaited_return_in_try_block`** (flagged by CI's newer SDK):
  the 401-retry in `ApiClient._send` now awaits the recursive call so a
  failing second pass flows through the method's own catch — callers
  keep the "never throws, returns null on failure" contract.

## 2. Community Commits: posting restored (real feature gap)

While removing the "unused `_create`" warning I found the cause: the
New Commit sheet (`_NewCommitSheet`) and the `createCommunityCommit`
API have existed since v2.5.9, but **no UI ever called them** — the
Commits feed was read-only for our own users in every shipped version.

- The Commits tab now has a **"New Commit" extended FAB** (disabled
  while busy) that opens the existing sheet — duration, reason,
  anonymous toggle — and posts to the feed.

## 3. CI: Flutter pinned to the verified toolchain

`ci.yml` mobile job now pins `flutter-version: 3.44.9` (the version the
release APK is built and verified with) instead of floating
`latest stable` — the gate was also red because newer SDKs ship new
lint rules. Pinned = deterministic: what passes locally passes in CI.

## Version

- `versionName` 2.7.1 / `versionCode` 27 (pubspec `2.7.1+27`,
  `constants.appVersion` 2.7.1).

## No changes

Worker, admin, Android Kotlin enforcement, database schema — untouched.
