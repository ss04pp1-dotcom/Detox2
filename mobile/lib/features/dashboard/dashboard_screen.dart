import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';
import '../insights/insights_screen.dart';
import '../session/active_session_screen.dart';
import '../settings/settings_screen.dart';
import '../study/study_setup_screen.dart';

/// Dashboard + bottom navigation shell (UI/UX §9, §14, §63).
///
/// Home must answer three questions instantly:
///   1. What is my current state?  2. How much have I focused?  3. What next?
/// Eight distinct home states — each with its own visual hierarchy.
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);

    // While enforcement is active, the shell is REPLACED by the session
    // experience (UI/UX §9) — normal navigation must not be an escape hatch.
    if (app.state.sessionActive || app.state.cageActive) {
      return const ActiveSessionShell();
    }

    final pages = [
      const _HomePage(),
      const _FocusTabPage(),
      const _DetoxTabPage(),
      const _InsightsTabPage(),
      const _SettingsTabPage(),
    ];

    return Scaffold(
      body: IndexedStack(index: _tab, children: pages),
      bottomNavigationBar: NavigationBarTheme(
        data: NavigationBarThemeData(
          backgroundColor: AppColors.surface,
          indicatorColor: AppColors.primary.withValues(alpha: 0.15),
          labelTextStyle: WidgetStatePropertyAll(
            AppTypography.caption().copyWith(fontSize: 11, fontWeight: FontWeight.w600),
          ),
        ),
        child: NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          height: 68,
          destinations: [
            const NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Home'),
            const NavigationDestination(icon: Icon(Icons.menu_book_outlined), selectedIcon: Icon(Icons.menu_book), label: 'Focus'),
            const NavigationDestination(icon: Icon(Icons.spa_outlined), selectedIcon: Icon(Icons.spa), label: 'Detox'),
            const NavigationDestination(icon: Icon(Icons.insights_outlined), selectedIcon: Icon(Icons.insights), label: 'Insights'),
            const NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings), label: 'Settings'),
          ],
        ),
      ),
    );
  }
}

/// Wrapper so the active session screen can be pushed from the shell while
/// keeping this file's import graph acyclic.
class ActiveSessionShell extends StatelessWidget {
  const ActiveSessionShell({super.key});

  @override
  Widget build(BuildContext context) {
    return const ActiveSessionScreen(embedded: true);
  }
}

// ---------------------------------------------------------------------------
// HOME — 8 states (UI/UX §63)
// ---------------------------------------------------------------------------

class _HomePage extends StatefulWidget {
  const _HomePage();

  @override
  State<_HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<_HomePage> with WidgetsBindingObserver {
  AnnouncementItem? _offer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadOffer();
  }

  /// v2.2 Phase D — offer routing (pull model). PROMOTION announcements
  /// surface once as a banner with a 24h cooldown after dismissal — a
  /// deliberate rejection of the competitor's 1-hour upsell spam.
  Future<void> _loadOffer() async {
    final prefs = await SharedPreferences.getInstance();
    final dismissedAtMs = prefs.getInt('mld_offer_dismissed_at') ?? 0;
    final recentlyDismissed =
        DateTime.now().millisecondsSinceEpoch - dismissedAtMs <
            24 * 60 * 60 * 1000;
    if (recentlyDismissed) return;
    final raw = await ApiClient.instance.fetchAnnouncements();
    if (!mounted || raw.isEmpty) return;
    final items = raw.map(AnnouncementItem.fromJson).toList();
    final offer = items.firstWhere((a) => a.isOffer, orElse: () => items.first);
    if (mounted) setState(() => _offer = offer);
  }

  Future<void> _dismissOffer() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(
        'mld_offer_dismissed_at', DateTime.now().millisecondsSinceEpoch);
    if (mounted) {
      setState(() => _offer = null);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check permissions when returning from Android Settings (TRD §31).
    if (state == AppLifecycleState.resumed) {
      AppStateScope.of(context).refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);

    if (!app.bootstrapped) {
      return const Center(child: CircularProgressIndicator());
    }

    // State: permission problem (only when not enforcing — the immersive
    // screen above already owns the session-active case).
    if (!app.state.permissions.enforcementReady && app.state.pactAccepted) {
      return _PermissionProblemView(onFix: () => Navigator.of(context).pushNamed(AppConstants.routePermissionCenter));
    }

    return SafeArea(
      child: RefreshIndicator(
        color: AppColors.primary,
        onRefresh: () => AppStateScope.of(context).refresh(),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: AppSpacing.screenH.copyWith(top: AppSpacing.xl, bottom: AppSpacing.xxxl),
          children: [
            const _Greeting(),
            if (_offer != null) ...[
              const SizedBox(height: AppSpacing.lg),
              _OfferBanner(
                offer: _offer!,
                onDismiss: _dismissOffer,
              ),
            ],
            const SizedBox(height: AppSpacing.xxl),
            const _HeroStatus(),
            const SizedBox(height: AppSpacing.xxl),
            const _ProgressStrip(),
            const SizedBox(height: AppSpacing.xxl),
            const _TodayProgress(),
            const SizedBox(height: AppSpacing.xxl),
            const _QuickActions(),
          ],
        ),
      ),
    );
  }
}

