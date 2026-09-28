import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// v2.3 r7 — Blocking Schedules (Social Sentry "Schedules" parity).
///
/// A schedule = days + time window + app list. While one is active the
/// accessibility engine blocks its apps WITHOUT any session — always-on
/// walls for e.g. "no social apps 22:00–07:00".
class SchedulesScreen extends StatefulWidget {
  const SchedulesScreen({super.key});

  @override
  State<SchedulesScreen> createState() => _SchedulesScreenState();
}

class _SchedulesScreenState extends State<SchedulesScreen> {
  List<ScheduleProfileInfo> _schedules = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final schedules = await NativeBridge.instance.getSchedules();
    if (!mounted) return;
    setState(() {
      _schedules = schedules;
      _loading = false;
    });
  }

  Future<void> _toggle(ScheduleProfileInfo s) async {
    await NativeBridge.instance.saveSchedule(ScheduleProfileInfo(
      id: s.id,
      name: s.name,
      startMinuteOfDay: s.startMinuteOfDay,
      endMinuteOfDay: s.endMinuteOfDay,
      days: s.days,
      blockedPackages: s.blockedPackages,
      enabled: !s.enabled,
    ));
    _load();
  }

  Future<void> _delete(ScheduleProfileInfo s) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Delete "${s.name}"?'),
        content: const Text('The schedule stops blocking immediately.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Delete')),
        ],
      ),
    );
    if (confirmed == true) {
      await NativeBridge.instance.deleteSchedule(s.id);
      _load();
    }
  }

  Future<void> _edit([ScheduleProfileInfo? existing]) async {
    final saved = await Navigator.of(context).push<ScheduleProfileInfo>(
      MaterialPageRoute(
        builder: (_) => ScheduleEditorScreen(existing: existing),
        fullscreenDialog: true,
      ),
    );
    if (saved != null) {
      await NativeBridge.instance.saveSchedule(saved);
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Blocking Schedules')),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        onPressed: () => _edit(),
        child: const Icon(Icons.add),
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(
                  top: AppSpacing.xl,
                  bottom: AppSpacing.xxxl,
                ),
                children: [
                  MLDCard(
                    child: Text(
                      'Schedules block their app list during the time window — '
                      'no session needed. Emergency calls and system apps are '
                      'never blocked.',
                      style: AppTypography.caption(
                          color: AppColors.textSecondary),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  if (_schedules.isEmpty)
                    const MLDEmptyState(
                      title: 'NO SCHEDULES YET',
                      message:
                          'Create one — e.g. "Night Guard": social apps blocked 22:00 – 07:00 every day.',
                      icon: Icons.schedule,
                    ),
                  for (final s in _schedules) ...[
                    _ScheduleTile(
                      schedule: s,
                      onToggle: () => _toggle(s),
                      onEdit: () => _edit(s),
                      onDelete: () => _delete(s),
                    ),
                    const SizedBox(height: AppSpacing.md),
                  ],
                ],
              ),
      ),
    );
  }
}

class _ScheduleTile extends StatelessWidget {
  const _ScheduleTile({
    required this.schedule,
    required this.onToggle,
    required this.onEdit,
    required this.onDelete,
  });

