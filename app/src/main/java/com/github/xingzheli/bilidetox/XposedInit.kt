package com.github.xingzheli.bilidetox

import com.github.xingzheli.bilidetox.hook.BaseHook
import com.github.xingzheli.bilidetox.hook.BlockUpdateHook
import com.github.xingzheli.bilidetox.hook.HomeTabHook
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

        val hooks = listOf<BaseHook>(
            HomeTabHook(lpparam.classLoader),
            BlockUpdateHook(lpparam.classLoader),
        )

        for (hook in hooks) {
            if (!hook.enabled) {
                Log.d("${hook.name} 已在配置中禁用，跳过")
                continue
            }
            // 逐个 try：一个 hook 失败不应影响其它 hook，更不能让宿主崩溃。
            try {
                hook.hook()
            } catch (e: Throwable) {
                Log.e("${hook.name} 安装失败", e)
            }
        }
    }
}
