import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import '../core/constants.dart';
import 'models.dart';

/// A structured error returned by the native layer (TRD §74).
class NativeError implements Exception {
  const NativeError(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => 'NativeError($code): $message';
}

/// Result envelope for every MethodChannel call. Flutter must never assume
/// native success (TRD §75) — every call returns success or a typed error.
class NativeResult<T> {
  const NativeResult.ok(this.data) : error = null;
  const NativeResult.err(this.error) : data = null;

  final T? data;
  final NativeError? error;

  bool get isOk => error == null;
}

/// Typed wrapper around the `com.maxleveldetox/native` MethodChannel and the
/// `com.maxleveldetox/statestream` EventChannel.
///
/// SECURITY (TRD §41/§42): this bridge is a *requester*, never an authority.
/// `stopSession`, `requestTempUnlock`, `executeBailout`, `awardAdCoin` are all
/// re-validated natively (session state, coin balance, policy, unlock window)
/// before anything happens.
class NativeBridge {
  NativeBridge._();

  static final NativeBridge instance = NativeBridge._();

  static const MethodChannel _methods = MethodChannel(AppConstants.nativeChannel);
  static const EventChannel _stateStream = EventChannel(AppConstants.nativeStateStream);
  static const EventChannel _billingStream =
      EventChannel('com.maxleveldetox/billing');

  Stream<DeviceState>? _stateStreamCached;
  Stream<Map<dynamic, dynamic>>? _purchaseStreamCached;

  /// Live device state pushed by Kotlin: on every state change AND every
  /// second while a session/cage/unlock is running (UI clock).
  Stream<DeviceState> get stateStream {
    // v2.5.5 audit fix: a malformed frame on the EventChannel used to
    // surface as a stream error (the raw `as Map` cast threw inside .map)
    // — skip bad frames instead so one corrupt push can never kill the
    // state stream for the rest of the process. (Stream has no whereType —
    // a transformer is the canonical skip-frames shape.)
    return _stateStreamCached ??= _stateStream
        .receiveBroadcastStream()
        .transform<DeviceState>(
          StreamTransformer<dynamic, DeviceState>.fromHandlers(
            handleData: (raw, sink) {
              if (raw is! Map) return; // skip malformed frame
              try {
                sink.add(
                    DeviceState.fromJson(Map<dynamic, dynamic>.from(raw)));
              } catch (_) {
                // corrupt frame — skipped, never propagated as an error
              }
            },
          ),
        );
  }

  /// Completed Play purchases pushed by Kotlin. Flutter forwards each one
  /// to the Worker verify endpoint — the client never grants itself PRO.
  Stream<Map<dynamic, dynamic>> get purchaseStream {
    return _purchaseStreamCached ??= _billingStream
        .receiveBroadcastStream()
        .map((raw) => Map<dynamic, dynamic>.from(raw as Map));
  }

  // ---------------------------------------------------------------------
  // Low-level call helpers
  // ---------------------------------------------------------------------

  Future<NativeResult<Map<dynamic, dynamic>?>> call(
    String method, [
    Map<String, dynamic>? args,
  ]) async {
    try {
      final raw = await _methods.invokeMethod<dynamic>(method, args);
      if (raw is Map) {
        final map = Map<dynamic, dynamic>.from(raw);
        if (map['success'] == false) {
          return NativeResult.err(NativeError(
            map['errorCode'] as String? ?? 'UNKNOWN',
            map['message'] as String? ?? 'Native call failed',
          ));
        }
        return NativeResult.ok(map['data'] as Map<dynamic, dynamic>? ?? const {});
      }
      return NativeResult.ok(const {});
    } on PlatformException catch (e) {
      return NativeResult.err(NativeError(e.code, e.message ?? 'platform error'));
    } catch (e) {
      return NativeResult.err(NativeError('CHANNEL_ERROR', e.toString()));
    }
  }

  // ---------------------------------------------------------------------
  // Onboarding / pact
  // ---------------------------------------------------------------------

  Future<bool> completeOnboarding() async {
    final r = await call('completeOnboarding');
    return r.isOk;
  }