  final ScheduleProfileInfo schedule;
  final VoidCallback onToggle;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      borderColor: schedule.enabled
          ? AppColors.primary.withValues(alpha: 0.45)
          : AppColors.edge,
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  schedule.name,
                  style: AppTypography.body(weight: FontWeight.w700),
                ),
              ),
              Switch(value: schedule.enabled, onChanged: (_) => onToggle()),
            ],
          ),
          const SizedBox(height: AppSpacing.xs),
          Row(
            children: [
              const Icon(Icons.schedule, size: 16, color: AppColors.primary),
              const SizedBox(width: 6),
              Text(schedule.timeLabel,
                  style: AppTypography.caption(weight: FontWeight.w600)),
              const SizedBox(width: AppSpacing.md),
              const Icon(Icons.calendar_today_outlined,
                  size: 14, color: AppColors.textSecondary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(schedule.daysLabel,
                    style: AppTypography.caption(
                        color: AppColors.textSecondary)),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            '${schedule.blockedPackages.length} apps blocked',
            style: AppTypography.caption(color: AppColors.textSecondary),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              MLDButton(
                label: 'Edit',
                expanded: false,
                height: 36,
                onPressed: onEdit,
              ),
              const SizedBox(width: AppSpacing.md),
              TextButton(
                onPressed: onDelete,
                child: Text('Delete',
                    style: AppTypography.caption(color: AppColors.danger)),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

class ScheduleEditorScreen extends StatefulWidget {
  const ScheduleEditorScreen({super.key, this.existing});

  final ScheduleProfileInfo? existing;

  @override
  State<ScheduleEditorScreen> createState() => _ScheduleEditorScreenState();
}

class _ScheduleEditorScreenState extends State<ScheduleEditorScreen> {
  late final TextEditingController _name;
  late int _startMinute;
  late int _endMinute;
  late Set<int> _days;
  late Set<String> _packages;
  List<AppRule> _apps = [];
  bool _loadingApps = true;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _name = TextEditingController(text: e?.name ?? '');
    _startMinute = e?.startMinuteOfDay ?? 22 * 60; // default 22:00
    _endMinute = e?.endMinuteOfDay ?? 7 * 60;      // default 07:00 (+1)
    _days = e?.days.toSet() ?? {1, 2, 3, 4, 5, 6, 7};
    _packages = e?.blockedPackages.toSet() ?? {};
    _loadApps();
  }

  // v2.5.5 audit fix: the editor's TextEditingController was never
  // disposed (the State had no dispose()).
  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _loadApps() async {
    final apps = await NativeBridge.instance.getAppRules();
    if (!mounted) return;
    setState(() {
      _apps = apps.where((a) => a.category != 'other').toList();
      if (_apps.isEmpty) _apps = apps;
      _loadingApps = false;
    });
  }

  Future<void> _pickTime(bool start) async {
    final initial = (start ? _startMinute : _endMinute);
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: initial ~/ 60, minute: initial % 60),
    );
    if (picked == null) return;
    setState(() {
      final m = picked.hour * 60 + picked.minute;
      if (start) {
        _startMinute = m;
      } else {
        _endMinute = m;
      }
    });
  }

  String _label(int minute) {
    final h = minute ~/ 60;
    final m = minute % 60;
    return '${h.toString().padLeft(2, '0')}:${m.toString().padLeft(2, '0')}';
  }

  void _save() {
    if (_packages.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Pick at least one app to block.')),
      );
      return;
    }
    if (_days.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Pick at least one day.')),
      );
      return;
    }
    final schedule = ScheduleProfileInfo(
      id: widget.existing?.id ?? DateTime.now().millisecondsSinceEpoch.toString(),
      name: _name.text.trim().isEmpty ? 'Schedule' : _name.text.trim(),
      startMinuteOfDay: _startMinute,
      endMinuteOfDay: _endMinute,
      days: _days,
      blockedPackages: _packages,
      enabled: widget.existing?.enabled ?? true,
    );
    Navigator.of(context).pop(schedule);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.existing == null ? 'New Schedule' : 'Edit Schedule'),
        actions: [
          TextButton(onPressed: _save, child: const Text('SAVE')),
        ],
      ),
      body: SafeArea(
        child: _loadingApps
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(
                  top: AppSpacing.xl,
                  bottom: AppSpacing.xxxl,
                ),
                children: [
                  Text('Name', style: AppTypography.label()),
                  const SizedBox(height: AppSpacing.sm),
                  TextField(
                    controller: _name,
                    decoration: const InputDecoration(
                      hintText: 'e.g. Night Guard',
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),

                  Text('Time window', style: AppTypography.label()),
                  const SizedBox(height: AppSpacing.sm),
                  Row(
                    children: [
                      Expanded(
                        child: MLDCard(
                          padding: const EdgeInsets.all(AppSpacing.md),
                          child: InkWell(
                            onTap: () => _pickTime(true),
                            child: Column(
                              children: [
                                Text('START',
                                    style: AppTypography.caption(
                                        color: AppColors.textSecondary)),
                                Text(_label(_startMinute),
                                    style: AppTypography.heading()),
                              ],
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: MLDCard(
                          padding: const EdgeInsets.all(AppSpacing.md),
                          child: InkWell(
                            onTap: () => _pickTime(false),
                            child: Column(
                              children: [
                                Text('END',
                                    style: AppTypography.caption(
                                        color: AppColors.textSecondary)),
                                Text(_label(_endMinute),
                                    style: AppTypography.heading()),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  if (_startMinute > _endMinute) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Text(
                      'Overnight window: blocks past midnight into the next day.',
                      style: AppTypography.caption(color: AppColors.info),
                    ),
                  ],
                  const SizedBox(height: AppSpacing.xl),

                  Text('Days', style: AppTypography.label()),
                  const SizedBox(height: AppSpacing.sm),
                  Wrap(
                    spacing: 6,
                    children: [
                      for (var d = 1; d <= 7; d++)
                        FilterChip(
                          label: Text(ScheduleProfileInfo.dayNames[d - 1]),
                          selected: _days.contains(d),
                          onSelected: (sel) => setState(() {
                            if (sel) {
                              _days.add(d);
                            } else {
                              _days.remove(d);
                            }
                          }),
                        ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.xl),

                  Text(
                    'Apps to block (${_packages.length} selected)',
                    style: AppTypography.label(),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  for (final app in _apps) ...[
                    MLDAppRuleTile(
                      name: app.appName,
                      category: app.category,
                      blocked: _packages.contains(app.packageName),
                      onToggle: (_) => setState(() {
                        if (_packages.contains(app.packageName)) {
                          _packages.remove(app.packageName);
                        } else {
                          _packages.add(app.packageName);
                        }
                      }),
                    ),
                    const SizedBox(height: 6),
                  ],
                ],
              ),
      ),
    );
  }
}
