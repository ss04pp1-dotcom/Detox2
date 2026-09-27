package com.maxleveldetox.accessibility

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DiagLog (v1.0.7 → v2.2.1) — a tiny in-memory ring buffer of enforcement
 * decisions AND native errors.
 *
 * WHY: the user's phone is our only real test device (vivo Funtouch, Android
 * 16). Without a trace of what the detector decided and why, remote debugging
 * of "it kicked me out of YouTube" / "nothing works" reports is pure
 * guesswork. Every enforcement action, every permission transition and every
 * caught native exception appends one line here; the Flutter System Health
 * screen renders the last entries so a screenshot from the user tells us
 * exactly which signal fired (or never fired).
 *
 * PRIVACY (TRD §115): entries carry package names and signal names only —
 * never node text, never content. Buffer is in-memory, capped, and lost on
 * process death by design.
 */
object DiagLog {

    private const val CAP = 120
    private const val ERROR_CAP = 30

    private val buffer = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // v2.5.7 (K-5): set by MldApp.onCreate so errors also land in the
    // persisted rolling log (CrashReporter.appendError). Null before init
    // and in unit tests — disk mirroring is strictly optional.
    @Volatile
    internal var diskMirror: ((String) -> Unit)? = null

    @Synchronized
    fun log(tag: String, detail: String) {
        val line = "[${fmt.format(Date())}] $tag $detail"
        buffer.addLast(line)
        while (buffer.size > CAP) buffer.removeFirst()
    }

    /** Native error capture — every swallowed exception lands here so the
     * System Health screen can show it instead of it vanishing silently.
     * v2.5.7 (K-5): also mirrored to the on-disk log so the trace survives
     * process death (was in-memory only). */
    @Synchronized
    fun logError(where: String, e: Throwable) {
        val line = "[${fmt.format(Date())}] ERROR $where ${e.javaClass.simpleName}: ${e.message}"
        errors.addLast(line)
        buffer.addLast(line)
        try { diskMirror?.invoke(line) } catch (_: Exception) { }
        while (errors.size > ERROR_CAP) errors.removeFirst()
        while (buffer.size > CAP) buffer.removeFirst()
    }

    @Synchronized
    fun logError(where: String, message: String) {
        val line = "[${fmt.format(Date())}] ERROR $where $message"
        errors.addLast(line)
        buffer.addLast(line)
        try { diskMirror?.invoke(line) } catch (_: Exception) { }
        while (errors.size > ERROR_CAP) errors.removeFirst()
        while (buffer.size > CAP) buffer.removeFirst()
    }

    @Synchronized
    fun drain(): List<String> = buffer.toList()

    @Synchronized
    fun drainErrors(): List<String> = errors.toList()
}
