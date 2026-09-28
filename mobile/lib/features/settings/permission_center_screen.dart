import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Permission Center (UI/UX §45): live enforcement capability status with
/// one-tap Fix buttons. Reflects the native PermissionMonitor.
class PermissionCenterScreen extends StatefulWidget {
  const PermissionCenterScreen({super.key});

  @override
  State<PermissionCenterScreen> createState() => _PermissionCenterScreenState();
}

class _PermissionCenterScreenState extends State<PermissionCenterScreen> {
  bool _explanationOpen = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    AppStateScope.of(context).refresh();
  }

  Future<void> _fix(String key) async {
    if (key == 'battery') {
      await NativeBridge.instance.requestIgnoreBatteryOptimizations();
    } else {
      await NativeBridge.instance.openPermissionSettings(key);
    }
  }

  Future<void> _openOemSettings() async {
    final opened = await NativeBridge.instance.openOemBackgroundSettings();
    if (!mounted) return;
    final msg = opened == null || opened == 'unavailable'
        ? 'Could not open the settings screen on this device.'
        : (opened == 'App info'
            ? 'Opened App info — look for Battery / Autostart there.'
            : 'Opened: $opened');
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final perms = AppStateScope.of(context).state.permissions;

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Permission Center')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(
            top: AppSpacing.xl,
            bottom: AppSpacing.xxxl,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              MLDPermissionTile(
                name: 'Accessibility',
                description: 'Detects restricted apps and enforces your rules.',
                granted: perms.accessibility,
                onSetup: () => _fix('accessibility'),
              ),
              const SizedBox(height: 8),
              MLDPermissionTile(
                name: 'Usage Access',
                description: 'Accurate app usage statistics for insights.',
                granted: perms.usageAccess,
                onSetup: () => _fix('usageAccess'),
              ),
              const SizedBox(height: 8),
              MLDPermissionTile(
                name: 'Overlay',
                description: 'Instant blocking screen over restricted apps.',
                granted: perms.overlay,
                onSetup: () => _fix('overlay'),
              ),
              const SizedBox(height: 8),
              MLDPermissionTile(
                name: 'Notifications',
                description: 'Session status and recovery notifications.',
                granted: perms.notifications,
                onSetup: () => _fix('notifications'),
              ),
              const SizedBox(height: 8),
              MLDPermissionTile(
                name: 'Exact Alarms',
                description: 'Shockwave Alarm and session-end timing.',
                granted: perms.exactAlarms,
                required_: false,
                onSetup: () => _fix('exactAlarms'),
              ),
              const SizedBox(height: 8),
              MLDPermissionTile(
                name: 'Battery Optimization',
                description: 'Recommended on aggressive OEM battery savers.',
                granted: perms.batteryIgnored,
                required_: false,
                onSetup: () => _fix('battery'),
              ),
              const SizedBox(height: AppSpacing.md),
              // OEM autostart / background-power manager: the setting that
              // decides whether Xiaomi / OPPO / Realme / Vivo / Tecno /
              // Infinix / itel phones silently kill enforcement.
              MLDButton(
                label: 'Open autostart & background settings',
                variant: MLDButtonVariant.secondary,
                icon: Icons.settings_applications_outlined,
                onPressed: _openOemSettings,
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                'On Xiaomi, OPPO, Realme, Vivo, Tecno, Infinix and itel phones, allow '
                'MAXLEVEL DETOX to auto-start and run in the background — otherwise the '
                'system may stop protection without warning.',
                style: AppTypography.caption(),
              ),
              const SizedBox(height: AppSpacing.xxl),
              TextButton(
                onPressed: () => setState(() => _explanationOpen = !_explanationOpen),
                child: Text(_explanationOpen ? 'Hide details' : 'What can Android NOT guarantee?'),
              ),
              if (_explanationOpen)
                MLDCard(
                  child: Text(
                    'Ordinary Android apps cannot absolutely prevent hardware shutdown, every '
                    'force-stop path, or every system-level action. MAXLEVEL DETOX uses detection, '
                    'persistent state and recovery to keep your rules active — the honest claim is '
                    '"maximum practical enforcement", not "unbypassable".',
                    style: AppTypography.caption(),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
