package com.github.xingzheli.bilidetox.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.widget.Button

/** 宿主自定义夜间主题不一定改变系统夜间标记，以当前主题的正文颜色为准。 */
object SettingsTheme {
    fun styleButton(button: Button, forceDark: Boolean? = null) {
        val dark = forceDark ?: run {
            val fallback = button.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val colors = button.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            try {
                val primary = colors.getColorStateList(0)?.defaultColor
                if (primary == null) fallback else Color.luminance(primary) > 0.5
            } finally { colors.recycle() }
        }
        val enabled = intArrayOf(android.R.attr.state_enabled)
        val states = arrayOf(enabled, intArrayOf())
        button.backgroundTintList = ColorStateList(states, if (dark) {
            intArrayOf(Color.rgb(69, 69, 69), Color.rgb(52, 52, 52))
        } else {
            intArrayOf(Color.rgb(224, 224, 224), Color.rgb(237, 237, 237))
        })
        button.setTextColor(ColorStateList(states, if (dark) {
            intArrayOf(Color.rgb(250, 250, 250), Color.rgb(166, 166, 166))
        } else {
            intArrayOf(Color.rgb(33, 33, 33), Color.rgb(102, 102, 102))
        }))
    }

    fun show(dialog: AlertDialog) {
        dialog.show()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.let { styleButton(it) }
        }
    }
}
