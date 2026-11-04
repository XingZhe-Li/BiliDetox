package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.ConfigData

/**
 * 全屏播放器右侧推荐拉条的可见性规则。
 *
 * 刻意做成纯 Kotlin（不引 android.*、不依赖 Xposed）：桌面 JVM 单元测试可以
 * 直接引用本对象，不必起模拟器验证列表裁剪行为。
 */
object PlayerRecommendationsPolicy {

    /** 拉条与「相关推荐」同属一块内容，复用已有开关；不新增开关以免扩大配置面。 */
    fun shouldSuppress(config: ConfigData): Boolean = config.hideRelatedVideos

    /**
     * 裁剪宿主传入的相关推荐列表。
     *
     * 契约：**空列表 = 宿主不展示**。宿主在 `c(List)` 里按 `list.size > 3`
     * 决定拉条是否可见，空列表天然小于阈值，等价于「这条边栏不该出现」；
     * 而传 null 会让宿主遍历时直接 NPE，所以这里给的是空列表而不是 null。
     *
     * 关闭开关或 raw 为 null 时原样返回同一引用：宿主每次装配都会调用本方法，
     * 无谓拷贝会在全屏播放时反复产生垃圾。
     */
    fun filterList(config: ConfigData, raw: List<Any?>?): List<Any?>? {
        if (!shouldSuppress(config)) return raw
        // 已经是空列表时同样原样返回，保持引用不变。
        if (raw == null || raw.isEmpty()) return raw
        return emptyList()
    }
}
