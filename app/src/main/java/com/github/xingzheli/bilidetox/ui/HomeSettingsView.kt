package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.HomeConfig
import com.github.xingzheli.bilidetox.RuntimeConfig

class HomeSettingsView(private val activity: Activity) : LinearLayout(activity) {
    init { orientation = VERTICAL; render() }

    private fun render() {
        removeAllViews()
        val config = RuntimeConfig.get()
        val editable = !config.timeLock.isPledged(System.currentTimeMillis())
        addView(TextView(activity).apply {
            text = "导航页提供搜索框，以及关注、离线缓存、历史记录、我的收藏、稍后再看的图标入口，不加载直播或其他信息流。关闭导航页后，下方开关控制原生首页的栏目显示。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })
        if (!editable) addView(TextView(activity).apply {
            text = "发誓锁定生效中，首页管理的全部选项也不可修改。"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })
        row("使用导航页", "搜索框与常用入口，不加载信息流", config.home.blankHome, editable,
            { it.blankHome }) { home, value -> home.copy(blankHome = value) }
        row("显示推荐", "独立控制首页的推荐栏目", config.home.showRecommendation, editable,
            { it.showRecommendation }) { home, value -> home.copy(showRecommendation = value) }
        row("显示热门", "独立控制首页的热门栏目", config.home.showPopular, editable,
            { it.showPopular }) { home, value -> home.copy(showPopular = value) }
        row("显示直播", "关闭后不创建首页直播分页", config.home.showLive, editable,
            { it.showLive }) { home, value -> home.copy(showLive = value) }
        row("显示动画", "包含原生动画／番剧栏目", config.home.showAnimation, editable,
            { it.showAnimation }) { home, value -> home.copy(showAnimation = value) }
        row("显示影视", "独立控制影视，不影响动画开关", config.home.showCinema, editable,
            { it.showCinema }) { home, value -> home.copy(showCinema = value) }
        addView(TextView(activity).apply {
            text = "设置即时保存；返回首页时自动重建。栏目设置会保留，关闭导航页后生效。"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun row(title: String, summary: String, value: Boolean, editable: Boolean,
        read: (HomeConfig) -> Boolean, edit: (HomeConfig, Boolean) -> HomeConfig) {
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
                    if (!RuntimeConfig.update { it.copy(home = edit(it.home, checked)) }) {
                        Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    }
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                } finally {
                    binding = true
                    isChecked = read(RuntimeConfig.get().home)
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
