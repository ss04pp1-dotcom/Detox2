import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart' hide AppState;
import 'package:shared_preferences/shared_preferences.dart';

import 'core/constants.dart';
import 'core/router.dart';
import 'core/theme/mld_theme.dart';
import 'data/api_client.dart';
import 'data/app_state.dart';
import 'data/models.dart';
import 'data/native_bridge.dart';
import 'shared/mld_widgets.dart';

/// MAXLEVEL DETOX — entry point.
///
/// Architecture (PRD §29 / TRD §146):
///   Flutter UI  ->  MethodChannel  ->  Kotlin enforcement engine
///                                          -> Android system APIs
/// Flutter renders. Kotlin enforces. The cloud manages the product only.
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(statusBarColor: Colors.transparent, statusBarIconBrightness: Brightness.light),
  );
  // v2.5.5 audit fix: initialize() returns a Future that can fail — the
  // old fire-and-forget call produced an unhandled async error and raced
  // the first RewardedAd.load (the stuck-`_loading` trigger). Startup is
  // still never blocked on ads.
  unawaited(_initAds());
  runApp(const MldApp());
}

Future<void> _initAds() async {
  try {
    await MobileAds.instance.initialize();
  } catch (_) {
    // Ads unavailable on this device/SDK state — the coin faucet simply
    // reports failedToLoad until a later attempt. Never fatal.
  }
}

/// Inherited access to [AppState] without a DI framework.
class AppStateScope extends InheritedNotifier<AppState> {
  const AppStateScope({super.key, required AppState notifier, required super.child})
      : super(notifier: notifier);

  static AppState of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppStateScope>();
    assert(scope != null, 'AppStateScope missing from widget tree');
    return scope!.notifier!;
  }
}

class MldApp extends StatefulWidget {
  const MldApp({super.key});

  @override
  State<MldApp> createState() => _MldAppState();
}

class _MldAppState extends State<MldApp> {
  final AppState _appState = AppState();

  // v2.5.5 audit fix: messenger key for non-blocking, cloud-driven banners
  // (app-update nudge) that must work regardless of the current route.
  final GlobalKey<ScaffoldMessengerState> _messengerKey =
      GlobalKey<ScaffoldMessengerState>();

  /// v2.5.5 audit fix: periodic lightweight cloud sync (heartbeat + native
  /// event drain + flush). Fire-and-forget — it never gates the UI.
  Timer? _cloudSyncTimer;

  @override
  void initState() {
    super.initState();
    _bootstrapCloud();
    _bootstrapGrowth();
    _cloudSyncTimer = Timer.periodic(
      const Duration(minutes: 15),
      (_) => _periodicCloudSync(),
    );
  }

  @override
  void dispose() {
    _cloudSyncTimer?.cancel();
    _appState.dispose();
    super.dispose();
  }

  /// Cloud bootstrap is fire-and-forget: it must NEVER gate the UI or the
  /// enforcement experience (offline-first, TRD §59).
  ///
  /// v2.5.5 audit fix: the whole remote-config pipeline was dead code —
  /// fetchConfig/fetchFlags/ensureDeviceRegistered/sendHeartbeat/
  /// fetchAppVersion/applyRemoteConfig had zero callers, so RuntimeConfig
  /// never received server values and the offline event queue had no
  /// producers. Wire it end-to-end here (every step individually
  /// failure-tolerant — the app is fully functional offline).
  Future<void> _bootstrapCloud() async {
    await ApiClient.instance.restoreSession();
    await ApiClient.instance.ensureDeviceRegistered();

    // Remote config -> Kotlin RuntimeConfig (bounded + clamped natively).
    // Worker envelope (routes/app.ts getConfig): {version, config: {...}}.
    // The config doc is FLAT and its key names match RuntimeConfig's
    // validator; server-only keys (paymentsEnabled, bkash*, trialDays) are
    // dropped natively by design.
    final cfg = await ApiClient.instance.fetchConfig();
    if (cfg != null && cfg['config'] is Map) {
      await NativeBridge.instance.applyRemoteConfig(
          jsonEncode(Map<String, dynamic>.from(cfg['config'] as Map)));
    }

    // Feature flags (worker envelope: {flags: {key: bool}}). Deliberately
    // NOT forwarded through applyRemoteConfig: RuntimeConfig has no flags
    // surface — a `{'flags': ...}` payload would pass validation as an
    // all-defaults config and WIPE the cached remote values applied above.
    // They are kept client-side for future Dart-side gating.
    final flags = await ApiClient.instance.fetchFlags();
    if (flags != null && flags['flags'] is Map) {
      ApiClient.instance.featureFlags =
          Map<String, bool>.from(flags['flags'] as Map);
    }

    // v2.5.8 roadmap: server-pushed detection rules (reels/shorts
    // signatures). Forwarded raw to Kotlin — the native validator decides.
    await _syncDetectionRules();

    await _checkAppVersion();
    await _drainAndFlush();
    await _reconcileCoinGrants();
  }

