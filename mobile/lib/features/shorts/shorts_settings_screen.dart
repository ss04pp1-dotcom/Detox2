import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Shorts blocker settings (UI/UX §43): platform toggles, warning count,
/// cage duration — all bounded by remote config.
class ShortsSettingsScreen extends StatefulWidget {
  const ShortsSettingsScreen({super.key});

  @override
  State<ShortsSettingsScreen> createState() => _ShortsSettingsScreenState();
}

class _ShortsSettingsScreenState extends State<ShortsSettingsScreen> {
  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final shorts = app.state.shorts;

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Shorts Blocker')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              MLDCard(
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Status', style: AppTypography.label()),
                          Text(
                            shorts.enabled ? 'ON — blocking short-form content' : 'OFF',
                            style: AppTypography.body(weight: FontWeight.w700),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: shorts.enabled,
                      onChanged: (_) => _toggleMaster(context),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),

              const MLDSectionHeader(title: 'SUPPORTED APPS'),
              if (shorts.platforms.isEmpty)
                const MLDWarningBanner(
                  message: 'Platform list is loading from the enforcement engine…',
                  tone: MLDBannerTone.info,
                )
              else
                for (final p in shorts.platforms) ...[
                  _platformTile(context, p),
                  const SizedBox(height: AppSpacing.md),
                ],
              const SizedBox(height: AppSpacing.xxl),

              const MLDSectionHeader(title: 'ESCALATION'),
              MLDCard(
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Warnings before Cage', style: AppTypography.body()),
                        Text('${shorts.warningLimit}',
                            style: AppTypography.body(weight: FontWeight.w800)),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Cage duration', style: AppTypography.body()),
                        Text('${AppConstants.cageDurationSeconds ~/ 60} minutes',
                            style: AppTypography.body(weight: FontWeight.w800)),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Today\'s warnings', style: AppTypography.body()),
                        Text('${shorts.warningCount} / ${shorts.warningLimit}',
                            style: AppTypography.body(
                              color: shorts.warningCount >= shorts.warningLimit
                                  ? AppColors.danger
                                  : AppColors.warning,
                              weight: FontWeight.w800,
                            )),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              const MLDWarningBanner(
                message:
                    'The warning counter is shared across all platforms — switching apps does not reset it.',
                tone: MLDBannerTone.info,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _toggleMaster(BuildContext context) async {
    final app = AppStateScope.of(context);
    // v2.5.5 audit fix: the native verdict was ignored — a refused write
    // silently reverted on the next state push.
    final ok = await app.toggleShorts(!app.state.shorts.enabled);
    if (!mounted) return;
    if (!ok) _toast('Engine refused the change.');
    setState(() {});
  }

  Future<void> _togglePlatform(BuildContext context, ShortsPlatform p) async {
    final app = AppStateScope.of(context);
    // v2.5.5 audit fix: same as above — surface native write failures.
    final ok = await app.toggleShortsPlatform(p.packageName, !p.enabled);
    if (!mounted) return;
    if (!ok) _toast('Engine refused the change.');
    setState(() {});
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  Widget _platformTile(BuildContext context, ShortsPlatform p) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(p.appName, style: AppTypography.body(weight: FontWeight.w600)),
          ),
          Switch(
            value: p.enabled,
            onChanged: (_) => _togglePlatform(context, p),
          ),
        ],
      ),
    );
  }
}
