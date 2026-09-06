package com.github.xingzheli.bilidetox

import com.github.xingzheli.bilidetox.hook.BaseHook
import com.github.xingzheli.bilidetox.hook.BlockUpdateHook
import com.github.xingzheli.bilidetox.hook.HomeTabHook
import com.github.xingzheli.bilidetox.hook.SettingsEntryHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 模块入口。
 *
 * 注册于 assets/xposed_init。
 */
class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != Config.BILIBILI_PACKAGE) return

        // 只在主进程生效。
        //
        // B 站有 :web / :download / :push 等多个子进程，它们不会加载首页，
        // 也不跑更新检查；在那些进程里 hook 纯属浪费，还会让日志变噪。
        // 主进程的 processName 恰好等于包名，带冒号的都是子进程。
        if (lpparam.processName != Config.BILIBILI_PACKAGE) return

        Log.d("已注入 ${lpparam.packageName} (${lpparam.processName})")

        // 初始化运行时配置（JSON 放在宿主数据目录，设置对话框直写）。
        // Xposed api 82 的 LoadPackageParam 没有 appDataDir，主进程数据目录
        // 固定为 /data/user/0/<pkg>，多用户场景暂不支持。
        RuntimeConfig.init(null)
        Log.d("当前配置: ${RuntimeConfig.get()}")

        val hooks = listOf<BaseHook>(
            HomeTabHook(lpparam.classLoader),
            BlockUpdateHook(lpparam.classLoader),
            SettingsEntryHook(lpparam.classLoader),
        )

        // 逐个 try：一个 hook 失败不应影响其它 hook，更不能让宿主崩溃。
        for (hook in hooks) {
            try {
                hook.hook()
            } catch (e: Throwable) {
                Log.e("${hook.name} 安装失败", e)
            }
        }
    }
}
