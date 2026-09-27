# MAXLEVEL DETOX — Security Model

> Honest claim: **«Maximum practical tamper-resistant enforcement within
> Android's permitted application capabilities.»**
> We never claim "100% unbypassable" — that claim would be false for any
> ordinary consumer Android app (PRD §58, TRD §144).

## 1. The one rule that makes everything else work

**Flutter renders. Kotlin enforces. The cloud manages the product.**

Every security-relevant decision happens in the native Kotlin layer and is
derived from persisted state:

```
Flutter UI ──request──▶ MethodChannel ──▶ native validation
                                              │
                                              ▼
                                   SystemClock.elapsedRealtime()
                                   DataStore + Room (persisted)
                                              │
                                              ▼
                                   PolicyEngine verdict
                                              ▼
                              Block / Allow / Warn / Cage
```

If Flutter lies, crashes, or is killed: nothing changes for enforcement.
If the network is gone: nothing changes. If the process dies: persisted
state + boot recovery restore enforcement on the next start.

## 2. Anti-bypass matrix (user's top requirement)

| Bypass attempt | Mechanism | Residual risk (honest) |
|---|---|---|
| Kill Flutter / swipe app away | Enforcement lives in Kotlin (`EnforcementService` + accessibility service); persisted state is the authority | None beyond process-death row below |
| Kill app process (force-stop from settings) | Session persists in DataStore; `MldApp.onCreate → recoverIfNeeded()` restores on next launch; `MY_PACKAGE_REPLACED`/boot receivers cover restarts | While the process stays force-stopped, accessibility events can't fire — enforcement resumes on next app open. Android does not let ordinary apps prevent this. |
| Reboot / power off | `BootRecoveryReceiver`: reads persisted session → still active → restores service + alarms. **A shutdown is never treated as completion** (PRD §26) | None for session continuity |
| Change system time / timezone | Timer = `SystemClock.elapsedRealtime()` (monotonic), persisted as start/end offsets. Wall clock is display-only (TRD §52) | None |
| Open blocked app via launcher / recents / deep link / share sheet / notification | The accessibility service evaluates the resulting FOREGROUND PACKAGE regardless of entry path (TRD §83) → lock screen + HOME action + violation | Sub-second window while the event propagates (Android event delivery latency) |
| Press back / home / recents to escape lock screen | Lock screen redirects home; re-entry into any restricted app re-triggers the detection loop (TRD §38) | None by design — enforcement is a loop, not a one-shot gate |
| Disable Accessibility mid-session | `PermissionMonitor` sweep (every 30s in the foreground service) → `PERMISSION_TAMPER` violation + session → `RECOVERY` + high-priority notification with restore path; unknown state never unlocks (TRD §114) | An ordinary app cannot silently re-enable accessibility; we detect + recover instead of pretending to prevent |
| Disable Usage Access / overlay / notifications | Same diff-and-react pattern; sessions refuse to START without core permissions (TRD §97) | As above |
| Reach Settings during a session | Settings package is policy-blocked; restoration happens through a **purpose-scoped, 3-minute, persisted grace window** — not an open door (TRD §84) | Settings is OS-controlled; we detect navigation, we cannot remove the screen |
| Uninstall the app | Device Admin / Device Owner is a separate deployment model (PRD §33/§85, out of v1 scope). Normal Mode restores full uninstall rights by design | Cannot be prevented by ordinary consumer installs — explicitly documented |
| Replay a rewarded-ad callback | Coins insert with deterministic PK `coin_<rewardKey>` + `INSERT OR IGNORE` → the second insert is a no-op (TRD §63/§64) | None |
| Double-tap bailout / unlock buttons | Native mutex + atomic Room spend; spend verifies `balance >= cost` before insert; one active session / one unlock window enforced | None |
| Fake coin balance from Flutter | Balance = `SUM(amount)` over the immutable ledger; the bridge exposes **no** method that adds arbitrary amounts | None |
| Stop the session via the bridge | `stopSession` succeeds only when `elapsedRealtime >= endElapsed`, else `SESSION_NOT_COMPLETE` (TRD §42) | None |
| Remote config weakens enforcement | Config is schema/type/range-validated and **clamped** to frozen bounds natively; active sessions keep their own policy snapshot (`policyVersion`); config can never unlock a session, mint coins or disable emergency access (PRD §35, §70) | None |
| Backend outage / offline | Everything above is local. The Worker only manages accounts/config/analytics. Fail-safe, never fail-open (PRD §50) | None for enforcement |
| Debug tools in release | `debug_*` bridge methods check `FLAG_DEBUGGABLE`; release APKs physically fail them (TRD §139) | None |
| Hardware power button / OEM power menu | Explicitly outside third-party app control (PRD §26) — session survives via reboot recovery | Acknowledged platform limit |
| OEM silently kills the process (battery "optimization") — **v2.0 Phase A1/A2** | Dual-engine redundancy: engine 1 (accessibility) writes a heartbeat; when it goes stale > 60s the persisted `AccessibilityGuardJobService` (JobScheduler job 9401, survives reboot) starts engine 2 (`ForegroundAppMonitorService`, UsageStats poll @850ms) and posts a "Protection degraded — tap to fix" notification. Engine 2 yields after engine 1 is healthy 30s. Guards self-disable after 3 fully-clean days (battery), any incident re-arms them | Engine 2 has ~1s latency and no shorts/content detection — degraded, not full enforcement; honest by design |
| Uninstall / force-stop / disable attempt via Settings — **v2.0 Phase A3** | While a session is enforcing, `UninstallInterceptor` scrapes events from 18 OEM settings/installer packages (MIUI, ColorOS, Vivo, Samsung, Huawei, OnePlus, Google Files…) for our app label + uninstall/force-stop keywords (EN + BN), fires `GLOBAL_ACTION_BACK` + delayed re-check, and after 2 attempts shows the blocking lock screen. Violation logged as `UNAUTHORIZED_UNLOCK` | Outside an enforcing session the user may freely uninstall — deliberately (their device, their right; forced otherwise would be malware behavior) |
| Escape lock-my-phone by unlocking with power button — **v2.0 Phase A4** | Device Admin (force-lock policy ONLY) + `DevicePolicyManager.lockNow()` loop @1.5s while interactive + SCREEN_ON/USER_PRESENT receivers instant re-lock + attempt logging; session is wall-clock-authoritative and survives reboot/OEM-kill via persisted state + boot recovery | Device admin can be removed from Settings; during a session the uninstall-interceptor path guards that screen, and removal mid-session is recorded (`adminStripped`) and surfaced — never silently continues |
| FGS restart storm / crash loop — **v2.0 Phase A5** | Every enforcement FGS start passes a persisted backoff gate: 40s × 2^n cap 15 min, max 6 attempts per 30-min window; a clean 5-minute run resets the counter; `onTimeout` (Android 14/15) degrades gracefully instead of crashing | After 6 failed attempts we escalate to the user (notification) rather than loop forever — honest degrade |
| Overlay permission revoked mid-session — **v2.0 Phase A6** | Blocking surface falls back to `TYPE_ACCESSIBILITY_OVERLAY` (a11y overlay stacking — needs NO `SYSTEM_ALERT_WINDOW`); stale overlays auto-remove on foreground change | Engine 2 (UsageStats) cannot use this path — it uses the activity-based lock screen |
| Reels feed inside an ALLOWED app (no session) — **v2.0 Phase B1/B2** | Per-app signature detection (YT `reel_watch_fragment_root`+`pivot_bar`, TikTok instant-home, FB BFS ≤500 nodes + text pre-check + debounce, FB Lite `video_view`, IG `root_clips_layout`/`clips_viewer_video_container`/`reel_recycler`, Chrome `url_bar` URL shapes) → escalation ladder: toast → soft overlay (1/2-min allowance unlocks) → 10s hard cooldown → ringtone + `killBackgroundProcesses`. Counters/quota persisted with daily date-key rollover; 60s consecutive reset; 3 emergency passes/day; daily allowance 5..120 min (default 30) | Signature drift across app updates (mitigated: partial-id fallback + multi-signal FB decision); hard cooldown is 10 fixed seconds by design — it breaks the autoplay loop, it does not trap |
| Escape monk mode — **v2.0 Phase B3** | No give-up flow exists: exits are duration expiry (wall-clock, SP-persisted, reboot-surviving) and the always-available emergency dialer. FSM {LOCKED, ALLOWED_APP} policed by 1.5s/800ms ticks (UsageEvents 5s window), DeviceAdmin `lockNow()` re-lock, full-screen no-dismiss activity re-asserted on SCREEN_ON/USER_PRESENT, persisted guard job (id 9402) + boot re-arm + onTaskRemoved alarm re-kick. BOTH engines enforce `MONK_BLOCK` (engine 2 polices without a session) | Device admin removal via Settings (guarded outside monk by the uninstall interceptor path only during sessions — during monk the overlay + UsageEvents police continues degraded); OEM launcher unknown to our lists is still allowed via default-launcher resolution |
| Abuse the reels allowance / emergency passes — **v2.0 Phase B2** | All quota state persisted with daily local-date rollover (not app-restart); unlock windows use `elapsedRealtime` (clock-change-proof); one window at a time; soft-unlock minutes decrement the allowance BEFORE the window opens | The allowance IS the designed escape hatch — 3 passes + N minutes per day is the honest budget, not a bypass |
| End a Prime commit early — **v2.0 Phase B5** | While active: temp unlock REFUSED (`PRIME_ACTIVE`), coin bailout REFUSED (session engine gate). The ONLY exit is `giveUpPrimeCommit(code)` = per-install TOTP (SecureRandom secret, RFC-6238, 6 digits, 300s step, ±1) + 20-min code-replay burn + mandatory `PRIME_GIVEUP` relapse violation recorded BEFORE the session ends (crash cannot lose it) + honest notification | A user who enrolled their secret in an authenticator can always give up — deliberately: commitment tools with no escape valve become hostageware; the price is the burn + the relapse record |
| Extract a master code from the APK (decompile attack) — **v2.0 Phase B5** | There is none. No shared/hardcoded emergency secret exists in any form (verified: `verify_phase_b.py` asserts the codebase contains no such token). Every gate uses the per-install secret generated on-device at enrollment | Future: server-side validation of the same TOTP factor once accounts ship |


