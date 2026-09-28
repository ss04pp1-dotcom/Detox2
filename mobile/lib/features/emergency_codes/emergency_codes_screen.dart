import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';

/// EmergencyCodesScreen (v2.5 r9) — SS per-user emergency TOTP port:
/// RFC-6238 rotating 6-digit codes (30 s window, ±1 skew, 20-min replay
/// guard). Codes unlock Prime give-ups and the highest-severity walls.
/// The full sheet is shown ONCE at enrollment — store it somewhere safe.
class EmergencyCodesScreen extends StatefulWidget {
  const EmergencyCodesScreen({super.key});

  @override
  State<EmergencyCodesScreen> createState() => _EmergencyCodesScreenState();
}

class _EmergencyCodesScreenState extends State<EmergencyCodesScreen> {
  bool _loading = true;
  bool _busy = false;
  Map<String, dynamic>? _status;
  String? _freshSecret;
  String? _freshOtpauth;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final status = await NativeBridge.instance.getEmergencyCodeStatus();
    if (!mounted) return;
    setState(() {
      _status = status;
      _loading = false;
    });
  }

  Future<void> _enroll() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enroll emergency codes?'),
        content: const Text(
          'You will get a sheet of rotating 6-digit codes (RFC-6238, one '
          'works per ~30 s window, each usable once). The full sheet is '
          'shown ONLY this once — write it down or screenshot it now.\n\n'
          'Codes are for real emergencies: giving up a Prime commitment, '
          'or exiting the hardest walls.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Enroll')),
        ],
      ),
    );
    if (confirmed != true) return;
    // v2.5.5 audit fix: mounted guard between the dialog await and setState.
    if (!mounted) return;
    setState(() => _busy = true);
    final result = await NativeBridge.instance.enrollEmergencyCodes();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (result != null) {
        _freshSecret = result['secret'] as String?;
        _freshOtpauth = result['otpauthUrl'] as String?;
      }
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final enrolled = _status?['enrolled'] as bool? ?? false;
    final used = _status?['usedCount'] as int? ?? 0;

    return Scaffold(
      appBar: AppBar(title: const MLDAppBarTitle(title: 'Emergency Codes')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xxxl),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    MLDCard(
                      child: Column(
                        children: [
                          Icon(
                            enrolled ? Icons.key : Icons.key_off,
                            size: 40,
                            color: enrolled ? AppColors.success : AppColors.warning,
                          ),
                          const SizedBox(height: AppSpacing.md),
                          Text(
                            enrolled
                                ? 'Enrolled · $used codes used'
                                : 'Not enrolled',
                            style: const TextStyle(
                                fontWeight: FontWeight.w700, fontSize: 14),
                          ),
                          const SizedBox(height: AppSpacing.sm),
                          const Text(
                            'Rotating TOTP codes (30 s window) for real '
                            'emergencies — Prime give-ups and the hardest '
                            'walls. Using one is always logged as a '
                            'discipline event.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: AppColors.textSecondary, fontSize: 13),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    if (_freshSecret != null) ...[
                      const MLDSectionHeader(
                          title: 'YOUR CODE SEED — SHOWS ONCE'),
                      const SizedBox(height: AppSpacing.md),
                      MLDCard(
                        child: Column(
                          children: [
                            const Text(
                              'Secret (write it down NOW):',
                              style: TextStyle(
                                  color: AppColors.textSecondary,
                                  fontSize: 12),
                            ),
                            const SizedBox(height: AppSpacing.sm),
                            Text(
                              _freshSecret!,
                              style: const TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.w700,
                                  letterSpacing: 1),
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: AppSpacing.md),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                TextButton.icon(
                                  icon: const Icon(Icons.copy, size: 16),
                                  label: const Text('Copy secret'),
                                  onPressed: () => Clipboard.setData(
                                      ClipboardData(text: _freshSecret!)),
                                ),
                                TextButton.icon(
                                  icon: const Icon(Icons.link, size: 16),
                                  label: const Text('Copy otpauth URI'),
                                  onPressed: () => Clipboard.setData(
                                      ClipboardData(
                                          text: _freshOtpauth ?? '')),
                                ),
                              ],
                            ),
                            const SizedBox(height: AppSpacing.md),
                            const Text(
                              'Codes rotate every 30 seconds from this seed '
                              '(RFC-6238). Paste the URI into any authenticator '
                              'app (Google Authenticator etc.) to see the live '
                              'code. The seed never shows again.',
                              style: TextStyle(
                                  color: AppColors.warning, fontSize: 12),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: AppSpacing.xl),
                    ],
                    if (!enrolled)
                      MLDButton(
                        label: 'Enroll now',
                        loading: _busy,
                        onPressed: _enroll,
                      ),
                    if (enrolled)
                      const Text(
                        'Current codes: open Prime Commit → "Use emergency '
                        'code" and enter the current code from your sheet. '
                        'Re-enrollment regenerates the sheet.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: AppColors.textSecondary, fontSize: 12),
                      ),
                  ],
                ),
              ),
      ),
    );
  }
}
