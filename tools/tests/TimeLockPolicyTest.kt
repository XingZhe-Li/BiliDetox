import com.github.xingzheli.bilidetox.*
import java.text.SimpleDateFormat
import java.util.TimeZone

/** 运行方式见 docs/TIME_LOCK.md；无需设备或 Android 运行时。 */
fun main() {
    val zone = TimeZone.getTimeZone("Asia/Shanghai")
    fun time(value: String, tz: TimeZone = zone): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply { timeZone = tz }.parse(value)!!.time
    fun blocked(config: TimeLockConfig, value: String) = TimeLockPolicy.isBlocked(config, time(value), zone)
    fun rejected(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IllegalArgumentException)
    }
    val off = TimeLockConfig()
    check(!blocked(off, "2026-10-30 23:30:00"))
    val night = off.copy(enabled = true)
    check(!blocked(night, "2026-10-30 22:59:59"))
    check(blocked(night, "2026-10-30 23:00:00"))
    check(blocked(night, "2026-10-31 00:00:00"))
    check(blocked(night, "2026-10-31 05:59:59"))
    check(!blocked(night, "2026-10-31 06:00:00"))
    check(TimeLockPolicy.nextAvailableAt(night, time("2026-10-30 23:40:21"), zone) == time("2026-10-31 06:00:00"))
    val multiple = night.copy(periods = listOf(DailyTimeRange.NIGHT, DailyTimeRange(12 * 60, 13 * 60)))
    check(blocked(multiple, "2026-10-30 12:00:00"))
    check(!blocked(multiple, "2026-10-30 13:00:00"))
    check(!blocked(multiple, "2026-10-30 20:00:00"))
    val overlap = night.copy(periods = listOf(DailyTimeRange.NIGHT, DailyTimeRange(5 * 60, 7 * 60), DailyTimeRange(7 * 60, 8 * 60)))
    check(TimeLockPolicy.nextAvailableAt(overlap, time("2026-10-30 23:40:21"), zone) == time("2026-10-31 08:00:00"))
    rejected { DailyTimeRange(60, 60) }
    rejected { DailyTimeRange(-1, 60) }
    rejected { DailyTimeRange(0, 1440) }
    rejected { TimeLockPolicy.validate(emptyList()) }
    rejected { TimeLockPolicy.validate(listOf(DailyTimeRange(0, 720), DailyTimeRange(720, 0))) }
    val enabled = TimeLockPolicy.prepareChange(off, night, time("2026-10-30 12:00:00"), zone)
    check(enabled.editableAfter == time("2026-10-31 00:00:00"))
    rejected { TimeLockPolicy.prepareChange(enabled, off, time("2026-10-30 12:01:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(enabled, enabled.copy(periods = multiple.periods), time("2026-10-30 12:01:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(enabled, off, time("2026-10-31 05:59:59"), zone) }
    check(!TimeLockPolicy.prepareChange(enabled, off, time("2026-10-31 06:00:00"), zone).enabled)
    val pledge = TimeLockPolicy.prepareChange(enabled, enabled.copy(pledgeUntil = time("2026-11-02 12:00:00")), time("2026-10-30 12:01:00"), zone)
    rejected { TimeLockPolicy.prepareChange(pledge, off, time("2026-10-31 12:00:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(pledge, pledge.copy(pledgeUntil = 0), time("2026-10-31 12:00:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(pledge, pledge.copy(periods = multiple.periods), time("2026-10-31 12:00:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(pledge, pledge.copy(pledgeUntil = time("2026-11-03 12:00:00")), time("2026-10-31 12:00:00"), zone) }
    check(TimeLockPolicy.prepareChange(pledge, pledge, time("2026-10-31 12:00:00"), zone) == pledge)
    check(TimeLockPolicy.editRestriction(pledge, time("2026-11-02 12:00:00"), zone) == null)
    val disabled = TimeLockPolicy.prepareChange(pledge, off, time("2026-11-02 12:00:00"), zone)
    check(!disabled.enabled && disabled.pledgeUntil == 0L && disabled.editableAfter == 0L)
    val endsAtNight = pledge.copy(pledgeUntil = time("2026-11-02 23:30:00"))
    rejected { TimeLockPolicy.prepareChange(endsAtNight, off, time("2026-11-02 23:30:00"), zone) }
    rejected { TimeLockPolicy.prepareChange(off, night.copy(pledgeUntil = time("2026-10-29 12:00:00")), time("2026-10-30 12:00:00"), zone) }
    val startWithPledge = TimeLockPolicy.prepareChange(off, night.copy(pledgeUntil = time("2026-11-02 12:00:00")), time("2026-10-30 12:00:00"), zone)
    check(startWithPledge.isPledged(time("2026-10-31 12:00:00")))
    check(!blocked(startWithPledge, "2026-10-31 12:00:00"))
    check(blocked(startWithPledge, "2026-11-03 23:30:00")) // 誓约到期不会关闭每日锁。
    val ny = TimeZone.getTimeZone("America/New_York")
    val dst = TimeLockPolicy.prepareChange(off, night, time("2026-03-08 00:30:00", ny), ny)
    check(dst.editableAfter == time("2026-03-09 00:00:00", ny)) // 不是固定加 24 小时。
    check(TimeLockPolicy.nextAvailableAt(night, time("2026-11-01 00:30:00", ny), ny) == time("2026-11-01 06:00:00", ny))
    println("TimeLockPolicy: all boundary, multi-period, editing, pledge and DST checks passed")
}
