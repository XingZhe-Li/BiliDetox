package com.github.xingzheli.bilidetox.hook

/**
 * 一个 hook 单元。
 *
 * 约定：hook() 抛出的异常由调用方捕获并记录，单个 hook 失败不影响其它 hook，
 * 更不能让宿主崩溃。
 */
interface BaseHook {
    /** 用于日志的名字。 */
    val name: String

    /** 是否启用；来自 Config 的硬编码开关。 */
    val enabled: Boolean

    fun hook()
}
