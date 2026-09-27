import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// MAXLEVEL DETOX — centralized design tokens (UI/UX doc §81).
///
/// Nothing in the app may hardcode colors, spacing, radii or type sizes —
/// everything flows through these tokens so the visual language stays
/// consistent: dark, serious, premium, high-contrast during enforcement.
abstract final class AppColors {
  // Surfaces
  static const Color background = Color(0xFF0A0E1A);
  static const Color surface = Color(0xFF111A2E);
  static const Color elevated = Color(0xFF1E293B);
  static const Color edge = Color(0xFF243049);

  // Brand
  static const Color primary = Color(0xFF6366F1); // indigo 500
  static const Color primaryDim = Color(0xFF4F46E5); // indigo 600
  static const Color premium = Color(0xFF8B5CF6); // violet

  // Semantics (UI/UX §4 — color is never the only signal)
  static const Color success = Color(0xFF10B981);
  static const Color warning = Color(0xFFF59E0B);
  static const Color danger = Color(0xFFEF4444);
  static const Color info = Color(0xFF3B82F6);

  // Text
  static const Color textPrimary = Color(0xFFF1F5F9);
  static const Color textSecondary = Color(0xFF94A3B8);
  static const Color textDisabled = Color(0xFF64748B);

  // Cage / enforcement lock — deepest red-toned surface
  static const Color cageBackground = Color(0xFF140B0B);
  static const Color cageSurface = Color(0xFF241111);

  // Mode accents (v2.6 reference design) — every enforcement mode carries
  // its own signature color so the user can tell state at a glance:
  // Study = teal · Detox = fuchsia · Monk = amber · Lock = red ·
  // Safety Pause = cyan · Prime Commit = violet.
  static const Color study = Color(0xFF00D2A0); // teal
  static const Color studyDim = Color(0xFF00A080);
  static const Color detox = Color(0xFFD946EF); // fuchsia
  static const Color detoxDim = Color(0xFFA21FAF);
  static const Color monk = Color(0xFFF59E0B); // amber
  static const Color monkDim = Color(0xFFD97706);
  static const Color lock = Color(0xFFEF4444); // red (matches danger)
  static const Color lockDim = Color(0xFFDC2626);
  static const Color safety = Color(0xFF22D3EE); // cyan
  static const Color safetyDim = Color(0xFF0891B2);
  static const Color prime = Color(0xFF7C3AED); // violet
  static const Color primeDim = Color(0xFF6D28D9);

  static const Color onPrimary = Color(0xFFFFFFFF);
}

abstract final class AppSpacing {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 20;
  static const double xxl = 24;
  static const double xxxl = 32;
  static const double huge = 40;
  static const double massive = 48;
  static const double colossal = 64;

  /// Primary screen horizontal padding (UI/UX §6: 20–24px).
  static const EdgeInsets screenH = EdgeInsets.symmetric(horizontal: 20);
}

abstract final class AppRadii {
  static const double sm = 12; // small components
  static const double button = 16; // buttons
  static const double card = 20; // cards
  static const double hero = 28; // hero surfaces
}

abstract final class AppSizes {
  /// Minimum touch target (UI/UX §55).
  static const double minTouch = 48;
  /// Primary CTA height.
  static const double ctaHeight = 54;
  static const double iconMd = 24;
}

abstract final class AppDurations {
  static const Duration fast = Duration(milliseconds: 150);
  static const Duration normal = Duration(milliseconds: 250);
  static const Duration slow = Duration(milliseconds: 400);
  /// Hold-to-confirm length for the bailout flow (UI/UX §33).
  static const Duration holdToConfirm = Duration(milliseconds: 2200);
}

/// Typography — Inter with tabular numerals for timers (UI/UX §5).
abstract final class AppTypography {
  static TextStyle display({Color color = AppColors.textPrimary, FontWeight weight = FontWeight.w800}) =>
      GoogleFonts.inter(fontSize: 36, height: 1.1, fontWeight: weight, color: color, letterSpacing: -0.5);

  static TextStyle heading({Color color = AppColors.textPrimary, FontWeight weight = FontWeight.w700}) =>
      GoogleFonts.inter(fontSize: 26, height: 1.15, fontWeight: weight, color: color, letterSpacing: -0.3);

  static TextStyle section({Color color = AppColors.textPrimary, FontWeight weight = FontWeight.w700}) =>
      GoogleFonts.inter(fontSize: 19, height: 1.2, fontWeight: weight, color: color);

  static TextStyle body({Color color = AppColors.textPrimary, FontWeight weight = FontWeight.w400, double? height}) =>
      GoogleFonts.inter(fontSize: 15, height: height ?? 1.5, fontWeight: weight, color: color);

  static TextStyle caption({Color? color, FontWeight weight = FontWeight.w500}) =>
      GoogleFonts.inter(fontSize: 13, height: 1.4, fontWeight: weight, color: color ?? AppColors.textSecondary);

  /// Uppercase micro-label used for section headers.
  static TextStyle label({Color color = AppColors.textSecondary}) =>
      GoogleFonts.inter(fontSize: 12, fontWeight: FontWeight.w700, letterSpacing: 1.2, color: color, height: 1.3);

  /// The enforcement timer — tabular numerals so digits never jitter.
  static TextStyle timer({double size = 56, Color color = AppColors.textPrimary}) => GoogleFonts.jetBrainsMono(
        fontSize: size,
        fontWeight: FontWeight.w700,
        height: 1.0,
        letterSpacing: 2,
        color: color,
        fontFeatures: const [FontFeature.tabularFigures()],
      );
}
