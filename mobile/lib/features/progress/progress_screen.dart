import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Progress hub (v2.1 Phase C): level curve, streak + freezes, check-in
/// cycle, milestones, DP ledger and relapse history.
///
/// TONE POLICY: mirrors the native GamificationNotifier — factual and
/// respectful, never guilt-driven. A relapse is data, not a verdict.
class ProgressScreen extends StatefulWidget {
  const ProgressScreen({super.key});

  @override
  State<ProgressScreen> createState() => _ProgressScreenState();
}

class _ProgressScreenState extends State<ProgressScreen> {
  ProgressSnapshot? _snapshot;
  List<DpAwardRecord> _awards = const [];
  List<RelapseRecord> _relapses = const [];
  BreakPassStatus _breakPasses =
      const BreakPassStatus(allowancePerWeek: 0, usedThisWeek: 0, remaining: 0, windowSeconds: 300, activePackage: '', activeRemainingSeconds: 0);
  bool _claiming = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final snap = await NativeBridge.instance.getProgress();
    final awards = await NativeBridge.instance.getDpHistory(limit: 25);
    final relapses = await NativeBridge.instance.getRelapseHistory(limit: 15);
    final breakPasses = await NativeBridge.instance.getBreakPassStatus();
    if (!mounted) return;
    setState(() {
      _snapshot = snap.isOk ? snap.data : null;
      _awards = awards.isOk ? awards.data ?? const [] : const [];
      _relapses = relapses.isOk ? relapses.data ?? const [] : const [];
      _breakPasses = breakPasses;
      _error = snap.isOk ? null : snap.error?.message;
    });
  }

  Future<void> _claim() async {
    if (_claiming) return;
    setState(() => _claiming = true);
    final r = await NativeBridge.instance.claimCheckIn();
    if (!mounted) return;
    setState(() => _claiming = false);
    if (r.isOk) {
      final awarded = r.data ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: AppColors.elevated,
          content: Text('Checked in · +$awarded DP',
              style: AppTypography.body()),
        ),
      );
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final snap = _snapshot;

    if (snap == null) {
      return Scaffold(
        appBar: AppBar(title: const MLDAppBarTitle(title: 'Progress')),
        body: Center(
          child: _error == null
              ? const CircularProgressIndicator(color: AppColors.primary)
              : MLDEmptyState(
                  icon: Icons.terrain_outlined,
                  title: 'Progress unavailable',
                  message: _error ?? 'Try again in a moment.',
                ),
        ),
      );
    }

    if (!snap.enabled) {
      return Scaffold(
        appBar: AppBar(title: const MLDAppBarTitle(title: 'Progress')),
        body: const Center(
          child: MLDEmptyState(
            icon: Icons.terrain_outlined,
            title: 'Progress is turned off',
            message:
                'The progress layer can be enabled remotely by the product team.',
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Progress')),
      body: RefreshIndicator(
        color: AppColors.primary,
        onRefresh: _load,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxxl),
          children: [
            _LevelCard(dp: snap.dp),
            const SizedBox(height: AppSpacing.xxl),
            _StreakCard(streak: snap.streak, protection: snap.protection),
            const SizedBox(height: AppSpacing.xxl),
            _CheckInCard(
              checkIn: snap.checkIn,
              claiming: _claiming,
              onClaim: _claim,
            ),
            const SizedBox(height: AppSpacing.xxl),
            _MilestonesCard(streak: snap.streak),
            const SizedBox(height: AppSpacing.xxl),
            _BreakPassCard(
              status: _breakPasses,
              onUse: _useBreakPass,
              onEnd: _endBreakPass,
            ),
            const SizedBox(height: AppSpacing.xxl),
            _DpHistoryCard(awards: _awards),
            const SizedBox(height: AppSpacing.xxl),
            _RelapseCard(relapses: _relapses),
          ],
        ),
      ),
    );
  }

  Future<void> _useBreakPass(String pkg) async {
    final r = await NativeBridge.instance.useBreakPass(pkg);
    if (!mounted) return;
    if (r.isOk) {
      final minutes = ((r.data?.windowSeconds ?? 300) / 60).round();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: AppColors.elevated,
          content: Text('Break started — $minutes minutes on $pkg',
              style: AppTypography.body()),
        ),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: AppColors.elevated,
          content: Text(r.error?.message ?? 'Break pass unavailable.',
              style: AppTypography.body()),
        ),
      );
    }
    await _load();
  }

  Future<void> _endBreakPass() async {
    await NativeBridge.instance.endBreakPassEarly();
    await _load();
  }
}

// ---------------------------------------------------------------------------
// Level card
// ---------------------------------------------------------------------------

