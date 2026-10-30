package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.DailyTimeRange
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.TimeLockConfig
import com.github.xingzheli.bilidetox.TimeLockPolicy
import java.text.SimpleDateFormat
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
        addText("普通锁最早在启用次日的可用时段关闭。需要冻结所有设置时，请返回主设置进入「发誓锁」。")
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
        addView(button(if (config.enabled) "关闭时段锁" else "启用时段锁", canEdit) {
            if (RuntimeConfig.get().timeLock.enabled) {
                save { it.copy(enabled = false) }
            } else confirmEnable()
        })
    }

    private fun confirmEnable() {
        AlertDialog.Builder(SettingsTheme.dialogContext(activity))
            .setTitle("启用时段锁")
            .setMessage("最早在次日的可用时段关闭或修改。在锁定时段内启用会立即限制使用。发誓锁定请前往独立的「发誓锁」设置页。")
            .setNegativeButton("取消", null)
            .setPositiveButton("启用") { _, _ -> save { it.copy(enabled = true) } }
            .create().let { SettingsTheme.show(it) }
    }

    private fun editPeriod(index: Int?, period: DailyTimeRange) {
        TimePickerDialog(SettingsTheme.dialogContext(activity), { _, startHour, startMinute ->
            TimePickerDialog(SettingsTheme.dialogContext(activity), { _, endHour, endMinute ->
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
