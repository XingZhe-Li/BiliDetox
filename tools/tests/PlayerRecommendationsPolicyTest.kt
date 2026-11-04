import com.github.xingzheli.bilidetox.ConfigData
import com.github.xingzheli.bilidetox.hook.PlayerRecommendationsPolicy

fun main() {
    val off = ConfigData()
    check(!off.hideRelatedVideos)
    val on = off.copy(hideRelatedVideos = true)
    check(PlayerRecommendationsPolicy.shouldSuppress(on))
    check(!PlayerRecommendationsPolicy.shouldSuppress(off))

    val raw = listOf<Any?>("av1", null, 2)
    val filtered = PlayerRecommendationsPolicy.filterList(on, raw)
    check(filtered != null && filtered.isEmpty())
    // 关闭时必须是同一引用：hook 靠 === 决定是否替换入参，拷贝成新列表等于改了数据却没生效。
    check(PlayerRecommendationsPolicy.filterList(off, raw) === raw)

    // null 是「本就没有数据」，两种开关下都原样返回，不能变成空列表掩盖数据缺失。
    check(PlayerRecommendationsPolicy.filterList(off, null) == null)
    check(PlayerRecommendationsPolicy.filterList(on, null) == null)

    // 空列表在关闭时同样不能被替换成新实例，否则上游的 === 判断与引用缓存都会失效。
    val empty = emptyList<Any?>()
    check(PlayerRecommendationsPolicy.filterList(off, empty) === empty)

    // 开关只读 config 快照：同一份 raw 在两种 config 下结果不同，且 raw 自身不被修改。
    check(PlayerRecommendationsPolicy.filterList(on, raw) !== raw)
    check(PlayerRecommendationsPolicy.filterList(off, raw) === raw)
    check(raw.size == 3)
    check(raw[0] == "av1" && raw[1] == null && raw[2] == 2)

    println("PlayerRecommendationsPolicy: switch gating, identity passthrough, null/empty raw and config snapshot checks passed")
}
