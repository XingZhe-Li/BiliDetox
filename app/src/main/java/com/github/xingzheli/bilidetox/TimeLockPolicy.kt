package com.github.xingzheli.bilidetox

import java.util.Calendar
import java.util.TimeZone

/** 每天重复的半开区间；结束早于开始表示跨午夜，不接受全天锁。 */
data class DailyTimeRange(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439) { "时间必须在 00:00–23:59 之间" }
        require(startMinute != endMinute) { "开始与结束时间不能相同" }
    }

    fun contains(minute: Int): Boolean = if (startMinute < endMinute) {
        minute >= startMinute && minute < endMinute
    } else {
        minute >= startMinute || minute < endMinute
    }

    fun label(): String = "${clock(startMinute)}–${if (endMinute < startMinute) "次日 " else ""}${clock(endMinute)}"

    companion object {
        val NIGHT = DailyTimeRange(23 * 60, 6 * 60)
        fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

data class TimeLockConfig(
    val enabled: Boolean = false,
    val periods: List<DailyTimeRange> = listOf(DailyTimeRange.NIGHT),
    /** 普通锁启用次日的本地零点；还须同时处于可用时段。 */
    val editableAfter: Long = 0,
    /** 绝对时间戳；到期只解除誓约，不自动关闭每日重复的时段锁。 */
    val pledgeUntil: Long = 0,
) {
    fun isPledged(now: Long): Boolean = enabled && pledgeUntil > now
}

/** 不依赖 Android，设置保存和拦截页面共用同一套规则。 */
object TimeLockPolicy {
    fun isBlocked(config: TimeLockConfig, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (!config.enabled) return false
        val calendar = calendar(now, zone)
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return config.periods.any { it.contains(minute) }
    }

    fun editRestriction(config: TimeLockConfig, now: Long, zone: TimeZone = TimeZone.getDefault()): String? = when {
        !config.enabled -> null
        config.isPledged(now) -> "发誓锁定尚未到期，BiliDetox 的所有设置均不可修改。"
        isBlocked(config, now, zone) -> "时段锁生效中，请等到可用时段再操作。"
        now < config.editableAfter -> "普通锁启用后，最早可在次日的可用时段关闭或修改。"
        else -> null
    }

    fun validate(periods: List<DailyTimeRange>) {
        require(periods.isNotEmpty()) { "请至少保留一个锁定时段" }
        require((0..1439).any { minute -> periods.none { it.contains(minute) } }) {
            "这些时段合起来覆盖了全天，请至少留出一个可用时段"
        }
    }

    /** 所有写入均从此处校验，不能通过重开设置或修改时段绕过誓约。 */
    fun prepareChange(
        current: TimeLockConfig,
        proposed: TimeLockConfig,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): TimeLockConfig {
        if (current == proposed) return current
        validate(proposed.periods)
        // 普通锁在启用当天仍可追加誓约；除此之外按正常编辑限制处理。
        val addingPledge = current.enabled && !current.isPledged(now) &&
            proposed == current.copy(pledgeUntil = proposed.pledgeUntil) && proposed.pledgeUntil > now
        if (!addingPledge) {
            editRestriction(current, now, zone)?.let { throw IllegalArgumentException(it) }
        }
        if (proposed.pledgeUntil != current.pledgeUntil && proposed.pledgeUntil != 0L) {
            require(proposed.enabled && proposed.pledgeUntil > now) { "发誓锁定的终止时间必须晚于当前时间" }
        }
        return when {
            !proposed.enabled -> proposed.copy(editableAfter = 0, pledgeUntil = 0)
            !current.enabled -> proposed.copy(editableAfter = nextDayStart(now, zone))
            else -> proposed.copy(editableAfter = current.editableAfter)
        }
    }

    /** 返回所有重叠、相邻时段合并后的首次可用时刻。 */
    fun nextAvailableAt(config: TimeLockConfig, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        if (!isBlocked(config, now, zone)) return now
        val cursor = calendar(now, zone).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 按实际分钟推进，也覆盖夏令时切换造成的重复或跳过时刻。
        repeat(48 * 60) {
            cursor.add(Calendar.MINUTE, 1)
            val minute = cursor.get(Calendar.HOUR_OF_DAY) * 60 + cursor.get(Calendar.MINUTE)
            if (config.periods.none { it.contains(minute) }) return cursor.timeInMillis
        }
        error("锁定时段没有可用时间")
    }

    private fun nextDayStart(now: Long, zone: TimeZone): Long = calendar(now, zone).apply {
        add(Calendar.DATE, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun calendar(now: Long, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply { timeInMillis = now }
}
