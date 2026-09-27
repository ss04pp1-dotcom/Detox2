import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

/// System Health (v2.2.1) — the on-device debugging surface.
///
/// When a remote tester reports "nothing works", this screen answers the
/// questions a developer would ask first: does the bridge respond, is the
/// state stream alive, is the accessibility component the one Android
/// actually has enabled, do the databases open, and what did the enforcement
/// engine decide recently. One screenshot from here replaces a whole debug
/// session.
class SystemHealthScreen extends StatefulWidget {
  const SystemHealthScreen({super.key});

  @override
  State<SystemHealthScreen> createState() => _SystemHealthScreenState();
}

class _SystemHealthScreenState extends State<SystemHealthScreen> {
  Map<String, dynamic>? _report;
  bool _loading = true;
  DateTime _fetchedAt = DateTime.now();

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final report = await NativeBridge.instance.getSystemReport();
    if (!mounted) return;
    setState(() {
      _report = report;
      _loading = false;
      _fetchedAt = DateTime.now();
    });
  }

  Future<void> _copyReport() async {
    final text = _buildReportText();
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Report copied — paste it in chat')),
    );
  }

  String _buildReportText() {
    final r = _report ?? const {};
    final app = AppStateScope.of(context);
    final buf = StringBuffer()
      ..writeln('MAXLEVEL DETOX — System Health')
      ..writeln('fetched: ${_fetchedAt.toIso8601String()}')
      ..writeln('package: ${r['package']}')
      ..writeln('version: ${r['versionName']} (${r['versionCode']})')
      ..writeln('device: ${r['device']}')
      ..writeln('bridgeResponds: ${_report != null}')
      ..writeln('streamAlive: ${app.bootstrapped} (pushes natively: ${r['streamPushes']})')
      ..writeln('expectedA11y: ${r['expectedA11yComponent']}')
      ..writeln('enabledA11y: ${r['enabledA11yServices']}')
      ..writeln('permissions: ${r['permissions']}')
      ..writeln('dataStoreReadable: ${r['dataStoreReadable']}')
      ..writeln('roomProbe: ${r['roomProbe']}')
      ..writeln('engineStatus: ${r['engineStatus']}')
      ..writeln('guardsEnabled: ${r['guardsEnabled']}')
      ..writeln('sessionActive: ${r['sessionActive']} (${r['sessionStatus']})')
      ..writeln('cageActive: ${r['cageActive']}')
      ..writeln('shortsEnabled: ${r['shortsEnabled']}')
      ..writeln('nativeErrors:');
    final errors = (r['nativeErrors'] as List<dynamic>? ?? const []);
    if (errors.isEmpty) {
      buf.writeln('  (none)');
    } else {
      for (final e in errors) {
        buf.writeln('  $e');
      }
    }
    buf.writeln('recentLog:');
    final log = (r['recentLog'] as List<dynamic>? ?? const []);
    if (log.isEmpty) {
      buf.writeln('  (empty — no enforcement events this process)');
    } else {
      for (final l in log) {
        buf.writeln('  $l');
      }
    }
    return buf.toString();
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final r = _report;

    return Scaffold(
      appBar: AppBar(
        title: const Text('System Health'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: _refresh,
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: AppSpacing.screenH.copyWith(
                    top: AppSpacing.xl, bottom: AppSpacing.xxxl),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (r == null) ...[
                      const MLDWarningBanner(
                        message:
                            'Native bridge did not answer. The Kotlin layer is not responding to Flutter.',
                        tone: MLDBannerTone.danger,
                      ),
                      const SizedBox(height: AppSpacing.xxl),
                    ] else ...[
                      _section('IDENTITY', [
                        _row('Package', '${r['package']}'),
                        _row('Version', '${r['versionName']} (${r['versionCode']})'),
                        _row('Device', '${r['device']}'),
                      ]),
                      _section('RUNTIME CHECKS', [
                        _check(
                          'Native bridge',
                          true,
                          detail: 'responding',
                        ),
                        _check(
                          'State stream',
                          app.bootstrapped,
                          detail: app.bootstrapped
                              ? 'receiving native state'
                              : 'NOT receiving — UI may show stale data',
                        ),
                        _check(
                          'DataStore',
                          r['dataStoreReadable'] == true,
                          detail: r['dataStoreReadable'] == true
                              ? 'readable'
                              : 'READ FAILED',
                        ),
                        _check(
                          'Room database',
                          '${r['roomProbe']}'.startsWith('ok'),
                          detail: '${r['roomProbe']}',
                        ),
                      ]),
                      _section('ENFORCEMENT BACKBONE', [
                        _check(
                          'Accessibility enabled',
                          (r['permissions'] as Map?)?['accessibility'] == true,
                          detail: (r['permissions'] as Map?)?['accessibility'] == true
                              ? 'service is on'
                              : 'service is OFF — sessions cannot start',
                        ),
                        _row('Expected component', '${r['expectedA11yComponent']}',
                            small: true),
                        _row('Android has enabled', '${r['enabledA11yServices']}',
                            small: true),
                        _check(
                          'Overlay permission',
                          (r['permissions'] as Map?)?['overlay'] == true,
                          detail: 'required for the blocking screen',
                        ),
                        _check(
                          'Usage access',
                          (r['permissions'] as Map?)?['usageAccess'] == true,
                          detail: 'required for sessions',
                        ),
                        _row('Session', '${r['sessionStatus']}'),
                        _row('Cage active', '${r['cageActive']}'),
                        _row('Shorts blocker', '${r['shortsEnabled'] == true ? 'on' : 'off'}'),
                      ]),
                      _section('NATIVE ERRORS (${(r['nativeErrors'] as List?)?.length ?? 0})', [
                        if ((r['nativeErrors'] as List? ?? const []).isEmpty)
                          _mono('(none — no exceptions caught this process)')
                        else
                          ..._monoLines(r['nativeErrors'] as List),
                      ]),
                      _section('ENFORCEMENT LOG (last 40)', [
                        if ((r['recentLog'] as List? ?? const []).isEmpty)
                          _mono('(empty — no enforcement events yet this process. '
                              'Open a social app during a session to generate entries.)')
                        else
                          ..._monoLines(r['recentLog'] as List),
                      ]),
                    ],
                    const SizedBox(height: AppSpacing.xl),
                    MLDButton(
                      label: 'COPY FULL REPORT',
                      icon: Icons.copy,
                      onPressed: _copyReport,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Text(
                      'Tip: screenshot this screen or copy the report and send it when reporting an issue.',
                      textAlign: TextAlign.center,
                      style: AppTypography.caption(),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  List<Widget> _monoLines(List lines) =>
      lines.map((l) => _mono(l.toString())).toList();

  Widget _mono(String text) => Container(
        margin: const EdgeInsets.only(bottom: AppSpacing.xs),
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.background,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: AppColors.edge),
        ),
        child: Text(
          text,
          style: AppTypography.caption().copyWith(
            fontFamily: 'monospace',
            fontSize: 11,
            height: 1.45,
            color: AppColors.textSecondary,
          ),
        ),
      );

  Widget _section(String title, List<Widget> children) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: AppSpacing.xxl),
        Text(title, style: AppTypography.label()),
        const SizedBox(height: AppSpacing.md),
        ...children,
      ],
    );
  }

  Widget _row(String label, String value, {bool small = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 128,
            child: Text(label,
                style: AppTypography.caption(color: AppColors.textSecondary)),
          ),
          Expanded(
            child: Text(
              value.isEmpty ? '—' : value,
              style: (small ? AppTypography.caption() : AppTypography.body())
                  .copyWith(fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  Widget _check(String label, bool ok, {String detail = ''}) {
    final color = ok ? AppColors.success : AppColors.danger;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Row(
        children: [
          Icon(ok ? Icons.check_circle : Icons.cancel, color: color, size: 20),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label,
                    style: AppTypography.body(weight: FontWeight.w700)),
                if (detail.isNotEmpty)
                  Text(detail, style: AppTypography.caption()),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
