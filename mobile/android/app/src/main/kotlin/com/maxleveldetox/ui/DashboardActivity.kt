package com.maxleveldetox.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.SessionEngine
import com.maxleveldetox.enforcement.SessionMode

class DashboardActivity : AppCompatActivity() {

    private lateinit var coinsTv: TextView
    private lateinit var streakTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Top Navigation Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(MldDesign.dp(this@DashboardActivity, 20), MldDesign.dp(this@DashboardActivity, 24), MldDesign.dp(this@DashboardActivity, 20), MldDesign.dp(this@DashboardActivity, 14))

            val titleTv = TextView(this@DashboardActivity).apply {
                text = "MAXLEVEL DETOX"
                textSize = 18f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Coins Chip
            val coinsChip = LinearLayout(this@DashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(MldDesign.dp(this@DashboardActivity, 10), MldDesign.dp(this@DashboardActivity, 6), MldDesign.dp(this@DashboardActivity, 10), MldDesign.dp(this@DashboardActivity, 6))
                background = MldDesign.roundedDrawable(this@DashboardActivity, "#1F1B12", "#78350F", 20)
                setOnClickListener {
                    startActivity(Intent(this@DashboardActivity, CoinsActivity::class.java))
                }
            }
            coinsTv = TextView(this@DashboardActivity).apply {
                text = "🪙 0"
                textSize = 12f
                setTextColor(Color.parseColor(MldDesign.ACCENT_WARNING))
                typeface = Typeface.DEFAULT_BOLD
            }
            coinsChip.addView(coinsTv)
            addView(coinsChip)

            // Shield / Permissions Button
            val shieldBtn = TextView(this@DashboardActivity).apply {
                text = "🛡️"
                textSize = 18f
                setPadding(MldDesign.dp(this@DashboardActivity, 12), 0, 0, 0)
                setOnClickListener {
                    startActivity(Intent(this@DashboardActivity, PermissionCenterActivity::class.java))
                }
            }
            addView(shieldBtn)
        }
        root.addView(topBar)

        // Scrollable Body
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@DashboardActivity, 20), 0, MldDesign.dp(this@DashboardActivity, 20), MldDesign.dp(this@DashboardActivity, 30))
        }

        // Today's Focus Card
        val focusCard = MldDesign.cardContainer(this, 18).apply {
            val title = TextView(this@DashboardActivity).apply {
                text = "TODAY'S FOCUS"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val bigStats = TextView(this@DashboardActivity).apply {
                text = "120 min"
                textSize = 32f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, MldDesign.dp(this@DashboardActivity, 4), 0, 0)
            }
            val status = TextView(this@DashboardActivity).apply {
                text = "Target 180 min · 67% completed"
                textSize = 13f
                setTextColor(Color.parseColor(MldDesign.ACCENT_SUCCESS))
                setPadding(0, MldDesign.dp(this@DashboardActivity, 4), 0, 0)
            }
            addView(title)
            addView(bigStats)
            addView(status)
        }
        content.addView(focusCard)

        // Section Title: Quick Start Modes
        val modeSectionTitle = TextView(this).apply {
            text = "QUICK START MODES"
            textSize = 11f
            setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, MldDesign.dp(this@DashboardActivity, 22), 0, MldDesign.dp(this@DashboardActivity, 10))
        }
        content.addView(modeSectionTitle)

        // 1. Study Mode Card
        content.addView(createModeCard(
            tag = "FOCUSED WORK",
            title = "Study Mode",
            desc = "Deep work with allowed tools, subject tracking & auto breaks.",
            accentHex = MldDesign.ACCENT_STUDY,
            actionLabel = "START STUDY SESSION"
        ) {
            startActivity(Intent(this, StudySetupActivity::class.java))
        })

        // 2. Detox Mode Card
        content.addView(createModeCard(
            tag = "TOTAL LOCKOUT",
            title = "Hard Detox",
            desc = "Complete phone lock to defeat dopamine addiction loops.",
            accentHex = MldDesign.ACCENT_DETOX,
            actionLabel = "ENGAGE DETOX"
        ) {
            startActivity(Intent(this, DetoxSetupActivity::class.java))
        })

        // 3. Shorts & Reels Blocker Card
        content.addView(createModeCard(
            tag = "ADDICTION SHIELD",
            title = "Shorts & Reels Blocker",
            desc = "Auto-blocks YouTube Shorts, Instagram Reels, Facebook Reels, and TikTok.",
            accentHex = MldDesign.ACCENT_DANGER,
            actionLabel = "SHORTS SETTINGS & CAGE"
        ) {
            startActivity(Intent(this, ShortsSettingsActivity::class.java))
        })

        // 4. App Rules Management Card
        content.addView(createModeCard(
            tag = "CUSTOM ACCESS",
            title = "App Rules & Limits",
            desc = "Configure which apps are blocked, allowlisted, or time-limited.",
            accentHex = MldDesign.COLOR_TEXT_SECONDARY,
            actionLabel = "MANAGE APPS"
        ) {
            startActivity(Intent(this, AppRulesActivity::class.java))
        })

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateBalance()
    }

    private fun updateBalance() {
        try {
            val bal = MldApp.instance.stateRepo.blockingCoinBalance()
            coinsTv.text = "🪙 $bal"
        } catch (_: Exception) {}
    }

    private fun createModeCard(
        tag: String,
        title: String,
        desc: String,
        accentHex: String,
        actionLabel: String,
        onClick: () -> Unit
    ): LinearLayout {
        return MldDesign.cardContainer(this, 16).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MldDesign.dp(this@DashboardActivity, 14)
            }
            layoutParams = params

            val tagTv = TextView(this@DashboardActivity).apply {
                text = tag
                textSize = 10f
                setTextColor(Color.parseColor(accentHex))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val titleTv = TextView(this@DashboardActivity).apply {
                text = title
                textSize = 18f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, MldDesign.dp(this@DashboardActivity, 2), 0, 0)
            }
            val descTv = TextView(this@DashboardActivity).apply {
                text = desc
                textSize = 13f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
                setPadding(0, MldDesign.dp(this@DashboardActivity, 4), 0, MldDesign.dp(this@DashboardActivity, 14))
            }
            val actionBtn = MldDesign.primaryButton(this@DashboardActivity, actionLabel, accentHex) {
                onClick()
            }

            addView(tagTv)
            addView(titleTv)
            addView(descTv)
            addView(actionBtn)
        }
    }
}
