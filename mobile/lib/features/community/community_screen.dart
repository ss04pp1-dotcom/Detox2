import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// CommunityScreen (v2.5 r9 → v2.5.8) — SS accountability-graph port:
/// public commitment feed (cheerable), friends (search/add/remove), referral
/// (code + share + rewards) + the roadmap leaderboard tab (global DP
/// ranking + clubs). Requires sign-in; guest state is explained.
class CommunityScreen extends StatefulWidget {
  const CommunityScreen({super.key});

  @override
  State<CommunityScreen> createState() => _CommunityScreenState();
}

class _CommunityScreenState extends State<CommunityScreen>
    with SingleTickerProviderStateMixin {
  // v2.5.8 roadmap: 4th tab — Leaderboard (global DP ranking + clubs).
  late final TabController _tabs = TabController(length: 4, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final signedIn = ApiClient.instance.isAuthenticated;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Community'),
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          tabs: const [
            Tab(text: 'Commits'),
            Tab(text: 'Friends'),
            Tab(text: 'Referral'),
            Tab(text: 'Leaderboard'),
          ],
        ),
      ),
      body: SafeArea(
        child: !signedIn
            ? const _SignInWall()
            : TabBarView(
                controller: _tabs,
                children: [
                  _CommitsTab(),
                  _FriendsTab(),
                  _ReferralTab(),
                  _LeaderboardTab(),
                ],
              ),
      ),
    );
  }
}

