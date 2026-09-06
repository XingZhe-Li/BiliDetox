package com.github.xingzheli.bilidetox.hook

import android.app.Activity
import android.content.Context
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import com.github.xingzheli.bilidetox.findUniqueMethodByNameOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 禁用应用内更新。
 *
 * 目标：tv.danmaku.bili.update.api.UpdateHelper
 *
 * 这个类在 9.6.0 里类名与方法名都没有被混淆（proguard 显然对它做了 keep），
 * 因此可以直接按签名 hook，不需要像 BiliRoaming 那样靠特征字符串
 * "Do sync http request." 去 dex 里搜混淆类（那个类名叫 wm1.c，每个版本都会变）。
 *
 * 三个入口都要堵：
 *   checkUpdateInStartup    —— 启动时自动检查，是弹窗的主要来源
 *   checkUpdateAndShowDialog —— 设置页里手动点「检查更新」
 *   getExistingForceUpdate  —— 读取已缓存的强制更新信息；不堵的话，
 *                              之前下载好的强更包仍会在启动时弹安装框
 *
 * 开关是运行时的（RuntimeConfig.blockUpdate）：before 阶段按需置空返回值，
 * 开关关闭时原方法照常执行，所以不能用 XC_MethodReplacement。
 */
class BlockUpdateHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "BlockUpdate"

    override fun hook() {
        val updateHelper = "tv.danmaku.bili.update.api.UpdateHelper".findClassOrNull(classLoader)
        if (updateHelper == null) {
            Log.w("未找到 UpdateHelper，禁用更新未生效")
            return
        }

        val iUpdater = "tv.danmaku.bili.update.api.updater.IUpdater".findClassOrNull(classLoader)
        if (iUpdater == null) {
            Log.w("未找到 IUpdater 接口，禁用更新未生效")
            return
        }

        var blocked = 0

        // 启动时检查。直接吞掉，不回调 IUpdater —— 调用方对「什么都没发生」是容忍的。
        updateHelper.findMethodOrNull("checkUpdateInStartup", Activity::class.java, iUpdater)
            ?.let { blocked += it.hookIfBlocked() }
            ?: Log.w("未找到 checkUpdateInStartup")

        // 手动检查更新。同样吞掉，用户点了会没反应，这正是我们想要的。
        updateHelper.findMethodOrNull("checkUpdateAndShowDialog", Context::class.java, iUpdater)
            ?.let { blocked += it.hookIfBlocked() }
            ?: Log.w("未找到 checkUpdateAndShowDialog")

        // 已缓存的强制更新。返回 null 而不是空 Task：
        // 调用方 checkUpdateInStartup 已经被我们干掉了，这里唯一的其它调用者
        // 也只是拿它判空，返回 null 是安全的。
        updateHelper.findMethodOrNull("getExistingForceUpdate", Activity::class.java)
            ?.let { blocked += it.hookIfBlocked() }
            ?: Log.w("未找到 getExistingForceUpdate")

        // 更新相关的埋点上报，顺手堵掉，避免无谓的后台任务。
        updateHelper.findUniqueMethodByNameOrNull("checkInternalUpdateFlag")
            ?.let { blocked += it.hookIfBlocked() }

        Log.d("禁用更新：已拦截 $blocked 个方法")
    }

    /** 挂上「配置开启时置空返回值」的 before hook。 */
    private fun java.lang.reflect.Method.hookIfBlocked(): Int {
        XposedBridge.hookMethod(
            this,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (RuntimeConfig.get().blockUpdate) {
                        param.result = null
                    }
                }
            },
        )
        Log.d("已拦截 $declaringClass.$name")
        return 1
    }
}
