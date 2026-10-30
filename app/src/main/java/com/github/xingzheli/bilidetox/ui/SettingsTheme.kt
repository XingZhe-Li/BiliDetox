package com.github.xingzheli.bilidetox.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.Switch

/** 宿主自定义夜间主题不一定改变系统夜间标记，以当前主题的正文颜色为准。 */
object SettingsTheme {
    /** 系统选择器不能混用宿主的浅色底层主题和夜间文字覆盖。 */
    fun dialogContext(context: Context): Context = ContextThemeWrapper(context,
        if (isDark(context)) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert)

    fun isDark(context: Context): Boolean {
        val fallback = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            val primary = colors.getColorStateList(0)?.defaultColor
            return if (primary == null) fallback else Color.luminance(primary) > 0.5
        } finally { colors.recycle() }
    }

    /** 明确绘制轨道和滑块，避免宿主 Switch 资源在夜间仍使用黑色或透明素材。 */
    fun styleSwitch(toggle: Switch) {
        val dark = isDark(toggle.context)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(),
        )
        val thumbColors = ColorStateList(states, if (dark) intArrayOf(
            Color.WHITE, Color.rgb(229, 229, 229), Color.rgb(206, 184, 192), Color.rgb(163, 163, 163),
        ) else intArrayOf(Color.WHITE, Color.WHITE, Color.rgb(240, 232, 235), Color.rgb(240, 240, 240)))
        val trackColors = ColorStateList(states, if (dark) intArrayOf(
            Color.rgb(214, 90, 134), Color.rgb(119, 119, 119), Color.rgb(128, 84, 105), Color.rgb(80, 80, 80),
        ) else intArrayOf(
            Color.rgb(185, 61, 106), Color.rgb(132, 132, 132), Color.rgb(197, 143, 164), Color.rgb(199, 199, 199),
        ))
        val density = toggle.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        toggle.thumbTintList = null
        toggle.trackTintList = null
        toggle.thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(24), dp(24))
            setColor(thumbColors)
            setStroke(dp(1), if (dark) Color.rgb(45, 45, 45) else Color.rgb(240, 240, 240))
        }
        toggle.trackDrawable = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setSize(dp(48), dp(24))
            setColor(trackColors)
            setStroke(dp(1), if (dark) Color.rgb(146, 146, 146) else Color.rgb(132, 132, 132))
        }
        toggle.switchMinWidth = dp(48)
        toggle.splitTrack = false
    }

    fun styleButton(button: Button, forceDark: Boolean? = null) {
        val dark = forceDark ?: isDark(button.context)
        val enabled = intArrayOf(android.R.attr.state_enabled)
        val states = arrayOf(enabled, intArrayOf())
        val background = ColorStateList(states, if (dark) {
            intArrayOf(Color.rgb(185, 61, 106), Color.rgb(64, 64, 64))
        } else {
            intArrayOf(Color.rgb(178, 47, 92), Color.rgb(230, 230, 230))
        })
        val density = button.resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(background)
        }
        // AlertDialog 的文字按钮可能没有可着色的填充，不能只设置 backgroundTintList。
        button.backgroundTintList = null
        button.background = InsetDrawable(RippleDrawable(
            ColorStateList.valueOf(Color.argb(55, 255, 255, 255)), shape, null), (4 * density).toInt())
        button.setTextColor(ColorStateList(states, if (dark) {
            intArrayOf(Color.WHITE, Color.rgb(166, 166, 166))
        } else {
            intArrayOf(Color.WHITE, Color.rgb(102, 102, 102))
        }))
    }

    fun show(dialog: AlertDialog) {
        dialog.show()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.let { styleButton(it) }
        }
    }
}
