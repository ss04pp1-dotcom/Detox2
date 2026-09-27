package com.maxleveldetox.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.maxleveldetox.lock.LockMyPhoneController
import com.maxleveldetox.lock.LockMyPhoneService
import com.maxleveldetox.monk.MonkModeLockService
import com.maxleveldetox.monk.MonkModeManager

/**
 * ServiceRevival (v2.5 r9.2) — Social Sentry enforcement mechanisms #6/#7/#8.
 *
 * Lock-my-phone and monk-mode sessions are persisted (wall-clock authority),
 * but the FOREGROUND SERVICES that enforce them can be killed by an OEM
 * battery manager. Restart-on-boot and restart-on-process-start already
 * exist; what was missing was a periodic "session is live but its service is
 * dead -> start it" check. It is called from the persisted guard job, the
 * enforcement sweep and engine 2's slow tick.
 *
 * Liveness is process-local (`isAlive` flags set in onCreate/onDestroy): if
 * the whole process was killed the flags are naturally false again.
 * Starting a foreground service from the background can be refused by the
 * OS (API 31+); that is caught here — the guard notification and the next
 * pass are the fallback, never a crash.
 */
object ServiceRevival {

    /**
     * Re-arm a restart of [cls] ~1.5 s from now (used from `onTaskRemoved`:
     * many OEMs kill foreground services when the task is swiped away, and
     * START_STICKY alone is not honoured everywhere). An exact alarm is used
     * when permitted — alarms are one of the few sanctioned ways to start a
     * foreground service from the background on Android 12+.
     */
    fun scheduleServiceRestart(context: Context, cls: Class<*>, requestCode: Int) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, cls)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= 26) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
            val at = System.currentTimeMillis() + 1_500L
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
        }
    }

    fun reviveIfNeeded(context: Context) {
        // Scheduled / recurring lock-my-phone windows (throttled inside).
        try {
            com.maxleveldetox.lock.LockScheduler.evaluateAndRearm(context)
        } catch (_: Exception) {
        }
        try {
            if (LockMyPhoneController.isSessionActive(context) &&
                !LockMyPhoneService.isAlive
            ) {
                LockMyPhoneService.start(context)
            }
        } catch (_: Exception) {
        }
        try {
            if (MonkModeManager.isActive(context) && !MonkModeLockService.isAlive) {
                MonkModeLockService.start(context)
            }
        } catch (_: Exception) {
        }
    }
}