class _SignInWall extends StatelessWidget {
  const _SignInWall();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: AppSpacing.screenH,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.group_outlined,
                size: 48, color: AppColors.textDisabled),
            const SizedBox(height: AppSpacing.lg),
            const Text(
              'Community needs an account',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
            ),
            const SizedBox(height: AppSpacing.sm),
            const Text(
              'Sign in with Google (Settings → Account) to make public '
              'commitments, add friends and earn referral PRO days.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: AppSpacing.xl),
            MLDButton(
              label: 'Go to Settings',
              expanded: false,
              onPressed: () => Navigator.of(context).pushNamed('/settings'),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------
// Commits feed
// ---------------------------------------------------------------------

class _CommitsTab extends StatefulWidget {
  @override
  State<_CommitsTab> createState() => _CommitsTabState();
}

class _CommitsTabState extends State<_CommitsTab> {
  bool _loading = true;
  bool _busy = false;
  List<Map<dynamic, dynamic>> _commits = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final feed = await ApiClient.instance.fetchCommunityFeed();
    if (!mounted) return;
    setState(() {
      _commits = feed;
      _loading = false;
    });
  }

  Future<void> _create() async {
    final result = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      builder: (context) => const _NewCommitSheet(),
    );
    // v2.5.5 audit fix: mounted guard between the sheet await and setState.
    if (!mounted || result == null) return;
    setState(() => _busy = true);
    final ok = await ApiClient.instance.createCommunityCommit(
      durationDays: result['days'] as int,
      reason: result['reason'] as String,
      anonymous: result['anonymous'] as bool? ?? false,
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok == null) {
      _toast('Could not post — you look offline.');
    } else {
      _toast('Commitment posted. Do not let them watch you fold.');
      _load();
    }
  }

  Future<void> _cheer(Map<dynamic, dynamic> commit) async {
    await ApiClient.instance
        .cheerCommunityCommit(commit['id'].toString());
    _load();
  }

  /// v2.5.7 (F-2): report a commitment (moderation signal — 3 reports flag
  /// it for the admin Security Events queue).
  Future<void> _report(Map<dynamic, dynamic> commit) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Report this commitment?'),
        content: const Text(
            'Reports are reviewed by the moderation team. Abusive, sexual or '
            'spam content is hidden from everyone.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Report')),
        ],
      ),
    );
    if (confirmed != true) return;
    final res = await ApiClient.instance.reportCommunityCommit(
        commit['id'].toString());
    if (!mounted) return;
    _toast(res != null
        ? 'Reported — thank you.'
        : 'Could not report — check your connection.');
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        RefreshIndicator(
          onRefresh: _load,
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _commits.isEmpty
                  ? ListView(
                      children: const [
                        SizedBox(height: 120),
                        Center(
                          child: Text(
                              'No active commitments yet.\n'
                              'Be the first to go public — or you may be '
                              'offline.\nPull to retry.',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: AppColors.textSecondary)),
                        ),
                      ],
                    )
                  : ListView.builder(
                      padding: AppSpacing.screenH
                          .copyWith(bottom: AppSpacing.xxxl),
                      itemCount: _commits.length,
                      itemBuilder: (context, i) {
                        final c = _commits[i];
                        final mine = c['mine'] == true;
                        final cheered = c['hasCheered'] == true;
                        return Padding(
                          padding: const EdgeInsets.only(bottom: AppSpacing.md),
                          child: MLDCard(
                            padding: const EdgeInsets.all(AppSpacing.lg),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Icon(
                                      mine ? Icons.person : Icons.public,
                                      size: 16,
                                      color: AppColors.textSecondary,
                                    ),
                                    const SizedBox(width: AppSpacing.sm),
                                    Expanded(
                                      child: Text(
                                        mine
                                            ? 'You'
                                            : (c['displayName'] as String? ??
                                                'Anonymous'),
                                        style: const TextStyle(
                                            fontWeight: FontWeight.w600,
                                            fontSize: 13),
                                      ),
                                    ),
                                    Text(
                                      '${c['durationDays']} days',
                                      style: const TextStyle(
                                          fontSize: 12,
                                          color: AppColors.primary),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: AppSpacing.sm),
                                Text(
                                  c['reason']?.toString() ?? '',
                                  style: const TextStyle(fontSize: 14),
                                ),
                                const SizedBox(height: AppSpacing.md),
                                Row(
                                  children: [
                                    Icon(Icons.local_fire_department,
                                        size: 16,
                                        color: cheered
                                            ? AppColors.warning
                                            : AppColors.textDisabled),
                                    const SizedBox(width: AppSpacing.xs),
                                    Text('${c['cheersCount'] ?? 0} cheers'),
                                    const Spacer(),
                                    // v2.5.7 (F-2): report path on every
                                    // non-mine commitment.
                                    if (c['mine'] != true)
                                      IconButton(
                                        visualDensity: VisualDensity.compact,
                                        tooltip: 'Report',
                                        icon: const Icon(Icons.flag_outlined,
                                            size: 18),
                                        onPressed: () => _report(c),
                                      ),
                                    TextButton.icon(
                                      onPressed:
                                          cheered ? null : () => _cheer(c),
                                      icon: const Icon(Icons.thumb_up_outlined,
                                          size: 16),
                                      label: const Text('Cheer'),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
        ),
        if (_busy) const LinearProgressIndicator(minHeight: 2),
      ],
    );
  }
}

class _NewCommitSheet extends StatefulWidget {
  const _NewCommitSheet();

  @override
  State<_NewCommitSheet> createState() => _NewCommitSheetState();
}

class _NewCommitSheetState extends State<_NewCommitSheet> {
  int _days = 7;
  bool _anonymous = false;
  final _reason = TextEditingController();

  // v2.5.5 audit fix: the sheet's TextEditingController was never disposed.
  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: AppSpacing.xl,
        right: AppSpacing.xl,
        top: AppSpacing.xl,
        bottom: MediaQuery.of(context).viewInsets.bottom + AppSpacing.xl,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const MLDSectionHeader(title: 'Public commitment'),
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _reason,
            maxLines: 2,
            decoration: const InputDecoration(
              hintText: 'What are you committing to? (public)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          Wrap(
            spacing: AppSpacing.md,
            children: [3, 7, 14, 30]
                .map((d) => ChoiceChip(
                      label: Text('$d days'),
                      selected: _days == d,
                      onSelected: (_) => setState(() => _days = d),
                    ))
                .toList(),
          ),
          SwitchListTile(
            value: _anonymous,
            onChanged: (v) => setState(() => _anonymous = v),
            title: const Text('Post anonymously', style: TextStyle(fontSize: 14)),
            contentPadding: EdgeInsets.zero,
            dense: true,
          ),
          MLDButton(
            label: 'Commit publicly',
            onPressed: () => Navigator.of(context).pop({
              'days': _days,
              'reason': _reason.text.trim(),
              'anonymous': _anonymous,
            }),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------
// Friends
// ---------------------------------------------------------------------

class _FriendsTab extends StatefulWidget {
  @override
  State<_FriendsTab> createState() => _FriendsTabState();
}

class _FriendsTabState extends State<_FriendsTab> {
  bool _loading = true;
  List<Map<dynamic, dynamic>> _friends = const [];
  final _query = TextEditingController();
  List<Map<dynamic, dynamic>> _results = const [];
  bool _searching = false;

  // v2.5.5 audit fix: the search field's TextEditingController was never
  // disposed.
  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final friends = await ApiClient.instance.fetchFriends();
    if (!mounted) return;
    setState(() {
      _friends = friends;
      _loading = false;
    });
  }

  Future<void> _search() async {
    final q = _query.text.trim();
    if (q.length < 2) return;
    setState(() => _searching = true);
    final results = await ApiClient.instance.searchUsers(q);
    if (!mounted) return;
    setState(() {
      _results = results;
      _searching = false;
    });
  }

  Future<void> _add(String userId) async {
    final res = await ApiClient.instance.sendFriendRequest(userId);
    if (!mounted) return;
    _toast(res != null && res['ok'] == true
        ? 'Request sent'
        : (res?['message'] as String? ?? 'Could not send'));
  }

  /// v2.5.5 audit fix: `[0]` on an EMPTY display name threw RangeError —
  /// derive the avatar initial defensively (shared by search results and
  /// the friends list).
  String _initial(dynamic name) {
    final s = (name as String?)?.trim() ?? '';
    return s.isEmpty ? '?' : s[0];
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return _loading
        ? const Center(child: CircularProgressIndicator())
        : ListView(
            padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
            children: [
              TextField(
                controller: _query,
                onSubmitted: (_) => _search(),
                decoration: InputDecoration(
                  hintText: 'Search users…',
                  prefixIcon: const Icon(Icons.search),
                  border: const OutlineInputBorder(),
                  isDense: true,
                  suffixIcon: _searching
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: Padding(
                            padding: EdgeInsets.all(6),
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : IconButton(
                          icon: const Icon(Icons.arrow_forward),
                          onPressed: _search,
                        ),
                ),
              ),
              if (_results.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.md),
                ..._results.map((u) => ListTile(
                      leading: CircleAvatar(
                        backgroundColor: AppColors.elevated,
                        child: Text(_initial(u['displayName'])),
                      ),
                      title: Text(u['displayName']?.toString() ?? '?',
                          style: const TextStyle(fontSize: 14)),
                      subtitle: u['rank'] != null
                          ? Text(u['rank'].toString(),
                              style: const TextStyle(fontSize: 11))
                          : null,
                      trailing: TextButton(
                        onPressed: () => _add(u['id'].toString()),
                        child: const Text('Add'),
                      ),
                      dense: true,
                    )),
                const SizedBox(height: AppSpacing.xl),
              ],
              MLDSectionHeader(title: 'Friends (${_friends.length})'),
              const SizedBox(height: AppSpacing.md),
              ..._friends.map((f) => ListTile(
                    leading: CircleAvatar(
                      backgroundColor: AppColors.elevated,
                      child: Text(_initial(f['displayName'])),
                    ),
                    title: Text(f['displayName']?.toString() ?? '?',
                        style: const TextStyle(fontSize: 14)),
                    // v2.5.7 (L-4): the fake hardcoded "Streak 0 days"
                    // subtitle removed — streaks are device-side state the
                    // server never tracked; the placeholder was a false
                    // parity claim.
                    dense: true,
                  )),
            ],
          );
  }
}

// ---------------------------------------------------------------------
// Referral
// ---------------------------------------------------------------------

class _ReferralTab extends StatefulWidget {
  @override
  State<_ReferralTab> createState() => _ReferralTabState();
}

class _ReferralTabState extends State<_ReferralTab> {
  bool _loading = true;
  Map<String, dynamic>? _data;
  // v2.5.7 (H-1): the referred-friend side — enter a friend's code once,
  // within 7 days of signup.
  final TextEditingController _applyController = TextEditingController();
  bool _applying = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _applyController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final data = await ApiClient.instance.fetchReferral();
    if (!mounted) return;
    setState(() {
      _data = data;
      _loading = false;
    });
  }

  Future<void> _applyCode() async {
    final code = _applyController.text.trim().toUpperCase();
    if (_applying || code.length < 6) return;
    setState(() => _applying = true);
    final res = await ApiClient.instance.applyReferralCode(code);
    if (!mounted) return;
    setState(() => _applying = false);
    final msg = res != null
        ? (res['qualified'] == true
            ? 'Code applied — your friend gets credit when you stay signed in.'
            : 'Code applied — it qualifies after you sign in with Google.')
        : switch (ApiClient.instance.lastErrorCode) {
            'CONFLICT' =>
              ApiClient.instance.lastErrorMessage ?? 'This code cannot be applied.',
            'NOT_FOUND' => 'Invalid code — check the spelling.',
            'OFFLINE' => 'You appear to be offline — try again later.',
            _ => 'Could not apply the code. Please try again.',
          };
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
    if (res != null) {
      _applyController.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final code = _data?['code'] as String? ?? '';
    final proDays = _data?['proDaysEarned'] as int? ?? 0;
    final qualified = _data?['qualifiedReferrals'] as int? ?? 0;
    final pending = _data?['pendingRewards'] as List<dynamic>? ?? const [];

    return _loading
        ? const Center(child: CircularProgressIndicator())
        : ListView(
            padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
            children: [
              MLDCard(
                child: Column(
                  children: [
                    const MLDSectionHeader(title: 'Your referral code'),
                    const SizedBox(height: AppSpacing.md),
                    Text(
                      code.isEmpty ? '—' : code,
                      style: const TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 3),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    MLDButton(
                      label: 'Share invite link',
                      expanded: false,
                      onPressed: code.isEmpty
                          ? null
                          : () async {
                              // v2.5.5 audit fix: the button body used to be
                              // EMPTY — it did nothing. Clipboard fallback
                              // (no share-sheet dependency by design).
                              await Clipboard.setData(ClipboardData(
                                  text: 'https://maxleveldetox.com/r/$code'));
                              if (!mounted) return;
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                    content: Text('Invite link copied')),
                              );
                            },
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xl),
              MLDCard(
                child: Column(
                  children: [
                    _stat('Qualified referrals', '$qualified'),
                    _stat('PRO days earned', '$proDays'),
                    _stat('Milestones',
                        '3→+1d · 5→+3d · 10→+7d · 20→+15d · 50→+30d'),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xl),
              // v2.5.7 (H-1): referred-friend side — apply a friend's code
              // (once, within 7 days of signup) to complete the pipeline.
              MLDCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const MLDSectionHeader(title: 'Have a friend\'s code?'),
                    const SizedBox(height: AppSpacing.sm),
                    Text(
                      'Applied once per account, within 7 days of signing up.',
                      style: const TextStyle(
                          color: AppColors.textSecondary, fontSize: 12),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _applyController,
                            textCapitalization: TextCapitalization.characters,
                            maxLength: 8,
                            decoration: const InputDecoration(
                              labelText: 'Referral code',
                              counterText: '',
                              border: OutlineInputBorder(),
                              isDense: true,
                            ),
                          ),
                        ),
                        const SizedBox(width: AppSpacing.md),
                        MLDButton(
                          label: _applying ? '…' : 'Apply',
                          expanded: false,
                          onPressed: _applying ? null : _applyCode,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xl),
              if (pending.isNotEmpty) ...[
                MLDSectionHeader(
                    title: 'Pending rewards (${pending.length})'),
                const SizedBox(height: AppSpacing.md),
                ...pending.map((p) => ListTile(
                      leading: const Icon(Icons.card_giftcard),
                      title: Text('Claimable reward',
                          style: const TextStyle(fontSize: 14)),
                      trailing: TextButton(
                        onPressed: () async {
                          await ApiClient.instance.claimReferralReward(
              (p as Map)['id'].toString());
                          _load();
                        },
                        child: const Text('Claim'),
                      ),
                      dense: true,
                    )),
              ],
              const Text(
                'Friend signs in with Google and claims within 7 days → they '
                'get 1 day PRO instantly, you get 1 day PRO per qualified '
                'referral (milestones stack, 180 free days/year cap). '
                'Anti-fraud: no self, fake, bot or circular referrals.',
                style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
              ),
            ],
          );
  }

  Widget _stat(String label, String value) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(fontSize: 13)),
            Text(value,
                style: const TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 13)),
          ],
        ),
      );
}

