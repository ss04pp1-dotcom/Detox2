package com.maxleveldetox.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.overlay.EnforcementWall

/**
 * DnsTamperDetector (v2.5 r9) — Private-DNS tamper interception, Social
 * Sentry enforcement mechanism #13.
 *
 * Social Sentry binds Private-DNS lock to Prime Mode: while a commitment
 * is active, opening the system "Private DNS" settings screen is treated
 * as a bypass attempt — the service presses BACK and shows a
 * non-dismissible overlay ("DNS Protection is locked by active Prime
 * Mode commit.").
 *
 * Detection (same signal SS uses — a11y screen scrape of settings text):
 *   - event package is a settings provider, AND
 *   - the visible node tree mentions private-dns vocabulary, AND
 *   - a Prime Commit enforcement window is live.
 * Response: GLOBAL_ACTION_BACK + the EnforcementWall HARD surface.
 *
 * SAFETY: never fires outside a Prime window; never during the settings
 * grace window; the word-list is narrow ("private dns" family only), so
 * ordinary settings browsing is untouched.
 */
object DnsTamperDetector {

    private val DNS_KEYWORDS = listOf(
        "private dns", "privatedns", "dns hostname", "dns provider",
        "private dns hostname", "指定的私人dns",
    )

    private var lastBackAt = 0L

    fun maybeIntercept(
        service: AccessibilityService,
        event: AccessibilityEvent,
    ): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return false

        val pkg = event.packageName?.toString() ?: return false
        val isSettings = pkg == "com.android.settings" ||
            pkg == "com.google.android.settings" ||
            pkg.endsWith(".settings")
        if (!isSettings) return false

        val app = service.application as? MldApp ?: return false

        // Only while a Prime Commit is enforcing.
        val prime = try {
            app.stateRepo.blockingPrime()
        } catch (_: Exception) {
            return false
        }
        if (!prime.active) return false

        // Never fight the restore window.
        val grace = try {
            app.stateRepo.blockingGraceWindow()
        } catch (_: Exception) {
            null
        }
        val now = com.maxleveldetox.enforcement.SystemClockNow.elapsed
        if (grace != null && grace.expiresElapsed > now) return false

        // Scrape the visible tree for private-dns vocabulary.
        val root = try {
            service.rootInActiveWindow ?: return false
        } catch (_: Exception) {
            return false
        }
        val sb = StringBuilder()
        scrapeNode(root, sb, 0)
        val text = sb.toString().lowercase()
        val hit = DNS_KEYWORDS.any { text.contains(it) }
        if (!hit) return false

        // Throttle: one response per 2 s.
        if (now - lastBackAt < 2_000L) return false
        lastBackAt = now

        DiagLog.log("DNS_TAMPER", "private-dns screen during Prime — blocking")
        return try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            EnforcementWall.showHard(
                service, pkg,
                "DNS Protection is locked by your active Prime Mode commit.",
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scrapeNode(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int,
        // v2.5.5 audit fix m-14: node budget (the UninstallInterceptor
        // pattern) — the a11y main thread must never walk an unbounded
        // settings tree while the detector is live.
        budget: IntArray = IntArray(1) { 220 },
    ) {
        if (node == null || depth > 30) return
        if (budget[0] <= 0) return
        budget[0]--
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) return
            scrapeNode(node.getChild(i), sb, depth + 1, budget)
        }
    }
}
