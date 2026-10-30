import com.github.xingzheli.bilidetox.HomeConfig
import com.github.xingzheli.bilidetox.HomeTabPolicy
import com.github.xingzheli.bilidetox.HomeTabPolicy.Kind

fun main() {
    val config = HomeConfig()
    check(config.blankHome)
    check(HomeConfig.fromLegacy(true) == config)
    val legacyVisible = HomeConfig.fromLegacy(false)
    check(legacyVisible.showRecommendation && legacyVisible.showPopular && legacyVisible.blankHome)
    val recommend = "bilibili://pegasus/promo?spm_id_from=333.1007#top"
    val popular = "bilibili://pegasus/hottopic?source=home"
    val animation = "bilibili://pgc/home?home_flow_type=1"
    val cinema = "bilibili://pgc/home?spm=1&home_flow_type=2#tab"
    val live = "bilibili://live/home?source=home#top"
    // 9.6.0 MainResourceManager.c 中明确列出的新旧路由，不能只测试旧 pgc/home。
    val animationRoutes = listOf(animation, "bilibili://pgc/home", "bilibili://pgc/bangumi_v2?spm=home")
    val cinemaRoutes = listOf(cinema, "bilibili://pgc/cinema-tab?spm=home", "bilibili://pgc/cinema_v2?spm=home")
    animationRoutes.forEach { check(HomeTabPolicy.kind(it) == Kind.ANIMATION) }
    cinemaRoutes.forEach { check(HomeTabPolicy.kind(it) == Kind.CINEMA) }
    check(HomeTabPolicy.kind(live) == Kind.LIVE)
    check(HomeTabPolicy.shouldShow(config, live))
    val allHidden = config.copy(showLive = false, showAnimation = false, showCinema = false)
    (animationRoutes + cinemaRoutes + live).forEach { check(!HomeTabPolicy.shouldShow(allHidden, it)) }
    val onlyAnimation = allHidden.copy(showAnimation = true)
    animationRoutes.forEach { check(HomeTabPolicy.shouldShow(onlyAnimation, it)) }
    (cinemaRoutes + live).forEach { check(!HomeTabPolicy.shouldShow(onlyAnimation, it)) }
    val onlyCinema = allHidden.copy(showCinema = true)
    cinemaRoutes.forEach { check(HomeTabPolicy.shouldShow(onlyCinema, it)) }
    (animationRoutes + live).forEach { check(!HomeTabPolicy.shouldShow(onlyCinema, it)) }
    val onlyLive = allHidden.copy(showLive = true)
    check(HomeTabPolicy.shouldShow(onlyLive, live))
    (animationRoutes + cinemaRoutes).forEach { check(!HomeTabPolicy.shouldShow(onlyLive, it)) }
    check(HomeTabPolicy.kind(recommend) == Kind.RECOMMENDATION)
    check(HomeTabPolicy.kind(popular) == Kind.POPULAR)
    check(HomeTabPolicy.kind(animation) == Kind.ANIMATION)
    check(HomeTabPolicy.kind("bilibili://pgc/home") == Kind.ANIMATION)
    check(HomeTabPolicy.kind(cinema) == Kind.CINEMA)
    check(HomeTabPolicy.kind("bilibili://pgc/home?%68ome_flow_type=%32") == Kind.CINEMA)
    check(!HomeTabPolicy.shouldShow(config, recommend) && !HomeTabPolicy.shouldShow(config, popular))
    check(HomeTabPolicy.shouldShow(config, animation) && HomeTabPolicy.shouldShow(config, cinema))
    val noAnimation = config.copy(showAnimation = false)
    check(!HomeTabPolicy.shouldShow(noAnimation, animation) && HomeTabPolicy.shouldShow(noAnimation, cinema))
    val noCinema = config.copy(showCinema = false)
    check(HomeTabPolicy.shouldShow(noCinema, animation) && !HomeTabPolicy.shouldShow(noCinema, cinema))
    check(HomeTabPolicy.shouldShow(config.copy(showRecommendation = true), recommend))
    check(!HomeTabPolicy.shouldShow(config.copy(showRecommendation = true), popular))
    check(HomeTabPolicy.shouldShow(config.copy(showPopular = true), popular))
    check(!HomeTabPolicy.shouldShow(config.copy(showPopular = true), recommend))
    listOf(null, "", "bad uri %", "bilibili://future/tab", "https://pgc/home?home_flow_type=2").forEach {
        check(HomeTabPolicy.kind(it) == Kind.OTHER)
        check(HomeTabPolicy.shouldShow(config, it))
    }
    println("HomeTabPolicy: independent visibility, cinema query, legacy migration and unknown-route checks passed")
}