// ---------------------------------------------------------------------
// v2.5.8 roadmap — Leaderboard tab (global DP ranking + clubs)
// ---------------------------------------------------------------------

class _LeaderboardTab extends StatefulWidget {
  @override
  State<_LeaderboardTab> createState() => _LeaderboardTabState();
}

class _LeaderboardTabState extends State<_LeaderboardTab> {
  static const _windows = ['weekly', 'monthly', 'alltime'];
  static const _windowLabels = ['Week', 'Month', 'All-time'];

  bool _loading = true;
  bool _busy = false;
  bool _optedIn = false;
  String _displayMode = 'name';
  int _globalRank = 0;
  int _lifetimeDp = 0;
  int _streakDays = 0;
  String _levelName = '';
  Map<dynamic, dynamic>? _club;
  List<Map<dynamic, dynamic>> _entries = const [];
  List<Map<dynamic, dynamic>> _clubEntries = const [];
  int _window = 2; // default: all-time

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final me = await ApiClient.instance.fetchLeaderboardMe();
    if (!mounted) return;
    if (me != null) {
      _optedIn = me['optedIn'] == true;
      _displayMode = (me['displayMode'] as String?) ?? 'name';
      _globalRank = (me['globalRank'] as num?)?.toInt() ?? 0;
      final profile = me['profile'];
      if (profile is Map) {
        _lifetimeDp = (profile['lifetimeDp'] as num?)?.toInt() ?? 0;
        _streakDays = (profile['streakDays'] as num?)?.toInt() ?? 0;
        _levelName = (profile['levelName'] as String?) ?? '';
      }
      final club = me['club'];
      _club = club is Map ? club : null;
      // v2.5.9 fix: mirror the SERVER opt-in into the local sync flag so the
      // periodic DP snapshot sync (main.dart) resumes after a reinstall or
      // on a second device — otherwise the board would go permanently stale
      // (the local pref defaults false and nothing else ever sets it).
      if (_optedIn) {
        final prefs = await SharedPreferences.getInstance();
        if (prefs.getBool('mld_leaderboard_opted_in') != true) {
          await prefs.setBool('mld_leaderboard_opted_in', true);
        }
      }
    }
    setState(() => _loading = false);
    if (_optedIn) {
      await _loadBoards();
    }
  }

  Future<void> _loadBoards() async {
    final global = await ApiClient.instance
        .fetchLeaderboardGlobal(window: _windows[_window]);
    if (!mounted) return;
    if (global != null) {
      final entries = global['entries'];
      setState(() {
        _entries = entries is List
            ? entries.map((e) => Map<dynamic, dynamic>.from(e as Map)).toList()
            : const [];
      });
    }
    if (_club != null && _club!['id'] != null) {
      final detail = await ApiClient.instance
          .fetchClub(_club!['id'].toString(), window: _windows[_window]);
      if (!mounted) return;
      if (detail != null) {
        final clubEntries = detail['entries'];
        final club = detail['club'];
        setState(() {
          _clubEntries = clubEntries is List
              ? clubEntries
                  .map((e) => Map<dynamic, dynamic>.from(e as Map))
                  .toList()
              : const [];
          if (club is Map) _club = Map<dynamic, dynamic>.from(club);
        });
      }
    }
  }

  /// Opt-in flow: display-mode choice, then server opt-in + an immediate DP
  /// snapshot sync so the user appears on the board within seconds.
  Future<void> _optInFlow() async {
    final anonymous = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Join the leaderboard?'),
        content: const Text(
            'Your Dopamine Points, level and streak will be ranked against '
            'other MAXLEVEL users. You can show your display name or stay '
            'anonymous — and leave any time.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, null),
              child: const Text('Cancel')),
          OutlinedButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Anonymous')),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Use my name')),
        ],
      ),
    );
    if (anonymous == null) return;
    final mode = anonymous ? 'anonymous' : 'name';
    setState(() => _busy = true);
    final res = await ApiClient.instance.leaderboardOptIn(
        optedIn: true, displayMode: mode);
    if (res != null) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('mld_leaderboard_opted_in', true);
      final snapshot = await NativeBridge.instance.getLeaderboardSnapshot();
      if (snapshot != null) {
        await ApiClient.instance.syncLeaderboardSnapshot(snapshot);
      }
    }
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      _toast('You are on the board. Climb with every focus minute.');
      await _load();
    } else {
      _toast('Could not opt in — check your connection.');
    }
  }

  Future<void> _optOut() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Leave the leaderboard?'),
        content: const Text(
            'Your ranking disappears immediately. Your DP, streaks and '
            'levels on this device are never affected.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Stay')),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Leave')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.leaderboardOptIn(optedIn: false);
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('mld_leaderboard_opted_in', false);
      _toast('You are off the board.');
      _load();
    } else {
      _toast('Could not update — check your connection.');
    }
  }

  Future<void> _createClub() async {
    final result = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      builder: (context) => const _NewClubSheet(),
    );
    if (!mounted || result == null) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.createClub(
      name: result['name'] as String,
      description: result['description'] as String?,
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      _toast('Club created — share the invite code!');
      await _load();
    } else {
      _toast('Could not create the club.');
    }
  }

  Future<void> _joinClub() async {
    final code = await showDialog<String>(
      context: context,
      builder: (dialogContext) {
        final controller = TextEditingController();
        return AlertDialog(
          title: const Text('Join a club'),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLength: 6,
            textCapitalization: TextCapitalization.characters,
            decoration: const InputDecoration(
                hintText: '6-character invite code',
                counterText: ''),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(dialogContext, controller.text.trim()),
                child: const Text('Join')),
          ],
        );
      },
    );
    if (code == null || code.length != 6) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.joinClub(code.toUpperCase());
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      _toast('Welcome to the club!');
      await _load();
    } else {
      _toast('No club found for that code.');
    }
  }

  Future<void> _leaveClub() async {
    setState(() => _busy = true);
    final res = await ApiClient.instance.leaveClub();
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      _toast('You left the club.');
      await _load();
    } else {
      _toast('Could not leave — check your connection.');
    }
  }

  /// v2.5.8: search public clubs by name and join straight from a result.
  Future<void> _searchClubs() async {
    final club = await showModalBottomSheet<Map<dynamic, dynamic>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      builder: (context) => const _ClubSearchSheet(),
    );
    if (!mounted || club == null) return;
    setState(() => _busy = true);
    final res = await ApiClient.instance.joinClub(
        (club['inviteCode'] as String? ?? '').toUpperCase());
    if (!mounted) return;
    setState(() => _busy = false);
    if (res != null) {
      _toast('Welcome to ${club['name'] ?? 'the club'}!');
      await _load();
    } else {
      _toast('Could not join — the club may be full or hidden.');
    }
  }

  void _copyCode(String code) {
    Clipboard.setData(ClipboardData(text: code));
    _toast('Invite code copied: $code');
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (!_optedIn) return _buildOptInWall();
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
        children: [
          _buildMyRankCard(),
          const SizedBox(height: AppSpacing.lg),
          _buildWindowSelector(),
          const SizedBox(height: AppSpacing.md),
          ..._buildGlobalList(),
          const SizedBox(height: AppSpacing.xl),
          _buildClubSection(),
        ],
      ),
    );
  }

  Widget _buildOptInWall() {
    return Center(
      child: Padding(
        padding: AppSpacing.screenH,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.leaderboard_outlined,
                size: 48, color: AppColors.textDisabled),
            const SizedBox(height: AppSpacing.lg),
            const Text('Healthy competition, zero shame',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: AppSpacing.sm),
            const Text(
              'Rank your Dopamine Points against the community and inside '
              'invite-only clubs. Anonymous mode available. Off by default — '
              'nothing leaves this device until you join.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: AppSpacing.xl),
            MLDButton(
              label: 'Join the leaderboard',
              loading: _busy,
              onPressed: _optInFlow,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMyRankCard() {
    return MLDCard(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.emoji_events_outlined,
                  size: 20, color: AppColors.primary),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text(
                  _globalRank > 0 ? 'Global rank #$_globalRank' : 'Unranked yet',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              TextButton(
                onPressed: _busy ? null : _optOut,
                child: const Text('Leave board',
                    style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              Expanded(
                  child: _miniStat('DP', _lifetimeDp.toString())),
              Expanded(child: _miniStat('Level', _levelName)),
              Expanded(child: _miniStat('Streak', '$_streakDays d')),
            ],
          ),
        ],
      ),
    );
  }

  Widget _miniStat(String label, String value) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: const TextStyle(
                  color: AppColors.textSecondary, fontSize: 11)),
          const SizedBox(height: 2),
          Text(value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
        ],
      );

  Widget _buildWindowSelector() {
    return SegmentedButton<int>(
      segments: const [
        ButtonSegment(value: 0, label: Text('Week')),
        ButtonSegment(value: 1, label: Text('Month')),
        ButtonSegment(value: 2, label: Text('All-time')),
      ],
      selected: {_window},
      onSelectionChanged: (set) {
        setState(() => _window = set.first);
        _loadBoards();
      },
    );
  }

  List<Widget> _buildGlobalList() {
    if (_entries.isEmpty) {
      return const [
        SizedBox(height: 40),
        Center(
          child: Text('No ranked players in this window yet.\nBe the first.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textSecondary)),
        ),
      ];
    }
    return [
      MLDSectionHeader(
          title: _window == 0
              ? 'This week\u2019s climbers'
              : _window == 1
                  ? 'This month\u2019s climbers'
                  : 'All-time legends'),
      const SizedBox(height: AppSpacing.md),
      ..._entries.map(_entryTile),
    ];
  }

  Widget _entryTile(Map<dynamic, dynamic> e) {
    final mine = e['isMe'] == true;
    final rank = (e['rank'] as num?)?.toInt() ?? 0;
    final value = (e['value'] as num?)?.toInt() ?? 0;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: MLDCard(
        padding:
            const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: 10),
        child: Row(
          children: [
            SizedBox(
              width: 34,
              child: Text('#$rank',
                  style: TextStyle(
                    fontWeight: FontWeight.w800,
                    fontSize: 13,
                    color: rank <= 3 ? AppColors.primary : AppColors.textSecondary,
                  )),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    mine ? 'You' : (e['displayName'] as String? ?? 'Detoxer'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontWeight: mine ? FontWeight.w800 : FontWeight.w600,
                        fontSize: 13),
                  ),
                  Text(
                    '${e['levelName'] ?? ''} · ${e['streakDays'] ?? 0}-day streak',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        color: AppColors.textSecondary, fontSize: 11),
                  ),
                ],
              ),
            ),
            Text('$value DP',
                style: const TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 13)),
          ],
        ),
      ),
    );
  }

  Widget _buildClubSection() {
    if (_club == null) {
      return MLDCard(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Clubs', style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: AppSpacing.sm),
            const Text(
              'Small invite-only groups with their own leaderboard slice. '
              'Study halls, gym crews, no-doom squads — bring your own.',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
            ),
            const SizedBox(height: AppSpacing.md),
            Row(
              children: [
                Expanded(
                  child: MLDButton(
                    label: 'Create',
                    loading: _busy,
                    onPressed: _createClub,
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: MLDButton(
                    label: 'Join with code',
                    variant: MLDButtonVariant.secondary,
                    loading: _busy,
                    onPressed: _joinClub,
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            // v2.5.8: discover public clubs by name (searchClubs endpoint).
            MLDButton(
              label: 'Search clubs by name',
              variant: MLDButtonVariant.secondary,
              loading: _busy,
              onPressed: _searchClubs,
            ),
          ],
        ),
      );
    }
    final name = _club!['name'] as String? ?? 'Club';
    final code = _club!['inviteCode'] as String? ?? '';
    final members = (_club!['memberCount'] as num?)?.toInt() ?? 1;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        MLDCard(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.groups_2_outlined,
                      size: 20, color: AppColors.primary),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Text(name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                  ),
                  Text('$members member${members == 1 ? '' : 's'}',
                      style: const TextStyle(
                          color: AppColors.textSecondary, fontSize: 12)),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              Row(
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: () => _copyCode(code),
                      borderRadius: BorderRadius.circular(8),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: AppSpacing.md, vertical: 10),
                        decoration: BoxDecoration(
                          color: AppColors.surface,
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: AppColors.edge),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.content_copy,
                                size: 14, color: AppColors.textSecondary),
                            const SizedBox(width: AppSpacing.sm),
                            Text('Invite code: $code',
                                style: const TextStyle(
                                    fontWeight: FontWeight.w700, fontSize: 13)),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  MLDButton(
                    label: 'Leave',
                    variant: MLDButtonVariant.danger,
                    expanded: false,
                    loading: _busy,
                    onPressed: _leaveClub,
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        if (_clubEntries.isNotEmpty) ...[
          MLDSectionHeader(title: 'Club board'),
          const SizedBox(height: AppSpacing.md),
          ..._clubEntries.map(_entryTile),
        ],
      ],
    );
  }
}

