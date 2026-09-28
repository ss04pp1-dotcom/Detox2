package com.maxleveldetox.reels

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.maxleveldetox.reels.DetectionRules.Shape

/**
 * ReelsDetector (v2.0 Phase B1 → v2.5.8 data-driven) — per-app,
 * signature-based short-form surface detection.
 *
 * v2.5.8 ROADMAP CHANGE (Dynamic Remote Rule Config): the per-platform
 * SIGNATURE SETS (view ids, content-description hints, URL shapes) now come
 * from [DetectionRules] — compiled defaults, overridable per platform by a
 * server-published ruleset (admin panel -> GET /detection/rules -> Flutter
 * -> NativeBridge.applyDetectionRules). When Facebook/Instagram/YouTube ship
 * a UI update that renames a view id, fresh signatures are pushed WITHOUT an
 * app release.
 *
 * What stays COMPILED (never remotely configurable — enforcement shape):
 *   - the four strategy engines below (view-id / package-gate / text-BFS /
 *     URL) and their decision logic,
 *   - the bounded traversal (MAX_BFS_NODES / MAX_CHILDREN_PER_NODE),
 *   - debounces, escalation ladder, scan delays (caller-side).
 *
 * PRIVACY: node text / content-desc / view-ids are matched IN MEMORY and
 * discarded. Nothing is persisted beyond (package, timestamp) by the
 * caller's violation logging. No URL or caption text is ever stored.
 */
class ReelsDetector {

    enum class Surface {
        /** Confirmed immersive reels/shorts feed — full escalation applies. */
        FEED,
        /** Reels-adjacent navigation surface (tab, profile link) — counts,
         *  but the caller may treat it as a softer signal. */
        NAV,
    }

    data class Detection(val surface: Surface, val strategy: String)

    // -----------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------

    fun detect(
        pkg: String,
        event: AccessibilityEvent?,
        rootNode: AccessibilityNodeInfo?,
    ): Detection? {
        val resolved = DetectionRules.resolve(pkg) ?: return null
        return when (resolved.shape) {
            Shape.VIEW_ID -> detectByViewIds(pkg, rootNode, resolved)
            Shape.PACKAGE_GATE -> Detection(Surface.FEED, "tiktok_instant_home")
            Shape.TEXT_BFS -> detectByTextBfs(event, rootNode, resolved)
        }
    }

    /**
     * v2.9 r16 — ACTIVITY-NAME detection: the WINDOW_STATE_CHANGED event's
     * className (e.g. `com.google.android.youtube.shorts.ShortsActivity`)
     * matched against the platform's activityHints (lowercased contains).
     *
     * This is the cheapest and most drift-resistant strategy: no node tree
     * walk at all, and activity class names survive the UI redesigns that
     * rename view ids (the classic YouTube miss vector). Returns null when
     * the platform has no hints configured or nothing matches.
     */
    fun detectActivity(pkg: String, className: String?): Detection? {
        if (className.isNullOrBlank()) return null
        val resolved = DetectionRules.resolve(pkg) ?: return null
        if (resolved.rule.activityHints.isEmpty()) return null
        val cls = className.lowercase()
        val hint = resolved.rule.activityHints.firstOrNull { cls.contains(it.lowercase()) }
            ?: return null
        return Detection(Surface.FEED, "${resolved.platformKey}_activity:$hint")
    }

    // -----------------------------------------------------------------
    // VIEW_ID strategy — YouTube / Facebook Lite / Instagram (+ lite):
    // any configured feed view id (qualified against the RUNNING package,
    // or used as-is when the entry is already `pkg:id/name`), with two
    // optional per-platform refinements:
    //   immersiveGate   — the matched node must cover at least half the
    //                     window height (FB-Lite inline-video disambiguation)
    //   immersiveViewIds — only affects the strategy label (pivot visible
    //                     vs immersed), never the decision
    // -----------------------------------------------------------------

    private fun detectByViewIds(
        pkg: String,
        root: AccessibilityNodeInfo?,
        resolved: DetectionRules.Resolved,
    ): Detection? {
        val rule = resolved.rule
        val feedIds = rule.feedViewIds + rule.liteFeedViewIds + rule.sharedFeedViewIds
        var matched: Pair<String, AccessibilityNodeInfo>? = null
        for (id in feedIds) {
            val qualified = if (id.contains(':')) id else "$pkg:id/$id"
            val node = findByViewId(root, qualified) ?: continue
            matched = id to node
            break
        }
        val (matchedId, matchedNode) = matched ?: return null

        if (rule.immersiveGate && !isImmersive(matchedNode, root)) return null

        val shortId = matchedId.substringAfterLast('/')
        val pivot = rule.immersiveViewIds.firstOrNull { id ->
            findByViewId(root, if (id.contains(':')) id else "$pkg:id/$id") != null
        }
        val strategy = when {
            resolved.platformKey == "youtube" && pivot != null -> "yt_shorts_pivot"
            resolved.platformKey == "youtube" -> "yt_shorts_immersed"
            else -> "${resolved.platformKey}:$shortId"
        }
        return Detection(Surface.FEED, strategy)
    }