  /// 15-minute lightweight sync — heartbeat + event drain + flush +
  /// server coin-grant reconcile (v2.5.7 C-3).
  Future<void> _periodicCloudSync() async {
    if (!ApiClient.instance.isAuthenticated) return;
    try {
      final s = _appState.state;
      await ApiClient.instance.sendHeartbeat(
        permissionSummary: {
          'accessibility': s.permissions.accessibility,
          'usageAccess': s.permissions.usageAccess,
          'overlay': s.permissions.overlay,
          'notifications': s.permissions.notifications,
        },
        // Live native state projection — the same values the UI renders.
        activeSession: s.sessionActive,
      );
      await _drainAndFlush();
      await _reconcileCoinGrants();
      // v2.5.8 roadmap: keep the reels/shorts detection signatures fresh so
      // a platform UI update is healed within one sync cycle (15 min).
      await _syncDetectionRules();
      // v2.5.8 roadmap: mirror the DP snapshot for the community
      // leaderboard (no-op unless the user opted in).
      await _syncLeaderboard();
      // v2.5.7 (H-6 runtime note): the purchase event channel has no
      // replay — if billing connected and restored BEFORE Flutter's
      // purchaseStream listener attached (possible at cold start), those
      // emits were dropped. Re-arming the restore here guarantees every
      // completed purchase reaches the Worker verify endpoint within one
      // sync cycle; verify is idempotent server-side.
      await NativeBridge.instance.restorePurchases();
    } catch (_) {
      // Cloud sync must never gate or crash the UI (offline-first).
    }
  }

  /// v2.5.7 (C-3): pull server coin grants (admin adjustments / bonuses)
  /// and apply them to the immutable device ledger, idempotently. The
  /// device stays the spend authority (offline-first); the server is the
  /// grant authority — this closes the old gap where an admin's +500 coins
  /// never reached the user's balance.
  Future<void> _reconcileCoinGrants() async {
    if (!ApiClient.instance.isAuthenticated) return;
    try {
      final prefs = await SharedPreferences.getInstance();
      final lastSync = prefs.getString('mld_coin_grants_since');
      final res = await ApiClient.instance.fetchCoins(since: lastSync);
      if (res == null) return;
      final grants = (res['grants'] as List<dynamic>? ?? const [])
          .map((e) => Map<dynamic, dynamic>.from(e as Map))
          .toList();
      if (grants.isNotEmpty) {
        await NativeBridge.instance.applyServerCoinGrants(grants);
      }
      final serverTime = res['serverTime'] as String?;
      if (serverTime != null && serverTime.isNotEmpty) {
        await prefs.setString('mld_coin_grants_since', serverTime);
      }
    } catch (_) {
      // best-effort mirror sync — never blocks anything
    }
  }

  /// v2.5.8 roadmap: pull the published detection-rules version and push
  /// the ruleset to the native validator. Cheap when nothing changed (the
  /// worker always returns a full doc; Kotlin swaps atomically after its
  /// own strict validation, so a bad doc can never reach the detector).
  Future<void> _syncDetectionRules() async {
    if (!ApiClient.instance.isAuthenticated) return;
    try {
      final res = await ApiClient.instance.fetchDetectionRules();
      if (res == null) return;
      final rules = res['rules'];
      if (rules is Map) {
        final doc = Map<String, dynamic>.from(rules);
        // Version gate: a ruleset published for a NEWER app (schema the
        // native validator of THIS build may not fully understand) is
        // skipped — the active ruleset stays as-is. Kotlin still rejects
        // unknown shapes, but gating here keeps the last-known-good doc
        // cached instead of hard-failing validation on every sync.
        final minAppVersion = doc['minAppVersion'] as String?;
        if (minAppVersion != null && minAppVersion.isNotEmpty &&
            _isNewerThan(minAppVersion)) {
          debugPrint(
              '[mld] detection rules v${res['version']} need app >= $minAppVersion (have ${AppConstants.appVersion}) — skipped');
          return;
        }
        // The inner doc gets the envelope's version attached so the native
        // status call can report it (Kotlin tolerates the extra key).
        final version = res['version'];
        if (version is num) doc['version'] = version.toInt();
        await NativeBridge.instance.applyDetectionRules(jsonEncode(doc));
      }
    } catch (_) {
      // offline-first: compiled defaults keep detection alive
    }
  }

