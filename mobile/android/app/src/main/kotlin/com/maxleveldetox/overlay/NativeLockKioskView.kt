package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.enforcement.SessionSnapshot
import com.maxleveldetox.enforcement.SystemClockNow
import com.maxleveldetox.safety.EmergencyLockdown

/**
 * NativeLockKioskView — Phase 1 Native Enforcement Surface (1000% design match to Flutter).
 *
 * Implements:
 *  1. Header chip with mode indicator (STUDY LOCK / DETOX LOCK / CAGE)
 *  2. Subject badge ("Subject: Physics")
 *  3. Custom Circular Countdown Timer Ring (Canvas drawArc, live reverse tick)
 *  4. 3 Stat Tiles: [Blocked] [Warnings] [Coins]
 *  5. Allowed Study Apps row (with live system app icons and launch handlers)
 *  6. Rewarded Ad / Earn Coins action button
 *  7. Emergency Dialer button (always accessible)
 *  8. WindowManager TYPE_ACCESSIBILITY_OVERLAY (Layer 2032) impenetrable lock
 */
class NativeLockKioskView(
    private val service: AccessibilityService,
    private val mode: SessionMode,
    private val subjectName: String,
    private val totalDurationSeconds: Int,
    private val endElapsed: Long,
    private val allowedPackages: Set<String> = emptySet(),
    private val isCage: Boolean = false,
    private val onOpenApp: (() -> Unit)? = null,
    private val onLaunchAllowed: ((String) -> Unit)? = null,
    private val onWatchAd: (() -> Unit)? = null,
) : LinearLayout(service) {

    companion object {
        const val BG_COLOR = "#0B0F19"
        const val CARD_BG = "#131D31"
        const val CARD_BORDER = "#1E293B"
        const val TEXT_PRIMARY = "#F8FAFC"
        const val TEXT_MUTED = "#64748B"
        const val ACCENT_STUDY = "#6366F1"   // Indigo / Violet
        const val ACCENT_DETOX = "#A855F7"   // Purple
        const val ACCENT_CAGE = "#EF4444"    // Danger Red
        const val ACCENT_COIN = "#F59E0B"    // Amber
    }

    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val isStudy = mode == SessionMode.STUDY && !isCage
    private val accentColorHex = when {
        isCage -> ACCENT_CAGE
        isStudy -> ACCENT_STUDY
        else -> ACCENT_DETOX
    }
    private val accentColor = Color.parseColor(accentColorHex)

    private val timerRing: CircularTimerRingView
    private val blockedValueView: TextView
    private val warnedValueView: TextView
    private val coinsValueView: TextView

    private var blockedCount = 0
    private var warningCount = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Color.parseColor(BG_COLOR))
        setPadding(dp(20), dp(36), dp(20), dp(24))

        // 1. Header Pill (Mode Tag)
        val headerPill = LinearLayout(service).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(adjustAlpha(accentColor, 0.15f))
                setStroke(dp(1), accentColor)
            }
            val titleText = when {
                isCage -> "🔒 CAGE LOCK"
                isStudy -> "📚 STUDY LOCK"
                else -> "🛡️ DETOX LOCK"
            }
            addView(TextView(service).apply {
                text = titleText
                textSize = 13f
                setTextColor(accentColor)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            })
        }
        addView(headerPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 2. Subject Badge (if present)
        if (isStudy && subjectName.isNotBlank()) {
            addView(TextView(service).apply {
                text = "Subject: $subjectName"
                textSize = 14f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }

        // 3. Circular Timer Ring (Canvas View)
        val ringSize = dp(190)
        timerRing = CircularTimerRingView(service, accentColor).apply {
            layoutParams = LayoutParams(ringSize, ringSize).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
        }
        addView(timerRing)

        // Subtitle message
        addView(TextView(service).apply {
            text = if (isCage) {
                "Repeated violations detected.\nThe restriction is temporarily intensified."
            } else {
                "This session is enforcing total lockout.\nStay focused until the timer ends."
            }
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_MUTED))
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(16))
        })

        // 4. Stat Tiles Row: [Blocked] [Warned] [Coins]
        val statRow = LinearLayout(service).apply {
            orientation = HORIZONTAL
            weightSum = 3f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        blockedValueView = createStatTile(statRow, "BLOCKED", "0", Color.parseColor(TEXT_PRIMARY))
        warnedValueView = createStatTile(statRow, "WARNED", "0", Color.parseColor(TEXT_PRIMARY))
        val currentCoins = readCoinsSafe()
        coinsValueView = createStatTile(statRow, "COINS", "🪙 $currentCoins", Color.parseColor(ACCENT_COIN))
        addView(statRow)

        // 5. Allowed Study Apps (if Study Mode and non-empty)
        if (isStudy && allowedPackages.isNotEmpty()) {
            addView(TextView(service).apply {
                text = "ALLOWED STUDY APPS"
                textSize = 11f
                setTextColor(Color.parseColor(TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding(0, dp(4), 0, dp(6))
            })

            val appsScroll = HorizontalScrollView(service).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                val container = LinearLayout(service).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val pm = service.packageManager
                for (pkg in allowedPackages) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val icon = pm.getApplicationIcon(appInfo)
                        val label = pm.getApplicationLabel(appInfo).toString()

                        val card = LinearLayout(service).apply {
                            orientation = VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            background = createRoundedDrawable(CARD_BG, CARD_BORDER, 12)
                            val iv = ImageView(service).apply {
                                setImageDrawable(icon)
                                layoutParams = LayoutParams(dp(36), dp(36))
                            }
                            val tv = TextView(service).apply {
                                text = label
                                textSize = 11f
                                setTextColor(Color.parseColor(TEXT_PRIMARY))
                                maxLines = 1
                                gravity = Gravity.CENTER
                                setPadding(0, dp(4), 0, 0)
                            }
                            addView(iv)
                            addView(tv)
                            setOnClickListener {
                                onLaunchAllowed?.invoke(pkg)
                            }
                        }
                        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(8)
                        }
                        container.addView(card, params)
                    } catch (_: Exception) {}
                }
                addView(container)
            }
            val scrollParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
            addView(appsScroll, scrollParams)
        }

        // 6. Action / Rewarded Ad Button ("WATCH AD · +1 COIN")
        val adButton = Button(service).apply {
            text = "🎬 WATCH AD · +1 COIN"
            textSize = 12f
            setTextColor(Color.parseColor(ACCENT_COIN))
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable("#1F1B12", "#78350F", 10)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isAllCaps = false
            setOnClickListener {
                onWatchAd?.invoke()
            }
        }
        val adParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
            bottomMargin = dp(10)
        }
        addView(adButton, adParams)

        // Spacer
        addView(View(service).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // 7. Emergency Call Button (Always accessible)
        val emergencyBtn = Button(service).apply {
            text = "🚨 EMERGENCY CALL"
            textSize = 13f
            setTextColor(Color.parseColor(ACCENT_CAGE))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            background = createRoundedDrawable("#1C1012", "#7F1D1D", 10)
            isAllCaps = false
            setOnClickListener {
                EmergencyLockdown.start(service)
                EmergencyLockdown.openDialer(service)
            }
        }
        val emParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(8)
        }
        addView(emergencyBtn, emParams)

        // Apply Immersive System UI flags
        @Suppress("DEPRECATION")
        systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun createStatTile(
        parent: LinearLayout,
        label: String,
        initialValue: String,
        valueColor: Int
    ): TextView {
        val tile = LinearLayout(service).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = createRoundedDrawable(CARD_BG, CARD_BORDER, 12)
        }
        val valView = TextView(service).apply {
            text = initialValue
            textSize = 15f
            setTextColor(valueColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val lblView = TextView(service).apply {
            text = label
            textSize = 10f
            setTextColor(Color.parseColor(TEXT_MUTED))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        tile.addView(valView)
        tile.addView(lblView)

        val params = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        parent.addView(tile, params)
        return valView
    }

    private fun createRoundedDrawable(bgColor: String, borderColor: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgColor))
            setStroke(dp(1), Color.parseColor(borderColor))
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    private fun readCoinsSafe(): Int {
        return try {
            MldApp.instance.stateRepo.blockingCoinBalance()
        } catch (_: Exception) {
            0
        }
    }

    fun updateTick(remainingSeconds: Int, totalSeconds: Int) {
        val safeTotal = totalSeconds.coerceAtLeast(1)
        val progress = 1f - (remainingSeconds.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)

        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        val timeStr = if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }

        timerRing.update(progress, timeStr)
        coinsValueView.text = "🪙 ${readCoinsSafe()}"
    }

    fun recordViolation() {
        blockedCount++
        blockedValueView.text = "$blockedCount"
    }

    fun recordWarning() {
        warningCount++
        warnedValueView.text = "$warningCount"
    }

    /**
     * CircularTimerRingView — Canvas-based circular countdown progress ring.
     */
    class CircularTimerRingView(context: Context, private val ringColor: Int) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val strokeWidth = 10f * density

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = this@CircularTimerRingView.strokeWidth
            color = Color.parseColor("#1E293B")
        }

        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = this@CircularTimerRingView.strokeWidth
            color = ringColor
            strokeCap = Paint.Cap.ROUND
        }

        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f * density
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 10f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }

        private val oval = RectF()
        private var progress = 0f
        private var timeText = "00:00:00"

        fun update(progress: Float, timeText: String) {
            this.progress = progress
            this.timeText = timeText
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = strokeWidth / 2f + 4f * density
            oval.set(pad, pad, width - pad, height - pad)

            val cx = width / 2f
            val cy = height / 2f
            val r = (Math.min(width, height) - strokeWidth) / 2f

            // 1. Background ring
            canvas.drawCircle(cx, cy, r, trackPaint)

            // 2. Progress arc (smooth rounded cap)
            if (progress > 0.001f) {
                canvas.drawArc(oval, -90f, progress * 360f, false, progressPaint)
            }

            // 3. Digital Time in Center
            val timeY = cy + (timePaint.textSize / 3.2f)
            canvas.drawText(timeText, cx, timeY, timePaint)

            // 4. "TIME REMAINING" subtitle
            val labelY = timeY + (16f * density)
            canvas.drawText("REMAINING TIME", cx, labelY, labelPaint)
        }
    }
}