  Future<bool> acceptPact() async {
    final r = await call('acceptPact');
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // Sessions (Study / Detox)
  // ---------------------------------------------------------------------

  Future<NativeResult<DeviceState>> startSession({
    required String mode, // STUDY | DETOX
    required int durationMinutes,
    required String strictness,
    required List<String> allowedPackages,
    required List<String> blockedCategories,
    String subject = '',
  }) async {
    final r = await call('startSession', {
      'mode': mode,
      'durationMinutes': durationMinutes,
      'strictness': strictness,
      'allowedPackages': allowedPackages,
      'blockedCategories': blockedCategories,
      'subject': subject,
    });
    return r.isOk ? NativeResult.ok(_stateOf(r.data)) : NativeResult.err(r.error);
  }

  // Study break + subjects (v2.5 r9.4) -----------------------------------

  /// Pause enforcement for [minutes] (1..10). Bounded natively: Study mode
  /// only, 3 breaks per session, auto-resumes. Returns null on success or
  /// the native error message.
  Future<String?> pauseSession(int minutes) async {
    final r = await call('pauseSession', {'minutes': minutes});
    return r.isOk ? null : (r.error?.message ?? 'Could not start the break.');
  }

  Future<String?> resumeSession() async {
    final r = await call('resumeSession');
    return r.isOk ? null : (r.error?.message ?? 'Could not resume.');
  }

  /// {subjects: [String], dayKey: String, today: [{name, seconds}]}
  Future<Map<String, dynamic>> getStudySubjects() async {
    final r = await call('getStudySubjects');
    if (!r.isOk) return const {'subjects': [], 'today': []};
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// Returns null on success or an error message.
  Future<String?> addStudySubject(String name) async {
    final r = await call('addStudySubject', {'name': name});
    return r.isOk ? null : (r.error?.message ?? 'Could not add the subject.');
  }

  Future<void> removeStudySubject(String name) async {
    await call('removeStudySubject', {'name': name});
  }

  /// Ask the engine to end the session. Native only honors this when the
  /// legitimate end time has been reached — otherwise SESSION_NOT_COMPLETE.
  Future<NativeResult<DeviceState>> requestStop() async {
    final r = await call('stopSession');
    return r.isOk ? NativeResult.ok(_stateOf(r.data)) : NativeResult.err(r.error);
  }

  Future<NativeResult<DeviceState>> executeBailout() async {
    final r = await call('executeBailout');
    return r.isOk ? NativeResult.ok(_stateOf(r.data)) : NativeResult.err(r.error);
  }

  Future<NativeResult<Map<dynamic, dynamic>>> validateBailout() async {
    final r = await call('validateBailout');
    return r.isOk
        ? NativeResult.ok(Map<dynamic, dynamic>.from(r.data ?? const {}))
        : NativeResult.err(r.error);
  }

  // ---------------------------------------------------------------------
  // Temporary unlock
  // ---------------------------------------------------------------------

  Future<NativeResult<DeviceState>> requestTempUnlock(List<String> packages) async {
    final r = await call('requestTempUnlock', {'packages': packages});
    return r.isOk ? NativeResult.ok(_stateOf(r.data)) : NativeResult.err(r.error);
  }

  // ---------------------------------------------------------------------
  // Coins
  // ---------------------------------------------------------------------

  /// Idempotent ad reward: `rewardKey` is a unique per-callback id generated
  /// by Flutter; Kotlin inserts it into the immutable ledger with a unique
  /// constraint, so a replayed callback cannot mint coins (TRD §63/§64).
  Future<NativeResult<bool>> awardAdCoin(String rewardKey) async {
    final r = await call('awardAdCoin', {'rewardKey': rewardKey});
    if (r.isOk) {
      return NativeResult.ok(r.data?['awarded'] as bool? ?? false);
    }
    return NativeResult.err(r.error);
  }

  Future<List<CoinTransaction>> getCoinTransactions({int limit = 100}) async {
    final r = await call('getCoinTransactions', {'limit': limit});
    if (!r.isOk) return const [];
    final list = r.data?['transactions'] as List<dynamic>? ?? const [];
    return list
        .map((e) => CoinTransaction.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  /// v2.5.7 (C-3): apply server-originated coin grants (admin adjustments,
  /// bonuses, refunds) to the immutable device ledger. Idempotent by
  /// transactionId — returns the number of NEW grants applied.
  Future<int> applyServerCoinGrants(List<Map<dynamic, dynamic>> grants) async {
    if (grants.isEmpty) return 0;
    final r = await call('applyServerCoinGrants', {
      'grantsJson': jsonEncode(grants),
    });
    if (!r.isOk) return 0;
    return (r.data?['applied'] as num?)?.toInt() ?? 0;
  }

  /// v2.5.7 (K-3): real device identity for /devices/register —
  /// {manufacturer, model, androidVersion, apiLevel} from Build.*.
  Future<Map<String, dynamic>> getDeviceInfo() async {
    final r = await call('getDeviceInfo');
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// v2.5.7 (K-4): open this app's Play Store page (market:// with the
  /// https://play.google.com fallback), used by the forced-update dialog.
  Future<void> openPlayStorePage() async {
    await call('openPlayStorePage');
  }

  // ---------------------------------------------------------------------
  // Shorts blocker
  // ---------------------------------------------------------------------

  Future<bool> setShortsEnabled(bool enabled) async {
    final r = await call('setShortsEnabled', {'enabled': enabled});
    return r.isOk;
  }

  Future<bool> setShortsPlatform(String packageName, bool enabled) async {
    final r = await call('setShortsPlatform', {
      'packageName': packageName,
      'enabled': enabled,
    });
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // App rules
  // ---------------------------------------------------------------------

  Future<List<AppRule>> getAppRules() async {
    final r = await call('getAppRules');
    if (!r.isOk) return const [];
    final list = r.data?['apps'] as List<dynamic>? ?? const [];
    return list.map((e) {
      final m = Map<dynamic, dynamic>.from(e as Map);
      return AppRule(
        packageName: m['packageName'] as String? ?? '',
        appName: m['appName'] as String? ?? '',
        category: m['category'] as String? ?? 'unknown',
        blocked: m['blocked'] as bool? ?? false,
      );
    }).toList();
  }

  Future<bool> setAppRule(String packageName, bool blocked) async {
    final r = await call('setAppRule', {'packageName': packageName, 'blocked': blocked});
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // Permissions
  // ---------------------------------------------------------------------

  Future<PermissionSummary> getPermissionState() async {
    final r = await call('getPermissionState');
    if (!r.isOk) return DeviceState.empty.permissions;
    return PermissionSummary.fromJson(Map<dynamic, dynamic>.from(r.data ?? const {}));
  }

  /// Opens the exact Android settings screen for a capability. Native may
  /// open a short, logged settings-grace window while a session is active.
  Future<bool> openPermissionSettings(String key) async {
    final r = await call('openPermissionSettings', {'key': key});
    return r.isOk;
  }

  Future<bool> requestIgnoreBatteryOptimizations() async {
    final r = await call('requestIgnoreBatteryOptimizations');
    return r.isOk;
  }

  /// Opens the OEM autostart / background-power screen for this device
  /// (Xiaomi, OPPO/Realme, Vivo, Tecno/Infinix/itel, Huawei, Samsung …).
  /// Returns a short label of what was opened, or null on failure.
  Future<String?> openOemBackgroundSettings() async {
    final r = await call('openOemBackgroundSettings');
    if (!r.isOk) return null;
    return r.data?['opened'] as String?;
  }

  // ---------------------------------------------------------------------
  // Usage / history / stats
  // ---------------------------------------------------------------------

  Future<List<UsageStat>> getUsageStats() async {
    final r = await call('getUsageStats');
    if (!r.isOk) return const [];
    final list = r.data?['usage'] as List<dynamic>? ?? const [];
    return list
        .map((e) => UsageStat.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  Future<List<HistoryEntry>> getHistory({int limit = 60}) async {
    final r = await call('getHistory', {'limit': limit});
    if (!r.isOk) return const [];
    final list = r.data?['history'] as List<dynamic>? ?? const [];
    return list
        .map((e) => HistoryEntry.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  Future<WeeklyStats> getWeeklyStats() async {
    final r = await call('getWeeklyStats');
    if (!r.isOk) {
      return const WeeklyStats(
        focusSeconds: 0,
        detoxSeconds: 0,
        blockedAttempts: 0,
        sessionsCompleted: 0,
        sessionsPlanned: 0,
        streakDays: 0,
        dailyFocus: [],
        dailyDetox: [],
      );
    }
    return WeeklyStats.fromJson(Map<dynamic, dynamic>.from(r.data ?? const {}));
  }

  Future<List<ViolationRecord>> getViolations({int limit = 50}) async {
    final r = await call('getViolations', {'limit': limit});
    if (!r.isOk) return const [];
    final list = r.data?['violations'] as List<dynamic>? ?? const [];
    return list
        .map((e) => ViolationRecord.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  // ---------------------------------------------------------------------
  // Alarm
  // ---------------------------------------------------------------------

  Future<bool> scheduleAlarm(AlarmConfig alarm) async {
    final r = await call('scheduleAlarm', alarm.toWire());
    return r.isOk;
  }

  Future<bool> cancelAlarm(String alarmId) async {
    final r = await call('cancelAlarm', {'alarmId': alarmId});
    return r.isOk;
  }

  Future<List<AlarmConfig>> getAlarms() async {
    final r = await call('getAlarms');
    if (!r.isOk) return const [];
    final list = r.data?['alarms'] as List<dynamic>? ?? const [];
    return list
        .map((e) => AlarmConfig.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  // ---------------------------------------------------------------------
  // Remote config (fetched by the API client, validated natively)
  // ---------------------------------------------------------------------

  /// Kotlin validates schema/types/ranges and clamps to frozen bounds before
  /// caching (PRD §51). Invalid payloads are rejected, not merged.
  Future<bool> applyRemoteConfig(String configJson) async {
    final r = await call('applyRemoteConfig', {'configJson': configJson});
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // v2.5.8 roadmap — dynamic detection rules (server-pushed reels/shorts
  // signatures; Kotlin validates strictly and keeps compiled defaults on
  // any rejection)
  // ---------------------------------------------------------------------

  Future<bool> applyDetectionRules(String rulesJson) async {
    final r = await call('applyDetectionRules', {'rulesJson': rulesJson});
    return r.isOk;
  }

  Future<int> detectionRulesVersion() async {
    final r = await call('getDetectionRulesStatus');
    return (r.data?['version'] as num?)?.toInt() ?? 0;
  }

  /// v2.5.8 roadmap: DP mirror payload for the community leaderboard —
  /// {lifetimeDp, weekDp, weekAnchor, monthDp, monthAnchor, streakDays,
  ///  levelName} straight from the native ProgressEngine (the DP authority).
  Future<Map<String, dynamic>?> getLeaderboardSnapshot() async {
    final r = await call('getLeaderboardSnapshot');
    if (!r.isOk || r.data == null) return null;
    return Map<String, dynamic>.from(r.data!);
  }

  // ---------------------------------------------------------------------
  // v2.5.9 (r11.1) — opportunity cost + distraction trend (user-requested)
  // ---------------------------------------------------------------------

  /// Weekly opportunity-cost snapshot ("ei shomoy kaje lagale eita hoto"
  /// numbers: yearly days lost, books, taka, reels skipped). `enabled`
  /// false = quiet week, nothing to show.
  Future<Map<String, dynamic>> getOpportunityCost() async {
    final r = await call('getOpportunityCost');
    if (!r.isOk || r.data == null) return {'enabled': false};
    return Map<String, dynamic>.from(r.data!);
  }

  /// 7-day distraction trend series (oldest first) for the Insights chart:
  /// [{date, distractingMinutes, reelsBlocked, focusMinutes}].
  Future<List<Map<String, dynamic>>> getDistractionTrend() async {
    final r = await call('getDistractionTrend');
    if (!r.isOk) return const [];
    final days = r.data?['days'] as List<dynamic>? ?? const [];
    return days
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  // ---------------------------------------------------------------------
  // Emergency
  // ---------------------------------------------------------------------

  /// Opens the dialer (never a restricted action — PRD §27).
  Future<void> openEmergencyDialer() async {
    await call('emergencyCall');
  }

  // ---------------------------------------------------------------------
  // v2.1 Phase C — Progress layer (gamification)
  // ---------------------------------------------------------------------

  /// Full progress snapshot (levels, streaks, protection, check-in cycle).
  Future<NativeResult<ProgressSnapshot>> getProgress() async {
    final r = await call('getProgress');
    if (!r.isOk) return NativeResult.err(r.error);
    final raw = r.data;
    if (raw == null) return NativeResult.err(const NativeError('NO_DATA', 'Empty progress payload'));
    return NativeResult.ok(
        ProgressSnapshot.fromJson(Map<dynamic, dynamic>.from(raw)));
  }

  /// Claim today's check-in. Returns awarded DP, or an error when already
  /// claimed / unavailable.
  Future<NativeResult<int>> claimCheckIn() async {
    final r = await call('claimCheckIn');
    if (!r.isOk) return NativeResult.err(r.error);
    return NativeResult.ok((r.data?['awarded'] as num?)?.toInt() ?? 0);
  }

  /// Recent DP award ledger rows.
  Future<NativeResult<List<DpAwardRecord>>> getDpHistory({int limit = 30}) async {
    final r = await call('getDpHistory', {'limit': limit});
    if (!r.isOk) return NativeResult.err(r.error);
    final rows = r.data?['awards'] as List<dynamic>? ?? const [];
    return NativeResult.ok(rows
        .map((e) => DpAwardRecord.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList());
  }

  /// Relapse history rows.
  Future<NativeResult<List<RelapseRecord>>> getRelapseHistory({int limit = 20}) async {
    final r = await call('getRelapseHistory', {'limit': limit});
    if (!r.isOk) return NativeResult.err(r.error);
    final rows = r.data?['relapses'] as List<dynamic>? ?? const [];
    return NativeResult.ok(rows
        .map((e) => RelapseRecord.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList());
  }

  // ---------------------------------------------------------------------
  // v2.2 Phase D — growth & monetization
  // ---------------------------------------------------------------------

  /// Weekly break-pass status (allowance, usage, active window).
  Future<BreakPassStatus> getBreakPassStatus() async {
    final r = await call('getBreakPassStatus');
    if (!r.isOk) {
      return BreakPassStatus.fromJson(const {});
    }
    return BreakPassStatus.fromJson(Map<dynamic, dynamic>.from(r.data ?? const {}));
  }

  /// Spend one weekly break pass on a package (5-minute window).
  Future<NativeResult<BreakPassStatus>> useBreakPass(String packageName) async {
    final r = await call('useBreakPass', {'package': packageName});
    if (!r.isOk) return NativeResult.err(r.error);
    return NativeResult.ok(
        BreakPassStatus.fromJson(Map<dynamic, dynamic>.from(r.data ?? const {})));
  }

  /// End the active break window early (honesty is free).
  Future<BreakPassStatus> endBreakPassEarly() async {
    final r = await call('endBreakPassEarly');
    if (!r.isOk) return BreakPassStatus.fromJson(const {});
    return BreakPassStatus.fromJson(Map<dynamic, dynamic>.from(r.data ?? const {}));
  }

  /// Play Billing product catalog (empty when billing is unavailable).
  Future<List<PlayProductInfo>> getBillingProducts() async {
    final r = await call('getBillingProducts');
    if (!r.isOk) return const [];
    final list = r.data?['products'] as List<dynamic>? ?? const [];
    return list
        .map((e) => PlayProductInfo.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  /// Launch the Play purchase sheet. Verify happens server-side afterwards.
  /// v2.5.7 (W-7): launchPurchase now optionally carries the signed-in
  /// account id so the purchase is bound to the account at launch time
  /// (obfuscatedExternalAccountId) and the Worker can verify ownership.
  Future<bool> launchPurchase(String productId, {String? accountId}) async {
    final r = await call('launchPurchase', {
      'productId': productId,
      if (accountId != null && accountId.isNotEmpty) 'accountId': accountId,
    });
    return r.isOk;
  }

  /// Restore previous Play purchases (re-emits them for server verification).
  Future<bool> restorePurchases() async {
    final r = await call('restorePurchases');
    return r.isOk;
  }

  /// Widget pinning state (any pinned? launcher supports pinning?).
  Future<Map<String, dynamic>> getWidgetStatus() async {
    final r = await call('getWidgetStatus');
    if (!r.isOk) return {'anyPinned': false, 'pinSupported': false};
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// Ask the launcher to pin a widget ('streak' | 'session' | 'usage' | 'coins').
  Future<bool> pinWidget(String which) async {
    final r = await call('pinWidget', {'which': which});
    return r.isOk;
  }

  /// Force-refresh all pinned widgets.
  Future<bool> updateWidgets() async {
    final r = await call('updateWidgets');
    return r.isOk;
  }

  /// Forward Worker announcements so native can post unseen ones locally.
  Future<bool> deliverAnnouncements(List<AnnouncementItem> items) async {
    final payload = jsonEncode(items.map((a) => {
          'id': a.id,
          'title': a.title,
          'body': a.body,
          'type': a.type,
        }).toList());
    final r = await call('deliverAnnouncements', {'announcementsJson': payload});
    return r.isOk;
  }

  /// Lazy daily-insight check (the exact alarm is the primary driver).
  Future<bool> checkDailyInsight() async {
    final r = await call('checkDailyInsight');
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // v2.3 r7 — App limits / schedules / notification guard
  // ---------------------------------------------------------------------

  /// True when the user granted Notification Listener access.
  Future<bool> isNotificationListenerGranted() async {
    final r = await call('getNotificationGuard');
    if (!r.isOk) return false;
    return r.data?['granted'] as bool? ?? false;
  }

  /// Notification-guard toggle + access in one payload.
  Future<({bool granted, bool enabled})> getNotificationGuard() async {
    final r = await call('getNotificationGuard');
    if (!r.isOk) return (granted: false, enabled: true);
    return (
      granted: r.data?['granted'] as bool? ?? false,
      enabled: r.data?['enabled'] as bool? ?? true,
    );
  }

  Future<bool> setNotificationGuardEnabled(bool enabled) async {
    final r = await call('setNotificationGuardEnabled', {'enabled': enabled});
    return r.isOk;
  }

  Future<List<AppLimitEntry>> getAppLimits() async {
    final r = await call('getAppLimits');
    if (!r.isOk) return const [];
    final list = r.data?['limits'] as List<dynamic>? ?? const [];
    return list
        .map((e) => AppLimitEntry.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  /// minutes == 0 removes the limit.
  Future<bool> setAppLimit(String packageName, int minutes) async {
    final r = await call('setAppLimit', {'package': packageName, 'minutes': minutes});
    return r.isOk;
  }

  Future<List<ScheduleProfileInfo>> getSchedules() async {
    final r = await call('getSchedules');
    if (!r.isOk) return const [];
    final list = r.data?['schedules'] as List<dynamic>? ?? const [];
    return list
        .map((e) => ScheduleProfileInfo.fromJson(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  Future<bool> saveSchedule(ScheduleProfileInfo schedule) async {
    final r = await call('saveSchedule', {
      'id': schedule.id,
      'name': schedule.name,
      'startMinute': schedule.startMinuteOfDay,
      'endMinute': schedule.endMinuteOfDay,
      'days': schedule.days.toList(),
      'packages': schedule.blockedPackages.toList(),
      'enabled': schedule.enabled,
    });
    return r.isOk;
  }

  Future<bool> deleteSchedule(String id) async {
    final r = await call('deleteSchedule', {'id': id});
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // v2.2.1 — Diagnostics (System Health)
  // ---------------------------------------------------------------------

  /// Full native self-report: identity, permissions (incl. the exact
  /// accessibility component Android has enabled), storage probes, engine
  /// status and the recent native error buffer.
  Future<Map<String, dynamic>?> getSystemReport() async {
    final r = await call('getSystemReport');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// Tail of the in-memory enforcement decision log (DiagLog).
  Future<List<String>> getDiagLog() async {
    final r = await call('getDiagLog');
    if (!r.isOk) return const [];
    final list = r.data?['lines'] as List<dynamic>? ?? const [];
    return list.map((e) => e.toString()).toList();
  }

  // ---------------------------------------------------------------------
  // Debug-only tools (native refuses these in release builds, TRD §139)
  // ---------------------------------------------------------------------

  Future<bool> debugFastForward(int minutes) async {
    if (!assertionsEnabled) return false;
    final r = await call('debugFastForward', {'minutes': minutes});
    return r.isOk;
  }

  Future<bool> debugSimulateShorts() async {
    if (!assertionsEnabled) return false;
    final r = await call('debugSimulateShorts');
    return r.isOk;
  }

  Future<bool> debugResetData() async {
    if (!assertionsEnabled) return false;
    final r = await call('debugResetData');
    return r.isOk;
  }

  Future<bool> debugAwardDp(String reason, int amount) async {
    if (!assertionsEnabled) return false;
    final r = await call('debugAwardDp', {'reason': reason, 'amount': amount});
    return r.isOk;
  }

  Future<bool> debugResetProgress() async {
    if (!assertionsEnabled) return false;
    final r = await call('debugResetProgress');
    return r.isOk;
  }

  // ---------------------------------------------------------------------
  // v2.5 r9 — Discipline engines (Lock My Phone / Monk / Prime / Safety /
  // Emergency codes / device admin)
  // ---------------------------------------------------------------------

  Future<Map<String, dynamic>?> getEngineStatus() async {
    final r = await call('getEngineStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> getGuardStatus() async {
    final r = await call('getGuardStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<bool> requestDeviceAdmin() async {
    final r = await call('requestDeviceAdmin');
    return r.isOk;
  }

  Future<bool> isDeviceAdminActive() async {
    final r = await call('getDeviceAdminState');
    if (!r.isOk) return false;
    // Native replies with `adminActive`. (This used to read `active`, which
    // never exists — the Lock My Phone screen always believed Device Admin
    // was OFF, so its start button and duration chips stayed disabled.)
    return (r.data?['adminActive'] ?? r.data?['active']) as bool? ?? false;
  }

  Future<Map<String, dynamic>?> startLockMyPhone({
    required int minutes,
    String reason = 'user_initiated',
  }) async {
    final r = await call('startLockMyPhone', {
      'durationMinutes': minutes,
      'reason': reason,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> getLockMyPhoneStatus() async {
    final r = await call('getLockMyPhoneStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  // Scheduled / recurring lock windows (v2.5 r9.3).
  Future<List<Map<String, dynamic>>> getLockSchedules() async {
    final r = await call('getLockSchedules');
    if (!r.isOk) return const [];
    final list = r.data?['schedules'] as List<dynamic>? ?? const [];
    return list
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  /// Returns null on success, or a human-readable error message.
  Future<String?> saveLockSchedule(Map<String, dynamic> schedule) async {
    final r = await call('saveLockSchedule', schedule);
    return r.isOk ? null : (r.error?.message ?? 'Could not save the schedule.');
  }

  /// Returns null on success, or a human-readable error message.
  Future<String?> deleteLockSchedule(String id) async {
    final r = await call('deleteLockSchedule', {'id': id});
    return r.isOk ? null : (r.error?.message ?? 'Could not delete the schedule.');
  }

  Future<bool> stopLockMyPhoneValidated() async {
    final r = await call('stopLockMyPhoneValidated');
    return r.isOk;
  }

  Future<Map<String, dynamic>?> activateMonkMode({
    required List<String> allowedPackages,
    int durationMinutes = 120,
    String goal = 'Deep work',
  }) async {
    final r = await call('activateMonkMode', {
      'allowedPackages': allowedPackages,
      'durationMinutes': durationMinutes,
      'goal': goal,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> getMonkModeStatus() async {
    final r = await call('getMonkModeStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// v2.5.5 audit fix: Monk Mode previously had NO deactivation path — the
  /// active screen promised "deactivate from here" but neither the bridge
  /// nor the UI had any way to do it (lock-in until the 120-min timer).
  /// Kotlin handles "deactivateMonkMode" (INVALID_REQUEST when not active,
  /// SYSTEM_RESTRICTION when the engine refuses).
  Future<NativeResult<Map<dynamic, dynamic>>> deactivateMonkMode() async {
    final r = await call('deactivateMonkMode');
    return r.isOk
        ? NativeResult.ok(Map<dynamic, dynamic>.from(r.data ?? const {}))
        : NativeResult.err(r.error);
  }

  Future<bool> setSafetyPauseEnabled({required bool enabled}) async {
    final r = await call('setSafetyPauseEnabled', {'enabled': enabled});
    return r.isOk;
  }

  Future<bool> setSafetyPauseApps({required List<String> packages}) async {
    final r = await call('setSafetyPauseApps', {'apps': packages});
    return r.isOk;
  }

  Future<bool> setSafetyPauseSeconds({required int seconds}) async {
    final r = await call('setSafetyPauseSeconds', {'seconds': seconds});
    return r.isOk;
  }

  Future<Map<String, dynamic>?> getSafetyPauseStatus() async {
    final r = await call('getSafetyPauseStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  /// Enrolls the user into TOTP emergency codes; returns the initial code
  /// set (only shown once) or null on failure.
  Future<Map<String, dynamic>?> enrollEmergencyCodes() async {
    final r = await call('enrollEmergencyCodes');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> getEmergencyCodeStatus() async {
    final r = await call('getEmergencyCodeStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> activatePrimeCommit({
    required int hours,
    String title = 'Prime commit',
  }) async {
    final r = await call('activatePrimeCommit', {
      'commitHours': hours,
      'title': title,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> giveUpPrimeCommit({String? code}) async {
    final r = await call('giveUpPrimeCommit', {
      if (code != null) 'code': code,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> getPrimeCommitStatus() async {
    final r = await call('getPrimeCommitStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  // ---------------------------------------------------------------------
  // v2.5 r9 — Reels extras
  // ---------------------------------------------------------------------

  Future<Map<String, dynamic>?> getReelsStatus() async {
    final r = await call('getReelsStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<bool> setReelsDailyLimitMinutes({required int minutes}) async {
    final r = await call('setReelsDailyLimitMinutes', {'minutes': minutes});
    return r.isOk;
  }

  Future<Map<String, dynamic>?> useReelsEmergencyPass(String pkg) async {
    final r = await call('useReelsEmergencyPass', {'package': pkg});
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  // ---------------------------------------------------------------------
  // v2.5 r9 — Social Sentry parity: tasks / brain rot / companion
  // ---------------------------------------------------------------------

  Future<Map<String, dynamic>?> getTasks() async {
    final r = await call('getTasks');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> addTask({
    required String title,
    String note = '',
    int priority = 2,
    String category = 'general',
    bool routine = false,
  }) async {
    final r = await call('addTask', {
      'title': title,
      'note': note,
      'priority': priority,
      'category': category,
      'routine': routine,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<bool> addSubtask({
    required String taskId,
    required String title,
  }) async {
    final r = await call('addSubtask', {
      'taskId': taskId,
      'title': title,
    });
    return r.isOk;
  }

  Future<bool> toggleSubtask({
    required String taskId,
    required String subtaskId,
  }) async {
    final r = await call('toggleSubtask', {
      'taskId': taskId,
      'subtaskId': subtaskId,
    });
    return r.isOk;
  }

  Future<bool> completeTask({required String taskId}) async {
    final r = await call('completeTask', {'taskId': taskId});
    return r.isOk;
  }

  Future<bool> reopenTask({required String taskId}) async {
    final r = await call('reopenTask', {'taskId': taskId});
    return r.isOk;
  }

  Future<bool> deleteTask({required String taskId}) async {
    final r = await call('deleteTask', {'taskId': taskId});
    return r.isOk;
  }

  Future<Map<String, dynamic>?> getBrainRotStatus() async {
    final r = await call('getBrainRotStatus');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<bool> snoozeBrainRot() async {
    final r = await call('snoozeBrainRot');
    return r.isOk;
  }

  Future<Map<String, dynamic>?> getCompanionConfig() async {
    final r = await call('getCompanionConfig');
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<Map<String, dynamic>?> setCompanionConfig({
    String? name,
    String? personality,
    bool? roastMode,
  }) async {
    final r = await call('setCompanionConfig', {
      if (name != null) 'name': name,
      if (personality != null) 'personality': personality,
      if (roastMode != null) 'roastMode': roastMode,
    });
    if (!r.isOk) return null;
    return Map<String, dynamic>.from(r.data ?? const {});
  }

  Future<List<Map<String, dynamic>>> getCompanionHistory() async {
    final r = await call('getCompanionHistory');
    if (!r.isOk) return const [];
    final list = r.data?['messages'] as List<dynamic>? ?? const [];
    return list
        .map((e) => Map<String, dynamic>.from(Map<dynamic, dynamic>.from(e)))
        .toList();
  }

  Future<bool> appendCompanionMessage({
    required String role,
    required String content,
  }) async {
    final r = await call('appendCompanionMessage', {
      'role': role,
      'content': content,
    });
    return r.isOk;
  }

  Future<bool> clearCompanionHistory() async {
    final r = await call('clearCompanionHistory');
    return r.isOk;
  }

  /// One-shot flag set when a Sinthia check-in notification is tapped.
  Future<bool> consumeOpenCompanion() async {
    final r = await call('consumeOpenCompanion');
    if (!r.isOk) return false;
    return r.data?['pending'] as bool? ?? false;
  }

  /// v2.5.5 audit fix: drain the native AnalyticsOut queue (events recorded
  /// by the Kotlin engine while offline). Envelope:
  ///   {events: [{type: String, payload: Map}]}
  /// The events are forwarded to the Worker's /events endpoint by main.dart
  /// via ApiClient.enqueueEvent (which adds the eventId/occurredAt fields
  /// the worker requires).
  Future<List<Map<String, dynamic>>> drainAnalyticsEvents() async {
    final r = await call('drainAnalyticsEvents');
    if (!r.isOk) return const [];
    final list = r.data?['events'] as List<dynamic>? ?? const [];
    return list.map((e) {
      final m = Map<String, dynamic>.from(e as Map);
      final payload = m['payload'];
      return <String, dynamic>{
        'type': m['type']?.toString() ?? '',
        'payload': payload is Map
            ? Map<String, dynamic>.from(payload)
            : <String, dynamic>{},
      };
    }).toList();
  }

  // ---------------------------------------------------------------------

  DeviceState _stateOf(Map<dynamic, dynamic>? data) {
    final raw = data?['state'] as Map<dynamic, dynamic>? ?? data ?? const {};
    return DeviceState.fromJson(Map<dynamic, dynamic>.from(raw));
  }
}

/// True only in debug/profile builds (kDebugMode semantics without importing
/// foundation in this file).
bool get assertionsEnabled {
  bool ok = false;
  assert(() {
    ok = true;
    return true;
  }());
  return ok;
}
