package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.PurifyConfig
import com.github.xingzheli.bilidetox.RuntimeConfig

/**
 * 「净化管理」子页：广告净化开关集中在这里。
 *
 * 只收广告相关的能力；「相关推荐」里的广告卡片不归这里——它随
 * RelatedVideosHook 的组件前缀过滤一起隐藏，由主界面的「视频页隐藏「相关推荐」」控制。
 */
class PurifySettingsView(private val activity: Activity) : LinearLayout(activity) {
    init { orientation = VERTICAL; render() }

    private fun render() {
        removeAllViews()
        val config = RuntimeConfig.get()
        val editable = !config.timeLock.isPledged(System.currentTimeMillis())
        addView(TextView(activity).apply {
            text = "广告相关的净化开关集中在这里，改动即时保存，回到视频页后生效。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })
        if (!editable) addView(TextView(activity).apply {
            text = "发誓锁定生效中，净化管理的全部选项也不可修改。"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })
        row(
            "详情页广告面板",
            "暂停后的广告弹层，以及播放器下方的推荐广告",
            config.purify.blockVideoDetailAdPanel,
            editable,
            { it.blockVideoDetailAdPanel },
        ) { purify, value -> purify.copy(blockVideoDetailAdPanel = value) }
        addView(TextView(activity).apply {
            text = "详情页「相关推荐」里的广告卡片不在这里控制，它随主界面的「视频页隐藏「相关推荐」」一起隐藏。"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun row(
        title: String,
        summary: String,
        value: Boolean,
        editable: Boolean,
        read: (PurifyConfig) -> Boolean,
        edit: (PurifyConfig, Boolean) -> PurifyConfig,
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
                    if (!RuntimeConfig.update { it.copy(purify = edit(it.purify, checked)) }) {
                        Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    }
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                } finally {
                    binding = true
                    isChecked = read(RuntimeConfig.get().purify)
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
