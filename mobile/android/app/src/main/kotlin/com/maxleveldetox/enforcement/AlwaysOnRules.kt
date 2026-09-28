package com.maxleveldetox.enforcement

import android.content.Context

/**
 * AlwaysOnRules (v2.5 r9.5) — "does the user have rules that must be
 * enforced WITHOUT a session?" (enabled blocking schedules, per-app daily
 * limits). Engine 2, the guard job and the accessibility hand-off use this so
 * that these rules keep working when the accessibility service is dead —
 * Social Sentry's engine 2 enforces schedules and limits, not just sessions.
 *
 * Cheap (small SharedPreferences reads) and never throws.
 */
object AlwaysOnRules {
    fun exist(context: Context): Boolean = try {
        ScheduleEngine.all(context).any { it.enabled } ||
            AppLimitEngine.all(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}
