import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/models.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';
import '../community/community_screen.dart';
import '../insights/insights_screen.dart';
import '../session/active_session_screen.dart';
import '../settings/settings_screen.dart';
import '../tasks/tasks_screen.dart';

/// Dashboard + bottom navigation shell (UI/UX §9, §14, §63).
///
/// v2.6 reference design: five tabs — Home, Insights, Community, Tasks,
/// Settings — with the six enforcement modes launched from the Home
/// "Quick Actions" grid, each in its signature color.
///
/// Home must answer three questions instantly:
///   1. What is my current state?  2. How much have I focused?  3. What next?
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

    // During an active enforcement session the session surface owns the
    // whole app shell. There is intentionally no bottom navigation here:
    // Insights/Community/Tasks/Settings must not become an in-app escape
    // route from Study Mode. The native SessionKiosk remains the real hard
    // enforcement layer outside the app.
    if (app.state.sessionActive || app.state.cageActive) {
      return const ActiveSessionShell();
    }

    final pages = [
      const _HomePage(),
      const _InsightsTabPage(),
      const _CommunityTabPage(),
      const _TasksTabPage(),
      const _SettingsTabPage(),
    ];

    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [AppColors.background, Color(0xFF07182C)],
          ),
        ),
        child: IndexedStack(index: _tab, children: pages),
      ),
      bottomNavigationBar: NavigationBarTheme(
        data: NavigationBarThemeData(
          backgroundColor: const Color(0xEE0D1324),
          indicatorColor: AppColors.primary.withValues(alpha: .16),
          elevation: 0,
          height: 74,
          labelTextStyle: WidgetStatePropertyAll(AppTypography.caption().copyWith(fontSize: 10, fontWeight: FontWeight.w700)),
          iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(color: states.contains(WidgetState.selected) ? AppColors.primary : AppColors.textSecondary, size: 23)),
        ),
        child: NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          destinations: const [
            NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home_rounded), label: 'Home'),
            NavigationDestination(icon: Icon(Icons.insights_outlined), selectedIcon: Icon(Icons.insights_rounded), label: 'Insights'),
            NavigationDestination(icon: Icon(Icons.groups_outlined), selectedIcon: Icon(Icons.groups_rounded), label: 'Community'),
            NavigationDestination(icon: Icon(Icons.checklist_outlined), selectedIcon: Icon(Icons.checklist_rounded), label: 'Tasks'),
            NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings_rounded), label: 'Settings'),
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
// HOME (v2.6 reference design — Screens 17/18/25)
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
            const MLDBrandHeader(),
            const SizedBox(height: AppSpacing.xl),
            const _Greeting(),
            // v2.7 r13 — emergency dialer-only lockdown state banner
            // (renders nothing while inactive).
            const MLDEmergencyBanner(),
            if (_offer != null) ...[
              const SizedBox(height: AppSpacing.lg),
              _OfferBanner(
                offer: _offer!,
                onDismiss: _dismissOffer,
              ),
            ],
            const SizedBox(height: AppSpacing.xxl),
            const _StatsRow(),
            const SizedBox(height: AppSpacing.xxl),
            const _TodayFocus(),
            const SizedBox(height: AppSpacing.xxl),
            const _QuickActions(),
            const SizedBox(height: AppSpacing.xxl),
            const _MoreTools(),
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

/// Header (mockup Screen 17): greeting + tagline, avatar chip on the right.
class _Greeting extends StatelessWidget {
  const _Greeting();

  String get _greeting {
    final h = DateTime.now().hour;
    if (h < 5) return 'Late night focus';
    if (h < 12) return 'Good morning.';
    if (h < 17) return 'Good afternoon.';
    return 'Good evening.';
  }

