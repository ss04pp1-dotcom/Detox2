import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/theme/tokens.dart';
import '../data/native_bridge.dart';

/// MLDAppBackdrop — shared visual atmosphere for every route.
/// It is intentionally decoration-only: no state, navigation, or interaction.
class MLDAppBackdrop extends StatelessWidget {
  const MLDAppBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF020A16), AppColors.background, Color(0xFF06182A)],
          stops: [0, .48, 1],
        ),
      ),
      child: Stack(
        fit: StackFit.expand,
        children: [
          const IgnorePointer(child: CustomPaint(painter: _MLDGridPainter())),
          Positioned(
            top: -170,
            right: -110,
            child: IgnorePointer(
              child: Container(
                width: 380,
                height: 380,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.primary.withValues(alpha: .035),
                  boxShadow: [BoxShadow(color: AppColors.primary.withValues(alpha: .16), blurRadius: 150, spreadRadius: 18)],
                ),
              ),
            ),
          ),
          Positioned(
            bottom: -170,
            left: -120,
            child: IgnorePointer(
              child: Container(
                width: 390,
                height: 390,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.premium.withValues(alpha: .03),
                  boxShadow: [BoxShadow(color: AppColors.premium.withValues(alpha: .12), blurRadius: 150, spreadRadius: 15)],
                ),
              ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}


class _MLDGridPainter extends CustomPainter {
  const _MLDGridPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()..color = AppColors.primary.withValues(alpha: .018)..strokeWidth = 1;
    const step = 28.0;
    for (double x = 0; x <= size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), p);
    }
    for (double y = 0; y <= size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), p);
    }
    final glow = Paint()..color = AppColors.primary.withValues(alpha: .018);
    for (double x = 14; x < size.width; x += step * 2) {
      for (double y = 14; y < size.height; y += step * 2) {
        canvas.drawCircle(Offset(x, y), 1.2, glow);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _MLDGridPainter oldDelegate) => false;
}

/// Compact brand header used by the reference dashboard and key surfaces.
/// The mark is vector-painted in Flutter so no mock image/data is embedded.
class MLDAppBarTitle extends StatelessWidget {
  const MLDAppBarTitle({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CustomPaint(size: Size(30, 24), painter: _MLDLogoPainter()),
        const SizedBox(width: 9),
        Flexible(child: Text(title, overflow: TextOverflow.ellipsis)),
      ],
    );
  }
}

class MLDBrandHeader extends StatelessWidget {
  const MLDBrandHeader({super.key, this.compact = false});

  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        CustomPaint(
          size: Size(compact ? 34 : 42, compact ? 28 : 34),
          painter: const _MLDLogoPainter(),
        ),
        SizedBox(width: compact ? 8 : 10),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'MAXLEVEL DETOX',
              style: AppTypography.body(weight: FontWeight.w900)
                  .copyWith(fontSize: compact ? 13 : 15, letterSpacing: -.2),
            ),
            if (!compact)
              Text('Better You, Higher Level', style: AppTypography.caption().copyWith(fontSize: 10)),
          ],
        ),
      ],
    );
  }
}

class _MLDLogoPainter extends CustomPainter {
  const _MLDLogoPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * .13
      ..strokeCap = StrokeCap.square
      ..strokeJoin = StrokeJoin.miter;
    final left = AppColors.primary.withValues(alpha: .85);
    const right = AppColors.textPrimary;
    const accent = AppColors.primary;

    final a = Path()
      ..moveTo(size.width * .05, size.height * .88)
      ..lineTo(size.width * .34, size.height * .18)
      ..lineTo(size.width * .52, size.height * .62);
    p.color = left;
    canvas.drawPath(a, p);

    final b = Path()
      ..moveTo(size.width * .42, size.height * .88)
      ..lineTo(size.width * .66, size.height * .28)
      ..lineTo(size.width * .95, size.height * .88);
    p.color = right;
    canvas.drawPath(b, p);

    final glow = Paint()
      ..color = accent.withValues(alpha: .16)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 7);
    canvas.drawCircle(Offset(size.width * .66, size.height * .28), size.width * .09, glow);
  }

  @override
  bool shouldRepaint(covariant _MLDLogoPainter oldDelegate) => false;
}

