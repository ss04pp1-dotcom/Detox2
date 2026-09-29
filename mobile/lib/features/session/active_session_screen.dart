import 'dart:async';

import 'package:flutter/material.dart';

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

  // v2.9.3 r19 — PRIME OWNED SESSION: while a Prime commit owns this
  // session the ONLY exit is the TOTP emergency code, and this locked
  // surface is the user's whole world (the prime settings screen is
  // unreachable from here) — so the give-up entry must live on THIS
  // screen, or the promised exit does not exist at all.
  bool _primeActive = false;
  bool _primeBusy = false;

  // v2.5.5 audit fix: captured in didChangeDependencies (the legal seam for
  // inherited lookups) so the stream watcher below can check the bailout
  // suppression flag without an inherited lookup inside the listener.
  AppState? _app;

  @override
  void initState() {
    super.initState();
    _hadSession = true; // this screen is only mounted for an active session
    _checkPrime();
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

  /// One-shot read: does a Prime commit own this session? (Checked on
  /// mount only — the prime state cannot flip to active while a session
  /// is already running, and expiry ends the session itself.)
  Future<void> _checkPrime() async {
    final status = await NativeBridge.instance.getPrimeCommitStatus();
    if (!mounted) return;
    final active = status?['active'] == true;
    if (active != _primeActive) setState(() => _primeActive = active);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _app ??= AppStateScope.of(context);
  }

  @override
  void dispose() {
    _watch?.cancel();
    // v2.9.3 r19: never leave the completion-suppression flag latched —
    // a natural later completion must still navigate normally.
    _app?.suppressCompletionRedirect = false;
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

    // v2.9 r17 (user-requested total lockdown): BACK can never leave the
    // session surface — the native kiosk strips + key filter already kill
    // the hardware/gesture back outside the app; this seals the in-app
    // side. Dismissal only happens via the legitimate end-of-session flow.
    return PopScope(
      canPop: false,
      child: Scaffold(
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
              if (_primeActive) ...[
                const SizedBox(height: AppSpacing.md),
                const MLDStatusChip(
                  label: 'PRIME COMMIT — NO UNLOCKS, NO BAILOUTS',
                  color: AppColors.prime,
                ),
              ],
              const SizedBox(height: AppSpacing.xxl),

              if (isDetox) ...[
                // Detox keeps its original controls: End / Details + temp unlock
                // (Pause was never a Detox control; Study has no controls at all).
                Row(
                  children: [
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
                // While a Prime commit owns the session the temporary-unlock
                // path is refused natively anyway; show the one real exit
                // instead: the TOTP emergency give-up.
                if (_primeActive)
                  MLDButton(
                    label: 'END WITH EMERGENCY CODE',
                    icon: Icons.military_tech_outlined,
                    variant: MLDButtonVariant.danger,
                    loading: _primeBusy,
                    onPressed: _giveUpPrime,
                  )
                else
                  MLDButton(
                    label: unlockActive
                        ? 'UNLOCK ACTIVE · ${_fmt(tempUnlock!.remainingSeconds)}'
                        : 'TEMPORARY UNLOCK · 5 coins = 5 min',
                    icon: Icons.lock_open,
                    onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeTempUnlock),
                  ),
              ] else ...[
                // Study Mode deliberately has no End/Pause/Unlock control.
                // The native SessionKiosk wall is the hard lock and this
                // Flutter surface mirrors that promise: timer + status only.
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(AppSpacing.lg),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        AppColors.study.withValues(alpha: .14),
                        AppColors.surface,
                      ],
                    ),
                    borderRadius: BorderRadius.circular(AppRadii.card),
                    border: Border.all(color: AppColors.study.withValues(alpha: .32)),
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 42,
                        height: 42,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: AppColors.study.withValues(alpha: .14),
                        ),
                        child: const Icon(Icons.lock_rounded, color: AppColors.study),
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('STUDY MODE IS LOCKED', style: AppTypography.label(color: AppColors.study)),
                            const SizedBox(height: 3),
                            Text(
                              'No pause, end or temporary unlock. Stay focused until the timer reaches zero.',
                              style: AppTypography.caption(),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                if (_primeActive) ...[
                  const SizedBox(height: AppSpacing.md),
                  MLDButton(
                    label: 'END WITH EMERGENCY CODE',
                    icon: Icons.military_tech_outlined,
                    variant: MLDButtonVariant.danger,
                    loading: _primeBusy,
                    onPressed: _giveUpPrime,
                  ),
                ],
              ],
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

  // ignore: unused_element
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

  /// v2.9.3 r19 — the Prime give-up flow, reachable from the locked
  /// session surface itself (see _primeActive). TOTP verified natively;
  /// a relapse is NOT a celebration, so the completion redirect is
  /// suppressed (same pattern as the bailout screen).
  Future<void> _giveUpPrime() async {
    if (_primeBusy) return;
    final code = await _askEmergencyCode();
    if (!mounted || code == null) return;
    setState(() => _primeBusy = true);

    // Latch the suppression BEFORE the await — the native state push and
    // the method-channel reply are separate async events (bailout race
    // lesson, v2.5.5).
    final app = _app ?? AppStateScope.of(context);
    _app = app;
    app.suppressCompletionRedirect = true;

    final error = await NativeBridge.instance.giveUpPrimeCommit(code: code);
    if (!mounted) {
      app.suppressCompletionRedirect = false;
      return;
    }
    if (error != null) {
      app.suppressCompletionRedirect = false;
      setState(() => _primeBusy = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error)));
      return;
    }
    // Ended — a relapse goes straight home, no celebration.
    Navigator.of(context).pushNamedAndRemoveUntil(
      AppConstants.routeShell,
      (route) => false,
    );
  }

  /// TOTP emergency-code dialog (same flow as the Prime screen).
  Future<String?> _askEmergencyCode() async {
    final controller = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Emergency code'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
                'Enter the CURRENT 6-digit code from your emergency sheet. '
                'Each code works once for 5 minutes.\n\nGiving up resets '
                'your streak to Day 1.'),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: controller,
              autofocus: true,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: const InputDecoration(
                  border: OutlineInputBorder(), counterText: ''),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, null),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, controller.text.trim()),
              child: const Text('Use code')),
        ],
      ),
    );
    controller.dispose();
    return result;
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
    // v2.9 r17: back cannot dismiss the study-break surface either.
    return PopScope(
      canPop: false,
      child: Scaffold(
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
      ),
    );
  }
}