/// Bottom sheet: search public clubs by name (live, debounced) and pick
/// one to join. Results come from /clubs/search (hidden clubs never
/// surface server-side).
class _ClubSearchSheet extends StatefulWidget {
  const _ClubSearchSheet();

  @override
  State<_ClubSearchSheet> createState() => _ClubSearchSheetState();
}

class _ClubSearchSheetState extends State<_ClubSearchSheet> {
  final _query = TextEditingController();
  List<Map<dynamic, dynamic>> _results = const [];
  bool _searching = false;
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debounce?.cancel();
    if (value.trim().length < 2) {
      setState(() => _results = const []);
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), _search);
  }

  Future<void> _search() async {
    final q = _query.text.trim();
    if (q.length < 2) return;
    setState(() => _searching = true);
    final results = await ApiClient.instance.searchClubs(q);
    if (!mounted) return;
    setState(() {
      _searching = false;
      _results = results;
    });
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Find a club',
                style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
            const SizedBox(height: AppSpacing.sm),
            const Text(
                'Search public clubs by name. Hidden clubs never appear.',
                style: TextStyle(color: AppColors.textSecondary, fontSize: 12)),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _query,
              autofocus: true,
              textCapitalization: TextCapitalization.words,
              onChanged: _onChanged,
              decoration: const InputDecoration(
                  labelText: 'Club name (min 2 chars)', counterText: ''),
            ),
            const SizedBox(height: AppSpacing.lg),
            if (_searching)
              const Padding(
                padding: EdgeInsets.all(AppSpacing.lg),
                child: Center(
                    child: SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))),
              )
            else if (_results.isEmpty)
              Padding(
                padding: const EdgeInsets.all(AppSpacing.lg),
                child: Center(
                  child: Text(
                      _query.text.trim().length < 2
                          ? 'Type at least 2 characters.'
                          : 'No clubs match "${_query.text.trim()}".',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          color: AppColors.textSecondary, fontSize: 12)),
                ),
              )
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 300),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: _results.length,
                  itemBuilder: (context, i) {
                    final club = _results[i];
                    final members = (club['memberCount'] as num?)?.toInt() ?? 1;
                    return ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(club['name']?.toString() ?? 'Club',
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle: Text(
                          '$members member${members == 1 ? '' : 's'}'
                          '${club['description'] == null ? '' : ' · ${club['description']}'}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 12)),
                      trailing: const Icon(Icons.login, size: 18),
                      onTap: () => Navigator.pop(context, club),
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Bottom sheet: create a club (name + optional description).
class _NewClubSheet extends StatefulWidget {
  const _NewClubSheet();

  @override
  State<_NewClubSheet> createState() => _NewClubSheetState();
}

class _NewClubSheetState extends State<_NewClubSheet> {
  final _name = TextEditingController();
  final _description = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Create a club',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
              const SizedBox(height: AppSpacing.lg),
              TextField(
                controller: _name,
                maxLength: 40,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                    labelText: 'Club name (3-40 chars)',
                    counterText: ''),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                controller: _description,
                maxLength: 200,
                decoration: const InputDecoration(
                    labelText: 'Description (optional)', counterText: ''),
              ),
              const SizedBox(height: AppSpacing.lg),
              MLDButton(
                label: 'Create club',
                onPressed: () {
                  final name = _name.text.trim();
                  if (name.length < 3) return;
                  Navigator.pop(context, {
                    'name': name,
                    'description': _description.text.trim(),
                  });
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}
