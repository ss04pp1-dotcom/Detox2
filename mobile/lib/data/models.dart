/// Typed models mirroring the Kotlin native layer's JSON contract.
///
/// The native side is the single source of truth for all enforcement state
/// (TRD §99). Flutter models are read-only projections of native state —
/// they never drive security decisions.
library;

enum SessionMode { study, detox, unknown }

enum SessionStatus {
  idle,
  armed,
  starting,
  active,
  tempUnlock,
  cage,
  completing,
  completed,
  bailout,
  recovery,
  error,
  paused,
  unknown;

  static SessionStatus fromName(String? name) {
    return SessionStatus.values.firstWhere(
      (s) => s.name.toUpperCase() == (name ?? '').toUpperCase(),
      orElse: () => SessionStatus.unknown,
    );
  }

  /// True when an enforcement session is live (PRD §8 state machine).
  bool get isEnforcing =>
      this == SessionStatus.active ||
      this == SessionStatus.tempUnlock ||
      this == SessionStatus.cage ||
      this == SessionStatus.recovery;
}

enum Strictness { balanced, strict, maxlevel }

enum ViolationType {
  blockedApp,
  shortsEntry,
  permissionTamper,
  restrictedSetting,
  unauthorizedUnlock,
  sessionTamper;

  static ViolationType fromName(String? name) {
    return ViolationType.values.firstWhere(
      (v) => v.name.toUpperCase() == (name ?? '').toUpperCase().replaceAll('_', ''),
      orElse: () => ViolationType.blockedApp,
    );
  }

  String get label {
    switch (this) {
      case ViolationType.blockedApp:
        return 'Blocked app';
      case ViolationType.shortsEntry:
        return 'Shorts attempt';
      case ViolationType.permissionTamper:
        return 'Permission changed';
      case ViolationType.restrictedSetting:
        return 'Restricted setting';
      case ViolationType.unauthorizedUnlock:
        return 'Unlock attempt';
      case ViolationType.sessionTamper:
        return 'Session tamper';
    }
  }
}

class SessionSnapshot {
  const SessionSnapshot({
    required this.id,
    required this.mode,
    required this.status,
    required this.remainingSeconds,
    required this.totalSeconds,
    required this.strictness,
    required this.policyVersion,
    required this.violationCount,
    required this.blockedAppCount,
    required this.allowedPackages,
    required this.blockedCategories,
    this.pauseRemainingSeconds = 0,
    this.pauseCount = 0,
    this.maxPauses = 3,
    this.subjectName = '',
  });

  final String id;
  final SessionMode mode;
  final SessionStatus status;
  final int remainingSeconds;
  final int totalSeconds;
  final Strictness strictness;
  final int policyVersion;
  final int violationCount;
  final int blockedAppCount;
  final List<String> allowedPackages;
  final List<String> blockedCategories;

  /// Study break (v2.5 r9.4): seconds until the current break auto-ends.
  final int pauseRemainingSeconds;
  final int pauseCount;
  final int maxPauses;
  final String subjectName;

  /// A session exists — enforcing OR on a study break.
  bool get isActive => status.isEnforcing || status == SessionStatus.paused;
  bool get isPaused => status == SessionStatus.paused;

  double get progress =>
      totalSeconds <= 0 ? 0 : (1 - (remainingSeconds / totalSeconds)).clamp(0.0, 1.0);

  String get modeLabel => mode == SessionMode.study ? 'STUDY MODE' : 'DETOX ACTIVE';

