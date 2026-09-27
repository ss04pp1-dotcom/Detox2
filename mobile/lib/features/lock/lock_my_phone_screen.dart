import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';
import 'lock_schedules_section.dart';

/// LockMyPhoneScreen (v2.5 r9) — the hardest mode (SS LockMyPhone port):
/// Device-Admin lockNow loop, only exits at the end time or via the paid
/// bailout (500 coins).
class LockMyPhoneScreen extends StatefulWidget {
  const LockMyPhoneScreen({super.key});

  @override
  State<LockMyPhoneScreen> createState() => _LockMyPhoneScreenState();
}

class _LockMyPhoneScreenState extends State<LockMyPhoneScreen> {
  int _minutes = 60;
  bool _adminActive = false;
  bool _busy = false;
  Map<String, dynamic>? _status;
  Timer? _ticker;

  static const _presets = [15, 30, 60, 120, 240];

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
    final admin = await NativeBridge.instance.isDeviceAdminActive();
    final status = await NativeBridge.instance.getLockMyPhoneStatus();
    if (!mounted) return;
    setState(() {
      _adminActive = admin;
      _status = status;
    });
    _ticker?.cancel();
    if (status != null && status['active'] == true) {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
    }
  }

  Future<void> _refresh() async {
    final status = await NativeBridge.instance.getLockMyPhoneStatus();
    if (!mounted) return;
    setState(() => _status = status);
  }

  Future<void> _requestAdmin() async {
    setState(() => _busy = true);
    await NativeBridge.instance.requestDeviceAdmin();
    if (!mounted) return;
    setState(() => _busy = false);
    await Future<void>.delayed(const Duration(seconds: 2));
    await _load();
  }

  Future<void> _start() async {
    if (_busy) return;
    setState(() => _busy = true);
    final result = await NativeBridge.instance.startLockMyPhone(
      minutes: _minutes,
      reason: 'user_initiated',
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (result != null) {
      await _load();
    } else {
      _toast('Could not start — is Device Admin enabled?');
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final active = _status?['active'] as bool? ?? false;
    // v2.5.5 audit fix: defensive num casts — `as int?` throws on a stray
    // double/Long from the platform channel.
    final remaining = ((_status?['remainingSeconds'] as num?) ?? 0).toInt();
    final attempts = ((_status?['attempts'] as num?) ?? 0).toInt();

    return Scaffold(
      appBar: AppBar(title: const Text('Lock My Phone')),
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
                      const MLDSectionHeader(title: 'LOCKED'),
                      const SizedBox(height: AppSpacing.md),
                      Text(
                        _fmt(remaining),
                        style: const TextStyle(
                            fontSize: 52,
                            fontWeight: FontWeight.w700,
                            fontFeatures: [FontFeature.tabularFigures()]),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        'The phone re-locks itself every 1.5 seconds.\n'
                        'Power button, recents, home — all covered.',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            color: AppColors.textSecondary, fontSize: 13),
                      ),
                      const SizedBox(height: AppSpacing.md),
                      Text('$attempts unlock attempts logged',
                          style: const TextStyle(fontSize: 12)),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.xxl),
                const Text(
                  'This session ends only at the timer — or via the coin '
                  'bailout (Coins screen → Bailout). There is no other exit '
                  'by design.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
                ),
              ] else ...[
                MLDCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            _adminActive
                                ? Icons.verified_user
                                : Icons.warning_amber_rounded,
                            color: _adminActive
                                ? AppColors.success
                                : AppColors.warning,
                          ),
                          const SizedBox(width: AppSpacing.md),
                          Expanded(
                            child: Text(
                              _adminActive
                                  ? 'Device Admin active — full lockdown ready'
                                  : 'Device Admin required',
                              style: const TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 13),
                            ),
                          ),
                        ],
                      ),
                      if (!_adminActive) ...[
                        const SizedBox(height: AppSpacing.md),
                        const Text(
                          'Lock My Phone uses the Device Admin force-lock '
                          'permission to re-lock the screen continuously. '
                          'It can be revoked any time from this app.',
                          style: TextStyle(
                              color: AppColors.textSecondary, fontSize: 12),
                        ),
                        const SizedBox(height: AppSpacing.md),
                        MLDButton(
                          label: 'Grant Device Admin',
                          loading: _busy,
                          onPressed: _requestAdmin,
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.xxl),
                const MLDSectionHeader(title: 'Duration'),
                const SizedBox(height: AppSpacing.md),
                Wrap(
                  spacing: AppSpacing.md,
                  runSpacing: AppSpacing.md,
                  children: _presets
                      .map((m) => ChoiceChip(
                            label: Text(_minutesLabel(m)),
                            selected: _minutes == m,
                            onSelected: _adminActive && !_busy
                                ? (_) => setState(() => _minutes = m)
                                : null,
                          ))
                      .toList(),
                ),
                const SizedBox(height: AppSpacing.xl),
                MLDButton(
                  label: 'Lock it down',
                  loading: _busy,
                  onPressed: _adminActive ? _start : null,
                ),
                const SizedBox(height: AppSpacing.xl),
                const Text(
                  'During the session the device re-locks every 1.5 s while '
                  'the screen is on. Incoming and ongoing phone calls pause '
                  'the lock (any other app re-locks it). Emergency calls stay '
                  'possible from the lock screen if your phone has a secure '
                  'screen lock set.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
                ),
                const SizedBox(height: AppSpacing.xxl),
                LockSchedulesSection(adminActive: _adminActive),
              ],
            ],
          ),
        ),
      ),
    );
  }

  String _minutesLabel(int m) =>
      m < 60 ? '$m min' : '${m ~/ 60} hr${m >= 120 ? 's' : ''}';

  String _fmt(int seconds) {
    final m = seconds ~/ 60;
    final s = seconds % 60;
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }
}
