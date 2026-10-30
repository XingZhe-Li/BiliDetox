package com.github.xingzheli.bilidetox

import java.net.URI
import java.net.URLDecoder

data class HomeConfig(
    val blankHome: Boolean = true,
    val showRecommendation: Boolean = false,
    val showPopular: Boolean = false,
    val showAnimation: Boolean = true,
    val showCinema: Boolean = true,
) {
    companion object {
        /** 兼容旧的「移除推荐/热门」总开关。空白首页是新版默认落地页。 */
        fun fromLegacy(removeHomeTabs: Boolean) = HomeConfig(
            showRecommendation = !removeHomeTabs,
            showPopular = !removeHomeTabs,
        )
    }
}

/** 区分动画与影视时保留 home_flow_type；其他埋点参数不影响匹配。 */
object HomeTabPolicy {
    enum class Kind { RECOMMENDATION, POPULAR, ANIMATION, CINEMA, OTHER }

    fun kind(value: String?): Kind = try {
        val uri = URI(value ?: "")
        val target = "${uri.host.orEmpty().lowercase()}${uri.path.orEmpty()}"
        if (!uri.scheme.equals("bilibili", ignoreCase = true)) Kind.OTHER else when (target) {
            "pegasus/promo" -> Kind.RECOMMENDATION
            "pegasus/hottopic" -> Kind.POPULAR
            "pgc/home" -> {
                val flow = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                    .firstOrNull { URLDecoder.decode(it[0], "UTF-8") == "home_flow_type" }
                    ?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
                if (flow == "2") Kind.CINEMA else Kind.ANIMATION
            }
            else -> Kind.OTHER
        }
    } catch (_: Exception) { Kind.OTHER }

    fun shouldShow(config: HomeConfig, uri: String?): Boolean = when (kind(uri)) {
        Kind.RECOMMENDATION -> config.showRecommendation
        Kind.POPULAR -> config.showPopular
        Kind.ANIMATION -> config.showAnimation
        Kind.CINEMA -> config.showCinema
        Kind.OTHER -> true
    }
}
