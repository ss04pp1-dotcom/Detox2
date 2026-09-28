package com.maxleveldetox.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MldDesign — Theme tokens and reusable view builders.
 * 100% pixel-to-pixel fidelity with Flutter tokens and Material 3 design system.
 */
object MldDesign {
    const val COLOR_BG = "#0B0F19"
    const val COLOR_SURFACE = "#111827"
    const val COLOR_CARD = "#131D31"
    const val COLOR_CARD_BORDER = "#1E293B"
    const val COLOR_TEXT_PRIMARY = "#F8FAFC"
    const val COLOR_TEXT_SECONDARY = "#94A3B8"
    const val COLOR_TEXT_MUTED = "#64748B"

    const val ACCENT_STUDY = "#6366F1"    // Indigo / Violet
    const val ACCENT_DETOX = "#A855F7"    // Purple
    const val ACCENT_MONK = "#F43F5E"     // Rose / Crimson
    const val ACCENT_SUCCESS = "#10B981"  // Emerald
    const val ACCENT_WARNING = "#F59E0B"  // Amber
    const val ACCENT_DANGER = "#EF4444"   // Red

    fun dp(context: Context, value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }

    fun roundedDrawable(
        context: Context,
        bgColorHex: String,
        borderColorHex: String? = null,
        radiusDp: Int = 14,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(Color.parseColor(bgColorHex))
            if (borderColorHex != null) {
                setStroke(dp(context, strokeWidthDp), Color.parseColor(borderColorHex))
            }
        }
    }

    fun primaryButton(
        context: Context,
        label: String,
        accentColorHex: String = ACCENT_STUDY,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            letterSpacing = 0.04f
            background = roundedDrawable(context, accentColorHex, null, 12)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            setOnClickListener { onClick() }
        }
    }

    fun outlineButton(
        context: Context,
        label: String,
        borderColorHex: String = COLOR_CARD_BORDER,
        textColorHex: String = COLOR_TEXT_PRIMARY,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = roundedDrawable(context, "#1A2234", borderColorHex, 12)
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            setOnClickListener { onClick() }
        }
    }

    fun headerView(
        context: Context,
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 14))

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                if (onBack != null) {
                    val backBtn = TextView(context).apply {
                        text = "‹"
                        textSize = 32f
                        setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                        setPadding(0, 0, dp(context, 16), 0)
                        setOnClickListener { onBack() }
                    }
                    addView(backBtn)
                }

                val titleTv = TextView(context).apply {
                    text = title
                    textSize = 22f
                    setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.02f
                }
                addView(titleTv)
            }
            addView(row)

            if (subtitle != null) {
                val subTv = TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                    setPadding(if (onBack != null) dp(context, 32) else 0, dp(context, 4), 0, 0)
                }
                addView(subTv)
            }
        }
    }

    fun cardContainer(context: Context, padDp: Int = 16): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(context, COLOR_CARD, COLOR_CARD_BORDER, 16)
            setPadding(dp(context, padDp), dp(context, padDp), dp(context, padDp), dp(context, padDp))
        }
    }
}
