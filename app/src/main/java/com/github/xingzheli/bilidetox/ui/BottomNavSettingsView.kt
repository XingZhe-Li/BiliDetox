package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.BottomNavConfig
import com.github.xingzheli.bilidetox.RuntimeConfig

/** 控制底部导航栏中的发布和会员购入口。 */
class BottomNavSettingsView(private val activity: Activity) : LinearLayout(activity) {
    init { orientation = VERTICAL; render() }

    private fun render() {
        val config = RuntimeConfig.get()
        val editable = !config.timeLock.isPledged(System.currentTimeMillis())
        addView(TextView(activity).apply {
            text = "独立控制底部导航栏的添加和会员购入口。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })
        if (!editable) addView(TextView(activity).apply {
            text = "发誓锁定生效中，底部导航栏的全部选项也不可修改。"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })
        row("显示添加", "底部导航栏的发布内容入口", config.bottomNav.showPublish, editable,
            { it.showPublish }) { bottom, value -> bottom.copy(showPublish = value) }
        row("显示会员购", "底部导航栏的会员购入口", config.bottomNav.showMall, editable,
            { it.showMall }) { bottom, value -> bottom.copy(showMall = value) }
    }

    private fun row(
        title: String,
        summary: String,
        value: Boolean,
        editable: Boolean,
        read: (BottomNavConfig) -> Boolean,
        edit: (BottomNavConfig, Boolean) -> BottomNavConfig,
    ) {
        val row = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isEnabled = editable
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(LinearLayout(activity).apply {
            orientation = VERTICAL
            alpha = if (editable) 1f else 0.5f
            addView(TextView(activity).apply { text = title; textSize = 16f })
            addView(TextView(activity).apply { text = summary; textSize = 12f; alpha = 0.7f })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            SettingsTheme.styleSwitch(this)
            isChecked = value
            isEnabled = editable
            contentDescription = title
            var binding = false
            setOnCheckedChangeListener { _, checked ->
                if (binding) return@setOnCheckedChangeListener
                try {
                    if (!RuntimeConfig.update { it.copy(bottomNav = edit(it.bottomNav, checked)) }) {
                        Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    }
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                } finally {
                    binding = true
                    isChecked = read(RuntimeConfig.get().bottomNav)
                    binding = false
                }
            }
        }
        row.addView(toggle)
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        addView(row)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
