package com.maxleveldetox.reels

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.maxleveldetox.accessibility.DiagLog

/**
 * ReelsRedirect (v2.7 r13 → v2.9.4 r20) — user-requested UX for
 * shorts/reels interception: when a feed is detected, STAY INSIDE THE
 * APP and navigate to its safe surface (YouTube -> Home, Facebook ->
 * Feed, Instagram -> Feed). The user is NEVER kicked out of a feed
 * platform.
 *
 * v2.9.4 r20 (user-reported regression fix): the old universal
 * "BACK-first" ladder caused two real bugs on device —
 *   (a) when Shorts was the app's ROOT surface (launcher shortcut /
 *       restored task), BACK did not close the player; the r18
 *       closed-loop kept pressing BACK every ~900 ms until YouTube's
 *       double-back-to-exit fired and the user was thrown OUT of the
 *       app ("ekdom app theke bair kore dicche");
 *   (b) the redirect machine-gun made single volume presses look like
 *       several presses.
 * The ladder is now PER PLATFORM and rate-limited:
 *   - youtube / instagram (+ lite): bottom-nav HOME TAB first (the
 *     pivot/tab bar is visible on the shorts/reels surface — this is
 *     exactly "go back to the app's feed"), then root revisit, and BACK
 *     only as the LAST rung (it is the only rung that can exit the app).
 *   - facebook (+ lite): BACK first (the reels player is fullscreen and
 *     hides the nav bar — BACK is the only thing that closes it; FB
 *     always opens on the feed, so the root-exit risk is negligible),
 *     then Home tab, then root revisit.
 *   - A per-package rate limit (min 2 s between navigate attempts) stops
 *     any caller — content events, scheduled re-scans, the episode
 *     verifier — from hammering rungs.
 *
 * SECURITY: this navigates the TARGET app only. It never weakens the
 * escalation ladder, quotas, debounces or violation recording (TRD
 * §34–35).
 */
object ReelsRedirect {

    /**
     * Platforms with no safe in-app surface — the whole app IS the feed,
     * so leaving it (HOME) is the only correct redirect. Browsers are
     * NOT listed any more: browser URL detection was removed entirely
     * in r20 (false-positive source, never user-requested).
     */
    val NO_SAFE_SURFACE = setOf(
        DetectionRules.PKG_TIKTOK,
        DetectionRules.PKG_TIKTOK_REGIONAL,
    )

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

    /**
     * r20 — per-package redirect order. Keyed by package; default order
     * for unlisted feed platforms is BACK -> tab -> revisit (the classic
     * fullscreen-player shape).
     */
    private val LADDER: Map<String, List<Rung>> = mapOf(
        DetectionRules.PKG_YOUTUBE to
            listOf(Rung.HOME_TAB, Rung.ROOT_REVISIT, Rung.BACK),
        DetectionRules.PKG_INSTAGRAM to
            listOf(Rung.HOME_TAB, Rung.ROOT_REVISIT, Rung.BACK),
        DetectionRules.PKG_INSTAGRAM_LITE to
            listOf(Rung.HOME_TAB, Rung.ROOT_REVISIT, Rung.BACK),
        DetectionRules.PKG_FACEBOOK to
            listOf(Rung.BACK, Rung.HOME_TAB, Rung.ROOT_REVISIT),
        DetectionRules.PKG_FACEBOOK_LITE to
            listOf(Rung.BACK, Rung.HOME_TAB, Rung.ROOT_REVISIT),
    )

    private enum class Rung { HOME_TAB, ROOT_REVISIT, BACK }

    /** BFS budget — a tree walk is bounded so a pathological deep layout
     *  can never stall the a11y hot path. */
    private const val MAX_NODES = 800

    // -----------------------------------------------------------------
    // Rate limit (r20) — one navigate attempt per package per window.
    // Without this, content events + re-scans + the episode verifier
    // together injected a global action every ~500 ms (the machine-gun
    // that exited apps and garbled volume presses).
    // -----------------------------------------------------------------

    private val lastNavigateAt = HashMap<String, Long>()
    private const val NAVIGATE_MIN_INTERVAL_MS = 2_000L

    /** True when a navigate attempt for [pkg] is allowed right now. */
    fun canNavigateNow(pkg: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastNavigateAt) {
            return now - (lastNavigateAt[pkg] ?: 0L) >= NAVIGATE_MIN_INTERVAL_MS
        }
    }

    private fun markNavigated(pkg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastNavigateAt) { lastNavigateAt[pkg] = now }
    }

    // -----------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------

    /**
     * From the accessibility service (has the node tree). Returns TRUE
     * when a rung succeeded — the caller must NOT apply any fallback.
     * Rate-limited: when the interval has not elapsed the call is a
     * no-op returning FALSE (the closed loop retries on the next event).
     */
    fun navigateFromService(service: AccessibilityService, pkg: String): Boolean {
        if (pkg in NO_SAFE_SURFACE) return false
        if (!canNavigateNow(pkg)) return false

        for (rung in LADDER[pkg] ?: DEFAULT_LADDER) {
            val ok = when (rung) {
                Rung.HOME_TAB -> tryClickHomeTab(service, pkg)
                Rung.ROOT_REVISIT -> tryRevisitRoot(service, pkg)
                Rung.BACK -> tryBackPress(service)
            }
            if (ok) {
                markNavigated(pkg)
                return true
            }
        }
        markNavigated(pkg) // failed round — still rate-limit the retries
        return false
    }

    private val DEFAULT_LADDER = listOf(Rung.BACK, Rung.HOME_TAB, Rung.ROOT_REVISIT)

    // -----------------------------------------------------------------
    // Rung — BACK press closes the immersive player (LAST resort for
    // youtube/instagram: the only rung that can exit the app when the
    // player is the task root).
    // -----------------------------------------------------------------

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
    // Rung — click the app's own Home/Feed tab
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
    // Rung — revisit the app's root activity (still the same app)
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