| Farm Discipline Points by reopening reels apps — **v2.1 Phase C** | `REELS_BLOCKED` awards pass a 30-second per-reason throttle; every reason class is subject to daily/weekly/monthly caps (150/800/3000 default, remote-tunable inside frozen bounds); awards append to the `dp_awards` ledger and lifetime DP is re-derived from the ledger SUM at every process start (self-heal — state/ledger drift cannot mint phantom DP) | DP is progression-only: it can never buy unlocks, bailouts or time, so the worst case is cosmetic inflation, capped |
| Wipe progress to dodge a relapse / streak reset — **v2.1 Phase C** | Relapse events and the streak/DP state live in the user's own local storage; clearing app data resets progress but also removes enforcement state, onboarding and the pact — the same cost as abandoning the app. Debug reset tools are `BuildConfigDebug`-gated and do not exist in release builds | Progress is motivation infrastructure, not enforcement; honesty is preserved because relapses are recorded the moment they happen (prime give-up records BEFORE session end), not at sync time |
| Manipulate streak by clock changes — **v2.1 Phase C** | Streak day keys are local calendar dates computed from wall clock; rolling forward/back a whole day is indistinguishable from time-zone travel and is accepted (same trade-off every streak product makes); freeze allowance is keyed by calendar month and persisted; grace uses wall-clock deadlines persisted in DataStore | A user who fights their own streak forfeits the streak's psychological value — the only thing at stake is their own motivation |
| Use gamification state to weaken enforcement — **v2.1 Phase C** | Structural invariant: the progress layer is a parallel observer. `ProgressEngine` holds no references that can unlock, extend, shorten or soften any session, cage, monk lock, prime commit or reels escalation; its only writes are its own DataStore keys (`dp_state`, `streak_state`, `streak_protection_state`, `checkin_state`) and Room tables (`dp_awards`, `relapse_events`). All award hooks are try/catch-isolated fire-and-forget — a crashing progress layer cannot take enforcement down with it | Remote config can tune multipliers/caps and disable the layer, but no config value reaches any enforcement decision path (PRD §35 boundary) |
| Fake a Play purchase to unlock PRO — **v2.2 Phase D** | The client is never the entitlement authority: `BillingManager` only launches the purchase sheet and emits `{productId, purchaseToken}` on the `com.maxleveldetox/billing` EventChannel; Flutter forwards the pair to `POST /subscription/verify`, and the Worker re-validates the token with the Google Play Developer API (service-account signed JWT) before writing a subscription row. A rooted/faked client that fabricates the event gets nothing — verify fails server-side | Play Billing is a convenience surface; PRO gates depth (history/widget styles), never enforcement, so a forged entitlement cannot weaken blocking |
| Spoof or squat a bKash payment — **v2.2 Phase D** | References (`MLD-XXXXXXXX`) are server-generated with `randomToken` and UNIQUE — the client cannot choose one; amounts are always read from the server's `subscription_plans` row, never from the request; ≤3 open payments per user; TrxIDs are format-validated and reviewed by a human against the merchant statement before `verify` grants days; verify extends from the CURRENT expiry so paid days are never eaten; rejects are audited + MEDIUM security events | The gateway is manual by design (no third-party money API to exploit); the global `paymentsEnabled` kill-switch plus empty-number check disables it instantly |
| Double-claim the free trial — **v2.2 Phase D** | `trial_claims.device_id` is UNIQUE and server-enforced; claims are additionally refused while any ACTIVE subscription exists and blocked entirely by the `paymentsEnabled` kill-switch. Unregistered sessions bind to `uid_<userId>`, still one claim per account | Worst case is one extra trial per device-farm account — capped value, no enforcement impact |
| Abuse break passes to dismantle blocking — **v2.2 Phase D** | One 5-minute window at a time, weekly-capped (0..5, remote-bounded), per-package, lazily self-expiring on `elapsedRealtime` (clock-change-proof); REFUSED during sessions, cage, prime commits and monk mode; the a11y stand-down hook sits only on the out-of-session reels path | Break passes are the designed honesty valve (planned beats impulsive); they never touch sessions, monk, prime or emergency flows |
| Spam offer banners / notifications — **v2.2 Phase D** | Offer routing is pull-on-launch with a 24h dismiss cooldown (vs the competitor's 1h upsell loop); announcement delivery dedupes via a 50-ID seen ledger and posts at most 3 per batch; the daily insight fires once per calendar day at the configured hour and stays silent on quiet days; both surfaces respect `POST_NOTIFICATIONS` and `insightNudgeEnabled` | Attention-respect is the product promise; every growth surface is remote-disableable in one switch |
## 3. Coin economy integrity (immutable ledger)

- Single faucet: the ad SDK's `onUserEarnedReward` callback → `awardAd` with
  a unique per-callback key → exactly `+1`.
- Every spend (`TEMP_UNLOCK_SPEND`, `BAILOUT_SPEND`) is one atomic
  transaction: verify balance → insert negative row → recompute cache.
- `balanceAfter >= 0` enforced at the DB level; balance is reconstructable
  from the append-only `coin_transactions` table.
- Server-side (optional future mode): same ledger semantics in D1 with
  `ADMIN_ADJUSTMENT` audited; the admin API has **no** client-callable
  "add coins" endpoint.

## 4. Emergency access (hard boundary)

Dialer/contacts/telephony are `EMERGENCY_ALLOW` — evaluated **before** any
other rule so no future policy change can ever wall off emergency calling.
The native lock/alarm screens always expose an Emergency button (PRD §27).

### v2.0 Phase B5 — emergency CODES (the anti-backdoor contract)

- **Per-install TOTP only.** A 16-char Base32 secret (SecureRandom) is
  generated on-device at enrollment; the user scans it into any
  authenticator app once. Validation: RFC-6238, 6 digits, HmacSHA1,
  300-second step, ±1 step tolerance.
- **No shared secret exists anywhere in the product.** The reference app's
  hardcoded master secret (which unlocks every installation worldwide) is
  the exact failure mode this design refuses. `verify_phase_b.py` asserts
  the shipped code contains no such token.
- **Replay guard:** every accepted code is burned for 20 minutes,
  namespaced by purpose (`prime:<code>`), with a pruned ledger in
  DataStore.
- **Used by:** Prime-commit give-up (combined with a mandatory relapse
  record). Monk mode deliberately has NO code path — duration or
  emergency call only.
- Server-side validation of the same factor lands with accounts; the
  local secret then syncs as an enrolled factor, never as a master key.

## 5. Privacy boundaries

- Accessibility node text is scanned **in memory** for shorts signals and
  discarded. Persisted: `(package, signal-type, timestamp)` only (TRD §115).
- The Phase B reels detector matches view-ids / content-descriptions /
  the Chrome URL-bar text **in memory**; URLs and captions are never
  persisted, logged, or transmitted. The user keyword allowlist is
  matched against event text in memory only.
- No keystrokes, no message contents, no screen recordings, no raw trees.
- Analytics events are aggregate names + counters (PRD §41, §48).
- Backups never include enforcement state or accessibility content.

## 5b. Progress-layer privacy (v2.1 Phase C)

- The gamification layer processes no screen content and no package names
  beyond what enforcement already records; `dp_awards` rows store reason
  names, amounts and date keys only.
- Relapse reasons embed the prime-commit title the user themselves typed;
  both stay on-device (analytics events carry source + streak count only).
- Remote config for the layer is pacing-only; the server never receives
  DP, streak, freeze or grace state (no new sync surface in v2.1).

## 6. Backend security summary

- Frozen response envelope + `requestId` everywhere; no stack traces.
- 100% parameterized D1; append-only audit/security/coin tables (DB
  triggers abort UPDATE/DELETE).
- PBKDF2-SHA256 (100k iter) admin passwords; HMAC-signed KV admin
  sessions; role re-read from D1 every request (instant revocation).
- RBAC matrix (6 roles × 10 permissions) enforced server-side; the admin
  SPA contains zero secrets.
- Rate limits per frozen contract; CORS allowlist; CSP + HSTS + nosniff.
- Google ID-token verification (aud/exp/email_verified); Play purchases
  verified server-side via service-account JWT — client claims never
  trusted; unavailable credentials return honest `SERVICE_UNAVAILABLE`,
  never a fake success.
- Recommended: Cloudflare Access (MFA) in front of the admin domain.

## 7. What would make it even harder (future work)

1. **Device Owner / kiosk provisioning** — true uninstall prevention and
   kiosk-level control; a separate deployment model (TRD §85).
2. Server-backed coin ledger with signed reward receipts.
3. Play Integrity API attestation for high-risk flows.
4. Per-OEM hardening packs (MIUI autostart, Samsung put-app-to-sleep…).

## 5c. Monetization-layer privacy (v2.2 Phase D)

- The billing layer sends exactly three fields to the Worker:
  `productId`, `purchaseToken`, `platform` — no purchase history, no
  pricing cache, no account metadata.
- bKash payments store the reference, amount, TrxID and optional sender
  number the user themselves typed; nothing is inferred (no IP
  geolocation fallback like the competitor's ip-api.com flow, which was
  explicitly not ported).
- Announcement/offer delivery keeps only a 50-ID seen ledger locally;
  no engagement telemetry is attached.
- The insight nudge computes from `daily_stats` on-device; the
  notification body never leaves the phone.
