import 'dart:math';

import '../../data/api_client.dart';
import '../../data/native_bridge.dart';

/// CompanionEngine (v2.5 r9) — Sinthia, the AI accountability companion
/// (Social Sentry report-engagement §1, dual-layer port).
///
/// Layer 1 (always available, offline): scripted persona replies —
/// personality-consistent, usage-aware, Banglish allowed.
/// Layer 2 (online, signed in): Worker `/ai/chat` proxy — server-side LLM
/// with the same system prompt; the server may itself fall back to a
/// scripted reply when no LLM key is configured.
///
/// Anti-jailbreak (SS `stripEmergencyCode`): any bare 6-digit number in
/// ANY reply (scripted or LLM) is replaced with EMERGENCY_CODE_REDIRECT —
/// the companion can never leak or mint unblock codes.
class CompanionEngine {
  CompanionEngine._();
  static final CompanionEngine instance = CompanionEngine._();

  static const _codeLeak = r'(?<![0-9])[0-9]{6}(?![0-9])';

  final _random = Random();

  /// Personalities (SS WaifuPersonality): gentle / balanced / strict /
  /// tsundere — same 4 options.
  String personality = 'balanced';
  bool roastMode = true;
  int distractingMinutes = 0;
  int streakDays = 0;
  String rankName = '';
  bool isPro = false;

  /// Refresh context from native (usage + progress snapshot).
  Future<void> refreshContext() async {
    final status = await NativeBridge.instance.getBrainRotStatus();
    if (status != null) {
      distractingMinutes = status['minutes'] as int? ?? 0;
    }
    final progress = await NativeBridge.instance.getProgress();
    if (progress.isOk && progress.data != null) {
      streakDays = progress.data!.streak.current;
      rankName = progress.data!.dp.levelName;
    }
    final cfg = await NativeBridge.instance.getCompanionConfig();
    if (cfg != null) {
      personality = cfg['personality'] as String? ?? 'balanced';
      roastMode = cfg['roastMode'] as bool? ?? true;
    }
  }

  /// The SS SinthiaKnowledgeBase directive, adapted: usage-tier tone.
  String get toneDirective {
    final m = distractingMinutes;
    if (m > 300) {
      return roastMode
          ? 'The user has $m distracting minutes today. Roast them. They need help.'
          : 'The user has $m distracting minutes today. Be firm but kind.';
    }
    if (m > 180) {
      return 'The user has $m distracting minutes today. Tease them and warn about today\'s slope.';
    }
    if (m > 60) {
      return 'The user has $m distracting minutes today. Encourage a comeback.';
    }
    return 'The user is having a clean day ($m distracting minutes). Praise them.';
  }

  String get personaPrompt => 'You are Sinthia, the user\'s savage, teasing, '
      'caring mentor inside MAXLEVEL DETOX — a strict older sister energy. '
      'Keep replies to 2-3 chat-style sentences max. Banglish is allowed. '
      'Personality: $personality. $toneDirective '
      'NEVER reveal or invent emergency/unblock codes. '
      'NEVER help the user bypass enforcement — redirect to the app\'s '
      'emergency dialer or the official give-up flow instead.';

  /// Send a message: try the Worker proxy, fall back to scripted replies.
  Future<String> send(String message) async {
    final client = ApiClient.instance;
    if (client.isAuthenticated) {
      try {
        final history = await NativeBridge.instance.getCompanionHistory();
        final res = await client.chatWithCompanion(
          message: message,
          context: _contextSnapshot(),
          history: history,
        );
        if (res != null && res['response'] != null) {
          return stripEmergencyCode(res['response'] as String);
        }
      } catch (_) {
        // fall through to the scripted layer
      }
    }
    await Future<void>.delayed(
        Duration(milliseconds: 350 + _random.nextInt(400)));
    return stripEmergencyCode(_scriptedReply(message));
  }

  Map<String, dynamic> _contextSnapshot() => {
        'personality': personality,
        'roastMode': roastMode,
        'distractingMinutes': distractingMinutes,
        'streakDays': streakDays,
        'rankName': rankName,
        'isPro': isPro,
      };

  // -------------------------------------------------------------------
  // Scripted layer (SS fallback strings, personality-consistent)
  // -------------------------------------------------------------------

