package com.maxleveldetox.enforcement

import android.app.Activity
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * KioskController — the SESSION KIOSK facade (v2.9 r17 rewrite).
 *
 * HISTORY: this object used to drive user-consent screen pinning
 * (Activity.startLockTask) during DETOX sessions. Pinning is escapable by
 * design (hold Back + Recents), was never applied to STUDY, never covered
 * the launcher or the notification shade, and while pinned the emergency
 * dialer launch was BLOCKED by the OS — the classic "emergency exits,
 * system fails" report. The user explicitly asked for pinning to be
 * REPLACED by a real lockdown: "nothing works, no way out at all."
 *
 * r17: pinning is GONE. The enforcement now lives in SessionKiosk
 * (overlay/SessionKiosk.kt):
 *   - a full-screen TYPE_ACCESSIBILITY_OVERLAY wall over the launcher /
 *     settings / unknown apps / systemui surfaces,
 *   - top+bottom strips over the status/nav bars while our own app or a
 *     STUDY-allowlisted app is in the foreground,
 *   - BACK/APP_SWITCH/HOME key consumption in the a11y key filter.
 *
 * This facade keeps the historical call sites (SessionEngine,
 * TempUnlockManager, MainActivity) — it forwards to SessionKiosk with the
 * StateRepository's context.
 */
object KioskController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------
    // Activity lifecycle wiring (called from MainActivity)
    // ------------------------------------------------------------------

    fun onActivityResumed(activity: Activity) {
        com.maxleveldetox.overlay.SessionKiosk.onOwnAppResumed(activity)
    }

    fun onActivityPaused() {
        // The next a11y foreground event (or the 30 s sweep) decides
        // wall-vs-strips; nothing eager to do on pause.
    }

    fun onActivityDestroyed(activity: Activity) {
        // The kiosk windows live in the accessibility service process —
        // an activity death never removes them.
    }

    // ------------------------------------------------------------------
    // Desired-state sync (idempotent, safe from any thread)
    // ------------------------------------------------------------------

    fun sync(stateRepo: StateRepository) {
        com.maxleveldetox.overlay.SessionKiosk.sync(stateRepo.contextRef())
    }

    fun syncAsync(stateRepo: StateRepository) {
        scope.launch { sync(stateRepo) }
    }

    fun setRequested(want: Boolean) {
        // v2.9 r17: kept for source compatibility — the desired state is
        // always recomputed from persisted session state by SessionKiosk.
        // Callers must use sync/syncAsync.
    }
}
