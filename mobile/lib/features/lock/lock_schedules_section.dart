import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Scheduled + recurring Lock My Phone windows (v2.5 r9.3).
///
/// The native LockScheduler owns all timing; this widget only lists,
/// creates, toggles and deletes schedules. A schedule whose window is live
/// (with a lock session running) is read-only — native refuses the change.
class LockSchedulesSection extends StatefulWidget {
  const LockSchedulesSection({super.key, required this.adminActive});

  final bool adminActive;

  @override
  State<LockSchedulesSection> createState() => _LockSchedulesSectionState();
}

class _LockSchedulesSectionState extends State<LockSchedulesSection> {
  List<Map<String, dynamic>> _items = const [];
  bool _loading = true;

  static const _dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  static const _onceDurations = [30, 60, 120, 240, 480];

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final items = await NativeBridge.instance.getLockSchedules();
    if (!mounted) return;
    setState(() {
      _items = items;
      _loading = false;
    });
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _save(Map<String, dynamic> schedule) async {
    final error = await NativeBridge.instance.saveLockSchedule(schedule);
    if (!mounted) return;
    if (error != null) _toast(error);
    await _reload();
  }

  Future<void> _delete(String id) async {
    final error = await NativeBridge.instance.deleteLockSchedule(id);
    if (!mounted) return;
    if (error != null) _toast(error);
    await _reload();
  }

  String _two(int v) => v.toString().padLeft(2, '0');

  String _fmtMin(int m) => '${_two(m ~/ 60)}:${_two(m % 60)}';

  String _fmtDuration(int mins) =>
      mins < 60 ? '$mins min' : '${mins ~/ 60} hr${mins >= 120 ? 's' : ''}';

  String _describe(Map<String, dynamic> s) {
    if (s['kind'] == 'once') {
      final start = DateTime.fromMillisecondsSinceEpoch(
          (s['startEpochMs'] as num).toInt());
      final mins = (s['durationMinutes'] as num).toInt();
      final when =
          '${_two(start.day)}/${_two(start.month)}/${start.year} ${_two(start.hour)}:${_two(start.minute)}';
      final done = s['consumed'] == true ? ' · done' : '';
      return 'Once · $when · ${_fmtDuration(mins)}$done';
    }
    final days = ((s['days'] as List<dynamic>?) ?? const [])
        .map((e) => (e as num).toInt())
        .where((d) => d >= 1 && d <= 7)
        .toList();
    final dayText = days.length == 7
        ? 'Every day'
        : days.map((d) => _dayNames[d - 1]).join(', ');
    final st = (s['startMin'] as num).toInt();
    final en = (s['endMin'] as num).toInt();
    final range = st == en ? 'all day' : '${_fmtMin(st)}–${_fmtMin(en)}';
    return '$dayText · $range';
  }

  Future<void> _addWeekly() async {
    final selected = <int>{1, 2, 3, 4, 5};
    var start = const TimeOfDay(hour: 22, minute: 0);
    var end = const TimeOfDay(hour: 6, minute: 0);

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('Weekly lock'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 6,
                  children: [
                    for (var d = 1; d <= 7; d++)
                      FilterChip(
                        label: Text(_dayNames[d - 1]),
                        selected: selected.contains(d),
                        onSelected: (v) => setLocal(() {
                          if (v) {
                            selected.add(d);
                          } else {
                            selected.remove(d);
                          }
                        }),
                      ),
                  ],
                ),
                const SizedBox(height: 12),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Starts'),
                  trailing: Text(start.format(ctx)),
                  onTap: () async {
                    final t =
                        await showTimePicker(context: ctx, initialTime: start);
                    if (t != null) setLocal(() => start = t);
                  },
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Ends'),
                  trailing: Text(end.format(ctx)),
                  onTap: () async {
                    final t =
                        await showTimePicker(context: ctx, initialTime: end);
                    if (t != null) setLocal(() => end = t);
                  },
                ),
                const SizedBox(height: 8),
                const Text(
                  'If the end is earlier than the start, the lock runs past '
                  'midnight. While a window is live the phone re-locks '
                  'continuously and the schedule cannot be edited or deleted '
                  '— the only exit is the coin bailout.',
                  style:
                      TextStyle(color: AppColors.textSecondary, fontSize: 12),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed:
                  selected.isEmpty ? null : () => Navigator.pop(ctx, true),
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !mounted) return;

    await _save({
      'kind': 'weekly',
      'enabled': true,
      'days': (selected.toList()..sort()),
      'startMin': start.hour * 60 + start.minute,
      'endMin': end.hour * 60 + end.minute,
    });
  }

  Future<void> _addOnce() async {
    final now = DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: now,
      firstDate: now,
      lastDate: now.add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(now.add(const Duration(hours: 1))),
    );
    if (time == null || !mounted) return;
    final startAt =
        DateTime(date.year, date.month, date.day, time.hour, time.minute);

    var minutes = 60;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('One-time lock'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Starts ${_two(startAt.day)}/${_two(startAt.month)} '
                '${_two(startAt.hour)}:${_two(startAt.minute)}',
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 6,
                children: [
                  for (final m in _onceDurations)
                    ChoiceChip(
                      label: Text(_fmtDuration(m)),
                      selected: minutes == m,
                      onSelected: (_) => setLocal(() => minutes = m),
                    ),
                ],
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !mounted) return;

    await _save({
      'kind': 'once',
      'enabled': true,
      'startEpochMs': startAt.millisecondsSinceEpoch,
      'durationMinutes': minutes,
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const MLDSectionHeader(title: 'Scheduled locks'),
        const SizedBox(height: AppSpacing.md),
        if (!widget.adminActive)
          const Padding(
            padding: EdgeInsets.only(bottom: AppSpacing.md),
            child: Text(
              'Device Admin is required — a schedule will not start until it '
              'is granted.',
              style: TextStyle(color: AppColors.warning, fontSize: 12),
            ),
          ),
        if (_loading)
          const Center(child: CircularProgressIndicator())
        else if (_items.isEmpty)
          const Text(
            'No schedules yet. A weekly schedule locks the phone at the same '
            'time on the days you pick; a one-time lock starts once at a set '
            'time.',
            style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
          )
        else
          for (final s in _items)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: MLDCard(
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _describe(s),
                        style: const TextStyle(
                            fontSize: 13, fontWeight: FontWeight.w600),
                      ),
                    ),
                    if (s['windowLive'] == true)
                      const Padding(
                        padding: EdgeInsets.only(right: 8),
                        child: Icon(Icons.lock, size: 18),
                      ),
                    Switch(
                      value: s['enabled'] == true,
                      onChanged: s['windowLive'] == true || s['consumed'] == true
                          ? null
                          : (v) => _save({...s, 'enabled': v}),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline),
                      // v2.5.5 audit fix: unguarded `s['id'] as String` threw
                      // if native ever emitted a numeric id — parse
                      // defensively and disable the row when absent.
                      onPressed: s['windowLive'] == true || s['id'] == null
                          ? null
                          : () => _delete(s['id'].toString()),
                    ),
                  ],
                ),
              ),
            ),
        const SizedBox(height: AppSpacing.md),
        MLDButton(
          label: 'Add weekly schedule',
          variant: MLDButtonVariant.secondary,
          icon: Icons.repeat,
          onPressed: _addWeekly,
        ),
        const SizedBox(height: AppSpacing.sm),
        MLDButton(
          label: 'Add one-time lock',
          variant: MLDButtonVariant.secondary,
          icon: Icons.event,
          onPressed: _addOnce,
        ),
      ],
    );
  }
}
