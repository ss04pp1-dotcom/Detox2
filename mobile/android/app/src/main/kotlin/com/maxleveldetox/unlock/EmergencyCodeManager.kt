package com.maxleveldetox.unlock

import android.content.Context
import com.maxleveldetox.storage.StateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * EmergencyCodeManager (v2.0 Phase B5) — per-user TOTP emergency codes.
 *
 * THE LESSON WE REFUSED TO LEARN THE HARD WAY: the reference app ships a
 * hardcoded global master secret inside every APK — a backdoor that
 * unlocks ANY installation worldwide once extracted via decompilation
 * (we extracted it; so can anyone). Our app will NEVER contain a shared
 * master code, in any form, at any strength.
 *
 * OUR DESIGN:
 *   - A per-installation Base32 secret (SecureRandom, 16 chars), created
 *     on first enrollment and shown to the user exactly once so they can
 *     add it to any authenticator app (Google Authenticator, Aegis, …).
 *   - Validation is RFC-6238 TOTP: 6 digits, HmacSHA1, 300-second step,
 *     ±1 step tolerance (±5 min clock skew).
 *   - Replay guard: any accepted code is burned for 20 minutes (the same
 *     code cannot be reused inside the window) — ported from the
 *     reference app's usedEmergencyCodes design.
 *   - Gates that use it (Prime Mode give-up) combine TOTP + replay +
 *     relapse logging. There is no fallback path, no master code, no
 *     debug variant. (Server-side validation arrives with the account
 *     system; the local secret will then be synced as an enrolled factor.)
 *
 * The secret lives in our DataStore. It is readable by this app only —
 * the same trust domain as every other enforcement state.
 */
class EmergencyCodeManager(
    private val context: Context,
    private val stateRepo: StateRepository,
) {

    private val mutex = Mutex()

    // -----------------------------------------------------------------
    // Enrollment
    // -----------------------------------------------------------------

    data class Enrollment(val secret: String, val otpauthUrl: String, val isNew: Boolean)

    /**
     * Returns the enrollment payload, generating the secret on first
     * call. The Flutter side shows the QR + manual key ONCE during setup.
     */
    suspend fun enroll(): Enrollment = mutex.withLock {
        val existing = stateRepo.blockingEmergencySecret()
        if (existing != null) return Enrollment(existing, otpauthUrl(existing), false)
        val secret = generateSecret()
        stateRepo.saveEmergencySecret(secret)
        return Enrollment(secret, otpauthUrl(secret), true)
    }

    fun isEnrolled(): Boolean = stateRepo.blockingEmergencySecret() != null

    // -----------------------------------------------------------------
    // Validation (RFC-6238 TOTP, 6 digits, 300s step, ±1 step)
    // -----------------------------------------------------------------

    data class VerifyResult(val ok: Boolean, val errorCode: String?, val message: String?) {
        companion object {
            val BAD = VerifyResult(false, "INVALID_CODE", "That code is not valid right now.")
            val REPLAY = VerifyResult(false, "CODE_REUSED",
                "That code was just used. Wait 20 minutes or generate a new one.")
            val NOT_ENROLLED = VerifyResult(false, "NOT_ENROLLED",
                "No emergency secret is enrolled on this device.")
        }
    }

    /**
     * Verify a 6-digit code against the enrolled secret and burn it on
     * success. [purpose] namespaces the replay ledger (e.g. "prime").
     */
    suspend fun verify(code: String, purpose: String): VerifyResult = mutex.withLock {
        val secret = stateRepo.blockingEmergencySecret()
            ?: return VerifyResult.NOT_ENROLLED

        val cleaned = code.trim()
        if (cleaned.length != 6 || cleaned.any { !it.isDigit() }) return VerifyResult.BAD

        val now = System.currentTimeMillis()
        if (!totpMatches(secret, now, cleaned)) return VerifyResult.BAD

        // Replay guard — the same code is burned for 20 minutes.
        val used = readUsedMap()
        val key = "$purpose:$cleaned"
        val lastUsed = used[key]
        if (lastUsed != null && now - lastUsed < REPLAY_WINDOW_MS) {
            return VerifyResult.REPLAY
        }

        used[key] = now
        pruneUsed(used, now)
        stateRepo.saveEmergencyUsed(used)
        return VerifyResult(true, null, null)
    }

    // -----------------------------------------------------------------
    // TOTP core
    // -----------------------------------------------------------------

    private fun totpMatches(secretBase32: String, nowMs: Long, code: String): Boolean {
        val key = try {
            base32Decode(secretBase32)
        } catch (_: Exception) {
            return false
        } ?: return false

        val counter = nowMs / 1000L / STEP_SECONDS
        for (step in longArrayOf(counter - 1, counter, counter + 1)) {
            if (generateCode(key, step) == code) return true
        }
        return false
    }

    private fun generateCode(key: ByteArray, counter: Long): String {
        val msg = ByteBufferEight(counter)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(msg)
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val otp = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return String.format(java.util.Locale.US, "%06d", otp % 1_000_000)
    }

    private fun ByteBufferEight(value: Long): ByteArray = byteArrayOf(
        (value ushr 56).toByte(),
        (value ushr 48).toByte(),
        (value ushr 40).toByte(),
        (value ushr 32).toByte(),
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    // -----------------------------------------------------------------
    // Base32 (RFC 4648) — encode/decode without external dependencies
    // -----------------------------------------------------------------

    private fun generateSecret(): String {
        val rng = SecureRandom()
        val sb = StringBuilder(SECRET_LENGTH)
        repeat(SECRET_LENGTH) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private fun base32Decode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it in ALPHABET }
        // 16 chars -> 80 bits -> 10 bytes (no padding needed).
        if (clean.length != SECRET_LENGTH) return null
        var bits = 0L
        var bitCount = 0
        val out = mutableListOf<Byte>()
        for (c in clean) {
            bits = (bits shl 5) or ALPHABET.indexOf(c).toLong()
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                out.add(((bits shr bitCount) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun otpauthUrl(secret: String): String =
        "otpauth://totp/${java.net.URLEncoder.encode("MAXLEVEL DETOX", "UTF-8")}" +
            "?secret=$secret&issuer=MAXLEVEL%20DETOX" +
            "&algorithm=SHA1&digits=6&period=${STEP_SECONDS}"

    // -----------------------------------------------------------------
    // Replay ledger (JSON map in DataStore, pruned on every write)
    // -----------------------------------------------------------------

    private fun readUsedMap(): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        val raw = stateRepo.blockingEmergencyUsed() ?: return out
        return try {
            val json = JSONObject(raw)
            json.keys().forEach { k -> out[k] = json.optLong(k, 0L) }
            out
        } catch (_: Exception) {
            out
        }
    }

    private fun pruneUsed(used: MutableMap<String, Long>, now: Long) {
        val iterator = used.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > REPLAY_WINDOW_MS * 2) iterator.remove()
        }
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val SECRET_LENGTH = 16
        const val STEP_SECONDS = 300L
        const val REPLAY_WINDOW_MS = 20 * 60_000L
    }
}
