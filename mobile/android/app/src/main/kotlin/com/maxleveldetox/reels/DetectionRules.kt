package com.maxleveldetox.reels

import com.maxleveldetox.storage.StateRepository
import org.json.JSONObject

/**
 * DetectionRules (v2.5.8 roadmap — Reels/Shorts detection robustness).
 *
 * Dynamic remote rule config for the signature-based shorts detector: the
 * admin panel publishes fresh View IDs / content-description hints / URL
 * shapes whenever YouTube, Instagram, TikTok or Facebook ship a UI update,
 * and the app picks them up on the next sync — NO app release needed.
 *
 * SECURITY BOUNDARY (mirrors RuntimeConfig, TRD §34–35, §51):
 *  1. COMPILED_DEFAULTS are frozen in the binary and always safe — they are
 *     exactly the shipped signatures of ReelsDetector pre-v2.5.8.
 *  2. A remote ruleset is validated with the SAME strict schema the Worker
 *     enforces (platform whitelist, charset regexes, per-list + doc caps,
 *     unknown keys rejected) and REJECTED whole on any violation. A remote
 *     platform entry is a COMPLETE REPLACEMENT for that platform; platforms
 *     absent from the remote doc keep their compiled defaults; a platform
 *     with enabled=false is skipped entirely.
 *  3. Detection is enforcement-adjacent but never weakened remotely: rules
 *     only ADD/SUBTRACT signatures. The escalation ladder, debounces and
 *     scan budgets are compiled in and cannot be touched by config.
 *
 * PRIVACY: unchanged — signatures are matched IN MEMORY and discarded; the
 * remote doc is stored as opaque validated JSON, never node contents.
 */
object DetectionRules {

    /** One platform's signature set (all lists short-form view ids unless
     *  an entry carries a full `pkg:id/name` resource name). */
    data class PlatformRule(
        val enabled: Boolean = true,
        val feedViewIds: List<String> = emptyList(),
        val immersiveViewIds: List<String> = emptyList(),
        val eventTextHints: List<String> = emptyList(),
        val reelDetailsHints: List<String> = emptyList(),
        val navHints: List<String> = emptyList(),
        val reelsHints: List<String> = emptyList(),
        val fullscreenHints: List<String> = emptyList(),
        val immersiveGate: Boolean = false,
        val urlShapes: List<String> = emptyList(),
        val liteFeedViewIds: List<String> = emptyList(),
        val sharedFeedViewIds: List<String> = emptyList(),
    )

    /** Detection strategy shape — compiled in (rules swap signatures only). */
    enum class Shape { VIEW_ID, PACKAGE_GATE, TEXT_BFS, URL }

    /**
     * A resolved, effective rule: the shape tells ReelsDetector which
     * compiled strategy engine to run; `rule` carries the (remote-or-
     * compiled) signature set for it.
     */
    data class Resolved(
        val platformKey: String,
        val shape: Shape,
        val rule: PlatformRule,
        /** The packages this platform rule scans (first = primary namespace
         *  for resolving short view ids). */
        val packages: List<String>,
    )

    /** A parsed, validated remote ruleset. version 0 == compiled defaults. */
    data class RuleSet(val version: Int, val platforms: Map<String, PlatformRule>)

    // -----------------------------------------------------------------
    // Frozen limits — MUST match worker DETECTION_RULES_LIMITS.
    // -----------------------------------------------------------------

    private const val MAX_LIST_ENTRIES = 25
    private const val MIN_ENTRY_LENGTH = 3
    private const val MAX_ENTRY_LENGTH = 64
    private const val MAX_DOC_CHARS = 20_000
    private const val MAX_PLATFORMS = 7

    private val VIEW_ID_RE = Regex("^[A-Za-z][A-Za-z0-9_./:]{2,63}$")
    private val TEXT_HINT_RE = Regex("^[\\x20-\\x7E]+$")
    private val URL_SHAPE_RE = Regex("^[a-z0-9./:_-]+$")

    // -----------------------------------------------------------------
    // Package -> platform mapping (the compiled strategy shapes)
    // -----------------------------------------------------------------

    /** platform key -> packages + shape (order = primary package first). */
    private val PLATFORM_MAP: Map<String, Pair<Shape, List<String>>> = mapOf(
        "youtube" to (Shape.VIEW_ID to listOf(PKG_YOUTUBE)),
        // The whole TikTok app is the feed — package gate for BOTH packages.
        "tiktok" to (Shape.PACKAGE_GATE to listOf(PKG_TIKTOK, PKG_TIKTOK_REGIONAL)),
        "facebook" to (Shape.TEXT_BFS to listOf(PKG_FACEBOOK)),
        "facebook_lite" to (Shape.VIEW_ID to listOf(PKG_FACEBOOK_LITE)),
        // One shared signature set for BOTH Instagram packages (pre-v2.5.8
        // behavior: detectInstagram ran for the lite package too).
        "instagram" to (Shape.VIEW_ID to listOf(PKG_INSTAGRAM, PKG_INSTAGRAM_LITE)),
        "chrome" to (Shape.URL to listOf(PKG_CHROME)),
        "chrome_beta" to (Shape.URL to listOf(PKG_CHROME_BETA)),
    )

