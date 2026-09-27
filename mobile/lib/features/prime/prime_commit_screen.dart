import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// PrimeCommitScreen (v2.5 r9) — SS Prime Mode port: an all-or-nothing
/// commitment window. No temp unlocks, no bailouts, no categories. Exit
/// only via a TOTP emergency code (RFC-6238) with relapse consequences.
class PrimeCommitScreen extends StatefulWidget {
  const PrimeCommitScreen({super.key});

  @override
  State<PrimeCommitScreen> createState() => _PrimeCommitScreenState();
}

class _PrimeCommitScreenState extends State<PrimeCommitScreen> {
  int _hours = 2;
  bool _busy = false;
  Map<String, dynamic>? _status;
  Timer? _ticker;

  static const _presets = [1, 2, 4, 8, 12];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final status = await NativeBridge.instance.getPrimeCommitStatus();
    if (!mounted) return;
    setState(() => _status = status);
    _ticker?.cancel();
    if (status?['active'] == true) {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
    }
  }

  Future<void> _refresh() async {
    final status = await NativeBridge.instance.getPrimeCommitStatus();
    if (!mounted) return;
    setState(() => _status = status);
  }

  Future<void> _activate() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Commit to Prime?'),
        content: const Text(
          'During this window there are NO temporary unlocks, NO bailouts '
          'and NO category escapes. The only exit is your 6-digit emergency '
          'code — using it resets your streak.\n\nPrivate DNS protection '
          'locks too: tampering with DNS settings will be blocked.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('I commit')),
        ],
      ),
    );
    // v2.5.5 audit fix: mounted guard between the dialog await and setState.
    if (!mounted || confirmed != true) return;
    setState(() => _busy = true);
    final result =
        await NativeBridge.instance.activatePrimeCommit(hours: _hours);
    if (!mounted) return;
    setState(() => _busy = false);
    if (result != null) {
      _load();
    } else {
      _toast('Activation failed — end any active session first.');
    }
  }

  Future<void> _giveUp() async {
    final code = await _askCode();
    // v2.5.5 audit fix: mounted guard between the dialog await and setState.
    if (!mounted || code == null) return;
    setState(() => _busy = true);
    final result = await NativeBridge.instance.giveUpPrimeCommit(code: code);
    if (!mounted) return;
    setState(() => _busy = false);
    if (result == null) {
      _toast('Invalid or replayed code. Try the next one from your sheet.');
    } else {
      _toast('Commitment ended. Relapse logged — streak reset to Day 1.');
      _load();
    }
  }

  Future<String?> _askCode() async {
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
                'Each code works once for 5 minutes.'),
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
    // v2.5.5 audit fix: dialog TextEditingControllers were never disposed.
    controller.dispose();
    return result;
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final active = _status?['active'] as bool? ?? false;
    // v2.5.5 audit fix: defensive num cast — `as int?` throws on a stray
    // double/Long from the platform channel.
    final remaining = ((_status?['remainingSeconds'] as num?) ?? 0).toInt();

    return Scaffold(
      appBar: AppBar(title: const Text('Prime Commit')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (active) ...[
                MLDCard(
                  child: Column(
                    children: [
                      const MLDSectionHeader(title: 'PRIME COMMITMENT LIVE'),
                      const SizedBox(height: AppSpacing.md),
                      Text(
                        _fmt(remaining),
                        style: const TextStyle(
                          fontSize: 52,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      const Text(
                        'No unlocks. No bailouts. No categories.\n'
                        'DNS protection is locked (tamper-guarded).',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: AppColors.textSecondary, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.xxl),
                MLDButton(
                  label: 'Use emergency code (ends commitment)',
                  variant: MLDButtonVariant.danger,
                  loading: _busy,
                  onPressed: _giveUp,
                ),
              ] else ...[
                MLDCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: const [
                      Text('All-or-nothing window',
                          style: TextStyle(
                              fontWeight: FontWeight.w700, fontSize: 15)),
                      SizedBox(height: AppSpacing.sm),
                      Text(
                        'Prime Commit is the contract you cannot lawyer out '
                        'of: for the whole window, every social app is '
                        'blocked, temporary unlocks and coin bailouts are '
                        'refused, and DNS tampering gets bounced back. The '
                        'only exit is your rotating TOTP emergency code — '
                        'and using it costs your streak.',
                        style: TextStyle(
                            color: AppColors.textSecondary, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.xxl),
                const MLDSectionHeader(title: 'Commitment length'),
                const SizedBox(height: AppSpacing.md),
                Wrap(
                  spacing: AppSpacing.md,
                  runSpacing: AppSpacing.md,
                  children: _presets
                      .map((m) => ChoiceChip(
                            label: Text('$m hr${m >= 2 ? 's' : ''}'),
                            selected: _hours == m,
                            onSelected: !_busy
                                ? (_) => setState(() => _hours = m)
                                : null,
                          ))
                      .toList(),
                ),
                const SizedBox(height: AppSpacing.xl),
                MLDButton(
                  label: 'Commit',
                  loading: _busy,
                  onPressed: _activate,
                ),
                const SizedBox(height: AppSpacing.xl),
                Text(
                  'Tip: enroll your emergency code sheet first (Settings → '
                  'Emergency Codes) — without it there is NO exit until the '
                  'timer ends.',
                  textAlign: TextAlign.center,
                  style:
                      TextStyle(color: AppColors.textSecondary, fontSize: 12),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  String _fmt(int seconds) {
    final h = seconds ~/ 3600;
    final m = (seconds % 3600) ~/ 60;
    final s = seconds % 60;
    return h > 0
        ? '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}'
        : '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }
}
