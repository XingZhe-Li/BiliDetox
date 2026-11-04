package com.github.xingzheli.bilidetox

/**
 * 「净化管理」子菜单的配置。
 *
 * 只放广告相关的净化开关；「相关推荐」里的广告卡片随 RelatedVideosHook 的
 * 组件前缀过滤一起隐藏，不在这里单独控制。
 *
 * 默认开启：广告拦截是本模块的既定行为，用户可在设置里关掉恢复原版。
 */
data class PurifyConfig(
    /** 详情页广告面板：暂停页广告弹层 + 播放器下方推荐广告，都由 AdPanelRepository#showPanel 分发。 */
    val blockVideoDetailAdPanel: Boolean = true,
)