/// v2.2 Phase D — announcement/offer banner (24h dismiss cooldown).
class _OfferBanner extends StatelessWidget {
  const _OfferBanner({required this.offer, required this.onDismiss});

  final AnnouncementItem offer;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    return MLDCard(
      borderColor: AppColors.premium.withValues(alpha: 0.45),
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Row(
        children: [
          Icon(
            offer.isOffer
                ? Icons.workspace_premium_outlined
                : Icons.campaign_outlined,
            color: offer.isOffer ? AppColors.premium : AppColors.info,
            size: 24,
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(offer.title,
                    style: AppTypography.body(weight: FontWeight.w700)),
                if (offer.body.isNotEmpty)
                  Text(
                    offer.body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTypography.caption(),
                  ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          if (offer.isOffer)
            MLDButton(
              label: 'View',
              expanded: false,
              height: 38,
              onPressed: () =>
                  Navigator.of(context).pushNamed(AppConstants.routePaywall),
            )
          else
            IconButton(
              onPressed: onDismiss,
              icon: const Icon(Icons.close, size: 18),
              tooltip: 'Dismiss',
            ),
        ],
      ),
    );
  }
}

class _Greeting extends StatelessWidget {
  const _Greeting();

  String get _greeting {
    final h = DateTime.now().hour;
    if (h < 5) return 'Late night focus';
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(_greeting, style: AppTypography.heading()),
        const SizedBox(height: 4),
        Text('Your phone is yours. Your rules are ready.',
            style: AppTypography.caption()),
      ],
    );
  }
}

/// READY / hero area (UI/UX §15). Inactive state only — active sessions
/// replace the entire shell.
class _HeroStatus extends StatelessWidget {
  const _HeroStatus();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.xxxl),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF151D35), AppColors.surface],
        ),
        borderRadius: BorderRadius.circular(AppRadii.hero),
        border: Border.all(color: AppColors.edge),
      ),
      child: Column(
        children: [
          Text('READY', style: AppTypography.label(color: AppColors.success)),
          const SizedBox(height: AppSpacing.md),
          Text('Your phone is yours.\nYour rules are ready.',
              textAlign: TextAlign.center,
              style: AppTypography.section()),
          const SizedBox(height: AppSpacing.xxl),
          Row(
            children: [
              Expanded(
                child: MLDButton(
                  label: 'START STUDY',
                  icon: Icons.menu_book_outlined,
                  onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeStudySetup),
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: MLDButton(
                  label: 'START DETOX',
                  variant: MLDButtonVariant.secondary,
                  icon: Icons.spa_outlined,
                  onPressed: () => Navigator.of(context).pushNamed(AppConstants.routeDetoxSetup),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// v2.1 Phase C: compact level + streak strip; taps into the Progress hub.
/// Hidden entirely when the progress layer is disabled.
class _ProgressStrip extends StatelessWidget {
  const _ProgressStrip();

  @override
  Widget build(BuildContext context) {
    final progress = AppStateScope.of(context).state.progress;
    if (progress == null || !progress.enabled) return const SizedBox.shrink();

    return InkWell(
      onTap: () => Navigator.of(context).pushNamed(AppConstants.routeProgress),
      borderRadius: BorderRadius.circular(AppRadii.card),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppColors.primary, AppColors.premium],
                ),
                borderRadius: BorderRadius.circular(AppRadii.sm),
              ),
              child: const Icon(Icons.terrain_outlined,
                  color: AppColors.onPrimary, size: 22),
            ),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    progress.levelName.isEmpty ? 'LEVEL ${progress.level}' : progress.levelName,
                    style: AppTypography.heading(),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${progress.dp} DP · ${progress.streakDays}-day streak',
                    style: AppTypography.caption(),
                  ),
                ],
              ),
            ),
            if (progress.frozen)
              const Icon(Icons.ac_unit, size: 18, color: AppColors.info)
            else if (progress.checkInAvailable)
              Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.md, vertical: AppSpacing.xs),
                decoration: BoxDecoration(
                  // v2.5.5 audit fix: withOpacity is deprecated (Flutter 3.27+).
                  color: AppColors.success.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(AppRadii.sm),
                ),
                child: Text('CHECK-IN READY',
                    style: AppTypography.label(color: AppColors.success)),
              )
            else
              const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

