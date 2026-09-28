package com.maxleveldetox.enforcement

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * ImmersiveBars (v2.3 r7) — hide the status + navigation bars on lock
 * surfaces.
 *
 * WHY: the lock screens are enforcement walls; visible system bars give
 * the user a swipe-down shade (settings quick tiles) and a swipe-up
 * navigation path — both escape hatches. Hiding the bars removes the
 * affordance entirely (ShadeGuard covers the edge swipe case).
 *
 * Transient reveal (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) is used so a
 * deliberate swipe shows the bars only momentarily on TOP of the lock
 * surface — the shade itself is collapsed by ShadeGuard while enforcing.
 */
object ImmersiveBars {

    fun apply(activity: Activity) {
        try {
            val window = activity.window ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                val controller = window.insetsController ?: return
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
        } catch (_: Exception) {
            // Cosmetic hardening — never let it break the lock surface.
        }
    }
}
