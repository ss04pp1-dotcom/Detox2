package com.maxleveldetox.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.SessionMode
import com.maxleveldetox.enforcement.Strictness
import com.maxleveldetox.overlay.SessionKiosk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetoxSetupActivity : AppCompatActivity() {

    private var selectedMinutes = 60
    private val durations = listOf(15, 30, 60, 120, 240, 480)
    private val durationButtons = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "Hard Detox Setup",
            "Total device lockout to break compulsive phone habits"
        ) {
            finish()
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@DetoxSetupActivity, 20), 0, MldDesign.dp(this@DetoxSetupActivity, 20), MldDesign.dp(this@DetoxSetupActivity, 30))
        }

        // 1. Duration Selection Card
        val durationCard = MldDesign.cardContainer(this, 16).apply {
            val title = TextView(this@DetoxSetupActivity).apply {
                text = "DETOX DURATION"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            addView(title)

            val chipsScroll = HorizontalScrollView(this@DetoxSetupActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipsRow = LinearLayout(this@DetoxSetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, MldDesign.dp(this@DetoxSetupActivity, 10), 0, 0)
            }
            for (m in durations) {
                val label = if (m >= 60) "${m / 60}h" else "${m}m"
                val chip = TextView(this@DetoxSetupActivity).apply {
                    text = label
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(MldDesign.dp(this@DetoxSetupActivity, 18), MldDesign.dp(this@DetoxSetupActivity, 10), MldDesign.dp(this@DetoxSetupActivity, 18), MldDesign.dp(this@DetoxSetupActivity, 10))
                    setOnClickListener {
                        selectedMinutes = m
                        updateDurationChips()
                    }
                }
                durationButtons.add(chip)
                chipsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = MldDesign.dp(this@DetoxSetupActivity, 8)
                })
            }
            chipsScroll.addView(chipsRow)
            addView(chipsScroll)
        }
        content.addView(durationCard)

        // 2. Warning Card
        val warningCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MldDesign.roundedDrawable(this@DetoxSetupActivity, "#1C1427", "#581C87", 14)
            setPadding(MldDesign.dp(this@DetoxSetupActivity, 16), MldDesign.dp(this@DetoxSetupActivity, 14), MldDesign.dp(this@DetoxSetupActivity, 16), MldDesign.dp(this@DetoxSetupActivity, 14))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = MldDesign.dp(this@DetoxSetupActivity, 16)
                bottomMargin = MldDesign.dp(this@DetoxSetupActivity, 24)
            }
            layoutParams = params

            val warnTitle = TextView(this@DetoxSetupActivity).apply {
                text = "⚠️ TOTAL PHONE LOCKOUT"
                textSize = 12f
                setTextColor(Color.parseColor(MldDesign.ACCENT_DETOX))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val warnBody = TextView(this@DetoxSetupActivity).apply {
                text = "During Hard Detox, ALL applications, browsers, settings, and media are completely locked. No apps can be allowlisted. Only Emergency Call is permitted until the countdown reaches zero."
                textSize = 13f
                setTextColor(Color.parseColor("#D8B4FE"))
                setPadding(0, MldDesign.dp(this@DetoxSetupActivity, 6), 0, 0)
            }
            addView(warnTitle)
            addView(warnBody)
        }
        content.addView(warningCard)

        // 3. Engage Detox Button
        val engageBtn = MldDesign.primaryButton(this, "ENGAGE HARD DETOX", MldDesign.ACCENT_DETOX) {
            startDetoxSession()
        }
        content.addView(engageBtn)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        updateDurationChips()
    }

    private fun updateDurationChips() {
        for ((i, chip) in durationButtons.withIndex()) {
            val m = durations[i]
            val isSelected = m == selectedMinutes
            chip.setTextColor(if (isSelected) Color.WHITE else Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
            chip.background = MldDesign.roundedDrawable(
                this,
                if (isSelected) MldDesign.ACCENT_DETOX else "#1A2234",
                if (isSelected) MldDesign.ACCENT_DETOX else MldDesign.COLOR_CARD_BORDER,
                10
            )
        }
    }

    private fun startDetoxSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as MldApp
                val result = app.sessionEngine.startSession(
                    mode = SessionMode.DETOX,
                    durationMinutes = selectedMinutes,
                    strictness = Strictness.MAXLEVEL,
                    allowedPackages = emptyList(),
                    blockedCategories = emptyList(),
                    subject = ""
                )
                if (!result.ok) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DetoxSetupActivity, result.message ?: "Failed to start detox", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    SessionKiosk.sync(this@DetoxSetupActivity)
                    Toast.makeText(this@DetoxSetupActivity, "Detox engaged for $selectedMinutes min", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DetoxSetupActivity, "Error starting detox: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