/// MLDButton — primary CTA (UI/UX §56). Minimum 54dp, loading state never
/// implies success while native validation is pending (UI/UX §80).
enum MLDButtonVariant { primary, secondary, danger }

class MLDButton extends StatelessWidget {
  const MLDButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.variant = MLDButtonVariant.primary,
    this.loading = false,
    this.icon,
    this.expanded = true,
    this.height,
  });

  final String label;
  final VoidCallback? onPressed;
  final MLDButtonVariant variant;
  final bool loading;
  final IconData? icon;
  final bool expanded;
  final double? height;

  @override
  Widget build(BuildContext context) {
    final disabled = onPressed == null || loading;
    final accent = variant == MLDButtonVariant.danger ? AppColors.danger : AppColors.primary;
    final fg = variant == MLDButtonVariant.secondary ? AppColors.textPrimary : Colors.white;
    return Semantics(
      button: true,
      enabled: !disabled,
      child: Opacity(
        opacity: disabled ? 0.45 : 1,
        child: SizedBox(
          width: expanded ? double.infinity : null,
          height: height ?? AppSizes.ctaHeight,
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: variant == MLDButtonVariant.secondary
                  ? LinearGradient(colors: [AppColors.surface.withValues(alpha: .92), AppColors.elevated.withValues(alpha: .72)])
                  : LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [accent, accent.withValues(alpha: .68)]),
              color: null,
              borderRadius: BorderRadius.circular(AppRadii.button),
              border: Border.all(color: variant == MLDButtonVariant.secondary ? AppColors.edge : accent.withValues(alpha: .82)),
              boxShadow: [
                BoxShadow(color: Colors.black.withValues(alpha: .25), blurRadius: 16, offset: const Offset(0, 8)),
                if (variant != MLDButtonVariant.secondary) BoxShadow(color: accent.withValues(alpha: .20), blurRadius: 22, offset: const Offset(0, 5)),
              ],
            ),
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                onTap: disabled ? null : onPressed,
                borderRadius: BorderRadius.circular(AppRadii.button),
                child: Center(
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    if (loading) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    else if (icon != null) ...[Icon(icon, size: 19, color: fg), const SizedBox(width: 8)],
                    if (loading) const SizedBox(width: 10),
                    Text(label, style: AppTypography.body(color: fg, weight: FontWeight.w800)),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Hold-to-confirm button for the bailout flow (UI/UX §33) — deliberately
/// high-friction so it cannot be triggered accidentally.
class MLDHoldToConfirmButton extends StatefulWidget {
  const MLDHoldToConfirmButton({
    super.key,
    required this.label,
    required this.onConfirmed,
    this.duration = AppDurations.holdToConfirm,
  });

  final String label;
  final VoidCallback onConfirmed;
  final Duration duration;

  @override
  State<MLDHoldToConfirmButton> createState() => _MLDHoldToConfirmButtonState();
}

class _MLDHoldToConfirmButtonState extends State<MLDHoldToConfirmButton> {
  double _progress = 0;
  bool _holding = false;

  void _start() {
    setState(() {
      _holding = true;
      _progress = 0;
    });
    HapticFeedback.mediumImpact();
    _tick();
  }

  Future<void> _tick() async {
    const stepMs = 20;
    while (_holding && _progress < 1) {
      await Future<void>.delayed(const Duration(milliseconds: stepMs));
      if (!_mounted || !_holding) return;
      setState(() {
        _progress = (_progress + stepMs / widget.duration.inMilliseconds).clamp(0.0, 1.0);
      });
      if (_progress >= 1) {
        HapticFeedback.heavyImpact();
        widget.onConfirmed();
        _reset();
        return;
      }
    }
  }

  void _reset() {
    if (!_mounted) return;
    setState(() {
      _holding = false;
      _progress = 0;
    });
  }

  bool _mounted = true;

  @override
  void dispose() {
    _mounted = false;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // v2.5.5 audit fix: this used to be a GestureDetector(onLongPressStart:
    // _start) WRAPPING the Listener(onPointerDown: _start) — both fired, so
    // ~500 ms into every hold the long-press handler started a SECOND
    // progress loop: the bar visibly reset and the deliberate 2.2 s gate
    // completed in ~1.35 s (with a double haptic). The Listener alone
    // covers down/up/cancel deterministically.
    return Listener(
      onPointerDown: (_) => _start(),
      onPointerUp: (_) => _reset(),
      onPointerCancel: (_) => _reset(),
      child: SizedBox(
        width: double.infinity,
        height: AppSizes.ctaHeight,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(AppRadii.button),
          child: Stack(
            children: [
              Container(color: AppColors.danger),
              Positioned.fill(
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: _progress,
                  child: Container(color: AppColors.danger.withValues(alpha: 0.6)),
                ),
              ),
              Center(
                child: Text(
                  _progress > 0 ? 'KEEP HOLDING…' : widget.label,
                  style: AppTypography.body(color: Colors.white, weight: FontWeight.w800),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// MLDCard — layered surface (UI/UX §57–58).
class MLDCard extends StatelessWidget {
  const MLDCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(AppSpacing.xxl),
    this.radius = AppRadii.card,
    this.color = AppColors.surface,
    this.borderColor = AppColors.edge,
  });

  final Widget child;
  final EdgeInsets padding;
  final double radius;
  final Color color;
  final Color borderColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            color.withValues(alpha: .98),
            AppColors.elevated.withValues(alpha: .68),
            AppColors.background.withValues(alpha: .56),
          ],
          stops: const [0, .48, 1],
        ),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: borderColor.withValues(alpha: .72)),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: .38), blurRadius: 24, offset: const Offset(0, 12)),
          BoxShadow(color: AppColors.primary.withValues(alpha: .055), blurRadius: 28, spreadRadius: -8),
        ],
      ),
      child: child,
    );
  }
}