class _LevelCard extends StatelessWidget {
  const _LevelCard({required this.dp});

  final ProgressDp dp;

  @override
  Widget build(BuildContext context) {
    final next = dp.nextLevelDp;
    final isMax = next == null;
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [AppColors.primary, AppColors.premium],
                  ),
                  borderRadius: BorderRadius.circular(AppRadii.button),
                ),
                child: const Icon(Icons.terrain_outlined,
                    color: AppColors.onPrimary, size: 30),
              ),
              const SizedBox(width: AppSpacing.lg),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('LEVEL ${dp.level}',
                        style: AppTypography.label(color: AppColors.primary)),
                    const SizedBox(height: 2),
                    Text(dp.levelName, style: AppTypography.heading()),
                    const SizedBox(height: 2),
                    Text(
                      isMax
                          ? 'Top of the mountain — ${dp.lifetime} lifetime DP'
                          : '${dp.lifetime} / $next lifetime DP to ${dp.nextLevelName ?? 'next level'}',
                      style: AppTypography.caption(),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.xxl),
          ClipRRect(
            borderRadius: BorderRadius.circular(AppRadii.sm),
            child: LinearProgressIndicator(
              value: (dp.progressPct / 100).clamp(0.0, 1.0),
              minHeight: 10,
              backgroundColor: AppColors.edge,
              valueColor: const AlwaysStoppedAnimation(AppColors.primary),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Today: +${dp.earnedToday} / ${dp.dailyCap} DP cap',
                  style: AppTypography.caption()),
              Text('${dp.progressPct}%',
                  style: AppTypography.label(color: AppColors.primary)),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Streak card
// ---------------------------------------------------------------------------

class _StreakCard extends StatelessWidget {
  const _StreakCard({required this.streak, required this.protection});

  final ProgressStreak streak;
  final ProgressProtection protection;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MLDSectionHeader(title: 'STREAK'),
          Row(
            children: [
              Icon(
                streak.current > 0 ? Icons.local_fire_department_outlined : Icons.local_fire_department,
                color: streak.current > 0 ? AppColors.warning : AppColors.textDisabled,
                size: 34,
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${streak.current} day${streak.current == 1 ? '' : 's'}',
                        style: AppTypography.heading()),
                    Text('Best: ${streak.best} · ${streak.onTrackToday ? 'On track today' : 'Nothing logged yet today'}',
                        style: AppTypography.caption()),
                  ],
                ),
              ),
              _FreezePill(remaining: protection.freezesRemaining),
            ],
          ),
          if (protection.frozen) ...[
            const SizedBox(height: AppSpacing.lg),
            MLDWarningBanner(
              message: protection.graceUntilWallMs > 0
                  ? 'Progression paused — recovery grace until ${_formatTime(protection.graceUntilWallMs)}. '
                      'Restore the lost permission and your streak stays intact.'
                  : protection.pendingRelapseReason.isNotEmpty
                      ? protection.pendingRelapseReason
                      : 'Progression is temporarily paused.',
            ),
          ],
          const SizedBox(height: AppSpacing.lg),
          Text(
            'A clean day = at least one discipline action (session, alarm, monk or check-in) '
            'and no bailout, cage or relapse. Missed days are covered by freezes '
            '(${protection.freezesUsedThisMonth} used this month).',
            style: AppTypography.caption(),
          ),
        ],
      ),
    );
  }

  String _formatTime(int wallMs) {
    final d = DateTime.fromMillisecondsSinceEpoch(wallMs);
    final h = d.hour.toString().padLeft(2, '0');
    final m = d.minute.toString().padLeft(2, '0');
    return '$h:$m';
  }
}

class _FreezePill extends StatelessWidget {
  const _FreezePill({required this.remaining});

  final int remaining;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
      decoration: BoxDecoration(
        color: AppColors.elevated,
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.ac_unit, size: 16, color: AppColors.info),
          const SizedBox(width: AppSpacing.xs),
          Text('$remaining left', style: AppTypography.label()),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Check-in card
// ---------------------------------------------------------------------------

class _CheckInCard extends StatelessWidget {
  const _CheckInCard({
    required this.checkIn,
    required this.claiming,
    required this.onClaim,
  });