  /// v2.5.8 roadmap: mirror the DP snapshot to the community leaderboard.
  /// Only runs after the user opted in (the toggle in the Leaderboard tab
  /// persists the flag; the server keeps opted-out rows invisible).
  Future<void> _syncLeaderboard() async {
    if (!ApiClient.instance.isAuthenticated) return;
    try {
      final prefs = await SharedPreferences.getInstance();
      if (prefs.getBool('mld_leaderboard_opted_in') != true) return;
      final snapshot = await NativeBridge.instance.getLeaderboardSnapshot();
      if (snapshot == null) return;
      await ApiClient.instance.syncLeaderboardSnapshot(snapshot);
    } catch (_) {
      // best-effort mirror — never blocks anything
    }
  }

  /// Drain the native AnalyticsOut queue into the offline event queue,
  /// then flush. v2.5.5 audit fix: native enforcement events previously had
  /// no path to the server (enqueueEvent had zero producers).
  Future<void> _drainAndFlush() async {
    try {
      final events = await NativeBridge.instance.drainAnalyticsEvents();
      for (final e in events) {
        final type = _normalizeEventType(e['type'] ?? '');
        if (type == null) continue; // worker rejects unknown types
        final payload = e['payload'] is Map
            ? Map<String, dynamic>.from(e['payload'] as Map)
            : <String, dynamic>{};
        await ApiClient.instance.enqueueEvent(type, payload);
      }
    } catch (_) {
      // bridge hiccup — the events stay queued natively for the next drain
    }
    await ApiClient.instance.flushEventQueue();
  }

  /// The worker's POST /events accepts a frozen event-type enum
  /// (routes/app.ts EVENT_TYPES). Native emits a superset (DP_*, PRIME_*,
  /// SESSION_PAUSED, mode-suffixed SESSION_STARTED_*) — forwarding any of
  /// those would 400 the WHOLE batch and brick the queue, so they are
  /// normalized or dropped here.
  static const Set<String> _acceptedEventTypes = {
    'SESSION_STARTED',
    'SESSION_COMPLETED',
    'SESSION_BAILED_OUT',
    'TEMP_UNLOCK_STARTED',
    'TEMP_UNLOCK_EXPIRED',
    'SHORTS_WARNING',
    'CAGE_ACTIVATED',
    'CAGE_RELEASED',
    'ALARM_TRIGGERED',
    'ALARM_COMPLETED',
    'PERMISSION_CHANGED',
    'RECOVERY_TRIGGERED',
  };

  static String? _normalizeEventType(String raw) {
    if (raw == 'SESSION_STARTED_STUDY' || raw == 'SESSION_STARTED_DETOX') {
      return 'SESSION_STARTED';
    }
    return _acceptedEventTypes.contains(raw) ? raw : null;
  }

