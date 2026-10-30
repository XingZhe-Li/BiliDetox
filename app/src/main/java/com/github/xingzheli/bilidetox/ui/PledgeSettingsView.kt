package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.TimeLockConfig
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 独立的全局设置誓约，不改变每日时段锁的启用状态。 */
class PledgeSettingsView(private val activity: Activity) : LinearLayout(activity) {
    init { orientation = VERTICAL; render() }

    private fun render() {
        removeAllViews()
        val config = RuntimeConfig.get().timeLock
        val now = System.currentTimeMillis()
        addText("发誓锁 · ${if (config.isPledged(now)) "生效中" else "未启用"}", 19f)
        addText("发誓锁会冻结 BiliDetox 的全部可编辑选项，包括首页管理、所有功能开关、时段锁和誓约终止时间。到期前即使处于可用时段，也不能修改。")
        addText("发誓锁只冻结设置，不限制观看，也不会自动启用时段锁。需要限制使用时间，请先到「时段锁」设置好时段，再发誓锁定。")
        if (config.pledgeUntil > 0) {
            addText("${if (config.isPledged(now)) "锁定至" else "上次誓约已到期："} ${format(config.pledgeUntil)}")
        }
        addText("到期后自动恢复各项原有编辑规则；已启用的每日时段锁仍会继续生效。")
        addView(Button(activity).apply {
            SettingsTheme.styleButton(this)
            text = if (config.isPledged(now)) "发誓锁生效中" else "设置终止时间并发誓锁定…"
            isEnabled = !config.isPledged(now)
            setOnClickListener { pickPledge { until -> confirmPledge(until) } }
        })
    }

    private fun confirmPledge(until: Long) {
        AlertDialog.Builder(SettingsTheme.dialogContext(activity))
            .setTitle("确认发誓锁定")
            .setMessage("发誓锁定至 ${format(until)}。\n\n在此之前，BiliDetox 的所有可编辑选项都会锁定，包括主设置页的全部开关、时段和誓约终止时间。即使处于可用时段，也不能修改这些设置。\n\n仍可浏览设置，可用时段仍可正常使用 B 站。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认发誓锁定") { _, _ ->
                save { it.copy(pledgeUntil = until) }
            }.create().let { SettingsTheme.show(it) }
    }

    private fun pickPledge(onPicked: (Long) -> Unit) {
        val date = Calendar.getInstance().apply { add(Calendar.DATE, 1) }
        DatePickerDialog(SettingsTheme.dialogContext(activity), { _, year, month, day ->
            date.set(Calendar.YEAR, year)
            date.set(Calendar.MONTH, month)
            date.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(SettingsTheme.dialogContext(activity), { _, hour, minute ->
                date.set(Calendar.HOUR_OF_DAY, hour)
                date.set(Calendar.MINUTE, minute)
                date.set(Calendar.SECOND, 0)
                date.set(Calendar.MILLISECOND, 0)
                if (date.timeInMillis <= System.currentTimeMillis()) toast("请选择晚于当前时间的终止时间")
                else onPicked(date.timeInMillis)
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).apply {
                setTitle("发誓锁定终止时间")
            }.let { SettingsTheme.show(it) }
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("发誓锁定终止日期")
            datePicker.minDate = System.currentTimeMillis()
        }.let { SettingsTheme.show(it) }
    }

    private fun save(edit: (TimeLockConfig) -> TimeLockConfig) {
        try {
            if (RuntimeConfig.update { it.copy(timeLock = edit(it.timeLock)) }) toast("发誓锁设置已保存")
            else toast("保存失败，请重试；锁定状态未改变")
        } catch (e: IllegalArgumentException) { toast(e.message ?: "当前不能修改发誓锁") }
        render()
    }

    private fun addText(value: String, size: Float = 13f) {
        addView(TextView(activity).apply {
            text = value
            textSize = size
            setPadding(0, 8, 0, 8)
        })
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun format(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
}
