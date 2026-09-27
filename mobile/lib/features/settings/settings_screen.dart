import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// Settings hub (UI/UX §41). During an active session, policy controls are
/// LOCKED — this screen honors that state (§42).
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.embedded = false});

  final bool embedded;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

/// Embedded variant used by the Settings tab.
class SettingsScreenRef extends StatelessWidget {
  const SettingsScreenRef({super.key});

  @override
  Widget build(BuildContext context) => const SettingsScreen(embedded: true);
}

class _SettingsScreenState extends State<SettingsScreen> {
  // v2.3 r7 — notification guard status (fetched on demand; not part of
  // the 1s state-stream projection).
  bool _notifGranted = false;
  bool _notifEnabled = true;

  @override
  void initState() {
    super.initState();
    _loadNotificationGuard();
  }

  Future<void> _loadNotificationGuard() async {
    final guard = await NativeBridge.instance.getNotificationGuard();
    if (!mounted) return;
    setState(() {
      _notifGranted = guard.granted;
      _notifEnabled = guard.enabled;
    });
  }

  Future<void> _toggleNotificationGuard(bool value) async {
    await NativeBridge.instance.setNotificationGuardEnabled(value);
    _loadNotificationGuard();
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final locked = app.state.sessionActive;

    final body = SingleChildScrollView(
      padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxxl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.embedded) ...[
            Text('SETTINGS', style: AppTypography.heading()),
            const SizedBox(height: AppSpacing.xxl),
          ],

          if (locked) ...[
            MLDCard(
              color: AppColors.warning.withValues(alpha: 0.08),
              borderColor: AppColors.warning.withValues(alpha: 0.4),
              child: Row(
                children: [
                  const Icon(Icons.lock, color: AppColors.warning, size: 28),
                  const SizedBox(width: AppSpacing.lg),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('SESSION ACTIVE', style: AppTypography.body(weight: FontWeight.w800)),
                        Text('Your rules are currently locked. Settings become editable when the session ends.',
                            style: AppTypography.caption()),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xxl),
          ],

          _section(context, 'GENERAL', [
            // v2.5.5 audit fix: these rows previously carried dead
            // `onTap: () {}` handlers — they are informational now (no
            // chevron, not tappable; _item hides the chevron without onTap).
            _item(Icons.dark_mode_outlined, 'Theme', 'Dark (default)'),
            _item(Icons.block_outlined, 'Shorts Blocker', 'Platforms, warnings, Cage',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeShortsSettings),
                enabled: !locked),
            _item(Icons.widgets_outlined, 'Home-screen Widgets', 'Streak, session, usage, coins',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeWidgets)),
          ]),
          _section(context, 'RULES', [
            _item(Icons.apps, 'App Rules', 'Allow / Block + daily time limits',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeAppRules),
                enabled: !locked),
            _item(Icons.schedule, 'Blocking Schedules',
                'Always-on time windows (e.g. 22:00 – 07:00)',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeSchedules),
                enabled: !locked),
            _item(Icons.alarm, 'Shockwave Alarm', 'Puzzle alarms',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeAlarmSetup)),
          ]),

          // v2.5 r9 — DISCIPLINE section (Social Sentry hard modes).
          _section(context, 'DISCIPLINE', [
            _item(Icons.phonelink_lock, 'Lock My Phone',
                'Full-device lockdown · Device Admin',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeLockMyPhone)),
            _item(Icons.self_improvement, 'Monk Mode',
                'Allowlist-only window · essentials + chosen apps',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeMonk)),
            _item(Icons.military_tech, 'Prime Commit',
                'All-or-nothing contract · TOTP emergency exit',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routePrime)),
            _item(Icons.pause_circle_outline, 'Safety Pause',
                'A breath of friction before chosen apps',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeSafety)),
            _item(Icons.key, 'Emergency Codes',
                'Rotating TOTP sheet — shows once',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeEmergencyCodes)),
            _item(Icons.checklist, 'Tasks & Routines',
                'Task +8 · subtask +2 · routine +4 DP',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeTasks)),
          ]),

          // v2.5 r9 — ACCOUNT section (SS profile port).
          _section(context, 'ACCOUNT', [
            _item(Icons.person_outline, 'Account',
                'Identity, plan, device admin',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeAccount)),
            _item(Icons.group_outlined, 'Community & Referral',
                'Public commits, friends, PRO rewards',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCommunity)),
            _item(Icons.favorite_outline, 'Sinthia',
                'AI accountability companion',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCompanion)),
          ]),

          // v2.3 r7 — Notification Guard card (Social Sentry E14 parity):
          // listener access + toggle in one place.
          _section(context, 'NOTIFICATIONS', [
            _notificationGuardItem(),
          ]),
          _section(context, 'SUPPORT', [
            _item(Icons.workspace_premium_outlined, 'MAXLEVEL PRO',
                'Support the app — safety stays free',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routePaywall)),
            // v2.5.7 (H-2): report a problem — the support-ticket producer.
            _item(Icons.support_agent_outlined, 'Report a Problem',
                'Tickets + replies from the team',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeSupport)),
          ]),
          _section(context, 'SYSTEM', [
            _item(Icons.verified_user_outlined, 'Permission Center', 'Enforcement capabilities',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routePermissionCenter)),
            _item(Icons.monitor_heart_outlined, 'System Health',
                'Self-test, enforcement log, error report',
                onTap: () => Navigator.of(context).pushNamed(AppConstants.routeSystemHealth)),
            _item(Icons.cloud_outlined, 'Backup', 'Google Drive (not in this build)'),
            _item(Icons.privacy_tip_outlined, 'Privacy', 'All enforcement data stays on your device'),
            _item(Icons.info_outline, 'About', '${AppConstants.appName} v${AppConstants.appVersion}'),
          ]),

          // Debug tools — debug builds only. The native side refuses every
          // debug_* method in release builds (TRD §139/§140).
          if (kDebugMode) _section(context, 'DEBUG (NOT IN RELEASE)', [
            _item(Icons.fast_forward, 'Fast-forward timer', 'Skip 1 minute (debug)',
                onTap: () => NativeBridge.instance.debugFastForward(1)),
            _item(Icons.smart_display_outlined, 'Simulate Shorts attempt', 'Trigger warning flow (debug)',
                onTap: () => NativeBridge.instance.debugSimulateShorts()),
            // v2.5.5 audit fix: single-tap debug reset used to wipe
            // sessions/coins with NO confirmation.
            _item(Icons.delete_forever, 'Reset local data', 'Wipe sessions/coins (debug)',
                onTap: _confirmDebugReset),
          ]),
        ],
      ),
    );

    if (widget.embedded) return body;
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Settings')),
      body: SafeArea(child: body),
    );
  }

  /// v2.3 r7 — Notification Guard row: access state + toggle. Silences
  /// notifications from blocked apps during Detox sessions / Monk Mode.
  Widget _notificationGuardItem() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                _notifGranted ? Icons.notifications_off_outlined : Icons.notifications_none,
                color: _notifGranted ? AppColors.primary : AppColors.textSecondary,
                size: 22,
              ),
              const SizedBox(width: AppSpacing.lg),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Notification Guard',
                        style: AppTypography.body(weight: FontWeight.w600)),
                    Text(
                      _notifGranted
                          ? 'Mutes blocked-app notifications during enforcement'
                          : 'Not granted — tap to allow Notification Access',
                      style: AppTypography.caption(),
                    ),
                  ],
                ),
              ),
              Switch(value: _notifEnabled, onChanged: _toggleNotificationGuard),
            ],
          ),
          if (!_notifGranted)
            Padding(
              padding: const EdgeInsets.only(left: AppSpacing.xl),
              child: TextButton.icon(
                icon: const Icon(Icons.settings_outlined, size: 16),
                label: const Text('Grant access'),
                onPressed: () async {
                  await NativeBridge.instance
                      .openPermissionSettings('notificationListener');
                  _loadNotificationGuard();
                },
              ),
            ),
        ],
      ),
    );
  }

  /// v2.5.5 audit fix: confirmation gate for the destructive debug reset.
  Future<void> _confirmDebugReset() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Reset local data?'),
        content: const Text(
            'Wipes sessions, coins and progress on this device. This cannot '
            'be undone.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Wipe')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await NativeBridge.instance.debugResetData();
  }

  Widget _section(BuildContext context, String title, List<Widget> items) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        MLDSectionHeader(title: title),
        MLDCard(
          padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
          child: Column(children: items),
        ),
        const SizedBox(height: AppSpacing.xxl),
      ],
    );
  }

  Widget _item(
    IconData icon,
    String title,
    String subtitle, {
    VoidCallback? onTap,
    bool enabled = true,
  }) {
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: InkWell(
        onTap: enabled ? onTap : null,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
          child: Row(
            children: [
              Icon(icon, color: AppColors.textSecondary, size: 22),
              const SizedBox(width: AppSpacing.lg),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: AppTypography.body(weight: FontWeight.w600)),
                    Text(subtitle, style: AppTypography.caption()),
                  ],
                ),
              ),
              if (onTap != null)
                const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            ],
          ),
        ),
      ),
    );
  }
}
