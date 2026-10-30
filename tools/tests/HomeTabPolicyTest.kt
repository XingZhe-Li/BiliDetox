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
    listOf(null, "", "bad uri %", "bilibili://live/home", "bilibili://future/tab", "https://pgc/home?home_flow_type=2").forEach {
        check(HomeTabPolicy.kind(it) == Kind.OTHER)
        check(HomeTabPolicy.shouldShow(config, it))
    }
    println("HomeTabPolicy: independent visibility, cinema query, legacy migration and unknown-route checks passed")
}
