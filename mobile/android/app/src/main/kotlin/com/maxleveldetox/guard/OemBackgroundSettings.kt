package com.maxleveldetox.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * OemBackgroundSettings (v2.5 r9.2) — Social Sentry enforcement mechanism
 * #34 / master-analysis E9 ("Chinese OEM matrix").
 *
 * The generic "ignore battery optimisations" dialog is not enough on the
 * phones most common in Bangladesh (Xiaomi / Redmi / POCO, OPPO / Realme,
 * Vivo / iQOO, Tecno / Infinix / itel, Huawei / Honor, Samsung, OnePlus):
 * each ships its own AUTOSTART / background-power manager, and that is the
 * setting that decides whether the OEM silently kills the enforcement
 * services. This opens the right OEM screen for the device.
 *
 * Component names are OEM-internal and drift between ROM versions, so every
 * candidate is tried defensively (an unknown component just fails and the
 * next one is tried) and the final fallback — this app's own system
 * "App info" page, from which the battery / autostart entries are one tap
 * away — always exists. Nothing here needs a permission.
 */
object OemBackgroundSettings {

    private class OemTarget(
        val brands: List<String>,
        val pkg: String,
        val cls: String,
        val label: String,
    )

    private val TARGETS = listOf(
        OemTarget(listOf("xiaomi", "redmi", "poco"), "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity", "Xiaomi Autostart"),
        OemTarget(listOf("oppo", "realme", "oneplus"), "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity", "ColorOS Startup manager"),
        OemTarget(listOf("oppo", "realme", "oneplus"), "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity", "ColorOS Startup manager"),
        OemTarget(listOf("oppo", "realme"), "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity", "OPPO Startup manager"),
        OemTarget(listOf("vivo", "iqoo"), "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", "Vivo Background startup"),
        OemTarget(listOf("vivo", "iqoo"), "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity", "Vivo Background power"),
        OemTarget(listOf("huawei", "honor"), "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", "Huawei App launch"),
        OemTarget(listOf("huawei", "honor"), "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity", "Huawei Protected apps"),
        OemTarget(listOf("samsung"), "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity", "Samsung Battery"),
        OemTarget(listOf("oneplus"), "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity", "OnePlus Auto-launch"),
        OemTarget(listOf("tecno", "infinix", "itel", "transsion"), "com.transsion.phonemaster",
            "com.cyin.himgr.autostart.AutoStartActivity", "Transsion Autostart"),
        OemTarget(listOf("asus"), "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity", "ASUS Autostart"),
    )

    /**
     * Opens the most specific OEM background / autostart screen available.
     * Returns a short human label of what was opened (never empty).
     */
    fun open(context: Context): String {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()

        for (t in TARGETS) {
            val matches = t.brands.any { maker.contains(it) || brand.contains(it) }
            if (!matches) continue
            try {
                val intent = Intent().apply {
                    component = ComponentName(t.pkg, t.cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return t.label
            } catch (_: Exception) {
                // Not present on this ROM version — try the next candidate.
            }
        }

        // Universal fallback: this app's own App-info page.
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "App info"
        } catch (_: Exception) {
            "unavailable"
        }
    }
}