/// MLDSectionHeader — uppercase micro label with optional trailing action.
class MLDSectionHeader extends StatelessWidget {
  const MLDSectionHeader({super.key, required this.title, this.action});

  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Row(
        children: [
          Container(
            width: 3,
            height: 14,
            decoration: BoxDecoration(
              color: AppColors.primary,
              borderRadius: BorderRadius.circular(99),
              boxShadow: [BoxShadow(color: AppColors.primary.withValues(alpha: .45), blurRadius: 8)],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(child: Text(title, style: AppTypography.label(color: AppColors.textSecondary))),
          if (action != null) action!,
        ],
      ),
    );
  }
}

/// MLDStatTile — small metric inside a card.
class MLDStatTile extends StatelessWidget {
  const MLDStatTile({
    super.key,
    required this.label,
    required this.value,
    this.accent,
  });

  final String label;
  final String value;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    final c = accent ?? AppColors.primary;
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [c.withValues(alpha: .12), AppColors.surface, AppColors.elevated.withValues(alpha: .7)],
        ),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: c.withValues(alpha: .26)),
        boxShadow: [BoxShadow(color: c.withValues(alpha: .06), blurRadius: 18, spreadRadius: -4)],
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        Row(
          children: [
            Container(width: 8, height: 8, decoration: BoxDecoration(color: c, shape: BoxShape.circle, boxShadow: [BoxShadow(color: c.withValues(alpha: .65), blurRadius: 9)])),
            const Spacer(),
            Icon(Icons.chevron_right_rounded, color: c.withValues(alpha: .65), size: 17),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Text(value, style: AppTypography.heading(color: AppColors.textPrimary, weight: FontWeight.w800).copyWith(fontSize: 22)),
        const SizedBox(height: 3),
        Text(label, style: AppTypography.caption()),
      ]),
    );
  }
}