  /// v2.5.5 audit fix: fetchAppVersion was never called. Public endpoint
  /// (works pre-login).
  ///
  /// v2.5.7 (K-4): a FORCED update (admin-flagged forceUpdate or a version
  /// below the server minimum) now shows a NON-DISMISSIBLE full-screen
  /// dialog with a Play Store button — the old 6-second SnackBar scrolled
  /// away and the app stayed usable, which contradicted the "force"
  /// semantics on an enforcement product. Soft updates keep the SnackBar.
  Future<void> _checkAppVersion() async {
    final v = await ApiClient.instance.fetchAppVersion();
    if (v == null) return; // offline or no policy row — nothing to do
    final latest = v['latest'] as String?;
    final minimum = v['minimum'] as String?;
    final message = v['message'] as String? ?? '';
    if (!_isNewerThan(latest) && !_isNewerThan(minimum)) return;
    // The worker's envelope also carries an explicit `forceUpdate` bool
    // (routes/app.ts getAppVersion) — honor it on top of the version math
    // so an admin-flagged force update is never downgraded to a soft nudge.
    final forced = (v['forceUpdate'] as bool? ?? false) || _isNewerThan(minimum);
    debugPrint(
        '[mld] app update available: current=${AppConstants.appVersion} latest=$latest minimum=$minimum force=$forced');

    if (forced) {
      _pendingForcedUpdate = (
        message: message.trim(),
        latest: latest ?? minimum ?? '',
      );
      _maybeShowForcedUpdate();
      return;
    }

    try {
      _messengerKey.currentState?.showSnackBar(
        SnackBar(
          content: Text(message.trim().isEmpty
              ? 'An update is available.'
              : message.trim()),
          duration: const Duration(seconds: 6),
        ),
      );
    } catch (_) {
      // no messenger yet — the debugPrint above still records it
    }
  }

  ({String message, String latest})? _pendingForcedUpdate;
  bool _forcedUpdateShown = false;

  /// Show the blocking dialog once the navigator is alive. Retries briefly
  /// until MaterialApp has a context (bootstrap may finish before the first
  /// frame). K-4: barrierDismissible=false + PopScope block the back button.
  void _maybeShowForcedUpdate({int attempt = 0}) {
    final update = _pendingForcedUpdate;
    if (update == null || _forcedUpdateShown) return;
    final context = _messengerKey.currentContext;
    if (context == null) {
      if (attempt < 40) {
        // navigator not ready yet (splash still routing) — retry shortly
        Future.delayed(const Duration(milliseconds: 500),
            () => _maybeShowForcedUpdate(attempt: attempt + 1));
      }
      return;
    }
    if (!context.mounted) return;
    final navigator = Navigator.of(context, rootNavigator: true);
    _forcedUpdateShown = true;
    showDialog<void>(
      context: navigator.context,
      barrierDismissible: false, // forced — no tap-outside escape
      builder: (dialogContext) => PopScope<Object?>(
        // K-4: back button cannot dismiss a forced update either.
        canPop: false,
        child: AlertDialog(
          title: const Text('Update Required'),
          content: Text(update.message.isEmpty
              ? 'A required update is available. Please update MAXLEVEL DETOX to continue.'
              : update.message),
          actions: [
            FilledButton.icon(
              icon: const Icon(Icons.shop_outlined),
              label: const Text('Open Play Store'),
              onPressed: () {
                launchPlayStore();
              },
            ),
          ],
        ),
      ),
    );
  }

  /// Fire the Play Store page for this package. Implemented natively
  /// (market:// intent with the https://play.google.com fallback) — no
  /// url_launcher dependency needed for one fixed URL.
  static void launchPlayStore() {
    NativeBridge.instance.openPlayStorePage();
  }

  /// True when [other] is a strictly newer dotted version than the current
  /// build. Unparseable segments fail OPEN (no nag) — the worker's own
  /// compareVersions fails CLOSED for flags; this is display-only.
  static bool _isNewerThan(String? other) {
    if (other == null || other.isEmpty) return false;
    final cur = AppConstants.appVersion.split('.');
    final oth = other.split('.');
    for (var i = 0; i < oth.length && i < cur.length; i++) {
      final a = int.tryParse(oth[i]);
      final b = int.tryParse(cur[i]);
      if (a == null || b == null) return false;
      if (a != b) return a > b;
    }
    return oth.length > cur.length; // e.g. 2.5.6.1 vs 2.5.6
  }