/// Cage view — v2.9.6 r22 (user request, verbatim intent: "কেস মুড =
/// স্টাডি মুডের যেই স্ক্রিনটা সেই রকম একদম হান্ড্রেড পার্সেন্ট"):
///
/// While the cage is active the surface is NO LONGER the old dark
/// minimal lock screen — it renders the SAME immersive mood screen the
/// user sees during a running session, and it FOLLOWS the active mood
/// (Study / Detox styling, icon, title, subject, stats and controls).
/// Whichever mood the session is in when the cage triggers, that is the
/// screen the cage shows; with no live session left it defaults to the
/// Study reference design.
///
/// The clock is the CAGE countdown (the intensified restriction), the
/// status chip says CAGE, and every session control survives — End
/// (bailout), Details, Temporary Unlock (natively refused with the
/// CAGE_ACTIVE message — the offer stays visible, exactly like the coin
/// features the user asked to keep), Emergency Call. Pause is the one
/// control hidden: a study break during punishment would neutralize the
/// cage. Ends automatically at zero.
class _CageView extends StatefulWidget {
  const _CageView();

  @override
  State<_CageView> createState() => _CageViewState();
}

class _CageViewState extends State<_CageView> {
  // v2.9.3 r19 pattern, mirrored here because the cage surface can also be
  // owned by a Prime commit — the TOTP give-up must stay reachable from
  // whatever locked surface the user is looking at.
  bool _primeActive = false;
  bool _primeBusy = false;
  AppState? _app;

  @override
  void initState() {
    super.initState();
    _checkPrime();
  }

  Future<void> _checkPrime() async {
    final status = await NativeBridge.instance.getPrimeCommitStatus();
    if (!mounted) return;
    final active = status?['active'] == true;
    if (active != _primeActive) setState(() => _primeActive = active);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _app ??= AppStateScope.of(context);
  }

