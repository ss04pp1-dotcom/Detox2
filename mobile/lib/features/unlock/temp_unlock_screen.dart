import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Temporary Unlock (UI/UX §29, PRD §15–17): 5 coins = 5 minutes for a
/// chosen allowlist. The unlock is validated + controlled NATIVELY — this
/// screen only renders the offer (TRD §41).
class TempUnlockScreen extends StatefulWidget {
  const TempUnlockScreen({super.key});

  @override
  State<TempUnlockScreen> createState() => _TempUnlockScreenState();
}

class _TempUnlockScreenState extends State<TempUnlockScreen> {
  final Set<String> _selected = {};
  bool _busy = false;
  String? _error;

  static const _unlockable = [
    ('com.android.camera2', 'Camera', Icons.photo_camera_outlined),
    ('com.google.android.apps.photos', 'Gallery', Icons.photo_library_outlined),
    ('com.android.chrome', 'Chrome', Icons.language),
  ];

  Future<void> _unlock() async {
    if (_busy || _selected.isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
    });

    final result = await NativeBridge.instance.requestTempUnlock(_selected.toList());

    if (!mounted) return;
    if (result.isOk) {
      HapticFeedback.mediumImpact();
      // v2.5.7 (C-3): mirror the coin spend to the server ledger
      // (best-effort; the device ledger is the authority).
      unawaited(ApiClient.instance.reportCoinSpend(
        type: 'TEMP_UNLOCK_SPEND',
        amount: AppConstants.tempUnlockCost,
        reference: 'tu_${DateTime.now().millisecondsSinceEpoch}',
      ));
      Navigator.of(context).pop();
    } else {
      setState(() {
        _busy = false;
        _error = switch (result.error?.code) {
          'INSUFFICIENT_COINS' => 'Not enough coins. Watch rewarded ads to earn more.',
          'CAGE_ACTIVE' => 'Temporary unlock is disabled while Cage is active.',
          'SESSION_NOT_ACTIVE' => 'No active session — start a Study or Detox session first.',
          _ => result.error?.message ?? 'Unlock failed.',
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final coins = app.state.coins;
    final active = app.state.tempUnlock?.active == true;
    final activeRemaining = app.state.tempUnlock?.remainingSeconds ?? 0;

    return Scaffold(
      appBar: AppBar(title: const Text('Temporary Unlock')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (active) ...[
                MLDCard(
                  color: AppColors.success.withValues(alpha: 0.08),
                  borderColor: AppColors.success.withValues(alpha: 0.4),
                  child: Column(
                    children: [
                      const Icon(Icons.lock_open, color: AppColors.success, size: 36),
                      const SizedBox(height: AppSpacing.md),
                      Text('UNLOCK ACTIVE',
                          style: AppTypography.label(color: AppColors.success)),
                      const SizedBox(height: AppSpacing.md),
                      Text(
                        '${activeRemaining ~/ 60}:${(activeRemaining % 60).toString().padLeft(2, '0')}',
                        style: AppTypography.timer(size: 44, color: AppColors.success),
                      ),
                      const SizedBox(height: AppSpacing.md),
                      Text(
                        'Enforcement resumes automatically when this ends.',
                        textAlign: TextAlign.center,
                        style: AppTypography.caption(),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.xxl),
              ],

              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  MLDCoinBadge(amount: AppConstants.tempUnlockCost, large: true),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16),
                    child: Icon(Icons.arrow_forward, color: AppColors.textSecondary),
                  ),
                  Text(
                    '${AppConstants.tempUnlockMinutes} minutes',
                    style: AppTypography.heading(),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.sm),
              Text('Your balance',
                  textAlign: TextAlign.center, style: AppTypography.caption()),
              Text('$coins',
                  textAlign: TextAlign.center,
                  style: AppTypography.timer(size: 34, color: AppColors.warning)),
              const SizedBox(height: AppSpacing.xxl),

              MLDSectionHeader(title: 'CHOOSE ACCESS'),
              for (final (pkg, label, icon) in _unlockable)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.md),
                  child: _selectTile(pkg, label, icon),
                ),
              const SizedBox(height: AppSpacing.xxl),

              if (_error != null) ...[
                MLDWarningBanner(message: _error!, tone: MLDBannerTone.danger),
                const SizedBox(height: AppSpacing.xxl),
              ],

              MLDButton(
                label: 'UNLOCK FOR ${AppConstants.tempUnlockMinutes} MIN',
                icon: Icons.lock_open,
                onPressed: _selected.isNotEmpty && coins >= AppConstants.tempUnlockCost && !active
                    ? _unlock
                    : null,
                loading: _busy,
              ),
              if (coins < AppConstants.tempUnlockCost) ...[
                const SizedBox(height: AppSpacing.md),
                TextButton(
                  onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeCoins),
                  child: const Text('Earn coins with rewarded ads'),
                ),
              ],
              const SizedBox(height: AppSpacing.xxl),
              const MLDWarningBanner(
                message:
                    'The unlock does NOT end your session. Your other rules stay enforced, and the unlock expires automatically.',
                tone: MLDBannerTone.info,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _selectTile(String pkg, String label, IconData icon) {
    final selected = _selected.contains(pkg);
    final allowed = _selected.length < 2 || selected;
    return InkWell(
      onTap: allowed
          ? () => setState(() => selected ? _selected.remove(pkg) : _selected.add(pkg))
          : null,
      borderRadius: BorderRadius.circular(AppRadii.sm),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary.withValues(alpha: 0.1) : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: selected ? AppColors.primary : AppColors.edge),
        ),
        child: Row(
          children: [
            Icon(icon, color: selected ? AppColors.primary : AppColors.textSecondary),
            const SizedBox(width: AppSpacing.lg),
            Expanded(child: Text(label, style: AppTypography.body(weight: FontWeight.w600))),
            Icon(
              selected ? Icons.check_circle : Icons.circle_outlined,
              color: selected ? AppColors.primary : AppColors.textSecondary,
            ),
          ],
        ),
      ),
    );
  }
}
