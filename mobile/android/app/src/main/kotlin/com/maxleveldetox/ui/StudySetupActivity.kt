package com.maxleveldetox.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
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

class StudySetupActivity : AppCompatActivity() {

    private var selectedMinutes = 25
    private var selectedSubject = "General Study"
    private val allowedPackages = mutableSetOf<String>()

    private val durations = listOf(15, 25, 45, 60, 90, 120)
    private val subjects = listOf("General Study", "Mathematics", "Physics", "Coding / Tech", "Reading", "Exam Prep")

    private val durationButtons = mutableListOf<TextView>()
    private val subjectButtons = mutableListOf<TextView>()
    private lateinit var appsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "Study Mode Setup",
            "Configure duration, subject & permitted study tools"
        ) {
            finish()
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@StudySetupActivity, 20), 0, MldDesign.dp(this@StudySetupActivity, 20), MldDesign.dp(this@StudySetupActivity, 30))
        }

        // 1. Duration Picker Card
        val durationCard = MldDesign.cardContainer(this, 16).apply {
            val title = TextView(this@StudySetupActivity).apply {
                text = "SESSION DURATION"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            addView(title)

            val chipsScroll = HorizontalScrollView(this@StudySetupActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipsRow = LinearLayout(this@StudySetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, MldDesign.dp(this@StudySetupActivity, 10), 0, 0)
            }
            for (m in durations) {
                val chip = TextView(this@StudySetupActivity).apply {
                    text = "${m}m"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(MldDesign.dp(this@StudySetupActivity, 16), MldDesign.dp(this@StudySetupActivity, 8), MldDesign.dp(this@StudySetupActivity, 16), MldDesign.dp(this@StudySetupActivity, 8))
                    setOnClickListener {
                        selectedMinutes = m
                        updateDurationChips()
                    }
                }
                durationButtons.add(chip)
                chipsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = MldDesign.dp(this@StudySetupActivity, 8)
                })
            }
            chipsScroll.addView(chipsRow)
            addView(chipsScroll)
        }
        content.addView(durationCard)

        // 2. Subject Picker Card
        val subjectCard = MldDesign.cardContainer(this, 16).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = MldDesign.dp(this@StudySetupActivity, 14)
            }
            layoutParams = params

            val title = TextView(this@StudySetupActivity).apply {
                text = "STUDY SUBJECT"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            addView(title)

            val chipsScroll = HorizontalScrollView(this@StudySetupActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipsRow = LinearLayout(this@StudySetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, MldDesign.dp(this@StudySetupActivity, 10), 0, 0)
            }
            for (sub in subjects) {
                val chip = TextView(this@StudySetupActivity).apply {
                    text = sub
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(MldDesign.dp(this@StudySetupActivity, 14), MldDesign.dp(this@StudySetupActivity, 8), MldDesign.dp(this@StudySetupActivity, 14), MldDesign.dp(this@StudySetupActivity, 8))
                    setOnClickListener {
                        selectedSubject = sub
                        updateSubjectChips()
                    }
                }
                subjectButtons.add(chip)
                chipsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = MldDesign.dp(this@StudySetupActivity, 8)
                })
            }
            chipsScroll.addView(chipsRow)
            addView(chipsScroll)
        }
        content.addView(subjectCard)

        // 3. Allowed Apps Card
        val appsCard = MldDesign.cardContainer(this, 16).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = MldDesign.dp(this@StudySetupActivity, 14)
                bottomMargin = MldDesign.dp(this@StudySetupActivity, 20)
            }
            layoutParams = params

            val title = TextView(this@StudySetupActivity).apply {
                text = "ALLOWED STUDY TOOLS"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val sub = TextView(this@StudySetupActivity).apply {
                text = "Select apps permitted during this session (e.g. Calculator, Notes)"
                textSize = 12f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
                setPadding(0, MldDesign.dp(this@StudySetupActivity, 2), 0, MldDesign.dp(this@StudySetupActivity, 8))
            }
            addView(title)
            addView(sub)

            appsContainer = LinearLayout(this@StudySetupActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(appsContainer)
        }
        content.addView(appsCard)

        // 4. Start Session Action Button
        val startBtn = MldDesign.primaryButton(this, "START STUDY SESSION", MldDesign.ACCENT_STUDY) {
            startStudySession()
        }
        content.addView(startBtn)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        updateDurationChips()
        updateSubjectChips()
        loadInstalledApps()
    }

    private fun updateDurationChips() {
        for ((i, chip) in durationButtons.withIndex()) {
            val m = durations[i]
            val isSelected = m == selectedMinutes
            chip.setTextColor(if (isSelected) Color.WHITE else Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
            chip.background = MldDesign.roundedDrawable(
                this,
                if (isSelected) MldDesign.ACCENT_STUDY else "#1A2234",
                if (isSelected) MldDesign.ACCENT_STUDY else MldDesign.COLOR_CARD_BORDER,
                10
            )
        }
    }

    private fun updateSubjectChips() {
        for ((i, chip) in subjectButtons.withIndex()) {
            val s = subjects[i]
            val isSelected = s == selectedSubject
            chip.setTextColor(if (isSelected) Color.WHITE else Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
            chip.background = MldDesign.roundedDrawable(
                this,
                if (isSelected) MldDesign.ACCENT_STUDY else "#1A2234",
                if (isSelected) MldDesign.ACCENT_STUDY else MldDesign.COLOR_CARD_BORDER,
                10
            )
        }
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("calc") }
                .take(15)

            withContext(Dispatchers.Main) {
                appsContainer.removeAllViews()
                for (app in packages) {
                    val row = LinearLayout(this@StudySetupActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, MldDesign.dp(this@StudySetupActivity, 6), 0, MldDesign.dp(this@StudySetupActivity, 6))

                        val icon = ImageView(this@StudySetupActivity).apply {
                            setImageDrawable(app.loadIcon(pm))
                            layoutParams = LinearLayout.LayoutParams(MldDesign.dp(this@StudySetupActivity, 32), MldDesign.dp(this@StudySetupActivity, 32))
                        }
                        val nameTv = TextView(this@StudySetupActivity).apply {
                            text = app.loadLabel(pm).toString()
                            textSize = 13f
                            setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                            setPadding(MldDesign.dp(this@StudySetupActivity, 10), 0, 0, 0)
                        }
                        val cb = CheckBox(this@StudySetupActivity).apply {
                            isChecked = allowedPackages.contains(app.packageName)
                            setOnCheckedChangeListener { _, isChecked ->
                                if (isChecked) allowedPackages.add(app.packageName)
                                else allowedPackages.remove(app.packageName)
                            }
                        }

                        addView(icon)
                        addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(cb)
                    }
                    appsContainer.addView(row)
                }
            }
        }
    }

    private fun startStudySession() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as MldApp
                val result = app.sessionEngine.startSession(
                    mode = SessionMode.STUDY,
                    durationMinutes = selectedMinutes,
                    strictness = Strictness.MAXLEVEL,
                    allowedPackages = allowedPackages.toList(),
                    blockedCategories = emptyList(),
                    subject = selectedSubject
                )
                if (!result.ok) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StudySetupActivity, result.message ?: "Failed to start study session", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    SessionKiosk.sync(this@StudySetupActivity)
                    Toast.makeText(this@StudySetupActivity, "Study session started: $selectedMinutes min", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StudySetupActivity, "Error starting session: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
