import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Widgets settings (v2.2 Phase D → v2.5.8): pin the six home-screen
/// widgets.
///
/// Widgets read native state directly (DataStore/Room) and keep working
/// when Flutter is dead. Pinning the first widget awards a one-time DP
/// bonus. The Brain Rot stage widget (Social Sentry's 5th) joined in r9;
/// the interactive Distraction Trend chart (v2.5.8 roadmap) is the 6th.
class WidgetsScreen extends StatefulWidget {
  const WidgetsScreen({super.key});

  @override
  State<WidgetsScreen> createState() => _WidgetsScreenState();
}

class _WidgetsScreenState extends State<WidgetsScreen> {
  bool _anyPinned = false;
  bool _pinSupported = true;
  bool _loading = true;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final status = await NativeBridge.instance.getWidgetStatus();
    if (!mounted) return;
    setState(() {
      _anyPinned = status['anyPinned'] as bool? ?? false;
      _pinSupported = status['pinSupported'] as bool? ?? false;
      _loading = false;
    });
  }

  Future<void> _pin(String which) async {
    if (_busy) return;
    setState(() => _busy = true);
    final ok = await NativeBridge.instance.pinWidget(which);
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text(
                'Pin request sent — confirm it on your home screen. +10 DP for your first widget.')),
      );
      await Future<void>.delayed(const Duration(seconds: 2));
      await _load();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text(
                'This launcher does not support one-tap pinning. Add the widget from your launcher\'s widget list instead.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Home-screen widgets')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    MLDCard(
                      child: Row(
                        children: [
                          Icon(
                            _anyPinned
                                ? Icons.check_circle_outline
                                : Icons.widgets_outlined,
                            color:
                                _anyPinned ? AppColors.success : AppColors.primary,
                          ),
                          const SizedBox(width: AppSpacing.md),
                          Expanded(
                            child: Text(
                              _anyPinned
                                  ? 'Widgets are live on your home screen'
                                  : 'No MAXLEVEL widgets pinned yet',
                              style: const TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 13),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xxl),
                    MLDSectionHeader(
                        title: _pinSupported
                            ? 'Tap to pin'
                            : 'Add from your launcher\'s widget list'),
                    const SizedBox(height: AppSpacing.md),
                    _widgetTile(
                      which: 'streak',
                      icon: Icons.local_fire_department_outlined,
                      title: 'Streak',
                      subtitle: 'Level, streak days and freeze state',
                    ),
                    _widgetTile(
                      which: 'session',
                      icon: Icons.timer_outlined,
                      title: 'Session',
                      subtitle: 'Active mode and time remaining',
                    ),
                    _widgetTile(
                      which: 'usage',
                      icon: Icons.bar_chart,
                      title: 'Today',
                      subtitle: 'Focus minutes and blocked attempts',
                    ),
                    _widgetTile(
                      which: 'coins',
                      icon: Icons.monetization_on_outlined,
                      title: 'Coins',
                      subtitle: 'Balance and bailout cost',
                    ),
                    _widgetTile(
                      which: 'brainrot',
                      icon: Icons.psychology_outlined,
                      title: 'Brain Rot Stage',
                      subtitle: 'HEALTHY → MILD → SEVERE → FULL badge',
                    ),
                    _widgetTile(
                      which: 'trend',
                      icon: Icons.show_chart,
                      title: 'Distraction Trend',
                      subtitle: '7-day chart: screen-time vs reels skipped — tap the widget to switch',
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    const Text(
                      'Widgets update on session changes, progress changes and every 30 minutes. They never hold enforcement authority — tap opens the app.',
                      textAlign: TextAlign.center,
                      style:
                          TextStyle(color: AppColors.textDisabled, fontSize: 12),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _widgetTile({
    required String which,
    required IconData icon,
    required String title,
    required String subtitle,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          children: [
            Icon(icon, color: AppColors.primary, size: 26),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text(subtitle,
                      style: const TextStyle(
                          color: AppColors.textSecondary, fontSize: 12)),
                ],
              ),
            ),
            MLDButton(
              label: 'Pin',
              expanded: false,
              loading: _busy,
              onPressed: () => _pin(which),
            ),
          ],
        ),
      ),
    );
  }
}
