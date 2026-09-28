import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Modern Profile / Account Screen with rich user statistics,
/// membership details, device protection toggles, and personalization.
class AccountScreen extends StatefulWidget {
  const AccountScreen({super.key});

  @override
  State<AccountScreen> createState() => _AccountScreenState();
}

class _AccountScreenState extends State<AccountScreen> {
  static bool _googleInitialized = false;

  bool _loading = true;
  bool _busy = false;
  SubscriptionInfo? _sub;
  TrialInfo? _trial;
  bool _adminActive = false;
  String? _deviceId;
  String? _displayName;
  int _streakDays = 0;
  int _coinBalance = 0;
  int _focusMinutes = 0;

  @override
  void initState() {
    super.initState();
    _loadProfileData();
  }

  Future<void> _loadProfileData() async {
    final client = ApiClient.instance;
    final admin = await NativeBridge.instance.isDeviceAdminActive();

    // Fetch user local stats
    try {
      final stats = await NativeBridge.instance.getWeeklyStats();
      final progress = await NativeBridge.instance.getProgress();
      final txs = await NativeBridge.instance.getCoinTransactions(limit: 100);
      int totalCoins = 0;
      for (final tx in txs) {
        totalCoins += tx.amount;
      }
      _streakDays = stats.streakDays > 0 ? stats.streakDays : (progress.data?.streak.current ?? 0);
      _coinBalance = totalCoins > 0 ? totalCoins : 0;
      _focusMinutes = (stats.focusSeconds ~/ 60);
    } catch (_) {}

    if (!mounted) return;
    setState(() {
      _deviceId = client.deviceId;
      _adminActive = admin;
      _loading = false;
    });

    if (client.isAuthenticated) {
      try {
        final sub = await client.fetchSubscription();
        final trial = await client.fetchTrial();
        await _loadDisplayName();
        if (!mounted) return;
        setState(() {
          _sub = (sub != null && sub['subscription'] is Map)
              ? SubscriptionInfo.fromJson(
                  Map<dynamic, dynamic>.from(sub['subscription'] as Map))
              : null;
          _trial = (trial != null && trial['trial'] is Map)
              ? TrialInfo.fromJson(
                  Map<dynamic, dynamic>.from(trial['trial'] as Map))
              : null;
        });
      } catch (_) {}
    } else {
      // Load local display name from SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      final localName = prefs.getString('mld_local_display_name');
      if (mounted && localName != null) {
        setState(() => _displayName = localName);
      }
    }
  }

  Future<void> _loadDisplayName() async {
    if (!ApiClient.instance.isAuthenticated) return;
    try {
      final me = await ApiClient.instance.fetchMe();
      final name = me?['user'] is Map
          ? (me!['user'] as Map)['displayName'] as String?
          : null;
      if (mounted && name != null) setState(() => _displayName = name);
    } catch (_) {}
  }

