package com.maxleveldetox.enforcement

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.monk.MonkModeManager
import com.maxleveldetox.storage.StateRepository

/**
 * PolicyEngine (TRD §10) — the single authority for "is this package
 * allowed right now?".
 *
 * Evaluation order (each step is a security boundary):
 *   1. CAGE active            -> CAGE_BLOCK for everything non-essential
 *   2. Emergency/dialer       -> EMERGENCY_ALLOW (PRD §27, hard boundary)
 *   3. System essentials      -> SYSTEM_ALLOW (launcher, keyboard, our app…)
 *   4. Settings               -> BLOCK unless a purpose-scoped grace window
 *                                is open (tamper-resistant, time-bounded)
 *   5. No session             -> ALLOW (shorts handled separately)
 *   6. Temp unlock window     -> TEMP_ALLOW for allowlisted pkgs only
 *   7. Session allowlist      -> ALLOW
 *   8. Blocked categories/pkg -> BLOCK (default-deny under MAXLEVEL)
 *   9. Unknown package        -> per-strictness default (TRD §108)
 *
 * The engine reads persisted state via blocking DataStore reads on the
 * calling thread — callers run on background dispatchers.
 */
class PolicyEngine(
    private val context: Context,
    private val stateRepo: StateRepository,
    private val runtimeConfig: RuntimeConfig,
) {

    /** Packages that must never be blocked (system essentials). */
    private val systemAllow = buildSet {
        add(context.packageName)                                  // ourselves
        add("com.android.systemui")
        add("com.android.launcher3")
        add("com.google.android.apps.nexuslauncher")
        add("com.sec.android.app.launcher")                       // Samsung
        add("com.miui.home")                                      // Xiaomi
        add("com.huawei.android.launcher")                        // Huawei
        // v2.5 r9.1: more OEM launchers (the default launcher and the
        // enabled keyboards are ALSO resolved dynamically — see
        // dynamicEssentialPackages()); these are the static safety net.
        add("com.android.launcher")                               // AOSP / generic
        add("com.oppo.launcher")                                  // OPPO / Realme (older ColorOS)
        add("com.oplus.launcher")                                 // OPPO / Realme / OnePlus (ColorOS 12+)
        add("com.bbk.launcher2")                                  // Vivo / iQOO
        add("com.vivo.launcher")
        add("com.transsion.hilauncher")                           // Tecno / itel (HiOS)
        add("com.transsion.XOSLauncher")                          // Infinix (XOS)
        add("com.mi.android.globallauncher")                      // POCO / Mi global
        add("net.oneplus.launcher")                               // OnePlus (OxygenOS)
        add("com.motorola.launcher3")                             // Motorola
        add("com.sec.android.inputmethod")                        // Samsung keyboard
        add("com.touchtype.swiftkey")
        add("com.android.settings.intelligence")
        add("com.android.inputmethod.latin")                      // keyboards
        add("com.google.android.inputmethod.latin")
        add("com.samsung.android.honeyboard")
        add("com.android.wallpaper")
        add("com.android.systemui.plugin.globalactions.wallpaper")
    }

    /** Emergency + telephony — never blockable (PRD §27). */
    private val emergencyAllow = buildSet {
        add("com.android.dialer")
        add("com.google.android.dialer")
        add("com.samsung.android.dialer")
        add("com.android.contacts")
        add("com.google.android.contacts")
        add("com.samsung.android.app.contacts")
        add("com.android.incallui")
        add("com.android.server.telecom")
    }

    fun evaluate(pkg: String?): PolicyDecision {
        if (pkg.isNullOrBlank()) return PolicyDecision.ALLOW

        // 2/3 — emergency and system essentials come FIRST so no future
        // rule change can ever wall off calling for help.
        if (pkg in emergencyAllow) return PolicyDecision.EMERGENCY_ALLOW
        if (isSystemEssential(pkg)) return PolicyDecision.SYSTEM_ALLOW

        val now = SystemClockNow.elapsed

        // 1 — Cage blocks everything non-essential.
        val cage = stateRepo.blockingCage()
        if (cage.active && !cage.isExpired(now)) {
            return PolicyDecision.CAGE_BLOCK
        }

        // 1b — Monk mode (v2.0 Phase B3): device-wide allowlist lockdown.
        // Consulted by BOTH engines (a11y + UsageStats poller), so an OEM
        // kill of one engine cannot silently drop the lockdown.
        if (MonkModeManager.isActive(context)) {
            return if (pkg == context.packageName ||
                pkg in MonkModeManager.allowedApps(context) ||
                MonkModeManager.isDefaultLauncher(context, pkg) ||
                isSystemEssential(pkg)
            ) PolicyDecision.ALLOW else PolicyDecision.MONK_BLOCK
        }

        // 4 — Settings is blocked during sessions unless a grace window is open.
        if (isSettingsPackage(pkg)) {
            val session = stateRepo.blockingSession()
            if (session == null || !session.status.isEnforcing) return PolicyDecision.ALLOW
            val grace = stateRepo.blockingGraceWindow()
            return if (grace != null && grace.expiresElapsed > now) {
                PolicyDecision.ALLOW // purpose-scoped restore window
            } else {
                PolicyDecision.BLOCK
            }
        }

        // 5 — No active session: normal mode.
        val session = stateRepo.blockingSession()
            ?: return PolicyDecision.ALLOW
        if (!session.status.isEnforcing) return PolicyDecision.ALLOW

        // 6 — Temporary unlock window.
        val tempUnlock = stateRepo.blockingTempUnlock()
        if (tempUnlock.allows(pkg, now)) return PolicyDecision.TEMP_ALLOW

        // 7 — Session allowlist.
        if (pkg in session.allowedPackages) return PolicyDecision.ALLOW

        // 7b — v2.5.5 audit fix M-1: explicit per-package user rules
        // (App Rules screen). The ACTIVE session's snapshot stays
        // authoritative for categories, but user rules were previously a
        // complete no-op — setAppRule persisted nothing. An explicit user
        // BLOCK now wins over the category/strictness fallthrough, and an
        // explicit user ALLOW exempts the package from the unknown-package
        // default-deny of future sessions.
        try {
            if (AppRulesStore.isExplicitlyBlocked(context, pkg)) {
                return PolicyDecision.BLOCK
            }
            if (AppRulesStore.isExplicitlyAllowed(context, pkg)) {
                return PolicyDecision.ALLOW
            }
        } catch (_: Exception) {
            // Rule lookup must never break enforcement.
        }

        // 8 — Explicit blocked packages/categories.
        if (categoryOf(pkg)?.let { it in session.blockedCategories } == true) {
            return PolicyDecision.BLOCK
        }

        // 9 — Unknown-package policy by strictness (TRD §108).
        return when (session.strictness) {
            Strictness.BALANCED -> PolicyDecision.ALLOW
            Strictness.STRICT ->
                if (isKnownDistracting(pkg)) PolicyDecision.BLOCK else PolicyDecision.ALLOW
            Strictness.MAXLEVEL -> PolicyDecision.BLOCK
        }
    }

    /** True when the package must be intercepted by LockController. */
    fun shouldBlock(pkg: String?): Boolean {
        val decision = evaluate(pkg)
        return decision == PolicyDecision.BLOCK ||
            decision == PolicyDecision.CAGE_BLOCK ||
            decision == PolicyDecision.MONK_BLOCK
    }

    /** System essentials (launcher, keyboard, ourselves…) — used by the
     *  monk-mode engine's allowlist police too (v2.0 Phase B3). */
    fun isSystemEssential(pkg: String): Boolean =
        pkg in systemAllow || pkg in dynamicEssentialPackages()

    // -----------------------------------------------------------------
    // v2.5 r9.1 — dynamic essentials: the device's own launcher(s) and
    // input methods. A hardcoded list can never cover every OEM; without
    // this, MAXLEVEL's default-deny would treat an unlisted launcher (Home
    // button) or keyboard (typing) as a blocked app and wall the user.
    // Cached for a short TTL — evaluate() runs on every foreground event.
    // -----------------------------------------------------------------

    private var dynamicEssential: Set<String> = emptySet()
    private var dynamicResolvedAt = 0L

    private fun dynamicEssentialPackages(): Set<String> = synchronized(this) {
        val now = SystemClockNow.elapsed
        val ttl = if (dynamicEssential.isEmpty()) DYNAMIC_RETRY_MS else DYNAMIC_TTL_MS
        if (dynamicResolvedAt == 0L || now - dynamicResolvedAt >= ttl) {
            dynamicEssential = resolveDynamicEssential()
            dynamicResolvedAt = now
        }
        dynamicEssential
    }

    @Suppress("DEPRECATION")
    private fun resolveDynamicEssential(): Set<String> {
        val out = HashSet<String>()
        val pm = context.packageManager

        // Home / launcher apps (default first, then every installed HOME app).
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { out.add(it) }
            for (ri in pm.queryIntentActivities(home, 0)) {
                ri.activityInfo?.packageName?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }

        // Input methods: every enabled keyboard + the currently selected one.
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { out.add(it.packageName) }
        } catch (_: Exception) {
        }
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
        } catch (_: Exception) {
        }

        // "android" is the resolver/chooser shell — never treat it as a
        // launcher just because no default HOME app is set.
        out.remove("android")
        return out
    }

    /** Emergency + telephony packages — never blockable (PRD §27). */
    fun isEmergency(pkg: String): Boolean = pkg in emergencyAllow

    /** Read-only emergency surface (used by the notification blocker). */
    fun emergencyPackages(): Set<String> = emergencyAllow.toSet()

    // -----------------------------------------------------------------
    // Category resolution
    // -----------------------------------------------------------------

    private val categoryCache = mutableMapOf<String, String>()

    fun categoryOf(pkg: String): String? {
        synchronized(categoryCache) { categoryCache[pkg]?.let { return it } }

        val category = when {
            pkg in KNOWN_SHORTS_PACKAGES -> "shorts"
            pkg in KNOWN_SOCIAL_PACKAGES -> "social"
            pkg in KNOWN_GAME_PACKAGES -> "games"
            pkg in KNOWN_ENTERTAINMENT_PACKAGES -> "entertainment"
            pkg in KNOWN_EDUCATION_PACKAGES -> "education"
            else -> null
        } ?: run {
            // Fall back to Android's own application category.
            try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                when (ai.category) {
                    ApplicationInfo.CATEGORY_SOCIAL -> "social"
                    ApplicationInfo.CATEGORY_GAME -> "games"
                    ApplicationInfo.CATEGORY_VIDEO -> "entertainment"
                    ApplicationInfo.CATEGORY_AUDIO -> "entertainment"
                    ApplicationInfo.CATEGORY_NEWS -> "entertainment"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
                    else -> null
                }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }

        if (category != null) {
            synchronized(categoryCache) { categoryCache[pkg] = category }
        }
        return category
    }

    private fun isKnownDistracting(pkg: String): Boolean =
        categoryOf(pkg) in setOf("social", "games", "entertainment", "shorts")

    /** v2.5 r9: public classifier for the Brain Rot engine/widget. */
    fun isDistracting(pkg: String): Boolean = isKnownDistracting(pkg)

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == "com.android.settings" || pkg.endsWith(".settings") &&
            pkg.startsWith("com.android") || pkg == "com.miui.securitycenter" ||
            pkg == "com.samsung.android.SettingsBigData" ||
            pkg == "com.coloros.safecenter" || pkg == "com.vivo.settings"

    // -----------------------------------------------------------------
    // Known package lists (extendable via app updates + remote config)
    // -----------------------------------------------------------------

    companion object {
        /** Dynamic launcher/IME cache lifetime (ms) and empty-result retry. */
        private const val DYNAMIC_TTL_MS = 60_000L
        private const val DYNAMIC_RETRY_MS = 5_000L

        val KNOWN_SHORTS_PACKAGES = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.facebook.katana",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.snapchat.android",
        )

        val KNOWN_SOCIAL_PACKAGES = setOf(
            "com.facebook.orca",             // Messenger
            "com.twitter.android",           // X
            "com.linkedin.android",
            "com.reddit.frontpage",
            "com.pinterest",
            "org.telegram.messenger",
            "com.whatsapp",
        )

        val KNOWN_GAME_PACKAGES = setOf(
            "com.supercell.clashofclans",
            "com.kiloo.subwaysurf",
            "com.tencent.ig",
            "com.dts.freefireth",
            "com.roblox.client",
            "com.mojang.minecraftpe",
        )

        val KNOWN_ENTERTAINMENT_PACKAGES = setOf(
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.disney.disneyplus",
            "com.hotstar.dplus",
        )

        val KNOWN_EDUCATION_PACKAGES = setOf(
            "com.google.android.apps.classroom",
            "com.duolingo",
            "com.khanacademy.android",
            "com.quizlet.quizletandroid",
        )
    }

    // -----------------------------------------------------------------
    // Permission deep-links (used by PermissionMonitor restore flows)
    // -----------------------------------------------------------------

    fun settingsIntentFor(key: String): Intent = when (key) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "usageAccess" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"))
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "exactAlarms" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        // v2.5 r9: the admin toggle screen (activate/deactivate list).
        "deviceAdmin" -> Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                android.content.ComponentName(
                    context, com.maxleveldetox.lock.MldDeviceAdminReceiver::class.java))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"))
    }
}