  @override
  Widget build(BuildContext context) {
    final progress = AppStateScope.of(context).state.progress;

    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_greeting, style: AppTypography.heading()),
              const SizedBox(height: 4),
              Text('Focus today, build tomorrow.',
                  style: AppTypography.caption()),
            ],
          ),
        ),
        if (progress != null && progress.enabled) ...[
          const SizedBox(width: AppSpacing.md),
          Container(
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.lg, vertical: AppSpacing.md),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.primary, AppColors.premium],
              ),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Row(
              children: [
                const Icon(Icons.terrain_outlined,
                    color: AppColors.onPrimary, size: 16),
                const SizedBox(width: 6),
                Text(
                  'LVL ${progress.level}',
                  style: AppTypography.label(color: AppColors.onPrimary)
                      .copyWith(letterSpacing: 0.6),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}

/// Stats row (mockup Screen 17): Current Streak + DP Points cards.
class _StatsRow extends StatelessWidget {
  const _StatsRow();

  @override
  Widget build(BuildContext context) {
    final progress = AppStateScope.of(context).state.progress;
    final shortsCount = AppStateScope.of(context).state.shorts.warningCount;
    if (progress == null || !progress.enabled) {
      // Progress layer disabled — keep the row useful with what remains.
      return Row(
        children: [
          Expanded(
            child: MLDStatTile(
              label: 'Shorts attempts',
              value: '$shortsCount',
              accent: AppColors.warning,
            ),
          ),
        ],
      );
    }

    return Row(
      children: [
        Expanded(
          child: _statCard(
            context: context,
            icon: Icons.local_fire_department_outlined,
            accent: AppColors.monk,
            label: 'Current Streak',
            value: '${progress.streakDays} Days',
            onTap: () => Navigator.of(context).pushNamed(AppConstants.routeProgress),
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: _statCard(
            context: context,
            icon: Icons.terrain_outlined,
            accent: AppColors.primary,
            label: 'DP Points',
            value: '${progress.dp}',
            onTap: () => Navigator.of(context).pushNamed(AppConstants.routeProgress),
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: _statCard(
            context: context,
            icon: Icons.block_outlined,
            accent: AppColors.warning,
            label: 'Shorts attempts',
            value: '$shortsCount',
            onTap: () => Navigator.of(context).pushNamed(AppConstants.routeShortsSettings),
          ),
        ),
      ],
    );
  }

  Widget _statCard({
    required BuildContext context,
    required IconData icon,
    required Color accent,
    required String label,
    required String value,
    VoidCallback? onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadii.sm),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(color: AppColors.edge),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: accent, size: 20),
            const SizedBox(height: AppSpacing.md),
            Text(value,
                style: AppTypography.heading().copyWith(fontSize: 20)),
            const SizedBox(height: 2),
            Text(label, style: AppTypography.caption()),
          ],
        ),
      ),
    );
  }
}

