package com.maxleveldetox.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maxleveldetox.MldApp
import com.maxleveldetox.enforcement.ShortsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortsSettingsActivity : AppCompatActivity() {

    private lateinit var masterSwitch: Switch
    private val platformSwitches = mutableMapOf<String, Switch>()

    private val platformLabels = mapOf(
        "com.google.android.youtube" to ("YouTube Shorts" to "🔴"),
        "com.instagram.android" to ("Instagram Reels" to "🟣"),
        "com.facebook.katana" to ("Facebook Reels" to "🔵"),
        "com.zhiliaoapp.musically" to ("TikTok" to "⚫")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "Shorts & Reels Blocker",
            "Auto-detects and suppresses dopamine-inducing vertical video feeds"
        ) {
            finish()
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@ShortsSettingsActivity, 20), 0, MldDesign.dp(this@ShortsSettingsActivity, 20), MldDesign.dp(this@ShortsSettingsActivity, 30))
        }

        // 1. Master Toggle Card
        val masterCard = MldDesign.cardContainer(this, 18).apply {
            val row = LinearLayout(this@ShortsSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val infoCol = LinearLayout(this@ShortsSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val title = TextView(this@ShortsSettingsActivity).apply {
                        text = "Master Blocker Shield"
                        textSize = 16f
                        setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val sub = TextView(this@ShortsSettingsActivity).apply {
                        text = "Active background enforcement"
                        textSize = 12f
                        setTextColor(Color.parseColor(MldDesign.ACCENT_SUCCESS))
                    }
                    addView(title)
                    addView(sub)
                }
                addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                masterSwitch = Switch(this@ShortsSettingsActivity).apply {
                    isChecked = true
                    setOnCheckedChangeListener { _, isChecked ->
                        toggleMaster(isChecked)
                    }
                }
                addView(masterSwitch)
            }
            addView(row)
        }
        content.addView(masterCard)

        // Section Title: Protected Platforms
        val platTitle = TextView(this).apply {
            text = "PROTECTED PLATFORMS"
            textSize = 11f
            setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, MldDesign.dp(this@ShortsSettingsActivity, 20), 0, MldDesign.dp(this@ShortsSettingsActivity, 10))
        }
        content.addView(platTitle)

        // 2. Platforms Card
        val platformsCard = MldDesign.cardContainer(this, 16)
        for ((pkg, info) in platformLabels) {
            val (name, icon) = info
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, MldDesign.dp(this@ShortsSettingsActivity, 8), 0, MldDesign.dp(this@ShortsSettingsActivity, 8))

                val iconTv = TextView(this@ShortsSettingsActivity).apply {
                    text = icon
                    textSize = 18f
                    setPadding(0, 0, MldDesign.dp(this@ShortsSettingsActivity, 12), 0)
                }
                val labelTv = TextView(this@ShortsSettingsActivity).apply {
                    text = name
                    textSize = 14f
                    setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                    typeface = Typeface.DEFAULT_BOLD
                }
                val sw = Switch(this@ShortsSettingsActivity).apply {
                    isChecked = true
                    setOnCheckedChangeListener { _, isChecked ->
                        togglePlatform(pkg, isChecked)
                    }
                }
                platformSwitches[pkg] = sw

                addView(iconTv)
                addView(labelTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sw)
            }
            platformsCard.addView(row)
        }
        content.addView(platformsCard)

        // 3. Cage Explanation Card
        val cageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MldDesign.roundedDrawable(this@ShortsSettingsActivity, "#1C1012", "#7F1D1D", 14)
            setPadding(MldDesign.dp(this@ShortsSettingsActivity, 16), MldDesign.dp(this@ShortsSettingsActivity, 14), MldDesign.dp(this@ShortsSettingsActivity, 16), MldDesign.dp(this@ShortsSettingsActivity, 14))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = MldDesign.dp(this@ShortsSettingsActivity, 16)
            }
            layoutParams = params

            val title = TextView(this@ShortsSettingsActivity).apply {
                text = "🔒 THE 60-SECOND CAGE PROTOCOL"
                textSize = 12f
                setTextColor(Color.parseColor(MldDesign.ACCENT_DANGER))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val body = TextView(this@ShortsSettingsActivity).apply {
                text = "If you repeatedly attempt to open reels or shorts (5 attempts within 3 minutes), MaxLevel Detox activates a 60-second CAGE lockdown. The screen is locked at OS layer 2032 with a reverse circular countdown ring until discipline is restored."
                textSize = 13f
                setTextColor(Color.parseColor("#FECACA"))
                setPadding(0, MldDesign.dp(this@ShortsSettingsActivity, 6), 0, 0)
            }
            addView(title)
            addView(body)
        }
        content.addView(cageCard)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        loadCurrentState()
    }

    private fun loadCurrentState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as MldApp
            val state = try {
                app.stateRepo.blockingShorts()
            } catch (_: Exception) {
                null
            } ?: ShortsState(enabled = true, warningCount = 0, warningDateKey = "", platforms = ShortsState.DEFAULT_PLATFORMS)

            withContext(Dispatchers.Main) {
                masterSwitch.isChecked = state.enabled
                for ((pkg, sw) in platformSwitches) {
                    sw.isChecked = state.platforms[pkg] ?: true
                    sw.isEnabled = state.enabled
                }
            }
        }
    }

    private fun toggleMaster(enabled: Boolean) {
        for ((_, sw) in platformSwitches) {
            sw.isEnabled = enabled
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as MldApp
                val current = app.stateRepo.blockingShorts()
                app.stateRepo.saveShorts(current.copy(enabled = enabled))
            } catch (_: Exception) {}
        }
    }

    private fun togglePlatform(pkg: String, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as MldApp
                val current = app.stateRepo.blockingShorts()
                val updated = current.platforms.toMutableMap()
                updated[pkg] = enabled
                app.stateRepo.saveShorts(current.copy(platforms = updated))
            } catch (_: Exception) {}
        }
    }
}
