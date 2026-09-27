import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'tokens.dart';

/// MAXLEVEL DETOX visual system.
///
/// This file is deliberately presentation-only: it changes Material defaults
/// (surfaces, controls, dialogs, navigation and typography) without touching
/// enforcement, persistence, networking or business logic.
ThemeData buildMldTheme() {
  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      primary: AppColors.primary,
      onPrimary: AppColors.onPrimary,
      secondary: AppColors.premium,
      onSecondary: Colors.white,
      surface: AppColors.surface,
      onSurface: AppColors.textPrimary,
      error: AppColors.danger,
      onError: Colors.white,
    ),
    scaffoldBackgroundColor: Colors.transparent,
    splashFactory: InkSparkle.splashFactory,
  );

  final outline = AppColors.edge.withValues(alpha: .82);

  return base.copyWith(
    appBarTheme: AppBarTheme(
      backgroundColor: AppColors.background.withValues(alpha: .58),
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      toolbarHeight: 62,
      centerTitle: false,
      titleSpacing: 14,
      systemOverlayStyle: SystemUiOverlayStyle.light,
      titleTextStyle: const TextStyle(
        color: AppColors.textPrimary,
        fontSize: 18,
        fontWeight: FontWeight.w800,
        letterSpacing: -.2,
      ),
      iconTheme: const IconThemeData(color: AppColors.textPrimary, size: 22),
      actionsIconTheme: const IconThemeData(color: AppColors.textSecondary, size: 21),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(AppSizes.ctaHeight),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.button)),
        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800, letterSpacing: .1),
        elevation: 0,
      ).copyWith(
        overlayColor: WidgetStatePropertyAll(Colors.white.withValues(alpha: .10)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size.fromHeight(AppSizes.ctaHeight),
        foregroundColor: AppColors.textPrimary,
        side: BorderSide(color: outline),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.button)),
        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.primary,
        textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.sm)),
      ),
    ),
    cardTheme: CardThemeData(
      color: AppColors.surface.withValues(alpha: .90),
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadii.card),
        side: BorderSide(color: outline),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surface.withValues(alpha: .88),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(AppRadii.sm), borderSide: BorderSide(color: outline)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(AppRadii.sm), borderSide: BorderSide(color: outline)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(AppRadii.sm), borderSide: const BorderSide(color: AppColors.primary, width: 1.5)),
      errorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(AppRadii.sm), borderSide: const BorderSide(color: AppColors.danger)),
      focusedErrorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(AppRadii.sm), borderSide: const BorderSide(color: AppColors.danger, width: 1.5)),
      hintStyle: const TextStyle(color: AppColors.textDisabled),
      labelStyle: const TextStyle(color: AppColors.textSecondary),
      prefixIconColor: AppColors.textSecondary,
      suffixIconColor: AppColors.textSecondary,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: const Color(0xF2081628),
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      height: 76,
      indicatorColor: AppColors.primary.withValues(alpha: .18),
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      labelTextStyle: const WidgetStatePropertyAll(
        TextStyle(color: AppColors.textSecondary, fontSize: 10, fontWeight: FontWeight.w700),
      ),
      iconTheme: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return IconThemeData(color: selected ? AppColors.primary : AppColors.textSecondary, size: selected ? 24 : 22);
      }),
    ),
    tabBarTheme: TabBarThemeData(
      dividerColor: Colors.transparent,
      indicatorSize: TabBarIndicatorSize.label,
      indicator: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: AppColors.primary.withValues(alpha: .16),
        border: Border.all(color: AppColors.primary.withValues(alpha: .30)),
      ),
      labelColor: AppColors.textPrimary,
      unselectedLabelColor: AppColors.textSecondary,
      labelStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800),
      unselectedLabelStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      overlayColor: WidgetStatePropertyAll(Colors.white.withValues(alpha: .05)),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: AppColors.surface,
      modalBackgroundColor: AppColors.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: AppColors.edge,
      elevation: 0,
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: AppColors.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 12,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.sm), side: BorderSide(color: outline)),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: AppColors.elevated,
      selectedColor: AppColors.primary.withValues(alpha: .18),
      side: BorderSide(color: outline),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
      labelStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 12, fontWeight: FontWeight.w600),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
    ),
    listTileTheme: ListTileThemeData(
      iconColor: AppColors.textSecondary,
      textColor: AppColors.textPrimary,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 5),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.sm)),
      tileColor: AppColors.surface.withValues(alpha: .32),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: AppColors.primary,
      linearTrackColor: AppColors.elevated,
      circularTrackColor: AppColors.elevated,
    ),
    textTheme: const TextTheme(
      bodyLarge: TextStyle(color: AppColors.textPrimary, fontSize: 16, height: 1.45),
      bodyMedium: TextStyle(color: AppColors.textPrimary, fontSize: 15, height: 1.5),
      bodySmall: TextStyle(color: AppColors.textSecondary, fontSize: 13, height: 1.4),
      titleLarge: TextStyle(color: AppColors.textPrimary, fontSize: 24, fontWeight: FontWeight.w800),
      titleMedium: TextStyle(color: AppColors.textPrimary, fontSize: 18, fontWeight: FontWeight.w800),
      titleSmall: TextStyle(color: AppColors.textPrimary, fontSize: 14, fontWeight: FontWeight.w700),
      labelLarge: TextStyle(color: AppColors.textPrimary, fontSize: 13, fontWeight: FontWeight.w800),
    ),
    dividerTheme: const DividerThemeData(color: AppColors.edge, thickness: 1, space: 1),
    switchTheme: SwitchThemeData(
      trackColor: WidgetStateProperty.resolveWith((states) => states.contains(WidgetState.selected) ? AppColors.primary.withValues(alpha: .48) : AppColors.elevated),
      thumbColor: WidgetStateProperty.resolveWith((states) => states.contains(WidgetState.selected) ? Colors.white : AppColors.textSecondary),
      trackOutlineColor: const WidgetStatePropertyAll(AppColors.edge),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith((states) => states.contains(WidgetState.selected) ? AppColors.primary : Colors.transparent),
      side: const BorderSide(color: AppColors.edge),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
    ),
    radioTheme: const RadioThemeData(fillColor: WidgetStatePropertyAll(AppColors.primary)),
    sliderTheme: SliderThemeData(
      activeTrackColor: AppColors.primary,
      inactiveTrackColor: AppColors.elevated,
      thumbColor: AppColors.primary,
      overlayColor: AppColors.primary.withValues(alpha: .12),
      trackHeight: 5,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: AppColors.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 20,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.hero), side: BorderSide(color: outline)),
      titleTextStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 18, fontWeight: FontWeight.w800),
      contentTextStyle: const TextStyle(color: AppColors.textSecondary, fontSize: 14, height: 1.45),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: AppColors.elevated,
      contentTextStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 14),
      behavior: SnackBarBehavior.floating,
      elevation: 10,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.button), side: BorderSide(color: outline)),
    ),
    timePickerTheme: TimePickerThemeData(
      backgroundColor: AppColors.surface,
      hourMinuteColor: AppColors.elevated,
      hourMinuteTextColor: AppColors.textPrimary,
      dialBackgroundColor: AppColors.elevated,
      dialTextColor: AppColors.textPrimary,
      entryModeIconColor: AppColors.primary,
      dayPeriodColor: AppColors.elevated,
      dayPeriodTextColor: AppColors.textPrimary,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.hero), side: BorderSide(color: outline)),
    ),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
      },
    ),
  );
}
