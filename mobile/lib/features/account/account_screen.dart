import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// AccountScreen (v2.5 r9) — SS profile/account port: identity (Google
/// sign-in via the Worker), plan + trial state, device admin controls,
/// and the account danger zone.
class AccountScreen extends StatefulWidget {
  const AccountScreen({super.key});

  @override
  State<AccountScreen> createState() => _AccountScreenState();
}

class _AccountScreenState extends State<AccountScreen> {
  /// google_sign_in 7.x requires initialize() to run exactly once per
  /// process (v2.5.5 audit fix: repeated calls are documented as undefined
  /// behavior).
  static bool _googleInitialized = false;

  bool _loading = true;
  bool _busy = false;
  // v2.5.5 audit fix: store the parsed projections (like the paywall does)
  // instead of raw envelopes that were read at the wrong nesting level.
  SubscriptionInfo? _sub;
  TrialInfo? _trial;
  bool _adminActive = false;
  String? _deviceId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = ApiClient.instance;
    final admin = await NativeBridge.instance.isDeviceAdminActive();
    if (!mounted) return;
    setState(() {
      _deviceId = client.deviceId;
      _adminActive = admin;
      _loading = false;
    });
    if (client.isAuthenticated) {
      final sub = await client.fetchSubscription();
      final trial = await client.fetchTrial();
      unawaited(_loadDisplayName());
      if (!mounted) return;
      setState(() {
        // v2.5.5 audit fix: the worker envelops both payloads —
        //   GET /subscription -> {subscription: {...}|null}
        //   GET /trial        -> {trial: {...}, trialDays, paymentsEnabled}
        // The old code read planId/active/daysRemaining off the TOP level,
        // where they never exist, so PRO always rendered as "Free tier".
        _sub = (sub != null && sub['subscription'] is Map)
            ? SubscriptionInfo.fromJson(
                Map<dynamic, dynamic>.from(sub['subscription'] as Map))
            : null;
        _trial = (trial != null && trial['trial'] is Map)
            ? TrialInfo.fromJson(
                Map<dynamic, dynamic>.from(trial['trial'] as Map))
            : null;
      });
    }
  }

  Future<void> _toggleAdmin() async {
    if (_adminActive) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Remove Device Admin?'),
          content: const Text(
              'Lock My Phone re-locking stops working without it. Active '
              'lock sessions may degrade to the overlay wall.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Keep')),
            FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Remove')),
          ],
        ),
      );
      if (confirmed != true) return;
      _toast('Remove it from Settings → Device admin apps (system screen).');
      await NativeBridge.instance.openPermissionSettings('deviceAdmin');
    } else {
      await NativeBridge.instance.requestDeviceAdmin();
      await Future<void>.delayed(const Duration(seconds: 2));
      _load();
    }
  }

  Future<void> _signOut() async {
    setState(() => _busy = true);
    await ApiClient.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushNamedAndRemoveUntil(AppConstants.routeAuth, (route) => false);
  }

  /// Days left in an active trial (never below zero), or null when there is
  /// no active trial.
  int? get _trialDaysLeft {
    final t = _trial;
    if (t == null || !t.active) return null;
    final exp = t.expiresAt == null ? null : DateTime.tryParse(t.expiresAt!);
    if (exp == null) return null;
    final days = exp.difference(DateTime.now().toUtc()).inDays + 1;
    return days > 0 ? days : 0;
  }

  /// v2.5.5 audit fix (CRITICAL): Google Sign-In previously did not exist
  /// anywhere in the client — `loginWithGoogle` had zero callers, no
  /// google_sign_in dependency and no button — so the authenticated product
  /// surface (purchase verification, community, referral, trial, event
  /// flush) was unreachable. The Google idToken is exchanged at the Worker;
  /// the client never grants itself anything.
  Future<void> _signIn() async {
    if (_busy) return;

    // The OAuth 2.0 WEB client id of the backend audience is injected at
    // build time. Without it the SDK would mint tokens for the wrong (or
    // no) audience and every login would 401 at the Worker.
    if (AppConstants.googleServerClientId.isEmpty) {
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Sign-in is not configured'),
          content: const Text(
            'This build has no Google OAuth Web Client ID. Rebuild with\n\n'
            '--dart-define=MLD_GOOGLE_SERVER_CLIENT_ID=<web client id>\n\n'
            'The idToken audience must match the Worker\'s GOOGLE_CLIENT_ID.',
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context), child: const Text('OK')),
          ],
        ),
      );
      return;
    }

    setState(() => _busy = true);
    try {
      final google = GoogleSignIn.instance;
      // google_sign_in 7.x: initialize() must run exactly once per process.
      if (!_googleInitialized) {
        await google.initialize(
            serverClientId: AppConstants.googleServerClientId);
        _googleInitialized = true;
      }
      final account = await google.authenticate();
      final idToken = account.authentication.idToken;
      if (idToken == null || idToken.isEmpty) {
        _toast('Google did not return an ID token — try again.');
        return;
      }
      final ok = await ApiClient.instance.loginWithGoogle(idToken);
      if (!mounted) return;
      _toast(ok
          ? 'Signed in — sync unlocked.'
          : 'Sign-in failed — check your connection.');
      if (ok) {
        // Fire-and-forget: bind this device + drain the offline queue now
        // that a session exists. Never gates the UI (offline-first).
        unawaited(ApiClient.instance.ensureDeviceRegistered());
        unawaited(ApiClient.instance.flushEventQueue());
      }
    } on GoogleSignInException catch (e) {
      // Cancellation is a normal user exit — stay silent for it.
      if (mounted && e.code != GoogleSignInExceptionCode.canceled) {
        _toast('Sign-in failed (${e.code.name}).');
      }
    } catch (_) {
      if (mounted) _toast('Sign-in failed — try again.');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        // Refresh identity/plan/trial projections for the new session.
        _load();
      }
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  String? _displayName;

  /// v2.5.7 (H-4): load the user-chosen display name (defaults to
  /// "User #XXXX" server-side — never the email prefix).
  Future<void> _loadDisplayName() async {
    if (!ApiClient.instance.isAuthenticated) {
      if (mounted) setState(() => _displayName = null);
      return;
    }
    final me = await ApiClient.instance.fetchMe();
    final name = me?['user'] is Map
        ? (me!['user'] as Map)['displayName'] as String?
        : null;
    if (mounted) setState(() => _displayName = name);
  }

  /// v2.5.7 (H-4): rename dialog — PATCH /me {displayName}.
  Future<void> _editDisplayName() async {
    if (!ApiClient.instance.isAuthenticated) {
      _toast('Sign in first to set a display name.');
      return;
    }
    final controller = TextEditingController(text: _displayName ?? '');
    final submitted = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Display Name'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: controller,
              autofocus: true,
              maxLength: 40,
              decoration: const InputDecoration(
                labelText: 'Name (2-40 chars)',
                hintText: 'Shown on community commits and friend search',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Your email is never shown. Pick a name you are comfortable sharing publicly.',
              style: TextStyle(fontSize: 12),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, controller.text.trim()),
              child: const Text('Save')),
        ],
      ),
    );
    if (submitted == null) return;
    if (submitted.length < 2) {
      _toast('Name must be at least 2 characters.');
      return;
    }
    final ok = await ApiClient.instance.updateDisplayName(submitted);
    if (!mounted) return;
    if (ok) {
      setState(() => _displayName = submitted);
      _toast('Display name updated.');
    } else {
      _toast(ApiClient.instance.lastErrorMessage ?? 'Could not update the name.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final client = ApiClient.instance;
    final signedIn = client.isAuthenticated;
    // v2.5.5 audit fix: parse the enveloped projection (see _load) — the
    // old `_subscription?['planId']` / `?['active']` reads never existed at
    // that level, so `pro` was always false and `plan` always null.
    final plan = _sub?.plan ?? '';
    final pro = _sub?.isActive ?? false;
    final trialDays = _trialDaysLeft;

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Account')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                children: [
                  // Identity -------------------------------------------------
                  MLDCard(
                    child: Column(
                      children: [
                        CircleAvatar(
                          radius: 28,
                          backgroundColor: AppColors.elevated,
                          child: Icon(
                            signedIn ? Icons.person : Icons.person_outline,
                            size: 30,
                            color: signedIn
                                ? AppColors.primary
                                : AppColors.textDisabled,
                          ),
                        ),
                        const SizedBox(height: AppSpacing.md),
                        Text(
                          signedIn ? 'Signed in' : 'Guest (device-bound)',
                          style: const TextStyle(
                              fontWeight: FontWeight.w700, fontSize: 14),
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        Text(
                          signedIn
                              ? (_displayName ?? 'Sync, community and referral unlocked')
                              : _deviceId != null
                                  ? 'Device ${_deviceId!.substring(0, (_deviceId!.length.clamp(0, 8)))}…'
                                  : '',
                          style: const TextStyle(fontSize: 12),
                        ),
                        if (signedIn) ...[
                          const SizedBox(height: AppSpacing.sm),
                          TextButton.icon(
                            onPressed: _editDisplayName,
                            icon: const Icon(Icons.edit_outlined, size: 16),
                            label: const Text('Change display name'),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),

                  // Plan -----------------------------------------------------
                  const MLDSectionHeader(title: 'Plan'),
                  const SizedBox(height: AppSpacing.md),
                  MLDCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              pro
                                  ? Icons.workspace_premium
                                  : (trialDays ?? 0) > 0
                                      ? Icons.hourglass_top
                                      : Icons.free_breakfast,
                              color: pro
                                  ? AppColors.premium
                                  : (trialDays ?? 0) > 0
                                      ? AppColors.warning
                                      : AppColors.textDisabled,
                            ),
                            const SizedBox(width: AppSpacing.md),
                            Expanded(
              child: Text(
                                pro
                                    ? (plan.isEmpty ? 'PRO active' : 'PRO — $plan')
                                    : (trialDays ?? 0) > 0
                                        ? 'Free trial · $trialDays days left'
                                        : 'Free tier',
                                style: const TextStyle(
                                    fontWeight: FontWeight.w600, fontSize: 13),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: AppSpacing.md),
                        MLDButton(
                          label: pro ? 'Manage plan' : 'Upgrade to PRO',
                          variant: pro
                              ? MLDButtonVariant.secondary
                              : MLDButtonVariant.primary,
                          expanded: false,
                          onPressed: () =>
                              Navigator.of(context).pushNamed('/pro'),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),

                  // Device admin ---------------------------------------------
                  const MLDSectionHeader(title: 'Device'),
                  const SizedBox(height: AppSpacing.md),
                  MLDCard(
                    child: Column(
                      children: [
                        SwitchListTile(
                          value: _adminActive,
                          onChanged: _busy ? null : (_) => _toggleAdmin(),
                          title: const Text('Device Admin (force-lock)',
                              style: TextStyle(fontSize: 14)),
                          subtitle: const Text(
                              'Powers Lock My Phone\'s 1.5 s re-lock loop',
                              style: TextStyle(fontSize: 12)),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),

                  // Danger zone ----------------------------------------------
                  const MLDSectionHeader(title: 'Session'),
                  const SizedBox(height: AppSpacing.md),
                  if (signedIn)
                    MLDButton(
                      label: 'Sign out',
                      variant: MLDButtonVariant.danger,
                      loading: _busy,
                      onPressed: _signOut,
                    )
                  else ...[
                    // v2.5.5 audit fix (CRITICAL): this branch used to render
                    // only a text pointing at a control that did not exist —
                    // the actual sign-in entry point.
                    MLDButton(
                      label: 'Sign in with Google',
                      icon: Icons.login,
                      loading: _busy,
                      onPressed: _signIn,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    const Text(
                      'Sync progress across devices and unlock community, '
                      'referral and PRO purchase verification.',
                      textAlign: TextAlign.center,
                      style:
                          TextStyle(color: AppColors.textSecondary, fontSize: 12),
                    ),
                  ],
                ],
              ),
      ),
    );
  }
}
