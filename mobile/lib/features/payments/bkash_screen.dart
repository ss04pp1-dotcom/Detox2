import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../shared/mld_widgets.dart';

/// bKash manual payment screen (v2.2 Phase D).
///
/// Flow: pick a bKash plan -> the server creates a PENDING payment with a
/// unique MLD-XXXX reference -> the user sends the exact amount from their
/// bKash app (reference in the memo) -> they type the TrxID here -> the
/// payment moves to IN_REVIEW -> an admin verifies it against the merchant
/// statement and the PRO days are granted server-side.
///
/// The client can never choose the amount (server reads the plan row) or
/// the reference (server-generated, UNIQUE).
class BkashScreen extends StatefulWidget {
  const BkashScreen({super.key});

  @override
  State<BkashScreen> createState() => _BkashScreenState();
}

enum _Stage { pickPlan, transfer, submitted, history }

class _BkashScreenState extends State<BkashScreen> {
  _Stage _stage = _Stage.pickPlan;
  bool _loading = true;
  bool _busy = false;

  bool _gatewayEnabled = false;
  String _number = '';
  String _instructions = '';
  List<Plan> _plans = const [];

  BkashPaymentRecord? _payment;
  final TextEditingController _trxController = TextEditingController();
  final TextEditingController _senderController = TextEditingController();
  List<BkashPaymentRecord> _history = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _trxController.dispose();
    _senderController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
    });

    final info = await ApiClient.instance.fetchBkashInstructions();
    final history = await ApiClient.instance.fetchBkashStatus();

    if (!mounted) return;
    setState(() {
      _gatewayEnabled = info?['enabled'] as bool? ?? false;
      _number = info?['number'] as String? ?? '';
      _instructions = info?['instructions'] as String? ?? '';
      final plans = info?['plans'] as List<dynamic>? ?? const [];
      _plans = plans.map((e) => Plan.fromJson(Map<dynamic, dynamic>.from(e as Map))).toList();
      _history = history
          .map((e) => BkashPaymentRecord.fromJson(Map<dynamic, dynamic>.from(e)))
          .toList();
      if (_gatewayEnabled && _payment == null && _stage != _Stage.history) {
        _stage = _plans.isNotEmpty ? _Stage.pickPlan : _Stage.history;
      }
      _loading = false;
    });
  }

  Future<void> _startPayment(Plan plan) async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.bkashInit(plan.id);
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null && res['payment'] is Map) {
      setState(() {
        _payment =
            BkashPaymentRecord.fromJson(Map<dynamic, dynamic>.from(res['payment'] as Map));
        _number = res['number'] as String? ?? _number;
        _instructions = res['instructions'] as String? ?? _instructions;
        _stage = _Stage.transfer;
      });
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text(
                'Could not start the payment. You may have too many open ones — check below.')),
      );
      await _load();
    }
  }

  Future<void> _submitTrx() async {
    final payment = _payment;
    if (payment == null || _busy) return;
    final trx = _trxController.text.trim();
    if (trx.length < 6 || !RegExp(r'^[A-Za-z0-9-]{6,24}$').hasMatch(trx)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content:
                Text('TrxID looks wrong — it is 6-24 letters/digits from the bKash receipt.')),
      );
      return;
    }
    setState(() => _busy = true);
    final res = await ApiClient.instance.bkashSubmit(
        payment.id, trx, _senderController.text.trim());
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null && res['payment'] is Map) {
      setState(() {
        _payment =
            BkashPaymentRecord.fromJson(Map<dynamic, dynamic>.from(res['payment'] as Map));
        _stage = _Stage.submitted;
      });
      await _refreshHistory();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Submission failed — try again shortly.')),
      );
    }
  }

  Future<void> _cancelPayment() async {
    final payment = _payment;
    if (payment == null || _busy) return;
    setState(() => _busy = true);
    await ApiClient.instance.bkashCancel(payment.id);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _payment = null;
      _trxController.clear();
      _stage = _Stage.pickPlan;
    });
    await _refreshHistory();
  }

  Future<void> _refreshHistory() async {
    final history = await ApiClient.instance.fetchBkashStatus();
    if (!mounted) return;
    setState(() {
      _history = history
          .map((e) => BkashPaymentRecord.fromJson(Map<dynamic, dynamic>.from(e)))
          .toList();
    });
  }

  void _copy(String value, String label) {
    Clipboard.setData(ClipboardData(text: value));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('$label copied')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('bKash Payment')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _load,
                child: SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                  child: _body(),
                ),
              ),
      ),
    );
  }

  Widget _body() {
    if (!_gatewayEnabled) {
      return const MLDEmptyState(
        icon: Icons.credit_card_off,
        title: 'bKash is paused',
        message:
            'The bKash gateway is currently disabled by the operator. Google Play plans are still available.',
      );
    }
    switch (_stage) {
      case _Stage.pickPlan:
        return _pickPlan();
      case _Stage.transfer:
        return _transfer();
      case _Stage.submitted:
        return _submitted();
      case _Stage.history:
        return _historyList();
    }
  }

  Widget _pickPlan() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const MLDSectionHeader(title: 'Choose a plan'),
        const SizedBox(height: AppSpacing.md),
        ..._plans.map((plan) => Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.md),
              child: MLDCard(
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(plan.displayName,
                              style:
                                  const TextStyle(fontWeight: FontWeight.w700)),
                          const SizedBox(height: AppSpacing.xs),
                          Text('${plan.durationLabel} of PRO',
                              style: const TextStyle(
                                  color: AppColors.textSecondary, fontSize: 12)),
                        ],
                      ),
                    ),
                    Text(plan.priceLabel,
                        style: const TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w800)),
                    const SizedBox(width: AppSpacing.md),
                    MLDButton(
                      label: 'Start',
                      expanded: false,
                      loading: _busy,
                      onPressed: () => _startPayment(plan),
                    ),
                  ],
                ),
              ),
            )),
        const SizedBox(height: AppSpacing.lg),
        _historyList(),
      ],
    );
  }

  Widget _transfer() {
    final payment = _payment;
    if (payment == null) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        MLDWarningBanner(
          message:
              'Step 1 — Send ${_fmtBdt(payment.amountMinor)}: open your bKash app and send the EXACT amount to the number below. Paste the reference in the transfer memo so the review can match it.',
        ),
        const SizedBox(height: AppSpacing.lg),
        MLDCard(
          child: Column(
            children: [
              _copyRow('bKash number', _number),
              const Divider(color: AppColors.edge, height: AppSpacing.xxl),
              _copyRow('Reference (memo)', payment.reference),
              const Divider(color: AppColors.edge, height: AppSpacing.xxl),
              _copyRow('Amount', _fmtBdt(payment.amountMinor)),
            ],
          ),
        ),
        if (_instructions.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.lg),
          MLDCard(
            child: Text(
              _instructions,
              style:
                  const TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
          ),
        ],
        const SizedBox(height: AppSpacing.xxl),
        const MLDSectionHeader(title: 'Step 2 — Submit the TrxID'),
        const SizedBox(height: AppSpacing.md),
        MLDCard(
          child: Column(
            children: [
              TextField(
                controller: _trxController,
                decoration: const InputDecoration(
                  labelText: 'bKash TrxID',
                  hintText: 'e.g. 9AB7K2L3M4',
                ),
                textCapitalization: TextCapitalization.characters,
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                controller: _senderController,
                decoration: const InputDecoration(
                  labelText: 'Your bKash number (optional)',
                  hintText: '01XXXXXXXXX',
                ),
                keyboardType: TextInputType.phone,
              ),
              const SizedBox(height: AppSpacing.lg),
              MLDButton(
                label: 'Submit for review',
                icon: Icons.send_outlined,
                loading: _busy,
                onPressed: _submitTrx,
              ),
              const SizedBox(height: AppSpacing.sm),
              TextButton(
                onPressed: _busy ? null : _cancelPayment,
                child: const Text('Cancel this payment'),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _submitted() {
    final payment = _payment;
    if (payment == null) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: AppSpacing.xxl),
        const Icon(Icons.hourglass_top, size: 56, color: AppColors.warning),
        const SizedBox(height: AppSpacing.lg),
        Text(
          'Under review',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          'TrxID ${payment.trxId} is being checked against the merchant statement. PRO activates automatically once verified — usually within a day.',
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.textSecondary, fontSize: 13),
        ),
        const SizedBox(height: AppSpacing.xxl),
        MLDButton(
          label: 'Start another payment',
          variant: MLDButtonVariant.secondary,
          onPressed: () {
            setState(() {
              _payment = null;
              _trxController.clear();
              _stage = _Stage.pickPlan;
            });
          },
        ),
        const SizedBox(height: AppSpacing.lg),
        _historyList(),
      ],
    );
  }

  Widget _historyList() {
    if (_history.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const MLDSectionHeader(title: 'Your payments'),
        const SizedBox(height: AppSpacing.md),
        ..._history.take(10).map((p) => Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: MLDCard(
                padding: const EdgeInsets.all(AppSpacing.lg),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('${p.planName ?? 'PRO plan'} · ${p.reference}',
                              style: const TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 13)),
                          if (p.rejectReason != null)
                            Text(p.rejectReason!,
                                style: const TextStyle(
                                    color: AppColors.danger, fontSize: 12)),
                        ],
                      ),
                    ),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(_fmtBdt(p.amountMinor),
                            style: const TextStyle(
                                fontWeight: FontWeight.w700, fontSize: 13)),
                        Text(p.statusLabel,
                            style: TextStyle(
                              color: p.status == 'VERIFIED'
                                  ? AppColors.success
                                  : p.status == 'REJECTED'
                                      ? AppColors.danger
                                      : AppColors.textSecondary,
                              fontSize: 11,
                            )),
                      ],
                    ),
                  ],
                ),
              ),
            )),
      ],
    );
  }

  Widget _copyRow(String label, String value) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label,
                  style: const TextStyle(
                      color: AppColors.textSecondary, fontSize: 11)),
              const SizedBox(height: 2),
              Text(value,
                  style: const TextStyle(
                      fontSize: 17, fontWeight: FontWeight.w700)),
            ],
          ),
        ),
        IconButton(
          onPressed: () => _copy(value, label),
          icon: const Icon(Icons.copy, size: 18),
          tooltip: 'Copy $label',
        ),
      ],
    );
  }

  /// v2.5.5 audit fix: `amountMinor / 100` is a double — 29900 minor used to
  /// render as the broken-looking "299.0 BDT". Whole amounts print without
  /// decimals; fractional ones keep two.
  String _fmtBdt(int amountMinor) {
    final major = amountMinor / 100;
    return '${major % 1 == 0 ? major.toInt() : major.toStringAsFixed(2)} BDT';
  }
}
