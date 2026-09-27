package com.maxleveldetox.recovery

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.accessibility.DetoxAccessibilityService
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.ViolationType
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * PermissionMonitor (TRD §31–32) — tamper detection for enforcement
 * capabilities.
 *
 * Strategy: detection + persistent state + recovery. An ordinary Android app
 * cannot silently re-enable Accessibility after the user disables it — so
 * the engine records the violation, flags the session RECOVERY, and drives
 * the user to restore via an explicit, time-bounded settings grace window.
 * Unknown state NEVER silently unlocks (TRD §114).
 */
class PermissionMonitor(
    private val context: Context,
    private val stateRepo: StateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Snapshot(
        val accessibility: Boolean,
        val usageAccess: Boolean,
        val overlay: Boolean,
        val notifications: Boolean,
        val exactAlarms: Boolean,
        val batteryIgnored: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("accessibility", accessibility)
            put("usageAccess", usageAccess)
            put("overlay", overlay)
            put("notifications", notifications)
            put("exactAlarms", exactAlarms)
            put("batteryIgnored", batteryIgnored)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        accessibility = isAccessibilityEnabled(),
        usageAccess = hasUsageAccess(),
        overlay = Settings.canDrawOverlays(context),
        notifications = areNotificationsEnabled(),
        exactAlarms = canScheduleExactAlarms(),
        batteryIgnored = isIgnoringBatteryOptimizations(),
    )

    /**
     * Compare against the persisted snapshot; if a REQUIRED permission was
     * lost while a session is enforcing, record PERMISSION_TAMPER, set the
     * session to RECOVERY and prompt. If restored, resume ACTIVE.
     */
    fun checkAndReact() {
        val app = context as? MldApp ?: MldApp.get(context)
        val current = snapshot()
        val previousJson = stateRepo.blockingPermissionSnapshot()
        val previous = try {
            previousJson?.let { SnapshotFromJson(it) }
        } catch (_: Exception) {
            null
        }

        scope.launch {
            stateRepo.savePermissionSnapshot(current.toJson().toString())

            val session = stateRepo.blockingSession()
            if (session == null || !session.status.isEnforcing) return@launch

            val lostAccessibility = previous != null && previous.accessibility && !current.accessibility
            if (!current.accessibility && (previous == null || lostAccessibility)) {
                // The enforcement backbone is gone.
                com.maxleveldetox.accessibility.DiagLog.log("PERM_LOST", "accessibility (session enforcing)")
                app.violationManager.record(
                    sessionId = session.id,
                    pkg = "",
                    type = ViolationType.PERMISSION_TAMPER,
                    severity = "HIGH",
                    warningNumber = 0,
                    action = "accessibility_disabled",
                )

                // RECOVERY status (persisted) + high-priority notification.
                stateRepo.saveSession(session.copy(status = com.maxleveldetox.enforcement.SessionStatus.RECOVERY))
                notifyRestoreNeeded(app, "Accessibility")
                SessionEngine.Broadcaster.emit()
                // v2.1 Phase C: protection loss freezes DP progression and
                // arms the 24h Recovery Grace (streak at risk until restore).
                try {
                    app.progressEngine.onProtectionLost("Accessibility permission was lost during a session")
                } catch (_: Exception) {
                }
                return@launch
            }

            if (current.accessibility && session.status == com.maxleveldetox.enforcement.SessionStatus.RECOVERY) {
                // Restored — resume enforcement.
                com.maxleveldetox.accessibility.DiagLog.log("PERM_RESTORED", "accessibility -> ACTIVE")
                stateRepo.saveSession(session.copy(status = com.maxleveldetox.enforcement.SessionStatus.ACTIVE))
                SessionEngine.Broadcaster.emit()
                // v2.1 Phase C: grace cleared, progression resumes, streak intact.
                try {
                    app.progressEngine.onProtectionRestored()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Open a short, purpose-scoped settings grace window so the user can
     * RESTORE the accessibility permission without unblocking settings
     * browsing in general. The window is persisted, bounded (3 min) and the
     * target screen is the specific one needed.
     */
    fun openRestoreWindow(purpose: String) {
        val intent = appOpsAccessibilityIntent()
        scope.launch {
            stateRepo.saveGraceWindow(
                StateRepository.GraceWindow(
                    pkg = "com.android.settings",
                    purpose = purpose,
                    expiresElapsed = com.maxleveldetox.enforcement.SystemClockNow.elapsed + 3 * 60_000L,
                )
            )
        }
        try {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) { /* fall through to general settings */ }
    }

    // -----------------------------------------------------------------
    // Individual checks
    // -----------------------------------------------------------------

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, DetoxAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun areNotificationsEnabled(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Exception) {
        false
    }

    private fun canScheduleExactAlarms(): Boolean = try {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    } catch (_: Exception) {
        false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun appOpsAccessibilityIntent(): android.content.Intent =
        android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun notifyRestoreNeeded(app: MldApp, permission: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    SessionEngine.CH_RECOVERY,
                    context.getString(R.string.channel_recovery),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val n = NotificationCompat.Builder(context, SessionEngine.CH_RECOVERY)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_permission_lost))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "FOCUS PROTECTION INTERRUPTED — $permission is no longer active. " +
                    "Restore it to continue your session."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context, 0,
                    android.content.Intent(context, com.maxleveldetox.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        nm.notify(NOTIF_RESTORE, n)
    }

    companion object {
        private const val NOTIF_RESTORE = 3001

        fun SnapshotFromJson(json: String): Snapshot {
            val o = JSONObject(json)
            return Snapshot(
                accessibility = o.optBoolean("accessibility", false),
                usageAccess = o.optBoolean("usageAccess", false),
                overlay = o.optBoolean("overlay", false),
                notifications = o.optBoolean("notifications", false),
                exactAlarms = o.optBoolean("exactAlarms", false),
                batteryIgnored = o.optBoolean("batteryIgnored", false),
            )
        }
    }
}
