import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Insights (UI/UX §37–38): "Am I actually improving?" Week focus/detox,
/// blocked attempts, streak. Charts stay simple; no emotional manipulation
/// on streak loss.
class InsightsScreen extends StatefulWidget {
  const InsightsScreen({super.key, this.embedded = false});

  final bool embedded;

  @override
  State<InsightsScreen> createState() => _InsightsScreenState();
}

/// Embedded variant used by the Insights tab.
class InsightsScreenRef extends StatelessWidget {
  const InsightsScreenRef({super.key});

  @override
  Widget build(BuildContext context) => const InsightsScreen(embedded: true);
}

class _InsightsScreenState extends State<InsightsScreen> {
  WeeklyStats? _stats;
  SubscriptionInfo? _subscription;

  // v2.5.9 (r11.1) — opportunity cost + distraction trend (user-requested).
  Map<String, dynamic>? _opportunityCost;
  List<Map<String, dynamic>> _trendDays = const [];

  // v2.5.9 (r11.2) — today's top distracting apps (display-only ranking).
  List<Map<String, dynamic>> _topApps = const [];

  // v2.7 (r13) — FULL per-app usage today (user-requested: "which apps did
  // I run and for how long"). Ranked, with share-of-day bars + total
  // screen time.
  List<UsageStat> _usage = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final stats = await NativeBridge.instance.getWeeklyStats();
    final subRes = await ApiClient.instance.fetchSubscription();
    final cost = await NativeBridge.instance.getOpportunityCost();
    final trend = await NativeBridge.instance.getDistractionTrend();
    final topApps = await NativeBridge.instance.getTopDistractingApps();
    final usage = await NativeBridge.instance.getUsageStats();
    if (!mounted) return;
    setState(() {
      _stats = stats;
      if (cost['enabled'] == true) {
        _opportunityCost = cost;
      }
      _trendDays = trend;
      _topApps = topApps;
      _usage = usage;
      if (subRes != null && subRes['subscription'] is Map) {
        _subscription = SubscriptionInfo.fromJson(
            Map<dynamic, dynamic>.from(subRes['subscription'] as Map));
      }
    });
  }

  bool get _isPro => _subscription?.isActive ?? false;

  /// v2.7 (r13) — helpers for the APP USAGE TODAY card.
  int get _usageTotalMinutes =>
      _usage.fold<int>(0, (sum, a) => sum + a.minutesToday);

  int get _usageMaxMinutes =>
      _usage.fold<int>(0, (m, a) => a.minutesToday > m ? a.minutesToday : m);

  String _fmtSeconds(int s) {
    if (s <= 0) return '0m';
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    if (h > 0) return '${h}h ${m}m';
    return '${m}m';
  }

  @override
  Widget build(BuildContext context) {
    final stats = _stats;
    final labels = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

    final body = SingleChildScrollView(
      padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxxl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('YOUR WEEK', style: AppTypography.heading()),
          const SizedBox(height: AppSpacing.sm),
          Text('Am I actually improving?', style: AppTypography.caption()),
          const SizedBox(height: AppSpacing.xxl),

          Row(
            children: [
              Expanded(child: MLDStatTile(label: 'Focus', value: _fmtSeconds(stats?.focusSeconds ?? 0))),
              const SizedBox(width: AppSpacing.md),
              Expanded(child: MLDStatTile(label: 'Detox', value: _fmtSeconds(stats?.detoxSeconds ?? 0))),
              const SizedBox(width: AppSpacing.md),
              Expanded(child: MLDStatTile(label: 'Blocked', value: '${stats?.blockedAttempts ?? 0}', accent: AppColors.warning)),
            ],
          ),
          const SizedBox(height: AppSpacing.xxl),

          MLDCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const MLDSectionHeader(title: 'FOCUS TIME'),
                if (stats == null)
                  const Center(child: Padding(
                    padding: EdgeInsets.all(AppSpacing.xxl),
                    child: CircularProgressIndicator(),
                  ))
                else
                  MLDBarChart(
                    values: _padded(stats.dailyFocus).map((s) => s.toDouble()).toList(),
                    labels: labels,
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),

          MLDCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const MLDSectionHeader(title: 'DETOX TIME'),
                if (stats == null)
                  const SizedBox(height: 80)
                else
                  MLDBarChart(
                    values: _padded(stats.dailyDetox).map((s) => s.toDouble()).toList(),
                    labels: labels,
                    accent: AppColors.danger,
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),

          // v2.5.9 (r11.1) — DISTRACTION TREND: the in-app view of the same
          // data the home-screen trend widget charts (weekly distracting
          // minutes + reels skipped).
          if (_trendDays.isNotEmpty) ...[
            MLDCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const MLDSectionHeader(title: 'DISTRACTION TREND'),
                  const SizedBox(height: AppSpacing.sm),
                  MLDBarChart(
                    values: _trendDays
                        .map((d) =>
                            ((d['distractingMinutes'] as num?) ?? 0)
                                .toDouble())
                        .toList(),
                    labels: _trendDays
                        .map((d) => _dayInitial(d['date'] as String? ?? ''))
                        .toList(),
                    accent: AppColors.warning,
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    'Reels skipped this week: '
                    '${_trendDays.fold<int>(0, (sum, d) => sum + (((d['reelsBlocked'] as num?) ?? 0).toInt()))}',
                    style: AppTypography.caption(),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xxl),
          ],

          // v2.5.9 (r11.2) — TOP DISTRACTING APPS: where today's distraction
          // minutes actually went. Honest ranking, no shaming copy; hidden
          // on a quiet day (empty list).
          if (_topApps.isNotEmpty) ...[
            MLDCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const MLDSectionHeader(title: 'TOP DISTRACTING APPS TODAY'),
                  const SizedBox(height: AppSpacing.sm),
                  ...List.generate(_topApps.length, (i) {
                    final app = _topApps[i];
                    final minutes =
                        ((app['minutesToday'] as num?) ?? 0).toInt();
                    return Padding(
                      padding: EdgeInsets.only(
                          bottom: i == _topApps.length - 1
                              ? 0
                              : AppSpacing.md),
                      child: Row(
                        children: [
                          SizedBox(
                            width: 22,
                            child: Text('${i + 1}',
                                style: AppTypography.caption()),
                          ),
                          Expanded(
                            child: Text(
                              (app['appName'] as String?) ??
                                  (app['packageName'] as String?) ??
                                  'Unknown',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: AppTypography.body(),
                            ),
                          ),
                          Text(
                            minutes >= 60
                                ? '${minutes ~/ 60}h ${minutes % 60}m'
                                : '${minutes}m',
                            style: AppTypography.caption(),
                          ),
                        ],
                      ),
                    );
                  }),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xxl),
          ],

          // v2.7 (r13) — APP USAGE TODAY (user-requested): every app with
          // real foreground minutes today — what ran, for how long, ranked
          // with share-of-day bars and the total screen-time line.
          MLDCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const MLDSectionHeader(title: 'APP USAGE TODAY'),
                const SizedBox(height: AppSpacing.sm),
                if (_usage.isEmpty)
                  Text(
                    'No usage recorded yet today. Grant usage access in '
                    'Permission Center if this stays empty.',
                    style: AppTypography.caption(),
                  )
                else ...[
                  ...List.generate(_usage.take(8).length, (i) {
                    final app = _usage[i];
                    final minutes = app.minutesToday;
                    final share = _usageMaxMinutes <= 0
                        ? 0.0
                        : (minutes / _usageMaxMinutes).clamp(0.0, 1.0);
                    return Padding(
                      padding: EdgeInsets.only(
                          bottom: i == 7 || i == _usage.length - 1
                              ? 0
                              : AppSpacing.md),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              SizedBox(
                                width: 22,
                                child: Text('${i + 1}',
                                    style: AppTypography.caption()),
                              ),
                              Expanded(
                                child: Text(
                                  app.appName,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: AppTypography.body(),
                                ),
                              ),
                              Text(
                                minutes >= 60
                                    ? '${minutes ~/ 60}h ${minutes % 60}m'
                                    : '${minutes}m',
                                style: AppTypography.caption(),
                              ),
                            ],
                          ),
                          const SizedBox(height: AppSpacing.xs),
                          ClipRRect(
                            borderRadius: BorderRadius.circular(4),
                            child: LinearProgressIndicator(
                              value: share,
                              minHeight: 4,
                              backgroundColor: AppColors.edge,
                              valueColor: const AlwaysStoppedAnimation<Color>(
                                  AppColors.primary),
                            ),
                          ),
                        ],
                      ),
                    );
                  }),
                  if (_usage.length > 8)
                    Padding(
                      padding: const EdgeInsets.only(top: AppSpacing.md),
                      child: Text('…and ${_usage.length - 8} more apps',
                          style: AppTypography.caption()),
                    ),
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    'Total screen time: ${_fmtSeconds(_usageTotalMinutes * 60)}',
                    style: AppTypography.caption(
                        color: AppColors.textSecondary),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),

          // v2.5.9 (r11.1) — OPPORTUNITY COST (user-requested): "ei shomoy
          // kaje lagale eita hoto" — the user's own weekly numbers projected
          // to a year, with book/earning equivalents.
          if (_opportunityCost != null) ...[
            MLDCard(
              color: AppColors.warning.withValues(alpha: 0.06),
              borderColor: AppColors.warning.withValues(alpha: 0.3),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.schedule, color: AppColors.warning, size: 22),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Text('WHAT THIS TIME WAS WORTH',
                            style: AppTypography.body(
                                weight: FontWeight.w800)),
                      ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    '"${_opportunityCost!['line'] as String? ?? ''}"',
                    style: AppTypography.body(),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    'At this pace a year costs '
                    '${_opportunityCost!['yearlyDaysLost']} full days '
                    '(${_opportunityCost!['yearlyHours']} hours) — the same '
                    'time is ${_opportunityCost!['booksPerYear']} books, or '
                    '৳${_opportunityCost!['takaPerYear']} of focused work '
                    'at ৳${_opportunityCost!['hourlyRateBdt']}/hour.',
                    style: AppTypography.caption(),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xxl),
          ],

          MLDCard(
            color: AppColors.warning.withValues(alpha: 0.06),
            borderColor: AppColors.warning.withValues(alpha: 0.3),
            child: Row(
              children: [
                const Icon(Icons.local_fire_department, color: AppColors.warning, size: 34),
                const SizedBox(width: AppSpacing.lg),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${stats?.streakDays ?? 0} DAY FOCUS STREAK',
                          style: AppTypography.body(weight: FontWeight.w800)),
                      Text(
                        stats == null
                            ? ''
                            : 'You completed ${stats.sessionsCompleted} of ${max(stats.sessionsCompleted, stats.sessionsPlanned)} planned sessions.',
                        style: AppTypography.caption(),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),

          const MLDWarningBanner(
            message:
                'Streaks are honest numbers, not guilt trips. Miss a day and nothing dramatic happens — just start again.',
            tone: MLDBannerTone.info,
          ),
          const SizedBox(height: AppSpacing.xxl),

          // v2.2 Phase D — extended history is the honest PRO perk: depth,
          // never safety. Free tier keeps the 7-day charts above.
          MLDCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.workspace_premium_outlined,
                        color: AppColors.premium, size: 22),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Text('Longer history',
                          style: AppTypography.body(weight: FontWeight.w700)),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.sm),
                Text(
                  _isPro
                      ? 'PRO active — your full 90-day archive is kept server-side and in History.'
                      : 'PRO keeps 90 days of insight history instead of 7. Core blocking stays free either way.',
                  style: AppTypography.caption(),
                ),
                const SizedBox(height: AppSpacing.md),
                MLDButton(
                  label: _isPro ? 'View history' : 'See PRO',
                  variant: MLDButtonVariant.secondary,
                  expanded: false,
                  onPressed: () => Navigator.of(context).pushNamed(
                      _isPro
                          ? AppConstants.routeHistory
                          : AppConstants.routePaywall),
                ),
              ],
            ),
          ),
        ],
      ),
    );

    if (widget.embedded) return body;
    return Scaffold(
      appBar: AppBar(title: const Text('Insights')),
      body: SafeArea(child: body),
    );
  }

  List<int> _padded(List<int> values) {
    final padded = List<int>.from(values);
    while (padded.length < 7) {
      padded.add(0);
    }
    return padded.take(7).toList();
  }

  /// v2.5.9 (r11.1) — single-letter day label for the distraction chart
  /// (dateKey is yyyy-MM-dd local; falls back to '·').
  String _dayInitial(String dateKey) {
    final d = DateTime.tryParse(dateKey);
    if (d == null) return '·';
    const names = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
    return names[d.weekday - 1];
  }

  int max(int a, int b) => a > b ? a : b;
}