    private val DEFAULT_URL_SHAPES = listOf(
        "youtube.com/shorts",
        "m.youtube.com/shorts",
        "facebook.com/reel/",
        "facebook.com/reels/",
        "instagram.com/reel/",
        "instagram.com/reels/",
    )

    /**
     * The frozen compiled defaults — byte-equivalent to ReelsDetector's
     * pre-v2.5.8 shipped signatures (verified against the worker's
     * DEFAULT_DETECTION_RULES contract test).
     */
    val COMPILED_DEFAULTS: Map<String, PlatformRule> = mapOf(
        "youtube" to PlatformRule(
            enabled = true,
            feedViewIds = listOf("reel_watch_fragment_root"),
            immersiveViewIds = listOf("pivot_bar"),
        ),
        "tiktok" to PlatformRule(enabled = true),
        "facebook" to PlatformRule(
            enabled = true,
            eventTextHints = listOf("Reel details", "Reels tab details"),
            reelDetailsHints = listOf("Reel details", "Reels tab details"),
            navHints = listOf("Navigate to your Reels profile"),
            reelsHints = listOf("Reels"),
            fullscreenHints = listOf("Fullscreen"),
        ),
        "facebook_lite" to PlatformRule(
            enabled = true,
            feedViewIds = listOf("video_view"),
            immersiveGate = true,
        ),
        "instagram" to PlatformRule(
            enabled = true,
            feedViewIds = listOf("root_clips_layout"),
            liteFeedViewIds = listOf("clips_viewer_video_container"),
            sharedFeedViewIds = listOf("reel_recycler"),
        ),
        "chrome" to PlatformRule(enabled = true, urlShapes = DEFAULT_URL_SHAPES),
        "chrome_beta" to PlatformRule(enabled = true, urlShapes = DEFAULT_URL_SHAPES),
    )

    // -----------------------------------------------------------------
    // Active ruleset (thread-safe swap; read from the a11y hot path)
    // -----------------------------------------------------------------

    @Volatile
    private var remote: RuleSet? = null

    /** Cached supported-package set — the a11y hot path reads it on every
     *  content-change event, so it is computed once per ruleset change. */
    @Volatile
    private var cachedPackages: Set<String>? = null

    /** Effective rule for a package, or null when the platform is disabled
     *  or unknown (detector stands down). */
    fun resolve(pkg: String): Resolved? {
        val entry = PLATFORM_MAP.entries.firstOrNull { pkg in it.value.second } ?: return null
        val (shape, packages) = entry.value
        val rule = remote?.platforms?.get(entry.key) ?: COMPILED_DEFAULTS[entry.key] ?: return null
        if (!rule.enabled) return null
        return Resolved(entry.key, shape, rule, packages)
    }

    /** All packages whose platform is currently enabled (dynamic — the a11y
     *  service's early-bail set, previously the compiled SUPPORTED_PACKAGES). */
    fun supportedPackages(): Set<String> {
        cachedPackages?.let { return it }
        // Snapshot the ruleset once: activate() may swap `remote` between
        // the loop below and the cache store — a mixed set is harmless for
        // one read, but publishing a STALE cache after activate() nulled it
        // would pin the old set until the next activation. Re-check after
        // computing and only publish the cache when nothing changed.
        val snapshot = remote
        val out = mutableSetOf<String>()
        for ((key, meta) in PLATFORM_MAP) {
            val rule = snapshot?.platforms?.get(key) ?: COMPILED_DEFAULTS[key] ?: continue
            if (rule.enabled) out.addAll(meta.second)
        }
        if (remote === snapshot) {
            cachedPackages = out
            return out
        }
        // A swap happened mid-computation: recompute against the new ruleset
        // (the loop is trivially cheap; correctness of the hot-path set wins).
        return supportedPackages()
    }

    /** The active remote ruleset (null = compiled defaults, version 0). */
    fun activeRuleSet(): RuleSet = remote ?: RuleSet(0, emptyMap())

    // -----------------------------------------------------------------
    // Validation + activation
    // -----------------------------------------------------------------

