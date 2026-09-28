package com.maxleveldetox.reels

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.maxleveldetox.accessibility.DiagLog

/**
 * ReelsRedirect (v2.7 r13) — user-requested UX change for shorts/reels
 * interception: when a feed is detected, STAY INSIDE THE APP and navigate
 * to its safe surface (YouTube -> Home, Facebook -> Feed, Instagram ->
 * Feed). The old GLOBAL_ACTION_HOME kick-out becomes the last-resort
 * fallback only.
 *
 * Ladder (v2.9 r16 — closed-loop, per platform, one rung per detection):
 *   0. RUNG 1 — BACK PRESS: immersive reels/shorts players (Facebook
 *      Reels, YouTube Shorts, Instagram Reels) are fullscreen surfaces ON
 *      TOP of the app's own feed/home — Android's BACK closes the player
 *      and lands on the feed. This is the most reliable in-app redirect
 *      (it is exactly what a manual user does) and needs no tree walk.
 *   1. RUNG 2 — NODE CLICK — find the app's own bottom-nav "Home" tab in
 *      the active window tree and click it (partial-player states where
 *      the nav bar is still visible).
 *   2. RUNG 3 — ROOT REVISIT — launch the app's own launcher intent with
 *      CLEAR_TOP|SINGLE_TOP. The app's root activity comes forward and
 *      everything stacked above it is finished. Same app, same task.
 *
 * v2.9.2 r18 (user-requested): the caller NEVER kicks the user out of a
 * feed platform — when every rung fails it re-arms the closed loop
 * (scheduled re-scans + episode verification keep fighting INSIDE the
 * app). HOME remains correct ONLY for platforms with no safe in-app
 * surface at all (TikTok: the whole app IS the feed; Chrome: a shorts
 * URL is just a tab) — see NO_SAFE_SURFACE.
 *
 * CLOSED LOOP: the a11y service's scheduled re-scans (+500/+1500/
 * +3000/+5000/+8000 ms) and the episode verification loop re-run
 * detection after every redirect, so a redirect that DIDN'T stick (the
 * old Facebook bug: root-revisit reported success but the reels screen
 * survived) automatically retries instead of silently winning.
 *
 * SECURITY: this navigates the TARGET app only. It never weakens the
 * escalation ladder, quotas, debounces or violation recording — those are
 * untouched (rules swap signatures only, TRD §34–35).
 */
object ReelsRedirect {

    /** v2.9 r16 — closed-loop rung bookkeeping (per package). Rung 1 =
     * BACK, 2 = click Home tab, 3 = root revisit, 4+ = caller's HOME
     * fallback. Reset after RUNG_RESET_MS without a new detection. */
    private val rungs = HashMap<String, Int>()
    private val rungAt = HashMap<String, Long>()
    private const val RUNG_RESET_MS = 20_000L

