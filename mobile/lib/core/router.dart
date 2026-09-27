import 'package:flutter/material.dart';

import 'constants.dart';
import '../features/account/account_screen.dart';
import '../features/companion/companion_screen.dart';
import '../features/community/community_screen.dart';
import '../features/emergency_codes/emergency_codes_screen.dart';
import '../features/lock/lock_my_phone_screen.dart';
import '../features/monk/monk_mode_screen.dart';
import '../features/prime/prime_commit_screen.dart';
import '../features/safety/safety_pause_screen.dart';
import '../features/tasks/tasks_screen.dart';
import '../features/alarm/alarm_setup_screen.dart';
import '../features/bailout/bailout_screen.dart';
import '../features/coins/coin_history_screen.dart';
import '../features/coins/coins_screen.dart';
import '../features/dashboard/dashboard_screen.dart';
import '../features/detox/detox_confirm_screen.dart';
import '../features/insights/insights_screen.dart';
import '../features/onboarding/onboarding_screen.dart';
import '../features/permissions/permission_setup_screen.dart';
import '../features/payments/bkash_screen.dart';
import '../features/paywall/paywall_screen.dart';
import '../features/progress/progress_screen.dart';
import '../features/session/activation_screen.dart';
import '../features/session/active_session_screen.dart';
import '../features/session/completion_screen.dart';
import '../features/settings/app_rules_screen.dart';
import '../features/settings/permission_center_screen.dart';
import '../features/settings/schedules_screen.dart';
import '../features/settings/settings_screen.dart';
import '../features/settings/support_screen.dart';
import '../features/settings/system_health_screen.dart';
import '../features/settings/widgets_screen.dart';
import '../features/shorts/shorts_settings_screen.dart';
import '../features/study/study_setup_screen.dart';
import '../features/unlock/temp_unlock_screen.dart';
import '../main.dart';

/// Named-route table. The splash gate (MldApp) decides the entry route based
/// on native state (onboarding done? pact accepted? session active?) before
/// any of these are reachable.
Map<String, WidgetBuilder> buildAppRoutes() {
  return {
    AppConstants.routeSplash: (_) => const SplashScreen(),
    AppConstants.routeOnboarding: (_) => const OnboardingScreen(),
    AppConstants.routePact: (_) => const PactScreen(),
    AppConstants.routePermissions: (_) => const PermissionSetupScreen(),
    AppConstants.routeShell: (_) => const DashboardScreen(),
    AppConstants.routeStudySetup: (_) => const StudySetupScreen(),
    AppConstants.routeDetoxSetup: (_) => const DetoxSetupScreen(),
    AppConstants.routeDetoxConfirm: (_) => const DetoxConfirmScreen(),
    AppConstants.routeActivation: (_) => const ActivationScreen(),
    AppConstants.routeActiveSession: (_) => const ActiveSessionScreen(),
    AppConstants.routeCompletion: (_) => const CompletionScreen(),
    AppConstants.routeRecovery: (_) => const RecoveryScreen(),
    AppConstants.routeShortsSettings: (_) => const ShortsSettingsScreen(),
    AppConstants.routeCoins: (_) => const CoinsScreen(),
    AppConstants.routeCoinHistory: (_) => const CoinHistoryScreen(),
    AppConstants.routeTempUnlock: (_) => const TempUnlockScreen(),
    AppConstants.routeBailout: (_) => const BailoutScreen(),
    AppConstants.routeAlarmSetup: (_) => const AlarmSetupScreen(),
    AppConstants.routeInsights: (_) => const InsightsScreen(),
    AppConstants.routeHistory: (_) => const HistoryScreen(),
    AppConstants.routeSettings: (_) => const SettingsScreen(),
    AppConstants.routeSupport: (_) => const SupportScreen(),
    AppConstants.routeAppRules: (_) => const AppRulesScreen(),
    AppConstants.routeSchedules: (_) => const SchedulesScreen(),
    AppConstants.routePermissionCenter: (_) => const PermissionCenterScreen(),
    AppConstants.routeProgress: (_) => const ProgressScreen(),

    // v2.2 Phase D — growth & monetization.
    AppConstants.routePaywall: (_) => const PaywallScreen(),
    AppConstants.routeBkash: (_) => const BkashScreen(),
    AppConstants.routeWidgets: (_) => const WidgetsScreen(),

    // v2.2.1 — diagnostics.
    AppConstants.routeSystemHealth: (_) => const SystemHealthScreen(),
    AppConstants.routeLockMyPhone: (_) => const LockMyPhoneScreen(),
    AppConstants.routeMonk: (_) => const MonkModeScreen(),
    AppConstants.routePrime: (_) => const PrimeCommitScreen(),
    AppConstants.routeSafety: (_) => const SafetyPauseScreen(),
    AppConstants.routeEmergencyCodes: (_) => const EmergencyCodesScreen(),
    AppConstants.routeAccount: (_) => const AccountScreen(),
    AppConstants.routeCompanion: (_) => const CompanionScreen(),
    AppConstants.routeTasks: (_) => const TasksScreen(),
    AppConstants.routeCommunity: (_) => const CommunityScreen(),
  };
}
