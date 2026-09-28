import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Shockwave Alarm setup (UI/UX §36). The alarm ring screen + puzzle are
/// NATIVE (AlarmActivity) so they work even if Flutter is dead (TRD §26).
class AlarmSetupScreen extends StatefulWidget {
  const AlarmSetupScreen({super.key});

  @override
  State<AlarmSetupScreen> createState() => _AlarmSetupScreenState();
}

class _AlarmSetupScreenState extends State<AlarmSetupScreen> {
  List<AlarmConfig> _alarms = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final alarms = await NativeBridge.instance.getAlarms();
    if (!mounted) return;
    setState(() {
      _alarms = alarms;
      _loading = false;
    });
  }

  Future<void> _create() async {
    final config = await _showEditor();
    if (config == null) return;
    final ok = await NativeBridge.instance.scheduleAlarm(config);
    // v2.5.5 audit fix: a failed scheduleAlarm used to be invisible — the
    // alarm simply never appeared after reload.
    if (!mounted) return;
    if (ok) {
      _load();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Engine refused the change.')),
      );
    }
  }

  Future<void> _toggle(AlarmConfig alarm) async {
    // v2.5.5 audit fix: same feedback for enable/disable writes.
    final ok = await NativeBridge.instance.scheduleAlarm(
      AlarmConfig(
        id: alarm.id,
        hour: alarm.hour,
        minute: alarm.minute,
        repeatDays: alarm.repeatDays,
        difficulty: alarm.difficulty,
        enabled: !alarm.enabled,
        label: alarm.label,
      ),
    );
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Engine refused the change.')),
      );
    }
    _load();
  }

  Future<void> _delete(AlarmConfig alarm) async {
    await NativeBridge.instance.cancelAlarm(alarm.id);
    _load();
  }

  Future<AlarmConfig?> _showEditor() {
    TimeOfDay time = const TimeOfDay(hour: 6, minute: 30);
    String difficulty = 'MEDIUM';
    final days = <int>{};

    return showModalBottomSheet<AlarmConfig>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.hero)),
      ),
      builder: (context) => StatefulBuilder(
        builder: (context, setSheet) => Padding(
          padding: EdgeInsets.only(
            left: AppSpacing.xl,
            right: AppSpacing.xl,
            top: AppSpacing.xxl,
            bottom: MediaQuery.of(context).viewInsets.bottom + AppSpacing.xxxl,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('SHOCKWAVE ALARM', style: AppTypography.heading()),
              const SizedBox(height: AppSpacing.xxl),
              InkWell(
                onTap: () async {
                  final picked = await showTimePicker(
                    context: context,
                    initialTime: time,
                  );
                  if (picked != null) setSheet(() => time = picked);
                },
                borderRadius: BorderRadius.circular(AppRadii.sm),
                child: MLDCard(
                  padding: const EdgeInsets.all(AppSpacing.xl),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('WAKE TIME', style: AppTypography.label()),
                          Text(
                            '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}',
                            style: AppTypography.timer(size: 40),
                          ),
                        ],
                      ),
                      const Icon(Icons.edit, color: AppColors.textSecondary),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              Text('REPEAT', style: AppTypography.label()),
              const SizedBox(height: AppSpacing.md),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  for (int d = 1; d <= 7; d++)
                    _dayChip(d, days.contains(d), (v) => setSheet(
                          () => v ? days.add(d) : days.remove(d),
                        )),
                ],
              ),
              const SizedBox(height: AppSpacing.xxl),
              Text('DIFFICULTY', style: AppTypography.label()),
              const SizedBox(height: AppSpacing.md),
              Row(
                children: [
                  for (final d in ['EASY', 'MEDIUM', 'HARD'])
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.only(right: AppSpacing.sm),
                        child: GestureDetector(
                          onTap: () => setSheet(() => difficulty = d),
                          child: Container(
                            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                            decoration: BoxDecoration(
                              color: difficulty == d
                                  ? AppColors.primary.withValues(alpha: 0.15)
                                  : Colors.transparent,
                              borderRadius: BorderRadius.circular(AppRadii.sm),
                              border: Border.all(
                                color: difficulty == d ? AppColors.primary : AppColors.edge,
                              ),
                            ),
                            child: Center(
                              child: Text(
                                d[0] + d.substring(1).toLowerCase(),
                                style: AppTypography.caption(
                                  color: difficulty == d ? AppColors.primary : AppColors.textSecondary,
                                  weight: FontWeight.w700,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.xxl),
              MLDButton(
                label: 'SAVE ALARM',
                onPressed: () => Navigator.of(context).pop(
                  AlarmConfig(
                    id: 'alm_${DateTime.now().millisecondsSinceEpoch}',
                    hour: time.hour,
                    minute: time.minute,
                    repeatDays: days.toList()..sort(),
                    difficulty: difficulty,
                    enabled: true,
                    label: 'Wake up',
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Shockwave Alarm')),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        onPressed: _create,
        child: const Icon(Icons.add),
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _alarms.isEmpty
                ? const MLDEmptyState(
                    title: 'NO ALARMS YET',
                    message:
                        'Shockwave Alarm rings loud and only stops when you solve a cognitive puzzle.',
                    icon: Icons.alarm,
                  )
                : ListView.separated(
                    padding: AppSpacing.screenH.copyWith(
                      top: AppSpacing.xl,
                      bottom: AppSpacing.huge,
                    ),
                    itemCount: _alarms.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, i) => _tile(_alarms[i]),
                  ),
      ),
    );
  }

  Widget _tile(AlarmConfig a) {
    final hasRepeat = a.repeatDays.isNotEmpty;
    final repeatLabel = hasRepeat
        ? a.repeatDays.map((d) => ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][d - 1]).join(' ')
        : 'Once';

    return MLDCard(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${a.hour.toString().padLeft(2, '0')}:${a.minute.toString().padLeft(2, '0')}',
                  style: AppTypography.timer(size: 32, color: a.enabled ? AppColors.textPrimary : AppColors.textDisabled),
                ),
                Text('$repeatLabel · ${a.difficulty[0]}${a.difficulty.substring(1).toLowerCase()} puzzle',
                    style: AppTypography.caption()),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline, color: AppColors.textSecondary),
            onPressed: () => _delete(a),
          ),
          Switch(value: a.enabled, onChanged: (_) => _toggle(a)),
        ],
      ),
    );
  }

  Widget _dayChip(int day, bool selected, ValueChanged<bool> onToggle) {
    const names = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
    return GestureDetector(
      onTap: () => onToggle(!selected),
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: selected ? AppColors.primary.withValues(alpha: 0.2) : AppColors.elevated,
          border: Border.all(color: selected ? AppColors.primary : AppColors.edge),
        ),
        child: Center(
          child: Text(names[day - 1],
              style: AppTypography.caption(
                color: selected ? AppColors.primary : AppColors.textSecondary,
                weight: FontWeight.w700,
              )),
        ),
      ),
    );
  }
}
