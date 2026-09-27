/// Global product constants. Remote config can override the *bounded* values
/// at runtime (validated + clamped natively in RuntimeConfig); these are the
/// built-in defaults that ship with the app.
abstract final class AppConstants {
  static const String appName = 'MAXLEVEL DETOX';
  static const String tagline = 'TAKE BACK CONTROL';
  // v2.5.5 audit fix: bumped with the audit-fix release (android versionCode
  // 21 / versionName 2.5.6 in app/build.gradle already match).
  static const String appVersion = '2.7.0';

  // --- Navigation route names ---
  static const String routeSplash = '/';
  static const String routeOnboarding = '/onboarding';
  static const String routePact = '/onboarding/pact';
  static const String routePermissions = '/permissions';
  static const String routeShell = '/shell';
  static const String routeStudySetup = '/study';
  static const String routeDetoxSetup = '/detox';
  static const String routeDetoxConfirm = '/detox/confirm';
  static const String routeActivation = '/activation';
  static const String routeActiveSession = '/session';
  static const String routeCompletion = '/session/complete';
  static const String routeRecovery = '/recovery';
  static const String routeShortsSettings = '/shorts';
  static const String routeCoins = '/coins';
  static const String routeCoinHistory = '/coins/history';
  static const String routeTempUnlock = '/unlock';
  static const String routeBailout = '/bailout';
  static const String routeAlarmSetup = '/alarm';
  static const String routeInsights = '/insights';
  static const String routeHistory = '/history';
  static const String routeSettings = '/settings';
  // v2.5.7 (H-2): user-side support tickets.
  static const String routeSupport = '/settings/support';
  static const String routeAppRules = '/settings/app-rules';
  static const String routeSchedules = '/settings/schedules';
  static const String routePermissionCenter = '/settings/permissions';
  static const String routeProgress = '/progress';

  // v2.2 Phase D — growth & monetization.
  static const String routePaywall = '/pro';
  static const String routeBkash = '/payments/bkash';
  static const String routeWidgets = '/settings/widgets';

  // v2.2.1 — diagnostics.
  static const String routeSystemHealth = '/settings/health';

  // v2.5 r9 — Social Sentry parity: discipline engines + engagement.
  static const String routeLockMyPhone = '/lock-my-phone';
  static const String routeMonk = '/monk';
  static const String routePrime = '/prime';
  static const String routeSafety = '/safety-pause';
  static const String routeEmergencyCodes = '/emergency-codes';
  static const String routeAccount = '/account';
  static const String routeCompanion = '/companion';
  static const String routeTasks = '/tasks';
  static const String routeCommunity = '/community';

  // --- MethodChannel (TRD §40) ---
  static const String nativeChannel = 'com.maxleveldetox/native';
  static const String nativeStateStream = 'com.maxleveldetox/statestream';

  // --- Coin economy (PRD §15/§19; remote-config bounded) ---
  static const int coinsPerAd = 1;
  static const int tempUnlockCost = 5;
  static const int tempUnlockMinutes = 5;
  static const int bailoutCost = 500;

  // --- Shorts blocker (PRD §12–14) ---
  static const int shortsWarningLimit = 5;
  static const int cageDurationSeconds = 30 * 60;

  // --- Sessions ---
  static const int defaultStudyMinutes = 60;
  static const int defaultDetoxMinutes = 120;

  // --- Ads ---
  // v2.5.5 audit fix: the rewarded unit is now injectable per build —
  //   flutter build apk --dart-define=MLD_REWARDED_UNIT=ca-app-pub-XXXX/YYYY
  // The default is Google's PUBLIC SAMPLE unit (test inventory only; no
  // fills in production — swap before a Play release, same as the AdMob
  // App ID in android/app/build.gradle's MLD_ADMOB_APP_ID property).
  static const String rewardedAdUnitId = String.fromEnvironment(
    'MLD_REWARDED_UNIT',
    defaultValue: 'ca-app-pub-3940256099942544/5224354917',
  );

  // --- API ---
  // v2.5.5 audit fix: base URL injectable per build —
  //   flutter build apk --dart-define=MLD_API_BASE=https://staging.../api/v1
  static const String apiBaseUrl = String.fromEnvironment(
    'MLD_API_BASE',
    defaultValue: 'https://api.maxleveldetox.com/api/v1',
  );

  // --- Google Sign-In (v2.5.5 audit fix: the sign-in flow existed on the
  //     Worker but had NO client-side entry point — Play purchases could
  //     never be granted. Set the OAuth 2.0 WEB client id of the backend
  //     audience at build time, or leave empty to disable the button):
  //   flutter build apk --dart-define=MLD_GOOGLE_SERVER_CLIENT_ID=1234-abc.apps.googleusercontent.com
  static const String googleServerClientId = String.fromEnvironment(
    'MLD_GOOGLE_SERVER_CLIENT_ID',
  );
}
