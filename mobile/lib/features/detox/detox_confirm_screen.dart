import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../shared/mld_widgets.dart';
import '../study/study_setup_screen.dart' show DetoxConfirmArgs;

/// Detox confirmation — the psychological checkpoint before activation
/// (UI/UX §18). No surprises: the user sees exactly what will happen.
class DetoxConfirmScreen extends StatelessWidget {
  const DetoxConfirmScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final args =
        ModalRoute.of(context)?.settings.arguments is DetoxConfirmArgs
            ? ModalRoute.of(context)!.settings.arguments as DetoxConfirmArgs
            : DetoxConfirmArgs(durationMinutes: 120, strictness: 'MAXLEVEL');

    final durationLabel = args.durationMinutes >= 60
        ? '${args.durationMinutes ~/ 60}h${args.durationMinutes % 60 > 0 ? ' ${args.durationMinutes % 60}m' : ''}'
        : '${args.durationMinutes} min';

    return Scaffold(
      appBar: AppBar(title: const Text('Ready to Commit?')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('YOUR DETOX SESSION WILL',
                  style: AppTypography.label()),
              const SizedBox(height: AppSpacing.lg),
              MLDCard(
                child: Column(
                  children: [
                    _row('Block selected apps', Icons.block),
                    _row('Enforce your restrictions', Icons.security),
                    _row('Track violations', Icons.warning_amber_rounded),
                    _row('Allow temporary unlock by your rules only', Icons.lock_open),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              MLDCard(
                color: AppColors.elevated,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('Session', style: AppTypography.body(color: AppColors.textSecondary)),
                    Text('$durationLabel · ${args.strictness}',
                        style: AppTypography.body(weight: FontWeight.w800)),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              const MLDWarningBanner(
                message:
                    'Early exit costs 500 coins. Shutdown or restart will not end your session.',
                tone: MLDBannerTone.warning,
              ),
              const SizedBox(height: AppSpacing.xxxl),
              MLDButton(
                label: 'START DETOX',
                icon: Icons.spa_outlined,
                onPressed: () =>
                    Navigator.of(context).pushReplacementNamed('/activation', arguments: args),
              ),
              const SizedBox(height: AppSpacing.md),
              MLDButton(
                label: 'Go Back',
                variant: MLDButtonVariant.secondary,
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(String text, IconData icon) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: Row(
        children: [
          Icon(icon, color: AppColors.success, size: 20),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Text(text, style: AppTypography.body(weight: FontWeight.w500))),
        ],
      ),
    );
  }
}
