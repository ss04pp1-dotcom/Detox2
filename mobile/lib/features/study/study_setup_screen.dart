import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
// v2.5.5 audit fix: unused import removed (data/models.dart — nothing in
// this file names a model class; the analyzer had flagged it since ship).
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';
import '../session/activation_screen.dart';

/// Study Mode setup (UI/UX §16): duration -> app categories -> allowlist ->
/// START STUDY. The start button is deliberately consequential (§85).
class StudySetupScreen extends StatefulWidget {
  const StudySetupScreen({super.key, this.embedded = false});

  /// When true, rendered inside the Focus tab (no Scaffold/AppBar).
  final bool embedded;

  @override
  State<StudySetupScreen> createState() => _StudySetupScreenState();
}

/// Embedded variant used by the Focus tab.
class StudySetupScreenRef extends StatelessWidget {
  const StudySetupScreenRef({super.key});

  @override
  Widget build(BuildContext context) => const StudySetupScreen(embedded: true);
}

class _StudySetupScreenState extends State<StudySetupScreen> {
  int _duration = AppConstants.defaultStudyMinutes;
  final Set<String> _blockedCategories = {'social', 'games', 'shorts', 'entertainment'};
  final Set<String> _allowed = <String>{};

  // Subjects (v2.5 r9.4): optional label for the session + today's time.
  String _subject = '';
  List<String> _subjects = const [];
  List<Map<String, dynamic>> _today = const [];

  static const _durations = [25, 45, 60, 90, 120];

  @override
  void initState() {
    super.initState();
    _loadSubjects();
  }

