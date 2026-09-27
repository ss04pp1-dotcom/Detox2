import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/theme/tokens.dart';

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

    final (bg, fg, border) = switch (variant) {
      MLDButtonVariant.primary => (AppColors.primary, AppColors.onPrimary, null),
      MLDButtonVariant.secondary => (Colors.transparent, AppColors.textPrimary, AppColors.edge),
      MLDButtonVariant.danger => (AppColors.danger, Colors.white, null),
    };

    return Semantics(
      button: true,
      enabled: !disabled,
      child: Opacity(
        opacity: disabled ? 0.45 : 1,
        child: SizedBox(
          width: expanded ? double.infinity : null,
          height: height ?? AppSizes.ctaHeight,
          child: Material(
            color: disabled ? AppColors.elevated : bg,
            borderRadius: BorderRadius.circular(AppRadii.button),
            child: InkWell(
              onTap: disabled ? null : (onPressed!),
              borderRadius: BorderRadius.circular(AppRadii.button),
              child: Container(
                decoration: border != null
                    ? BoxDecoration(border: Border.all(color: border, width: 1.4)) // v2.5.5 audit fix: no-effect `!` removed (flow-promoted non-null)
                    : null,
                alignment: Alignment.center,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (loading)
                      const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    else if (icon != null) ...[
                      Icon(icon, size: 20, color: fg),
                      const SizedBox(width: 8),
                    ],
                    if (loading) const SizedBox(width: 10),
                    Text(
                      label,
                      style: AppTypography.body(
                        color: disabled ? AppColors.textSecondary : fg,
                        weight: FontWeight.w700,
                      ),
                    ),
                  ],
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
    final stepMs = 20;
    while (_holding && _progress < 1) {
      await Future<void>.delayed(Duration(milliseconds: stepMs));
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
        color: color,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: borderColor),
        boxShadow: const [
          BoxShadow(color: Color(0x33000000), blurRadius: 18, offset: Offset(0, 6)),
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
          Expanded(child: Text(title, style: AppTypography.label())),
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
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.elevated,
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(value,
              style: AppTypography.heading(
                color: accent ?? AppColors.textPrimary,
              ).copyWith(fontSize: 22)),
          const SizedBox(height: 2),
          Text(label, style: AppTypography.caption()),
        ],
      ),
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
              decoration: BoxDecoration(
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
        color: AppColors.surface,
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
        color: AppColors.surface,
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
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadii.card),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: accent.withValues(alpha: 0.14),
                borderRadius: BorderRadius.circular(AppRadii.sm),
                border: Border.all(color: accent.withValues(alpha: 0.3)),
              ),
              child: Icon(icon, color: accent, size: 22),
            ),
            const SizedBox(height: AppSpacing.md),
            Text(title, style: AppTypography.body(weight: FontWeight.w700)),
            const SizedBox(height: 2),
            Text(subtitle, style: AppTypography.caption()),
          ],
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
