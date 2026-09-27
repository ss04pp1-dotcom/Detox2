import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/app_state.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_timer.dart';
import '../../shared/mld_widgets.dart';
import 'completion_screen.dart';

/// The immersive active-session experience (UI/UX §20–22, §65, §89).
///
/// "Less UI is better." Mode, timer, status, relevant stats, Temporary
/// Unlock, Emergency. No settings, no analytics, no navigation.
///
/// The timer digits are VISUAL ONLY — Kotlin owns the clock (TRD §66).
class ActiveSessionScreen extends StatefulWidget {
  const ActiveSessionScreen({super.key, this.embedded = false});

  final bool embedded;

  @override
  State<ActiveSessionScreen> createState() => _ActiveSessionScreenState();
}

class _ActiveSessionScreenState extends State<ActiveSessionScreen> {
  StreamSubscription<DeviceState>? _watch;
  bool _hadSession = false;

  // v2.5.5 audit fix: captured in didChangeDependencies (the legal seam for
  // inherited lookups) so the stream watcher below can check the bailout
  // suppression flag without an inherited lookup inside the listener.
  AppState? _app;

  @override
  void initState() {
    super.initState();
    _hadSession = true; // this screen is only mounted for an active session
    _watch = NativeBridge.instance.stateStream.listen((s) {
      if (!mounted || !_hadSession) return;
      if (s.session == null || !s.session!.isActive) {
        _hadSession = false;
        // v2.5.5 audit fix: when a bailout navigation is in flight the
        // bailout screen owns the post-session routing (shell — the user
        // paid 500 coins and must NOT see the completion celebration).
        if (_app?.suppressCompletionRedirect == true) return;
        Navigator.of(context).pushNamedAndRemoveUntil(
          AppConstants.routeCompletion,
          (route) => false,
        );
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _app ??= AppStateScope.of(context);
  }

  @override
  void dispose() {
    _watch?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);

    if (!app.bootstrapped) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    // Cage takes over the whole experience.
    if (app.state.cageActive) {
      return const _CageView();
    }

    final s = app.state.session;
    if (s == null || !s.isActive) {
      // Session ended while this screen was open.
      return const _CompletionRedirect();
    }

    // Study break: enforcement is paused, countdown to auto-resume.
    if (s.isPaused) return _BreakView(session: s);

    final isDetox = s.mode == SessionMode.detox;
    // v2.6 reference design — each mode carries its signature accent.
    final accent = isDetox ? AppColors.detox : AppColors.study;
    final tempUnlock = app.state.tempUnlock;
    final unlockActive = tempUnlock?.active == true;

    return Scaffold(
      backgroundColor: isDetox ? const Color(0xFF160D18) : AppColors.background,
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxl),
          child: Column(
            children: [
              // v2.7 r13 — emergency dialer-only lockdown banner (renders
              // nothing while inactive). Lives above everything so END is
              // always the first reachable control in our app.
              const MLDEmergencyBanner(),
              const SizedBox(height: AppSpacing.xl),
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: accent.withValues(alpha: 0.12),
                  border: Border.all(color: accent.withValues(alpha: 0.35)),
                ),
                child: Icon(
                  isDetox ? Icons.spa_outlined : Icons.menu_book_outlined,
                  size: 34,
                  color: accent,
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                isDetox ? 'Detox Mode' : 'Study Mode',
                style: AppTypography.heading(),
              ),
              if (!isDetox && s.subjectName.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.xs),
                Text(s.subjectName,
                    style: AppTypography.caption(color: AppColors.textSecondary)),
              ],
              const SizedBox(height: AppSpacing.md),
              MLDStatusChip(
                label: unlockActive
                    ? 'UNLOCK ACTIVE · ${_fmt(tempUnlock!.remainingSeconds)} LEFT'
                    : 'RUNNING • ${_fmtLong(s.remainingSeconds)} LEFT',
                color: unlockActive ? AppColors.success : accent,
              ),
              const Spacer(),
              MLDTimer(
                remainingSeconds: s.remainingSeconds,
                ringProgress: s.progress,
                color: unlockActive ? AppColors.success : AppColors.textPrimary,
                ringColor: unlockActive ? AppColors.success : accent,
                size: 48,
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                'Remaining',
                style: AppTypography.label(),
              ),
              const SizedBox(height: AppSpacing.md),
              Text(
                isDetox
                    ? 'No distractions! Keep going!'
                    : 'Focus active — distractions stay blocked.',
                textAlign: TextAlign.center,
                style: AppTypography.caption(color: AppColors.textSecondary),
              ),
              const Spacer(),
              Row(
                children: [
                  Expanded(
                    child: MLDStatTile(
                      label: isDetox ? 'Apps restricted' : 'Blocked apps',
                      value: '${s.blockedAppCount}',
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: MLDStatTile(label: 'Warnings', value: '${s.violationCount}', accent: AppColors.warning)),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: MLDStatTile(label: 'Coins', value: '${app.state.coins}', accent: AppColors.warning)),
                ],
              ),
              const SizedBox(height: AppSpacing.xxl),

              // Primary control row (v2.6 reference design: Pause / End / Details).
              Row(
                children: [
                  if (!isDetox &&
                      s.status == SessionStatus.active &&
                      !unlockActive &&
                      s.pauseCount < s.maxPauses) ...[
                    Expanded(
                      child: MLDButton(
                        label: 'Pause',
                        icon: Icons.pause_rounded,
                        expanded: false,
                        height: 48,
                        onPressed: _pickBreak,
                      ),
                    ),
                    const SizedBox(width: AppSpacing.md),
                  ],
                  Expanded(
                    child: MLDButton(
                      label: 'End',
                      icon: Icons.stop_rounded,
                      variant: MLDButtonVariant.danger,
                      expanded: false,
                      height: 48,
                      onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeBailout),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: MLDButton(
                      label: 'Details',
                      icon: Icons.info_outline,
                      variant: MLDButtonVariant.secondary,
                      expanded: false,
                      height: 48,
                      onPressed: () => _showDetails(s),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.md),

              MLDButton(
                label: unlockActive
                    ? 'UNLOCK ACTIVE · ${_fmt(tempUnlock!.remainingSeconds)}'
                    : 'TEMPORARY UNLOCK · 5 coins = 5 min',
                icon: Icons.lock_open,
                onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeTempUnlock),
              ),
              const SizedBox(height: AppSpacing.md),

              // Emergency is ALWAYS discoverable (PRD §27, UI/UX §88).
              TextButton.icon(
                onPressed: () => NativeBridge.instance.openEmergencyDialer(),
                icon: const Icon(Icons.emergency_outlined, size: 18, color: AppColors.danger),
                label: const Text('Emergency Call',
                    style: TextStyle(color: AppColors.danger, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Session details sheet (mockup Screen 26): the summary facts plus the
  /// secondary controls that used to clutter the main session screen.
  Future<void> _showDetails(SessionSnapshot s) async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.card)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xxl, AppSpacing.xxl, AppSpacing.xxl, AppSpacing.xxl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Session Details', style: AppTypography.section()),
              const SizedBox(height: AppSpacing.xxl),
              _detailRow('Mode', s.mode == SessionMode.detox ? 'Detox' : 'Study'),
              _detailRow('Time', '${_fmtLong(s.remainingSeconds)} of ${_fmtLong(s.totalSeconds)} remaining'),
              _detailRow('Progress', '${(s.progress * 100).round()}%'),
              _detailRow(
                  'Apps ${s.mode == SessionMode.detox ? 'restricted' : 'blocked'}',
                  '${s.blockedAppCount}'),
              if (s.mode != SessionMode.detox)
                _detailRow('Breaks used', '${s.pauseCount} of ${s.maxPauses}'),
              _detailRow('Warnings', '${s.violationCount}'),
              const SizedBox(height: AppSpacing.xl),
              MLDButton(
                label: 'CLOSE',
                variant: MLDButtonVariant.secondary,
                onPressed: () => Navigator.pop(ctx),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _detailRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: Row(
        children: [
          Expanded(child: Text(label, style: AppTypography.caption())),
          Text(value, style: AppTypography.body(weight: FontWeight.w700)),
        ],
      ),
    );
  }

  Future<void> _pickBreak() async {
    final minutes = await showDialog<int>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('Take a break'),
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(24, 0, 24, 12),
            child: Text(
              'Enforcement pauses and the break ends by itself. Your session '
              'end moves back by the length of the break.',
              style: TextStyle(fontSize: 12),
            ),
          ),
          for (final m in const [5, 10])
            SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, m),
              child: Text('$m minutes'),
            ),
        ],
      ),
    );
    if (minutes == null || !mounted) return;
    final error = await NativeBridge.instance.pauseSession(minutes);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
    }
  }

  String _fmt(int s) {
    final m = s ~/ 60;
    final sec = s % 60;
    return '$m:${sec.toString().padLeft(2, '0')}';
  }

  /// "1h 25m" / "45m" — for status chips (v2.6 reference design).
  String _fmtLong(int s) {
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    if (h > 0) return '${h}h ${m}m';
    return '${m}m';
  }
}