  Future<void> _loadSubjects() async {
    final data = await NativeBridge.instance.getStudySubjects();
    if (!mounted) return;
    setState(() {
      _subjects = (data['subjects'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList();
      _today = (data['today'] as List<dynamic>? ?? const [])
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      if (_subject.isNotEmpty && !_subjects.contains(_subject)) _subject = '';
    });
  }

  Future<void> _addSubject() async {
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New subject'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 24,
          decoration: const InputDecoration(hintText: 'e.g. Physics'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, controller.text),
            child: const Text('Add'),
          ),
        ],
      ),
    );
    // v2.5.5 audit fix: dialog TextEditingControllers were never disposed.
    controller.dispose();
    if (name == null || name.trim().isEmpty || !mounted) return;
    // v2.5.5 audit fix: send the TRIMMED name — the stored subject used to
    // keep trailing spaces while `_subject = name.trim()`, so the typed
    // subject never highlighted its own chip afterwards.
    final error = await NativeBridge.instance.addStudySubject(name.trim());
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error)));
      return;
    }
    setState(() => _subject = name.trim());
    await _loadSubjects();
  }

  Future<void> _removeSubject(String name) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete "$name"?'),
        content: const Text('Past study time for this subject is kept.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await NativeBridge.instance.removeStudySubject(name);
    if (!mounted) return;
    if (_subject == name) setState(() => _subject = '');
    await _loadSubjects();
  }

  String _fmtSeconds(int s) {
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    if (h > 0) return '${h}h ${m}m';
    return '${m}m';
  }

  static const _categories = [
    ('social', 'Social', Icons.people_outline),
    ('games', 'Games', Icons.sports_esports_outlined),
    ('shorts', 'Short Videos', Icons.smart_display_outlined),
    ('entertainment', 'Entertainment', Icons.movie_outlined),
  ];

  /// Allowlist groups (v2.5 r9.5): one toggle covers the equivalent app on
  /// every common OEM — Social Sentry ships default whitelists of dialers,
  /// cameras, calculators, AI assistants, notes, scanners, file managers and
  /// meeting apps. Package names that are not installed are simply ignored.
  static const _allowOptions = <(String, String, List<String>)>[
    ('phone', 'Phone & Contacts', [
      'com.android.dialer', 'com.google.android.dialer',
      'com.samsung.android.dialer', 'com.android.contacts',
      'com.google.android.contacts', 'com.samsung.android.app.contacts',
    ]),
    ('camera', 'Camera', [
      'com.android.camera', 'com.android.camera2',
      'com.google.android.GoogleCamera', 'com.sec.android.app.camera',
      'com.oppo.camera', 'com.oplus.camera', 'com.huawei.camera',
      'com.motorola.camera3',
    ]),
    ('gallery', 'Gallery & Photos', [
      'com.google.android.apps.photos', 'com.sec.android.gallery3d',
      'com.miui.gallery', 'com.coloros.gallery3d', 'com.oplus.gallery',
      'com.android.gallery3d', 'com.vivo.gallery',
    ]),
    ('calculator', 'Calculator', [
      'com.google.android.calculator', 'com.android.calculator2',
      'com.sec.android.app.popupcalculator', 'com.miui.calculator',
      'com.coloros.calculator', 'com.oneplus.calculator',
      'com.bbk.calculator',
    ]),
    ('notes', 'Notes', [
      'com.google.android.keep', 'com.samsung.android.app.notes',
      'com.miui.notes', 'com.coloros.note', 'com.microsoft.office.onenote',
      'com.evernote', 'md.obsidian',
    ]),
    ('ai', 'AI assistants', [
      'com.openai.chatgpt', 'com.google.android.apps.bard',
      'com.anthropic.claude', 'com.microsoft.copilot',
      'ai.perplexity.app.android', 'com.deepseek.chat',
    ]),
    ('docs', 'Docs & PDF', [
      'com.google.android.apps.docs',
      'com.google.android.apps.docs.editors.docs',
      'com.google.android.apps.docs.editors.sheets',
      'com.google.android.apps.docs.editors.slides', 'com.adobe.reader',
      'com.microsoft.office.word', 'com.microsoft.office.excel',
      'com.microsoft.office.powerpoint', 'cn.wps.moffice_eng',
    ]),
    ('scanner', 'Scanner', [
      'com.adobe.scan.android', 'com.intsig.camscanner',
      'com.microsoft.office.officelens',
    ]),
    ('meet', 'Online classes', [
      'us.zoom.videomeetings', 'com.google.android.apps.meetings',
      'com.google.android.apps.tachyon', 'com.microsoft.teams',
    ]),
    ('files', 'Files', [
      'com.google.android.apps.nbu.files', 'com.android.documentsui',
      'com.google.android.documentsui', 'com.mi.android.globalFileexplorer',
      'com.sec.android.app.myfiles', 'com.coloros.filemanager',
    ]),
    ('translate', 'Translate', ['com.google.android.apps.translate']),
    ('browser', 'Chrome', ['com.android.chrome']),
  ];

  bool get _valid => _duration > 0 && _blockedCategories.isNotEmpty;

  void _start() {
    Navigator.of(context).pushNamed(
      AppConstants.routeActivation,
      arguments: ActivationArgs(
        mode: 'STUDY',
        durationMinutes: _duration,
        strictness: 'STRICT',
        blockedCategories: _blockedCategories.toList(),
        allowedPackages: [
          for (final (id, _, pkgs) in _allowOptions)
            if (_allowed.contains(id)) ...pkgs,
        ],
        subject: _subject,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final body = SingleChildScrollView(
      padding: AppSpacing.screenH.copyWith(
        top: widget.embedded ? AppSpacing.xl : AppSpacing.xl,
        bottom: AppSpacing.xxxl,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.embedded) ...[
            Text('STUDY MODE', style: AppTypography.heading()),
            const SizedBox(height: AppSpacing.sm),
            Text('Focus session with the apps you choose.',
                style: AppTypography.caption()),
            const SizedBox(height: AppSpacing.xxl),
          ],

          const MLDSectionHeader(title: 'DURATION'),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              for (final d in _durations)
                _chip(
                  label: d >= 60 ? '${d ~/ 60}h${d % 60 > 0 ? ' ${d % 60}m' : ''}' : '$d min',
                  selected: _duration == d,
                  onTap: () => setState(() => _duration = d),
                ),
              _chip(
                label: 'Custom',
                selected: !_durations.contains(_duration),
                onTap: () async {
                  final minutes = await _askCustomDuration(context);
                  if (minutes != null && minutes >= 10 && minutes <= 480) {
                    setState(() => _duration = minutes);
                  }
                },
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.xxl),

          const MLDSectionHeader(title: 'SUBJECT (OPTIONAL)'),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              _chip(
                label: 'None',
                selected: _subject.isEmpty,
                onTap: () => setState(() => _subject = ''),
              ),
              for (final name in _subjects)
                GestureDetector(
                  onLongPress: () => _removeSubject(name),
                  child: _chip(
                    label: name,
                    selected: _subject == name,
                    onTap: () => setState(() => _subject = name),
                  ),
                ),
              _chip(label: '+ Add', selected: false, onTap: _addSubject),
            ],
          ),
          if (_subjects.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.sm),
            Text('Long-press a subject to delete it.',
                style: AppTypography.caption()),
          ],
          if (_today.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.md),
            Text('TODAY BY SUBJECT', style: AppTypography.caption()),
            const SizedBox(height: AppSpacing.xs),
            for (final row in _today)
              Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(
                  // v2.5.5 audit fix: defensive cast — a missing/null `seconds`
                  // used to throw on `(row['seconds'] as num)`.
                  '${row['name']} · ${_fmtSeconds(((row['seconds'] as num?) ?? 0).toInt())}',
                  style: AppTypography.body(),
                ),
              ),
          ],
          const SizedBox(height: AppSpacing.xxl),

          const MLDSectionHeader(title: 'APPS TO BLOCK'),
          for (final (key, label, icon) in _categories)
            _categoryTile(
              icon: icon,
              label: label,
              blocked: _blockedCategories.contains(key),
              onToggle: (v) => setState(() => v ? _blockedCategories.add(key) : _blockedCategories.remove(key)),
            ),
          const SizedBox(height: AppSpacing.xxl),

          const MLDSectionHeader(title: 'APPS YOU NEED (ALLOWLIST)'),
          for (final (id, label, _) in _allowOptions)
              _allowTile(
                label: label,
                allowed: _allowed.contains(id),
                onToggle: (v) => setState(() => v ? _allowed.add(id) : _allowed.remove(id)),
              ),
          const SizedBox(height: AppSpacing.xxl),

          const MLDWarningBanner(
            message:
                'Once the session starts, these rules are locked until it legitimately ends. Emergency calling always remains available.',
            tone: MLDBannerTone.info,
          ),
          const SizedBox(height: AppSpacing.xxl),

          MLDButton(
            label: 'START STUDY',
            icon: Icons.menu_book_outlined,
            onPressed: _valid ? _start : null,
          ),
        ],
      ),
    );

    if (widget.embedded) return body;
    return Scaffold(
      appBar: AppBar(title: const Text('Study Mode')),
      body: SafeArea(child: body),
    );
  }

  Widget _chip({required String label, required bool selected, required VoidCallback onTap}) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl, vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary.withValues(alpha: 0.15) : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: selected ? AppColors.primary : AppColors.edge, width: selected ? 1.6 : 1),
        ),
        child: Text(
          label,
          style: AppTypography.body(
            color: selected ? AppColors.primary : AppColors.textSecondary,
            weight: FontWeight.w700,
          ),
        ),
      ),
    );
  }

  Widget _categoryTile({
    required IconData icon,
    required String label,
    required bool blocked,
    required ValueChanged<bool> onToggle,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: InkWell(
        onTap: () => onToggle(!blocked),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppRadii.sm),
            border: Border.all(color: AppColors.edge),
          ),
          child: Row(
            children: [
              Icon(icon, color: AppColors.textSecondary, size: 22),
              const SizedBox(width: AppSpacing.lg),
              Expanded(child: Text(label, style: AppTypography.body(weight: FontWeight.w600))),
              Icon(
                blocked ? Icons.check_box : Icons.check_box_outline_blank,
                color: blocked ? AppColors.danger : AppColors.textSecondary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _allowTile({
    required String label,
    required bool allowed,
    required ValueChanged<bool> onToggle,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: InkWell(
        onTap: () => onToggle(!allowed),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppRadii.sm),
            border: Border.all(color: allowed ? AppColors.success : AppColors.edge),
          ),
          child: Row(
            children: [
              Expanded(child: Text(label, style: AppTypography.body(weight: FontWeight.w600))),
              Icon(
                allowed ? Icons.check_circle : Icons.circle_outlined,
                color: allowed ? AppColors.success : AppColors.textSecondary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<int?> _askCustomDuration(BuildContext context) {
    final controller = TextEditingController(text: '$_duration');
    return showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Custom duration (minutes)'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(hintText: '10 – 480'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(context, int.tryParse(controller.text)),
            child: const Text('Set'),
          ),
        ],
      ),
    ).whenComplete(controller.dispose); // v2.5.5 audit fix: leak
  }
}

/// Detox setup (UI/UX §17) — more serious, strictness selector, then a
/// confirmation checkpoint before activation.
class DetoxSetupScreen extends StatefulWidget {
  const DetoxSetupScreen({super.key, this.embedded = false});

  final bool embedded;

  @override
  State<DetoxSetupScreen> createState() => _DetoxSetupScreenState();
}

class DetoxSetupScreenRef extends StatelessWidget {
  const DetoxSetupScreenRef({super.key});

  @override
  Widget build(BuildContext context) => const DetoxSetupScreen(embedded: true);
}

class _DetoxSetupScreenState extends State<DetoxSetupScreen> {
  int _duration = AppConstants.defaultDetoxMinutes;
  String _strictness = 'MAXLEVEL';

  static const _durations = [30, 60, 120, 240, 480];

  bool get _valid => _duration > 0;

  void _continueToConfirm() {
    Navigator.of(context).pushNamed(
      AppConstants.routeDetoxConfirm,
      arguments: DetoxConfirmArgs(
        durationMinutes: _duration,
        strictness: _strictness,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final body = SingleChildScrollView(
      padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.embedded) ...[
            Text('DETOX MODE', style: AppTypography.heading()),
            const SizedBox(height: AppSpacing.sm),
            Text('Disconnect from distractions.',
                style: AppTypography.caption()),
            const SizedBox(height: AppSpacing.xxl),
          ] else ...[
            Text('Disconnect from distractions.',
                style: AppTypography.caption()),
            const SizedBox(height: AppSpacing.xxl),
          ],

          const MLDSectionHeader(title: 'DURATION'),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              for (final d in _durations)
                _durationChip(d),
              _customChip(),
            ],
          ),
          const SizedBox(height: AppSpacing.xxl),

          const MLDSectionHeader(title: 'STRICTNESS'),
          _strictnessTile(
            value: 'BALANCED',
            title: 'Balanced',
            description: 'Unknown apps stay usable. Core distractions blocked.',
            selected: _strictness == 'BALANCED',
          ),
          const SizedBox(height: AppSpacing.md),
          _strictnessTile(
            value: 'STRICT',
            title: 'Strict',
            description: 'Unknown apps restricted. Allowlist only.',
            selected: _strictness == 'STRICT',
          ),
          const SizedBox(height: AppSpacing.md),
          _strictnessTile(
            value: 'MAXLEVEL',
            title: 'MAXLEVEL',
            description: 'Everything restricted except your allowlist, phone and emergency.',
            selected: _strictness == 'MAXLEVEL',
            accent: AppColors.danger,
          ),
          const SizedBox(height: AppSpacing.xxl),

          MLDButton(
            label: 'CONTINUE',
            icon: Icons.spa_outlined,
            onPressed: _valid ? _continueToConfirm : null,
          ),
        ],
      ),
    );

    if (widget.embedded) return body;
    return Scaffold(
      appBar: AppBar(title: const Text('Detox Mode')),
      body: SafeArea(child: body),
    );
  }

  Widget _durationChip(int d) {
    final selected = _duration == d;
    final label = d >= 60 ? (d % 60 == 0 ? '${d ~/ 60} hour${d >= 120 ? 's' : ''}' : '${d ~/ 60}h ${d % 60}m') : '$d min';
    return GestureDetector(
      onTap: () => setState(() => _duration = d),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl, vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary.withValues(alpha: 0.15) : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: selected ? AppColors.primary : AppColors.edge, width: selected ? 1.6 : 1),
        ),
        child: Text(label,
            style: AppTypography.body(
              color: selected ? AppColors.primary : AppColors.textSecondary,
              weight: FontWeight.w700,
            )),
      ),
    );
  }

  Widget _customChip() {
    final selected = !_durations.contains(_duration);
    return GestureDetector(
      onTap: () async {
        final minutes = await _askCustomDuration(context);
        if (minutes != null && minutes >= 30 && minutes <= 1440) {
          setState(() => _duration = minutes);
        }
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl, vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary.withValues(alpha: 0.15) : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: selected ? AppColors.primary : AppColors.edge, width: selected ? 1.6 : 1),
        ),
        child: Text(
          !_durations.contains(_duration) ? '$_duration min' : 'Custom',
          style: AppTypography.body(
            color: selected ? AppColors.primary : AppColors.textSecondary,
            weight: FontWeight.w700,
          ),
        ),
      ),
    );
  }

  Widget _strictnessTile({
    required String value,
    required String title,
    required String description,
    required bool selected,
    Color accent = AppColors.primary,
  }) {
    return InkWell(
      onTap: () => setState(() => _strictness = value),
      borderRadius: BorderRadius.circular(AppRadii.sm),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: selected ? accent.withValues(alpha: 0.1) : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: selected ? accent : AppColors.edge, width: selected ? 1.6 : 1),
        ),
        child: Row(
          children: [
            Icon(
              selected ? Icons.radio_button_checked : Icons.radio_button_off,
              color: selected ? accent : AppColors.textSecondary,
            ),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: AppTypography.body(weight: FontWeight.w800)),
                  Text(description, style: AppTypography.caption()),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<int?> _askCustomDuration(BuildContext context) {
    final controller = TextEditingController(text: '$_duration');
    return showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Custom duration (minutes)'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(hintText: '30 – 1440'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(context, int.tryParse(controller.text)),
            child: const Text('Set'),
          ),
        ],
      ),
    ).whenComplete(controller.dispose); // v2.5.5 audit fix: leak
  }
}

/// Arguments passed to the confirmation screen.
class DetoxConfirmArgs {
  const DetoxConfirmArgs({required this.durationMinutes, required this.strictness});

  final int durationMinutes;
  final String strictness;
}
