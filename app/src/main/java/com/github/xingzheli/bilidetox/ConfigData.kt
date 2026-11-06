package com.github.xingzheli.bilidetox

import java.util.TimeZone

/** 底部导航默认沿用宿主的完整栏目。 */
data class BottomNavConfig(
    val showPublish: Boolean = true,
    val showMall: Boolean = true,
)

/** 配置的不可变快照；默认使用空白首页、隐藏推荐/热门及禁用更新。 */
data class ConfigData(
    val home: HomeConfig = HomeConfig(),
    val bottomNav: BottomNavConfig = BottomNavConfig(),
    val blockUpdate: Boolean = true,
    val hideRelatedVideos: Boolean = false,
    val hideComments: Boolean = false,
    val hideSearchPlaceholder: Boolean = false,
    val hideSearchSquares: Boolean = false,
    val strictSearchResults: Boolean = false,
    val purify: PurifyConfig = PurifyConfig(),
    val timeLock: TimeLockConfig = TimeLockConfig(),
)

/** 发誓锁定冻结整份配置；普通时段锁的编辑限制只针对时段设置。 */
object ConfigEditPolicy {
    const val PLEDGE_RESTRICTION = "发誓锁定尚未到期，BiliDetox 的所有设置均不可修改。"

    fun prepareChange(
        current: ConfigData,
        proposed: ConfigData,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): ConfigData {
        if (current == proposed) return current
        require(!current.timeLock.isPledged(now)) { PLEDGE_RESTRICTION }
        return proposed.copy(timeLock = TimeLockPolicy.prepareChange(current.timeLock, proposed.timeLock, now, zone))
    }
}