  Future<void> _editDisplayName() async {
    final controller = TextEditingController(text: _displayName ?? '');
    final submitted = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: const Text('Edit Profile Name'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: controller,
              autofocus: true,
              maxLength: 30,
              decoration: const InputDecoration(
                labelText: 'Display Name',
                hintText: 'e.g. Alex Hunter',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              'Your profile name appears across community leaderboards and commitments.',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    controller.dispose();

    if (submitted == null || submitted.isEmpty) return;

    if (ApiClient.instance.isAuthenticated) {
      final ok = await ApiClient.instance.updateDisplayName(submitted);
      if (mounted) {
        if (ok) {
          setState(() => _displayName = submitted);
          _toast('Profile name updated');
        } else {
          _toast(ApiClient.instance.lastErrorMessage ?? 'Update failed');
        }
      }
    } else {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('mld_local_display_name', submitted);
      if (mounted) {
        setState(() => _displayName = submitted);
        _toast('Profile name updated locally');
      }
    }
  }

  Future<void> _toggleAdmin() async {
    if (_adminActive) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          backgroundColor: AppColors.surface,
          title: const Text('Disable Anti-Uninstall Protection?'),
          content: const Text(
            'Removing Device Admin turns off strict enforcement and anti-tamper safeguards.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Keep Protected'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Turn Off'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
      _toast('Remove it in Settings → Device admin apps.');
      await NativeBridge.instance.openPermissionSettings('deviceAdmin');
    } else {
      await NativeBridge.instance.requestDeviceAdmin();
      await Future<void>.delayed(const Duration(seconds: 2));
      _loadProfileData();
    }
  }

  Future<void> _signInWithGoogle() async {
    if (_busy) return;
    if (AppConstants.googleServerClientId.isEmpty) {
      _toast('Google Sign-in client ID is not configured in this build.');
      return;
    }

    setState(() => _busy = true);
    try {
      final google = GoogleSignIn.instance;
      if (!_googleInitialized) {
        await google.initialize(
          serverClientId: AppConstants.googleServerClientId,
        );
        _googleInitialized = true;
      }
      final account = await google.authenticate();
      final idToken = account.authentication.idToken;
      if (idToken == null || idToken.isEmpty) {
        _toast('Could not retrieve Google authentication token.');
        return;
      }
      final ok = await ApiClient.instance.loginWithGoogle(idToken);
      if (!mounted) return;
      _toast(ok ? 'Signed in successfully!' : 'Sign-in failed. Please try again.');
      if (ok) {
        unawaited(ApiClient.instance.ensureDeviceRegistered());
        unawaited(ApiClient.instance.flushEventQueue());
      }
    } catch (e) {
      if (mounted) _toast('Sign-in error: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _loadProfileData();
      }
    }
  }

  Future<void> _signOut() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: const Text('Sign Out?'),
        content: const Text('You can sign back in at any time to sync your progress.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Sign Out'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _busy = true);
    await ApiClient.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushNamedAndRemoveUntil(AppConstants.routeAuth, (route) => false);
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        content: Text(msg),
      ),
    );
  }

  int? get _trialDaysLeft {
    final t = _trial;
    if (t == null || !t.active) return null;
    final exp = t.expiresAt == null ? null : DateTime.tryParse(t.expiresAt!);
    if (exp == null) return null;
    final days = exp.difference(DateTime.now().toUtc()).inDays + 1;
    return days > 0 ? days : 0;
  }

  @override
  Widget build(BuildContext context) {
    final signedIn = ApiClient.instance.isAuthenticated;
    final pro = _sub?.isActive ?? false;
    final trialDays = _trialDaysLeft;

    final shortId = _deviceId != null && _deviceId!.length > 6
        ? _deviceId!.substring(0, 6).toUpperCase()
        : 'USER';
    final userTitle = _displayName?.isNotEmpty == true
        ? _displayName!
        : (signedIn ? 'Detox Warrior #$shortId' : 'Guest Warrior #$shortId');
    final userSubtitle = signedIn ? 'Cloud Sync Enabled' : 'Device ID: #$shortId · Tap to Sign In';

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: const MLDAppBarTitle(title: 'Profile & Account'),
        actions: [
          IconButton(
            icon: const Icon(Icons.edit_note, size: 24),
            tooltip: 'Edit Name',
            onPressed: _editDisplayName,
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: AppSpacing.screenH.copyWith(
                  top: AppSpacing.md,
                  bottom: AppSpacing.xxxl + 40,
                ),
                children: [
                  // 1. Profile Header Card
                  _buildProfileHeader(userTitle, userSubtitle, signedIn, pro),
                  const SizedBox(height: AppSpacing.lg),

                  // 2. Quick Statistics Grid (Streak, Coins, Focus)
                  _buildStatsRow(),
                  const SizedBox(height: AppSpacing.xl),

                  // 3. Subscription & Plan Banner
                  _buildSubscriptionCard(pro, trialDays),
                  const SizedBox(height: AppSpacing.xl),

                  // 4. Security & Device Enforcement
                  const MLDSectionHeader(title: 'Device & Security'),
                  const SizedBox(height: AppSpacing.md),
                  _buildSecuritySection(),
                  const SizedBox(height: AppSpacing.xl),

                  // 5. Preferences & App Controls
                  const MLDSectionHeader(title: 'Preferences'),
                  const SizedBox(height: AppSpacing.md),
                  _buildPreferencesSection(),
                  const SizedBox(height: AppSpacing.xl),

                  // 6. Account & Session Actions
                  const MLDSectionHeader(title: 'Account Actions'),
                  const SizedBox(height: AppSpacing.md),
                  _buildAccountActions(signedIn),
                  const SizedBox(height: AppSpacing.xxl),

                  // Footer info
                  Center(
                    child: Column(
                      children: [
                        Text(
                          'MAXLEVEL DETOX v2.9.1 (Build 30)',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppColors.textDisabled.withValues(alpha: 0.8),
                          ),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          'Zero-Distraction Discipline OS',
                          style: TextStyle(
                            fontSize: 11,
                            color: AppColors.textDisabled,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildProfileHeader(
    String title,
    String subtitle,
    bool signedIn,
    bool pro,
  ) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [
            AppColors.surface,
            AppColors.elevated,
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.edge),
      ),
      child: Row(
        children: [
          // Avatar with status ring
          Stack(
            alignment: Alignment.bottomRight,
            children: [
              Container(
                width: 64,
                height: 64,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(
                    colors: pro
                        ? [AppColors.premium, const Color(0xFFC084FC)]
                        : [AppColors.primary, const Color(0xFF38BDF8)],
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: (pro ? AppColors.premium : AppColors.primary)
                          .withValues(alpha: 0.35),
                      blurRadius: 10,
                      spreadRadius: 2,
                    ),
                  ],
                ),
                child: Center(
                  child: Text(
                    title.isNotEmpty ? title[0].toUpperCase() : 'M',
                    style: const TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.all(4),
                decoration: const BoxDecoration(
                  color: AppColors.surface,
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  pro ? Icons.star : Icons.shield,
                  size: 14,
                  color: pro ? AppColors.warning : AppColors.success,
                ),
              ),
            ],
          ),
          const SizedBox(width: AppSpacing.lg),

          // User details
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.textPrimary,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    InkWell(
                      onTap: _editDisplayName,
                      child: const Padding(
                        padding: EdgeInsets.all(4),
                        child: Icon(
                          Icons.edit,
                          size: 16,
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: pro
                        ? AppColors.premium.withValues(alpha: 0.2)
                        : AppColors.elevated,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: pro ? AppColors.premium : AppColors.edge,
                    ),
                  ),
                  child: Text(
                    pro ? '⚡ PRO MEMBER' : 'FREE TIER',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 0.5,
                      color: pro ? AppColors.premium : AppColors.textSecondary,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatsRow() {
    return Row(
      children: [
        Expanded(
          child: _statCard(
            icon: Icons.local_fire_department,
            color: AppColors.warning,
            value: '$_streakDays Days',
            label: 'Current Streak',
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: _statCard(
            icon: Icons.monetization_on,
            color: AppColors.info,
            value: '$_coinBalance DP',
            label: 'Discipline Coins',
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: _statCard(
            icon: Icons.timer,
            color: AppColors.success,
            value: '${(_focusMinutes / 60).toStringAsFixed(1)}h',
            label: 'Focus Time',
          ),
        ),
      ],
    );
  }

  Widget _statCard({
    required IconData icon,
    required Color color,
    required String value,
    required String label,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        children: [
          Icon(icon, color: color, size: 22),
          const SizedBox(height: 8),
          Text(
            value,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w800,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            label,
            style: const TextStyle(
              fontSize: 10,
              color: AppColors.textSecondary,
            ),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget _buildSubscriptionCard(bool pro, int? trialDays) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: pro
              ? [const Color(0xFF1E1035), const Color(0xFF2E1065)]
              : [const Color(0xFF0F1E36), const Color(0xFF162A4A)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(
          color: pro ? AppColors.premium : AppColors.primaryDim,
          width: 1.2,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: (pro ? AppColors.premium : AppColors.primary)
                      .withValues(alpha: 0.2),
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  pro ? Icons.workspace_premium : Icons.stars,
                  color: pro ? AppColors.premium : AppColors.primary,
                  size: 24,
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      pro ? 'MAXLEVEL PRO Activated' : 'Upgrade to PRO',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        color: Colors.white,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      pro
                          ? 'All premium blockers & cloud sync active'
                          : (trialDays != null && trialDays > 0)
                              ? 'Free Trial active ($trialDays days remaining)'
                              : 'Unlock strict lockdown, unlimited routines & cloud sync',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          const Divider(height: 1, color: AppColors.edge),
          const SizedBox(height: AppSpacing.md),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Row(
                children: [
                  Icon(Icons.check_circle, size: 14, color: AppColors.success),
                  SizedBox(width: 4),
                  Text('Zero Ads', style: TextStyle(fontSize: 11, color: Colors.white70)),
                  SizedBox(width: 10),
                  Icon(Icons.check_circle, size: 14, color: AppColors.success),
                  SizedBox(width: 4),
                  Text('Anti-Bypass', style: TextStyle(fontSize: 11, color: Colors.white70)),
                ],
              ),
              FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: pro ? AppColors.elevated : AppColors.primary,
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 0),
                  minimumSize: const Size(0, 36),
                ),
                onPressed: () => Navigator.of(context).pushNamed('/pro'),
                child: Text(
                  pro ? 'Manage' : 'View Plans',
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSecuritySection() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        children: [
          SwitchListTile(
            value: _adminActive,
            onChanged: _busy ? null : (_) => _toggleAdmin(),
            secondary: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: (_adminActive ? AppColors.success : AppColors.danger)
                    .withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(
                Icons.admin_panel_settings,
                color: _adminActive ? AppColors.success : AppColors.danger,
                size: 22,
              ),
            ),
            title: const Text(
              'Device Admin Protection',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              _adminActive
                  ? 'Active · Re-lock loop & anti-uninstall protection enabled'
                  : 'Inactive · Tap to grant protection',
              style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
          ),
          const Divider(height: 1, color: AppColors.edge),
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppColors.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.vpn_key, color: AppColors.primary, size: 22),
            ),
            title: const Text(
              'Emergency Bailout Codes',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            subtitle: const Text(
              'View or generate one-time emergency unlock keys',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
            trailing: const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            onTap: () => Navigator.of(context).pushNamed('/emergency-codes'),
          ),
        ],
      ),
    );
  }

  Widget _buildPreferencesSection() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.card),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        children: [
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppColors.info.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.widgets, color: AppColors.info, size: 22),
            ),
            title: const Text(
              'Home-Screen Widgets',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            subtitle: const Text(
              'Pin Streak, Today Focus & Distraction Trend widgets',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
            trailing: const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            onTap: () => Navigator.of(context).pushNamed('/widgets'),
          ),
          const Divider(height: 1, color: AppColors.edge),
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppColors.warning.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.rule, color: AppColors.warning, size: 22),
            ),
            title: const Text(
              'App & Website Rules',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            subtitle: const Text(
              'Configure blocked apps and time limits',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
            trailing: const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            onTap: () => Navigator.of(context).pushNamed('/app-rules'),
          ),
          const Divider(height: 1, color: AppColors.edge),
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppColors.primaryDim.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.help_outline, color: AppColors.primary, size: 22),
            ),
            title: const Text(
              'Support & Diagnostic Report',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
            subtitle: const Text(
              'Verify background permissions and report issues',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
            trailing: const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            onTap: () => Navigator.of(context).pushNamed('/support'),
          ),
        ],
      ),
    );
  }

  Widget _buildAccountActions(bool signedIn) {
    if (signedIn) {
      return Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.card),
          border: Border.all(color: AppColors.edge),
        ),
        child: Column(
          children: [
            ListTile(
              leading: const Icon(Icons.logout, color: AppColors.danger),
              title: const Text(
                'Sign Out',
                style: TextStyle(
                  color: AppColors.danger,
                  fontWeight: FontWeight.w700,
                  fontSize: 14,
                ),
              ),
              subtitle: const Text(
                'Sign out of your Google account on this device',
                style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
              ),
              onTap: _signOut,
            ),
          ],
        ),
      );
    } else {
      return Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.card),
          border: Border.all(color: AppColors.edge),
        ),
        child: Column(
          children: [
            MLDButton(
              label: 'Sign in with Google',
              icon: Icons.login,
              loading: _busy,
              onPressed: _signInWithGoogle,
            ),
            const SizedBox(height: AppSpacing.sm),
            const Text(
              'Sign in to sync your streak across devices and unlock community leaderboards.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
          ],
        ),
      );
    }
  }
}
