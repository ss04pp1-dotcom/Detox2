package com.maxleveldetox.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.storage.AlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Calendar
import kotlin.random.Random

/**
 * ShockwaveAlarmEngine (TRD §25–28, PRD §20–21) + AlarmReceiver +
 * AlarmActivity (the full-screen puzzle).
 *
 * The alarm UI is NATIVE: it fires and works even if Flutter is dead, the
 * app was swiped away, or the screen is off (TRD §26). The alarm stops ONLY
 * on a correct answer — never on app switches or screen changes.
 */
class ShockwaveAlarmEngine(
    private val context: Context,
    private val database: com.maxleveldetox.storage.MldDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun upsert(alarm: AlarmEntity) {
        database.alarmDao().upsert(alarm)
        scheduleNext()
    }

    suspend fun delete(alarmId: String) {
        database.alarmDao().delete(alarmId)
        cancelPending(alarmId)
        scheduleNext()
    }

    /** Re-arm everything after boot / update. */
    suspend fun rescheduleAll() = scheduleNext()

    private fun cancelPending(alarmId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(alarmId)
        am.cancel(pi)
    }

    private fun pendingIntent(alarmId: String): PendingIntent = PendingIntent.getBroadcast(
        context, alarmId.hashCode(),
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Schedule the next occurrence of every enabled alarm. */
    private suspend fun scheduleNext() {
        val alarms = database.alarmDao().all().filter { it.enabled }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        for (alarm in alarms) {
            val triggerAt = nextTriggerWallMs(alarm) ?: continue
            cancelPending(alarm.id)
            val pi = pendingIntent(alarm.id)
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)
            }
        }
    }

    private fun nextTriggerWallMs(alarm: AlarmEntity): Long? {
        val days = parseDays(alarm.repeatDays)
        val now = Calendar.getInstance()

        for (offset in 0..8) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= System.currentTimeMillis()) continue
            // v2.5.5 audit fix C-2: repeatDays arrive from Flutter as ISO
            // numbers (1=Mon..7=Sun) but Calendar.DAY_OF_WEEK is
            // SUNDAY=1..SATURDAY=7 — comparing them directly shifted every
            // weekday selection one day early ("Mon–Fri" fired Sun–Thu).
            val isoDow = ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Mon=1..Sun=7
            if (days.isEmpty() || days.contains(isoDow)) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    companion object {
        fun parseDays(json: String): List<Int> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optInt(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val app = context.applicationContext as? MldApp ?: return

        // Post a full-screen-intent notification (screen-off path) and
        // launch the puzzle activity directly when possible.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "Shockwave Alarm", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Full-screen alarm with puzzle" })
        }

        val fullScreen = PendingIntent.getActivity(
            context, alarmId.hashCode(),
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP")
            .setContentText("Solve the puzzle to stop the alarm.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .apply {
                // v2.5.5 audit fix m-20: Android 14+ can revoke
                // USE_FULL_SCREEN_INTENT for non-alarm apps; only attach the
                // full-screen intent when actually permitted (otherwise the
                // high-importance alarm-channel heads-up is the surface).
                val fsiAllowed = Build.VERSION.SDK_INT < 34 ||
                    nm.canUseFullScreenIntent()
                if (fsiAllowed) setFullScreenIntent(fullScreen, true)
            }
            .setOngoing(true)
            .build()
        nm.notify(alarmId.hashCode(), notification)

        // Direct launch for the screen-on path.
        try {
            context.startActivity(
                Intent(context, AlarmActivity::class.java)
                    .putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (_: Exception) { /* the full-screen intent is the fallback */ }

        // Reschedule the next occurrence.
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // Engine re-arms via MldApp access
                (context.applicationContext as MldApp).alarmEngine.rescheduleAll()
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.maxleveldetox.action.ALARM_FIRE"
        const val EXTRA_ALARM_ID = "alarmId"
        const val CHANNEL_ALARM = "mld_alarm"
    }
}

/**
 * The full-screen puzzle (UI/UX §34–35). Loud (alarm stream, looping),
 * persistent, and dismissible ONLY by a correct answer.
 */
class AlarmActivity : android.app.Activity() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var answer = 0
    private var attempts = 0

    // v2.5.5 audit fix M-7: difficulty is loaded OFF the main thread and
    // applied when it differs from the default (the puzzle re-renders).
    private var difficulty = "MEDIUM"
    private var questionView: TextView? = null
    private var alarmScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID) ?: ""

        setContentView(buildUi(difficulty))
        startAlarmSound()
        com.maxleveldetox.enforcement.AnalyticsOut.post("ALARM_TRIGGERED", emptyMap())

        // Load the saved difficulty off the main thread (was runBlocking
        // Room on the UI thread — ANR exposure on low-end devices).
        alarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch {
                val entity = try {
                    (application as? MldApp)?.database?.alarmDao()?.byId(alarmId)
                } catch (_: Exception) {
                    null
                }
                val diff = entity?.difficulty ?: "MEDIUM"
                if (diff != difficulty) {
                    handler.post { if (diff != difficulty) applyDifficulty(diff) }
                }
            }
        }
    }

    private fun applyDifficulty(diff: String) {
        difficulty = diff
        val (question, correct) = makePuzzle(diff)
        answer = correct
        questionView?.text = "$question = ?"
    }

    // -----------------------------------------------------------------
    // Puzzle
    // -----------------------------------------------------------------

    private fun makePuzzle(difficulty: String): Pair<String, Int> {
        return when (difficulty) {
            "EASY" -> {
                val a = Random.nextInt(3, 15)
                val b = Random.nextInt(3, 15)
                "$a + $b" to (a + b)
            }
            "HARD" -> {
                val a = Random.nextInt(6, 19)
                val b = Random.nextInt(3, 12)
                val c = Random.nextInt(10, 60)
                "$a × $b + $c" to (a * b + c)
            }
            else -> { // MEDIUM
                val a = Random.nextInt(3, 12)
                val b = Random.nextInt(3, 9)
                "$a × $b" to (a * b)
            }
        }
    }

    // -----------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------

    private fun buildUi(difficulty: String): View {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val (question, correct) = makePuzzle(difficulty)
        answer = correct
        val root = LinearLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#120606"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        fun title(text: String, size: Float, color: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val questionView = TextView(this).apply {
            textSize = 40f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        questionView.text = "$question = ?"
        this.questionView = questionView

        val input = EditText(this).apply {
            hint = "?"
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            background.alpha = 60
        }

        val feedback = TextView(this).apply {
            text = " "
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
        }

        val submit = Button(this).apply {
            text = "SUBMIT"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            isAllCaps = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(16).toFloat()
                setColor(android.graphics.Color.parseColor("#EF4444"))
            }
            setOnClickListener {
                val given = input.text.toString().trim().toIntOrNull()
                attempts++
                if (given == answer) {
                    stopEverything()
                } else {
                    feedback.text = "INCORRECT — try again."
                    input.text.clear()
                    vibrateFeedback()
                }
            }
        }

        root.addView(title("WAKE UP", 34f, android.graphics.Color.parseColor("#EF4444")))
        root.addView(title(timeNow(), 26f, android.graphics.Color.WHITE))
        root.addView(questionView.apply { setPadding(0, pad * 2, 0, pad) })
        root.addView(input.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pad(60))
        })
        root.addView(feedback)
        root.addView(submit.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pad(54)).apply {
                topMargin = pad(16)
            }
        })

        return root
    }

    private fun pad(dp: Int): Int = (resources.displayMetrics.density * dp).toInt()

    private fun vibrateFeedback() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (_: Exception) { /* vibration is best-effort */ }
    }

    private fun timeNow(): String {
        val cal = Calendar.getInstance()
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    // -----------------------------------------------------------------
    // Audio — alarm stream, looping, max-volume request within OEM limits
    // (TRD §27: we do NOT claim to override hardware volume).
    // -----------------------------------------------------------------

    private fun startAlarmSound() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (_: Exception) { /* silent mode / DND: the puzzle still gates dismissal */ }
    }

    private fun stopEverything() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) { }
        player = null

        com.maxleveldetox.enforcement.AnalyticsOut.post("ALARM_COMPLETED", mapOf("attempts" to attempts))

        // v2.1 Phase C: puzzle solved, day started (daily stat + DP).
        try {
            (applicationContext as? com.maxleveldetox.MldApp)
                ?.progressEngine?.onAlarmCompleted()
        } catch (_: Exception) {
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(intent?.getStringExtra(EXTRA_ALARM_ID)?.hashCode() ?: 0)

        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        alarmScope?.cancel()
        try {
            player?.release()
        } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBackPressed() {
        // The alarm does not stop because the user pressed back (TRD §26).
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarmId"
    }
}
