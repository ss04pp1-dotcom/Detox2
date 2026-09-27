package com.maxleveldetox.safety

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
import com.maxleveldetox.accessibility.DiagLog
import org.json.JSONObject

/**
 * EmergencyLockdown (v2.7 r13) — user-requested emergency semantics.
 *
 * OLD behavior: the Emergency button opened the dialer and effectively
 * stood the whole enforcement system down around it — from the dialer the
 * user could wander anywhere. NEW behavior: emergency = the phone becomes
 * a DIALER AND NOTHING ELSE.
 *
 *   - Pressing Emergency (session screen, monk overlay, lock screen) opens
 *     the dialer AND enters lockdown.
 *   - While the lockdown is live, every app that is not the dialer family,
 *     our own app, or core system surfaces is bounced straight back to the
 *     dialer (enforced by the accessibility service foreground pipeline and
 *     the monk-mode tick).
 *   - Our own app stays reachable so the user can deliberately END the
 *     emergency from the banner.
 *   - Safety cap: 15 minutes. A forgotten lockdown never bricks the phone;
 *     elapsedRealtime is reboot-safe (a reboot simply expires it).
 *
 * PRIVACY/TRUST: state is a local SharedPreferences flag — same trust
 * domain as every other enforcement surface. No cloud, no codes.
 */
object EmergencyLockdown {

    private const val PREFS = "mld_emergency_lockdown"
    private const val KEY_ACTIVE = "active"
    private const val KEY_START = "start_elapsed"

    /** The deliberate end-vs-expiry cap (user feedback: never trapped). */
    const val MAX_DURATION_MS = 15 * 60_000L

    // -----------------------------------------------------------------
    // State
    // -----------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_START, SystemClock.elapsedRealtime())
            .apply()
        // v2.9 r17: the lockdown owns the screen from this instant — drop
        // any SESSION KIOSK surfaces (wall/strips) immediately so the
        // dialer is visible without waiting for the next a11y event.
        // (One of the r16 "emergency exits, system fails" roots: while the
        // old screen-pinning held MainActivity, the dialer launch itself
        // was OS-blocked. Pinning is gone in r17; this hide closes the
        // visual race the other way around.)
        try {
            com.maxleveldetox.overlay.SessionKiosk.hideAll(context)
        } catch (_: Exception) {
        }
        DiagLog.log("EMERGENCY_LOCKDOWN", "started")
    }

    fun end(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
        // v2.9 r17: ending the emergency re-arms the session kiosk right
        // away (wall over the launcher / strips over our own app) — the
        // r16 hole was that nothing re-asserted enforcement after the
        // lockdown ended, so the user could wander anywhere.
        try {
            com.maxleveldetox.overlay.SessionKiosk.sync(context)
        } catch (_: Exception) {
        }
        DiagLog.log("EMERGENCY_LOCKDOWN", "ended")
    }

    /** True while the lockdown is live (expiry-checked on every read). */
    fun isActive(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        val start = p.getLong(KEY_START, 0L)
        val now = SystemClock.elapsedRealtime()
        // Reboot (now < start) or cap exceeded -> auto-expire.
        if (now < start || now - start > MAX_DURATION_MS) {
            end(context)
            return false
        }
        return true
    }

    fun remainingSeconds(context: Context): Int {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return 0
        val start = p.getLong(KEY_START, 0L)
        val now = SystemClock.elapsedRealtime()
        val remain = MAX_DURATION_MS - (now - start)
        return ((remain / 1000L).coerceIn(0L, MAX_DURATION_MS / 1000L)).toInt()
    }

    fun statusJson(context: Context): JSONObject = JSONObject().apply {
        put("active", isActive(context))
        put("remainingSeconds", remainingSeconds(context))
    }

    // -----------------------------------------------------------------
    // Dialer family
    // -----------------------------------------------------------------

    /**
     * The deliberate allowlist while locked down: dialer + in-call UI +
     * contacts (a contact card is one tap from a call) + the system
     * surfaces a call physically needs. Deliberately narrow — no generic
     * "phone"/"browser" substrings (mirrors LockMyPhoneService.isCallSurface).
     */
    fun isDialer(context: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val p = pkg.lowercase()
        if (p == "android" ||
            p == "com.android.systemui" ||
            p == "com.android.phone" ||
            p == "com.android.server.telecom" ||
            p.contains("dialer") ||
            p.contains("incallui") ||
            p.contains("callui") ||
            p.contains("telecom") ||
            p.contains("contacts")
        ) return true
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.defaultDialerPackage == pkg || tm.systemDialerPackage == pkg
        } catch (_: Exception) {
            false
        }
    }

    /** What may be on screen during the lockdown: dialer family + us. */
    fun isAllowed(context: Context, pkg: String): Boolean =
        pkg == context.packageName || isDialer(context, pkg)

    // -----------------------------------------------------------------
    // The one action every emergency surface performs
    // -----------------------------------------------------------------

    fun openDialer(context: Context) {
        try {
            // Explicit dial intent against the DEFAULT dialer — more
            // reliable cross-OEM than a bare ACTION_DIAL resolve.
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val dialer = tm.defaultDialerPackage
            val intent = if (dialer != null) {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).apply {
                    setPackage(dialer)
                }
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** ComponentName convenience for callers that show a "call in progress"
     *  chip against the dialer package. */
    fun dialerComponent(context: Context): ComponentName? = try {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val pkg = tm.defaultDialerPackage ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        launch.component
    } catch (_: Exception) {
        null
    }
}
