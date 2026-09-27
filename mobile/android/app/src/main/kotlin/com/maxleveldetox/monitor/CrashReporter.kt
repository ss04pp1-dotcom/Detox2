package com.maxleveldetox.monitor

import android.content.Context
import com.maxleveldetox.accessibility.DiagLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReporter (v2.5.7 / K-5) — a dependency-free crash + error drain.
 *
 * The whole Kotlin tree swallows hundreds of `catch (_: Exception) {}`
 * blocks and DiagLog is in-memory only — when the process died, every
 * trace of what went wrong died with it, so field debugging was pure
 * guesswork unless the user happened to screenshot System Health first.
 *
 * What this adds (all local, no third-party SDK, privacy-preserving —
 * stack traces contain class names only, never user content):
 *  - An UNCAUGHT exception hook: the last crash's stack is written to
 *    files/crash_last.txt and reported through DiagLog on next start.
 *  - A persisted rolling error file (files/native_errors.log, capped at
 *    64 KB) that DiagLog errors are mirrored into.
 *  - buildSystemReport (NativeBridge) reads both, so a System Health
 *    screenshot or a support ticket now carries the crash history.
 *
 * Trade-off note: a full Sentry/Crashlytics integration would give
 * server-side aggregation, but it adds a third-party dependency and
 * network egress that this offline-first, enforcement-first app does
 * not want by default. This reporter keeps everything on-device; the
 * support-ticket path (v2.5.7 H-2) is the transport.
 */
object CrashReporter {

    private const val CRASH_FILE = "crash_last.txt"
    private const val ERROR_LOG_FILE = "native_errors.log"
    private const val ERROR_LOG_MAX_BYTES = 64 * 1024

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        // (1) Uncaught exception hook — write then chain to the previous
        // handler (usually the system's, which kills the process).
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Exception) {
                // The crash handler itself must never throw.
            }
            previous?.uncaughtException(thread, throwable)
        }

        // (2) If the PREVIOUS run crashed, surface it in the diagnostics
        // buffer so System Health (and the state stream) picks it up.
        try {
            val crashFile = File(appContext.filesDir, CRASH_FILE)
            if (crashFile.exists()) {
                val head = crashFile.readText().lineSequence().take(6).joinToString("\n")
                if (head.isNotBlank()) {
                    DiagLog.logError("previousCrash", head)
                }
            }
        } catch (_: Exception) {
            // unreadable crash file — ignore
        }
    }

    /** Persist an uncaught exception (called on the crashing thread). */
    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val stack = throwable.stackTraceToString().lineSequence().take(24).joinToString("\n")
        val text = buildString {
            appendLine("time=${fmt.format(Date())}")
            appendLine("thread=${thread.name}")
            appendLine(stack)
        }
        File(context.filesDir, CRASH_FILE).writeText(text)
    }

    /**
     * Mirror a DiagLog error line into the rolling on-disk error log
     * (capped — the oldest half is dropped when the cap is exceeded).
     */
    fun appendError(context: Context, line: String) {
        try {
            val file = File(context.applicationContext.filesDir, ERROR_LOG_FILE)
            if (file.exists() && file.length() > ERROR_LOG_MAX_BYTES) {
                // Keep the newest half only.
                val keep = file.readText().let { it.substring(it.length / 2) }
                file.writeText(keep)
            }
            file.appendText(line + "\n")
        } catch (_: Exception) {
            // Disk errors must never crash the caller.
        }
    }

    /** The last recorded crash (for buildSystemReport); null when none. */
    fun lastCrash(context: Context): String? = try {
        val f = File(context.applicationContext.filesDir, CRASH_FILE)
        if (f.exists()) f.readText().take(4000) else null
    } catch (_: Exception) {
        null
    }

    /** The tail of the persisted native error log (newest 40 lines). */
    fun errorLogTail(context: Context): List<String> = try {
        val f = File(context.applicationContext.filesDir, ERROR_LOG_FILE)
        if (!f.exists()) emptyList()
        else f.readLines().takeLast(40)
    } catch (_: Exception) {
        emptyList()
    }
}
