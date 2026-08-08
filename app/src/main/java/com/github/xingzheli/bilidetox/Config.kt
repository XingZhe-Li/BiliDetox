package com.github.xingzheli.bilidetox

/**
 * 硬编码配置。
 *
 * 目前没有设置界面，所有开关在这里改。修改后重新编译安装模块即可。
 */
object Config {
    /** 目标应用包名 */
    const val BILIBILI_PACKAGE = "tv.danmaku.bili"

    /** 是否移除首页顶部的推荐类 Tab */
    const val REMOVE_HOME_TABS = true

    /** 是否禁用应用内更新检查 */
    const val BLOCK_UPDATE = true

    /**
     * 需要从首页顶栏移除的 Tab uri。
     *
     * 这些值来自 9.6.0 的 tv.danmaku.bili.ui.main2.resource.C0553a#a()：
     *   "24" bilibili://pegasus/promo             推荐（默认选中页）
     *   "27" bilibili://pegasus/hottopic          热门
     *   "20" bilibili://live/home                 直播
     *   "30" bilibili://pgc/home                  番剧
     *   "13" bilibili://pgc/home?home_flow_type=2 影视
     *
     * 匹配采用「忽略 query 与 fragment，只比较 scheme+host+path」的方式。
     *
     * 注意：正因为忽略 query，"bilibili://pgc/home" 会同时命中番剧和影视
     * （影视只是多了 ?home_flow_type=2）。若日后想单独移除其中一个，
     * 需要改 uriMatches 的比较策略，不能只往这个集合里加条目。
     * 当前集合里的 promo / hottopic 不存在这种歧义。
     */
    val REMOVED_TAB_URIS = setOf(
        "bilibili://pegasus/promo",
        "bilibili://pegasus/hottopic",
    )

    /**
     * 过滤后必须保留的最小 Tab 数量。
     *
     * MainResourceManager 在拿到云端数据后有一步校验（9.6.0 反编译行号约 1066）：
     *     if (bottomList.size() < 1 || tabList.size() < 1) { 走失败回调 }
     * 一旦 Tab 被清空，B 站会认为数据无效并回退到硬编码兜底列表，
     * 结果是推荐 Tab 又冒出来了。所以过滤时必须留底。
     */
    const val MIN_KEPT_TABS = 1
}
