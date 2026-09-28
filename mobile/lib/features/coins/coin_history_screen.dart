import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../data/models.dart';
import '../../shared/mld_widgets.dart';

/// Coin history (UI/UX §32): today-first transaction list with green/red
/// semantic indicators, plus the reconstructed balance.
class CoinHistoryScreen extends StatefulWidget {
  const CoinHistoryScreen({super.key});

  @override
  State<CoinHistoryScreen> createState() => _CoinHistoryScreenState();
}

class _CoinHistoryScreenState extends State<CoinHistoryScreen> {
  List<CoinTransaction> _transactions = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final txns = await NativeBridge.instance.getCoinTransactions(limit: 200);
    if (!mounted) return;
    setState(() {
      _transactions = txns;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Coin History')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _transactions.isEmpty
                ? const MLDEmptyState(
                    title: 'NO COIN ACTIVITY YET',
                    message: 'Watch a rewarded ad to earn your first coin.',
                    icon: Icons.monetization_on_outlined,
                  )
                : ListView.separated(
                    padding: AppSpacing.screenH.copyWith(
                      top: AppSpacing.xl,
                      bottom: AppSpacing.xxxl,
                    ),
                    itemCount: _transactions.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, i) => _tile(_transactions[i]),
                  ),
      ),
    );
  }

  Widget _tile(CoinTransaction t) {
    final earned = t.amount > 0;
    final time = DateTime.fromMillisecondsSinceEpoch(t.timestampMs);
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Row(
        children: [
          Icon(
            earned ? Icons.add_circle : Icons.remove_circle,
            color: earned ? AppColors.success : AppColors.danger,
            size: 20,
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(t.typeLabel, style: AppTypography.body(weight: FontWeight.w600)),
                Text(DateFormat('MMM d, HH:mm').format(time), style: AppTypography.caption()),
              ],
            ),
          ),
          Text(
            '${earned ? '+' : ''}${t.amount}',
            style: AppTypography.body(
              color: earned ? AppColors.success : AppColors.danger,
              weight: FontWeight.w800,
            ),
          ),
        ],
      ),
    );
  }
}
