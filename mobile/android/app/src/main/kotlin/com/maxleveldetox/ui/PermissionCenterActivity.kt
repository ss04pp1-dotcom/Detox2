package com.maxleveldetox.ui

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.maxleveldetox.lock.MldDeviceAdminReceiver

class PermissionCenterActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "System Health & Permissions",
            "Hardware and system bindings required for unbreakable detox enforcement"
        ) {
            finish()
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@PermissionCenterActivity, 20), 0, MldDesign.dp(this@PermissionCenterActivity, 20), MldDesign.dp(this@PermissionCenterActivity, 30))
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(container)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        container.removeAllViews()

        // 1. Accessibility Service
        val a11yGranted = isAccessibilityServiceEnabled()
        container.addView(createPermissionItem(
            name = "Accessibility Service",
            desc = "Primary enforcement engine. Detects restricted apps and shorts feeds.",
            isGranted = a11yGranted,
            actionLabel = "ENABLE ACCESSIBILITY"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // 2. Usage Stats
        val usageGranted = isUsageStatsGranted()
        container.addView(createPermissionItem(
            name = "Usage Stats Access",
            desc = "Monitors active foreground apps and tracks daily focus progress.",
            isGranted = usageGranted,
            actionLabel = "ENABLE USAGE ACCESS"
        ) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        // 3. Overlay (Draw Over Other Apps)
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        container.addView(createPermissionItem(
            name = "Display Over Other Apps",
            desc = "Renders the unbreakable study and detox overlay kiosk surfaces.",
            isGranted = overlayGranted,
            actionLabel = "ENABLE OVERLAY"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        })

        // 4. Battery Optimization Exemption
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        container.addView(createPermissionItem(
            name = "Battery Optimization Exemption",
            desc = "Prevents OEM aggressive process killers (Xiaomi, Samsung, Realme) from sleeping enforcement.",
            isGranted = batteryIgnored,
            actionLabel = "DISABLE BATTERY OPTIMIZATION"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        // 5. Device Admin (Uninstall Protection)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MldDeviceAdminReceiver::class.java)
        val adminGranted = dpm.isAdminActive(adminComponent)
        container.addView(createPermissionItem(
            name = "Device Admin (Uninstall Protection)",
            desc = "Protects MaxLevel Detox from impulsive uninstallation during active lockouts.",
            isGranted = adminGranted,
            actionLabel = "ACTIVATE DEVICE ADMIN"
        ) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects active focus sessions from impulsive removal.")
            }
            startActivity(intent)
        })
    }

    private fun createPermissionItem(
        name: String,
        desc: String,
        isGranted: Boolean,
        actionLabel: String,
        onAction: () -> Unit
    ): LinearLayout {
        return MldDesign.cardContainer(this, 16).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MldDesign.dp(this@PermissionCenterActivity, 14)
            }
            layoutParams = params

            val row = LinearLayout(this@PermissionCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val titleTv = TextView(this@PermissionCenterActivity).apply {
                    text = name
                    textSize = 15f
                    setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                    typeface = Typeface.DEFAULT_BOLD
                }
                val statusBadge = TextView(this@PermissionCenterActivity).apply {
                    text = if (isGranted) "✓ GRANTED" else "MISSING"
                    textSize = 11f
                    setTextColor(if (isGranted) Color.parseColor(MldDesign.ACCENT_SUCCESS) else Color.parseColor(MldDesign.ACCENT_DANGER))
                    typeface = Typeface.DEFAULT_BOLD
                    background = MldDesign.roundedDrawable(
                        this@PermissionCenterActivity,
                        if (isGranted) "#064E3B" else "#450A0A",
                        if (isGranted) MldDesign.ACCENT_SUCCESS else MldDesign.ACCENT_DANGER,
                        12
                    )
                    setPadding(MldDesign.dp(this@PermissionCenterActivity, 8), MldDesign.dp(this@PermissionCenterActivity, 4), MldDesign.dp(this@PermissionCenterActivity, 8), MldDesign.dp(this@PermissionCenterActivity, 4))
                }

                addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusBadge)
            }
            addView(row)

            val descTv = TextView(this@PermissionCenterActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
                setPadding(0, MldDesign.dp(this@PermissionCenterActivity, 6), 0, if (!isGranted) MldDesign.dp(this@PermissionCenterActivity, 12) else 0)
            }
            addView(descTv)

            if (!isGranted) {
                val btn = MldDesign.primaryButton(this@PermissionCenterActivity, actionLabel, MldDesign.ACCENT_STUDY) {
                    onAction()
                }
                addView(btn)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${com.maxleveldetox.accessibility.DetoxAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun isUsageStatsGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
