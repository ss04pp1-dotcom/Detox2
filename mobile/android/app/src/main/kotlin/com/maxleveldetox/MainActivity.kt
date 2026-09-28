package com.maxleveldetox

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.maxleveldetox.bridge.NativeBridge
import com.maxleveldetox.enforcement.SessionEngine

/**
 * MAXLEVEL DETOX — Flutter host activity.
 *
 * The Flutter engine renders the EXPERIENCE; the Kotlin enforcement engine
 * (accessible via MldApp) owns all security decisions (TRD §1/§42).
 *
 * v2.5 r9: notification deeplink routing — Sinthia check-in notifications
 * open the app with `openRoute=companion`; the pending flag is stored in
 * the companion prefs and consumed by the splash route (the Flutter side
 * never needs to parse raw intents).
 */
class MainActivity : FlutterActivity() {

    // v2.5.5 audit fix M-6: track the bridge so it can be detached when
    // this activity instance is destroyed (recreation) — a NEW bridge is
    // created per configureFlutterEngine call.
    private var bridge: NativeBridge? = null

    // ------------------------------------------------------------------
    // v2.9.2 r18 (user-requested): while the SESSION KIOSK is armed the
    // session screen itself holds the user like the cage — the BACK key
    // AND the gesture-back are consumed right here (the manifest pins
    // enableOnBackInvokedCallback=false, so the classic path is the one
    // the OS uses on every API level). Evaluated at press time — always
    // fresh, no polling, no I/O.
    // ------------------------------------------------------------------
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val locked = try {
            com.maxleveldetox.overlay.SessionKiosk.isWallShowing() ||
                com.maxleveldetox.overlay.SessionKiosk.isStripShowing()
        } catch (_: Exception) {
            false
        }
        if (locked) return // consumed — kiosk armed: NOTHING happens
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        NativeBridge(this).also { bridge = it }.attach(
            flutterEngine.dartExecutor.binaryMessenger
        )
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        // v2.5.5 audit fix M-6: remove the Broadcaster listener + cancel the
        // bridge scope BEFORE a recreated activity attaches its own bridge —
        // otherwise every rotation/dark-mode change leaked one MainActivity.
        try {
            bridge?.detach()
        } catch (_: Exception) {
        }
        bridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        // v2.5.5 audit fix M-3: kiosk lifecycle wiring.
        com.maxleveldetox.enforcement.KioskController.onActivityResumed(this)
        // Returning from Android Settings is a recovery + permission-diff
        // opportunity (TRD §31).
        val app = application as MldApp
        app.permissionMonitor.checkAndReact()
        SessionEngine.Broadcaster.emit()
        routeIntentExtras(intent)
    }

    override fun onPause() {
        super.onPause()
        // v2.5.5 audit fix M-3: kiosk lifecycle wiring.
        com.maxleveldetox.enforcement.KioskController.onActivityPaused()
    }

    override fun onDestroy() {
        // v2.5.5 audit fix M-3: kiosk lifecycle wiring.
        com.maxleveldetox.enforcement.KioskController.onActivityDestroyed(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntentExtras(intent)
    }

    /** Map `openRoute` extras (Sinthia check-ins, guard notifications,
     *  widgets) onto pending-open flags consumed by the splash router. */
    private fun routeIntentExtras(intent: Intent?) {
        // v2.5.5 audit fix m-3: GuardNotifier used to send `route` while only
        // `openRoute` was consumed — normalize both keys so "Fix Now"
        // notifications actually land somewhere useful.
        val route = intent?.getStringExtra("openRoute")
            ?: intent?.getStringExtra("route") ?: return
        val prefs = getSharedPreferences("mld_companion", MODE_PRIVATE)
        when (route) {
            "companion" -> {
                prefs.edit().putBoolean("openCompanionPending", true).apply()
                SessionEngine.Broadcaster.emit()
            }
            "permissions" -> {
                prefs.edit().putBoolean("openPermissionsPending", true).apply()
                SessionEngine.Broadcaster.emit()
            }
        }
    }
}
