import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../shared/mld_widgets.dart';
import '../session/activation_screen.dart';

/// Mood Board (v2.9.7 r23) — the mood-first launcher.
///
/// Every mood below is a REAL enforcement mode wearing its signature accent
/// (tokens.dart "Mode accents"). Tapping a mood reveals its plan card:
/// Study Mood can quick-start straight into ActivationScreen using the EXACT
/// defaults of StudySetupScreen._start() (mode STUDY, STRICT, the four
/// distraction categories blocked, empty allowlist, no subject) — only the
/// duration is chosen here. Every other mood routes to its existing setup
/// flow, so no confirmation checkpoint is bypassed (UI/UX §16/§17/§85 stay
/// intact) and no new enforcement logic exists in this file.
class MoodBoardScreen extends StatefulWidget {
  const MoodBoardScreen({super.key});

  @override
  State<MoodBoardScreen> createState() => _MoodBoardScreenState();
}

class _MoodBoardScreenState extends State<MoodBoardScreen>
    with SingleTickerProviderStateMixin {
  /// Mood catalogue — grid order, Study flagship first.
  static const List<_MoodSpec> _moods = [
    _MoodSpec(
      id: 'study',
      name: 'Study Mood',
      tagline: 'Books open. Distractions closed.',
      icon: Icons.menu_book_outlined,
      accent: AppColors.study,
      intensity: 2,
      facts: [
        (Icons.lock_outline, 'Socials locked'),
        (Icons.smart_display_outlined, 'Shorts blocked'),
        (Icons.sticky_note_2_outlined, 'Notes allowed'),
      ],
      setupRoute: AppConstants.routeStudySetup,
      quickStart: true,
    ),
    _MoodSpec(
      id: 'detox',
      name: 'Detox Mood',
      tagline: 'Full disconnect. The feed stops here.',
      icon: Icons.spa_outlined,
      accent: AppColors.detox,
      intensity: 3,
      facts: [
        (Icons.block_outlined, 'Feed blocked'),
        (Icons.verified_user_outlined, 'Strict rules'),
        (Icons.battery_charging_full_outlined, 'Deep reset'),
      ],
      setupRoute: AppConstants.routeDetoxSetup,
    ),
    _MoodSpec(
      id: 'monk',
      name: 'Monk Mood',
      tagline: 'Total silence. Nothing gets through.',
      icon: Icons.self_improvement,
      accent: AppColors.monk,
      intensity: 3,
      facts: [
        (Icons.do_not_disturb_on_outlined, 'All blocked'),
        (Icons.key_off_outlined, 'No escapes'),
        (Icons.nightlight_outlined, 'Deep silence'),
      ],
      setupRoute: AppConstants.routeMonk,
    ),
    _MoodSpec(
      id: 'lock',
      name: 'Lockdown Mood',
      tagline: 'The phone goes to sleep.',
      icon: Icons.phonelink_lock,
      accent: AppColors.lock,
      intensity: 3,
      facts: [
        (Icons.ac_unit_outlined, 'Phone frozen'),
        (Icons.timer_outlined, 'Timed release'),
        (Icons.call_outlined, 'SOS calling'),
      ],
      setupRoute: AppConstants.routeLockMyPhone,
    ),
    _MoodSpec(
      id: 'calm',
      name: 'Calm Mood',
      tagline: 'A deliberate pause. Think, then continue.',
      icon: Icons.pause_circle_outline,
      accent: AppColors.safety,
      intensity: 1,
      facts: [
        (Icons.hourglass_empty, 'Short pause'),
        (Icons.psychology_outlined, 'Think first'),
        (Icons.waves_outlined, 'Gentle exit'),
      ],
      setupRoute: AppConstants.routeSafety,
    ),
    _MoodSpec(
      id: 'prime',
      name: 'Prime Mood',
      tagline: 'The ultimate commitment.',
      icon: Icons.military_tech,
      accent: AppColors.prime,
      intensity: 3,
      facts: [
        (Icons.password_outlined, 'TOTP exit'),
        (Icons.event_available_outlined, 'Long commit'),
        (Icons.workspace_premium_outlined, 'Max reward'),
      ],
      setupRoute: AppConstants.routePrime,
    ),
  ];

  /// Study quick-start presets (mirrors StudySetupScreen's chip ladder).
  static const List<int> _studyDurations = [25, 45, 60, 90];

  late final AnimationController _enter = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  )..forward();

  /// Study is the flagship mood — pre-selected so the console is never empty.
  int _selected = 0;

  /// Quick-start duration; defaults to the same 60 min as Study setup.
  int _studyMinutes = AppConstants.defaultStudyMinutes;

  _MoodSpec get _mood => _moods[_selected];

  /// Time-aware hero line — the board should feel alive, not static.
  String get _vibe {
    final h = DateTime.now().hour;
    if (h < 5) return 'Late-night hours — Study Mood hits different right now.';
    if (h < 12) return 'Fresh mind, quiet morning. Prime time for Study Mood.';
    if (h < 17) return 'Afternoon dip incoming — lock in before it lands.';
    if (h < 22) return 'Evening reset. A Detox Mood pays off tonight.';
    return 'Wind-down hours. Calm Mood — or just lock the phone.';
  }

  void _select(int i) {
    if (i == _selected) return;
    HapticFeedback.selectionClick();
    setState(() => _selected = i);
  }

  /// Quick-start = StudySetupScreen._start() with only the duration swapped.
  /// The activation screen still runs the full native validation sequence
  /// (TRD §95) — this is a shortcut to the SAME door, not around it.
  void _quickStartStudy() {
    HapticFeedback.mediumImpact();
    Navigator.of(context).pushNamed(
      AppConstants.routeActivation,
      arguments: ActivationArgs(
        mode: 'STUDY',
        durationMinutes: _studyMinutes,
        strictness: 'STRICT',
        blockedCategories: const ['social', 'games', 'shorts', 'entertainment'],
        allowedPackages: const [],
        subject: '',
      ),
    );
  }

  void _openSetup() => Navigator.of(context).pushNamed(_mood.setupRoute);

  String _fmtDuration(int m) => m >= 60
      ? (m % 60 == 0
          ? '${m ~/ 60} hour${m >= 120 ? 's' : ''}'
          : '${m ~/ 60}h ${m % 60}m')
      : '$m min';

  @override
  void dispose() {
    _enter.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Mood Board')),
      body: MLDAppBackdrop(
        child: SafeArea(
          child: ListView(
            padding: AppSpacing.screenH.copyWith(
              top: AppSpacing.xl,
              bottom: AppSpacing.xxxl,
            ),
            children: [
              _hero(),
              const SizedBox(height: AppSpacing.xxl),
              _grid(),
              const SizedBox(height: AppSpacing.xl),
              _console(),
            ],
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------
  // Hero — status pill, display headline, time-aware line.
  // ---------------------------------------------------------------------

  Widget _hero() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.md, vertical: 7),
              decoration: BoxDecoration(
                color: AppColors.elevated.withValues(alpha: .72),
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: AppColors.edge),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 6,
                    height: 6,
                    decoration: const BoxDecoration(
                        shape: BoxShape.circle, color: AppColors.success),
                  ),
                  const SizedBox(width: 7),
                  Text(
                    '6 MOODS · ONE TAP TO LOCK IN',
                    style: AppTypography.label(color: AppColors.textSecondary)
                        .copyWith(fontSize: 9, letterSpacing: 1),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        Text('Pick your mood.', style: AppTypography.display()),
        const SizedBox(height: AppSpacing.sm),
        Text(
          _vibe,
          style: AppTypography.body(
              color: AppColors.textSecondary, fontSize: 14),
        ),
      ],
    );
  }

  // ---------------------------------------------------------------------
  // Mood grid — 2×3 cards, staggered entrance, tap to select.
  // ---------------------------------------------------------------------

  Widget _grid() {
    return GridView.count(
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: AppSpacing.md,
      crossAxisSpacing: AppSpacing.md,
      childAspectRatio: 1.04,
      children: [
        for (var i = 0; i < _moods.length; i++)
          FadeTransition(
            opacity: _cardAnim(i),
            child: SlideTransition(
              position: Tween<Offset>(
                      begin: const Offset(0, .22), end: Offset.zero)
                  .animate(_cardAnim(i)),
              child: _MoodCard(
                mood: _moods[i],
                selected: _selected == i,
                onSelect: () => _select(i),
              ),
            ),
          ),
      ],
    );
  }

  CurvedAnimation _cardAnim(int i) {
    // 6 cards → begins 0…0.275, ends ≤ 0.695 — always inside [0, 1], so no
    // clamp() is needed (and clamp() returns num, which Interval rejects).
    final begin = i * .055;
    return CurvedAnimation(
      parent: _enter,
      curve: Interval(begin, begin + .42, curve: Curves.easeOutCubic),
    );
  }

  // ---------------------------------------------------------------------
  // Console — the selected mood's plan. Study gets quick-start; every other
  // mood routes to its existing setup flow (no bypassed confirmations).
  // ---------------------------------------------------------------------

  Widget _console() {
    return FadeTransition(
      opacity: CurvedAnimation(
          parent: _enter, curve: const Interval(.28, .85, curve: Curves.easeOut)),
      child: AnimatedSize(
        duration: AppDurations.slow,
        curve: Curves.easeOutCubic,
        child: AnimatedSwitcher(
          duration: AppDurations.normal,
          switchInCurve: Curves.easeOutCubic,
          switchOutCurve: Curves.easeIn,
          transitionBuilder: (child, anim) => FadeTransition(
            opacity: anim,
            child: SlideTransition(
              position:
                  Tween(begin: const Offset(0, .04), end: Offset.zero)
                      .animate(anim),
              child: child,
            ),
          ),
          child: KeyedSubtree(
            key: ValueKey(_mood.id),
            child:
                _mood.quickStart ? _studyConsole(_mood) : _setupConsole(_mood),
          ),
        ),
      ),
    );
  }

  Widget _studyConsole(_MoodSpec m) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.xxl),
      decoration: _consoleDecoration(m.accent),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _consoleHeader(m),
          const SizedBox(height: AppSpacing.xxl),
          _factsRow(m),
          const SizedBox(height: AppSpacing.xxl),
          const MLDSectionHeader(title: 'SESSION LENGTH'),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [for (final d in _studyDurations) _durationChip(d)],
          ),
          const SizedBox(height: AppSpacing.lg),
          const MLDWarningBanner(
            message:
                'Rules lock the moment the session starts and stay locked until it legitimately ends. Emergency calling always remains available.',
            tone: MLDBannerTone.info,
          ),
          const SizedBox(height: AppSpacing.lg),
          Row(
            children: [
              Expanded(
                child: MLDButton(
                  label: 'START STUDY NOW',
                  icon: Icons.play_arrow_rounded,
                  onPressed: _quickStartStudy,
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: MLDButton(
                  label: 'FINE-TUNE',
                  variant: MLDButtonVariant.secondary,
                  icon: Icons.tune_rounded,
                  onPressed: _openSetup,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _setupConsole(_MoodSpec m) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.xxl),
      decoration: _consoleDecoration(m.accent),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _consoleHeader(m),
          const SizedBox(height: AppSpacing.xxl),
          _factsRow(m),
          const SizedBox(height: AppSpacing.xxl),
          MLDButton(
            label: 'CONFIGURE ${m.name.toUpperCase()}',
            icon: Icons.arrow_forward_rounded,
            onPressed: _openSetup,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            'Opens the ${m.name} setup flow — you review every rule before anything locks.',
            textAlign: TextAlign.center,
            style: AppTypography.caption(),
          ),
        ],
      ),
    );
  }

  BoxDecoration _consoleDecoration(Color accent) => BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            accent.withValues(alpha: .16),
            AppColors.surface.withValues(alpha: .97),
            AppColors.background.withValues(alpha: .55),
          ],
          stops: const [0, .45, 1],
        ),
        borderRadius: BorderRadius.circular(AppRadii.hero),
        border: Border.all(color: accent.withValues(alpha: .5), width: 1.4),
        boxShadow: [
          BoxShadow(
              color: Colors.black.withValues(alpha: .38),
              blurRadius: 24,
              offset: const Offset(0, 12)),
          BoxShadow(
              color: accent.withValues(alpha: .18),
              blurRadius: 30,
              spreadRadius: -6),
        ],
      );

  Widget _consoleHeader(_MoodSpec m) {
    return Row(
      children: [
        Container(
          width: 64,
          height: 64,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                m.accent.withValues(alpha: .34),
                m.accent.withValues(alpha: .10),
              ],
            ),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: m.accent.withValues(alpha: .55)),
            boxShadow: [
              BoxShadow(
                  color: m.accent.withValues(alpha: .35),
                  blurRadius: 22,
                  offset: const Offset(0, 6)),
            ],
          ),
          child: Icon(m.icon, color: m.accent, size: 30),
        ),
        const SizedBox(width: AppSpacing.lg),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(m.name,
                  style: AppTypography.heading().copyWith(fontSize: 21)),
              const SizedBox(height: 3),
              Text(m.tagline,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.caption()),
            ],
          ),
        ),
        _intensityPill(m),
      ],
    );
  }

  /// Intensity dots (1–3). Color is never the only signal — the fact chips
  /// and setup flows carry the semantic load (UI/UX §4).
  Widget _intensityPill(_MoodSpec m) {
    return Container(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      decoration: BoxDecoration(
        color: m.accent.withValues(alpha: .12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: m.accent.withValues(alpha: .4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < 3; i++) ...[
            if (i > 0) const SizedBox(width: 3),
            Container(
              width: 6,
              height: 6,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: i < m.intensity
                    ? m.accent
                    : m.accent.withValues(alpha: .22),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _factsRow(_MoodSpec m) {
    return Row(
      children: [
        for (var i = 0; i < m.facts.length; i++) ...[
          if (i > 0) const SizedBox(width: AppSpacing.md),
          Expanded(child: _factChip(m, i)),
        ],
      ],
    );
  }

  Widget _factChip(_MoodSpec m, int i) {
    final (icon, text) = m.facts[i];
    return Container(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.sm, vertical: AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.elevated.withValues(alpha: .55),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        border: Border.all(color: m.accent.withValues(alpha: .18)),
      ),
      child: Column(
        children: [
          Icon(icon, size: 18, color: m.accent),
          const SizedBox(height: AppSpacing.sm),
          Text(
            text,
            textAlign: TextAlign.center,
            style: AppTypography.caption(),
          ),
        ],
      ),
    );
  }

  Widget _durationChip(int d) {
    final selected = _studyMinutes == d;
    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        setState(() => _studyMinutes = d);
      },
      child: AnimatedContainer(
        duration: AppDurations.fast,
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.xl, vertical: AppSpacing.md),
        decoration: BoxDecoration(
          color: selected
              ? AppColors.study.withValues(alpha: .16)
              : AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.sm),
          border: Border.all(
              color: selected ? AppColors.study : AppColors.edge,
              width: selected ? 1.6 : 1),
        ),
        child: Text(
          _fmtDuration(d),
          style: AppTypography.body(
            color: selected ? AppColors.study : AppColors.textSecondary,
            weight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------
// Mood spec — one real enforcement mode in its signature accent.
// ---------------------------------------------------------------------

class _MoodSpec {
  const _MoodSpec({
    required this.id,
    required this.name,
    required this.tagline,
    required this.icon,
    required this.accent,
    required this.intensity,
    required this.facts,
    required this.setupRoute,
    this.quickStart = false,
  });

  final String id;

  /// Display name ("Study Mood") — maps to a real mode ('STUDY').
  final String name;
  final String tagline;
  final IconData icon;
  final Color accent;

  /// 1 (gentle) … 3 (maximum) — how hard the mode locks.
  final int intensity;

  /// Three (icon, label) fact chips shown in the console.
  final List<(IconData, String)> facts;

  /// The existing setup route for this mode.
  final String setupRoute;

  /// Only Study Mood quick-starts (safe defaults, UI/UX §85 preserved).
  final bool quickStart;
}

// ---------------------------------------------------------------------
// Mood card — glass tile with accent aura; press-scale + selected glow.
// ---------------------------------------------------------------------

class _MoodCard extends StatefulWidget {
  const _MoodCard({
    required this.mood,
    required this.selected,
    required this.onSelect,
  });

  final _MoodSpec mood;
  final bool selected;
  final VoidCallback onSelect;

  @override
  State<_MoodCard> createState() => _MoodCardState();
}

class _MoodCardState extends State<_MoodCard> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final m = widget.mood;
    final accent = m.accent;
    final selected = widget.selected;

    return Semantics(
      button: true,
      selected: selected,
      label: m.name,
      child: GestureDetector(
        onTapDown: (_) => setState(() => _pressed = true),
        onTapUp: (_) => setState(() => _pressed = false),
        onTapCancel: () => setState(() => _pressed = false),
        onTap: widget.onSelect,
        child: AnimatedScale(
          scale: _pressed ? .97 : 1,
          duration: AppDurations.fast,
          curve: Curves.easeOut,
          child: AnimatedContainer(
            duration: AppDurations.normal,
            curve: Curves.easeOutCubic,
            padding: const EdgeInsets.all(AppSpacing.lg),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  accent.withValues(alpha: selected ? .26 : .12),
                  AppColors.surface.withValues(alpha: .96),
                  AppColors.background.withValues(alpha: .5),
                ],
              ),
              borderRadius: BorderRadius.circular(AppRadii.card),
              border: Border.all(
                color: selected ? accent : accent.withValues(alpha: .22),
                width: selected ? 1.8 : 1,
              ),
              boxShadow: selected
                  ? [
                      BoxShadow(
                          color: accent.withValues(alpha: .32),
                          blurRadius: 26,
                          offset: const Offset(0, 8)),
                      BoxShadow(
                          color: Colors.black.withValues(alpha: .34),
                          blurRadius: 16,
                          offset: const Offset(0, 10)),
                    ]
                  : [
                      BoxShadow(
                          color: Colors.black.withValues(alpha: .3),
                          blurRadius: 16,
                          offset: const Offset(0, 10)),
                    ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      width: 46,
                      height: 46,
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            accent.withValues(alpha: .32),
                            accent.withValues(alpha: .08),
                          ],
                        ),
                        borderRadius: BorderRadius.circular(15),
                        border: Border.all(
                            color:
                                accent.withValues(alpha: selected ? .7 : .35)),
                      ),
                      child: Icon(m.icon, color: accent, size: 22),
                    ),
                    const Spacer(),
                    AnimatedContainer(
                      duration: AppDurations.normal,
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: selected
                            ? accent
                            : AppColors.elevated.withValues(alpha: .8),
                        border: Border.all(
                            color: selected ? accent : AppColors.edge),
                      ),
                      child: selected
                          ? const Icon(Icons.check_rounded,
                              size: 15, color: AppColors.onPrimary)
                          : null,
                    ),
                  ],
                ),
                const Spacer(),
                Text(m.name, style: AppTypography.body(weight: FontWeight.w800)),
                const SizedBox(height: 3),
                Text(
                  m.tagline,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.caption(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