/// MLDEmptyState (UI/UX §47 — never leave blank screens).
class MLDEmptyState extends StatelessWidget {
  const MLDEmptyState({
    super.key,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
    this.icon = Icons.hourglass_empty,
  });

  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xxxl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(AppSpacing.xl),
              decoration: const BoxDecoration(
                color: AppColors.elevated,
                shape: BoxShape.circle,
              ),
              child: Icon(icon, size: 32, color: AppColors.textSecondary),
            ),
            const SizedBox(height: AppSpacing.xxl),
            Text(title,
                textAlign: TextAlign.center,
                style: AppTypography.section(weight: FontWeight.w800)),
            const SizedBox(height: AppSpacing.sm),
            Text(message,
                textAlign: TextAlign.center,
                style: AppTypography.caption()),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: AppSpacing.xxl),
              MLDButton(
                label: actionLabel!,
                onPressed: onAction,
                expanded: false,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// MLDWarningBanner — paired color + icon + text (never color alone).
class MLDWarningBanner extends StatelessWidget {
  const MLDWarningBanner({
    super.key,
    required this.message,
    this.tone = MLDBannerTone.warning,
    this.actionLabel,
    this.onAction,
  });

  final String message;
  final MLDBannerTone tone;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final (color, icon) = switch (tone) {
      MLDBannerTone.warning => (AppColors.warning, Icons.warning_amber_rounded),
      MLDBannerTone.danger => (AppColors.danger, Icons.error_outline),
      MLDBannerTone.info => (AppColors.info, Icons.info_outline),
      MLDBannerTone.success => (AppColors.success, Icons.check_circle_outline),
    };
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 22),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Text(message, style: AppTypography.body(weight: FontWeight.w500))),
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(width: AppSpacing.md),
            TextButton(
              onPressed: onAction,
              style: TextButton.styleFrom(
                foregroundColor: color,
                textStyle: AppTypography.body(weight: FontWeight.w700).copyWith(fontSize: 13),
              ),
              child: Text(actionLabel!),
            ),
          ],
        ],
      ),
    );
  }
}

enum MLDBannerTone { warning, danger, info, success }

/// MLDCoinBadge — small coin pill.
class MLDCoinBadge extends StatelessWidget {
  const MLDCoinBadge({super.key, required this.amount, this.large = false});

  final int amount;
  final bool large;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: large ? AppSpacing.lg : AppSpacing.md,
        vertical: large ? AppSpacing.sm : 4,
      ),
      decoration: BoxDecoration(
        color: AppColors.warning.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(large ? AppRadii.sm : 999),
        border: Border.all(color: AppColors.warning.withValues(alpha: 0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.monetization_on_outlined,
              size: large ? 24 : 16, color: AppColors.warning),
          const SizedBox(width: 6),
          Text(
            '$amount',
            style: AppTypography.body(
              color: AppColors.warning,
              weight: FontWeight.w800,
            ).copyWith(fontSize: large ? 22 : 13),
          ),
        ],
      ),
    );
  }
}

/// MLDPermissionTile — permission row with status + Fix action (UI/UX §45).
class MLDPermissionTile extends StatelessWidget {
  const MLDPermissionTile({
    super.key,
    required this.name,
    required this.description,
    required this.granted,
    required this.onSetup,
    this.required_ = true,
  });

  final String name;
  final String description;
  final bool granted;
  final VoidCallback onSetup;
  final bool required_;

  @override
  Widget build(BuildContext context) {
    final color = granted ? AppColors.success : (required_ ? AppColors.danger : AppColors.warning);
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        gradient: LinearGradient(colors: [AppColors.surface, AppColors.elevated.withValues(alpha: .58)]),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: granted ? AppColors.edge : color.withValues(alpha: 0.5)),
      ),
      child: Row(
        children: [
          Icon(granted ? Icons.check_circle : Icons.error_outline, color: color, size: 24),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  Text(name, style: AppTypography.body(weight: FontWeight.w700)),
                  if (!required_) ...[
                    const SizedBox(width: 6),
                    Text('(optional)', style: AppTypography.caption()),
                  ],
                ]),
                const SizedBox(height: 2),
                Text(description, style: AppTypography.caption()),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          TextButton(
            onPressed: onSetup,
            style: TextButton.styleFrom(
              foregroundColor: granted ? AppColors.textSecondary : AppColors.primary,
              textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
            ),
            child: Text(granted ? 'View' : 'Set Up'),
          ),
        ],
      ),
    );
  }
}