/// Study break view (v2.5 r9.4): enforcement is paused; the break counts
/// down locally and ends by itself natively (alarm + sweep), or immediately
/// via RESUME NOW.
class _BreakView extends StatefulWidget {
  const _BreakView({required this.session});

  final SessionSnapshot session;

  @override
  State<_BreakView> createState() => _BreakViewState();
}

class _BreakViewState extends State<_BreakView> {
  Timer? _tick;
  late int _baseRemaining;
  late DateTime _baseAt;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _sync();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  void _sync() {
    _baseRemaining = widget.session.pauseRemainingSeconds;
    _baseAt = DateTime.now();
  }

  @override
  void didUpdateWidget(covariant _BreakView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.session.pauseRemainingSeconds != widget.session.pauseRemainingSeconds ||
        oldWidget.session.pauseCount != widget.session.pauseCount) {
      _sync();
    }
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  int get _remaining {
    final gone = DateTime.now().difference(_baseAt).inSeconds;
    final left = _baseRemaining - gone;
    return left < 0 ? 0 : left;
  }

  Future<void> _resume() async {
    if (_busy) return;
    setState(() => _busy = true);
    final error = await NativeBridge.instance.resumeSession();
    if (!mounted) return;
    setState(() => _busy = false);
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
    }
  }

  String _fmt(int s) {
    final m = s ~/ 60;
    final sec = s % 60;
    return '$m:${sec.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxl),
          child: Column(
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.coffee_outlined, size: 18, color: AppColors.success),
                  const SizedBox(width: AppSpacing.sm),
                  Text('STUDY BREAK', style: AppTypography.label(color: AppColors.success)),
                ],
              ),
              if (s.subjectName.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.xs),
                Text(s.subjectName,
                    style: AppTypography.caption(color: AppColors.textSecondary)),
              ],
              const Spacer(),
              MLDTimer(
                remainingSeconds: _remaining,
                ringProgress: null,
                color: AppColors.success,
              ),
              const SizedBox(height: AppSpacing.xxl),
              Text(
                'Enforcement is paused. The break ends on its own — your '
                'session has ${_fmt(s.remainingSeconds)} of focus time left and '
                'will not start counting down until you are back.',
                textAlign: TextAlign.center,
                style: AppTypography.caption(color: AppColors.textSecondary),
              ),
              const Spacer(),
              MLDButton(
                label: 'RESUME NOW',
                icon: Icons.play_arrow_rounded,
                loading: _busy,
                onPressed: _resume,
              ),
              const SizedBox(height: AppSpacing.md),
              Text(
                'Break ${s.pauseCount} of ${s.maxPauses}',
                style: AppTypography.caption(color: AppColors.textDisabled),
              ),
              const SizedBox(height: AppSpacing.md),
              TextButton.icon(
                onPressed: () => NativeBridge.instance.openEmergencyDialer(),
                icon: const Icon(Icons.emergency_outlined, size: 18, color: AppColors.danger),
                label: const Text('Emergency',
                    style: TextStyle(color: AppColors.danger, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Cage view (UI/UX §27–28): darker, unmistakable, countdown, no buttons
/// that would hand back access. Ends automatically at zero.
class _CageView extends StatelessWidget {
  const _CageView();

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final cage = app.state.cage;

    return Scaffold(
      backgroundColor: AppColors.cageBackground,
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH,
          child: Column(
            children: [
              const SizedBox(height: AppSpacing.massive),
              Container(
                padding: const EdgeInsets.all(AppSpacing.xl),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.danger.withValues(alpha: 0.12),
                  border: Border.all(color: AppColors.danger.withValues(alpha: 0.4)),
                ),
                child: const Icon(Icons.lock, size: 44, color: AppColors.danger),
              ),
              const SizedBox(height: AppSpacing.xxl),
              Text('CAGE', style: AppTypography.display(color: AppColors.danger)),
              const SizedBox(height: AppSpacing.xxxl),
              MLDTimer(
                remainingSeconds: cage?.remainingSeconds ?? 0,
                color: AppColors.danger,
                showRing: true,
                ringProgress: 1 - ((cage?.remainingSeconds ?? 0) / AppConstants.cageDurationSeconds),
                ringColor: AppColors.danger,
                ringSize: 200,
              ),
              const SizedBox(height: AppSpacing.xxxl),
              Text(
                'Repeated violations detected.\nYour restriction has been temporarily intensified.',
                textAlign: TextAlign.center,
                style: AppTypography.body(color: AppColors.textSecondary),
              ),
              const Spacer(),
              Text(
                'Cage ends automatically when the timer reaches zero.',
                textAlign: TextAlign.center,
                style: AppTypography.caption(color: AppColors.textDisabled),
              ),
              const SizedBox(height: AppSpacing.md),
              TextButton.icon(
                onPressed: () => NativeBridge.instance.openEmergencyDialer(),
                icon: const Icon(Icons.emergency_outlined, size: 18, color: AppColors.danger),
                label: const Text('Emergency',
                    style: TextStyle(color: AppColors.danger, fontWeight: FontWeight.w700)),
              ),
              const SizedBox(height: AppSpacing.xxl),
            ],
          ),
        ),
      ),
    );
  }
}

class _CompletionRedirect extends StatelessWidget {
  const _CompletionRedirect();

  @override
  Widget build(BuildContext context) {
    // Native state says no active session -> show completion summary.
    return const CompletionScreen();
  }
}
