import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Paywall (v2.2 Phase D) — PRO is a SUPPORTER tier.
///
/// ETHICAL STANCE (deliberate divergence from the competitor, which
/// paywalls core blocking): every enforcement feature stays free forever.
/// PRO buys convenience and depth — extended insight history (90 days vs
/// 7), the full widget family, and a supporter badge. The trial is one
/// per device, verified server-side.
class PaywallScreen extends StatefulWidget {
  const PaywallScreen({super.key});

  @override
  State<PaywallScreen> createState() => _PaywallScreenState();
}

class _PaywallScreenState extends State<PaywallScreen> {
  List<Plan> _plans = const [];
  List<PlayProductInfo> _playProducts = const [];
  TrialInfo _trial = const TrialInfo(
      eligible: false, active: false, claimedAt: null, expiresAt: null);
  SubscriptionInfo? _subscription;
  bool _paymentsEnabled = true;
  bool _loading = true;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    final plansRes = await ApiClient.instance.fetchPlans();
    final trialRes = await ApiClient.instance.fetchTrial();
    final subRes = await ApiClient.instance.fetchSubscription();
    final playProducts = await NativeBridge.instance.getBillingProducts();

    if (!mounted) return;
    setState(() {
      _plans = plansRes.map(Plan.fromJson).toList();
      _playProducts = playProducts;
      if (trialRes != null && trialRes['trial'] is Map) {
        _trial = TrialInfo.fromJson(
            Map<dynamic, dynamic>.from(trialRes['trial'] as Map));
        _paymentsEnabled = trialRes['paymentsEnabled'] as bool? ?? true;
      }
      if (subRes != null && subRes['subscription'] is Map) {
        _subscription = SubscriptionInfo.fromJson(
            Map<dynamic, dynamic>.from(subRes['subscription'] as Map));
      }
      _loading = false;
      if (_plans.isEmpty) {
        _error = 'Could not reach the plan catalog. Check your connection.';
      }
    });
  }

  Future<void> _claimTrial() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.claimTrial();
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null && res['subscription'] is Map) {
      setState(() {
        _subscription = SubscriptionInfo.fromJson(
            Map<dynamic, dynamic>.from(res['subscription'] as Map));
        _trial = const TrialInfo(
            eligible: false, active: true, claimedAt: null, expiresAt: null);
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Free trial activated — enjoy PRO.')),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text(
                'Trial unavailable — it may already be claimed on this device.')),
      );
    }
  }

  Future<void> _buyPlay(Plan plan) async {
    if (_busy) return;
    setState(() => _busy = true);
    final launched = await NativeBridge.instance.launchPurchase(
      plan.productId,
      // v2.5.7 (W-7): bind the purchase to the signed-in account.
      accountId: ApiClient.instance.userId,
    );
    if (!mounted) return;
    if (!launched) {
      setState(() => _busy = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content:
                Text('Google Play billing is unavailable here. Try bKash below.')),
      );
      return;
    }
    // The purchase sheet is up; verification happens through
    // purchaseEvents -> Worker verify when it completes.
    setState(() => _busy = false);
  }

  Future<void> _restore() async {
    if (_busy) return;
    setState(() => _busy = true);
    await NativeBridge.instance.restorePurchases();
    await Future<void>.delayed(const Duration(milliseconds: 800));
    final subRes = await ApiClient.instance.fetchSubscription();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (subRes != null && subRes['subscription'] is Map) {
        _subscription = SubscriptionInfo.fromJson(
            Map<dynamic, dynamic>.from(subRes['subscription'] as Map));
      }
    });
  }

  String? _playPriceFor(String productId) {
    for (final p in _playProducts) {
      if (p.productId == productId && p.formattedPrice.isNotEmpty) {
        return p.formattedPrice;
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final isPro = _subscription?.isActive ?? false;

    return Scaffold(
      appBar: AppBar(
        title: const Text('MAXLEVEL PRO'),
        actions: [
          TextButton(
            onPressed: _busy ? null : _restore,
            child: const Text('Restore'),
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _load,
                child: SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _header(isPro),
                      const SizedBox(height: AppSpacing.xxl),
                      _perksCard(),
                      const SizedBox(height: AppSpacing.xxl),
                      if (isPro) _activeCard(),
                      if (!isPro && _trial.eligible && _paymentsEnabled) ...[
                        _trialCard(),
                        const SizedBox(height: AppSpacing.xxl),
                      ],
                      if (_error != null)
                        MLDCard(
                          child: Text(
                            _error!,
                            style: TextStyle(color: AppColors.textSecondary),
                          ),
                        )
                      else ...[
                        MLDSectionHeader(
                            title: _playProducts.isEmpty
                                ? 'Plans (bKash available)'
                                : 'Plans'),
                        const SizedBox(height: AppSpacing.md),
                        ..._plans.map(_planTile),
                      ],
                      const SizedBox(height: AppSpacing.xxl),
                      _bkashEntry(),
                      const SizedBox(height: AppSpacing.xl),
                      Text(
                        'Core blocking is free forever. PRO is how you support development — it never gates your safety features.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppColors.textDisabled,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
      ),
    );
  }

  Widget _header(bool isPro) {
    return Column(
      children: [
        const SizedBox(height: AppSpacing.lg),
        Container(
          width: 72,
          height: 72,
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [AppColors.premium, AppColors.primary],
            ),
            borderRadius: BorderRadius.circular(20),
          ),
          child: const Icon(Icons.workspace_premium_outlined,
              size: 36, color: Colors.white),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text(
          isPro ? 'You are PRO' : 'Become a supporter',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          isPro
              ? 'PRO active — ${_subscription?.daysLeft ?? 0} day(s) left. Thank you.'
              : 'Unlock depth, keep every safety feature free.',
          style: TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );
  }

  Widget _perksCard() {
    const perks = [
      ('Insight history: 90 days', 'Free tier keeps 7 days'),
      ('Full widget family', 'Streak, session, usage, coins'),
      ('Supporter badge', 'On your progress profile'),
      ('Early feature access', 'New experiments reach you first'),
    ];
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('What PRO includes',
              style: Theme.of(context)
                  .textTheme
                  .titleMedium
                  ?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: AppSpacing.md),
          ...perks.map((p) => Padding(
                padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
                child: Row(
                  children: [
                    const Icon(Icons.check_circle_outline,
                        size: 18, color: AppColors.success),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Text.rich(TextSpan(children: [
                        TextSpan(text: '${p.$1}  '),
                        TextSpan(
                          text: p.$2,
                          style: TextStyle(color: AppColors.textDisabled),
                        ),
                      ])),
                    ),
                  ],
                ),
              )),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'Always free: sessions, cage, monk mode, prime commit, reels protection, emergency codes.',
            style: TextStyle(color: AppColors.textDisabled, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _activeCard() {
    return MLDCard(
      child: Row(
        children: [
          const Icon(Icons.verified_outlined, color: AppColors.success),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('PRO active',
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                Text(
                  _subscription?.plan == 'trial'
                      ? 'Free trial — ends ${_subscription?.expiry?.toLocal().toString().split(' ').first ?? 'soon'}'
                      : 'Renews via ${_subscription?.productId == '' ? 'your provider' : _subscription?.productId}',
                  style: const TextStyle(
                      color: AppColors.textSecondary, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _trialCard() {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.card_giftcard, color: AppColors.premium),
              SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Try PRO free',
                    style: TextStyle(fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          const Text(
            'One free trial per device, no card needed. Starts instantly.',
            style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
          ),
          const SizedBox(height: AppSpacing.lg),
          MLDButton(
            label: 'Start free trial',
            icon: Icons.rocket_launch_outlined,
            loading: _busy,
            onPressed: _claimTrial,
          ),
        ],
      ),
    );
  }

  Widget _planTile(Plan plan) {
    if (plan.source != 'play') return const SizedBox.shrink();
    final playPrice = _playPriceFor(plan.productId) ?? plan.priceLabel;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: MLDCard(
        borderColor: plan.isPopular ? AppColors.premium : AppColors.edge,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(plan.displayName,
                      style: const TextStyle(fontWeight: FontWeight.w700)),
                ),
                if (plan.isPopular)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: AppColors.premium.withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Text('BEST VALUE',
                        style: TextStyle(
                            color: AppColors.premium,
                            fontSize: 10,
                            fontWeight: FontWeight.w800)),
                  ),
              ],
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              plan.description ?? '${plan.durationLabel} of PRO',
              style: const TextStyle(
                  color: AppColors.textSecondary, fontSize: 12),
            ),
            const SizedBox(height: AppSpacing.md),
            Row(
              children: [
                Text(playPrice,
                    style: const TextStyle(
                        fontSize: 20, fontWeight: FontWeight.w800)),
                const SizedBox(width: AppSpacing.sm),
                Text('/ ${plan.durationLabel}',
                    style: const TextStyle(
                        color: AppColors.textDisabled, fontSize: 12)),
                const Spacer(),
                MLDButton(
                  label: 'Continue',
                  expanded: false,
                  loading: _busy,
                  onPressed: () => _buyPlay(plan),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _bkashEntry() {
    return MLDCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.account_balance_wallet_outlined,
                  color: AppColors.success),
              SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Pay with bKash',
                    style: TextStyle(fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          const Text(
            'No card? Send the amount from your bKash app, submit the TrxID, and PRO is activated after a quick manual review.',
            style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
          ),
          const SizedBox(height: AppSpacing.lg),
          MLDButton(
            label: 'Open bKash payment',
            variant: MLDButtonVariant.secondary,
            icon: Icons.arrow_forward,
            onPressed: () => Navigator.of(context).pushNamed('/payments/bkash'),
          ),
        ],
      ),
    );
  }
}