/// MLDAppRuleTile — Allow/Block row (UI/UX §44).
class MLDAppRuleTile extends StatelessWidget {
  const MLDAppRuleTile({
    super.key,
    required this.name,
    required this.category,
    required this.blocked,
    required this.onToggle,
  });

  final String name;
  final String category;
  final bool blocked;
  final ValueChanged<bool> onToggle;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
      decoration: BoxDecoration(
        gradient: LinearGradient(colors: [AppColors.surface, AppColors.elevated.withValues(alpha: .58)]),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, style: AppTypography.body(weight: FontWeight.w600)),
                Text(category.toUpperCase(), style: AppTypography.caption()),
              ],
            ),
          ),
          _BlockToggle(blocked: blocked, onChanged: onToggle),
        ],
      ),
    );
  }
}

class _BlockToggle extends StatelessWidget {
  const _BlockToggle({required this.blocked, required this.onChanged});

  final bool blocked;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: blocked ? 'Blocked' : 'Allowed',
      child: GestureDetector(
        onTap: () => onChanged(!blocked),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _segment('Allow', !blocked, () => onChanged(false)),
              _segment('Block', blocked, () => onChanged(true)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _segment(String label, bool selected, VoidCallback onTap) {
    final color = selected
        ? (label == 'Block' ? AppColors.danger : AppColors.success)
        : AppColors.textSecondary;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? color.withValues(alpha: 0.15) : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: selected ? color : AppColors.edge),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              label == 'Block' ? Icons.block : Icons.check,
              size: 14,
              color: color,
            ),
            const SizedBox(width: 4),
            Text(label,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w700,
                  fontSize: 12,
                )),
          ],
        ),
      ),
    );
  }
}

/// Warning dots ● ● ○ ○ ○ (UI/UX §25).
class MLDWarningDots extends StatelessWidget {
  const MLDWarningDots({
    super.key,
    required this.count,
    required this.limit,
    this.size = 14,
  });

  final int count;
  final int limit;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Warning $count of $limit',
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(limit, (i) {
          final filled = i < count;
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Container(
              width: size,
              height: size,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: filled
                    ? (count >= limit ? AppColors.danger : AppColors.warning)
                    : Colors.transparent,
                border: Border.all(
                  color: filled
                      ? (count >= limit ? AppColors.danger : AppColors.warning)
                      : AppColors.edge,
                  width: 2,
                ),
              ),
            ),
          );
        }),
      ),
    );
  }
}

/// MLDStatusChip — tinted status pill with a colored dot, e.g.
/// "RUNNING • 1H 25M LEFT" (v2.6 reference design: every active session
/// screen carries one under the mode title).
class MLDStatusChip extends StatelessWidget {
  const MLDStatusChip({
    super.key,
    required this.label,
    required this.color,
    this.icon,
  });

  final String label;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(shape: BoxShape.circle, color: color),
          ),
          const SizedBox(width: AppSpacing.sm),
          Text(
            label,
            style: AppTypography.label(color: color).copyWith(letterSpacing: 0.8),
          ),
          if (icon != null) ...[
            const SizedBox(width: AppSpacing.sm),
            Icon(icon, size: 14, color: color),
          ],
        ],
      ),
    );
  }
}

/// MLDModeTile — quick-action grid tile for an enforcement mode (v2.6
/// reference design "Quick Actions"): tinted icon container, title and a
/// one-line promise in the mode's signature color.
class MLDModeTile extends StatelessWidget {
  const MLDModeTile({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.accent,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Color accent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppRadii.card),
        child: Ink(
          decoration: BoxDecoration(
            gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [accent.withValues(alpha: .14), AppColors.surface, AppColors.background.withValues(alpha: .45)]),
            borderRadius: BorderRadius.circular(AppRadii.card),
            border: Border.all(color: accent.withValues(alpha: .24)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Container(width: 48, height: 48, decoration: BoxDecoration(color: accent.withValues(alpha: .14), shape: BoxShape.circle, border: Border.all(color: accent.withValues(alpha: .32))), child: Icon(icon, color: accent, size: 23)),
                Icon(Icons.arrow_forward_rounded, color: accent.withValues(alpha: .75), size: 19),
              ]),
              const Spacer(),
              Text(title, style: AppTypography.body(weight: FontWeight.w800)),
              const SizedBox(height: 3),
              Text(subtitle, style: AppTypography.caption()),
            ]),
          ),
        ),
      ),
    );
  }
}

