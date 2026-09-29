package com.maxleveldetox.enforcement

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.R
import com.maxleveldetox.config.RuntimeConfig
import com.maxleveldetox.coins.CoinLedger
import com.maxleveldetox.recovery.EnforcementReceiver
import com.maxleveldetox.recovery.PermissionMonitor
import com.maxleveldetox.storage.MldDatabase
import com.maxleveldetox.storage.SessionEntity
import com.maxleveldetox.storage.StateRepository
import com.maxleveldetox.unlock.TempUnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SessionEngine (TRD §5) — the central authority for Study/Detox sessions.
 *
 * ANTI-BYPASS DESIGN:
 *  - Timing uses SystemClock.elapsedRealtime() persisted at start; wall-clock
 *    changes cannot extend or shorten the session (TRD §52).
 *  - Every state transition happens inside a mutex and persists BEFORE the
 *    in-memory view updates (TRD §94).
 *  - stopSession from Flutter only succeeds when the legitimate end time has
 *    passed; otherwise SESSION_NOT_COMPLETE (TRD §42).
 *  - Bailout spends coins in ONE atomic Room transaction before the session
 *    is terminated (TRD §24/§100).
 *  - Force-stop/process death leaves persisted state; recoverIfNeeded()
 *    restores or legitimately completes on next process start (TRD §34/§76).
 *  - A shutdown/reboot is NEVER treated as session completion (PRD §26).
 */
