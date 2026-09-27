package com.maxleveldetox.prime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maxleveldetox.MainActivity
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.ErrorCodes
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.enforcement.SessionStatus
import com.maxleveldetox.enforcement.Strictness
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.enforcement.ViolationType
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * PrimeCommitManager (v2.0 Phase B5) — the commitment mode.
 *
 * Ported from the reference app's Prime Mode (report-modes.md §1.3):
 * a hours-long MAXLEVEL detox commitment that hardens the escape routes:
 *
 *   WHILE A COMMIT IS ACTIVE:
 *     - temporary unlock is REFUSED (TempUnlockManager checks us)
 *     - coin bailout is REFUSED (SessionEngine checks us)
 *     - the only exit is the commit's own end time, or
 *     - emergencyGiveUp(code): per-user TOTP + 20-min replay guard,
 *       which ALWAYS records a relapse violation (PRIME_GIVEUP, HIGH)
 *       — the relapse is a first-class fact, not a silent escape
 *
 *   WHAT IT IS NOT: it is NOT a paywall flag (the reference app confuses
 *   isFeatureLocked with prime-commitment). Ours is purely a commitment
 *   contract. It is also NOT a shared-secret backdoor — the give-up
 *   gate is the same per-user TOTP as everything else.
 *
 * Implementation: a commit OWNS a DETOX session (MAXLEVEL strictness,
 * empty allowlist, distracting categories blocked) started through the
 * normal SessionEngine path, plus a small PrimeState record that marks
 * the session as prime-owned. Snapshot/restore is trivial because the
 * session itself is the snapshot — on give-up or expiry the session
 * finalizes through the normal session engine and the prime state
 * clears.
 */