  final ProgressCheckIn checkIn;
  final bool claiming;
  final VoidCallback onClaim;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          MLDSectionHeader(
            title: 'DAILY CHECK-IN',
            action: Text(
              '${checkIn.totalCheckIns} total · ${checkIn.cyclesCompleted} cycles',
              style: AppTypography.caption(),
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: List.generate(checkIn.cycleRewards.length, (i) {
              final isPast = i < checkIn.cycleDay;
              final isToday = i == checkIn.cycleDay;
              return _CycleDayDot(
                day: i + 1,
                reward: checkIn.cycleRewards[i],
                state: isPast
                    ? _CycleState.done
                    : isToday
                        ? (checkIn.available ? _CycleState.today : _CycleState.claimed)
                        : _CycleState.locked,
              );
            }),
          ),
          const SizedBox(height: AppSpacing.xxl),
          SizedBox(
            width: double.infinity,
            child: MLDButton(
              // v2.5.5 audit fix: a shorter native rewards list with
              // `available: true` used to throw RangeError on the fixed
              // clamp(0, 6) index — clamp against the real length.
              label: checkIn.available
                  ? 'CHECK IN · +${checkIn.cycleRewards.isEmpty ? 0 : checkIn.cycleRewards[checkIn.cycleDay.clamp(0, checkIn.cycleRewards.length - 1)]} DP'
                  : 'CHECKED IN TODAY',
              icon: Icons.task_alt_outlined,
              variant: checkIn.available ? MLDButtonVariant.primary : MLDButtonVariant.secondary,
              loading: claiming,
              onPressed: checkIn.available ? onClaim : null,
            ),
          ),
        ],
      ),
    );
  }
}

enum _CycleState { done, today, claimed, locked }

class _CycleDayDot extends StatelessWidget {
  const _CycleDayDot({
    required this.day,
    required this.reward,
    required this.state,
  });

  final int day;
  final int reward;
  final _CycleState state;

  @override
  Widget build(BuildContext context) {
    final Color border;
    final Color fill;
    final IconData icon;
    switch (state) {
      case _CycleState.done:
        border = AppColors.success;
        fill = AppColors.success.withValues(alpha: 0.15);
        icon = Icons.check;
        break;
      case _CycleState.today:
        border = AppColors.primary;
        fill = AppColors.primary.withValues(alpha: 0.15);
        icon = Icons.radio_button_checked;
        break;
      case _CycleState.claimed:
        border = AppColors.success;
        fill = AppColors.success.withValues(alpha: 0.15);
        icon = Icons.check_circle;
        break;
      case _CycleState.locked:
        border = AppColors.edge;
        fill = Colors.transparent;
        icon = Icons.circle_outlined;
        break;
    }
    return Column(
      children: [
        Container(
          width: 38,
          height: 38,
          decoration: BoxDecoration(
            color: fill,
            shape: BoxShape.circle,
            border: Border.all(color: border, width: 1.5),
          ),
          child: Icon(icon, size: 18, color: border),
        ),
        const SizedBox(height: AppSpacing.xs),
        Text('+$reward', style: AppTypography.caption()),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Milestones card
// ---------------------------------------------------------------------------

class _MilestonesCard extends StatelessWidget {
  const _MilestonesCard({required this.streak});

  final ProgressStreak streak;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MLDSectionHeader(title: 'MILESTONES'),
          ...streak.milestones.map((m) => Padding(
                padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
                child: Row(
                  children: [
                    Icon(
                      m.achieved ? Icons.emoji_events_outlined : Icons.emoji_events_outlined,
                      size: 20,
                      color: m.achieved ? AppColors.warning : AppColors.textDisabled,
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Text(
                        '${m.days} clean days in a row',
                        style: AppTypography.body().copyWith(
                          color: m.achieved ? AppColors.textPrimary : AppColors.textSecondary,
                          decoration: m.achieved ? TextDecoration.none : TextDecoration.none,
                        ),
                      ),
                    ),
                    Text(
                      m.achieved ? '+${m.dp} DP · done' : '+${m.dp} DP',
                      style: AppTypography.label(
                          color: m.achieved ? AppColors.success : AppColors.textSecondary),
                    ),
                  ],
                ),
              )),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// DP ledger
// ---------------------------------------------------------------------------

class _DpHistoryCard extends StatelessWidget {
  const _DpHistoryCard({required this.awards});

  final List<DpAwardRecord> awards;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MLDSectionHeader(title: 'RECENT DISCIPLINE POINTS'),
          if (awards.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
              child: Text(
                'Complete a session, check in, or let the blocker intercept a reel — points land here.',
                style: AppTypography.caption(),
              ),
            )
          else
            ...awards.take(12).map((a) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs + 2),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(a.label, style: AppTypography.body()),
                      ),
                      if (a.capped)
                        const Padding(
                          padding: EdgeInsets.only(right: AppSpacing.sm),
                          child: Icon(Icons.speed, size: 14, color: AppColors.warning),
                        ),
                      Text('+${a.amount}', style: AppTypography.body().copyWith(
                        color: AppColors.success, fontWeight: FontWeight.w700)),
                    ],
                  ),
                )),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Relapse history
// ---------------------------------------------------------------------------

class _RelapseCard extends StatelessWidget {
  const _RelapseCard({required this.relapses});

