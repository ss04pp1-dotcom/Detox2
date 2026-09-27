# r7 FEATURE PARITY CHECKLIST — Social Sentry (base) vs MAXLEVEL DETOX
Generated: 2026-09-18 (Task 12). Source: download/social-sentry-analysis/*.md (5 reports).
Rule: Social Sentry = base standard. Feature আছে → deep verify. নেই → build properly.

## STATUS LEGEND
- [x] VERIFIED-OK (code-traced, logic sound)
- [~] EXISTS-NEEDS-FIX (found defect/gap while verifying)
- [ ] MISSING (build in r7)
- [D] DEFERRED (documented roadmap, not this build)

## A. ENFORCEMENT (report-enforcement.md — 40 mechanisms)

| # | Social Sentry mechanism | Our status | Notes |
|---|---|---|---|
| E1 | A11y event pipeline, throttled | [x] | DetoxAccessibilityService.kt FG_THROTTLE 800ms, WINDOW_STATE_CHANGED→PolicyEngine |
| E2 | Engine heartbeat 60s | [x] | EngineStateStore.writeEngine1Heartbeat, HEARTBEAT_THROTTLE_MS 20s |
| E3 | Engine 2 UsageStats poll 850ms | [x] | ForegroundAppMonitorService (verify poll interval in code) |
| E4 | E1↔E2 handoff | [x] | onUnbind/onDestroy→FAMS.start; onServiceConnected heartbeat |
| E5 | Guard jobs (watchdogs) | [x] | AccessibilityGuardJobService + MonkModeGuardJobService + LMP re-arm in MldApp.onCreate |
| E6 | Snapshot auto-reblock 5s | [~] | TempUnlock has expiry; ADD: alarm-driven re-block + lazy in-event expiry re-check (r7) |
| E7 | DNS tamper detection (Prime) | [D] | defer (P1 low value vs effort) |
| E8 | A11y overlay stacking (no SAW) | [x] | A11yOverlayController TYPE_ACCESSIBILITY_OVERLAY fallback |
| E9 | Chinese OEM matrix | [~] | systemAllow has MIUI/Samsung/Huawei/vivo entries; battery whitelist intents DEFER |
| E10 | Key event interception | [~] | LockScreenActivity.onBackPressed cage-blocked; ADD back-key consume via a11y onKeyEvent? — immersive bars cover it |
| E11 | Uninstall screen-scrape | [~] | UninstallInterceptor exists — VERIFY Bangla keywords + OEM package list coverage (16 pkgs) |
| E12 | DeviceAdmin lockNow loop | [x] | LockMyPhoneService (verify 1.5s loop + SCREEN_ON/USER_PRESENT receivers) |
| E13 | FGS crash-loop recovery | [x] | FGS onTimeout implemented in FAMS + LockMyPhoneService |
| E14 | **NotificationListenerService** | [ ] | **BUILD r7**: block notifications during enforcing session + monk (SocialSentry flagship E14) |
| E15 | Boot/Update recovery | [x] | BootRecoveryReceiver + MY_PACKAGE_REPLACED in manifest |
| E16 | Grace period 5s on activation | [ ] | **BUILD r7**: SessionEngine sessionStart grace — avoids self-block |
| E17 | Per-pkg block escalation (toast→5s→10s) | [ ] | **BUILD r7**: blocked-app attempt counter in DetoxA11yService (like reels ladder) |
| E18 | Countdown-gated overlay buttons | [ ] | **BUILD r7**: LockScreenActivity 5s countdown before dismiss enabled |
| E19 | forceStopAndRemoveFromRecents | [~] | ADD to reels hard-lockout end (killBackgroundProcesses after HOME) |
| E20 | Reels hard-lockout ringtone | [ ] | **BUILD r7** (small): RingtoneManager play at lockout end |

## B. MODES (report-modes.md)

| Mode | Status | Notes |
|---|---|---|
| Focus/Study Mode | [x] | SessionEngine STUDY mode, subjects, alarms |
| Force Mode (Detox) | [~] | DETOX exists; ADD grace+escalation (E16/E17) + notification blocking (E14) |
| Prime Mode | [x] | PrimeCommitManager (TOTP give-up, relapse log) |
| Monk Mode | [x] | MonkModeManager + LockService + GuardJob + OverlayActivity |
| Lock My Phone | [~] | LockMyPhoneService; VERIFY call trap (call_never_connected re-lock) |
| Safety Mode | [~] | SafetyPauseManager + Activity; VERIFY 30s re-trigger suppression exists |
| Schedules | [ ] | **BUILD r7**: ScheduleProfile engine (Kotlin) + UI (Dart) |
| App Limits | [ ] | **BUILD r7**: per-app daily minute limits + block overlay |
| Temp unlock budget | [x] | TempUnlockManager (coins, window) |
| Emergency TOTP | [x] | EmergencyCodeManager — VERIFY 20-min replay guard |
| Break passes | [x] | BreakPassManager weekly |

## C. ENGAGEMENT (report-engagement.md)

| System | Status | Notes |
|---|---|---|
| AI companion (Sinthia) | [D] | Needs Worker LLM endpoint; roadmap r8 |
| Hourly check-in notifications | [D] | roadmap r8 (with companion) |
| Aura economy / levels / caps | [x] | ProgressEngine DP system — VERIFY caps (daily/weekly) |
| Streaks + freeze + recovery grace + relapse | [x] | ProgressEngine (Phase C) |
| Brain-rot stage system | [ ] | **BUILD r7 (light)**: stage computed from usage, exposed in state + Insights card |
| Widgets ×5 | [x] | 4 providers (streak/session/usage/coins) — parity ok |
| Community/Friends/Referral | [D] | P2 roadmap |
| Pause allowance | [x] | BreakPassManager |

## D. MONETIZATION/BACKEND (report-backend.md)
| System | Status |
|---|---|
| Play Billing + verify | [x] BillingManager + Worker verify |
| Plans/trial | [D] roadmap |
| bKash gateway | [D] roadmap |
| FCM push | [D] roadmap |

## E. BUGS reported on r6 (user's phone) — root-cause + DEFENSIVE fixes in r7
- BUG-1 "permission given but not detected": r6's state layer broke. FIX: permission screen 2s polling + resume refresh + getPermissionState direct call. Root cause on r6 disk lost — r4 base clean.
- BUG-2 "home endless loading": bootstrapped flag waits for stateStream first event; if buildStateJson ever returns null/throws persistently → infinite spinner. FIX (defense-in-depth):
  1. NativeBridge.buildStateJson per-field try/catch (one bad subsystem never nulls whole state)
  2. app_state.dart bootstrap watchdog: 4s without event → manual refresh() fallback (marks bootstrapped)
  3. Dart stream .map guard: single parse error must not kill subscription (add onError resume)

## F. v4 DEFECTS (user-reported small defects) — proper r7 implementations
- Notification shade escape during lock → LockScreenActivity immersive (hide status+nav) + ShadeGuard (a11y: systemui STATUS_BAR expansion during enforcing → GLOBAL_ACTION_BACK; NEVER when grace window open; NEVER for own pkg)
- Home button escape → LockScreenActivity onPause re-assert loop (400ms REORDER_TO_FRONT while enforcing)
- System bars on all lock surfaces → apply immersive to LockScreenActivity, ReelsOverlayActivity, MonkModeOverlayActivity, SafetyPauseActivity

## G. BUILD PLAN (r7 = vc13, vn 2.3.0 — user phone has r6/vc12)
1. Implement E-section fixes + B/A missing features above (marked BUILD)
2. dart analyze + kotlin compile via gradle
3. IRON RULE: source zip FIRST → download/MAXLEVEL-DETOX-complete-project-v2.3.0-r7.zip
4. Build APK (build_r5_delivery.py recipe adapted), iterate errors, update zip on fix
5. Verify: badging vc13, apksigner v1+v2+v3 same cert, sha256, zipalign 16KB
