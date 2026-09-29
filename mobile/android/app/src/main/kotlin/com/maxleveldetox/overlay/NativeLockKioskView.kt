package com.maxleveldetox.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.maxleveldetox.MldApp
import com.maxleveldetox.R
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.safety.EmergencyLockdown

/**
 * The visual surface used by SessionKiosk's TYPE_ACCESSIBILITY_OVERLAY wall.
 *
 * IMPORTANT: this is not a second lock mechanism. SessionKiosk remains the
 * enforcement authority. This class is deliberately just the beautiful,
 * user-facing Study/Detox surface sitting on top of that hard wall.
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
        private const val BG = "#04101F"
        private const val SURFACE = "#081B32"
        private const val SURFACE_2 = "#0C2745"
        private const val EDGE = "#174E79"
        private const val TEXT = "#F5FAFF"
        private const val MUTED = "#A6BCD2"
        private const val STUDY = "#00D2A0"
        private const val DETOX = "#D946EF"
        private const val CAGE = "#EF4444"
        private const val COIN = "#F59E0B"
    }

    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val isStudy = mode == SessionMode.STUDY && !isCage
    private val accent = Color.parseColor(if (isCage) CAGE else if (isStudy) STUDY else DETOX)
    private val timerRing: CircularTimerRingView
    private val blockedValue: TextView
    private val warnedValue: TextView
    private val coinsValue: TextView
    private var blockedCount = 0
    private var warningCount = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor(BG))
        setPadding(dp(18), dp(18), dp(18), dp(14))

        val scroll = ScrollView(service).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(service).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        scroll.addView(content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Brand + security status header.
        val header = LinearLayout(service).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val logo = TextView(service).apply {
            text = "M"
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = gradient(SURFACE_2, accent, 14)
        }
        header.addView(logo, LayoutParams(dp(44), dp(44)))
        val brand = LinearLayout(service).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brand.addView(TextView(service).apply {
            text = "MAXLEVEL DETOX"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TEXT))
            letterSpacing = .04f
        })
        brand.addView(TextView(service).apply {
            text = "Focus system • protected"
            textSize = 10.5f
            setTextColor(Color.parseColor(MUTED))
        })
        header.addView(brand, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(statusPill("ACTIVE", accent))
        content.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Mode hero.
        val hero = LinearLayout(service).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = gradient(if (isCage) "#241111" else SURFACE, accent, 24)
        }
        val modeRow = LinearLayout(service).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeIcon = ImageView(service).apply {
            setImageResource(if (isStudy) R.drawable.ic_book else if (isCage) R.drawable.ic_lock else R.drawable.ic_shield)
            setColorFilter(accent)
            background = circleBackground(accent, .14f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        modeRow.addView(modeIcon, LayoutParams(dp(48), dp(48)))
        val modeTexts = LinearLayout(service).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        modeTexts.addView(TextView(service).apply {
            text = when {
                isCage -> "CAGE MODE"
                isStudy -> "STUDY MODE"
                else -> "DETOX MODE"
            }
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TEXT))
        })
        modeTexts.addView(TextView(service).apply {
            text = when {
                isCage -> "Restriction intensified"
                isStudy && subjectName.isNotBlank() -> "Focus session • $subjectName"
                isStudy -> "Focus session • distractions blocked"
                else -> "Full digital detox • stay present"
            }
            textSize = 11.5f
            setTextColor(Color.parseColor(MUTED))
        })
        modeRow.addView(modeTexts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(modeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        timerRing = CircularTimerRingView(service, accent).apply {
            layoutParams = LayoutParams(dp(232), dp(232)).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }
        }
        hero.addView(timerRing)
        hero.addView(TextView(service).apply {
            text = if (isStudy) "STAY IN STUDY MODE UNTIL THE TIMER ENDS" else "SESSION ENFORCEMENT IS ACTIVE"
            textSize = 10.5f
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(accent)
        })
        content.addView(hero, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // Three compact stats.
        val stats = LinearLayout(service).apply {
            orientation = HORIZONTAL
            weightSum = 3f
        }
        blockedValue = createStat(stats, R.drawable.ic_shield, "BLOCKED", "0", accent)
        warnedValue = createStat(stats, R.drawable.ic_warning, "WARNINGS", "0", Color.parseColor("#FBBF24"))
        coinsValue = createStat(stats, R.drawable.ic_coin, "COINS", "${readCoinsSafe()}", Color.parseColor(COIN))
        content.addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        // Allowed apps.
        if (isStudy && allowedPackages.isNotEmpty()) {
            addSectionTitle(content, "ALLOWED STUDY APPS", "Only the tools you selected can be opened.")
            val appsScroll = HorizontalScrollView(service).apply {
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }
            val row = LinearLayout(service).apply { orientation = HORIZONTAL }
            val pm = service.packageManager
            allowedPackages.take(8).forEach { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val card = LinearLayout(service).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(10), dp(12), dp(9))
                        background = gradient(SURFACE, EDGE, 16)
                        setOnClickListener { onLaunchAllowed?.invoke(pkg) }
                    }
                    card.addView(ImageView(service).apply {
                        setImageDrawable(pm.getApplicationIcon(info))
                    }, LayoutParams(dp(34), dp(34)))
                    card.addView(TextView(service).apply {
                        text = pm.getApplicationLabel(info).toString()
                        textSize = 9.5f
                        setTextColor(Color.parseColor(TEXT))
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, dp(5), 0, 0)
                    })
                    row.addView(card, LayoutParams(dp(82), dp(78)).apply { rightMargin = dp(8) })
                } catch (_: Exception) { }
            }
            appsScroll.addView(row)
            content.addView(appsScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(82)).apply { bottomMargin = dp(12) })
        }

        // Enforcement message card — intentionally no Exit/End button.
        val secure = LinearLayout(service).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = gradient("#071F2C", accent, 16)
        }
        secure.addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(accent)
        }, LayoutParams(dp(28), dp(28)))
        val secureText = LinearLayout(service).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        secureText.addView(TextView(service).apply {
            text = when {
                isCage -> "LOCKED BY CAGE ENGINE"
                isStudy -> "LOCKED BY STUDY ENGINE"
                else -> "LOCKED BY DETOX ENGINE"
            }
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
        })
        secureText.addView(TextView(service).apply {
            text = "Back, Home, Recents and blocked apps are handled by the native enforcement layer."
            textSize = 10.5f
            setTextColor(Color.parseColor(MUTED))
        })
        secure.addView(secureText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(secure, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // Reward action remains optional and never ends the session.
        if (onWatchAd != null) {
            val ad = smallActionButton("EARN +1 COIN", R.drawable.ic_coin, Color.parseColor(COIN))
            ad.setOnClickListener { onWatchAd.invoke() }
            content.addView(ad, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(10) })
        }

        content.addView(TextView(service).apply {
            text = "Your emergency call remains available."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(MUTED))
            setPadding(0, dp(4), 0, dp(8))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Emergency is the only explicit escape/safety action exposed here.
        val emergency = Button(service).apply {
            text = "EMERGENCY CALL"
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFCDD2"))
            background = gradient("#2A1116", Color.parseColor(CAGE), 16)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                EmergencyLockdown.start(service)
                EmergencyLockdown.openDialer(service)
            }
        }
        addView(emergency, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        @Suppress("DEPRECATION")
        systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String) {
        val wrap = LinearLayout(service).apply {
            orientation = VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(7))
        }
        wrap.addView(TextView(service).apply {
            text = title
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
            setTextColor(Color.parseColor(TEXT))
        })
        wrap.addView(TextView(service).apply {
            text = subtitle
            textSize = 9.5f
            setTextColor(Color.parseColor(MUTED))
        })
        parent.addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun statusPill(label: String, color: Int): TextView = TextView(service).apply {
        text = "●  $label"
        textSize = 9.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = gradient("#10243A", color, 999)
    }

    private fun createStat(parent: LinearLayout, iconRes: Int, label: String, value: String, color: Int): TextView {
        val tile = LinearLayout(service).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(9), dp(7), dp(9))
            background = gradient(SURFACE, EDGE, 16)
        }
        tile.addView(ImageView(service).apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }, LayoutParams(dp(21), dp(21)))
        val valueView = TextView(service).apply {
            text = value
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TEXT))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        tile.addView(valueView)
        tile.addView(TextView(service).apply {
            text = label
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            setTextColor(Color.parseColor(MUTED))
            gravity = Gravity.CENTER
        })
        parent.addView(tile, LayoutParams(0, dp(78), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        return valueView
    }

    private fun smallActionButton(label: String, iconRes: Int, color: Int): Button = Button(service).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        background = gradient("#152238", color, 14)
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
    }

    private fun gradient(bg: String, border: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = if (radius == 999) dp(30).toFloat() else dp(radius).toFloat()
        setColor(Color.parseColor(bg))
        setStroke(dp(1), border)
    }

    private fun circleBackground(color: Int, alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun readCoinsSafe(): Int = try { MldApp.instance.stateRepo.blockingCoinBalance() } catch (_: Exception) { 0 }

    fun updateTick(remainingSeconds: Int, totalSeconds: Int) {
        val safeTotal = totalSeconds.coerceAtLeast(1)
        val progress = 1f - (remainingSeconds.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        val time = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        timerRing.update(progress, time)
        coinsValue.text = readCoinsSafe().toString()
    }

    fun recordViolation() { blockedCount++; blockedValue.text = blockedCount.toString() }
    fun recordWarning() { warningCount++; warnedValue.text = warningCount.toString() }

    class CircularTimerRingView(context: Context, private val ringColor: Int) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val stroke = 11f * density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.parseColor("#17304A") }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke + 10f * density; color = Color.argb(34, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor)) }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = ringColor; strokeCap = Paint.Cap.ROUND }
        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 37f * density; typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(MUTED); textSize = 10f * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; letterSpacing = .1f }
        private val oval = RectF()
        private var progress = 0f
        private var timeText = "00:00"

        fun update(progress: Float, timeText: String) { this.progress = progress; this.timeText = timeText; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = stroke + 10f * density
            oval.set(pad, pad, width - pad, height - pad)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (Math.min(width, height) - 2f * pad) / 2f
            canvas.drawCircle(cx, cy, radius, track)
            if (progress > .001f) {
                canvas.drawArc(oval, -90f, progress * 360f, false, glow)
                canvas.drawArc(oval, -90f, progress * 360f, false, progressPaint)
            }
            canvas.drawText(timeText, cx, cy + timePaint.textSize * .34f, timePaint)
            canvas.drawText("TIME REMAINING", cx, cy + timePaint.textSize * .34f + 25f * density, smallPaint)
        }
    }
}