  final List<RelapseRecord> relapses;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MLDSectionHeader(title: 'RELAPSE HISTORY'),
          if (relapses.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
              child: Text(
                'Nothing here. Keep it that way — but if it happens, the record helps you see patterns, not judge yourself.',
                style: AppTypography.caption(),
              ),
            )
          else
            ...relapses.take(10).map((r) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.history, size: 18, color: AppColors.danger),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(r.sourceLabel, style: AppTypography.body()),
                            const SizedBox(height: 2),
                            Text(
                              '${r.streakDaysAtRelapse}-day streak · ${_formatDate(r.timestampWall)}',
                              style: AppTypography.caption(),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                )),
        ],
      ),
    );
  }

  String _formatDate(int wallMs) {
    final d = DateTime.fromMillisecondsSinceEpoch(wallMs);
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }
}

// ---------------------------------------------------------------------------
// v2.2 Phase D — weekly break passes (planned breaks, not impulsive ones)
// ---------------------------------------------------------------------------

class _BreakPassCard extends StatefulWidget {
  const _BreakPassCard({
    required this.status,
    required this.onUse,
    required this.onEnd,
  });

  final BreakPassStatus status;
  final Future<void> Function(String pkg) onUse;
  final Future<void> Function() onEnd;

  @override
  State<_BreakPassCard> createState() => _BreakPassCardState();
}

class _BreakPassCardState extends State<_BreakPassCard> {
  final TextEditingController _pkgController = TextEditingController();
  bool _busy = false;

  static const _quickApps = [
    ('com.instagram.android', 'Instagram'),
    ('com.google.android.youtube', 'YouTube'),
    ('com.zhiliaoapp.musically', 'TikTok'),
    ('com.facebook.katana', 'Facebook'),
  ];

  @override
  void dispose() {
    _pkgController.dispose();
    super.dispose();
  }

  Future<void> _use(String pkg) async {
    if (_busy) return;
    setState(() => _busy = true);
    await widget.onUse(pkg);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.status;
    final minutes = (s.activeRemainingSeconds / 60).ceil();

    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.coffee_outlined, color: AppColors.info),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Break passes',
                    style: AppTypography.body(weight: FontWeight.w700)),
              ),
              Text(
                s.allowancePerWeek > 0
                    ? '${s.remaining}/${s.allowancePerWeek} left'
                    : 'off',
                style: AppTypography.caption(
                    color: s.remaining > 0 ? AppColors.success : null),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'A break pass stands the reels blocker down for 5 planned minutes on one app. They reset every Monday — and they never work during a session, cage, prime commit or monk mode.',
            style: AppTypography.caption(),
          ),
          const SizedBox(height: AppSpacing.lg),
          if (s.isActive) ...[
            Container(
              padding: const EdgeInsets.all(AppSpacing.lg),
              decoration: BoxDecoration(
                color: AppColors.info.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                    color: AppColors.info.withValues(alpha: 0.4)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.timer_outlined,
                      color: AppColors.info, size: 20),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Text(
                      'Break active on ${s.activePackage} — about $minutes min left',
                      style: AppTypography.caption(),
                    ),
                  ),
                  TextButton(
                    onPressed: _busy ? null : () => widget.onEnd(),
                    child: const Text('End now'),
                  ),
                ],
              ),
            ),
          ] else if (s.allowancePerWeek > 0 && s.remaining > 0) ...[
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: _quickApps
                  .map((a) => ActionChip(
                        label: Text(a.$2),
                        onPressed: _busy ? null : () => _use(a.$1),
                      ))
                  .toList(),
            ),
            const SizedBox(height: AppSpacing.md),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _pkgController,
                    decoration: const InputDecoration(
                      labelText: 'Other package name',
                      hintText: 'com.example.app',
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                MLDButton(
                  label: 'Use',
                  expanded: false,
                  loading: _busy,
                  onPressed: () {
                    final pkg = _pkgController.text.trim();
                    if (pkg.isNotEmpty) _use(pkg);
                  },
                ),
              ],
            ),
          ] else ...[
            Text(
              s.allowancePerWeek == 0
                  ? 'Break passes are currently disabled by remote config.'
                  : 'All ${s.allowancePerWeek} passes used this week. Fresh ones arrive Monday.',
              style: AppTypography.caption(color: AppColors.textDisabled),
            ),
          ],
        ],
      ),
    );
  }
}