    private fun advanceRung(pkg: String): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(rungs) {
            if (now - (rungAt[pkg] ?: 0L) > RUNG_RESET_MS) rungs[pkg] = 0
            rungAt[pkg] = now
            val r = (rungs[pkg] ?: 0) + 1
            rungs[pkg] = r
            return r
        }
    }

    /** Forget the ladder (e.g. the user disabled the shorts blocker). */
    fun reset(pkg: String) = synchronized(rungs) {
        rungs.remove(pkg)
        rungAt.remove(pkg)
    }

    /** Bottom-nav tab labels worth clicking, per platform. Exact match on
     *  the trimmed label (so "Home" never matches "Homescreen"); English
     *  first, Bengali for our primary market. */
    private val HOME_TAB_LABELS: Map<String, List<String>> = mapOf(
        DetectionRules.PKG_YOUTUBE to listOf("Home", "হোম", "হোম পেজ"),
        DetectionRules.PKG_FACEBOOK to listOf("Home", "হোম", "News Feed", "নিউজ ফিড", "ফিড"),
        DetectionRules.PKG_FACEBOOK_LITE to listOf("Home", "হোম", "News Feed", "নিউজ ফিড"),
        DetectionRules.PKG_INSTAGRAM to listOf("Home", "হোম", "হোম ট্যাব"),
        DetectionRules.PKG_INSTAGRAM_LITE to listOf("Home", "হোম"),
    )

    /** Platforms with no safe in-app surface — HOME (or the caller's own
     *  fallback) is correct for these. */
    val NO_SAFE_SURFACE = setOf(
        DetectionRules.PKG_TIKTOK,
        DetectionRules.PKG_TIKTOK_REGIONAL,
        DetectionRules.PKG_CHROME,
        DetectionRules.PKG_CHROME_BETA,
    )

    /** BFS budget — a launcher tree walk is bounded so a pathological
     *  deep layout can never stall the a11y hot path. */
    private const val MAX_NODES = 800

    // -----------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------

    /**
     * From the accessibility service (has the node tree). Returns TRUE when
     * the user was kept inside the app (a rung succeeded) — the caller
     * should then SKIP its GLOBAL_ACTION_HOME fallback. Rung 4 returns
     * false on purpose: after three failed in-app attempts the caller's
     * HOME kick-out is the honest answer.
     */
    fun navigateFromService(service: AccessibilityService, pkg: String): Boolean {
        if (pkg in NO_SAFE_SURFACE) return false

        // 1. BACK press: immediately pops the fullscreen reel/shorts viewer back to Feed/Home
        if (tryBackPress(service)) return true

        // 2. Click Home/Feed bottom-nav tab in the app
        if (tryClickHomeTab(service, pkg)) return true

        // 3. Revisit root activity with CLEAR_TOP (stays inside app)
        if (tryRevisitRoot(service, pkg)) return true

        return false
    }

    /**
     * From any context (no node tree — e.g. the escalation manager after
     * the hard-lockout countdown). Root revisit only; returns FALSE when
     * the caller should apply its own fallback.
     */
    fun navigateFromContext(context: Context, pkg: String): Boolean {
        if (pkg in NO_SAFE_SURFACE) return false
        return tryRevisitRoot(context, pkg)
    }

    // -----------------------------------------------------------------
    // Rung 1 — BACK press closes the immersive player
    // -----------------------------------------------------------------

    /** A consumed BACK = the player is dismissing (feed lands behind it).
     *  Returns false only when the system refused the action — then the
     *  caller immediately tries the next rung (same invocation). */
    private fun tryBackPress(service: AccessibilityService): Boolean {
        return try {
            val consumed = service.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK)
            if (consumed) DiagLog.log("REELS_REDIRECT", "BACK pressed (player dismiss)")
            consumed
        } catch (_: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------
    // Rung 2 — click the app's own Home tab
    // -----------------------------------------------------------------

    private fun tryClickHomeTab(service: AccessibilityService, pkg: String): Boolean {
        val labels = HOME_TAB_LABELS[pkg] ?: return false
        return try {
            val root = service.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != pkg) return false

            val window = Rect()
            root.getBoundsInScreen(window)
            val navTop = window.bottom - ((window.height() * NAV_ZONE_FRACTION).toInt())
            var visited = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val b = Rect()
                node.getBoundsInScreen(b)
                val inNavZone = b.top >= navTop && b.height() > 0
                if (inNavZone && matchesLabel(node, labels)) {
                    if (clickNode(node)) {
                        DiagLog.log("REELS_REDIRECT", "$pkg -> clicked nav Home tab")
                        return true
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesLabel(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        for (label in labels) {
            if (text.equals(label, ignoreCase = true) ||
                desc.equals(label, ignoreCase = true)
            ) return true
        }
        return false
    }

    /** Click the node, or its nearest clickable ancestor (tab labels are
     *  often plain TextViews inside the clickable tab container). */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 4) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    // -----------------------------------------------------------------
    // Rung 3 — revisit the app's root activity (still the same app)
    // -----------------------------------------------------------------

    private fun tryRevisitRoot(context: Context, pkg: String): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(launch)
            DiagLog.log("REELS_REDIRECT", "$pkg -> root activity (CLEAR_TOP)")
            true
        } catch (_: Exception) {
            false
        }
    }

    private const val NAV_ZONE_FRACTION = 0.42f // bottom ~42% of the window
}