class _TodayProgress extends StatelessWidget {
  const _TodayProgress();

  @override
  Widget build(BuildContext context) {
    final app = AppStateScope.of(context);
    final shortsCount = app.state.shorts.warningCount;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        MLDSectionHeader(title: "TODAY'S PROGRESS"),
        Row(
          children: [
            const Expanded(child: MLDStatTile(label: 'Focus', value: '—')),
            const SizedBox(width: AppSpacing.md),
            const Expanded(child: MLDStatTile(label: 'Detox', value: '—')),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: MLDStatTile(
                label: 'Shorts attempts',
                value: '$shortsCount',
                accent: AppColors.warning,
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _QuickActions extends StatelessWidget {
  const _QuickActions();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        MLDSectionHeader(title: 'QUICK ACTIONS'),
        _action(
          context,
          icon: Icons.block_outlined,
          title: 'Shorts Blocker',
          subtitle: 'Reels, TikTok, YouTube Shorts',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeShortsSettings),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.alarm,
          title: 'Shockwave Alarm',
          subtitle: 'Solve the puzzle to stop it',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeAlarmSetup),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.monetization_on_outlined,
          title: 'Coins',
          subtitle: 'Earn → temporary unlock',
          trailing: MLDCoinBadge(amount: AppStateScope.of(context).state.coins),
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCoins),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.history,
          title: 'History',
          subtitle: 'Sessions, violations, outcomes',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeHistory),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.favorite_outline,
          title: 'Sinthia',
          subtitle: 'Your AI accountability partner',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCompanion),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.checklist,
          title: 'Tasks & Routines',
          subtitle: '+8 DP per task · daily routines',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeTasks),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.group_outlined,
          title: 'Community',
          subtitle: 'Public commits, friends, referral',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCommunity),
        ),
        const SizedBox(height: AppSpacing.xxl),
        MLDSectionHeader(title: 'HARD MODES'),
        _action(
          context,
          icon: Icons.phonelink_lock,
          title: 'Lock My Phone',
          subtitle: 'Full device lockdown · no exit but time',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeLockMyPhone),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.self_improvement,
          title: 'Monk Mode',
          subtitle: 'Allowlist-only discipline window',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeMonk),
        ),
        const SizedBox(height: AppSpacing.md),
        _action(
          context,
          icon: Icons.military_tech,
          title: 'Prime Commit',
          subtitle: 'All-or-nothing contract · TOTP exit',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routePrime),
        ),
      ],
    );
  }

  Widget _action(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    Widget? trailing,
    VoidCallback? onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadii.card),
      child: MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(AppSpacing.md),
              decoration: BoxDecoration(
                color: AppColors.primary.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(AppRadii.sm),
              ),
              child: Icon(icon, color: AppColors.primary, size: 22),
            ),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: AppTypography.body(weight: FontWeight.w700)),
                  Text(subtitle, style: AppTypography.caption()),
                ],
              ),
            ),
            if (trailing != null) trailing,
            const SizedBox(width: AppSpacing.sm),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Tab pages (lightweight wrappers around full feature screens)
// ---------------------------------------------------------------------------

class _FocusTabPage extends StatelessWidget {
  const _FocusTabPage();

  @override
  Widget build(BuildContext context) {
    return const StudySetupScreenRef();
  }
}

class _DetoxTabPage extends StatelessWidget {
  const _DetoxTabPage();

  @override
  Widget build(BuildContext context) {
    return const DetoxSetupScreenRef();
  }
}

class _InsightsTabPage extends StatelessWidget {
  const _InsightsTabPage();

  @override
  Widget build(BuildContext context) {
    return const InsightsScreenRef();
  }
}

class _SettingsTabPage extends StatelessWidget {
  const _SettingsTabPage();

  @override
  Widget build(BuildContext context) {
    return const SettingsScreenRef();
  }
}

/// Permission-problem state (UI/UX §63 #8).
class _PermissionProblemView extends StatelessWidget {
  const _PermissionProblemView({required this.onFix});

  final VoidCallback onFix;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: AppSpacing.screenH,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Icon(Icons.gpp_bad_outlined, size: 64, color: AppColors.warning),
            const SizedBox(height: AppSpacing.xxl),
            Text('FOCUS SYSTEM NEEDS ATTENTION',
                textAlign: TextAlign.center,
                style: AppTypography.heading()),
            const SizedBox(height: AppSpacing.md),
            Text(
              'A required permission is no longer active. Restore it to keep your focus system ready.',
              textAlign: TextAlign.center,
              style: AppTypography.body(color: AppColors.textSecondary),
            ),
            const SizedBox(height: AppSpacing.xxxl),
            MLDButton(label: 'FIX PERMISSIONS', onPressed: onFix),
          ],
        ),
      ),
    );
  }
}
