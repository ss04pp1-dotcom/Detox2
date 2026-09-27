import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// Five philosophy screens before any permission is requested (UI/UX §11,
/// PRD §5.1). No signature yet — that's the Pact screen that follows.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final PageController _controller = PageController();
  int _page = 0;

  static const _pages = [
    (title: 'TAKE BACK\nCONTROL', body: 'Your phone should work for you,\nnot against you.', image: 'assets/ui/onboarding_control.png', accent: AppColors.primary),
    (title: 'BUILD YOUR\nFOCUS', body: 'Study when you need to.\nDisconnect when you choose to.', image: 'assets/ui/onboarding_focus.png', accent: AppColors.safety),
    (title: 'MAKE THE\nCOMMITMENT', body: 'Once a session starts,\nyour rules become active.', image: 'assets/ui/onboarding_pact.png', accent: AppColors.monk),
    (title: 'PROTECT YOUR\nATTENTION', body: "MAXLEVEL DETOX uses Android's\nsupported controls to keep your rules active.", image: 'assets/ui/onboarding_permission.png', accent: AppColors.success),
    (title: 'CHOOSE YOUR\nLEVEL', body: 'Start small, build consistency,\nand level up your discipline.', image: 'assets/ui/onboarding_modes.png', accent: AppColors.premium),
  ];

  void _next() {
    HapticFeedback.selectionClick();
    if (_page < _pages.length - 1) {
      _controller.nextPage(duration: AppDurations.slow, curve: Curves.easeOutCubic);
    } else {
      Navigator.of(context).pushNamed(AppConstants.routePact);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = _pages[_page];
    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [AppColors.background, p.accent.withValues(alpha: .07), AppColors.background]),
        ),
        child: SafeArea(
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
              child: Row(children: [
                if (_page > 0) IconButton(onPressed: () => _controller.previousPage(duration: AppDurations.slow, curve: Curves.easeOutCubic), icon: const Icon(Icons.arrow_back_rounded)) else const SizedBox(width: 48),
                const Spacer(),
                Text('MAXLEVEL', style: AppTypography.label(color: p.accent)),
                const Spacer(),
                Text('${_page + 1}/${_pages.length}', style: AppTypography.caption()),
              ]),
            ),
            Expanded(
              child: PageView.builder(
                controller: _controller,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _pages.length,
                onPageChanged: (i) => setState(() => _page = i),
                itemBuilder: (context, i) {
                  final page = _pages[i];
                  return Padding(
                    padding: AppSpacing.screenH,
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Container(
                        constraints: const BoxConstraints(maxWidth: 390),
                        decoration: BoxDecoration(borderRadius: BorderRadius.circular(AppRadii.hero), boxShadow: [BoxShadow(color: page.accent.withValues(alpha: .12), blurRadius: 40)]),
                        clipBehavior: Clip.antiAlias,
                        child: Image.asset(page.image, fit: BoxFit.cover),
                      ),
                      const SizedBox(height: AppSpacing.xxl),
                      Text(page.title, textAlign: TextAlign.center, style: AppTypography.display().copyWith(fontSize: 32)),
                      const SizedBox(height: AppSpacing.md),
                      Text(page.body, textAlign: TextAlign.center, style: AppTypography.body(color: AppColors.textSecondary, weight: FontWeight.w500)),
                    ]),
                  );
                },
              ),
            ),
            Padding(
              padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
              child: Column(children: [
                Row(mainAxisAlignment: MainAxisAlignment.center, children: List.generate(_pages.length, (i) => AnimatedContainer(duration: AppDurations.normal, margin: const EdgeInsets.symmetric(horizontal: 4), width: i == _page ? 26 : 7, height: 7, decoration: BoxDecoration(color: i == _page ? p.accent : AppColors.edge, borderRadius: BorderRadius.circular(99))))),
                const SizedBox(height: AppSpacing.xl),
                MLDButton(label: _page == _pages.length - 1 ? 'MEET YOUR SYSTEM' : 'CONTINUE', icon: _page == _pages.length - 1 ? Icons.arrow_forward_rounded : Icons.chevron_right_rounded, onPressed: _next),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

/// The Pact — explicit agreement before strict enforcement can ever be
/// activated (PRD §5.1). Includes the user's typed signature.
class PactScreen extends StatefulWidget {
  const PactScreen({super.key});

  @override
  State<PactScreen> createState() => _PactScreenState();
}

class _PactScreenState extends State<PactScreen> {
  final _nameController = TextEditingController();
  bool _agree1 = false;
  bool _agree2 = false;
  bool _agree3 = false;
  bool _submitting = false;

  bool get _canSign =>
      _nameController.text.trim().length >= 3 && _agree1 && _agree2 && _agree3;

  Future<void> _sign() async {
    if (!_canSign || _submitting) return;
    setState(() => _submitting = true);
    HapticFeedback.heavyImpact();

    // Persist the pact natively — enforcement can never activate without it.
    // v2.5.5 audit fix: the result was ignored — on a native failure the
    // user was navigated onward anyway and bounced back by the splash gate
    // on the next cold start. Only advance when the pact is recorded.
    final ok = await NativeBridge.instance.acceptPact();
    if (!mounted) return;
    if (!ok) {
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not save the pact — try again.')),
      );
      return;
    }

    Navigator.of(context).pushNamed(AppConstants.routePermissions);
  }

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'The Commitment Pact')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              MLDCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('MY PACT', style: AppTypography.label()),
                    const SizedBox(height: AppSpacing.md),
                    Text(
                      'I commit to using MAXLEVEL DETOX honestly and to following my own rules. '
                      'No shortcuts. No excuses.\n\n'
                      'A better me starts now.',
                      style: AppTypography.body(height: 1.7),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              _check(
                value: _agree1,
                onChanged: (v) => setState(() => _agree1 = v),
                text: 'I understand what Study Mode and Detox Mode do, and that a started session stays active until it legitimately ends.',
              ),
              _check(
                value: _agree2,
                onChanged: (v) => _agree2 = v,
                text: 'I understand temporary unlock, warnings, Cage and bailout penalties — and that coins are earned only through completed rewarded ads.',
              ),
              _check(
                value: _agree3,
                onChanged: (v) => _agree3 = v,
                text: 'I understand Android limits what any app can enforce, and that emergency calling always remains available.',
              ),
              const SizedBox(height: AppSpacing.xxl),
              Text('YOUR SIGNATURE', style: AppTypography.label()),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                controller: _nameController,
                onChanged: (_) => setState(() {}),
                textCapitalization: TextCapitalization.words,
                style: AppTypography.body(),
                decoration: InputDecoration(
                  hintText: 'Type your name to sign',
                  filled: true,
                  fillColor: AppColors.surface,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(AppRadii.sm),
                    borderSide: const BorderSide(color: AppColors.edge),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(AppRadii.sm),
                    borderSide: const BorderSide(color: AppColors.edge),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(AppRadii.sm),
                    borderSide: const BorderSide(color: AppColors.primary),
                  ),
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              MLDButton(
                label: 'I AGREE — SIGN THE PACT',
                onPressed: _canSign ? _sign : null,
                loading: _submitting,
              ),
              const SizedBox(height: AppSpacing.md),
              Text(
                'You can uninstall or change anything in Normal Mode.\nNothing is locked yet.',
                textAlign: TextAlign.center,
                style: AppTypography.caption(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _check({required bool value, required ValueChanged<bool> onChanged, required String text}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      child: InkWell(
        onTap: () => onChanged(!value),
        borderRadius: BorderRadius.circular(AppRadii.sm),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.sm),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                value ? Icons.check_box : Icons.check_box_outline_blank,
                color: value ? AppColors.success : AppColors.textSecondary,
                size: 22,
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(child: Text(text, style: AppTypography.caption())),
            ],
          ),
        ),
      ),
    );
  }
}
