package com.maxleveldetox.unlock

import android.content.Context
import com.maxleveldetox.MldApp
import com.maxleveldetox.accessibility.DiagLog
import com.maxleveldetox.enforcement.SystemClockNow
import org.json.JSONObject

/**
 * AutoReblockMonitor (v2.5 r9) — Social Sentry enforcement mechanism #17:
 * SavedBlockingState snapshot + 5 s auto-reblock, making "temporary
 * unblock" tamper-proof.
 *
 * WHY: our lazy expiry (checked on every policy read) already restores
 * enforcement the instant the unlock window lapses — but only while SOME
 * engine is alive to evaluate. If the process was OEM-killed mid-unlock,
 * nothing re-arms until the next event. Social Sentry closes this with a
 * snapshot + a 5 s restore tick.
 *
 * HOW: when a temp unlock starts, the enforcement scope that was paused
 * (session id + mode + endElapsed) is snapshotted to prefs. Every
 * ForegroundAppMonitorService sweep (850 ms while engine 2 is up — ≤5 s
 * in the worst degraded case) calls [tick]:
 *   - unlock window expired but still marked active → clear it and
 *     re-assert enforcement from the snapshot (re-arm engines);
 *   - session ended while a snapshot exists → drop the snapshot.
 */
object AutoReblockMonitor {

    private const val PREFS = "mld_autoreblock"
    private const val K_SNAPSHOT = "snapshot"

    /** Persist the paused enforcement scope at unlock start. */
    fun snapshotUnlockStart(context: Context) {
        val app = context.applicationContext as? MldApp ?: return
        val session = try {
            app.stateRepo.blockingSession()
        } catch (_: Exception) {
            null
        } ?: return
        val json = JSONObject().apply {
            put("sessionId", session.id)
            put("mode", session.mode.name)
            put("endElapsed", session.endElapsed)
            put("savedAt", System.currentTimeMillis())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_SNAPSHOT, json.toString()).apply()
    }

    /** Sweep tick — call from the ForegroundAppMonitorService loop
     *  (suspend: clears expired unlock windows in-place). */
    suspend fun tick(context: Context) {
        val app = context.applicationContext as? MldApp ?: return
        try {
            val unlock = app.stateRepo.blockingTempUnlock()
            val now = SystemClockNow.elapsed

            if (unlock.active && unlock.isExpired(now)) {
                // Window lapsed: clear + engines re-assert from state on
                // the next evaluation (and re-arm engine 2 immediately).
                DiagLog.log("AUTO_REBLOCK", "temp unlock expired — re-asserting")
                app.stateRepo.saveTempUnlock(
                    com.maxleveldetox.enforcement.TempUnlockSnapshot.INACTIVE
                )
                com.maxleveldetox.monitor.ForegroundAppMonitorService.start(context)
            }

            // Snapshot hygiene: drop when its session is gone.
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(K_SNAPSHOT, null) ?: return
            val snap = JSONObject(raw)
            val session = app.stateRepo.blockingSession()
            if (session == null || session.id != snap.optString("sessionId")) {
                prefs.edit().remove(K_SNAPSHOT).apply()
            }
        } catch (_: Exception) {
        }
    }
}
