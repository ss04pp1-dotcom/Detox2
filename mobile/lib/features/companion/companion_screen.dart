import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../data/native_bridge.dart';
import '../../shared/mld_widgets.dart';
import 'companion_engine.dart';

/// CompanionScreen (v2.5 r9) — Sinthia chat (Social Sentry ai_chat route
/// port): dual-layer replies (Worker LLM proxy online / scripted persona
/// offline), personality settings, relationship level, brain-rot badge.
class CompanionScreen extends StatefulWidget {
  const CompanionScreen({super.key});

  @override
  State<CompanionScreen> createState() => _CompanionScreenState();
}

class _CompanionScreenState extends State<CompanionScreen> {
  final _controller = TextEditingController();
  final _scroll = ScrollController();
  bool _sending = false;
  bool _loading = true;

  List<Map<String, dynamic>> _messages = [];
  Map<String, dynamic>? _config;
  Map<String, dynamic>? _brainRot;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await CompanionEngine.instance.refreshContext();
    final history = await NativeBridge.instance.getCompanionHistory();
    final cfg = await NativeBridge.instance.getCompanionConfig();
    final brainRot = await NativeBridge.instance.getBrainRotStatus();
    if (!mounted) return;
    setState(() {
      _messages = history;
      _config = cfg;
      _brainRot = brainRot;
      _loading = false;
    });
    if (_messages.isEmpty) {
      await _greet();
    }
  }

  Future<void> _greet() async {
    await _append(
        'assistant', 'Ei je! Ami Sinthia 💅 Tomar accountability partner. '
        'Screen time, streak, reels — shob niye kotha hobe. Bolo, ajke ki obostha?');
  }

  Future<void> _append(String role, String content) async {
    await NativeBridge.instance.appendCompanionMessage(
        role: role, content: content);
    final history = await NativeBridge.instance.getCompanionHistory();
    if (!mounted) return;
    setState(() => _messages = history);
    _scrollToEnd();
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(
          _scroll.position.maxScrollExtent + 80,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _send() async {
    final text = _controller.text.trim();
    if (text.isEmpty || _sending) return;
    _controller.clear();
    setState(() => _sending = true);
    await _append('user', text);
    try {
      final reply = await CompanionEngine.instance.send(text);
      await _append('assistant', reply);
    } catch (_) {
      await _append('assistant',
          'Ugh, my brain lagged 💀 Try again in a moment.');
    }
    if (mounted) setState(() => _sending = false);
  }

  Future<void> _openSettings() async {
    final personality = _config?['personality'] as String? ?? 'balanced';
    final roast = _config?['roastMode'] as bool? ?? true;
    final updated = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => _CompanionSettingsSheet(
        personality: personality,
        roastMode: roast,
      ),
    );
    if (updated == null) return;
    final cfg = await NativeBridge.instance.setCompanionConfig(
      personality: updated['personality'] as String?,
      roastMode: updated['roastMode'] as bool?,
    );
    if (!mounted) return;
    setState(() => _config = cfg);
    CompanionEngine.instance.personality =
        cfg?['personality'] as String? ?? 'balanced';
    CompanionEngine.instance.roastMode = cfg?['roastMode'] as bool? ?? true;
  }

  @override
  void dispose() {
    _controller.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final stage = _brainRot?['label'] as String? ?? '';
    final minutes = _brainRot?['minutes'] as int? ?? 0;
    final relationship = _config?['relationshipLevel'] as int? ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Text('Sinthia'),
            const SizedBox(width: AppSpacing.sm),
            Icon(Icons.favorite,
                size: 14, color: Colors.pink.shade300),
            Text(' Lv.$relationship',
                style: const TextStyle(fontSize: 13)),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.tune),
            onPressed: _openSettings,
            tooltip: 'Persona',
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  if (stage.isNotEmpty)
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                          horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
                      color: AppColors.surface,
                      child: Text(
                        '$stage · $minutes distracting min today',
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                  Expanded(
                    child: ListView.builder(
                      controller: _scroll,
                      padding: AppSpacing.screenH,
                      itemCount: _messages.length,
                      itemBuilder: (context, i) {
                        final m = _messages[i];
                        final isUser = m['role'] == 'user';
                        return Align(
                          alignment: isUser
                              ? Alignment.centerRight
                              : Alignment.centerLeft,
                          child: Container(
                            margin: const EdgeInsets.only(bottom: AppSpacing.md),
                            padding: const EdgeInsets.symmetric(
                                horizontal: AppSpacing.lg,
                                vertical: AppSpacing.md),
                            constraints: BoxConstraints(
                                maxWidth:
                                    MediaQuery.of(context).size.width * 0.78),
                            decoration: BoxDecoration(
                              color: isUser
                                  ? AppColors.primary
                                  : AppColors.surface,
                              borderRadius: BorderRadius.only(
                                topLeft: const Radius.circular(14),
                                topRight: const Radius.circular(14),
                                bottomLeft: Radius.circular(isUser ? 14 : 4),
                                bottomRight: Radius.circular(isUser ? 4 : 14),
                              ),
                            ),
                            child: Text(m['content'].toString()),
                          ),
                        );
                      },
                    ),
                  ),
                  if (_sending)
                    const Padding(
                      padding: EdgeInsets.only(bottom: AppSpacing.sm),
                      child: Text('Sinthia is typing…',
                          style: TextStyle(fontSize: 12)),
                    ),
                  Padding(
                    padding: AppSpacing.screenH.copyWith(top: 0),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _controller,
                            onSubmitted: (_) => _send(),
                            textInputAction: TextInputAction.send,
                            decoration: const InputDecoration(
                              hintText: 'Message Sinthia…',
                              border: OutlineInputBorder(),
                              isDense: true,
                            ),
                          ),
                        ),
                        const SizedBox(width: AppSpacing.md),
                        IconButton.filled(
                          onPressed: _send,
                          icon: const Icon(Icons.send),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _CompanionSettingsSheet extends StatefulWidget {
  const _CompanionSettingsSheet({
    required this.personality,
    required this.roastMode,
  });

  final String personality;
  final bool roastMode;

  @override
  State<_CompanionSettingsSheet> createState() => _CompanionSettingsSheetState();
}

class _CompanionSettingsSheetState extends State<_CompanionSettingsSheet> {
  late String _personality;
  late bool _roastMode;

  static const _options = {
    'gentle': 'Gentle — soft support',
    'balanced': 'Balanced — tease + care (default)',
    'strict': 'Strict — no excuses',
    'tsundere': 'Tsundere — b-baka! 😤',
  };

  @override
  void initState() {
    super.initState();
    _personality = widget.personality;
    _roastMode = widget.roastMode;
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: AppSpacing.screenH.copyWith(bottom: AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const MLDSectionHeader(title: 'Persona'),
            const SizedBox(height: AppSpacing.md),
            // v2.7.1: Radio.groupValue/onChanged deprecated after v3.32 —
            // migrated to the RadioGroup ancestor pattern.
            RadioGroup<String>(
              groupValue: _personality,
              onChanged: (v) {
                if (v != null) setState(() => _personality = v);
              },
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final e in _options.entries)
                    RadioListTile<String>(
                      value: e.key,
                      title:
                          Text(e.value, style: const TextStyle(fontSize: 14)),
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                    ),
                ],
              ),
            ),
            SwitchListTile(
              value: _roastMode,
              onChanged: (v) => setState(() => _roastMode = v),
              title: const Text('Roast mode',
                  style: TextStyle(fontSize: 14)),
              subtitle: const Text('Savage tier at 5h+ screen time',
                  style: TextStyle(fontSize: 12)),
              dense: true,
              contentPadding: EdgeInsets.zero,
            ),
            const SizedBox(height: AppSpacing.lg),
            MLDButton(
              label: 'Save',
              onPressed: () => Navigator.of(context).pop({
                'personality': _personality,
                'roastMode': _roastMode,
              }),
            ),
          ],
        ),
      ),
    );
  }
}