  factory SessionSnapshot.fromJson(Map<dynamic, dynamic> json) {
    return SessionSnapshot(
      id: json['id'] as String? ?? '',
      mode: (json['mode'] as String?)?.toLowerCase() == 'study' ? SessionMode.study : SessionMode.detox,
      status: SessionStatus.fromName(json['status'] as String?),
      remainingSeconds: (json['remainingSeconds'] as num?)?.toInt() ?? 0,
      totalSeconds: (json['totalSeconds'] as num?)?.toInt() ?? 0,
      strictness: Strictness.values.firstWhere(
        (s) => s.name == (json['strictness'] as String? ?? 'maxlevel').toLowerCase(),
        orElse: () => Strictness.maxlevel,
      ),
      policyVersion: (json['policyVersion'] as num?)?.toInt() ?? 1,
      violationCount: (json['violationCount'] as num?)?.toInt() ?? 0,
      blockedAppCount: (json['blockedAppCount'] as num?)?.toInt() ?? 0,
      allowedPackages: (json['allowedPackages'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      blockedCategories: (json['blockedCategories'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      pauseRemainingSeconds: (json['pauseRemainingSeconds'] as num?)?.toInt() ?? 0,
      pauseCount: (json['pauseCount'] as num?)?.toInt() ?? 0,
      maxPauses: (json['maxPauses'] as num?)?.toInt() ?? 3,
      subjectName: json['subjectName'] as String? ?? '',
    );
  }
}

class CageSnapshot {
  const CageSnapshot({required this.active, required this.remainingSeconds});

  final bool active;
  final int remainingSeconds;

  factory CageSnapshot.fromJson(Map<dynamic, dynamic> json) => CageSnapshot(
        active: json['active'] as bool? ?? false,
        remainingSeconds: (json['remainingSeconds'] as num?)?.toInt() ?? 0,
      );
}

class TempUnlockSnapshot {
  const TempUnlockSnapshot({
    required this.active,
    required this.remainingSeconds,
    required this.allowedPackages,
  });

  final bool active;
  final int remainingSeconds;
  final List<String> allowedPackages;

  factory TempUnlockSnapshot.fromJson(Map<dynamic, dynamic> json) =>
      TempUnlockSnapshot(
        active: json['active'] as bool? ?? false,
        remainingSeconds: (json['remainingSeconds'] as num?)?.toInt() ?? 0,
        allowedPackages: (json['allowedPackages'] as List<dynamic>? ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}

class ShortsState {
  const ShortsState({
    required this.enabled,
    required this.warningCount,
    required this.warningLimit,
    required this.platforms,
  });

  final bool enabled;
  final int warningCount;
  final int warningLimit;
  final List<ShortsPlatform> platforms;

  factory ShortsState.fromJson(Map<dynamic, dynamic> json) => ShortsState(
        enabled: json['enabled'] as bool? ?? true,
        warningCount: (json['warningCount'] as num?)?.toInt() ?? 0,
        warningLimit: (json['warningLimit'] as num?)?.toInt() ?? 5,
        platforms: (json['platforms'] as List<dynamic>? ?? const [])
            .map((e) => ShortsPlatform.fromJson(e as Map<dynamic, dynamic>))
            .toList(),
      );
}

class ShortsPlatform {
  const ShortsPlatform({
    required this.packageName,
    required this.appName,
    required this.enabled,
  });

  final String packageName;
  final String appName;
  final bool enabled;

  factory ShortsPlatform.fromJson(Map<dynamic, dynamic> json) => ShortsPlatform(
        packageName: json['packageName'] as String? ?? '',
        appName: json['appName'] as String? ?? '',
        enabled: json['enabled'] as bool? ?? true,
      );
}

class PermissionSummary {
  const PermissionSummary({
    required this.accessibility,
    required this.usageAccess,
    required this.overlay,
    required this.notifications,
    required this.exactAlarms,
    required this.batteryIgnored,
  });

  final bool accessibility;
  final bool usageAccess;
  final bool overlay;
  final bool notifications;
  final bool exactAlarms;
  final bool batteryIgnored;

  /// The permissions required before a strict session may start (TRD §97).
  bool get enforcementReady => accessibility && usageAccess && overlay && notifications;

  factory PermissionSummary.fromJson(Map<dynamic, dynamic> json) => PermissionSummary(
        accessibility: json['accessibility'] as bool? ?? false,
        usageAccess: json['usageAccess'] as bool? ?? false,
        overlay: json['overlay'] as bool? ?? false,
        notifications: json['notifications'] as bool? ?? false,
        exactAlarms: json['exactAlarms'] as bool? ?? false,
        batteryIgnored: json['batteryIgnored'] as bool? ?? false,
      );
}

/// Composite device state — the projection Flutter renders.
class DeviceState {
  const DeviceState({
    this.session,
    this.cage,
    this.tempUnlock,
    required this.shorts,
    required this.coins,
    required this.permissions,
    required this.onboardingComplete,
    required this.pactAccepted,
    this.progress,
  });

  final SessionSnapshot? session;
  final CageSnapshot? cage;
  final TempUnlockSnapshot? tempUnlock;
  final ShortsState shorts;
  final int coins;
  final PermissionSummary permissions;
  final bool onboardingComplete;
  final bool pactAccepted;
  final ProgressSummary? progress;

  bool get sessionActive => session?.isActive ?? false;
  bool get cageActive => cage?.active ?? false;

  factory DeviceState.fromJson(Map<dynamic, dynamic> json) {
    return DeviceState(
      session: json['session'] == null
          ? null
          : SessionSnapshot.fromJson(json['session'] as Map<dynamic, dynamic>),
      cage: json['cage'] == null ? null : CageSnapshot.fromJson(json['cage'] as Map<dynamic, dynamic>),
      tempUnlock: json['tempUnlock'] == null
          ? null
          : TempUnlockSnapshot.fromJson(json['tempUnlock'] as Map<dynamic, dynamic>),
      shorts: ShortsState.fromJson(json['shorts'] as Map<dynamic, dynamic>? ?? {}),
      coins: (json['coins'] as num?)?.toInt() ?? 0,
      permissions:
          PermissionSummary.fromJson(json['permissions'] as Map<dynamic, dynamic>? ?? {}),
      onboardingComplete: json['onboardingComplete'] as bool? ?? false,
      pactAccepted: json['pactAccepted'] as bool? ?? false,
      progress: json['progress'] == null
          ? null
          : ProgressSummary.fromJson(json['progress'] as Map<dynamic, dynamic>),
    );
  }

  static const DeviceState empty = DeviceState(
    shorts: ShortsState(enabled: true, warningCount: 0, warningLimit: 5, platforms: []),
    coins: 0,
    permissions: PermissionSummary(
      accessibility: false,
      usageAccess: false,
      overlay: false,
      notifications: false,
      exactAlarms: false,
      batteryIgnored: false,
    ),
    onboardingComplete: false,
    pactAccepted: false,
  );
}

class CoinTransaction {
  const CoinTransaction({
    required this.id,
    required this.type,
    required this.amount,
    required this.timestampMs,
    required this.source,
  });

  final String id;
  final String type; // AD_REWARD | TEMP_UNLOCK_SPEND | BAILOUT_SPEND | BONUS | ...
  final int amount; // signed
  final int timestampMs;
  final String source;

  factory CoinTransaction.fromJson(Map<dynamic, dynamic> json) => CoinTransaction(
        id: json['id'] as String? ?? '',
        type: json['type'] as String? ?? '',
        amount: (json['amount'] as num?)?.toInt() ?? 0,
        timestampMs: (json['timestamp'] as num?)?.toInt() ?? 0,
        source: json['source'] as String? ?? '',
      );

  String get typeLabel {
    switch (type) {
      case 'AD_REWARD':
        return 'Rewarded ad';
      case 'TEMP_UNLOCK_SPEND':
        return 'Temporary unlock';
      case 'BAILOUT_SPEND':
        return 'Bailout';
      case 'BONUS':
        return 'Bonus';
      case 'ADMIN_ADJUSTMENT':
        return 'Adjustment';
      case 'REFUND':
        return 'Refund';
      default:
        return type;
    }
  }
}

class ViolationRecord {
  const ViolationRecord({
    required this.timestampMs,
    required this.type,
    required this.packageName,
    required this.warningNumber,
  });

  final int timestampMs;
  final ViolationType type;
  final String packageName;
  final int warningNumber;

  factory ViolationRecord.fromJson(Map<dynamic, dynamic> json) => ViolationRecord(
        timestampMs: (json['timestamp'] as num?)?.toInt() ?? 0,
        type: ViolationType.fromName(json['type'] as String?),
        packageName: json['packageName'] as String? ?? '',
        warningNumber: (json['warningNumber'] as num?)?.toInt() ?? 0,
      );
}

class HistoryEntry {
  const HistoryEntry({
    required this.id,
    required this.mode,
    required this.completed,
    required this.bailedOut,
    required this.startMs,
    required this.durationMinutes,
    required this.violations,
    required this.cageTriggered,
    required this.tempUnlockUsed,
  });

  final String id;
  final SessionMode mode;
  final bool completed;
  final bool bailedOut;
  final int startMs;
  final int durationMinutes;
  final int violations;
  final bool cageTriggered;
  final bool tempUnlockUsed;

  factory HistoryEntry.fromJson(Map<dynamic, dynamic> json) => HistoryEntry(
        id: json['id'] as String? ?? '',
        mode: (json['mode'] as String?)?.toLowerCase() == 'study' ? SessionMode.study : SessionMode.detox,
        completed: json['completed'] as bool? ?? false,
        bailedOut: json['bailedOut'] as bool? ?? false,
        startMs: (json['start'] as num?)?.toInt() ?? 0,
        durationMinutes: (json['durationMinutes'] as num?)?.toInt() ?? 0,
        violations: (json['violations'] as num?)?.toInt() ?? 0,
        cageTriggered: json['cageTriggered'] as bool? ?? false,
        tempUnlockUsed: json['tempUnlockUsed'] as bool? ?? false,
      );
}

class UsageStat {
  const UsageStat({
    required this.packageName,
    required this.appName,
    required this.minutesToday,
    required this.category,
  });

  final String packageName;
  final String appName;
  final int minutesToday;
  final String category;

  factory UsageStat.fromJson(Map<dynamic, dynamic> json) => UsageStat(
        packageName: json['packageName'] as String? ?? '',
        appName: json['appName'] as String? ?? '',
        minutesToday: (json['minutesToday'] as num?)?.toInt() ?? 0,
        category: json['category'] as String? ?? 'unknown',
      );
}

class AppRule {
  const AppRule({
    required this.packageName,
    required this.appName,
    required this.category,
    required this.blocked,
  });

  final String packageName;
  final String appName;
  final String category;
  final bool blocked;
}

/// v2.3 r7 — per-app daily usage limit (Social Sentry "App Limits" parity).
class AppLimitEntry {
  const AppLimitEntry({
    required this.packageName,
    required this.dailyLimitMinutes,
    required this.minutesUsedToday,
  });

  final String packageName;
  final int dailyLimitMinutes;
  final int minutesUsedToday;

  factory AppLimitEntry.fromJson(Map<dynamic, dynamic> json) => AppLimitEntry(
        packageName: json['packageName'] as String? ?? '',
        dailyLimitMinutes: (json['dailyLimitMinutes'] as num?)?.toInt() ?? 0,
        minutesUsedToday: (json['minutesUsedToday'] as num?)?.toInt() ?? 0,
      );
}

/// v2.3 r7 — scheduled blocking profile (Social Sentry "Schedules" parity).
class ScheduleProfileInfo {
  const ScheduleProfileInfo({
    required this.id,
    required this.name,
    required this.startMinuteOfDay,
    required this.endMinuteOfDay,
    required this.days,
    required this.blockedPackages,
    required this.enabled,
  });

  final String id;
  final String name;
  final int startMinuteOfDay; // 0..1439
  final int endMinuteOfDay;   // 0..1439 (start > end → overnight wrap)
  final Set<int> days;        // ISO day-of-week: Mon=1..Sun=7
  final Set<String> blockedPackages;
  final bool enabled;

  bool get isOvernight => startMinuteOfDay > endMinuteOfDay;

  String get timeLabel {
    String two(int n) => n.toString().padLeft(2, '0');
    final s = '${two(startMinuteOfDay ~/ 60)}:${two(startMinuteOfDay % 60)}';
    final e = '${two(endMinuteOfDay ~/ 60)}:${two(endMinuteOfDay % 60)}';
    return '$s – $e${isOvernight ? ' (+1)' : ''}';
  }

  static const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  String get daysLabel {
    if (days.length == 7) return 'Every day';
    final names = days.toList()..sort();
    return names.map((d) => dayNames[(d - 1) % 7]).join(', ');
  }

  factory ScheduleProfileInfo.fromJson(Map<dynamic, dynamic> json) =>
      ScheduleProfileInfo(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? 'Schedule',
        startMinuteOfDay: (json['startMinuteOfDay'] as num?)?.toInt() ?? 0,
        endMinuteOfDay: (json['endMinuteOfDay'] as num?)?.toInt() ?? 0,
        days: (json['days'] as List<dynamic>? ?? const [])
            .map(_asInt)
            .toSet(),
        blockedPackages:
            (json['blockedPackages'] as List<dynamic>? ?? const [])
                .map((e) => e.toString())
                .toSet(),
        enabled: json['enabled'] as bool? ?? true,
      );
}

class AlarmConfig {
  const AlarmConfig({
    required this.id,
    required this.hour,
    required this.minute,
    required this.repeatDays,
    required this.difficulty,
    required this.enabled,
    required this.label,
  });

  final String id;
  final int hour;
  final int minute;
  final List<int> repeatDays; // 1=Mon..7=Sun (ISO), empty = one-shot
  final String difficulty; // EASY | MEDIUM | HARD
  final bool enabled;
  final String label;

  factory AlarmConfig.fromJson(Map<dynamic, dynamic> json) => AlarmConfig(
        id: json['id'] as String? ?? '',
        hour: (json['hour'] as num?)?.toInt() ?? 6,
        minute: (json['minute'] as num?)?.toInt() ?? 30,
        repeatDays: (json['repeatDays'] as List<dynamic>? ?? const [])
            .map(_asInt)
            .toList(),
        difficulty: json['difficulty'] as String? ?? 'MEDIUM',
        enabled: json['enabled'] as bool? ?? true,
        label: json['label'] as String? ?? '',
      );

  Map<String, dynamic> toWire() => {
        'id': id,
        'hour': hour,
        'minute': minute,
        'repeatDays': repeatDays,
        'difficulty': difficulty,
        'enabled': enabled,
        'label': label,
      };
}

class WeeklyStats {
  const WeeklyStats({
    required this.focusSeconds,
    required this.detoxSeconds,
    required this.blockedAttempts,
    required this.sessionsCompleted,
    required this.sessionsPlanned,
    required this.streakDays,
    required this.dailyFocus,
    required this.dailyDetox,
  });

  final int focusSeconds;
  final int detoxSeconds;
  final int blockedAttempts;
  final int sessionsCompleted;
  final int sessionsPlanned;
  final int streakDays;
  /// 7 entries, Monday-first, seconds per day.
  final List<int> dailyFocus;
  final List<int> dailyDetox;

  factory WeeklyStats.fromJson(Map<dynamic, dynamic> json) => WeeklyStats(
        focusSeconds: (json['focusSeconds'] as num?)?.toInt() ?? 0,
        detoxSeconds: (json['detoxSeconds'] as num?)?.toInt() ?? 0,
        blockedAttempts: (json['blockedAttempts'] as num?)?.toInt() ?? 0,
        sessionsCompleted: (json['sessionsCompleted'] as num?)?.toInt() ?? 0,
        sessionsPlanned: (json['sessionsPlanned'] as num?)?.toInt() ?? 0,
        streakDays: (json['streakDays'] as num?)?.toInt() ?? 0,
        dailyFocus: (json['dailyFocus'] as List<dynamic>? ?? const [])
            .map(_asInt)
            .toList(),
        dailyDetox: (json['dailyDetox'] as List<dynamic>? ?? const [])
            .map(_asInt)
            .toList(),
      );
}

// ---------------------------------------------------------------------------
// v2.1 Phase C — Progress layer (gamification)
// ---------------------------------------------------------------------------

/// Compact progress projection embedded in every DeviceState push.
class ProgressSummary {
  const ProgressSummary({
    required this.enabled,
    this.level = 1,
    this.levelName = '',
    this.dp = 0,
    this.nextLevelDp,
    this.streakDays = 0,
    this.checkInAvailable = false,
    this.frozen = false,
  });

  final bool enabled;
  final int level;
  final String levelName;
  final int dp;
  final int? nextLevelDp;
  final int streakDays;
  final bool checkInAvailable;
  final bool frozen;

  factory ProgressSummary.fromJson(Map<dynamic, dynamic> json) =>
      ProgressSummary(
        enabled: json['enabled'] as bool? ?? false,
        level: (json['level'] as num?)?.toInt() ?? 1,
        levelName: json['levelName'] as String? ?? '',
        dp: (json['dp'] as num?)?.toInt() ?? 0,
        nextLevelDp: (json['nextLevelDp'] as num?)?.toInt(),
        streakDays: (json['streakDays'] as num?)?.toInt() ?? 0,
        checkInAvailable: json['checkInAvailable'] as bool? ?? false,
        frozen: json['frozen'] as bool? ?? false,
      );
}

/// Full progress snapshot (getProgress call).
class ProgressSnapshot {
  const ProgressSnapshot({
    required this.enabled,
    required this.dp,
    required this.streak,
    required this.protection,
    required this.checkIn,
  });

  final bool enabled;
  final ProgressDp dp;
  final ProgressStreak streak;
  final ProgressProtection protection;
  final ProgressCheckIn checkIn;

  factory ProgressSnapshot.fromJson(Map<dynamic, dynamic> json) =>
      ProgressSnapshot(
        enabled: json['enabled'] as bool? ?? false,
        dp: ProgressDp.fromJson(json['dp'] as Map<dynamic, dynamic>? ?? {}),
        streak: ProgressStreak.fromJson(json['streak'] as Map<dynamic, dynamic>? ?? {}),
        protection:
            ProgressProtection.fromJson(json['protection'] as Map<dynamic, dynamic>? ?? {}),
        checkIn: ProgressCheckIn.fromJson(json['checkIn'] as Map<dynamic, dynamic>? ?? {}),
      );
}

class ProgressDp {
  const ProgressDp({
    this.current = 0,
    this.lifetime = 0,
    this.peak = 0,
    this.level = 1,
    this.levelName = '',
    this.nextLevelDp,
    this.nextLevelName,
    this.progressPct = 0,
    this.earnedToday = 0,
    this.dailyCap = 150,
  });

  final int current;
  final int lifetime;
  final int peak;
  final int level;
  final String levelName;
  final int? nextLevelDp;
  final String? nextLevelName;
  final int progressPct;
  final int earnedToday;
  final int dailyCap;

  factory ProgressDp.fromJson(Map<dynamic, dynamic> json) => ProgressDp(
        current: (json['current'] as num?)?.toInt() ?? 0,
        lifetime: (json['lifetime'] as num?)?.toInt() ?? 0,
        peak: (json['peak'] as num?)?.toInt() ?? 0,
        level: (json['level'] as num?)?.toInt() ?? 1,
        levelName: json['levelName'] as String? ?? '',
        nextLevelDp: (json['nextLevelDp'] as num?)?.toInt(),
        nextLevelName: json['nextLevelName'] as String?,
        progressPct: (json['progressPct'] as num?)?.toInt() ?? 0,
        earnedToday: (json['earnedToday'] as num?)?.toInt() ?? 0,
        dailyCap: (json['dailyCap'] as num?)?.toInt() ?? 150,
      );
}

class StreakMilestone {
  const StreakMilestone({
    required this.days,
    required this.dp,
    required this.achieved,
  });

  final int days;
  final int dp;
  final bool achieved;

  factory StreakMilestone.fromJson(Map<dynamic, dynamic> json) => StreakMilestone(
        days: (json['days'] as num?)?.toInt() ?? 0,
        dp: (json['dp'] as num?)?.toInt() ?? 0,
        achieved: json['achieved'] as bool? ?? false,
      );
}

class ProgressStreak {
  const ProgressStreak({
    this.current = 0,
    this.best = 0,
    this.onTrackToday = false,
    this.milestones = const [],
  });

  final int current;
  final int best;
  final bool onTrackToday;
  final List<StreakMilestone> milestones;

  factory ProgressStreak.fromJson(Map<dynamic, dynamic> json) => ProgressStreak(
        current: (json['current'] as num?)?.toInt() ?? 0,
        best: (json['best'] as num?)?.toInt() ?? 0,
        onTrackToday: json['onTrackToday'] as bool? ?? false,
        milestones: (json['milestones'] as List<dynamic>? ?? const [])
            .map((e) => StreakMilestone.fromJson(e as Map<dynamic, dynamic>))
            .toList(),
      );
}

class ProgressProtection {
  const ProgressProtection({
    this.freezesRemaining = 0,
    this.freezesUsedThisMonth = 0,
    this.frozen = false,
    this.graceUntilWallMs = 0,
    this.pendingRelapseReason = '',
  });

  final int freezesRemaining;
  final int freezesUsedThisMonth;
  final bool frozen;
  final int graceUntilWallMs;
  final String pendingRelapseReason;

  factory ProgressProtection.fromJson(Map<dynamic, dynamic> json) =>
      ProgressProtection(
        freezesRemaining: (json['freezesRemaining'] as num?)?.toInt() ?? 0,
        freezesUsedThisMonth: (json['freezesUsedThisMonth'] as num?)?.toInt() ?? 0,
        frozen: json['frozen'] as bool? ?? false,
        graceUntilWallMs: (json['graceUntilWallMs'] as num?)?.toInt() ?? 0,
        pendingRelapseReason: json['pendingRelapseReason'] as String? ?? '',
      );
}

class ProgressCheckIn {
  const ProgressCheckIn({
    this.available = false,
    this.cycleDay = 0,
    this.cycleRewards = const [],
    this.totalCheckIns = 0,
    this.cyclesCompleted = 0,
  });

  final bool available;
  final int cycleDay;
  final List<int> cycleRewards;
  final int totalCheckIns;
  final int cyclesCompleted;

  factory ProgressCheckIn.fromJson(Map<dynamic, dynamic> json) => ProgressCheckIn(
        available: json['available'] as bool? ?? false,
        cycleDay: (json['cycleDay'] as num?)?.toInt() ?? 0,
        cycleRewards: (json['cycleRewards'] as List<dynamic>? ?? const [])
            .map(_asInt)
            .toList(),
        totalCheckIns: (json['totalCheckIns'] as num?)?.toInt() ?? 0,
        cyclesCompleted: (json['cyclesCompleted'] as num?)?.toInt() ?? 0,
      );
}

/// DP award ledger row.
class DpAwardRecord {
  const DpAwardRecord({
    required this.id,
    required this.reason,
    required this.amount,
    required this.multiplier,
    required this.capped,
    required this.timestampWall,
  });

  final int id;
  final String reason;
  final int amount;
  final double multiplier;
  final bool capped;
  final int timestampWall;

  factory DpAwardRecord.fromJson(Map<dynamic, dynamic> json) => DpAwardRecord(
        id: (json['id'] as num?)?.toInt() ?? 0,
        reason: json['reason'] as String? ?? '',
        amount: (json['amount'] as num?)?.toInt() ?? 0,
        multiplier: (json['multiplier'] as num?)?.toDouble() ?? 1.0,
        capped: json['capped'] as bool? ?? false,
        timestampWall: (json['timestampWall'] as num?)?.toInt() ?? 0,
      );

  String get label {
    switch (reason) {
      case 'SESSION_COMPLETED':
        return 'Session completed';
      case 'MONK_COMPLETED':
        return 'Monk mode completed';
      case 'PRIME_COMPLETED':
        return 'Prime commit kept';
      case 'CAGE_SURVIVED':
        return 'Cage served in full';
      case 'ALARM_COMPLETED':
        return 'Shockwave alarm solved';
      case 'SAFETY_PAUSE_COMPLETED':
        return 'Safety pause waited out';
      case 'REELS_BLOCKED':
        return 'Reel intercepted';
      case 'CLEAN_DAY':
        return 'Clean day';
      case 'CHECK_IN':
        return 'Daily check-in';
      case 'STREAK_MILESTONE':
        return 'Streak milestone';
      case 'FEATURE_SHORTS':
        return 'Shorts blocker enabled';
      case 'FEATURE_SAFETY':
        return 'Safety pause enabled';
      case 'FEATURE_MONK':
        return 'First monk mode';
      case 'FEATURE_PRIME':
        return 'First prime commit';
      case 'FEATURE_ALARM':
        return 'First alarm set';
      case 'FEATURE_WIDGET':
        return 'First widget pinned';
      default:
        return reason;
    }
  }
}

/// Relapse history row.
class RelapseRecord {
  const RelapseRecord({
    required this.id,
    required this.timestampWall,
    required this.reason,
    required this.streakDaysAtRelapse,
    required this.source,
  });

  final int id;
  final int timestampWall;
  final String reason;
  final int streakDaysAtRelapse;
  final String source;

  factory RelapseRecord.fromJson(Map<dynamic, dynamic> json) => RelapseRecord(
        id: (json['id'] as num?)?.toInt() ?? 0,
        timestampWall: (json['timestampWall'] as num?)?.toInt() ?? 0,
        reason: json['reason'] as String? ?? '',
        streakDaysAtRelapse: (json['streakDaysAtRelapse'] as num?)?.toInt() ?? 0,
        source: json['source'] as String? ?? '',
      );

  String get sourceLabel {
    switch (source) {
      case 'PRIME_GIVEUP':
        return 'Prime commit ended early';
      case 'PROTECTION_LOST':
        return 'Protection lost and not restored';
      default:
        return source;
    }
  }
}

// ===========================================================================
// v2.2 Phase D — growth & monetization models
// ===========================================================================

/// Server plan catalog row (GET /plans).
class Plan {
  const Plan({
    required this.id,
    required this.productId,
    required this.plan,
    required this.source,
    required this.durationDays,
    required this.priceMinor,
    required this.currency,
    required this.displayName,
    required this.description,
    required this.isPopular,
    required this.isActive,
    required this.sortOrder,
  });

  final String id;
  final String productId;
  final String plan;
  final String source; // play | bkash
  final int durationDays;
  final int priceMinor;
  final String currency;
  final String displayName;
  final String? description;
  final bool isPopular;
  final bool isActive;
  final int sortOrder;

  factory Plan.fromJson(Map<dynamic, dynamic> json) => Plan(
        id: json['id'] as String? ?? '',
        productId: json['productId'] as String? ?? '',
        plan: json['plan'] as String? ?? 'monthly',
        source: json['source'] as String? ?? 'play',
        durationDays: (json['durationDays'] as num?)?.toInt() ?? 30,
        priceMinor: (json['priceMinor'] as num?)?.toInt() ?? 0,
        currency: json['currency'] as String? ?? 'BDT',
        displayName: json['displayName'] as String? ?? '',
        description: json['description'] as String?,
        isPopular: json['isPopular'] as bool? ?? false,
        isActive: json['isActive'] as bool? ?? true,
        sortOrder: (json['sortOrder'] as num?)?.toInt() ?? 100,
      );

  /// Display price in major units (paisa -> taka for BDT).
  String get priceLabel {
    final major = priceMinor / 100;
    final symbol = currency == 'BDT' ? '\u09F3' : currency;
    final value = major % 1 == 0
        ? major.toInt().toString()
        : major.toStringAsFixed(2);
    return '$symbol$value';
  }

  String get durationLabel {
    if (durationDays >= 365) return '1 year';
    if (durationDays >= 180) return '6 months';
    if (durationDays >= 90) return '3 months';
    return '$durationDays days';
  }
}

/// Trial state (GET /trial, POST /trial/claim).
class TrialInfo {
  const TrialInfo({
    required this.eligible,
    required this.active,
    required this.claimedAt,
    required this.expiresAt,
  });

  final bool eligible;
  final bool active;
  final String? claimedAt;
  final String? expiresAt;

  factory TrialInfo.fromJson(Map<dynamic, dynamic> json) => TrialInfo(
        eligible: json['eligible'] as bool? ?? false,
        active: json['active'] as bool? ?? false,
        claimedAt: json['claimedAt'] as String?,
        expiresAt: json['expiresAt'] as String?,
      );
}

/// v2.5.5 audit fix: malformed native list elements used to throw on the
/// `(e as num).toInt()` casts in days/repeatDays/dailyFocus/dailyDetox/
/// cycleRewards — parse defensively instead (non-numeric -> 0).
int _asInt(dynamic e) =>
    e is num ? e.toInt() : num.tryParse(e?.toString() ?? '')?.toInt() ?? 0;

/// Server-side subscription projection (GET /subscription).
class SubscriptionInfo {
  const SubscriptionInfo({
    required this.id,
    required this.productId,
    required this.plan,
    required this.status,
    required this.expiryDate,
  });

  final String id;
  final String productId;
  final String plan;
  final String status;
  final String? expiryDate;

  factory SubscriptionInfo.fromJson(Map<dynamic, dynamic> json) =>
      SubscriptionInfo(
        id: json['id'] as String? ?? '',
        productId: json['productId'] as String? ?? '',
        plan: json['plan'] as String? ?? '',
        status: json['status'] as String? ?? 'PENDING',
        expiryDate: json['expiryDate'] as String?,
      );

  bool get isActive =>
      status == 'ACTIVE' &&
      expiryDate != null &&
      DateTime.tryParse(expiryDate!)?.isAfter(DateTime.now().toUtc()) == true;

  DateTime? get expiry =>
      expiryDate == null ? null : DateTime.tryParse(expiryDate!);

  /// Days of PRO left (never below zero).
  int get daysLeft {
    final e = expiry;
    if (e == null || !isActive) return 0;
    return e.difference(DateTime.now().toUtc()).inDays + 1;
  }
}

/// bKash manual-gateway payment record.
class BkashPaymentRecord {
  const BkashPaymentRecord({
    required this.id,
    required this.planId,
    required this.reference,
    required this.amountMinor,
    required this.currency,
    required this.trxId,
    required this.senderNumber,
    required this.status,
    required this.createdAt,
    this.planName,
    this.rejectReason,
  });

  final String id;
  final String planId;
  final String reference;
  final int amountMinor;
  final String currency;
  final String? trxId;
  final String? senderNumber;
  final String status; // PENDING | IN_REVIEW | VERIFIED | REJECTED | ...
  final String createdAt;
  final String? planName;
  final String? rejectReason;

  factory BkashPaymentRecord.fromJson(Map<dynamic, dynamic> json) =>
      BkashPaymentRecord(
        id: json['id'] as String? ?? '',
        planId: json['planId'] as String? ?? '',
        reference: json['reference'] as String? ?? '',
        amountMinor: (json['amountMinor'] as num?)?.toInt() ?? 0,
        currency: json['currency'] as String? ?? 'BDT',
        trxId: json['trxId'] as String?,
        senderNumber: json['senderNumber'] as String?,
        status: json['status'] as String? ?? 'PENDING',
        createdAt: json['createdAt'] as String? ?? '',
        planName: json['planName'] as String?,
        rejectReason: json['rejectReason'] as String?,
      );

  String get statusLabel {
    switch (status) {
      case 'IN_REVIEW':
        return 'Under review';
      case 'VERIFIED':
        return 'Verified — PRO activated';
      case 'REJECTED':
        return 'Rejected';
      case 'EXPIRED':
        return 'Expired';
      case 'CANCELED':
        return 'Canceled';
      default:
        return 'Waiting for transfer';
    }
  }
}

/// Weekly break-pass status (native bridge).
class BreakPassStatus {
  const BreakPassStatus({
    required this.allowancePerWeek,
    required this.usedThisWeek,
    required this.remaining,
    required this.windowSeconds,
    required this.activePackage,
    required this.activeRemainingSeconds,
  });

  final int allowancePerWeek;
  final int usedThisWeek;
  final int remaining;
  final int windowSeconds;
  final String activePackage;
  final int activeRemainingSeconds;

  factory BreakPassStatus.fromJson(Map<dynamic, dynamic> json) =>
      BreakPassStatus(
        allowancePerWeek: (json['allowancePerWeek'] as num?)?.toInt() ?? 0,
        usedThisWeek: (json['usedThisWeek'] as num?)?.toInt() ?? 0,
        remaining: (json['remaining'] as num?)?.toInt() ?? 0,
        windowSeconds: (json['windowSeconds'] as num?)?.toInt() ?? 300,
        activePackage: json['activePackage'] as String? ?? '',
        activeRemainingSeconds:
            (json['activeRemainingSeconds'] as num?)?.toInt() ?? 0,
      );

  bool get isActive => activePackage.isNotEmpty && activeRemainingSeconds > 0;
}

/// Play Billing product projection (native bridge).
class PlayProductInfo {
  const PlayProductInfo({
    required this.productId,
    required this.title,
    required this.formattedPrice,
  });

  final String productId;
  final String title;
  final String formattedPrice;

  factory PlayProductInfo.fromJson(Map<dynamic, dynamic> json) =>
      PlayProductInfo(
        productId: json['productId'] as String? ?? '',
        title: json['title'] as String? ?? '',
        formattedPrice: json['formattedPrice'] as String? ?? '',
      );
}

/// In-app announcement from the Worker (pull model).
class AnnouncementItem {
  const AnnouncementItem({
    required this.id,
    required this.title,
    required this.body,
    required this.type,
  });

  final String id;
  final String title;
  final String body;
  final String type;

  factory AnnouncementItem.fromJson(Map<dynamic, dynamic> json) =>
      AnnouncementItem(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        body: json['body'] as String? ?? '',
        type: json['type'] as String? ?? 'INFO',
      );

  bool get isOffer => type == 'PROMOTION';
}
