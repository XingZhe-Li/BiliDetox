package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.DailyTimeRange
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.TimeLockConfig
import com.github.xingzheli.bilidetox.TimeLockPolicy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 与宿主资源无关的时段锁设置；每次保存都重新检查当前时间及誓约。 */
class TimeLockSettingsView(private val activity: Activity) : LinearLayout(activity) {
    init {
        orientation = VERTICAL
        render()
    }

    private fun render() {
        removeAllViews()
        val config = RuntimeConfig.get().timeLock
        val now = System.currentTimeMillis()
        addText("时段锁 · ${if (config.enabled) "已启用" else "已禁用"}", 19f)
        addText("每天重复，可设置多个时段；结束早于开始表示跨午夜。默认夜间 23:00–次日 06:00。")
        addText("普通锁最早在启用次日的可用时段关闭。发誓锁定会冻结 BiliDetox 的所有可编辑选项，包括主设置页的全部开关；到期后每日时段锁仍然有效。")
        if (config.pledgeUntil > 0) {
            addText("发誓锁定${if (config.isPledged(now)) "至" else "已到期："} ${format(config.pledgeUntil)}")
        }
        TimeLockPolicy.editRestriction(config, now)?.let { addText(it) }
        val canEdit = TimeLockPolicy.editRestriction(config, now) == null
        config.periods.forEachIndexed { index, period ->
            val row = LinearLayout(activity).apply { orientation = HORIZONTAL }
            row.addView(TextView(activity).apply {
                text = period.label()
                textSize = 16f
                setPadding(0, 16, 0, 16)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("编辑", canEdit) { editPeriod(index, period) })
            row.addView(button("删除", canEdit) {
                save { current -> current.copy(periods = current.periods.filterIndexed { i, _ -> i != index }) }
            })
            addView(row)
        }
        addView(button("添加时段", canEdit) { editPeriod(null, DailyTimeRange(12 * 60, 13 * 60)) })
        addView(button("恢复夜间预设（23:00–次日 06:00）", canEdit) {
            save { it.copy(periods = listOf(DailyTimeRange.NIGHT)) }
        })
        addView(button(if (config.enabled) "关闭时段锁" else "启用时段锁", !config.enabled || canEdit) {
            if (RuntimeConfig.get().timeLock.enabled) {
                save { it.copy(enabled = false) }
            } else confirmEnable()
        })
        if (config.enabled && !config.isPledged(now)) {
            addView(button("发誓锁定…") { pickPledge { until -> confirmPledge(until, false) } })
        }
    }

    private fun confirmEnable() {
        val pledge = CheckBox(activity).apply {
            text = "同时启用发誓锁定（需指定终止时间）"
            isChecked = false
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(activity)
            .setTitle("启用时段锁")
            .setMessage("普通锁最早在次日的可用时段关闭或修改。在锁定时段内启用会立即限制使用。若同时发誓锁定，到期前 BiliDetox 的所有设置开关和时段都不能修改。")
            .setView(pledge)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                if (pledge.isChecked) pickPledge { until -> confirmPledge(until, true) }
                else save { it.copy(enabled = true, pledgeUntil = 0) }
            }.create().let { SettingsTheme.show(it) }
    }

    private fun confirmPledge(until: Long, enable: Boolean) {
        AlertDialog.Builder(activity)
            .setTitle("确认发誓锁定")
            .setMessage("发誓锁定至 ${format(until)}。\n\n在此之前，BiliDetox 的所有可编辑选项都会锁定，包括主设置页的全部开关、时段和誓约终止时间。即使处于可用时段，也不能修改这些设置。\n\n仍可浏览设置，可用时段仍可正常使用 B 站。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认发誓锁定") { _, _ ->
                save { it.copy(enabled = if (enable) true else it.enabled, pledgeUntil = until) }
            }.create().let { SettingsTheme.show(it) }
    }

    private fun pickPledge(onPicked: (Long) -> Unit) {
        val date = Calendar.getInstance().apply { add(Calendar.DATE, 1) }
        DatePickerDialog(activity, { _, year, month, day ->
            date.set(Calendar.YEAR, year)
            date.set(Calendar.MONTH, month)
            date.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(activity, { _, hour, minute ->
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

    private fun editPeriod(index: Int?, period: DailyTimeRange) {
        TimePickerDialog(activity, { _, startHour, startMinute ->
            TimePickerDialog(activity, { _, endHour, endMinute ->
                try {
                    val next = DailyTimeRange(startHour * 60 + startMinute, endHour * 60 + endMinute)
                    save { current ->
                        val periods = current.periods.toMutableList()
                        if (index == null) periods.add(next) else periods[index] = next
                        current.copy(periods = periods.distinct().sortedBy { it.startMinute })
                    }
                } catch (e: IllegalArgumentException) { toast(e.message ?: "时段无效") }
            }, period.endMinute / 60, period.endMinute % 60, true).apply { setTitle("锁定结束时间（可跨午夜）") }.let { SettingsTheme.show(it) }
        }, period.startMinute / 60, period.startMinute % 60, true).apply { setTitle("锁定开始时间") }.let { SettingsTheme.show(it) }
    }

    private fun save(edit: (TimeLockConfig) -> TimeLockConfig) {
        try {
            if (RuntimeConfig.update { it.copy(timeLock = edit(it.timeLock)) }) toast("时段锁设置已保存")
            else toast("保存失败，请重试；锁定状态未改变")
        } catch (e: IllegalArgumentException) { toast(e.message ?: "当前不能修改时段锁") }
        render()
    }

    private fun button(label: String, enabled: Boolean = true, action: () -> Unit): Button = Button(activity).apply {
        SettingsTheme.styleButton(this)
        text = label
        isEnabled = enabled
        setOnClickListener { action() }
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
