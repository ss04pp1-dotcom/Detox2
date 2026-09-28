import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Session completion (UI/UX §50, §91): satisfying but short. "You kept
/// your commitment." Then full control returns.
class CompletionScreen extends StatefulWidget {
  const CompletionScreen({super.key});

  @override
  State<CompletionScreen> createState() => _CompletionScreenState();
}

class _CompletionScreenState extends State<CompletionScreen> {
  // v2.5.5 audit fix: the summary row used to read `app.state.coins >= 0`
  // — a tautology that is ALWAYS true, so "No bailout ✓" showed even
  // right after the user paid the 500-coin bailout. Read the real
  // last-session outcome from native history instead.
  bool _lastBailedOut = false;
  int _focusMinutes = 0;
  int _violations = 0;

  @override
  void initState() {
    super.initState();
    HapticFeedback.heavyImpact();
    _loadLastOutcome();
  }

  Future<void> _loadLastOutcome() async {
    final history = await NativeBridge.instance.getHistory(limit: 1);
    if (!mounted) return;
    if (history.isNotEmpty) {
      setState(() {
        _lastBailedOut = history.first.bailedOut;
        _focusMinutes = history.first.durationMinutes;
        _violations = history.first.violations;
      });
    }
  }

  String get _focusLabel {
    final h = _focusMinutes ~/ 60;
    final m = _focusMinutes % 60;
    if (h > 0) return '${h}h ${m.toString().padLeft(2, '0')}m';
    return '${m}m';
  }

  @override
  Widget build(BuildContext context) {
    final progress = AppStateScope.of(context).state.progress;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH,
          child: Column(
            children: [
              const Spacer(),
              TweenAnimationBuilder<double>(
                tween: Tween(begin: 0.6, end: 1.0),
                duration: const Duration(milliseconds: 600),
                curve: Curves.elasticOut,
                builder: (context, v, child) => Transform.scale(scale: v, child: child),
                child: Container(
                  width: 110,
                  height: 110,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: AppColors.success.withValues(alpha: 0.12),
                    border: Border.all(color: AppColors.success.withValues(alpha: 0.5), width: 2),
                  ),
                  child: const Icon(Icons.emoji_events_outlined, size: 54, color: AppColors.success),
                ),
              ),
              const SizedBox(height: AppSpacing.massive),
              // v2.6 reference design (mockup Screen 27).
              Text('Session Completed!',
                  textAlign: TextAlign.center,
                  style: AppTypography.display(color: AppColors.success)),
              const SizedBox(height: AppSpacing.md),
              Text('Great job! You stayed focused.',
                  style: AppTypography.body(color: AppColors.textSecondary, weight: FontWeight.w500)),
              const Spacer(),
              Row(
                children: [
                  Expanded(
                    child: _StatCard(
                      icon: Icons.timer_outlined,
                      accent: AppColors.success,
                      label: 'Focus Time',
                      value: _focusLabel,
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: _StatCard(
                      icon: Icons.block_outlined,
                      accent: AppColors.primary,
                      label: 'Attempts Blocked',
                      value: '$_violations',
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: _StatCard(
                      icon: Icons.terrain_outlined,
                      accent: AppColors.monk,
                      label: 'DP Balance',
                      value: progress == null ? '—' : '${progress.dp}',
                    ),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.lg),
              if (_lastBailedOut)
                const MLDWarningBanner(
                  message: 'This session ended via the coin bailout.',
                  tone: MLDBannerTone.warning,
                )
              else
                const MLDWarningBanner(
                  message: 'No bailout — you kept your commitment.',
                  tone: MLDBannerTone.success,
                ),
              const SizedBox(height: AppSpacing.xxxl),
              MLDButton(
                label: 'BACK TO HOME',
                onPressed: () => Navigator.of(context).pushNamedAndRemoveUntil(
                  AppConstants.routeShell,
                  (route) => false,
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
            ],
          ),
        ),
      ),
    );
  }
}

/// Compact outcome stat card (v2.6 reference design Screen 27).
class _StatCard extends StatelessWidget {
  const _StatCard({
    required this.icon,
    required this.accent,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final Color accent;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        children: [
          Icon(icon, color: accent, size: 20),
          const SizedBox(height: AppSpacing.md),
          Text(value, style: AppTypography.heading().copyWith(fontSize: 19)),
          const SizedBox(height: 2),
          Text(label,
              textAlign: TextAlign.center,
              style: AppTypography.caption().copyWith(fontSize: 11)),
        ],
      ),
    );
  }
}

/// Recovery screen (UI/UX §49, §70): an incomplete enforcement state was
/// detected (process death, reboot, permission loss). No arbitrary
/// "End Session" button is offered.
class RecoveryScreen extends StatelessWidget {
  const RecoveryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final s = app.state.session;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(Icons.restart_alt, size: 64, color: AppColors.warning),
              const SizedBox(height: AppSpacing.xxl),
              Text('SESSION RECOVERY',
                  textAlign: TextAlign.center,
                  style: AppTypography.heading()),
              const SizedBox(height: AppSpacing.md),
              Text(
                'Your previous session is still active.\nWe are restoring your focus rules.',
                textAlign: TextAlign.center,
                style: AppTypography.body(color: AppColors.textSecondary),
              ),
              const SizedBox(height: AppSpacing.xxxl),
              MLDCard(
                child: Column(
                  children: [
                    Text('REMAINING', style: AppTypography.label()),
                    const SizedBox(height: AppSpacing.md),
                    Text(
                      _fmtRemaining(s?.remainingSeconds ?? 0),
                      style: AppTypography.timer(size: 40, color: AppColors.warning),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxxl),
              MLDButton(
                label: 'CONTINUE',
                onPressed: () => Navigator.of(context).pushNamedAndRemoveUntil(
                  AppConstants.routeActiveSession,
                  (route) => false,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _fmtRemaining(int seconds) {
    final h = seconds ~/ 3600;
    final m = (seconds % 3600) ~/ 60;
    final s = seconds % 60;
    if (h > 0) return '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }
}
