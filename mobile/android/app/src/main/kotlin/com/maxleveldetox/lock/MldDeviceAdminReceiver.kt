package com.maxleveldetox.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * MldDeviceAdminReceiver — minimal device admin (v2.0 Phase A4).
 *
 * Declares only the force-lock policy (see res/xml/device_admin.xml).
 * Used exclusively by LockMyPhoneService to enforce the scheduled
 * lock-my-phone mode: DevicePolicyManager.lockNow() in a 1.5s loop while
 * the screen is on, with SCREEN_ON / USER_PRESENT re-enforcement.
 *
 * onDisableRequested: during an ACTIVE LockMyPhone session we explain the
 * consequence instead of silently allowing removal — the standard device-
 * admin behavior (the OS shows this text and the user decides). Outside a
 * session we say nothing: removal is the user's right.
 */
class MldDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val active = LockMyPhoneController.isSessionActive(context)
        return if (active) {
            "MAXLEVEL DETOX lock-my-phone is active. If you remove device " +
                "admin now, the scheduled phone lock will end immediately " +
                "and the session will be marked as bailed out."
        } else {
            "Device admin removed. Lock-my-phone will not be available " +
                "until you re-enable it."
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Record the tamper path — LockMyPhone sessions end via RECOVERY
        // (never silently) if admin rights were stripped mid-session.
        if (LockMyPhoneController.isSessionActive(context)) {
            LockMyPhoneController.onAdminStripped(context)
        }
    }
}