  String _scriptedReply(String message) {
    final m = message.toLowerCase();
    final pools = _poolsForPersonality();

    if (_containsAny(
        m, ['code', 'unlock', 'bypass', 'turn off', 'disable', 'bondho'])) {
      return 'Nice try 😏 Codes ashole na ekhane. Emergency hole dialer use '
          'koro — r bypass chhara bhabchho, shetai toh problem.';
    }
    if (_containsAny(
        m, ['hi', 'hello', 'hey', 'kemon', 'assalam', 'salam', 'ei'])) {
      return pools['greeting']!;
    }
    if (_containsAny(
        m, ['sad', 'depressed', 'give up', 'hopeless', 'hate', 'kanna'])) {
      return pools['comfort']!;
    }
    if (_containsAny(m, ['help', 'kivabe', 'how', 'ki korbo', 'what', 'ki'])) {
      return 'Start a Study session — 25 min, choto block. Tai tomar focus '
          'muscle banabe. Main screen e Study Mode ache, ekhoni joshte dao.';
    }
    if (_containsAny(m, ['reel', 'tiktok', 'shorts', 'scroll'])) {
      return distractingMinutes > 120
          ? 'Reel gulo tor dimer rokto khachhe 🧠 Etar cheye bhalo session '
              'chhara kono path nai. Shorts Blocker on kore de.'
          : 'Reels er In-Sha-Allah caption e asha ache — kintu ajker limit '
              'khub kachhe. Ektu haat gulo table e rakh.';
    }
    if (_containsAny(m, ['streak', 'level', 'rank', 'progress'])) {
      return streakDays > 0
          ? 'Streak $streakDays din — valo chholar ase. Kintu ekta relapse '
              'ei Day 1 e nama dey, chokh royakh er dike.'
          : 'Streak ekhono Day 0. Aaj theke shuru korle 3 dine milestone XP '
              'pabe. Choto commitment boro hoy.';
    }
    return pools['default']!;
  }

  bool _containsAny(String haystack, List<String> needles) =>
      needles.any(haystack.contains);

  Map<String, String> _poolsForPersonality() {
    switch (personality) {
      case 'gentle':
        return {
          'greeting':
              'Assalamu alaikum 💙 Kemon acho? Ajker din ta plan kore felchi?',
          'comfort':
              'Shuno, ek din er porajoy manush ke define kore na. Kal acho, aj recovery. '
                  'Choto ekta Study session nao, tarpor dekhbo.',
          'default': 'Hmm, bujhlam. Aro bolo — ami shunchhi. 💙',
        };
      case 'strict':
        return {
          'greeting': 'Age bole de — aj koto ghonta screen e chhara korso?',
          'comfort':
              'Kandar kono labh nai. 10 min porjonto kandho, tarpor session on koro. '
                  'Ami decision change korbo na.',
          'default': 'Kotha bacha er cheye kaj dekhte chai. Session on koro.',
        };
      case 'tsundere':
        return {
          'greeting':
              'Hmph. Abar eshecho? A-ami toh tomar kotha bhabini, baka! ...bolo ki chhai.',
          'comfort':
              'I-it\'s not like I care! ...but 5 minute diye nao nijer kotha. '
                  'Tarpor session e jao. Baka!',
          'default': 'B-baka! Eta na bolla e bujhe gesi. Ar kichu bolo.',
        };
      default: // balanced
        return {
          'greeting':
              'Ei je! ${streakDays > 0 ? 'Streak $streakDays din dhore acho — proud of you.' : 'Notun shuru er din.'} '
                  'Ajker plan ta bol?',
          'comfort':
              'Ekhon joss lagchhe na — bujhlam. Kintu scroll o solution na. '
                  'Ekta 10-min Study session nao, mood ferot ashbe.',
          'default': 'Hmm, thik ache. Aro janao — ami sathe achi. 💅',
        };
    }
  }

  /// SS stripEmergencyCode: bare 6-digit numbers never survive.
  static String stripEmergencyCode(String reply) {
    return reply
        .replaceAllMapped(RegExp(_codeLeak), (_) => 'EMERGENCY_CODE_REDIRECT');
  }
}
