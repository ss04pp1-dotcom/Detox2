import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// App Rules (UI/UX §44): category-grouped Allow/Block list sourced from the
/// native policy engine. v2.3 r7: per-app DAILY TIME LIMITS live here too
/// (Social Sentry App Limits parity) — tap the limit chip under a tile to
/// set a daily minute budget.
class AppRulesScreen extends StatefulWidget {
  const AppRulesScreen({super.key});

  @override
  State<AppRulesScreen> createState() => _AppRulesScreenState();
}

class _AppRulesScreenState extends State<AppRulesScreen> {
  List<AppRule> _apps = [];
  Map<String, AppLimitEntry> _limits = {};
  bool _loading = true;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final apps = await NativeBridge.instance.getAppRules();
    final limits = await NativeBridge.instance.getAppLimits();
    if (!mounted) return;
    setState(() {
      _apps = apps;
      _limits = {
        for (final l in limits) l.packageName: l,
      };
      _loading = false;
    });
  }

  Future<void> _toggle(AppRule rule) async {
    // v2.5.5 audit fix: the native verdict was ignored — a refused write
    // was invisible and the tile silently reverted on reload.
    final ok = await NativeBridge.instance
        .setAppRule(rule.packageName, !rule.blocked);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Engine refused the change.')),
      );
    }
    _load();
  }

  Future<void> _setLimit(AppRule rule) async {
    final current = _limits[rule.packageName]?.dailyLimitMinutes ?? 0;
    final minutes = await showModalBottomSheet<int>(
      context: context,
      builder: (context) => _LimitPickerSheet(current: current),
    );
    if (minutes == null) return;
    // v2.5.5 audit fix: same feedback for the per-app limit write.
    final ok = await NativeBridge.instance
        .setAppLimit(rule.packageName, minutes);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Engine refused the change.')),
      );
    }
    _load();
  }

  String _appName(String pkg) {
    AppRule? match;
    for (final a in _apps) {
      if (a.packageName == pkg) {
        match = a;
        break;
      }
    }
    if (match != null) return match.appName;
    final dot = pkg.lastIndexOf('.');
    return dot >= 0 ? pkg.substring(dot + 1) : pkg;
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _apps
        .where((a) => _query.isEmpty || a.appName.toLowerCase().contains(_query.toLowerCase()))
        .toList();

    final byCategory = <String, List<AppRule>>{};
    for (final a in filtered) {
      byCategory.putIfAbsent(a.category, () => []).add(a);
    }
    final categories = byCategory.keys.toList()..sort();

    return Scaffold(
      appBar: AppBar(title: const Text('App Rules')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  Padding(
                    padding: AppSpacing.screenH.copyWith(top: AppSpacing.lg),
                    child: TextField(
                      onChanged: (q) => setState(() => _query = q),
                      decoration: InputDecoration(
                        hintText: 'Search installed apps…',
                        prefixIcon: const Icon(Icons.search, color: AppColors.textSecondary),
                        filled: true,
                        fillColor: AppColors.surface,
                        contentPadding: const EdgeInsets.all(AppSpacing.lg),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(AppRadii.sm),
                          borderSide: const BorderSide(color: AppColors.edge),
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(AppRadii.sm),
                          borderSide: const BorderSide(color: AppColors.edge),
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(AppRadii.sm),
                          borderSide: const BorderSide(color: AppColors.primary),
                        ),
                      ),
                    ),
                  ),
                  Expanded(
                    child: ListView(
                      padding: AppSpacing.screenH.copyWith(
                        top: AppSpacing.xl,
                        bottom: AppSpacing.xxxl,
                      ),
                      children: [
                        // v2.3 r7 — active limits summary (top of the list).
                        if (_limits.isNotEmpty) ...[
                          MLDCard(
                            padding: const EdgeInsets.all(AppSpacing.lg),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('DAILY TIME LIMITS',
                                    style: AppTypography.label()),
                                const SizedBox(height: AppSpacing.sm),
                                for (final l in _limits.values.take(4))
                                  Text(
                                    '${_appName(l.packageName)} — ${l.minutesUsedToday}/${l.dailyLimitMinutes} min today',
                                    style: AppTypography.caption(
                                        color: l.minutesUsedToday >= l.dailyLimitMinutes
                                            ? AppColors.danger
                                            : AppColors.textSecondary),
                                  ),
                                if (_limits.length > 4)
                                  Text('+${_limits.length - 4} more…',
                                      style: AppTypography.caption(
                                          color: AppColors.textSecondary)),
                              ],
                            ),
                          ),
                          const SizedBox(height: AppSpacing.lg),
                        ],
                        for (final cat in categories) ...[
                          MLDSectionHeader(title: cat.toUpperCase()),
                          for (final a in byCategory[cat]!) ...[
                            MLDAppRuleTile(
                              name: a.appName,
                              category: a.category,
                              blocked: a.blocked,
                              onToggle: (_) => _toggle(a),
                            ),
                            if (_limits[a.packageName] != null)
                              Padding(
                                padding:
                                    const EdgeInsets.only(left: AppSpacing.lg, top: 4),
                                child: InkWell(
                                  onTap: () => _setLimit(a),
                                  child: Row(
                                    children: [
                                      Icon(
                                        Icons.timer_outlined,
                                        size: 14,
                                        color: (_limits[a.packageName]!
                                                    .minutesUsedToday >=
                                                _limits[a.packageName]!
                                                    .dailyLimitMinutes)
                                            ? AppColors.danger
                                            : AppColors.primary,
                                      ),
                                      const SizedBox(width: 4),
                                      Text(
                                        '${_limits[a.packageName]!.minutesUsedToday}/${_limits[a.packageName]!.dailyLimitMinutes} min today — tap to change',
                                        style: AppTypography.caption(
                                            color: AppColors.textSecondary),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            const SizedBox(height: 8),
                          ],
                          const SizedBox(height: AppSpacing.md),
                        ],
                        if (filtered.isEmpty)
                          const MLDEmptyState(
                            title: 'NO APPS FOUND',
                            message: 'Try a different search term.',
                            icon: Icons.apps,
                          ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

/// Bottom sheet: pick a daily limit (0 = none).
class _LimitPickerSheet extends StatelessWidget {
  const _LimitPickerSheet({required this.current});

  final int current;

  @override
  Widget build(BuildContext context) {
    const options = [0, 15, 30, 45, 60, 90, 120, 180, 240];
    return SafeArea(
      child: ListView(
        padding: AppSpacing.screenH.copyWith(
          top: AppSpacing.xl,
          bottom: AppSpacing.xxxl,
        ),
        children: [
          Text('Daily time limit', style: AppTypography.heading()),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'The app is blocked for the rest of the day once the budget is '
            'spent. Resets at midnight. Unlock early with coins.',
            style: AppTypography.caption(color: AppColors.textSecondary),
          ),
          const SizedBox(height: AppSpacing.lg),
          for (final m in options)
            ListTile(
              title: Text(
                m == 0 ? 'No limit' : '$m minutes / day',
                style: AppTypography.body(
                    weight: m == current ? FontWeight.w700 : FontWeight.w400),
              ),
              trailing: m == current
                  ? const Icon(Icons.check, color: AppColors.primary)
                  : null,
              onTap: () => Navigator.pop(context, m),
            ),
        ],
      ),
    );
  }
}
