import 'dart:async';

import 'package:flutter/foundation.dart';

import 'native_bridge.dart';
import 'models.dart';

/// App-wide observable state. A thin ChangeNotifier over the native state
/// stream — Flutter renders, Kotlin decides (TRD §1).
///
/// This class holds NO security authority: every value here is a projection
/// of native state that can be re-validated at any time.
class AppState extends ChangeNotifier {
  AppState() {
    _bootstrap();
  }

  DeviceState _state = DeviceState.empty;
  bool _bootstrapped = false;
  StreamSubscription<DeviceState>? _sub;

  /// v2.5.5 audit fix: latched by BailoutScreen while its post-bailout
  /// shell navigation is in flight, so the active-session stream watcher
  /// does not ALSO push the completion celebration over it (the two
  /// pushNamedAndRemoveUntil calls raced: a user who paid 500 coins could
  /// see "COMMITMENT COMPLETE — you finished what you started" flash
  /// before the shell replaced it). Cleared by the bailout screen on
  /// failure and on dispose.
  bool suppressCompletionRedirect = false;

  DeviceState get state => _state;
  bool get bootstrapped => _bootstrapped;

  SessionSnapshot? get session => _state.session;
  bool get sessionActive => _state.sessionActive;
  bool get cageActive => _state.cageActive;
  int get coins => _state.coins;
  PermissionSummary get permissions => _state.permissions;
  ShortsState get shorts => _state.shorts;
  ProgressSummary? get progress => _state.progress;

  void _bootstrap() {
    _sub = NativeBridge.instance.stateStream.listen(
      (s) {
        _state = s;
        _bootstrapped = true;
        notifyListeners();
      },
      onError: (Object e) {
        if (kDebugMode) {
          // ignore: avoid_print
          print('state stream error: $e');
        }
      },
    );

    // v2.3 r7 WATCHDOG (the r6 infinite-spinner defense): if the native
    // stream never delivers (engine hiccup, slow cold start), fall back
    // to a direct method-channel fetch so the UI ALWAYS unblocks. The
    // stream keeps running — the next native push still wins.
    _bootstrapWatchdog = Timer(const Duration(seconds: 4), () {
      if (!_bootstrapped) {
        refresh().then((_) {
          if (!_bootstrapped) {
            _bootstrapped = true;
            notifyListeners();
          }
        });
      }
    });
  }

  Timer? _bootstrapWatchdog;

  /// Force-refresh from native (used when returning from Android settings).
  Future<void> refresh() async {
    final perms = await NativeBridge.instance.getPermissionState();
    _state = DeviceState(
      session: _state.session,
      cage: _state.cage,
      tempUnlock: _state.tempUnlock,
      shorts: _state.shorts,
      coins: _state.coins,
      permissions: perms,
      onboardingComplete: _state.onboardingComplete,
      pactAccepted: _state.pactAccepted,
      progress: _state.progress,
    );
    // v2.3 r7: a successful direct fetch is proof the bridge is alive —
    // never leave the UI on a loading spinner once we can build state.
    _bootstrapped = true;
    notifyListeners();
  }

  // v2.5.5 audit fix: surface the native verdict — the shorts settings
  // screen previously ignored it and a refused write silently reverted on
  // the next state push.
  Future<bool> toggleShorts(bool enabled) async {
    return NativeBridge.instance.setShortsEnabled(enabled);
  }

  Future<bool> toggleShortsPlatform(String packageName, bool enabled) async {
    return NativeBridge.instance.setShortsPlatform(packageName, enabled);
  }

  @override
  void dispose() {
    _bootstrapWatchdog?.cancel();
    _sub?.cancel();
    super.dispose();
  }
}
