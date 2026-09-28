package com.maxleveldetox.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppRulesActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val blockedApps = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "App Rules & Limits",
            "Set explicit restrictions for apps outside of active study sessions"
        ) {
            finish()
        })

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, MldDesign.dp(this@AppRulesActivity, 20), 0, 0)
        }
        root.addView(progressBar)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@AppRulesActivity, 20), 0, MldDesign.dp(this@AppRulesActivity, 20), MldDesign.dp(this@AppRulesActivity, 30))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                listContainer.removeAllViews()

                for (app in installed) {
                    val pkg = app.packageName
                    val label = app.loadLabel(pm).toString()
                    val icon = app.loadIcon(pm)

                    val card = MldDesign.cardContainer(this@AppRulesActivity, 12).apply {
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = MldDesign.dp(this@AppRulesActivity, 8)
                        }
                        layoutParams = params

                        val row = LinearLayout(this@AppRulesActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL

                            val iv = ImageView(this@AppRulesActivity).apply {
                                setImageDrawable(icon)
                                layoutParams = LinearLayout.LayoutParams(MldDesign.dp(this@AppRulesActivity, 36), MldDesign.dp(this@AppRulesActivity, 36))
                            }
                            val nameTv = TextView(this@AppRulesActivity).apply {
                                text = label
                                textSize = 14f
                                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                                typeface = Typeface.DEFAULT_BOLD
                                setPadding(MldDesign.dp(this@AppRulesActivity, 12), 0, 0, 0)
                            }
                            val statusBtn = TextView(this@AppRulesActivity).apply {
                                val isBlocked = blockedApps.contains(pkg)
                                text = if (isBlocked) "BLOCKED" else "ALLOWED"
                                textSize = 11f
                                setTextColor(if (isBlocked) Color.parseColor(MldDesign.ACCENT_DANGER) else Color.parseColor(MldDesign.ACCENT_SUCCESS))
                                typeface = Typeface.DEFAULT_BOLD
                                background = MldDesign.roundedDrawable(
                                    this@AppRulesActivity,
                                    if (isBlocked) "#450A0A" else "#064E3B",
                                    if (isBlocked) MldDesign.ACCENT_DANGER else MldDesign.ACCENT_SUCCESS,
                                    10
                                )
                                setPadding(MldDesign.dp(this@AppRulesActivity, 10), MldDesign.dp(this@AppRulesActivity, 6), MldDesign.dp(this@AppRulesActivity, 10), MldDesign.dp(this@AppRulesActivity, 6))
                                setOnClickListener {
                                    if (blockedApps.contains(pkg)) {
                                        blockedApps.remove(pkg)
                                        text = "ALLOWED"
                                        setTextColor(Color.parseColor(MldDesign.ACCENT_SUCCESS))
                                        background = MldDesign.roundedDrawable(this@AppRulesActivity, "#064E3B", MldDesign.ACCENT_SUCCESS, 10)
                                    } else {
                                        blockedApps.add(pkg)
                                        text = "BLOCKED"
                                        setTextColor(Color.parseColor(MldDesign.ACCENT_DANGER))
                                        background = MldDesign.roundedDrawable(this@AppRulesActivity, "#450A0A", MldDesign.ACCENT_DANGER, 10)
                                    }
                                }
                            }

                            addView(iv)
                            addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(statusBtn)
                        }
                        addView(row)
                    }
                    listContainer.addView(card)
                }
            }
        }
    }
}