/// "Today's Focus" hero (mockup Screens 17/18): when no session is running
/// this is the No Active Session card with the Choose Mode CTA that jumps
/// straight to the Quick Actions grid.
class _TodayFocus extends StatelessWidget {
  const _TodayFocus();

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
          Container(
            width: 84,
            height: 84,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.primary.withValues(alpha: 0.12),
              border: Border.all(color: AppColors.primary.withValues(alpha: 0.35)),
            ),
            child: const Icon(Icons.self_improvement,
                size: 40, color: AppColors.primary),
          ),
          const SizedBox(height: AppSpacing.xxl),
          Text('No Active Session', style: AppTypography.section()),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'Choose a mode and start your journey to a better you.',
            textAlign: TextAlign.center,
            style: AppTypography.body(color: AppColors.textSecondary),
          ),
          const SizedBox(height: AppSpacing.xxl),
          // v2.9.7 r23 — the Mood Board is the flagship entry point; the two
          // one-tap shortcuts stay for muscle memory.
          MLDButton(
            label: 'PICK A MOOD',
            icon: Icons.auto_awesome_outlined,
            onPressed: () =>
                Navigator.of(context).pushNamed(AppConstants.routeMoodBoard),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              Expanded(
                child: MLDButton(
                  label: 'START STUDY',
                  variant: MLDButtonVariant.secondary,
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

/// Quick Actions (mockup Screen 25): the six enforcement modes, each in its
/// signature accent — the heart of the v2.6 reference design.
class _QuickActions extends StatelessWidget {
  const _QuickActions();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const MLDSectionHeader(title: 'QUICK ACTIONS'),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: AppSpacing.md,
          crossAxisSpacing: AppSpacing.md,
          childAspectRatio: 1.28,
          children: [
            MLDModeTile(
              icon: Icons.menu_book_outlined,
              title: 'Study Mode',
              subtitle: 'Focus & Learn',
              accent: AppColors.study,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routeStudySetup),
            ),
            MLDModeTile(
              icon: Icons.spa_outlined,
              title: 'Detox Mode',
              subtitle: 'Full Digital Detox',
              accent: AppColors.detox,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routeDetoxSetup),
            ),
            MLDModeTile(
              icon: Icons.self_improvement,
              title: 'Monk Mode',
              subtitle: 'No Distractions',
              accent: AppColors.monk,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routeMonk),
            ),
            MLDModeTile(
              icon: Icons.phonelink_lock,
              title: 'Lock Phone',
              subtitle: 'Full Lock',
              accent: AppColors.lock,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routeLockMyPhone),
            ),
            MLDModeTile(
              icon: Icons.pause_circle_outline,
              title: 'Safety Pause',
              subtitle: 'Think & Continue',
              accent: AppColors.safety,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routeSafety),
            ),
            MLDModeTile(
              icon: Icons.military_tech,
              title: 'Prime Commit',
              subtitle: 'Ultimate Focus',
              accent: AppColors.prime,
              onTap: () => Navigator.of(context).pushNamed(AppConstants.routePrime),
            ),
          ],
        ),
      ],
    );
  }
}

/// Secondary tools — every entry point that existed on the old home stays
/// reachable; the modes grid above is the primary surface, this list keeps
/// the rest one tap away.
class _MoreTools extends StatelessWidget {
  const _MoreTools();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const MLDSectionHeader(title: 'MORE TOOLS'),
        _tool(
          context,
          icon: Icons.block_outlined,
          title: 'Shorts Blocker',
          subtitle: 'Reels, TikTok, YouTube Shorts',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeShortsSettings),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.alarm,
          title: 'Shockwave Alarm',
          subtitle: 'Solve the puzzle to stop it',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeAlarmSetup),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.monetization_on_outlined,
          title: 'Coins',
          subtitle: 'Earn → temporary unlock',
          trailing: MLDCoinBadge(amount: AppStateScope.of(context).state.coins),
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCoins),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.history,
          title: 'History',
          subtitle: 'Sessions, violations, outcomes',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeHistory),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.favorite_outline,
          title: 'Sinthia',
          subtitle: 'Your AI accountability partner',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCompanion),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.checklist,
          title: 'Tasks & Routines',
          subtitle: '+8 DP per task · daily routines',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeTasks),
        ),
        const SizedBox(height: AppSpacing.md),
        _tool(
          context,
          icon: Icons.group_outlined,
          title: 'Community',
          subtitle: 'Public commits, friends, referral',
          onTap: () => Navigator.of(context).pushNamed(AppConstants.routeCommunity),
        ),
      ],
    );
  }

  Widget _tool(
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

class _InsightsTabPage extends StatelessWidget {
  const _InsightsTabPage();

  @override
  Widget build(BuildContext context) {
    return const InsightsScreenRef();
  }
}

class _CommunityTabPage extends StatelessWidget {
  const _CommunityTabPage();

  @override
  Widget build(BuildContext context) {
    return const CommunityScreen();
  }
}

class _TasksTabPage extends StatelessWidget {
  const _TasksTabPage();

  @override
  Widget build(BuildContext context) {
    return const TasksScreen();
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
