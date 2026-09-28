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

  /// Preload a rewarded ad (called opportunistically on Coins screen).
  /// v2.5.5 audit fix: wrapped in try/catch — a throwing `RewardedAd.load`
  /// (e.g. racing the unawaited MobileAds.initialize) used to leave
  /// `_loading` stuck true forever, permanently bricking the coin faucet
  /// until process restart, plus an unhandled async error at every
  /// fire-and-forget call site.
  Future<void> preload() async {
    if (_loaded != null || _loading) return;
    _loading = true;
    try {
      await RewardedAd.load(
        adUnitId: AppConstants.rewardedAdUnitId,
        request: const AdRequest(),
        rewardedAdLoadCallback: RewardedAdLoadCallback(
          onAdLoaded: (ad) {
            _loaded = ad;
            _loading = false;
          },
          onAdFailedToLoad: (error) {
            _loaded = null;
            _loading = false;
          },
        ),
      );
    } catch (_) {
      _loaded = null;
      _loading = false; // never leave the guard stuck
    }
  }

  /// Show the ad and return the outcome. On `earned`, exactly one coin is
  /// awarded natively with an idempotent key.
  Future<AdOutcome> showAndEarn() async {
    final ad = _loaded;
    if (ad == null) {
      unawaited(preload());
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