  /// v2.2 Phase D growth bootstrap — also fire-and-forget:
  ///  - connect Play Billing (restores offline purchases)
  ///  - forward completed purchases to the Worker for server verification
  ///  - fetch announcements (pull model, no Firebase) and hand the unseen
  ///    ones to native for local notification delivery
  Future<void> _bootstrapGrowth() async {
    // Purchase verification loop: native emits, Worker verifies, server
    // grants. Nothing here trusts the client.
    NativeBridge.instance.purchaseStream.listen(
      (purchase) async {
        final productId = purchase['productId'] as String? ?? '';
        final purchaseToken = purchase['purchaseToken'] as String? ?? '';
        if (productId.isEmpty || purchaseToken.isEmpty) return;
        await ApiClient.instance.verifyPlayPurchase(
          productId: productId,
          purchaseToken: purchaseToken,
        );
      },
      // v2.5.5 audit fix: a malformed billing payload on the EventChannel
      // would otherwise escape as an unhandled zone error (the stateStream
      // equivalent is guarded in app_state.dart).
      onError: (Object e) {
        debugPrint('[mld] purchase stream error: $e');
      },
    );

    // Pull announcements once per app start; native dedupes + notifies.
    final raw = await ApiClient.instance.fetchAnnouncements();
    if (raw.isEmpty) return;
    final items = raw.map(AnnouncementItem.fromJson).toList();
    await NativeBridge.instance.deliverAnnouncements(items);
  }

  @override
  Widget build(BuildContext context) {
    return AppStateScope(
      notifier: _appState,
      child: MaterialApp(
        scaffoldMessengerKey: _messengerKey,
        title: AppConstants.appName,
        debugShowCheckedModeBanner: false,
        theme: buildMldTheme(),
        builder: (context, child) => MLDAppBackdrop(child: child ?? const SizedBox.shrink()),
        initialRoute: AppConstants.routeSplash,
        routes: buildAppRoutes(),
      ),
    );
  }
}

/// Splash / routing gate. Reads native state once, then routes:
///   onboarding incomplete -> Onboarding
///   pact not accepted      -> Pact
///   session/cage active    -> Active Session (also after process death)
///   otherwise              -> Dashboard
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  @override
  void initState() {
    super.initState();
    _resolve();
  }

  Future<void> _resolve() async {
    // Wait for the first native state push (or a short timeout).
    final state = AppStateScope.of(context);
    final deadline = DateTime.now().add(const Duration(seconds: 4));
    while (!state.bootstrapped && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 60));
    }

    if (!mounted) return;
    final s = state.state;
    String target = AppConstants.routeShell;
    if (!s.onboardingComplete) {
      target = AppConstants.routeOnboarding;
    } else if (!s.pactAccepted) {
      target = AppConstants.routePact;
    } else if (s.sessionActive || s.cageActive) {
      target = AppConstants.routeActiveSession;
    }

    // v2.5 r9: companion deeplink — a Sinthia check-in notification was
    // tapped (flag set by MainActivity). Wins over the default shell.
    if (target == AppConstants.routeShell) {
      try {
        final pending = await NativeBridge.instance.consumeOpenCompanion();
        if (pending) target = AppConstants.routeCompanion;
      } catch (_) {
        // bridge not ready yet — plain shell is fine
      }
    }
    if (!mounted) return;
    Navigator.of(context).pushReplacementNamed(target);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TweenAnimationBuilder<double>(
              tween: Tween(begin: 0.85, end: 1.0),
              duration: const Duration(milliseconds: 600),
              curve: Curves.easeOutBack,
              builder: (context, scale, child) =>
                  Transform.scale(scale: scale, child: child),
              child: const _SplashLogo(),
            ),
            const SizedBox(height: 32),
            Text(
              AppConstants.tagline,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    letterSpacing: 4,
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 48),
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2, color: AppColorsI.primary),
            ),
          ],
        ),
      ),
    );
  }
}

class _SplashLogo extends StatelessWidget {
  const _SplashLogo();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 88,
          height: 88,
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [AppColorsI.primary, AppColorsI.primaryDim],
            ),
            borderRadius: BorderRadius.circular(24),
            boxShadow: [
              BoxShadow(
                color: AppColorsI.primary.withValues(alpha: 0.35),
                blurRadius: 40,
                spreadRadius: 2,
              ),
            ],
          ),
          child: const Icon(Icons.shield_outlined, size: 44, color: Colors.white),
        ),
        const SizedBox(height: 20),
        Text(
          'MAXLEVEL\nDETOX',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
                fontSize: 30,
                fontWeight: FontWeight.w900,
                letterSpacing: 2,
                height: 1.05,
              ),
        ),
      ],
    );
  }
}

/// Tiny indirection so the splash can use colors without a tokens import
/// cycle in this file.
abstract final class AppColorsI {
  static const Color primary = Color(0xFF6366F1);
  static const Color primaryDim = Color(0xFF4F46E5);
}
