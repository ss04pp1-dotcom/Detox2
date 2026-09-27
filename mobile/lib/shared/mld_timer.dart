import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/theme/tokens.dart';

/// MLDTimer — the single most important widget during enforcement
/// (UI/UX §22): huge tabular numerals, optional progress ring that stays
/// visually SECONDARY to the digits.
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
    final stroke = 6.0;
    final rect = Offset.zero & size;
    final radius = (size.shortestSide - stroke) / 2;
    final center = Offset(size.width / 2, size.height / 2);

    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = trackColor;
    canvas.drawCircle(center, radius, track);

    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..color = color;
    canvas.drawArc(
      rect.deflate(stroke / 2),
      -math.pi / 2,
      2 * math.pi * progress,
      false,
      arc,
    );
  }

  @override
  bool shouldRepaint(covariant _RingPainter oldDelegate) =>
      oldDelegate.progress != progress || oldDelegate.color != color;
}