/// Session history (UI/UX §39–40): grouped list with completion/bailout
/// indicators. Tap-free simplicity for v1.
class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key});

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  List<HistoryEntry> _entries = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final entries = await NativeBridge.instance.getHistory(limit: 60);
    if (!mounted) return;
    setState(() {
      _entries = entries;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('History')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _entries.isEmpty
                ? const MLDEmptyState(
                    title: 'NO SESSIONS YET',
                    message: 'Your first focus session will appear here.',
                    icon: Icons.history,
                  )
                : ListView.separated(
                    padding: AppSpacing.screenH.copyWith(
                      top: AppSpacing.xl,
                      bottom: AppSpacing.xxxl,
                    ),
                    itemCount: _entries.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, i) => _tile(_entries[i]),
                  ),
      ),
    );
  }

  Widget _tile(HistoryEntry e) {
    final isStudy = e.mode == SessionMode.study;
    final ok = e.completed;
    final color = ok ? AppColors.success : (e.bailedOut ? AppColors.danger : AppColors.warning);
    final date = DateTime.fromMillisecondsSinceEpoch(e.startMs);
    final duration =
        e.durationMinutes >= 60 ? '${e.durationMinutes ~/ 60}h ${e.durationMinutes % 60}m' : '${e.durationMinutes}m';

    return MLDCard(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Row(
        children: [
          Icon(
            ok ? Icons.check_circle : (e.bailedOut ? Icons.exit_to_app : Icons.warning),
            color: color,
            size: 24,
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${isStudy ? 'Study' : 'Detox'} — $duration',
                  style: AppTypography.body(weight: FontWeight.w700),
                ),
                Text(
                  _dayLabel(date),
                  style: AppTypography.caption(),
                ),
              ],
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                ok ? 'Completed' : (e.bailedOut ? 'Bailed out' : 'Interrupted'),
                style: AppTypography.caption(color: color, weight: FontWeight.w700),
              ),
              if (e.tempUnlockUsed)
                Text('unlock used', style: AppTypography.caption().copyWith(fontSize: 11)),
              if (e.cageTriggered)
                Text('cage', style: AppTypography.caption().copyWith(fontSize: 11, color: AppColors.danger)),
            ],
          ),
        ],
      ),
    );
  }

  String _dayLabel(DateTime d) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final day = DateTime(d.year, d.month, d.day);
    final diff = today.difference(day).inDays;
    if (diff == 0) return 'Today';
    if (diff == 1) return 'Yesterday';
    return '${d.day}/${d.month}';
  }
}
