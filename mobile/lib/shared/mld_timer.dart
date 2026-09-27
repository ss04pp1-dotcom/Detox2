import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/theme/tokens.dart';

/// MLDTimer — the single most important widget during enforcement
/// (UI/UX §22): huge tabular numerals, progress ring that stays visually
/// SECONDARY to the digits.
///
/// v2.6 reference design: the ring is a thick (10px) rounded-cap arc with a
/// soft outer glow and a subtle color sweep — the signature visual of the
/// running session screens.
class MLDTimer extends StatelessWidget {
  const MLDTimer({
    super.key,
    required this.remainingSeconds,
    this.ringProgress,
    this.color = AppColors.textPrimary,
    this.ringColor = AppColors.primary,
    this.size = 64,
    this.showRing = true,
    this.ringSize = 220,
  });

  final int remainingSeconds;
  final double? ringProgress;
  final Color color;
  final Color ringColor;
  final double size;
  final bool showRing;
  final double ringSize;

  String get _formatted {
    final s = remainingSeconds < 0 ? 0 : remainingSeconds;
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    if (h > 0) {
      return '$h:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
    }
    return '${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final digits = Text(_formatted, style: AppTypography.timer(size: size, color: color));

    if (!showRing || ringProgress == null) return digits;

    return SizedBox(
      width: ringSize,
      height: ringSize,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SizedBox(
            width: ringSize,
            height: ringSize,
            child: CustomPaint(
              painter: _RingPainter(
                progress: ringProgress!.clamp(0.0, 1.0),
                color: ringColor,
                trackColor: AppColors.elevated,
              ),
            ),
          ),
          digits,
        ],
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  _RingPainter({
    required this.progress,
    required this.color,
    required this.trackColor,
  });

  final double progress;
  final Color color;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    // v2.6 reference design: thick 10px stroke with rounded caps and a soft
    // accent glow behind the arc (mockup "Glow Effects").
    final stroke = 10.0;
    final glow = stroke * 2.2;
    final rect = Offset.zero & size;
    final center = Offset(size.width / 2, size.height / 2);
    final radius = (size.shortestSide - stroke) / 2;

    // Track.
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = trackColor;
    canvas.drawCircle(center, radius, track);

    if (progress <= 0) return;

    final arcRect = rect.deflate(stroke / 2);
    final sweep = 2 * math.pi * progress;
    const start = -math.pi / 2;

    // Soft outer glow — a wider, blurred, low-alpha pass behind the arc.
    final glowPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = glow
      ..strokeCap = StrokeCap.round
      ..color = color.withValues(alpha: 0.18)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 12);
    canvas.drawArc(arcRect, start, sweep, false, glowPaint);

    // Gradient arc — sweeps from the accent to a lighter, lifted tone so
    // the ring reads as lit from the top.
    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..shader = SweepGradient(
        startAngle: start,
        endAngle: start + sweep,
        colors: [
          color.withValues(alpha: 0.55),
          color,
        ],
        transform: const GradientRotation(-math.pi / 2),
      ).createShader(rect);
    canvas.drawArc(arcRect, start, sweep, false, arc);
  }

  @override
  bool shouldRepaint(covariant _RingPainter oldDelegate) =>
      oldDelegate.progress != progress || oldDelegate.color != color;
}
