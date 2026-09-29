import 'dart:async';
import 'dart:math';

import 'package:google_mobile_ads/google_mobile_ads.dart';

import '../core/constants.dart';
import 'api_client.dart';
import 'native_bridge.dart';

/// Result of a complete watch flow.
enum AdOutcome { earned, dismissed, failedToLoad, failedToShow }

/// Rewarded ad manager — the ONLY legal coin faucet (PRD §15 / TRD §20).
///
/// HARD RULES (enforced here AND re-enforced natively):
///  1. A coin is granted only on the SDK's `onUserEarnedReward` callback —
///     never on load/open/close.
///  2. Every reward carries a unique `rewardKey`; the native ledger inserts
///     with a unique constraint, so a replayed callback cannot double-mint.
///  3. UI feedback happens only after the native insert succeeds.
class AdRewardManager {
  AdRewardManager._();

  static final AdRewardManager instance = AdRewardManager._();

  RewardedAd? _loaded;
  bool _loading = false;

  bool get isReady => _loaded != null;

  /// v2.9.12 r28 (user report: "ads dekha screen eita to kaj i kore na"):
  /// single load attempt frequently fails — the FIRST load after startup
  /// races MobileAds.initialize and a transient no-fill used to leave the
  /// faucet dead until the next screen visit. Retried here (bounded) with
  /// the v2.5.5 stuck-`_loading` guard preserved.
  static const int _loadAttempts = 3;
  static const Duration _loadRetryDelay = Duration(milliseconds: 1200);

  /// One load attempt; resolves true when an ad is cached.
  Future<bool> _loadOnce() {
    final completer = Completer<bool>();
    try {
      RewardedAd.load(
        adUnitId: AppConstants.rewardedAdUnitId,
        request: const AdRequest(),
        rewardedAdLoadCallback: RewardedAdLoadCallback(
          onAdLoaded: (ad) {
            _loaded = ad;
            if (!completer.isCompleted) completer.complete(true);
          },
          onAdFailedToLoad: (error) {
            _loaded = null;
            if (!completer.isCompleted) completer.complete(false);
          },
        ),
      );
    } catch (_) {
      _loaded = null;
      if (!completer.isCompleted) completer.complete(false);
    }
    return completer.future;
  }

  /// Preload a rewarded ad (called at startup, on the Coins screen and on
  /// the cage surface). Bounded retry — never leaves `_loading` stuck.
  Future<void> preload() async {
    if (_loaded != null || _loading) return;
    _loading = true;
    try {
      for (var attempt = 0; attempt < _loadAttempts; attempt++) {
        if (await _loadOnce()) return;
        if (attempt < _loadAttempts - 1) {
          await Future<void>.delayed(_loadRetryDelay);
        }
      }
    } finally {
      _loading = false;
    }
  }

  /// Show the ad and return the outcome. On `earned`, exactly one coin is
  /// awarded natively with an idempotent key.
  Future<AdOutcome> showAndEarn() async {
    // v2.9.12 r28 (user report: "ads dekha screen kaj kore na"): a tap
    // that arrived while the preload was still in flight used to fail
    // INSTANTLY with failedToLoad — the single most common "ad doesn't
    // work" report. Give the in-flight load a bounded window to land
    // (the button meanwhile shows its loading state) before giving up.
    if (_loaded == null) {
      unawaited(preload());
      final deadline = DateTime.now().add(const Duration(seconds: 8));
      while (_loaded == null && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
    }
    final ad = _loaded;
    if (ad == null) {
      return AdOutcome.failedToLoad;
    }
    _loaded = null; // consumed; preload the next in the background

    final completer = AdOutcomeCompleter();
    ad.fullScreenContentCallback = FullScreenContentCallback(
      onAdDismissedFullScreenContent: (ad) {
        ad.dispose();
        completer.completeDismissed();
      },
      onAdFailedToShowFullScreenContent: (ad, error) {
        ad.dispose();
        completer.completeFailedToShow();
      },
    );

    ad.show(
      onUserEarnedReward: (ad, reward) async {
        // The ONLY reward path. Unique key per callback event.
        // v2.5.5 audit fix: mark earning BEFORE the async native hop —
        // if the user closes the ad while the award round-trip is in
        // flight, the dismiss must defer to the reward verdict (the coin
        // IS credited natively; the UI must not claim "no coin").
        completer.markEarning();
        final rewardKey = 'adr_${_randomHex(16)}';
        final result = await NativeBridge.instance.awardAdCoin(rewardKey);
        // v2.5.7 (C-3): mirror the earn to the server ledger (best-effort,
        // idempotent by rewardKey) so the admin panel reflects reality.
        if (result.data == true) {
          unawaited(ApiClient.instance.reportCoinEarn(rewardKey));
        }
        completer.completeEarned(result.data == true);
      },
    );

    // Opportunistically start loading the next ad.
    unawaited(preload());

    return completer.outcome;
  }

  void dispose() {
    _loaded?.dispose();
    _loaded = null;
  }
}

/// Small helper that collapses SDK callbacks into one outcome future,
/// keeping "earned + dismissed" as `earned` and "dismissed without reward"
/// as `dismissed`.
class AdOutcomeCompleter {
  final Completer<AdOutcome> _completer = Completer<AdOutcome>();
  bool _done = false;

  /// v2.5.5 audit fix: a reward callback is in flight — a concurrent
  /// dismiss must NOT complete the future before the award verdict.
  bool _earning = false;

  Future<AdOutcome> get outcome => _completer.future;

  void markEarning() => _earning = true;

  void completeEarned(bool awarded) {
    if (_done) return;
    _done = true;
    _completer.complete(awarded ? AdOutcome.earned : AdOutcome.dismissed);
  }

  void completeDismissed() {
    // A stray late dismiss must not mask a pending reward verdict.
    if (_done || _earning) return;
    _done = true;
    _completer.complete(AdOutcome.dismissed);
  }

  void completeFailedToShow() {
    if (_earning) return; // reward still possible — wait for it
    if (_done) return;
    _done = true;
    _completer.complete(AdOutcome.failedToShow);
  }
}

String _randomHex(int bytes) {
  final rnd = Random.secure();
  return List.generate(bytes, (_) => rnd.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
}
