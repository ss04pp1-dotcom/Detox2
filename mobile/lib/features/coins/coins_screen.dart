import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/ad_reward_manager.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';
// v2.5.5 audit fix: unused import removed (coin_history_screen.dart —
// nothing in this file names it; the route push uses the registered
// route name, not the widget class).

/// Coins screen (UI/UX §30–31): balance, earn via rewarded ads, exchange
/// explainer. Coins motivate discipline — they are NOT another addictive
/// mechanic (UI/UX §90): +1 per completed ad, nothing random, nothing flashy.
class CoinsScreen extends StatefulWidget {
  const CoinsScreen({super.key});

  @override
  State<CoinsScreen> createState() => _CoinsScreenState();
}

class _CoinsScreenState extends State<CoinsScreen> {
  bool _busy = false;
  String? _flash;

  @override
  void initState() {
    super.initState();
    AdRewardManager.instance.preload();
  }

  Future<void> _watchAd() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _flash = null;
    });

    final outcome = await AdRewardManager.instance.showAndEarn();

    if (!mounted) return;
    setState(() {
      _busy = false;
      switch (outcome) {
        case AdOutcome.earned:
          _flash = '+1 COIN — Nice. Keep going.';
          HapticFeedback.mediumImpact();
        case AdOutcome.dismissed:
          _flash = 'Ad closed before the reward — no coin this time.';
        case AdOutcome.failedToLoad:
          _flash = 'No ad available right now. Try again in a moment.';
        case AdOutcome.failedToShow:
          _flash = 'Could not play the ad. No coin was charged.';
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final coins = app.state.coins;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Your Coins'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeCoinHistory),
            child: const Text('History'),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                padding: const EdgeInsets.all(AppSpacing.xxxl),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFF241A08), AppColors.surface],
                  ),
                  borderRadius: BorderRadius.circular(AppRadii.hero),
                  border: Border.all(color: AppColors.warning.withValues(alpha: 0.3)),
                ),
                child: Column(
                  children: [
                    Text('BALANCE', style: AppTypography.label(color: AppColors.warning)),
                    const SizedBox(height: AppSpacing.md),
                    Text('$coins',
                        style: AppTypography.timer(size: 64, color: AppColors.warning)),
                    const SizedBox(height: AppSpacing.xxl),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.lock_open, size: 16, color: AppColors.textSecondary),
                        const SizedBox(width: 6),
                        Text(
                          '${AppConstants.tempUnlockCost} coins = ${AppConstants.tempUnlockMinutes} minute unlock',
                          style: AppTypography.caption(),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),

              if (_flash != null) ...[
                MLDWarningBanner(
                  message: _flash!,
                  tone: _flash!.startsWith('+1')
                      ? MLDBannerTone.success
                      : MLDBannerTone.info,
                ),
                const SizedBox(height: AppSpacing.xxl),
              ],

              const MLDSectionHeader(title: 'EARN'),
              MLDCard(
                child: Column(
                  children: [
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(AppSpacing.md),
                          decoration: BoxDecoration(
                            color: AppColors.primary.withValues(alpha: 0.12),
                            borderRadius: BorderRadius.circular(AppRadii.sm),
                          ),
                          child: const Icon(Icons.play_circle_outline,
                              color: AppColors.primary, size: 26),
                        ),
                        const SizedBox(width: AppSpacing.lg),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Watch a rewarded ad', style: AppTypography.body(weight: FontWeight.w700)),
                              Text('Complete the full ad to earn', style: AppTypography.caption()),
                            ],
                          ),
                        ),
                        const MLDCoinBadge(amount: AppConstants.coinsPerAd),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.xxl),
                    MLDButton(
                      label: 'WATCH AD',
                      icon: Icons.play_arrow,
                      onPressed: _busy ? null : _watchAd,
                      loading: _busy,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),

              const MLDSectionHeader(title: 'SPEND'),
              _spendRow(
                context,
                icon: Icons.lock_open,
                title: 'Temporary Unlock',
                subtitle: '${AppConstants.tempUnlockCost} coins → ${AppConstants.tempUnlockMinutes} minutes',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeTempUnlock),
              ),
              const SizedBox(height: AppSpacing.xxl),

              const MLDWarningBanner(
                message:
                    'Coins are earned only through completed rewarded ads. Balances and spends are recorded in an immutable ledger on this device.',
                tone: MLDBannerTone.info,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _spendRow(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadii.card),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          children: [
            Icon(icon, color: AppColors.primary, size: 24),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: AppTypography.body(weight: FontWeight.w700)),
                  Text(subtitle, style: AppTypography.caption()),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
