import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/app_state.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Bailout — intentionally high-friction early exit (UI/UX §33, PRD §18).
/// Step 1: show the cost. Step 2: hold to confirm. Both validated natively;
/// coins are spent in one atomic native transaction.
class BailoutScreen extends StatefulWidget {
  const BailoutScreen({super.key});

  @override
  State<BailoutScreen> createState() => _BailoutScreenState();
}

class _BailoutScreenState extends State<BailoutScreen> {
  bool _confirming = false;
  bool _busy = false;
  String? _error;
  Map<dynamic, dynamic>? _validation;

  // v2.5.5 audit fix: captured from the inherited scope during build.
  AppState? _appState;

  @override
  void initState() {
    super.initState();
    _validate();
  }

  @override
  void dispose() {
    // v2.5.5 audit fix: never leave the suppression flag latched — this
    // route is torn down by the shell push below, after which natural
    // completions must navigate to the completion screen again.
    _appState?.suppressCompletionRedirect = false;
    super.dispose();
  }

  Future<void> _validate() async {
    final r = await NativeBridge.instance.validateBailout();
    if (!mounted) return;
    setState(() => _validation = r.data);
  }

  Future<void> _execute() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });

    // v2.5.5 audit fix: latch the suppression flag BEFORE the await — the
    // native state push (EventChannel) and the method-channel reply are
    // separate async events, and the active-session watcher used to race
    // this navigation with its own completion push. The bailout owns the
    // post-session routing: shell, not the celebration.
    final app = _appState ?? AppStateScope.of(context);
    _appState = app;
    app.suppressCompletionRedirect = true;

    final result = await NativeBridge.instance.executeBailout();

    if (!mounted) {
      app.suppressCompletionRedirect = false;
      return;
    }
    if (result.isOk) {
      HapticFeedback.heavyImpact();
      // v2.5.7 (C-3): mirror the bailout coin spend to the server ledger
      // (best-effort; the device ledger is the authority).
      unawaited(ApiClient.instance.reportCoinSpend(
        type: 'BAILOUT_SPEND',
        amount: AppConstants.bailoutCost,
        reference: 'bo_${DateTime.now().millisecondsSinceEpoch}',
      ));
      Navigator.of(context).pushNamedAndRemoveUntil(
        AppConstants.routeShell,
        (route) => false,
      );
      // The flag is cleared in dispose() — this route (and the embedded
      // active-session watcher below it) is torn down by the navigation.
    } else {
      app.suppressCompletionRedirect = false;
      setState(() {
        _busy = false;
        _error = switch (result.error?.code) {
          'INSUFFICIENT_COINS' =>
            'You need ${_cost - _balance} more coins. Bailout is deliberately expensive — that is the point.',
          'SESSION_NOT_ACTIVE' => 'No active session to end.',
          _ => result.error?.message ?? 'Bailout failed.',
        };
      });
    }
  }

  int get _cost => (_validation?['cost'] as num?)?.toInt() ?? AppConstants.bailoutCost;
  int get _balance => (_validation?['balance'] as num?)?.toInt() ?? 0;

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    _appState ??= app;

    return Scaffold(
      appBar: AppBar(title: const Text('End Session Early?')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (!_confirming) ...[
                const Icon(Icons.exit_to_app, size: 56, color: AppColors.danger),
                const SizedBox(height: AppSpacing.xxl),
                Text('THIS WILL COST',
                    textAlign: TextAlign.center,
                    style: AppTypography.label()),
                const SizedBox(height: AppSpacing.md),
                MLDCoinBadge(amount: _cost, large: true),
                const SizedBox(height: AppSpacing.xxl),
                Text(
                  'Your current balance\n$_balance coins',
                  textAlign: TextAlign.center,
                  style: AppTypography.body(color: AppColors.textSecondary),
                ),
                const SizedBox(height: AppSpacing.xxl),
                const MLDWarningBanner(
                  message:
                      'This action permanently ends your current session. A bailout is recorded in your history.',
                  tone: MLDBannerTone.danger,
                ),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.lg),
                  MLDWarningBanner(message: _error!, tone: MLDBannerTone.danger),
                ],
                const SizedBox(height: AppSpacing.xxxl),
                MLDButton(
                  label: 'CONTINUE',
                  variant: MLDButtonVariant.danger,
                  onPressed: (_balance >= _cost) ? () => setState(() => _confirming = true) : null,
                ),
                const SizedBox(height: AppSpacing.md),
                MLDButton(
                  label: 'KEEP SESSION',
                  variant: MLDButtonVariant.secondary,
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ] else ...[
                const SizedBox(height: AppSpacing.massive),
                Text('CONFIRM BAILOUT',
                    textAlign: TextAlign.center,
                    style: AppTypography.heading(color: AppColors.danger)),
                const SizedBox(height: AppSpacing.md),
                Text('$_cost coins will be deducted.',
                    textAlign: TextAlign.center,
                    style: AppTypography.body(color: AppColors.textSecondary)),
                const SizedBox(height: AppSpacing.massive),
                MLDHoldToConfirmButton(
                  label: 'HOLD TO CONFIRM',
                  onConfirmed: _execute,
                ),
                const SizedBox(height: AppSpacing.md),
                Text(
                  'Press and hold for ${AppDurations.holdToConfirm.inSeconds} seconds.',
                  textAlign: TextAlign.center,
                  style: AppTypography.caption(),
                ),
                const SizedBox(height: AppSpacing.xxl),
                if (_busy) const Center(child: CircularProgressIndicator()),
                if (!_busy)
                  MLDButton(
                    label: 'CANCEL',
                    variant: MLDButtonVariant.secondary,
                    onPressed: () => setState(() => _confirming = false),
                  ),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.lg),
                  MLDWarningBanner(message: _error!, tone: MLDBannerTone.danger),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}
