import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';
import '../study/study_setup_screen.dart' show DetoxConfirmArgs;

/// Arguments for starting a session (from setup screens or confirm screen).
class ActivationArgs {
  const ActivationArgs({
    required this.mode,
    required this.durationMinutes,
    required this.strictness,
    required this.blockedCategories,
    required this.allowedPackages,
    this.subject = '',
  });

  factory ActivationArgs.fromDetox(dynamic raw) {
    if (raw is ActivationArgs) return raw;
    // v2.5.5 audit fix: `raw` used to be duck-typed unconditionally
    // (`raw?.durationMinutes`), so a Map/String argument (anything that was
    // not a DetoxConfirmArgs) hit dynamic dispatch and could throw
    // NoSuchMethodError on the activation path. Accept ONLY the
    // DetoxConfirmArgs shape; everything else falls back to safe defaults.
    if (raw is DetoxConfirmArgs) {
      return ActivationArgs(
        mode: 'DETOX',
        durationMinutes: raw.durationMinutes,
        strictness: raw.strictness,
        blockedCategories: const ['social', 'games', 'shorts', 'entertainment', 'video'],
        allowedPackages: const [],
      );
    }
    return const ActivationArgs(
      mode: 'DETOX',
      durationMinutes: 120,
      strictness: 'MAXLEVEL',
      blockedCategories: ['social', 'games', 'shorts', 'entertainment', 'video'],
      allowedPackages: [],
    );
  }

  final String mode;
  final int durationMinutes;
  final String strictness;
  final List<String> blockedCategories;
  final List<String> allowedPackages;
  final String subject;
}

/// Activation animation screen (UI/UX §19): "Preparing your focus system…"
/// Policy locked -> Enforcement active -> Session started.
///
/// CRITICAL (TRD §95): the UI must NOT show an active session before native
/// activation succeeds. We await the native result and only then navigate.
class ActivationScreen extends StatefulWidget {
  const ActivationScreen({super.key});

  @override
  State<ActivationScreen> createState() => _ActivationScreenState();
}

class _ActivationScreenState extends State<ActivationScreen> {
  int _step = 0;
  String? _error;
  bool _done = false;
  bool _activationStarted = false;

  // v2.5.5 audit fix: the parsed route arguments, cached once in
  // didChangeDependencies and reused by build() for the error-title mode.
  ActivationArgs? _cachedArgs;

  static const _steps = ['Validating permissions', 'Locking policy', 'Activating enforcement'];

  // ModalRoute lookup must NOT happen inside initState (inherited-widget
  // lookups are illegal there); didChangeDependencies is the correct seam.
  // A flag guarantees _activate runs exactly once.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_activationStarted) return;
    _activationStarted = true;
    final raw = ModalRoute.of(context)?.settings.arguments;
    final args = raw is ActivationArgs
        ? raw
        : ActivationArgs.fromDetox(raw);
    _cachedArgs = args;
    _activate(args);
  }

  Future<void> _activate(ActivationArgs args) async {
    // Step 1: permission precheck (TRD §97)
    await Future<void>.delayed(const Duration(milliseconds: 350));
    if (!mounted) return;
    setState(() => _step = 1);

    final result = await NativeBridge.instance.startSession(
      mode: args.mode,
      durationMinutes: args.durationMinutes,
      strictness: args.strictness,
      allowedPackages: args.allowedPackages,
      blockedCategories: args.blockedCategories,
      subject: args.subject,
    );

    if (!mounted) return;

    if (result.isOk) {
      HapticFeedback.heavyImpact();
      setState(() {
        _step = 2;
        _done = true;
      });
      await Future<void>.delayed(const Duration(milliseconds: 650));
      if (!mounted) return;
      Navigator.of(context).pushNamedAndRemoveUntil(
        AppConstants.routeActiveSession,
        (route) => false,
      );
    } else {
      setState(() => _error = result.error?.message ?? 'Activation failed');
    }
  }

  @override
  Widget build(BuildContext context) {
    // v2.5.5 audit fix: the error title used to hardcode STUDY whenever the
    // route arguments were DetoxConfirmArgs (the `is ActivationArgs` test
    // defaulted isStudy to true), so a failed DETOX activation read
    // "COULD NOT START STUDY". Reuse the didChangeDependencies-cached args.
    final isStudy = _cachedArgs == null || _cachedArgs!.mode == 'STUDY';

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Padding(
          padding: AppSpacing.screenH,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error == null) ...[
                TweenAnimationBuilder<double>(
                  tween: Tween(begin: 0.9, end: 1.0),
                  duration: const Duration(milliseconds: 700),
                  curve: Curves.easeOutBack,
                  builder: (context, v, child) => Transform.scale(scale: v, child: child),
                  child: Container(
                    height: 120,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppColors.primary.withValues(alpha: 0.12),
                      border: Border.all(color: AppColors.primary.withValues(alpha: 0.4)),
                    ),
                    child: const Icon(Icons.lock_outline, size: 52, color: AppColors.primary),
                  ),
                ),
                const SizedBox(height: AppSpacing.massive),
                Text(
                  _done ? 'SESSION STARTED' : 'PREPARING YOUR FOCUS SYSTEM…',
                  textAlign: TextAlign.center,
                  style: AppTypography.section(),
                ),
                const SizedBox(height: AppSpacing.xxl),
                for (int i = 0; i < _steps.length; i++)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
                    child: Row(
                      children: [
                        if (i < _step || _done)
                          const Icon(Icons.check_circle, color: AppColors.success, size: 20)
                        else if (i == _step)
                          const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        else
                          const Icon(Icons.circle_outlined, color: AppColors.edge, size: 20),
                        const SizedBox(width: AppSpacing.md),
                        Text(_steps[i], style: AppTypography.body(color: AppColors.textSecondary)),
                      ],
                    ),
                  ),
              ] else ...[
                const Icon(Icons.gpp_bad_outlined, size: 64, color: AppColors.danger),
                const SizedBox(height: AppSpacing.xxl),
                Text('COULD NOT START ${isStudy ? 'STUDY' : 'DETOX'}',
                    textAlign: TextAlign.center,
                    style: AppTypography.heading(color: AppColors.danger)),
                const SizedBox(height: AppSpacing.md),
                Text(_error!,
                    textAlign: TextAlign.center,
                    style: AppTypography.body(color: AppColors.textSecondary)),
                const SizedBox(height: AppSpacing.xxxl),
                MLDButton(
                  label: 'FIX PERMISSIONS',
                  onPressed: () =>
                      Navigator.of(context).pushNamed(AppConstants.routePermissionCenter),
                ),
                const SizedBox(height: AppSpacing.md),
                MLDButton(
                  label: 'Back',
                  variant: MLDButtonVariant.secondary,
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
