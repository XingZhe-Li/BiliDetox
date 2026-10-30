import com.github.xingzheli.bilidetox.*
import java.util.TimeZone

/** 验证全局誓约不会遗漏任何开关，也不能通过旧页面或批量写入绕过。 */
fun main() {
    val now = 1_793_351_000_000L
    val until = now + 60_000
    val zone = TimeZone.getTimeZone("Asia/Shanghai")
    val pledged = ConfigData(timeLock = TimeLockConfig(enabled = true, editableAfter = until + 60_000, pledgeUntil = until))
    val edits: List<Pair<String, (ConfigData) -> ConfigData>> = listOf(
        "home.blankHome" to { it.copy(home = it.home.copy(blankHome = !it.home.blankHome)) },
        "home.showRecommendation" to { it.copy(home = it.home.copy(showRecommendation = !it.home.showRecommendation)) },
        "home.showPopular" to { it.copy(home = it.home.copy(showPopular = !it.home.showPopular)) },
        "home.showAnimation" to { it.copy(home = it.home.copy(showAnimation = !it.home.showAnimation)) },
        "home.showCinema" to { it.copy(home = it.home.copy(showCinema = !it.home.showCinema)) },
        "blockUpdate" to { it.copy(blockUpdate = !it.blockUpdate) },
        "hideRelatedVideos" to { it.copy(hideRelatedVideos = !it.hideRelatedVideos) },
        "hideComments" to { it.copy(hideComments = !it.hideComments) },
        "hideSearchPlaceholder" to { it.copy(hideSearchPlaceholder = !it.hideSearchPlaceholder) },
        "hideSearchSquares" to { it.copy(hideSearchSquares = !it.hideSearchSquares) },
        "strictSearchResults" to { it.copy(strictSearchResults = !it.strictSearchResults) },
    )
    fun rejects(current: ConfigData, next: ConfigData) {
        val error = runCatching { ConfigEditPolicy.prepareChange(current, next, now, zone) }.exceptionOrNull()
        check(error is IllegalArgumentException && error.message == ConfigEditPolicy.PLEDGE_RESTRICTION)
    }
    edits.forEach { (name, edit) ->
        rejects(pledged, edit(pledged))
        val opposite = edit(pledged)
        rejects(opposite, edit(opposite)) // 原先开启或关闭的开关都不可修改。
        val independent = pledged.copy(timeLock = pledged.timeLock.copy(enabled = false))
        rejects(independent, edit(independent)) // 无需启用时段锁，全局誓约仍冻结每个开关。
        check(ConfigEditPolicy.prepareChange(pledged, edit(pledged), until, zone) == edit(pledged)) { "$name must unlock at expiry" }
        val ordinary = pledged.copy(timeLock = pledged.timeLock.copy(pledgeUntil = 0))
        check(ConfigEditPolicy.prepareChange(ordinary, edit(ordinary), now, zone) == edit(ordinary)) { "ordinary lock must not freeze $name" }
    }
    rejects(pledged, pledged.copy(timeLock = pledged.timeLock.copy(enabled = false)))
    rejects(pledged, pledged.copy(timeLock = pledged.timeLock.copy(pledgeUntil = 0)))
    rejects(pledged, pledged.copy(timeLock = pledged.timeLock.copy(pledgeUntil = until + 60_000)))
    rejects(pledged, pledged.copy(timeLock = pledged.timeLock.copy(periods = listOf(DailyTimeRange(720, 780)))))
    rejects(pledged, ConfigData()) // 包括恢复默认或同时关闭誓约与改变开关。
    check(ConfigEditPolicy.prepareChange(pledged, pledged, now, zone) == pledged) // 无改动不写出不同状态。
    val off = ConfigData()
    val activating = off.copy(hideComments = true, timeLock = TimeLockConfig(enabled = true, pledgeUntil = until))
    val saved = ConfigEditPolicy.prepareChange(off, activating, now, zone)
    check(saved.hideComments && saved.timeLock.isPledged(now))
    rejects(saved, saved.copy(hideComments = false))
    println("ConfigEditPolicy: all home and feature switches, both directions, expiry and atomic bypass checks passed")
}