    // -----------------------------------------------------------------
    // TEXT_BFS strategy — Facebook: cheap event-text pre-check, then a
    // bounded BFS over content descriptions. FEED requires the reel-details
    // family AND the nav family AND (reels OR fullscreen); reel-details
    // alone degrades to NAV.
    // -----------------------------------------------------------------

    private fun detectByTextBfs(
        event: AccessibilityEvent?,
        root: AccessibilityNodeInfo?,
        resolved: DetectionRules.Resolved,
    ): Detection? {
        val rule = resolved.rule

        // Cheap pre-check on event text first (avoids the BFS entirely for
        // the common case where the event carries the "Reel details" label).
        // v2.9 r16: event is nullable — scheduled re-scans have no event.
        if (event != null) {
            val eventText =
                event.text?.joinToString(separator = " ") { it?.toString() ?: "" } ?: ""
            if (rule.eventTextHints.any { eventText.contains(it) }) {
                return Detection(Surface.FEED, "fb_reel_details_text")
            }
        }

        if (root == null) return null

        var sawReelDetails = false
        var sawNav = false
        var sawReels = false
        var sawFullscreen = false
        bfsScan(root, MAX_BFS_NODES) { node ->
            val desc = node.contentDescription?.toString() ?: return@bfsScan false
            if (!sawReelDetails && rule.reelDetailsHints.any { desc.contains(it) }) sawReelDetails = true
            if (!sawNav && rule.navHints.any { desc.contains(it) }) sawNav = true
            if (!sawReels && rule.reelsHints.any { desc == it || desc.endsWith(it) }) sawReels = true
            if (!sawFullscreen && rule.fullscreenHints.any { desc.contains(it) }) sawFullscreen = true
            sawReelDetails && sawNav && (sawReels || sawFullscreen) // early exit
        }

        return when {
            sawReelDetails && sawNav && (sawReels || sawFullscreen) ->
                Detection(Surface.FEED, "fb_bfs_confirmed")
            sawReelDetails ->
                Detection(Surface.NAV, "fb_reel_details_partial")
            else -> null
        }
    }

    // -----------------------------------------------------------------
    // Shared traversal helpers (bounded — TRD §78)
    // -----------------------------------------------------------------

    private fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val direct = try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (_: Exception) {
            null
        }
        if (!direct.isNullOrEmpty()) return direct[0]
        // Partial-id fallback for OEM/version drift (e.g. no package prefix).
        val shortId = viewId.substringAfterLast('/')
        var found: AccessibilityNodeInfo? = null
        bfsScan(root, MAX_BFS_NODES) { node ->
            val id = node.viewIdResourceName ?: return@bfsScan false
            if (id.substringAfterLast('/') == shortId || id.substringAfterLast(':') == shortId) {
                found = node
                true
            } else false
        }
        return found
    }

    /**
     * True when [node] covers at least half of the window height. If the
     * geometry cannot be read we keep the previous behaviour (treat as
     * immersive) rather than silently disabling detection.
     */
    private fun isImmersive(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return true
        return try {
            val nodeBounds = android.graphics.Rect()
            val rootBounds = android.graphics.Rect()
            node.getBoundsInScreen(nodeBounds)
            root.getBoundsInScreen(rootBounds)
            val rootHeight = rootBounds.height()
            if (rootHeight <= 0) true else nodeBounds.height() * 2 >= rootHeight
        } catch (_: Exception) {
            true
        }
    }

    /** Iterative breadth-first scan with a hard node cap. */
    private inline fun bfsScan(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (visit(node)) return
            val children = node.childCount
            val limit = minOf(children, MAX_CHILDREN_PER_NODE)
            for (i in 0 until limit) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (_: Exception) {
                    // stale node mid-traversal — skip
                }
            }
        }
    }

    companion object {
        private const val MAX_BFS_NODES = 500
        private const val MAX_CHILDREN_PER_NODE = 40

        /**
         * Packages this detector has strategies for — DYNAMIC since v2.5.8:
         * the active (remote-or-compiled) ruleset decides which platforms are
         * enabled. Property syntax keeps the old `ReelsDetector.SUPPORTED_PACKAGES`
         * call sites working.
         */
        val SUPPORTED_PACKAGES: Set<String>
            get() = DetectionRules.supportedPackages()
    }
}
