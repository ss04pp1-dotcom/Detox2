import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../shared/mld_widgets.dart';

/// Support (v2.5.7 / H-2) — "Report a problem".
///
/// The admin support queue (GET/PATCH /admin/support/tickets + the triage
/// board) always existed, but there was no app-side path to CREATE a
/// ticket — the subsystem was dead code. This screen is the producer:
/// a category + description form (validated client-side and again on the
/// Worker), plus a pull-model list of the user's tickets with the admin's
/// reply so the conversation actually closes the loop.
class SupportScreen extends StatefulWidget {
  const SupportScreen({super.key});

  @override
  State<SupportScreen> createState() => _SupportScreenState();
}

class _SupportScreenState extends State<SupportScreen> {
  static const List<(String, String)> _categories = [
    ('PAYMENT', 'bKash payment'),
    ('BILLING_PLAY', 'Play purchase / PRO'),
    ('ENFORCEMENT', 'Blocking / session issue'),
    ('ACCOUNT', 'Account / sign-in'),
    ('BUG', 'Something is broken'),
    ('FEEDBACK', 'Suggestion'),
    ('OTHER', 'Other'),
  ];

  String _category = 'BUG';
  final TextEditingController _description = TextEditingController();
  bool _busy = false;
  bool _loadingTickets = true;
  String? _error;
  String? _success;
  List<Map<dynamic, dynamic>> _tickets = const [];

  @override
  void initState() {
    super.initState();
    _loadTickets();
  }

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  Future<void> _loadTickets() async {
    if (!mounted) return;
    setState(() => _loadingTickets = true);
    final tickets = await ApiClient.instance.fetchSupportTickets();
    if (!mounted) return;
    setState(() {
      _tickets = tickets;
      _loadingTickets = false;
    });
  }

  Future<void> _submit() async {
    final text = _description.text.trim();
    if (_busy) return;
    if (text.length < 10) {
      setState(() => _error = 'Please describe the problem in at least 10 characters.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _success = null;
    });

    final res = await ApiClient.instance.createSupportTicket(
      category: _category,
      description: text,
    );

    if (!mounted) return;
    if (res != null) {
      _description.clear();
      setState(() {
        _busy = false;
        _success = 'Ticket submitted — our team will reply here.';
      });
      await _loadTickets();
    } else {
      setState(() {
        _busy = false;
        // v2.5.7 (F-1): surface the specific reason instead of a generic shrug.
        _error = switch (ApiClient.instance.lastErrorCode) {
          'OFFLINE' => 'You appear to be offline — try again with a connection.',
          'CONFLICT' => 'You already have too many open tickets — wait for a reply first.',
          'RATE_LIMITED' => 'Daily ticket limit reached — please try again tomorrow.',
          'VALIDATION_FAILED' =>
            ApiClient.instance.lastErrorMessage ?? 'Please check the form and retry.',
          _ => 'Could not submit the ticket. Please try again.',
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Report a Problem')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppSpacing.screenH,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                MLDWarningBanner(message: _error!),
                const SizedBox(height: AppSpacing.lg),
              ],
              if (_success != null) ...[
                MLDCard(
                  child: Row(
                    children: [
                      const Icon(Icons.check_circle_outline, color: Colors.greenAccent),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Text(_success!,
                            style: AppTypography.body(color: Colors.greenAccent)),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.lg),
              ],

              MLDCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('NEW TICKET', style: AppTypography.caption(weight: FontWeight.w800)),
                    const SizedBox(height: AppSpacing.md),
                    DropdownButtonFormField<String>(
                      initialValue: _category,
                      decoration: const InputDecoration(
                        labelText: 'Category',
                        border: OutlineInputBorder(),
                      ),
                      items: _categories
                          .map((c) => DropdownMenuItem(
                                value: c.$1,
                                child: Text(c.$2),
                              ))
                          .toList(),
                      onChanged: (v) => setState(() => _category = v ?? 'BUG'),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    TextField(
                      controller: _description,
                      maxLines: 5,
                      maxLength: 2000,
                      decoration: const InputDecoration(
                        labelText: 'What happened?',
                        hintText: 'Describe the problem — what you did, what you expected, what you saw.',
                        border: OutlineInputBorder(),
                        alignLabelWithHint: true,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    MLDButton(
                      label: _busy ? 'Submitting…' : 'Submit Ticket',
                      onPressed: _busy ? null : _submit,
                      icon: Icons.send_outlined,
                    ),
                    if (!ApiClient.instance.isAuthenticated) ...[
                      const SizedBox(height: AppSpacing.md),
                      Text(
                        'Sign in (Account screen) to submit tickets and receive replies.',
                        style: AppTypography.caption(),
                      ),
                    ],
                  ],
                ),
              ),

              const SizedBox(height: AppSpacing.xxl),
              Text('MY TICKETS', style: AppTypography.caption(weight: FontWeight.w800)),
              const SizedBox(height: AppSpacing.md),
              if (_loadingTickets)
                const Center(child: Padding(
                  padding: EdgeInsets.all(AppSpacing.xl),
                  child: CircularProgressIndicator(strokeWidth: 2),
                ))
              else if (_tickets.isEmpty)
                const MLDEmptyState(
                  icon: Icons.inbox_outlined,
                  title: 'No tickets yet',
                  message: 'Anything you submit shows up here with our reply.',
                )
              else
                ..._tickets.map(_ticketTile),
            ],
          ),
        ),
      ),
    );
  }

  Widget _ticketTile(Map<dynamic, dynamic> t) {
    final status = (t['status'] as String?) ?? 'OPEN';
    final category = (t['category'] as String?) ?? '';
    final description = (t['description'] as String?) ?? '';
    final response = (t['response'] as String?) ?? '';
    final respondedAt = (t['respondedAt'] as String?) ?? '';
    final createdAt = (t['createdAt'] as String?) ?? '';
    final hasReply = response.trim().isNotEmpty;

    final statusColor = switch (status) {
      'RESOLVED' || 'CLOSED' => Colors.greenAccent,
      'WAITING_USER' => Colors.orangeAccent,
      _ => Theme.of(context).colorScheme.secondary,
    };

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: MLDCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.support_agent_outlined,
                    size: 18, color: Theme.of(context).colorScheme.secondary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: Text(category,
                      style: AppTypography.body(weight: FontWeight.w800)),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(status,
                      style: AppTypography.caption(color: statusColor, weight: FontWeight.w800)),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(description, style: AppTypography.body()),
            const SizedBox(height: AppSpacing.sm),
            Text('Submitted ${_shortDate(createdAt)}',
                style: AppTypography.caption()),
            if (hasReply) ...[
              const SizedBox(height: AppSpacing.md),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(AppSpacing.md),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.secondary.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                      color: Theme.of(context).colorScheme.secondary.withValues(alpha: 0.3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('TEAM REPLY', style: AppTypography.caption(weight: FontWeight.w800)),
                    const SizedBox(height: AppSpacing.xs),
                    Text(response, style: AppTypography.body()),
                    if (respondedAt.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: AppSpacing.xs),
                        child: Text(_shortDate(respondedAt), style: AppTypography.caption()),
                      ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  static String _shortDate(String iso) {
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '';
    final local = dt.toLocal();
    final months = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
    ];
    return '${local.day} ${months[local.month - 1]} ${local.year}, '
        '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  }
}
