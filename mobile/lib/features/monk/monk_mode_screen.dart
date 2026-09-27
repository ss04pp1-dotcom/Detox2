import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// MonkModeScreen (v2.5 r9) — SS Monk Mode port: allowlist-only lockdown
/// (phone essentials + your chosen apps), audio-mode call detection,
/// continuous lockNow re-assert, no exit until you deactivate from here.
class MonkModeScreen extends StatefulWidget {
  const MonkModeScreen({super.key});

  @override
  State<MonkModeScreen> createState() => _MonkModeScreenState();
}

class _MonkModeScreenState extends State<MonkModeScreen> {
  bool _loading = true;
  bool _busy = false;
  Map<String, dynamic>? _status;
  final Set<String> _selected = <String>{};
  List<UsageStat> _apps = const [];
  final TextEditingController _search = TextEditingController();
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final status = await NativeBridge.instance.getMonkModeStatus();
    final usage = await NativeBridge.instance.getUsageStats();
    if (!mounted) return;
    setState(() {
      _status = status;
      _apps = usage;
      if (_selected.isEmpty) {
        final allowed = status?['allowedPackages'] as List<dynamic>? ?? [];
        _selected.addAll(allowed.map((e) => e.toString()));
      }
      _loading = false;
    });
    _ticker?.cancel();
    if (status?['active'] == true) {
      _ticker = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
    }
  }

  Future<void> _refresh() async {
    final status = await NativeBridge.instance.getMonkModeStatus();
    if (!mounted) return;
    setState(() => _status = status);
  }

  Future<void> _activate() async {
    if (_busy) return;
    setState(() => _busy = true);
    final result = await NativeBridge.instance
        .activateMonkMode(allowedPackages: _selected.toList());
    if (!mounted) return;
    setState(() => _busy = false);
    if (result != null) {
      _toast('Monk Mode active. Phone essentials + ${_selected.length} apps.');
      _load();
    } else {
      _toast('Activation failed — check permissions.');
    }
  }

  /// v2.5.5 audit fix: the promised deactivation path now exists. Native
  /// "deactivateMonkMode" is re-validated (INVALID_REQUEST when not active,
  /// SYSTEM_RESTRICTION when the engine refuses) — errors surface in a
  /// dialog, never silently.
  Future<void> _deactivate() async {
    if (_busy) return;
    setState(() => _busy = true);
    final r = await NativeBridge.instance.deactivateMonkMode();
    if (!mounted) return;
    setState(() => _busy = false);
    if (r.isOk) {
      _toast('Monk Mode deactivated. Enforcement released.');
      _load();
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Could not deactivate'),
        content: Text(r.error?.message ?? 'The engine refused the request.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context), child: const Text('OK')),
        ],
      ),
    );
    _load();
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final active = _status?['active'] as bool? ?? false;

    return Scaffold(
      appBar: AppBar(title: const Text('Monk Mode')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : active
                ? _activeView()
                : _setupView(),
      ),
    );
  }

  Widget _activeView() {
    // v2.5.5 audit fix: defensive num casts (a stray double/Long from the
    // platform channel would previously throw on `as int?`).
    final since = ((_status?['sinceWallMs'] as num?) ?? 0).toInt();
    final calls = ((_status?['callDetections'] as num?) ?? 0).toInt();
    return SingleChildScrollView(
      padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          MLDCard(
            child: Column(
              children: [
                const Icon(Icons.self_improvement,
                    size: 48, color: AppColors.primary),
                const SizedBox(height: AppSpacing.md),
                const Text('MONK MODE ACTIVE',
                    style: TextStyle(
                        fontWeight: FontWeight.w700, letterSpacing: 1.2)),
                const SizedBox(height: AppSpacing.md),
                Text(
                  'Only your allowlisted apps are reachable.\n'
                  'Calls are detected via audio-mode and pause the lock.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      color: AppColors.textSecondary, fontSize: 13),
                ),
                const SizedBox(height: AppSpacing.md),
                Text(
                  'Since ${_sinceLabel(since)} · $calls call pauses',
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xl),
          MLDSectionHeader(
              title: 'Allowlist (${_selected.length} apps)'),
          const SizedBox(height: AppSpacing.md),
          ..._selected.map((pkg) => Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: Text('· ${_appName(pkg)}',
                    style: const TextStyle(fontSize: 13)),
              )),
          const SizedBox(height: AppSpacing.xxl),
          const Text(
            'Deactivation is only possible from this screen while the device '
            'is unlocked — enforcement holds through reboots.',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
          ),
          const SizedBox(height: AppSpacing.xl),
          // v2.5.5 audit fix: the deactivation control itself — hold to
          // confirm, no DP refunded (this is an early exit from a
          // discipline window, same friction model as the bailout).
          MLDHoldToConfirmButton(
            label: 'DEACTIVATE NOW (no DP)',
            onConfirmed: _deactivate,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            'Hold for ${AppDurations.holdToConfirm.inSeconds} seconds. No '
            'discipline points are refunded for an early exit.',
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.textSecondary, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _setupView() {
    final query = _search.text.toLowerCase();
    final apps = query.isEmpty
        ? _apps
        : _apps
            .where((a) =>
                a.appName.toLowerCase().contains(query) ||
                a.packageName.toLowerCase().contains(query))
            .toList();

    return Column(
      children: [
        Padding(
          padding: AppSpacing.screenH,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              MLDCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Text('Allowlist-only lockdown',
                        style: TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 15)),
                    SizedBox(height: AppSpacing.sm),
                    Text(
                      'Everything is blocked except phone essentials (dialer, '
                      'SMS, settings basics) and the apps you pick below. '
                      'Harder than Detox Mode — there is no allow-category '
                      'escape.',
                      style: TextStyle(
                          color: AppColors.textSecondary, fontSize: 13),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              TextField(
                controller: _search,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  hintText: 'Search installed apps…',
                  prefixIcon: Icon(Icons.search),
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              Text('${_selected.length} selected',
                  style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.only(bottom: AppSpacing.xxxl),
            itemCount: apps.length,
            itemBuilder: (context, i) {
              final app = apps[i];
              final checked = _selected.contains(app.packageName);
              return CheckboxListTile(
                value: checked,
                onChanged: (v) => setState(() {
                  if (v == true) {
                    _selected.add(app.packageName);
                  } else {
                    _selected.remove(app.packageName);
                  }
                }),
                title: Text(app.appName,
                    style: const TextStyle(fontSize: 14)),
                subtitle: Text(app.packageName,
                    style: const TextStyle(fontSize: 11),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis),
                dense: true,
              );
            },
          ),
        ),
        Padding(
          padding: AppSpacing.screenH,
          child: MLDButton(
            label: 'Enter Monk Mode',
            loading: _busy,
            onPressed: _selected.isEmpty ? null : _activate,
          ),
        ),
      ],
    );
  }

  String _appName(String pkg) {
    for (final a in _apps) {
      if (a.packageName == pkg) return a.appName;
    }
    return pkg;
  }

  String _sinceLabel(int? since) {
    if (since == null) return 'earlier';
    final mins = DateTime.now().millisecondsSinceEpoch - since;
    return '${(mins / 60000).round()} min ago';
  }
}
