import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// TasksScreen — SS task economy & daily routines.
/// Enhanced with instant local cache fallback, safe deserialization,
/// and a modern, high-contrast Material 3 UI.
class TasksScreen extends StatefulWidget {
  const TasksScreen({super.key});

  @override
  State<TasksScreen> createState() => _TasksScreenState();
}

class _TasksScreenState extends State<TasksScreen> {
  static const String _kCacheKey = 'mld_cached_tasks_v2';

  bool _loading = true;
  bool _busy = false;
  List<Map<String, dynamic>> _tasks = [];
  Map<String, dynamic> _status = {};
  String _selectedFilter = 'all'; // all, active, routines, completed

  @override
  void initState() {
    super.initState();
    _loadFromCacheThenNative();
  }

  /// Load cached tasks first for 0ms render, then refresh from native bridge.
  Future<void> _loadFromCacheThenNative() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cachedRaw = prefs.getString(_kCacheKey);
      if (cachedRaw != null && cachedRaw.isNotEmpty) {
        final decoded = jsonDecode(cachedRaw);
        if (decoded is Map) {
          final list = (decoded['tasks'] as List<dynamic>? ?? [])
              .map((e) => _deepCast(e))
              .toList();
          if (mounted && list.isNotEmpty) {
            setState(() {
              _tasks = list;
              _status = _deepCast(decoded['status']);
              _loading = false;
            });
          }
        }
      }
    } catch (_) {
      // Safe fallback
    }

    await _loadNative();
  }

  Map<String, dynamic> _deepCast(dynamic item) {
    if (item is! Map) return {};
    final result = <String, dynamic>{};
    for (final entry in item.entries) {
      final key = entry.key.toString();
      final val = entry.value;
      if (val is Map) {
        result[key] = _deepCast(val);
      } else if (val is List) {
        result[key] = val.map((e) => e is Map ? _deepCast(e) : e).toList();
      } else {
        result[key] = val;
      }
    }
    return result;
  }

  Future<void> _loadNative() async {
    try {
      final data = await NativeBridge.instance
          .getTasks()
          .timeout(const Duration(seconds: 4));

      if (data != null && mounted) {
        final rawTasks = data['tasks'];
        final rawStatus = data['status'];

        final list = <Map<String, dynamic>>[];
        if (rawTasks is List) {
          for (final item in rawTasks) {
            if (item != null) list.add(_deepCast(item));
          }
        }

        final status = _deepCast(rawStatus);

        setState(() {
          _tasks = list;
          _status = status;
        });

        // Update local cache
        try {
          final prefs = await SharedPreferences.getInstance();
          await prefs.setString(
            _kCacheKey,
            jsonEncode({'tasks': list, 'status': status}),
          );
        } catch (_) {}
      }
    } catch (e) {
      debugPrint('NativeBridge getTasks error: $e');
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _addTask({
    required String title,
    String note = '',
    int priority = 2,
    bool routine = false,
  }) async {
    if (title.trim().isEmpty) return;
    setState(() => _busy = true);
    try {
      await NativeBridge.instance.addTask(
        title: title.trim(),
        note: note.trim(),
        routine: routine,
        priority: priority,
      );
    } catch (e) {
      debugPrint('Failed to add task: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
      _loadNative();
    }
  }

  Future<void> _toggleComplete(Map<String, dynamic> task) async {
    final id = task['id']?.toString() ?? '';
    if (id.isEmpty) return;
    final done = task['completedAt'] != null;

    // Optimistic UI update
    setState(() {
      task['completedAt'] = done ? null : DateTime.now().millisecondsSinceEpoch;
    });

    try {
      if (done) {
        await NativeBridge.instance.reopenTask(taskId: id);
      } else {
        final ok = await NativeBridge.instance.completeTask(taskId: id);
        if (ok && mounted) {
          final isRoutine = task['routine'] == true;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              behavior: SnackBarBehavior.floating,
              backgroundColor: AppColors.surface,
              content: Row(
                children: [
                  const Icon(Icons.check_circle, color: AppColors.success, size: 20),
                  const SizedBox(width: AppSpacing.sm),
                  Text(
                    isRoutine ? 'Routine complete! +4 DP' : 'Task complete! +8 DP',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                ],
              ),
              duration: const Duration(seconds: 2),
            ),
          );
        }
      }
    } catch (_) {}

    _loadNative();
  }

  Future<void> _addSubtask(Map<String, dynamic> task) async {
    final controller = TextEditingController();
    final title = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Add Subtask'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: 'e.g. Read first 5 pages',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('Add'),
          ),
        ],
      ),
    );
    controller.dispose();

    if (title != null && title.isNotEmpty) {
      await NativeBridge.instance.addSubtask(
        taskId: task['id'] as String,
        title: title,
      );
      _loadNative();
    }
  }

  Future<void> _toggleSubtask(
    Map<String, dynamic> task,
    Map<String, dynamic> sub,
  ) async {
    final taskId = task['id']?.toString() ?? '';
    final subId = sub['id']?.toString() ?? '';
    if (taskId.isEmpty || subId.isEmpty) return;

    // Optimistic toggle
    setState(() {
      sub['done'] = !(sub['done'] == true);
    });

    await NativeBridge.instance.toggleSubtask(
      taskId: taskId,
      subtaskId: subId,
    );
    _loadNative();
  }

  Future<void> _delete(Map<String, dynamic> task) async {
    final id = task['id']?.toString() ?? '';
    if (id.isEmpty) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete Task?'),
        content: Text('Are you sure you want to delete "${task['title']}"?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await NativeBridge.instance.deleteTask(taskId: id);
      _loadNative();
    }
  }

  void _showCreateTaskSheet({bool routine = false}) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.hero)),
      ),
      builder: (context) => _CreateTaskSheet(
        initialRoutine: routine,
        onAdd: (title, note, priority, isRoutine) {
          _addTask(
            title: title,
            note: note,
            priority: priority,
            routine: isRoutine,
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final allTasks = _tasks;
    final openTasks = allTasks.where((t) => t['completedAt'] == null).toList();
    final doneTasks = allTasks.where((t) => t['completedAt'] != null).toList();
    final routineTasks = allTasks.where((t) => t['routine'] == true).toList();

    List<Map<String, dynamic>> displayedTasks;
    switch (_selectedFilter) {
      case 'active':
        displayedTasks = openTasks;
        break;
      case 'routines':
        displayedTasks = routineTasks;
        break;
      case 'completed':
        displayedTasks = doneTasks;
        break;
      case 'all':
      default:
        displayedTasks = allTasks;
        break;
    }

    final doneToday = (_status['doneToday'] as num?)?.toInt() ?? 0;
    final comboEarned = _status['comboEarnedToday'] == true;
    final totalCount = allTasks.length;
    final doneCount = doneTasks.length;
    final progress = totalCount > 0 ? (doneCount / totalCount).clamp(0.0, 1.0) : 0.0;

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: const MLDAppBarTitle(title: 'Discipline Tasks'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: _busy ? null : _loadNative,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        elevation: 6,
        icon: const Icon(Icons.add_task),
        label: const Text(
          'New Task',
          style: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 0.3),
        ),
        onPressed: () => _showCreateTaskSheet(),
      ),
      body: SafeArea(
        child: _loading && _tasks.isEmpty
            ? const Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(),
                    SizedBox(height: AppSpacing.md),
                    Text(
                      'Loading tasks...',
                      style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
                    ),
                  ],
                ),
              )
            : RefreshIndicator(
                onRefresh: _loadNative,
                child: ListView(
                  padding: AppSpacing.screenH.copyWith(
                    top: AppSpacing.md,
                    bottom: AppSpacing.xxxl + 60,
                  ),
                  children: [
                    // Daily Discipline Progress Hero Card
                    _buildDisciplineComboCard(doneToday, comboEarned, progress, doneCount, totalCount),
                    const SizedBox(height: AppSpacing.lg),

                    // Filter Chips Bar
                    _buildFilterChips(totalCount, openTasks.length, routineTasks.length, doneCount),
                    const SizedBox(height: AppSpacing.lg),

                    // Task List or Empty State
                    if (displayedTasks.isEmpty)
                      _buildEmptyState()
                    else
                      ...displayedTasks.map(_buildTaskCard),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildDisciplineComboCard(
    int doneToday,
    bool comboEarned,
    double progress,
    int doneCount,
    int totalCount,
  ) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            AppColors.surface,
            comboEarned
                ? AppColors.primary.withValues(alpha: 0.15)
                : AppColors.elevated,
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(
          color: comboEarned
              ? AppColors.warning.withValues(alpha: 0.5)
              : AppColors.edge,
        ),
      ),
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(AppSpacing.sm),
                decoration: BoxDecoration(
                  color: comboEarned
                      ? AppColors.warning.withValues(alpha: 0.2)
                      : AppColors.primary.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  comboEarned ? Icons.bolt : Icons.checklist_rtl,
                  color: comboEarned ? AppColors.warning : AppColors.primary,
                  size: 22,
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      comboEarned
                          ? '⚡ Discipline Combo Active!'
                          : 'Daily Discipline Goal',
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      comboEarned
                          ? '+2 DP awarded today for staying focused'
                          : '$doneToday done today · 2 tasks + 10m focus = +2 DP',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              if (comboEarned)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: AppColors.warning.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Text(
                    '+2 DP',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      color: AppColors.warning,
                    ),
                  ),
                ),
            ],
          ),
          if (totalCount > 0) ...[
            const SizedBox(height: AppSpacing.md),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: progress,
                minHeight: 6,
                backgroundColor: AppColors.elevated,
                valueColor: AlwaysStoppedAnimation<Color>(
                  comboEarned ? AppColors.warning : AppColors.primary,
                ),
              ),
            ),
            const SizedBox(height: AppSpacing.xs),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '$doneCount of $totalCount completed',
                  style: const TextStyle(
                    fontSize: 11,
                    color: AppColors.textSecondary,
                  ),
                ),
                Text(
                  '${(progress * 100).toInt()}%',
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textSecondary,
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildFilterChips(int total, int active, int routines, int done) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _filterChip('all', 'All ($total)'),
          const SizedBox(width: AppSpacing.sm),
          _filterChip('active', 'Active ($active)'),
          const SizedBox(width: AppSpacing.sm),
          _filterChip('routines', 'Routines ($routines)'),
          const SizedBox(width: AppSpacing.sm),
          _filterChip('completed', 'Done ($done)'),
        ],
      ),
    );
  }

  Widget _filterChip(String id, String label) {
    final selected = _selectedFilter == id;
    return GestureDetector(
      onTap: () => setState(() => _selectedFilter = id),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: selected
              ? AppColors.primary
              : AppColors.surface.withValues(alpha: 0.9),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: selected ? AppColors.primary : AppColors.edge,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: selected ? Colors.white : AppColors.textSecondary,
          ),
        ),
      ),
    );
  }

  Widget _buildTaskCard(Map<String, dynamic> task) {
    final done = task['completedAt'] != null;
    final isRoutine = task['routine'] == true;
    final priority = (task['priority'] as num?)?.toInt() ?? 2;
    final title = task['title']?.toString() ?? 'Untitled';
    final note = task['note']?.toString() ?? '';
    final subtasks = (task['subtasks'] as List<dynamic>? ?? [])
        .map((e) => _deepCast(e))
        .toList();

    final completedSubtasks =
        subtasks.where((s) => s['done'] == true).length;

    Color priorityColor;
    String priorityLabel;
    switch (priority) {
      case 3:
        priorityColor = AppColors.danger;
        priorityLabel = 'High';
        break;
      case 1:
        priorityColor = AppColors.info;
        priorityLabel = 'Low';
        break;
      default:
        priorityColor = AppColors.warning;
        priorityLabel = 'Med';
        break;
    }

    return Container(
      margin: const EdgeInsets.only(bottom: AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(
          color: done
              ? AppColors.edge.withValues(alpha: 0.4)
              : AppColors.edge,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.all(AppSpacing.md),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Custom Checkbox
                GestureDetector(
                  onTap: _busy ? null : () => _toggleComplete(task),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.only(top: 2, right: AppSpacing.md),
                    width: 26,
                    height: 26,
                    decoration: BoxDecoration(
                      color: done
                          ? AppColors.success
                          : AppColors.elevated,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: done ? AppColors.success : AppColors.primary,
                        width: 1.5,
                      ),
                    ),
                    child: done
                        ? const Icon(Icons.check, size: 16, color: Colors.white)
                        : null,
                  ),
                ),

                // Title & Details
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          if (isRoutine)
                            Container(
                              margin: const EdgeInsets.only(right: 6),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                                vertical: 2,
                              ),
                              decoration: BoxDecoration(
                                color: AppColors.primaryDim.withValues(alpha: 0.2),
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.repeat, size: 10, color: AppColors.info),
                                  SizedBox(width: 3),
                                  Text(
                                    'DAILY',
                                    style: TextStyle(
                                      fontSize: 9,
                                      fontWeight: FontWeight.bold,
                                      color: AppColors.info,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: priorityColor.withValues(alpha: 0.15),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              priorityLabel,
                              style: TextStyle(
                                fontSize: 9,
                                fontWeight: FontWeight.bold,
                                color: priorityColor,
                              ),
                            ),
                          ),
                          const Spacer(),
                          Text(
                            isRoutine ? '+4 DP' : '+8 DP',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                              color: done
                                  ? AppColors.textDisabled
                                  : AppColors.primary,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        title,
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          decoration: done ? TextDecoration.lineThrough : null,
                          color: done
                              ? AppColors.textDisabled
                              : AppColors.textPrimary,
                        ),
                      ),
                      if (note.isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(
                          note,
                          style: TextStyle(
                            fontSize: 12,
                            color: done
                                ? AppColors.textDisabled
                                : AppColors.textSecondary,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),

                // Options Menu
                PopupMenuButton<String>(
                  icon: const Icon(
                    Icons.more_vert,
                    size: 20,
                    color: AppColors.textSecondary,
                  ),
                  onSelected: (v) {
                    if (v == 'sub') _addSubtask(task);
                    if (v == 'delete') _delete(task);
                  },
                  itemBuilder: (context) => [
                    const PopupMenuItem(
                      value: 'sub',
                      child: Row(
                        children: [
                          Icon(Icons.subdirectory_arrow_right, size: 16),
                          SizedBox(width: 8),
                          Text('Add Subtask'),
                        ],
                      ),
                    ),
                    const PopupMenuItem(
                      value: 'delete',
                      child: Row(
                        children: [
                          Icon(Icons.delete_outline, size: 16, color: AppColors.danger),
                          SizedBox(width: 8),
                          Text('Delete', style: TextStyle(color: AppColors.danger)),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Subtasks Section
          if (subtasks.isNotEmpty) ...[
            const Divider(height: 1, color: AppColors.edge),
            Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.md,
                vertical: AppSpacing.xs,
              ),
              child: Row(
                children: [
                  const Icon(Icons.format_list_bulleted, size: 14, color: AppColors.textSecondary),
                  const SizedBox(width: 6),
                  Text(
                    'Subtasks ($completedSubtasks/${subtasks.length})',
                    style: const TextStyle(fontSize: 11, color: AppColors.textSecondary),
                  ),
                ],
              ),
            ),
            ...subtasks.map((sub) {
              final subDone = sub['done'] == true;
              return InkWell(
                onTap: () => _toggleSubtask(task, sub),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.lg,
                    vertical: AppSpacing.xs,
                  ),
                  child: Row(
                    children: [
                      Icon(
                        subDone ? Icons.check_circle : Icons.radio_button_unchecked,
                        size: 16,
                        color: subDone ? AppColors.success : AppColors.textDisabled,
                      ),
                      const SizedBox(width: AppSpacing.sm),
                      Expanded(
                        child: Text(
                          sub['title']?.toString() ?? '',
                          style: TextStyle(
                            fontSize: 13,
                            decoration: subDone ? TextDecoration.lineThrough : null,
                            color: subDone
                                ? AppColors.textDisabled
                                : AppColors.textPrimary,
                          ),
                        ),
                      ),
                      Text(
                        '+2 DP',
                        style: TextStyle(
                          fontSize: 10,
                          color: subDone ? AppColors.textDisabled : AppColors.info,
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }),
            const SizedBox(height: AppSpacing.xs),
          ],
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.xxl),
      decoration: BoxDecoration(
        color: AppColors.surface.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: AppColors.primary.withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: const Icon(
              Icons.task_alt,
              size: 40,
              color: AppColors.primary,
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          const Text(
            'No tasks in this list',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: AppSpacing.xs),
          const Text(
            'Break down your goals into actionable daily steps. Tap below to create your first discipline task or routine.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
          ),
          const SizedBox(height: AppSpacing.xl),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              OutlinedButton.icon(
                icon: const Icon(Icons.repeat, size: 18),
                label: const Text('Add Routine'),
                onPressed: () => _showCreateTaskSheet(routine: true),
              ),
              const SizedBox(width: AppSpacing.md),
              FilledButton.icon(
                icon: const Icon(Icons.add, size: 18),
                label: const Text('Add Task'),
                onPressed: () => _showCreateTaskSheet(),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Modern Bottom Sheet to quickly add tasks with presets
class _CreateTaskSheet extends StatefulWidget {
  final bool initialRoutine;
  final Function(String title, String note, int priority, bool isRoutine) onAdd;

  const _CreateTaskSheet({
    required this.initialRoutine,
    required this.onAdd,
  });

  @override
  State<_CreateTaskSheet> createState() => _CreateTaskSheetState();
}

class _CreateTaskSheetState extends State<_CreateTaskSheet> {
  late final TextEditingController _titleController = TextEditingController();
  late final TextEditingController _noteController = TextEditingController();
  late bool _isRoutine = widget.initialRoutine;
  int _priority = 2; // 1 = Low, 2 = Medium, 3 = High

  final List<String> _presets = [
    'Deep Work 45m',
    'Morning Exercise',
    'Read 10 Pages',
    'No Phone 1 hr',
    'Hydrate 2L',
  ];

  @override
  void dispose() {
    _titleController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  void _submit() {
    final title = _titleController.text.trim();
    if (title.isEmpty) return;
    widget.onAdd(title, _noteController.text.trim(), _priority, _isRoutine);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: AppSpacing.lg,
        right: AppSpacing.lg,
        top: AppSpacing.lg,
        bottom: MediaQuery.of(context).viewInsets.bottom + AppSpacing.xl,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                _isRoutine ? Icons.repeat : Icons.add_task,
                color: AppColors.primary,
              ),
              const SizedBox(width: AppSpacing.sm),
              Text(
                _isRoutine ? 'Create Daily Routine' : 'Create Task',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: AppColors.textPrimary,
                ),
              ),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.close),
                onPressed: () => Navigator.pop(context),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),

          // Quick Presets
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: _presets.map((preset) {
                return Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ActionChip(
                    label: Text(preset, style: const TextStyle(fontSize: 11)),
                    backgroundColor: AppColors.elevated,
                    onPressed: () {
                      _titleController.text = preset;
                    },
                  ),
                );
              }).toList(),
            ),
          ),
          const SizedBox(height: AppSpacing.md),

          // Title
          TextField(
            controller: _titleController,
            autofocus: true,
            decoration: const InputDecoration(
              labelText: 'Task Title',
              hintText: 'e.g. Complete chapter 4 physics',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: AppSpacing.md),

          // Note
          TextField(
            controller: _noteController,
            decoration: const InputDecoration(
              labelText: 'Notes (optional)',
              hintText: 'Additional details or links',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: AppSpacing.md),

          // Priority Selector
          Row(
            children: [
              const Text(
                'Priority:',
                style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
              ),
              const SizedBox(width: AppSpacing.md),
              _priorityButton(1, 'Low', AppColors.info),
              const SizedBox(width: AppSpacing.sm),
              _priorityButton(2, 'Medium', AppColors.warning),
              const SizedBox(width: AppSpacing.sm),
              _priorityButton(3, 'High', AppColors.danger),
            ],
          ),
          const SizedBox(height: AppSpacing.md),

          // Routine Toggle
          SwitchListTile(
            title: const Text('Daily Routine', style: TextStyle(fontSize: 14)),
            subtitle: const Text(
              'Resets every day to build consistent habits',
              style: TextStyle(fontSize: 12),
            ),
            value: _isRoutine,
            onChanged: (val) => setState(() => _isRoutine = val),
            contentPadding: EdgeInsets.zero,
          ),
          const SizedBox(height: AppSpacing.lg),

          // Submit Button
          SizedBox(
            width: double.infinity,
            height: AppSizes.ctaHeight,
            child: FilledButton(
              onPressed: _submit,
              child: Text(
                _isRoutine ? 'Save Routine (+4 DP)' : 'Save Task (+8 DP)',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _priorityButton(int level, String label, Color color) {
    final selected = _priority == level;
    return GestureDetector(
      onTap: () => setState(() => _priority = level),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: selected ? color : color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.bold,
            color: selected ? Colors.white : color,
          ),
        ),
      ),
    );
  }
}