/// Simple weekly bar chart, zero dependencies (UI/UX §37 "keep charts simple").
class MLDBarChart extends StatelessWidget {
  const MLDBarChart({
    super.key,
    required this.values,
    required this.labels,
    this.accent = AppColors.primary,
    this.height = 140,
  });

  final List<double> values;
  final List<String> labels;
  final Color accent;
  final double height;

  @override
  Widget build(BuildContext context) {
    final maxV = values.isEmpty ? 1.0 : values.reduce((a, b) => a > b ? a : b);
    final scale = maxV <= 0 ? 0.0 : 1.0;
    return SizedBox(
      height: height,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: List.generate(values.length, (i) {
          final v = values[i];
          final frac = maxV <= 0 ? 0.0 : (v / maxV) * scale;
          return Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 5),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Container(
                    height: (height - 26) * frac.clamp(0.04, 1.0),
                    decoration: BoxDecoration(
                      color: accent.withValues(alpha: 0.35 + 0.65 * (maxV <= 0 ? 0 : v / maxV)),
                      borderRadius: const BorderRadius.vertical(top: Radius.circular(6)),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(labels.length > i ? labels[i] : '',
                      style: AppTypography.caption().copyWith(fontSize: 11)),
                ],
              ),
            ),
          );
        }),
      ),
    );
  }
}

// ---------------------------------------------------------------------
// v2.7 r13 — Emergency lockdown banner (user-requested semantics).
//
// Emergency = the phone is a DIALER AND NOTHING ELSE: enforcement stays
// fully armed, every other app bounces straight back to the dialer. This
// banner is how the user deliberately ENDS the emergency from our app
// (the only non-dialer app reachable during the lockdown). It polls the
// native lockdown state every 3s and renders nothing when inactive.
// ---------------------------------------------------------------------
class MLDEmergencyBanner extends StatefulWidget {
  const MLDEmergencyBanner({super.key});

  @override
  State<MLDEmergencyBanner> createState() => _MLDEmergencyBannerState();
}

class _MLDEmergencyBannerState extends State<MLDEmergencyBanner> {
  bool _active = false;
  int _remaining = 0;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _refresh();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _refresh());
  }

  Future<void> _refresh() async {
    try {
      final s = await NativeBridge.instance.getEmergencyLockdown();
      if (!mounted) return;
      final active = s['active'] == true;
      final remaining = ((s['remainingSeconds'] as num?) ?? 0).toInt();
      if (active != _active || _remaining != remaining) {
        setState(() {
          _active = active;
          _remaining = remaining;
        });
      }
    } catch (_) {
      // bridge hiccup — the next poll retries
    }
  }

  Future<void> _end() async {
    try {
      await NativeBridge.instance.endEmergencyLockdown();
    } catch (_) {
    }
    if (!mounted) return;
    setState(() => _active = false);
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_active) return const SizedBox.shrink();
    final mins = (_remaining ~/ 60).clamp(0, 99);
    final secs = _remaining % 60;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.danger.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.danger.withValues(alpha: 0.45)),
      ),
      child: Row(
        children: [
          const Icon(Icons.emergency, color: AppColors.danger, size: 26),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('EMERGENCY MODE ACTIVE',
                    style: AppTypography.body(
                        weight: FontWeight.w800, color: AppColors.danger)),
                const SizedBox(height: 2),
                Text(
                  'Phone limited to the dialer only. Ends in '
                  '${mins}m ${secs.toString().padLeft(2, '0')}s — or end it now.',
                  style: AppTypography.caption(),
                ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          MLDButton(
            label: 'END',
            onPressed: _end,
            variant: MLDButtonVariant.danger,
          ),
        ],
      ),
    );
  }
}