    /**
     * Strict parse/validate of a remote rules payload (the Worker envelope's
     * `rules` object serialized). Returns null on ANY violation — a bad doc
     * never partially applies.
     */
    fun parseAndValidate(rulesJson: String): RuleSet? {
        if (rulesJson.length > MAX_DOC_CHARS) return null
        return try {
            val root = JSONObject(rulesJson)
            if (root.optInt("schemaVersion", -1) != 1) return null
            val platforms = root.optJSONObject("platforms") ?: return null

            val out = mutableMapOf<String, PlatformRule>()
            val keys = platforms.keys()
            var count = 0
            while (keys.hasNext()) {
                val key = keys.next()
                count++
                if (count > MAX_PLATFORMS) return null
                val shape = PLATFORM_MAP[key]?.first ?: return null // unknown platform
                val o = platforms.optJSONObject(key) ?: return null
                val enabled = o.optBoolean("enabled", false)

                fun list(name: String, re: Regex): List<String> {
                    val arr = o.optJSONArray(name) ?: return emptyList()
                    if (arr.length() > MAX_LIST_ENTRIES) throw ValidationException()
                    val seen = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        // Non-string entries are rejected outright (server
                        // does the same; org.json optString would coerce).
                        val raw = arr.opt(i)
                        if (raw !is String) throw ValidationException()
                        val v = raw
                        if (v.length < MIN_ENTRY_LENGTH || v.length > MAX_ENTRY_LENGTH) {
                            throw ValidationException()
                        }
                        if (!re.matches(v)) throw ValidationException()
                        seen.add(v)
                    }
                    return seen.toList()
                }

                val rule = PlatformRule(
                    enabled = enabled,
                    feedViewIds = list("feedViewIds", VIEW_ID_RE),
                    immersiveViewIds = list("immersiveViewIds", VIEW_ID_RE),
                    eventTextHints = list("eventTextHints", TEXT_HINT_RE),
                    reelDetailsHints = list("reelDetailsHints", TEXT_HINT_RE),
                    navHints = list("navHints", TEXT_HINT_RE),
                    reelsHints = list("reelsHints", TEXT_HINT_RE),
                    fullscreenHints = list("fullscreenHints", TEXT_HINT_RE),
                    urlShapes = list("urlShapes", URL_SHAPE_RE),
                    liteFeedViewIds = list("liteFeedViewIds", VIEW_ID_RE),
                    sharedFeedViewIds = list("sharedFeedViewIds", VIEW_ID_RE),
                    immersiveGate = o.optBoolean("immersiveGate", false),
                )

                // Package-gate shapes need no signatures; everything else
                // must carry at least one.
                val hasSignatures = rule.feedViewIds.isNotEmpty() ||
                    rule.immersiveViewIds.isNotEmpty() ||
                    rule.eventTextHints.isNotEmpty() || rule.reelDetailsHints.isNotEmpty() ||
                    rule.navHints.isNotEmpty() || rule.reelsHints.isNotEmpty() ||
                    rule.fullscreenHints.isNotEmpty() || rule.urlShapes.isNotEmpty() ||
                    rule.liteFeedViewIds.isNotEmpty() || rule.sharedFeedViewIds.isNotEmpty()
                if (shape != Shape.PACKAGE_GATE && !hasSignatures) return null

                out[key] = rule
            }
            RuleSet(root.optInt("version", 0), out)
        } catch (_: Exception) {
            null // corrupt payload -> keep the current ruleset (fail safe)
        }
    }

    private class ValidationException : Exception()

    /** Activate a validated ruleset (thread-safe swap; invalidates the
     *  supported-package cache). Called by NativeBridge after validation. */
    fun activate(rules: RuleSet) {
        remote = rules
        cachedPackages = null
    }

    /** Load a cached ruleset from DataStore at process start (MldApp). */
    fun restore(stateRepo: StateRepository) {
        val raw = stateRepo.blockingDetectionRulesJson() ?: return
        val parsed = parseAndValidate(raw) ?: return
        remote = parsed
        cachedPackages = null
    }

    /** Persist the raw validated payload (called together with activate). */
    suspend fun persist(stateRepo: StateRepository, rulesJson: String) {
        stateRepo.saveDetectionRulesJson(rulesJson)
    }

    // Package name constants shared with ReelsDetector.
    const val PKG_YOUTUBE = "com.google.android.youtube"
    const val PKG_TIKTOK = "com.zhiliaoapp.musically"
    const val PKG_TIKTOK_REGIONAL = "com.ss.android.ugc.trill"
    const val PKG_FACEBOOK = "com.facebook.katana"
    const val PKG_FACEBOOK_LITE = "com.facebook.lite"
    const val PKG_INSTAGRAM = "com.instagram.android"
    const val PKG_INSTAGRAM_LITE = "com.instagram.lite"
    const val PKG_CHROME = "com.android.chrome"
    const val PKG_CHROME_BETA = "com.chrome.beta"
}
