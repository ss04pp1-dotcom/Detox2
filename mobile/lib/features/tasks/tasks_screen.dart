import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// TasksScreen (v2.5 r9) — SS task economy port: tasks + subtasks +
/// routines with DP awards (task +8 / subtask +2 / routine +4 / combo +2).
/// Routines reset daily; completion history feeds the discipline combo.
class TasksScreen extends StatefulWidget {
  const TasksScreen({super.key});

  @override
  State<TasksScreen> createState() => _TasksScreenState();
}

class _TasksScreenState extends State<TasksScreen> {
  bool _loading = true;
  bool _busy = false;
  List<Map<String, dynamic>> _tasks = const [];
  Map<String, dynamic>? _status;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final data = await NativeBridge.instance.getTasks();
    if (!mounted) return;
    setState(() {
      _tasks = (data?['tasks'] as List<dynamic>? ?? const [])
          .map((e) => Map<String, dynamic>.from(Map<dynamic, dynamic>.from(e)))
          .toList();
      _status = data?['status'] as Map<String, dynamic>?;
      _loading = false;
    });
  }

  Future<void> _addTask({bool routine = false}) async {
    final title = await _askText(
      title: routine ? 'New routine' : 'New task',
      hint: routine ? 'e.g. Read 10 pages' : 'e.g. Finish physics chapter',
    );
    if (title == null || title.trim().isEmpty) return;
    setState(() => _busy = true);
    await NativeBridge.instance.addTask(
      title: title.trim(),
      routine: routine,
      priority: routine ? 3 : 2,
    );
    if (!mounted) return;
    setState(() => _busy = false);
    _load();
  }

  Future<String?> _askText({
    required String title,
    required String hint,
  }) async {
    final controller = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(hintText: hint),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, null),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, controller.text),
              child: const Text('Add')),
        ],
      ),
    );
    // v2.5.5 audit fix: dialog TextEditingControllers were never disposed.
    controller.dispose();
    return result;
  }

  Future<void> _toggleComplete(Map<String, dynamic> task) async {
    final id = task['id'] as String;
    final done = task['completedAt'] != null;
    if (done) {
      await NativeBridge.instance.reopenTask(taskId: id);
    } else {
      final ok = await NativeBridge.instance.completeTask(taskId: id);
      if (ok && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(task['routine'] == true
              ? 'Routine complete. +4 DP'
              : 'Task complete. +8 DP'),
        ));
      }
    }
    _load();
  }

  Future<void> _addSubtask(Map<String, dynamic> task) async {
    final title = await _askText(title: 'Subtask', hint: 'A smaller step');
    if (title == null || title.trim().isEmpty) return;
    await NativeBridge.instance
        .addSubtask(taskId: task['id'] as String, title: title.trim());
    _load();
  }

  Future<void> _toggleSubtask(
      Map<String, dynamic> task, Map<String, dynamic> sub) async {
    await NativeBridge.instance.toggleSubtask(
      taskId: task['id'] as String,
      subtaskId: sub['id'] as String,
    );
    _load();
  }

  Future<void> _delete(Map<String, dynamic> task) async {
    await NativeBridge.instance.deleteTask(taskId: task['id'] as String);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final open = _tasks.where((t) => t['completedAt'] == null).toList();
    final done = _tasks.where((t) => t['completedAt'] != null).toList();
    final doneToday = _status?['doneToday'] as int? ?? 0;
    final combo = _status?['comboEarnedToday'] as bool? ?? false;

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Tasks')),
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FloatingActionButton.small(
            heroTag: 'routine',
            onPressed: () => _addTask(routine: true),
            child: const Icon(Icons.repeat),
          ),
          const SizedBox(height: AppSpacing.md),
          FloatingActionButton(
            heroTag: 'task',
            onPressed: () => _addTask(),
            child: const Icon(Icons.add),
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                children: [
                  MLDCard(
                    child: Row(
                      children: [
                        Icon(
                          combo
                              ? Icons.bolt
                              : doneToday >= 2
                                  ? Icons.bolt_outlined
                                  : Icons.checklist,
                          color: combo
                              ? AppColors.warning
                              : AppColors.primary,
                        ),
                        const SizedBox(width: AppSpacing.md),
                        Expanded(
                          child: Text(
                            combo
                                ? 'Discipline combo earned today (+2 DP)'
                                : '$doneToday done today · 2 tasks + 10 focus min = combo +2',
                            style: const TextStyle(fontSize: 13),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  if (open.isEmpty && done.isEmpty)
                    const Center(
                      child: Padding(
                        padding: EdgeInsets.all(AppSpacing.xxl),
                        child: Text(
                          'No tasks yet.\nTap + to add a task, or the repeat '
                          'button for a daily routine.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: AppColors.textSecondary),
                        ),
                      ),
                    ),
                  if (open.isNotEmpty) ...[
                    const MLDSectionHeader(title: 'Open'),
                    const SizedBox(height: AppSpacing.md),
                    ...open.map(_taskTile),
                  ],
                  if (done.isNotEmpty) ...[
                    const SizedBox(height: AppSpacing.xl),
                    MLDSectionHeader(title: 'Done (${done.length})'),
                    const SizedBox(height: AppSpacing.md),
                    ...done.map(_taskTile),
                  ],
                ],
              ),
      ),
    );
  }

  Widget _taskTile(Map<String, dynamic> task) {
    final done = task['completedAt'] != null;
    final routine = task['routine'] == true;
    final subtasks = (task['subtasks'] as List<dynamic>? ?? const [])
        .map((e) => Map<String, dynamic>.from(Map<dynamic, dynamic>.from(e)))
        .toList();

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          children: [
            Row(
              children: [
                if (routine)
                  const Padding(
                    padding: EdgeInsets.only(right: AppSpacing.sm),
                    child: Icon(Icons.repeat, size: 16, color: AppColors.info),
                  ),
                Expanded(
                  child: Text(
                    task['title'].toString(),
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      decoration: done ? TextDecoration.lineThrough : null,
                      color: done ? AppColors.textDisabled : null,
                    ),
                  ),
                ),
                IconButton(
                  icon: Icon(
                    done ? Icons.check_circle : Icons.radio_button_unchecked,
                    color: done ? AppColors.success : AppColors.primary,
                  ),
                  onPressed: _busy ? null : () => _toggleComplete(task),
                ),
                PopupMenuButton<String>(
                  onSelected: (v) {
                    if (v == 'sub') _addSubtask(task);
                    if (v == 'delete') _delete(task);
                  },
                  itemBuilder: (context) => [
                    const PopupMenuItem(
                        value: 'sub', child: Text('Add subtask')),
                    const PopupMenuItem(
                        value: 'delete', child: Text('Delete')),
                  ],
                ),
              ],
            ),
            ...subtasks.map((sub) => InkWell(
                  onTap: () => _toggleSubtask(task, sub),
                  child: Padding(
                    padding: const EdgeInsets.only(left: AppSpacing.xl),
                    child: Row(
                      children: [
                        Icon(
                          sub['done'] == true
                              ? Icons.check_box
                              : Icons.check_box_outline_blank,
                          size: 18,
                          color: sub['done'] == true
                              ? AppColors.success
                              : AppColors.textSecondary,
                        ),
                        const SizedBox(width: AppSpacing.sm),
                        Expanded(
                          child: Text(
                            sub['title'].toString(),
                            style: TextStyle(
                              fontSize: 13,
                              color: sub['done'] == true
                                  ? AppColors.textDisabled
                                  : AppColors.textSecondary,
                              decoration: sub['done'] == true
                                  ? TextDecoration.lineThrough
                                  : null,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                )),
          ],
        ),
      ),
    );
  }
}