class SessionEngine(
    private val context: Context,
    private val stateRepo: StateRepository,
    private val database: MldDatabase,
    private val policyEngine: PolicyEngine,
    private val violationManager: ViolationManager,
    private val tempUnlockManager: TempUnlockManager,
    private val coinLedger: CoinLedger,
    private val runtimeConfig: RuntimeConfig,
    private val permissionMonitor: PermissionMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // -----------------------------------------------------------------
    // Broadcast plumbing: native -> Flutter state stream
    // -----------------------------------------------------------------

    object Broadcaster {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        /** Register a state listener (v2.2 Phase D: multi-listener so the
         *  bridge and the widget updater can coexist). */
        fun addListener(l: () -> Unit) {
            listeners.add(l)
        }

        /** v2.5.5 audit fix M-6: unregister — NativeBridge.detach() removes
         *  its listener on activity recreation so stale bridges (holding
         *  dead MainActivitys) never accumulate. */
        fun removeListener(l: () -> Unit) {
            listeners.remove(l)
        }

        fun emit() {
            listeners.forEach { listener ->
                try {
                    listener()
                } catch (_: Exception) {
                    // One faulty listener must never break the others.
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Start
    // -----------------------------------------------------------------

    data class StartResult(val ok: Boolean, val errorCode: String?, val message: String?)

    suspend fun startSession(
        mode: SessionMode,
        durationMinutes: Int,
        strictness: Strictness,
        allowedPackages: List<String>,
        blockedCategories: List<String>,
        subject: String = "",
    ): StartResult = mutex.withLock {
        // Permission precheck — a session without enforcement capabilities
        // would be a false promise (TRD §97).
        val perms = permissionMonitor.snapshot()
        if (!perms.accessibility) {
            return StartResult(false, ErrorCodes.ACCESSIBILITY_DISABLED,
                "Accessibility service is required to enforce the session.")
        }
        if (!perms.usageAccess) {
            return StartResult(false, ErrorCodes.USAGE_ACCESS_DISABLED,
                "Usage access is required for accurate enforcement.")
        }
        if (!perms.overlay) {
            return StartResult(false, ErrorCodes.OVERLAY_DISABLED,
                "Display over other apps is required for the blocking screen.")
        }

        val existing = stateRepo.blockingSession()
        if (existing != null && existing.status.isLive) {
            return StartResult(false, ErrorCodes.SESSION_NOT_ACTIVE,
                "A session is already active.") // only one at a time (TRD §118)
        }

        val duration = durationMinutes.coerceIn(1, 24 * 60)
        val now = SystemClockNow.elapsed

        val snapshot = SessionSnapshot(
            id = generateSessionId(),
            mode = mode,
            status = SessionStatus.ACTIVE,
            startElapsed = now,
            endElapsed = now + duration * 60_000L,
            createdAtWall = System.currentTimeMillis(),
            strictness = strictness,
            policyVersion = 1,
            allowedPackages = allowedPackages.toSet() + PolicyEngine.KNOWN_EDUCATION_PACKAGES,
            blockedCategories = blockedCategories.toSet(),
            violationCount = 0,
            subjectName = if (mode == SessionMode.STUDY) subject.trim().take(24) else "",
        )

        // Persist FIRST (TRD §94 atomic start, §95).
        stateRepo.saveSession(snapshot)
        violationManager.clearSessionViolations = snapshot.id

        // v2.9.9 r25 — a fresh session starts a fresh shorts ladder: the
        // burst counter is persisted, so pre-session attempts (a
        // near-threshold leftover) must never cage the user inside their
        // own session start (user report: "starting Study Mode throws me
        // straight into the cage"). Best-effort — never blocks the start.
        try {
            com.maxleveldetox.MldApp.get(context).reelsEscalation.resetBurst()
        } catch (_: Exception) {
        }

        // Count of blocked apps for UI display.
        SessionSnapshot.cachedBlockedCount = countBlockedApps(snapshot)

        EnforcementService.start(context)
        scheduleEndAlarm(snapshot.endElapsed)
        createNotificationChannelsOnce()

        // v2.9.11 r27: arm the a11y key filter THIS INSTANT — it is kept
        // disarmed at idle (the volume multi-press root fix) and the
        // nav-key lockdown must be live from the first second of the
        // session, not on the next 2 s watchdog tick.
        try {
            com.maxleveldetox.accessibility.DetoxAccessibilityService.syncKeyFilterSoon()
        } catch (_: Exception) {
        }

        Broadcaster.emit()
        // v2.5.5 audit fix m-4: the identical-branch conditional was a
        // copy-paste smell — emit mode-specific event names.
        AnalyticsOut.post(
            if (mode == SessionMode.STUDY) "SESSION_STARTED_STUDY" else "SESSION_STARTED_DETOX",
            mapOf("mode" to mode.name, "durationMinutes" to duration))
        // v2.9 r17: sync the SESSION KIOSK (wall/strips) for both modes
        // (STUDY included — the old screen pinning is gone).
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)

        return StartResult(true, null, null)
    }

    // -----------------------------------------------------------------
    // Lazy completion — evaluated on every access + on the end alarm.
    // A session completes ONLY when elapsed >= endElapsed (TRD §53).
    // -----------------------------------------------------------------

    suspend fun evaluateAndMaybeComplete(): Boolean = mutex.withLock {
        val session = stateRepo.blockingSession() ?: return false
        val now = SystemClockNow.elapsed
        if (session.status == SessionStatus.PAUSED) {
            // A study break ends on its own; the session end moves back by
            // the break length.
            if (now >= session.pauseEndElapsed) resumeLocked(session)
            return false
        }
        if (!session.status.isEnforcing) return false
        if (!session.isExpired(now)) return false

        finalizeLocked(session, SessionStatus.COMPLETED)
        return true
    }

    // -----------------------------------------------------------------
    // Study break (pause / resume) — v2.5 r9.4
    //
    // Bounded by design so it cannot become a free exit: STUDY mode only,
    // at most MAX_PAUSES breaks per session, each at most MAX_PAUSE_MINUTES,
    // never during a cage / temporary unlock / Prime commit, and it always
    // auto-ends by wall clock. Break time is ADDED to the end of the
    // session (Social Sentry excludes paused time from the duration).
    // -----------------------------------------------------------------

    suspend fun pauseSession(minutes: Int): StartResult = mutex.withLock {
        val session = stateRepo.blockingSession()
        if (session == null || session.status != SessionStatus.ACTIVE) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED,
                "There is no running session to pause.")
        }
        if (session.mode != SessionMode.STUDY) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED,
                "Breaks are only available in Study Mode.")
        }
        val prime = stateRepo.blockingPrime()
        if (prime.active && prime.sessionId == session.id) {
            return StartResult(false, ErrorCodes.PRIME_ACTIVE,
                "A Prime commit owns this session — it cannot be paused.")
        }
        val now = SystemClockNow.elapsed
        if (stateRepo.blockingCage().let { it.active && !it.isExpired(now) } ||
            stateRepo.blockingTempUnlock().let { it.active && !it.isExpired(now) }
        ) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED,
                "Finish the current cage / temporary unlock first.")
        }
        if (session.pauseCount >= SessionSnapshot.MAX_PAUSES) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED,
                "You have used all ${SessionSnapshot.MAX_PAUSES} breaks for this session.")
        }
        if (session.remainingSeconds(now) < 120) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED,
                "Too close to the end of the session for a break.")
        }

        val mins = minutes.coerceIn(1, SessionSnapshot.MAX_PAUSE_MINUTES)
        val paused = session.copy(
            status = SessionStatus.PAUSED,
            pausedAtElapsed = now,
            pauseEndElapsed = now + mins * 60_000L,
            pauseCount = session.pauseCount + 1,
        )
        stateRepo.saveSession(paused)   // persist FIRST
        // While paused the end-alarm slot drives the auto-resume.
        cancelAlarm(EnforcementReceiver.ACTION_SESSION_END)
        scheduleAlarm(EnforcementReceiver.ACTION_SESSION_END, paused.pauseEndElapsed)
        Broadcaster.emit()
        AnalyticsOut.post("SESSION_PAUSED",
            mapOf("minutes" to mins, "pauseNumber" to paused.pauseCount))
        return StartResult(true, null, null)
    }

    suspend fun resumeSession(): StartResult = mutex.withLock {
        val session = stateRepo.blockingSession()
        if (session == null || session.status != SessionStatus.PAUSED) {
            return StartResult(false, ErrorCodes.PAUSE_NOT_ALLOWED, "The session is not on a break.")
        }
        resumeLocked(session)
        return StartResult(true, null, null)
    }

    /** Called with the mutex held: PAUSED -> ACTIVE, end pushed back. */
    private suspend fun resumeLocked(session: SessionSnapshot) {
        val now = SystemClockNow.elapsed
        val pausedFor = (now - session.pausedAtElapsed).coerceAtLeast(0L)
        val resumed = session.copy(
            status = SessionStatus.ACTIVE,
            endElapsed = session.endElapsed + pausedFor,
            pausedAtElapsed = 0L,
            pauseEndElapsed = 0L,
            pausedTotalSeconds = session.pausedTotalSeconds + (pausedFor / 1000L).toInt(),
        )
        stateRepo.saveSession(resumed)
        cancelAlarm(EnforcementReceiver.ACTION_SESSION_END)
        scheduleEndAlarm(resumed.endElapsed)
        EnforcementService.start(context)
        Broadcaster.emit()
        AnalyticsOut.post("SESSION_RESUMED", mapOf("pausedSeconds" to (pausedFor / 1000L).toInt()))
    }

    /** Flutter-requested stop — honored only at legitimate end time. */
    suspend fun requestStop(): StartResult = mutex.withLock {
        val session = stateRepo.blockingSession()
            ?: return StartResult(false, ErrorCodes.SESSION_NOT_ACTIVE, "No active session.")
        if (!session.isExpired(SystemClockNow.elapsed)) {
            return StartResult(false, ErrorCodes.SESSION_NOT_COMPLETE,
                "The session has not reached its end time.")
        }
        finalizeLocked(session, SessionStatus.COMPLETED)
        return StartResult(true, null, null)
    }

    // -----------------------------------------------------------------
    // Bailout — expensive, native-validated, atomic (TRD §24, PRD §18)
    // -----------------------------------------------------------------

    suspend fun validateBailout(): Map<String, Any> {
        val cost = runtimeConfig.current().bailoutCoins
        val balance = coinLedger.balance()
        return mapOf(
            "cost" to cost,
            "balance" to balance,
            "eligible" to (balance >= cost &&
                (stateRepo.blockingSession()?.status?.isLive == true)),
        )
    }

    suspend fun executeBailout(): StartResult = mutex.withLock {
        val session = stateRepo.blockingSession()
        if (session == null || !session.status.isLive) {
            return StartResult(false, ErrorCodes.SESSION_NOT_ACTIVE, "No active session to end.")
        }

        // Prime commits refuse the coin bailout — the ONLY exit is the
        // TOTP-gated give-up flow (v2.0 Phase B5).
        val prime = stateRepo.blockingPrime()
        if (prime.active && prime.sessionId == session.id) {
            return StartResult(false, ErrorCodes.PRIME_ACTIVE,
                "A Prime commit owns this session. Use the emergency give-up flow.")
        }

        // Atomic spend — if the coins are insufficient the session survives.
        val spend = coinLedger.spend(
            type = "BAILOUT_SPEND",
            cost = runtimeConfig.current().bailoutCoins,
            reference = "bailout:${session.id}",
        )
        if (!spend.first) {
            return StartResult(false, ErrorCodes.INSUFFICIENT_COINS,
                "Bailout requires ${runtimeConfig.current().bailoutCoins} coins.")
        }

        finalizeLocked(session, SessionStatus.BAILOUT)
        AnalyticsOut.post("SESSION_BAILED_OUT", mapOf("mode" to session.mode.name))
        return StartResult(true, null, null)
    }

    // -----------------------------------------------------------------
    // Prime give-up finalization (v2.0 Phase B5) — called ONLY by
    // PrimeCommitManager after the TOTP verify + relapse record. No
    // coins: the burned code + the relapse record is the price.
    // -----------------------------------------------------------------

    suspend fun finalizeForPrimeGiveUp(sessionId: String): StartResult = mutex.withLock {
        val session = stateRepo.blockingSession()
        if (session == null || session.id != sessionId || !session.status.isEnforcing) {
            return StartResult(false, ErrorCodes.SESSION_NOT_ACTIVE,
                "The commit's session is no longer active.")
        }
        finalizeLocked(session, SessionStatus.BAILOUT)
        AnalyticsOut.post("PRIME_GAVE_UP", mapOf("sessionId" to sessionId))
        return StartResult(true, null, null)
    }

    // -----------------------------------------------------------------
    // Cage (PRD §12–13) — driven by the shorts escalation path.
    // -----------------------------------------------------------------

    suspend fun activateCage() = mutex.withLock {
        val duration = runtimeConfig.current().cageDurationSeconds
        val now = SystemClockNow.elapsed
        stateRepo.saveCage(CageSnapshot(true, now, now + duration * 1000L))

        // Cage supersedes any active temporary unlock (PRD §17).
        tempUnlockManager.clear()

        scheduleAlarm(EnforcementReceiver.ACTION_CAGE_END, now + duration * 1000L)
        notifyCage(context, duration)
        violationManager.record(
            sessionId = stateRepo.blockingSession()?.id ?: "none",
            pkg = "",
            type = ViolationType.SESSION_TAMPER,
            severity = "HIGH",
            warningNumber = 0,
            action = "cage_started",
        )
        Broadcaster.emit()
        AnalyticsOut.post("CAGE_ACTIVATED", mapOf("durationSeconds" to duration))
    }

    suspend fun releaseCageIfExpired(): Boolean = mutex.withLock {
        val cage = stateRepo.blockingCage()
        if (!cage.active) return false
        if (!cage.isExpired(SystemClockNow.elapsed)) return false
        stateRepo.saveCage(CageSnapshot.INACTIVE)
        cancelAlarm(EnforcementReceiver.ACTION_CAGE_END)
        Broadcaster.emit()
        AnalyticsOut.post("CAGE_RELEASED", emptyMap())
        // v2.1 Phase C: the user served the full cage — that is discipline.
        try {
            com.maxleveldetox.MldApp.get(context).progressEngine.onCageSurvived()
        } catch (_: Exception) {
        }
        return true
    }

    // -----------------------------------------------------------------
    // Recovery — process start, boot, update (TRD §35, §76, §137)
    // -----------------------------------------------------------------

    /**
     * Restore enforcement after process death / reboot / app update.
     * A session that expired while the device was off completes LEGITIMATELY
     * (endElapsed passed) — it is never falsely marked interrupted.
     */
    suspend fun recoverIfNeeded() {
        val session = stateRepo.blockingSession()
        if (session != null && session.status == SessionStatus.PAUSED) {
            if (SystemClockNow.elapsed >= session.pauseEndElapsed) {
                evaluateAndMaybeComplete()   // auto-resume
            } else {
                EnforcementService.start(context)
                scheduleAlarm(EnforcementReceiver.ACTION_SESSION_END, session.pauseEndElapsed)
            }
        } else if (session != null && session.status.isEnforcing) {
            if (session.isExpired(SystemClockNow.elapsed)) {
                evaluateAndMaybeComplete()
            } else {
                EnforcementService.start(context)
                scheduleEndAlarm(session.endElapsed)
            }
        }

        // -----------------------------------------------------------------
        // v2.9.4 r20 — LEGACY PERSISTED-CAGE CLEANUP. The only production
        // cage is the 60 s IN-MEMORY burst cage (EnforcementWall). Nothing
        // persists a cage any more, but DataStore survives app updates and
        // elapsedRealtime resets on reboot — a leftover 30-minute cage
        // (written by pre-r18 in-session shorts attempts) or one warped by
        // a reboot could "reassert the cage" over every app switch for
        // hours or days after the user had long left shorts. Clear it.
        // -----------------------------------------------------------------
        val cage = stateRepo.blockingCage()
        if (cage.active) {
            stateRepo.saveCage(CageSnapshot.INACTIVE)
            cancelAlarm(EnforcementReceiver.ACTION_CAGE_END)
        }

        Broadcaster.emit()
        // v2.9 r17: re-sync the session kiosk after recovery (a session
        // that survived process death must re-arm its wall/strips).
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)
    }

    // -----------------------------------------------------------------
    // Finalization (called with mutex held)
    // -----------------------------------------------------------------

    private suspend fun finalizeLocked(session: SessionSnapshot, finalStatus: SessionStatus) {
        val now = SystemClockNow.elapsed
        val nowWall = System.currentTimeMillis()
        val tempUnlockUsed = stateRepo.blockingTempUnlock().active

        // Focus time excludes study breaks (already taken + one still running).
        val openBreakSeconds =
            if (session.status == SessionStatus.PAUSED && session.pausedAtElapsed > 0L)
                ((now - session.pausedAtElapsed) / 1000L).toInt() else 0
        val activeSeconds = (((now - session.startElapsed) / 1000L).toInt() -
            session.pausedTotalSeconds - openBreakSeconds).coerceAtLeast(0)

        // Persist terminal status FIRST (TRD §96: don't mark complete before
        // enforcement removal succeeds — order: persist, then remove).
        // v2.5.5 audit fix M-8: capture the cage state BEFORE clearing it —
        // the history row's cageTriggered used to be read after
        // saveCage(INACTIVE) and was therefore always false, corrupting
        // the session history / insights data.
        val cageWasActive = try {
            stateRepo.blockingCage().active
        } catch (_: Exception) {
            false
        }
        stateRepo.saveSession(null)
        stateRepo.saveCage(CageSnapshot.INACTIVE)
        tempUnlockManager.clear()
        // v2.9.11 r27: the cage screen is functional and offers the same
        // End (bailout) as the session screen — ending the session must
        // also end the IN-MEMORY cage, or the a11y cage gate would keep
        // blocking every app for the rest of the burst window after the
        // user already paid to leave.
        try {
            com.maxleveldetox.overlay.EnforcementWall.clearCage()
        } catch (_: Exception) {
        }

        cancelAlarm(EnforcementReceiver.ACTION_SESSION_END)
        cancelAlarm(EnforcementReceiver.ACTION_CAGE_END)
        EnforcementService.stop(context)
        // v2.9 r17: disarm the session kiosk surfaces when the session ends.
        com.maxleveldetox.enforcement.KioskController.syncAsync(stateRepo)
        // v2.9.11 r27: disarm the a11y key filter right away — at idle the
        // input pipeline must never round-trip key events through us
        // (volume multi-press root fix; the watchdog loop backstops this).
        try {
            com.maxleveldetox.accessibility.DetoxAccessibilityService.syncKeyFilterSoon()
        } catch (_: Exception) {
        }

        // History row.
        val completed = finalStatus == SessionStatus.COMPLETED
        database.sessionDao().upsert(
            SessionEntity(
                id = session.id,
                mode = session.mode.name,
                status = finalStatus.name,
                startWall = session.createdAtWall,
                endWall = nowWall,
                durationMinutes = activeSeconds / 60,
                completed = completed,
                bailedOut = finalStatus == SessionStatus.BAILOUT,
                violations = session.violationCount,
                cageTriggered = cageWasActive,
                tempUnlockUsed = tempUnlockUsed,
            )
        )

        // Daily stats.
        val dateKey = dateKeyFor(session.createdAtWall)
        val seconds = activeSeconds
        if (session.mode == SessionMode.STUDY && session.subjectName.isNotBlank()) {
            try {
                StudySubjectStore.addSeconds(context, session.subjectName, seconds, nowWall)
            } catch (_: Exception) {
            }
        }
        database.dailyStatDao().bump(
            dateKey,
            if (session.mode == SessionMode.STUDY) "focusSeconds" else "detoxSeconds",
            seconds,
        )
        if (completed) {
            database.dailyStatDao().bump(dateKey, "sessionsCompleted", 1)
        } else if (finalStatus == SessionStatus.BAILOUT) {
            database.dailyStatDao().bump(dateKey, "sessionsBailed", 1)
        }

        if (completed) {
            notifyCompletion(context)
            AnalyticsOut.post("SESSION_COMPLETED",
                mapOf("mode" to session.mode.name, "durationSeconds" to seconds))
            // v2.1 Phase C: progress hooks (parallel layer — failures here
            // can never affect the finalized session state above).
            try {
                val progress = com.maxleveldetox.MldApp.get(context).progressEngine
                progress.onSessionCompleted(session.mode.name, activeSeconds / 60)
            } catch (_: Exception) {
            }
        } else if (finalStatus == SessionStatus.BAILOUT) {
            try {
                com.maxleveldetox.MldApp.get(context).progressEngine.onBailout()
            } catch (_: Exception) {
            }
        }

        Broadcaster.emit()
    }

    // -----------------------------------------------------------------
    // Alarm plumbing
    // -----------------------------------------------------------------

    private fun scheduleEndAlarm(endElapsed: Long) {
        scheduleAlarm(EnforcementReceiver.ACTION_SESSION_END, endElapsed)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleAlarm(action: String, elapsedTarget: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val triggerAt = System.currentTimeMillis() + (elapsedTarget - SystemClockNow.elapsed).coerceAtLeast(0)

        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, EnforcementReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // Graceful degradation — lazy evaluation still completes the
            // session on the next access; the alarm is just a promptness aid.
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)
        }
    }

    private fun cancelAlarm(action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, EnforcementReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }

    // -----------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------

    private fun createNotificationChannelsOnce() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ENFORCEMENT, "Enforcement", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Active session status" })
            nm.createNotificationChannel(
                NotificationChannel(CH_RECOVERY, "Recovery", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Permission and session recovery prompts" })
        }
    }

    private fun notifyCompletion(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannelsOnce()
        val n = NotificationCompat.Builder(context, CH_RECOVERY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_session_completed))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_COMPLETION, n)
    }

    private fun notifyCage(context: Context, durationSeconds: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannelsOnce()
        val n = NotificationCompat.Builder(context, CH_RECOVERY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_cage_active,
                formatSeconds(durationSeconds)))
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_CAGE, n)
        // Clear the cage notification when it ends.
        scope.launch {
            kotlinx.coroutines.delay(durationSeconds * 1000L)
            nm.cancel(NOTIF_CAGE)
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun countBlockedApps(session: SessionSnapshot): Int {
        return try {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val count = pm.queryIntentActivities(launcher, 0).size
            val systemCount = 6 // rough system-app estimate
            (count - session.allowedPackages.size - systemCount).coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    private fun generateSessionId(): String {
        // v2.5.5 audit fix m-8: UUID-backed suffix (java.util.Random was
        // inconsistent with the security posture elsewhere — display-only
        // id, but keep the discipline).
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val suffix = java.util.UUID.randomUUID().toString()
            .replace("-", "").take(6).uppercase(Locale.US)
        return "MLD-$date-$suffix"
    }

    companion object {
        const val CH_ENFORCEMENT = "mld_enforcement"
        const val CH_RECOVERY = "mld_recovery"
        const val NOTIF_COMPLETION = 2001
        const val NOTIF_CAGE = 2002

        fun formatSeconds(total: Int): String {
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

        fun dateKeyFor(wallMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wallMs))
    }
}

/**
 * Analytics outbound queue — native events that Flutter picks up and
 * forwards to the Worker (with the offline queue). No raw content, ever.
 */
object AnalyticsOut {
    private val queue = mutableListOf<Pair<String, Map<String, Any>>>()

    @Synchronized
    fun post(type: String, payload: Map<String, Any>) {
        queue.add(type to payload)
        if (queue.size > 200) queue.removeAt(0)
    }

    @Synchronized
    fun drain(): List<Pair<String, Map<String, Any>>> {
        val copy = queue.toList()
        queue.clear()
        return copy
    }
}
