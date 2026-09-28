import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/constants.dart';
import 'native_bridge.dart';

/// Cloudflare Worker API client (frozen contract v1).
///
/// OFFLINE-FIRST (TRD §59 / PRD §50): every network failure degrades
/// gracefully. Enforcement events queue locally and flush later; config
/// fetch failures fall back to the last native-cached config. The app NEVER
/// requires this client for Study/Detox/Cage/Alarm/coins to work.
class ApiClient {
  ApiClient._();

  static final ApiClient instance = ApiClient._();

  String? _accessToken;
  String? _refreshToken;
  String? _deviceId;
  String? _userId;

  /// v2.5.7 (K-2): Keystore-encrypted token storage. SharedPreferences kept
  /// the 30-day refresh token in plaintext XML — readable by ADB backups and
  /// on rooted devices. Tokens now live in flutter_secure_storage; a
  /// one-time migration wipes the old prefs copy.
  static const FlutterSecureStorage _secure = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );
  static const String _kSecureAuth = 'mld_auth_v2';

  String? get deviceId => _deviceId;
  String? get userId => _userId;
  bool get isAuthenticated => _accessToken != null;

  // v2.5.7 (F-1): the last failure, surfaced for UI messaging. Offline-first
  // still returns null from every method, but login/paywall/support screens
  // can now distinguish offline vs. wrong-credentials vs. rate-limited
  // instead of showing a generic "did not work".
  // v2.7.1: annotations dropped — these are runtime UI surfaces, not test-only.
  String? lastErrorCode;
  String? lastErrorMessage;

  /// Human-readable message for the most recent failure (null when the last
  /// call succeeded or it was a pure network timeout).
  String? get lastError =>
      lastErrorCode == null ? null : (lastErrorMessage ?? lastErrorCode);

  void clearLastError() {
    lastErrorCode = null;
    lastErrorMessage = null;
  }

  // v2.5.5 audit fix: last fetched server feature flags (GET /flags ->
  // {flags: {key: bool}}). Held CLIENT-SIDE only — they are deliberately NOT
  // pushed through NativeBridge.applyRemoteConfig because Kotlin's
  // RuntimeConfig has no flags surface and would treat a {'flags': ...}
  // payload as an all-defaults config, wiping the cached remote values.
  Map<String, bool> featureFlags = {};

  // ---------------------------------------------------------------------
  // Auth (Email/Password & Google Sign-In -> Worker session)
  // ---------------------------------------------------------------------

  Future<bool> registerWithEmail(String email, String password, {String? displayName}) async {
    clearLastError();
    final body = <String, dynamic>{
      'email': email.trim().toLowerCase(),
      'password': password,
    };
    if (displayName != null && displayName.trim().isNotEmpty) {
      body['displayName'] = displayName.trim();
    }
    final res = await _post('/auth/register', body, authenticated: false);
    if (res == null) return false;
    return _handleAuthResponse(res);
  }

  Future<bool> loginWithEmail(String email, String password) async {
    clearLastError();
    final body = <String, dynamic>{
      'email': email.trim().toLowerCase(),
      'password': password,
    };
    final res = await _post('/auth/login', body, authenticated: false);
    if (res == null) return false;
    return _handleAuthResponse(res);
  }

  Future<bool> loginWithGoogle(String idToken) async {
    clearLastError();
    final res = await _post('/auth/google', {'credential': idToken}, authenticated: false);
    if (res == null) return false;
    return _handleAuthResponse(res);
  }

  bool _handleAuthResponse(Map<String, dynamic> res) {
    final tokens = res['tokens'] as Map<dynamic, dynamic>?;
    if (tokens == null) return false;
    _accessToken = tokens['accessToken'] as String?;
    _refreshToken = tokens['refreshToken'] as String?;
    final user = res['user'] as Map<dynamic, dynamic>?;
    _userId = user?['id'] as String?;
    unawaited(_persistSession());
    return _accessToken != null;
  }

  Future<void> logout() async {
    if (_accessToken != null) {
      await _post('/auth/logout', null);
    }
    _accessToken = null;
    _refreshToken = null;
    _userId = null;
    try {
      await _secure.delete(key: _kSecureAuth);
      // v2.5.7 (K-2): also wipe any legacy plaintext copy.
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('mld_auth');
    } catch (_) {
      // storage unavailable — the in-memory copy is already cleared
    }
  }

  Future<void> restoreSession() async {
    // v2.5.7 (K-2): read from the encrypted store, migrating any legacy
    // plaintext SharedPreferences copy exactly once.
    try {
      final raw = await _secure.read(key: _kSecureAuth);
      if (raw != null) {
        _applyStoredSession(raw);
        return;
      }
    } catch (_) {
      // secure storage unavailable (rare keystore faults) — fall through to
      // the legacy copy so the user is not logged out by an infrastructure
      // hiccup; the next persist re-writes the encrypted copy.
    }

    final prefs = await SharedPreferences.getInstance();
    final legacy = prefs.getString('mld_auth');
    if (legacy == null) return;
    _applyStoredSession(legacy);
    if (_accessToken != null) {
      // Migrate + wipe the plaintext original (K-2).
      await _persistSession();
      await prefs.remove('mld_auth');
    }
  }

  void _applyStoredSession(String raw) {
    try {
      final map = jsonDecode(raw) as Map<String, dynamic>;
      _accessToken = map['access'] as String?;
      _refreshToken = map['refresh'] as String?;
      _deviceId = map['deviceId'] as String?;
      _userId = map['userId'] as String?;
    } catch (_) {
      _accessToken = null;
      _refreshToken = null;
      _userId = null;
    }
  }

  Future<void> _persistSession() async {
    final payload = jsonEncode({
      'access': _accessToken,
      'refresh': _refreshToken,
      'deviceId': _deviceId,
      'userId': _userId,
    });
    try {
      await _secure.write(key: _kSecureAuth, value: payload);
    } catch (_) {
      // Never crash the flow on keystore faults — the session simply lives
      // in memory for this run (offline-first: enforcement never needs it).
    }
  }

  Future<bool> _tryRefresh() async {
    if (_refreshToken == null) return false;
    final res = await _post('/auth/refresh', {'refreshToken': _refreshToken}, authenticated: false);
    if (res == null) return false;
    final tokens = res['tokens'] as Map<dynamic, dynamic>?;
    if (tokens == null) return false;
    _accessToken = tokens['accessToken'] as String?;
    _refreshToken = tokens['refreshToken'] as String?;
    await _persistSession();
    return _accessToken != null;
  }

  // ---------------------------------------------------------------------
  // Device
  // ---------------------------------------------------------------------

  Future<void> ensureDeviceRegistered() async {
    if (!isAuthenticated || _deviceId != null) return;
    // v2.5.7 (K-3): real device identity. The old hardcoded
    // manufacturer='unknown' / androidVersion='unknown' made the admin
    // Devices page a wall of "unknown/unknown/unknown" rows. Build fields
    // come from the native side (Build.MANUFACTURER/MODEL/VERSION.RELEASE)
    // with sane fallbacks when the bridge is not ready yet.
    String model = 'android';
    String manufacturer = 'unknown';
    String androidVersion = 'unknown';
    try {
      final info = await NativeBridge.instance.getDeviceInfo();
      model = info['model'] as String? ?? model;
      manufacturer = info['manufacturer'] as String? ?? manufacturer;
      androidVersion = info['androidVersion'] as String? ?? androidVersion;
    } catch (_) {
      // bridge hiccup — register with fallbacks rather than never registering
    }
    final res = await _post('/devices/register', {
      'model': model,
      'manufacturer': manufacturer,
      'androidVersion': androidVersion,
      'appVersion': AppConstants.appVersion,
    });
    if (res != null) {
      _deviceId = res['deviceId'] as String?;
      await _persistSession();
    }
  }

  Future<void> sendHeartbeat({
    required Map<String, bool> permissionSummary,
    required bool activeSession,
  }) async {
    if (!isAuthenticated) return;
    await _post('/devices/heartbeat', {
      'appVersion': AppConstants.appVersion,
      'permissionSummary': permissionSummary,
      'activeSession': activeSession,
    });
  }

  // ---------------------------------------------------------------------
  // Config / flags / announcements / version
  // ---------------------------------------------------------------------

  Future<Map<String, dynamic>?> fetchConfig() => _get('/config');

  Future<Map<String, dynamic>?> fetchFlags() => _get('/flags');

  // v2.5.8 roadmap: dynamic detection rules (server-pushed reels/shorts
  // signatures). Envelope: {version, rules:{schemaVersion,minAppVersion,
  // platforms:{...}}}. Forwarded to Kotlin for strict validation — never
  // interpreted in Dart.
  Future<Map<String, dynamic>?> fetchDetectionRules() =>
      _get('/detection/rules');

  // v2.5.5 audit fix: /app/version is a PUBLIC worker route (no
  // requireUser — "force-update must work pre-login", routes/app.ts), so
  // fetch it unauthenticated or the update nudge would be skipped for
  // signed-out users (the default-authenticated GET returns null without
  // an access token).
  Future<Map<String, dynamic>?> fetchAppVersion() =>
      _get('/app/version', authenticated: false);

  /// Active announcements (pull model — no push dependency by design).
  Future<List<Map<dynamic, dynamic>>> fetchAnnouncements(
      {String? since}) async {
    // v2.5.5 audit fix: `since` is an ISO timestamp — a `+` in it would be
    // read as a space without query-component encoding.
    final res = await _get(
      since == null
          ? '/announcements'
          : '/announcements?since=${Uri.encodeQueryComponent(since)}',
    );
    if (res == null) return const [];
    final list = res['announcements'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  // ---------------------------------------------------------------------
  // v2.2 Phase D — plans / trial / subscription / bKash gateway
  // ---------------------------------------------------------------------

  Future<List<Map<dynamic, dynamic>>> fetchPlans() async {
    final res = await _get('/plans');
    if (res == null) return const [];
    final list = res['plans'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  Future<Map<dynamic, dynamic>?> fetchTrial() => _get('/trial');

  Future<Map<dynamic, dynamic>?> claimTrial() =>
      _post('/trial/claim', {'confirm': true});

  Future<Map<dynamic, dynamic>?> fetchSubscription() => _get('/subscription');

  /// v2.5.7 (H-4): identity projection {user, subscription, deviceCount} —
  /// used by the Account screen for the display name.
  Future<Map<String, dynamic>?> fetchMe() => _get('/me');

  /// Forward a completed Play purchase for server verification. The Worker
  /// re-checks the token with the Play Developer API before granting PRO —
  /// this client is never the entitlement authority.
  Future<bool> verifyPlayPurchase({
    required String productId,
    required String purchaseToken,
  }) async {
    final res = await _post('/subscription/verify', {
      'productId': productId,
      'purchaseToken': purchaseToken,
      'platform': 'android',
    });
    return res != null;
  }

  Future<Map<dynamic, dynamic>?> fetchBkashInstructions() =>
      _get('/payments/bkash/instructions');

  Future<Map<dynamic, dynamic>?> bkashInit(String planId) =>
      _post('/payments/bkash/init', {'planId': planId});

  Future<Map<dynamic, dynamic>?> bkashSubmit(
      String paymentId, String trxId, String? senderNumber) {
    return _post('/payments/bkash/submit', {
      'paymentId': paymentId,
      'trxId': trxId,
      if (senderNumber != null && senderNumber.isNotEmpty)
        'senderNumber': senderNumber,
    });
  }

  Future<Map<dynamic, dynamic>?> bkashCancel(String paymentId) =>
      _post('/payments/bkash/cancel', {'paymentId': paymentId});

  Future<List<Map<dynamic, dynamic>>> fetchBkashStatus() async {
    final res = await _get('/payments/bkash/status');
    if (res == null) return const [];
    final list = res['payments'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  // ---------------------------------------------------------------------
  // v2.5 r9 — Social Sentry parity: AI companion / community / referral
  // ---------------------------------------------------------------------

  /// Sinthia chat via the Worker LLM proxy (dual-layer: server LLM when
  /// configured, scripted persona fallback otherwise).
  Future<Map<String, dynamic>?> chatWithCompanion({
    required String message,
    required Map<String, dynamic> context,
    required List<Map<String, dynamic>> history,
    int turn = 0,
  }) {
    final recent = history.length > 12
        ? history.sublist(history.length - 12)
        : history;
    return _post('/ai/chat', {
      'message': message,
      'context': context,
      'history': recent
          .map((m) => {'role': m['role'], 'content': m['content']})
          .toList(),
      'turn': turn,
    });
  }

  Future<List<Map<dynamic, dynamic>>> fetchCommunityFeed(
      {String filter = 'active'}) async {
    final res = await _get('/community/commits?filter=$filter');
    if (res == null) return const [];
    final list = res['commits'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  Future<Map<String, dynamic>?> createCommunityCommit({
    required int durationDays,
    required String reason,
    bool anonymous = false,
  }) {
    return _post('/community/commits', {
      'durationDays': durationDays,
      'reason': reason,
      'anonymous': anonymous,
    });
  }

  Future<Map<String, dynamic>?> cheerCommunityCommit(String commitId) =>
      _post('/community/commits/$commitId/cheer', null);

  /// v2.5.7 (F-2): report a commitment (moderation signal).
  Future<Map<String, dynamic>?> reportCommunityCommit(String commitId) =>
      _post('/community/commits/$commitId/report', null);

  Future<List<Map<dynamic, dynamic>>> searchUsers(String query) async {
    final res = await _get('/friends/search?q=${Uri.encodeQueryComponent(query)}');
    if (res == null) return const [];
    final list = res['users'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  Future<List<Map<dynamic, dynamic>>> fetchFriends() async {
    final res = await _get('/friends');
    if (res == null) return const [];
    final list = res['friends'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  Future<Map<String, dynamic>?> sendFriendRequest(String userId) =>
      _post('/friends/requests/$userId', null);

  Future<Map<String, dynamic>?> acceptFriendRequest(String userId) =>
      _post('/friends/requests/$userId/accept', null);

  Future<Map<String, dynamic>?> removeFriend(String userId) =>
      _post('/friends/$userId/remove', null);

  Future<Map<String, dynamic>?> fetchReferral() => _get('/referral');

  /// v2.5.7 (H-1): the referred friend attaches a referrer's code once,
  /// within 7 days of signup.
  Future<Map<String, dynamic>?> applyReferralCode(String code) =>
      _post('/referral/apply', {'code': code.toUpperCase()});

  Future<Map<String, dynamic>?> claimReferralReward(String referralId) =>
      _post('/referral/claim', {'referralId': referralId});

  // ---------------------------------------------------------------------
  // v2.5.7 audit-fix round — coin mirror (C-3), support tickets (H-2),
  // display-name rename (H-4).
  // ---------------------------------------------------------------------

  /// Server coin view: {balance, grants: [...], serverTime}. Grants are the
  /// rows the device should apply idempotently (admin adjustments/bonuses).
  Future<Map<String, dynamic>?> fetchCoins({String? since}) => _get(
      since == null ? '/coins' : '/coins?since=${Uri.encodeQueryComponent(since)}');

  /// Best-effort mirror of a device ad reward (idempotent by rewardKey).
  Future<void> reportCoinEarn(String rewardKey, {int amount = 1}) async {
    await _post('/coins/earn', {'reference': rewardKey, 'amount': amount});
  }

  /// Best-effort mirror of a device spend (temp unlock / bailout).
  Future<void> reportCoinSpend({
    required String type,
    required int amount,
    required String reference,
  }) async {
    await _post('/coins/spend', {
      'type': type,
      'amount': amount,
      'reference': reference,
    });
  }

  /// v2.5.7 (H-2): report a problem -> creates a support ticket the admin
  /// queue can actually see.
  Future<Map<String, dynamic>?> createSupportTicket({
    required String category,
    required String description,
  }) =>
      _post('/support/tickets', {
        'category': category,
        'description': description,
        'appVersion': AppConstants.appVersion,
      });

  /// v2.5.7 (H-2): my tickets incl. admin replies (pull model).
  Future<List<Map<dynamic, dynamic>>> fetchSupportTickets() async {
    final res = await _get('/support/tickets');
    if (res == null) return const [];
    final list = res['tickets'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  /// v2.5.7 (H-4): set the user-chosen display name.
  Future<bool> updateDisplayName(String displayName) async {
    final res = await _patch('/me', {'displayName': displayName});
    return res != null;
  }

  // ---------------------------------------------------------------------
  // v2.5.8 roadmap — community leaderboard + clubs (DP competition)
  // ---------------------------------------------------------------------

  /// Opt in/out of the leaderboard. displayMode: 'name' | 'anonymous'.
  Future<Map<String, dynamic>?> leaderboardOptIn({
    required bool optedIn,
    String displayMode = 'name',
  }) =>
      _post('/leaderboard/opt-in', {
        'optedIn': optedIn,
        'displayMode': displayMode,
      });

  /// My profile + global rank + club snapshot.
  Future<Map<String, dynamic>?> fetchLeaderboardMe() => _get('/leaderboard/me');

  /// Mirror the device DP snapshot (server clamps; lifetime is monotonic).
  Future<Map<String, dynamic>?> syncLeaderboardSnapshot(
      Map<String, dynamic> snapshot) async {
    return _post('/leaderboard/sync', snapshot);
  }

  /// Top-50 + my rank. window: 'weekly' | 'monthly' | 'alltime'.
  Future<Map<String, dynamic>?> fetchLeaderboardGlobal(
      {String window = 'alltime'}) {
    assert(const ['weekly', 'monthly', 'alltime'].contains(window));
    return _get('/leaderboard/global?window=$window');
  }

  /// Create a club (auto-leaves any current club; invite code generated
  /// server-side).
  Future<Map<String, dynamic>?> createClub({
    required String name,
    String? description,
  }) =>
      _post('/clubs', {
        'name': name,
        if (description != null && description.isNotEmpty)
          'description': description,
      });

  /// My current club (null when not in one).
  Future<Map<String, dynamic>?> fetchMyClub() => _get('/clubs/mine');

  /// Search clubs by name prefix or exact invite code.
  Future<List<Map<dynamic, dynamic>>> searchClubs(String query) async {
    final res = await _get(
        '/clubs/search?q=${Uri.encodeQueryComponent(query)}');
    if (res == null) return const [];
    final list = res['clubs'] as List<dynamic>? ?? const [];
    return list.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList();
  }

  /// Join by 6-char invite code.
  Future<Map<String, dynamic>?> joinClub(String inviteCode) =>
      _post('/clubs/join/${Uri.encodeQueryComponent(inviteCode)}', null);

  /// Leave my current club.
  Future<Map<String, dynamic>?> leaveClub() => _post('/clubs/leave', null);

  /// Club detail + club leaderboard slice.
  Future<Map<String, dynamic>?> fetchClub(String clubId,
          {String window = 'alltime'}) =>
      _get('/clubs/$clubId?window=$window');

  // ---------------------------------------------------------------------
  // Offline-safe event queue (PRD §41 / Backend §90)
  // ---------------------------------------------------------------------

  static const int _maxQueue = 500;

  Future<void> enqueueEvent(String type, Map<String, dynamic> payload) async {
    final prefs = await SharedPreferences.getInstance();
    final queue = await _loadQueue(prefs);
    queue.add({
      'eventId': 'evt_${_randomHex(16)}',
      'type': type,
      'payload': payload,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
    });
    // Bound the queue: oldest events drop first. Analytics must never
    // exhaust storage if the device stays offline for a long time.
    while (queue.length > _maxQueue) {
      queue.removeAt(0);
    }
    await prefs.setString('mld_event_queue', jsonEncode(queue));
  }

  Future<void> flushEventQueue() async {
    if (!isAuthenticated) return;
    final prefs = await SharedPreferences.getInstance();
    final queue = await _loadQueue(prefs);
    if (queue.isEmpty) return;

    // v2.5.5 audit fix: on a mid-flush failure the already-sent prefix is
    // dropped BEFORE returning — the old code re-uploaded it on the next
    // flush, duplicating every successfully-sent batch on flaky links.
    for (int i = 0; i < queue.length; i += 50) {
      final batch = queue.skip(i).take(50).toList();
      final ok = await _post('/events', {'events': batch});
      if (ok == null) {
        // Still offline — keep the FAILED batch and everything after it
        // (batches before i were uploaded and are dropped; batch i itself
        // failed and must be retried, not discarded).
        await prefs.setString(
            'mld_event_queue', jsonEncode(queue.skip(i).toList()));
        return;
      }
    }
    await prefs.setString('mld_event_queue', '[]');
  }

  Future<List<Map<String, dynamic>>> _loadQueue(SharedPreferences prefs) async {
    final raw = prefs.getString('mld_event_queue');
    if (raw == null) return [];
    try {
      return (jsonDecode(raw) as List<dynamic>)
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
    } catch (_) {
      return [];
    }
  }

  // ---------------------------------------------------------------------
  // Low-level (envelope unwrapping, refresh on 401)
  // ---------------------------------------------------------------------

  Future<Map<String, dynamic>?> _get(String path,
      {bool authenticated = true, int retry = 0}) {
    return _send('GET', path, null, authenticated, retry);
  }

  Future<Map<String, dynamic>?> _post(String path, Map<String, dynamic>? body,
      {bool authenticated = true, int retry = 0}) {
    return _send('POST', path, body, authenticated, retry);
  }

  // v2.5.7 (H-4): PATCH support for the display-name rename.
  Future<Map<String, dynamic>?> _patch(String path, Map<String, dynamic>? body,
      {bool authenticated = true, int retry = 0}) {
    return _send('PATCH', path, body, authenticated, retry);
  }

  Future<Map<String, dynamic>?> _send(
    String method,
    String path,
    Map<String, dynamic>? body,
    bool authenticated,
    int retry,
  ) async {
    if (authenticated && _accessToken == null) return null;
    try {
      final uri = Uri.parse('${AppConstants.apiBaseUrl}$path');
      final request = http.Request(method, uri);
      request.headers['Content-Type'] = 'application/json';
      if (authenticated) request.headers['Authorization'] = 'Bearer $_accessToken';
      if (body != null) request.body = jsonEncode(body);

      // v2.7 r13: 12s -> 6s. The production Worker is not deployed yet, so
      // every cloud call currently fails against NXDOMAIN — a 12-second
      // spinner per tab was the "stuck loading screen" users reported.
      // Six seconds is still generous for a slow-but-live backend.
      final response = await request.send().timeout(const Duration(seconds: 6));
      final text = await response.stream.bytesToString();
      if (text.isEmpty) return null;
      final Map<String, dynamic> envelope;
      try {
        envelope = jsonDecode(text) as Map<String, dynamic>;
      } catch (_) {
        return null;
      }
      if (envelope['success'] != true) {
        final error = envelope['error'] as Map<String, dynamic>?;
        final code = error?['code'] as String? ?? '';
        // v2.5.7 (F-1): remember WHY the call failed so screens can show a
        // specific message (offline vs. 401 vs. 429 vs. 409...).
        lastErrorCode = code.isEmpty ? 'SERVER_ERROR' : code;
        lastErrorMessage = error?['message'] as String?;
        if (code == 'UNAUTHORIZED' && authenticated && retry == 0) {
          if (await _tryRefresh()) {
            // v2.7.1: await the retry so a failing second pass flows
            // through this method's own catch (never throws to callers).
            return await _send(method, path, body, authenticated, 1);
          }
        }
        return null;
      }
      clearLastError();
      final data = envelope['data'];
      if (data == null) return null;
      return Map<String, dynamic>.from(data as Map);
    } catch (_) {
      // v2.5.7 (F-1): network/timeout failure — distinct from server errors.
      lastErrorCode = 'OFFLINE';
      lastErrorMessage = 'Network unavailable — showing locally saved data.';
      return null; // offline / timeout — fail safe, fail silent
    }
  }
}

String _randomHex(int bytes) {
  final rnd = Random.secure();
  return List.generate(bytes, (_) => rnd.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
}
