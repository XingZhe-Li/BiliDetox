package com.github.xingzheli.bilidetox.hook

/**
 * 一个 hook 单元。
 *
 * 约定：
 * - hook() 抛出的异常由调用方捕获并记录，单个 hook 失败不影响其它 hook，
 *   更不能让宿主崩溃。
 * - 开关一律是运行时的（RuntimeConfig），hook 安装后每次调用点按需取值，
 *   设置对话框改动即时生效；因此这里不再有 enabled 概念。
 */
interface BaseHook {
    /** 用于日志的名字。 */
    val name: String

    fun hook()
}
