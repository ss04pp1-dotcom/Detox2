import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Guided permission checklist (UI/UX §12–13). Every capability explains
/// WHY it is needed before the user is sent to Android Settings.
class PermissionSetupScreen extends StatefulWidget {
  const PermissionSetupScreen({super.key});

  @override
  State<PermissionSetupScreen> createState() => _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen> {
  bool _explanationOpen = false;
  bool _banglaOpen = false;

  // v2.3 r7 (BUG-1 defense): poll the native permission state every 2s
  // while this screen is visible — returning from Android Settings shows
  // the green ticks IMMEDIATELY, even if the state stream is slow or the
  // lifecycle resume event was missed. This is what r6 failed to do.
  Timer? _permPoll;

  bool _notifGuardGranted = false;
  bool get notificationListenerGranted => _notifGuardGranted;

  static const Map<String, (String, String)> _permissions = {
    'accessibility': (
      'Accessibility Service',
      'Detects restricted apps and keeps your rules active while a session runs. Used only for enforcement — never for data collection.',
    ),
    'usageAccess': (
      'Usage Access',
      'Reads app usage statistics so your dashboard and insights are accurate.',
    ),
    'overlay': (
      'Display Over Other Apps',
      'Shows the blocking screen instantly when a restricted app opens.',
    ),
    'notifications': (
      'Notifications',
      'Session status, completion and recovery notifications.',
    ),
    'notificationListener': (
      'Notification Guard (optional)',
      'Silences notifications from blocked apps while a Detox session or Monk Mode runs. Requires Notification Access.',
    ),
    'exactAlarms': (
      'Alarm Access',
      'Exact alarms for the Shockwave Alarm and session end.',
    ),
    'battery': (
      'Battery Optimization',
      'Keeps enforcement alive on aggressive OEM battery savers (optional but recommended).',
    ),
  };

  Future<void> _open(String key) async {
    if (key == 'battery') {
      await NativeBridge.instance.requestIgnoreBatteryOptimizations();
    } else {
      await NativeBridge.instance.openPermissionSettings(key);
    }
    // Returning from system settings — refresh the optional guard state
    // too (it is not part of the 1s state stream projection).
    if (key == 'notificationListener') _refreshNotificationGuard();
  }

  @override
  void initState() {
    super.initState();
    _permPoll = Timer.periodic(const Duration(seconds: 2), (_) {
      if (mounted) AppStateScope.of(context).refresh();
    });
    _refreshNotificationGuard();
  }

  Future<void> _refreshNotificationGuard() async {
    final granted = await NativeBridge.instance.isNotificationListenerGranted();
    if (mounted) setState(() => _notifGuardGranted = granted);
  }

  @override
  void dispose() {
    _permPoll?.cancel();
    super.dispose();
  }

  Future<void> _continue() async {
    // v2.5.5 audit fix: the result was ignored — a failed native write still
    // navigated to the shell, after which the splash gate bounced the user
    // back to permissions on the next cold start.
    final ok = await NativeBridge.instance.completeOnboarding();
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not complete setup — try again.')),
      );
      return;
    }
    Navigator.of(context).pushNamedAndRemoveUntil(
      AppConstants.routeShell,
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Set Up Your Focus System'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: AppStateScope.of(context),
          builder: (context, _) {
            final state = AppStateScope.of(context).state;
            final perms = state.permissions;
            final ready = perms.enforcementReady;

            return SingleChildScrollView(
              padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Bangla quick guide — the primary tester is a Bangla
                  // speaker on a vivo device; the exact tap path matters.
                  MLDCard(
                    color: AppColors.primary.withValues(alpha: 0.08),
                    borderColor: AppColors.primary.withValues(alpha: 0.35),
                    padding: const EdgeInsets.all(AppSpacing.lg),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        InkWell(
                          onTap: () => setState(() => _banglaOpen = !_banglaOpen),
                          child: Row(
                            children: [
                              const Icon(Icons.translate, size: 20, color: AppColors.primary),
                              const SizedBox(width: AppSpacing.md),
                              Expanded(
                                child: Text('বাংলা নির্দেশনা (Bangla guide)',
                                    style: AppTypography.body(weight: FontWeight.w700)),
                              ),
                              Icon(_banglaOpen ? Icons.expand_less : Icons.expand_more,
                                  color: AppColors.textSecondary),
                            ],
                          ),
                        ),
                        if (_banglaOpen) ...[
                          const SizedBox(height: AppSpacing.md),
                          Text(
                            '১. Accessibility — Settings → Accessibility → Downloaded apps → MAXLEVEL DET0X → টগল On করুন।\n'
                            '২. Usage access — Settings → More settings → App usage → MAXLEVEL DET0X → Allow।\n'
                            '৩. Display over other apps — Settings → Apps → MAXLEVEL DET0X → Display pop-up windows → Allow।\n'
                            '\nপ্রতিটি অনুমতি দেওয়ার পর অ্যাপে ফিরে এলে সবুজ টিক দেখবেন। সব সবুজ হলে Study/Detox সেশন চালু হবে। সমস্যা হলে Settings → System Health দেখুন।',
                            style: AppTypography.body(
                                color: AppColors.textSecondary, height: 1.6),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xxl),
                  Text('Required capabilities', style: AppTypography.label()),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['accessibility']!.$1,
                    description: _permissions['accessibility']!.$2,
                    granted: perms.accessibility,
                    onSetup: () => _open('accessibility'),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['usageAccess']!.$1,
                    description: _permissions['usageAccess']!.$2,
                    granted: perms.usageAccess,
                    onSetup: () => _open('usageAccess'),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['overlay']!.$1,
                    description: _permissions['overlay']!.$2,
                    granted: perms.overlay,
                    onSetup: () => _open('overlay'),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['notifications']!.$1,
                    description: _permissions['notifications']!.$2,
                    granted: perms.notifications,
                    onSetup: () => _open('notifications'),
                  ),
                  const SizedBox(height: AppSpacing.xxl),

                  Text('Recommended', style: AppTypography.label()),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['notificationListener']!.$1,
                    description: _permissions['notificationListener']!.$2,
                    granted: notificationListenerGranted,
                    required_: false,
                    onSetup: () => _open('notificationListener'),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['exactAlarms']!.$1,
                    description: _permissions['exactAlarms']!.$2,
                    granted: perms.exactAlarms,
                    required_: false,
                    onSetup: () => _open('exactAlarms'),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  MLDPermissionTile(
                    name: _permissions['battery']!.$1,
                    description: _permissions['battery']!.$2,
                    granted: perms.batteryIgnored,
                    required_: false,
                    onSetup: () => _open('battery'),
                  ),
                  const SizedBox(height: AppSpacing.xxl),

                  if (!ready)
                    const MLDWarningBanner(
                      message:
                          'Core permissions are required before your first Study or Detox session can start.',
                      tone: MLDBannerTone.warning,
                    )
                  else
                    const MLDWarningBanner(
                      message: 'Focus system ready. You can refine the rest anytime in Settings.',
                      tone: MLDBannerTone.success,
                    ),
                  const SizedBox(height: AppSpacing.xxl),

                  MLDButton(
                    label: 'Continue',
                    onPressed: _continue,
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  TextButton(
                    onPressed: () => setState(() => _explanationOpen = !_explanationOpen),
                    child: Text(_explanationOpen ? 'Hide details' : 'What can Android NOT guarantee?'),
                  ),
                  if (_explanationOpen)
                    MLDCard(
                      child: Text(
                        'Android controls some system-level actions (power off, some settings, force '
                        'stop paths) that ordinary apps cannot fully restrict. MAXLEVEL DETOX detects '
                        'and recovers where Android permits — that is maximum practical enforcement, '
                        'not a promise of the impossible.',
                        style: AppTypography.caption(),
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