  @override
  void dispose() {
    _app?.suppressCompletionRedirect = false;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final cage = app.state.cage;
    final s = app.state.session;
    // Promoted non-null capture — Dart cannot promote `s` through the
    // `live != null` bool below, so the narrowed value lives in `live`.
    final SessionSnapshot? live = (s != null && s.isActive) ? s : null;

    // Follow the active mood; default to the Study reference design when
    // the cage outlived its session.
    final isDetox = live != null && live.mode == SessionMode.detox;
    final accent = isDetox ? AppColors.detox : AppColors.study;
    final cageRemaining = cage?.remainingSeconds ?? 0;
    // Same unlock-state plumbing as the session screen (the Detox cage
    // mirrors the Detox screen's TEMPORARY UNLOCK button 1:1).
    final tempUnlock = app.state.tempUnlock;
    final unlockActive = tempUnlock?.active == true;

    // v2.9.3 r19: the cage view keeps the same back-seal as the session
    // view — a back press can never bubble out of the root route.
    return PopScope(
      canPop: false,
      child: Scaffold(
      backgroundColor: isDetox ? const Color(0xFF160D18) : AppColors.background,
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxl),
          child: Column(
            children: [
              // Same banner as the session screen — END stays the first
              // reachable control in the app.
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
              if (!isDetox && live != null && live.subjectName.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.xs),
                Text(live.subjectName,
                    style: AppTypography.caption(color: AppColors.textSecondary)),
              ],
              const SizedBox(height: AppSpacing.md),
              MLDStatusChip(
                label: 'CAGE • ${_fmtLong(cageRemaining)} LEFT',
                color: accent,
              ),
              const Spacer(),
              MLDTimer(
                remainingSeconds: cageRemaining,
                ringProgress:
                    1 - (cageRemaining / AppConstants.cageDurationSeconds),
                color: AppColors.textPrimary,
                ringColor: accent,
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
                    ? 'Cage active — repeated violations intensified your restriction.'
                    : 'Cage active — distractions stay blocked and intensified.',
                textAlign: TextAlign.center,
                style: AppTypography.caption(color: AppColors.textSecondary),
              ),
              const Spacer(),
              Row(
                children: [
                  Expanded(
                    child: MLDStatTile(
                      label: isDetox ? 'Apps restricted' : 'Blocked apps',
                      value: '${live?.blockedAppCount ?? 0}',
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: MLDStatTile(label: 'Warnings', value: '${live?.violationCount ?? 0}', accent: AppColors.warning)),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: MLDStatTile(label: 'Coins', value: '${app.state.coins}', accent: AppColors.warning)),
                ],
              ),
              if (_primeActive) ...[
                const SizedBox(height: AppSpacing.md),
                const MLDStatusChip(
                  label: 'PRIME COMMIT — NO UNLOCKS, NO BAILOUTS',
                  color: AppColors.prime,
                ),
              ],
              const SizedBox(height: AppSpacing.xxl),

              // v2.9.8 r24: the control area mirrors the ACTIVE MOOD's
              // session screen 1:1 (the r22 per-mood split) — Detox keeps
              // End / Details / Temporary Unlock; Study is the hard-locked
              // surface with no controls. Pause deliberately hidden — a
              // break during the cage would defeat the punishment.
              if (isDetox) ...[
                // Detox keeps its original controls: End / Details + temp
                // unlock (same as the Detox session screen).
                Row(
                  children: [
                    if (live != null) ...[
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
                    ],
                    Expanded(
                      child: MLDButton(
                        label: 'Details',
                        icon: Icons.info_outline,
                        variant: MLDButtonVariant.secondary,
                        expanded: false,
                        height: 48,
                        onPressed: () => _showDetails(cageRemaining),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
                // While a Prime commit owns the session the temporary-unlock
                // path is refused natively anyway; show the one real exit
                // instead: the TOTP emergency give-up.
                if (_primeActive)
                  MLDButton(
                    label: 'END WITH EMERGENCY CODE',
                    icon: Icons.military_tech_outlined,
                    variant: MLDButtonVariant.danger,
                    loading: _primeBusy,
                    onPressed: _giveUpPrime,
                  )
                else
                  MLDButton(
                    label: unlockActive
                        ? 'UNLOCK ACTIVE · ${_fmt(tempUnlock!.remainingSeconds)}'
                        : 'TEMPORARY UNLOCK · 5 coins = 5 min',
                    icon: Icons.lock_open,
                    onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeTempUnlock),
                  ),
              ] else ...[
                // Study Mode deliberately has no End/Pause/Unlock control —
                // the same hard-lock surface as the Study session screen;
                // the cage ends automatically when the timer reaches zero.
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(AppSpacing.lg),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        AppColors.study.withValues(alpha: .14),
                        AppColors.surface,
                      ],
                    ),
                    borderRadius: BorderRadius.circular(AppRadii.card),
                    border: Border.all(color: AppColors.study.withValues(alpha: .32)),
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 42,
                        height: 42,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: AppColors.study.withValues(alpha: .14),
                        ),
                        child: const Icon(Icons.lock_rounded, color: AppColors.study),
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('STUDY MODE IS LOCKED', style: AppTypography.label(color: AppColors.study)),
                            const SizedBox(height: 3),
                            Text(
                              'No pause, end or temporary unlock. Stay focused until the timer reaches zero.',
                              style: AppTypography.caption(),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                if (_primeActive) ...[
                  const SizedBox(height: AppSpacing.md),
                  MLDButton(
                    label: 'END WITH EMERGENCY CODE',
                    icon: Icons.military_tech_outlined,
                    variant: MLDButtonVariant.danger,
                    loading: _primeBusy,
                    onPressed: _giveUpPrime,
                  ),
                ],
              ],
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
      ),
    );
  }

  /// Details sheet — the session screen's summary facts, cage edition.
  Future<void> _showDetails(int cageRemaining) async {
    final app = AppStateScope.of(context);
    final s = app.state.session;
    // Promoted capture (same pattern as build).
    final SessionSnapshot? live = (s != null && s.isActive) ? s : null;
    final isDetox = live != null && live.mode == SessionMode.detox;
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
              Text('Cage Details', style: AppTypography.section()),
              const SizedBox(height: AppSpacing.xxl),
              _detailRow('Mode', isDetox ? 'Detox' : 'Study'),
              _detailRow('Cage time', '${_fmtLong(cageRemaining)} remaining'),
              if (live != null) ...[
                _detailRow('Session time', '${_fmtLong(live.remainingSeconds)} of ${_fmtLong(live.totalSeconds)} remaining'),
                _detailRow('Progress', '${(live.progress * 100).round()}%'),
                _detailRow('Apps ${isDetox ? 'restricted' : 'blocked'}', '${live.blockedAppCount}'),
                if (!isDetox) _detailRow('Breaks used', '${live.pauseCount} of ${live.maxPauses}'),
                _detailRow('Warnings', '${live.violationCount}'),
              ],
              _detailRow('Coins', '${app.state.coins}'),
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

  /// v2.9.3 r19 — the Prime give-up flow, reachable from this locked
  /// surface too (see _primeActive). TOTP verified natively; a relapse is
  /// NOT a celebration, so the completion redirect is suppressed (same
  /// pattern as the bailout screen).
  Future<void> _giveUpPrime() async {
    if (_primeBusy) return;
    final code = await _askEmergencyCode();
    if (!mounted || code == null) return;
    setState(() => _primeBusy = true);

    final app = _app ?? AppStateScope.of(context);
    _app = app;
    app.suppressCompletionRedirect = true;

    final error = await NativeBridge.instance.giveUpPrimeCommit(code: code);
    if (!mounted) {
      app.suppressCompletionRedirect = false;
      return;
    }
    if (error != null) {
      app.suppressCompletionRedirect = false;
      setState(() => _primeBusy = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error)));
      return;
    }
    Navigator.of(context).pushNamedAndRemoveUntil(
      AppConstants.routeShell,
      (route) => false,
    );
  }

  /// TOTP emergency-code dialog (same flow as the Prime screen).
  Future<String?> _askEmergencyCode() async {
    final controller = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Emergency code'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
                'Enter the CURRENT 6-digit code from your emergency sheet. '
                'Each code works once for 5 minutes.\n\nGiving up resets '
                'your streak to Day 1.'),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: controller,
              autofocus: true,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: const InputDecoration(
                  border: OutlineInputBorder(), counterText: ''),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, null),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, controller.text.trim()),
              child: const Text('Use code')),
        ],
      ),
    );
    controller.dispose();
    return result;
  }

  /// m:ss — same format as the session screen's unlock chip.
  String _fmt(int s) {
    final m = s ~/ 60;
    final sec = s % 60;
    return '$m:${sec.toString().padLeft(2, '0')}';
  }

  String _fmtLong(int s) {
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    if (h > 0) return '${h}h ${m}m';
    return '${m}m';
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
