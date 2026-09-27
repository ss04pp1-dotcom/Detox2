import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// SafetyPauseScreen (v2.5 r9) — SS 5-Second Pause port: a chosen moment
/// of friction before your configured apps. Not a block — a breath.
class SafetyPauseScreen extends StatefulWidget {
  const SafetyPauseScreen({super.key});

  @override
  State<SafetyPauseScreen> createState() => _SafetyPauseScreenState();
}

class _SafetyPauseScreenState extends State<SafetyPauseScreen> {
  bool _loading = true;
  bool _busy = false;
  bool _enabled = false;
  int _seconds = 5;
  final Set<String> _apps = <String>{};
  List<UsageStat> _installed = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final status = await NativeBridge.instance.getSafetyPauseStatus();
    final usage = await NativeBridge.instance.getUsageStats();
    if (!mounted) return;
    setState(() {
      _enabled = status?['enabled'] as bool? ?? false;
      // v2.5.5 audit fix: defensive num cast (see lock/prime/monk screens).
      _seconds = ((status?['seconds'] as num?) ?? 5).toInt();
      final apps = status?['packages'] as List<dynamic>? ?? [];
      _apps
        ..clear()
        ..addAll(apps.map((e) => e.toString()));
      _installed = usage;
      _loading = false;
    });
  }

  Future<void> _toggle(bool value) async {
    setState(() => _busy = true);
    await NativeBridge.instance.setSafetyPauseEnabled(enabled: value);
    if (!mounted) return;
    setState(() {
      _enabled = value;
      _busy = false;
    });
    _load();
  }

  Future<void> _setSeconds(int value) async {
    setState(() => _busy = true);
    await NativeBridge.instance.setSafetyPauseSeconds(seconds: value);
    if (!mounted) return;
    setState(() {
      _seconds = value;
      _busy = false;
    });
  }

  Future<void> _toggleApp(String pkg, bool selected) async {
    setState(() {
      if (selected) {
        _apps.add(pkg);
      } else {
        _apps.remove(pkg);
      }
    });
    // v2.5.5 audit fix: the optimistic local set used to diverge silently
    // from native state when the engine refused the write — verify it and
    // always re-read the authoritative status afterwards.
    final ok = await NativeBridge.instance
        .setSafetyPauseApps(packages: _apps.toList());
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Engine refused the change.')),
      );
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Safety Pause')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                children: [
                  MLDCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SwitchListTile(
                          value: _enabled,
                          onChanged: _busy ? null : _toggle,
                          title: const Text('Enable Safety Pause',
                              style: TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 14)),
                          subtitle: const Text(
                              'A pause screen before chosen apps open — '
                              'with your task list and a breath of friction.',
                              style: TextStyle(fontSize: 12)),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  const MLDSectionHeader(title: 'Pause length'),
                  const SizedBox(height: AppSpacing.md),
                  Wrap(
                    spacing: AppSpacing.md,
                    children: [3, 5, 8, 10]
                        .map((s) => ChoiceChip(
                              label: Text('$s s'),
                              selected: _seconds == s,
                              onSelected: _enabled && !_busy
                                  ? (_) => _setSeconds(s)
                                  : null,
                            ))
                        .toList(),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  MLDSectionHeader(
                      title: 'Apps (${_apps.length} selected)'),
                  const SizedBox(height: AppSpacing.md),
                  ..._installed.map((app) {
                    final checked = _apps.contains(app.packageName);
                    return CheckboxListTile(
                      value: checked,
                      onChanged: _enabled && !_busy
                          ? (v) => _toggleApp(app.packageName, v == true)
                          : null,
                      title: Text(app.appName,
                          style: const TextStyle(fontSize: 14)),
                      subtitle: Text(app.packageName,
                          style: const TextStyle(fontSize: 11),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis),
                      dense: true,
                    );
                  }),
                ],
              ),
      ),
    );
  }
}