class PrimeCommitManager(
    private val context: Context,
    private val stateRepo: StateRepository,
) {

    private val mutex = Mutex()

    data class PrimeState(
        val active: Boolean = false,
        val title: String = "",
        val sessionId: String = "",
        val startWallMs: Long = 0L,
        val endWallMs: Long = 0L,
        val gaveUp: Boolean = false,
    )

    data class Result(val ok: Boolean, val errorCode: String?, val message: String?)

    // -----------------------------------------------------------------
    // Activation
    // -----------------------------------------------------------------

    suspend fun activate(title: String, commitHours: Int): Result = mutex.withLock {
        val app = context.applicationContext as? MldApp
            ?: return Result(false, ErrorCodes.UNKNOWN, "Application context missing.")

        if (commitHours < 1 || commitHours > 24) {
            return Result(false, ErrorCodes.INVALID_REQUEST,
                "commitHours out of range (1..24).")
        }
        if (!app.stateRepo.blockingPactAccepted()) {
            return Result(false, ErrorCodes.PERMISSION_REQUIRED,
                "The Commitment Pact must be accepted first.")
        }
        if (!app.emergencyCodes.isEnrolled()) {
            return Result(false, ErrorCodes.PERMISSION_REQUIRED,
                "Enroll your emergency authenticator first — a Prime commit " +
                    "must always have a real escape valve.")
        }

        val existing = stateRepo.blockingPrime()
        if (existing.active && !isExpired(existing)) {
            return Result(false, "PRIME_ACTIVE", "A Prime commit is already running.")
        }

        val session = app.stateRepo.blockingSession()
        if (session != null && session.status.isEnforcing) {
            return Result(false, "SESSION_ACTIVE",
                "End the active session before starting a Prime commit.")
        }

        // Start the underlying MAXLEVEL detox session (1..24h).
        val minutes = commitHours * 60
        val start = app.sessionEngine.startSession(
            mode = SessionMode.DETOX,
            durationMinutes = minutes,
            strictness = Strictness.MAXLEVEL,
            allowedPackages = emptyList(),
            blockedCategories = listOf("social", "shorts", "games", "entertainment"),
        )
        if (!start.ok) {
            return Result(false, start.errorCode ?: ErrorCodes.UNKNOWN, start.message)
        }
        val started = app.stateRepo.blockingSession()
            ?: return Result(false, ErrorCodes.UNKNOWN, "Session did not persist.")

        val now = System.currentTimeMillis()
        stateRepo.savePrime(PrimeState(
            active = true,
            title = title.take(120),
            sessionId = started.id,
            startWallMs = now,
            endWallMs = now + minutes * 60_000L,
        ))

        postCommitStartedNotification(title, commitHours)

        // v2.1 Phase C: first prime commit bonus.
        try {
            app.progressEngine.onFeatureActivated("prime")
        } catch (_: Exception) {
        }
        return Result(true, null, null)
    }

    // -----------------------------------------------------------------
    // Gates (consulted by TempUnlockManager + SessionEngine bailout)
    // -----------------------------------------------------------------

    fun isCommitActive(): Boolean {
        val state = stateRepo.blockingPrime()
        return state.active && !isExpired(state) && sessionStillEnforcing(state)
    }

    private fun sessionStillEnforcing(state: PrimeState): Boolean {
        if (state.sessionId.isEmpty()) return false
        val session = stateRepo.blockingSession() ?: return false
        return session.id == state.sessionId && session.status.isEnforcing
    }

    private fun isExpired(state: PrimeState): Boolean =
        System.currentTimeMillis() >= state.endWallMs

    // -----------------------------------------------------------------
    // Give-up — the ONLY mid-commit exit (TOTP + replay + relapse)
    // -----------------------------------------------------------------

    suspend fun giveUp(code: String): Result = mutex.withLock {
        val app = context.applicationContext as? MldApp
            ?: return Result(false, ErrorCodes.UNKNOWN, "Application context missing.")

        val state = stateRepo.blockingPrime()
        if (!state.active) {
            return Result(false, "PRIME_NOT_ACTIVE", "No Prime commit is running.")
        }

        val verify = app.emergencyCodes.verify(code, purpose = "prime")
        if (!verify.ok) {
            return Result(false, verify.errorCode, verify.message)
        }

        // The relapse is ALWAYS recorded — before the session ends, so a
        // crash mid-way cannot lose it.
        app.violationManager.record(
            sessionId = state.sessionId,
            pkg = "",
            type = ViolationType.PRIME_GIVEUP,
            severity = "HIGH",
            warningNumber = 0,
            action = "prime_giveup:${state.title}",
        )

        stateRepo.savePrime(state.copy(active = false, gaveUp = true))

        // End the owned session (prime give-up does not spend coins —
        // the TOTP burn + relapse record is the price).
        val session = app.stateRepo.blockingSession()
        if (session != null && session.id == state.sessionId && session.status.isEnforcing) {
            app.sessionEngine.finalizeForPrimeGiveUp(session.id)
        }

        // v2.1 Phase C: a deliberately broken commitment is an immediate
        // relapse (streak reset + relapse history row).
        try {
            app.progressEngine.onPrimeGiveUp(state.title)
        } catch (_: Exception) {
        }

        postGaveUpNotification(state.title)
        return Result(true, null, null)
    }

    // -----------------------------------------------------------------
    // Lazy reconciliation (called from EnforcementService sweep + bridge)
    // -----------------------------------------------------------------

    /** Clears a finished commit state once its session is gone/expired. */
    suspend fun reconcileIfNeeded(): Boolean {
        val state = stateRepo.blockingPrime()
        if (!state.active) return false
        if (!isExpired(state) && sessionStillEnforcing(state)) return false

        // Commit reached its end (or the session finished) — success!
        if (!state.gaveUp) {
            stateRepo.savePrime(state.copy(active = false))
            postCommitFinishedNotification(state.title)

            // v2.1 Phase C: kept a full commitment — the biggest DP award.
            try {
                (context.applicationContext as? com.maxleveldetox.MldApp)
                    ?.progressEngine?.onPrimeCompleted()
            } catch (_: Exception) {
            }
            return true
        }
        return false
    }

    // -----------------------------------------------------------------
    // Status projection
    // -----------------------------------------------------------------

    fun statusJson(): JSONObject {
        val state = stateRepo.blockingPrime()
        return JSONObject().apply {
            put("active", isCommitActive())
            put("title", state.title)
            put("remainingSeconds",
                ((state.endWallMs - System.currentTimeMillis()) / 1000L)
                    .coerceAtLeast(0L).toInt())
            put("endWallMs", state.endWallMs)
            put("gaveUp", state.gaveUp)
            put("lastSessionId", state.sessionId)
        }
    }

    // -----------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PRIME, context.getString(R.string.channel_prime),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_prime_desc) }
            )
        }
    }

    private fun postCommitStartedNotification(title: String, hours: Int) {
        try {
            ensureChannel()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(NOTIF_ID, NotificationCompat.Builder(context, CHANNEL_PRIME)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(context.getString(R.string.notif_prime_started_title))
                .setContentText(
                    context.getString(R.string.notif_prime_started_body, title, hours))
                .setContentIntent(open)
                .setOngoing(true)
                .build())
        } catch (_: Exception) {
        }
    }

    private fun postCommitFinishedNotification(title: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // v2.5.5 audit fix m-9: after a process restart (commit
            // finishing via sweep after process death) the mld_prime channel
            // may not exist — the notification was silently dropped on
            // API 26+. ensureChannel() first, like the started notification.
            ensureChannel()
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(NOTIF_ID, NotificationCompat.Builder(context, CHANNEL_PRIME)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notif_prime_done_title))
                .setContentText(context.getString(R.string.notif_prime_done_body, title))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {
        }
    }

    private fun postGaveUpNotification(title: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(context, CHANNEL_PRIME)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notif_prime_giveup_title))
                .setContentText(context.getString(R.string.notif_prime_giveup_body, title))
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_PRIME = "mld_prime"
        const val NOTIF_ID = 4301
    }
}
