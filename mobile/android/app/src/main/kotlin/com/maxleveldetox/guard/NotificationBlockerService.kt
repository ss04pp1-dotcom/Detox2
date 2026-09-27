package com.maxleveldetox.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.enforcement.SessionMode

/**
 * NotificationBlockerService (v2.3 r7) — notification interception during
 * enforcement (Social Sentry E14 parity).
 *
 * A NotificationListenerService is a SECOND blocking surface fully
 * independent of both engines: even if the a11y engine and engine 2 are
 * both dead, notification noise from blocked apps stays suppressed.
 *
 * WHEN a notification is cancelled:
 *   1. Monk Mode active → cancel everything except the monk allowlist,
 *      system/telecom/launcher/keyboard notifications and ourselves.
 *   2. A DETOX session is enforcing AND the user enabled notification
 *      blocking → cancel everything except emergency/telecom/system.
 *      (STUDY sessions are allowlist-based focus — notifications stay.)
 *
 * ETHICS BOUNDS:
 *   - Our own notifications are NEVER cancelled (recovery prompts must
 *     reach the user).
 *   - Emergency/dialer/telecom packages are NEVER cancelled (PRD §27).
 *   - Outside enforcement, nothing is touched — an idle blocker app has
 *     no business reading (let alone cancelling) notifications.
 *   - Access must be granted explicitly by the user in Android Settings
 *     (Notification access) — surfaced as a Recommended capability tile.
 */
class NotificationBlockerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // BOUND: never our own recovery/status notifications.
        if (pkg == packageName) return

        val app = applicationContext as? MldApp ?: return

        val monkActive = try {
            com.maxleveldetox.monk.MonkModeManager.isActive(this)
        } catch (_: Exception) {
            false
        }

        val session = try {
            app.stateRepo.blockingSession()
        } catch (_: Exception) {
            null
        }
        val detoxBlocking = session != null &&
            session.status.isEnforcing &&
            session.mode == SessionMode.DETOX &&
            NotificationBlockerPrefs.enabled(this)

        if (!monkActive && !detoxBlocking) return

        // Never silence the emergency surface.
        if (app.policyEngine.isEmergency(pkg)) return

        if (monkActive) {
            val allowed = pkg in MonkModeNotifications.allowlist(this, app)
            if (allowed) return
        } else {
            // DETOX path: allow system + input + ourselves (already
            // handled) — everything social/entertainment gets cancelled.
            if (pkg == "android" || pkg == "com.android.systemui") return
        }

        try {
            cancelNotification(sbn.key)
            DiagLog.log("NOTIF_BLOCK", "cancelled notification from $pkg")
        } catch (_: Exception) {
            // The notification may already be gone — cancelling is
            // best-effort by design.
        }
    }
}

/** Tiny prefs for the user-facing toggle (default ON for Detox). */
object NotificationBlockerPrefs {
    private const val PREFS = "mld_notification_blocker"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }
}

/** Package allowlists used while Monk Mode owns the device. */
object MonkModeNotifications {

    fun allowlist(context: Context, app: MldApp): Set<String> {
        val set = HashSet<String>()
        set.add(context.packageName)
        set.add("android")
        set.add("com.android.systemui")
        set.add("com.android.server.telecom")
        set.add("com.android.incallui")
        set.addAll(app.policyEngine.emergencyPackages())
        set.addAll(launcherPackages(context))
        try {
            set.addAll(com.maxleveldetox.monk.MonkModeManager.allowedApps(context))
        } catch (_: Exception) {
        }
        return set
    }

    private val launchers: Set<String> by lazy {
        setOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.coloros.gallerylauncher",
            "com.nttdocomo.android.home_launcher",
        )
    }

    private fun launcherPackages(context: Context): Set<String> {
        val found = HashSet<String>()
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                found.add(ri.activityInfo.packageName)
            }
        } catch (_: Exception) {
        }
        if (found.isEmpty()) found.addAll(launchers)
        return found
    }
}

/** Helpers for the permission status + settings deep-link. */
object NotificationBlockerAccess {

    fun isGranted(context: Context): Boolean =
        android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )?.split(':')?.mapNotNull { runCatching {
            ComponentName.unflattenFromString(it)
        }.getOrNull() }?.any {
            it.packageName == context.packageName
        } ?: false

    fun settingsIntent(): Intent =
        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.let { action ->
            Intent(action)
        }
}
