package com.github.xingzheli.bilidetox

import com.github.xingzheli.bilidetox.hook.BaseHook
import com.github.xingzheli.bilidetox.hook.BottomNavHook
import com.github.xingzheli.bilidetox.hook.BlockUpdateHook
import com.github.xingzheli.bilidetox.hook.CommentsHook
import com.github.xingzheli.bilidetox.hook.DetailAdPanelHook
import com.github.xingzheli.bilidetox.hook.DetailUnderPlayerAdHook
import com.github.xingzheli.bilidetox.hook.HomeTabHook
import com.github.xingzheli.bilidetox.hook.PlayerRecommendationsHook
import com.github.xingzheli.bilidetox.hook.RelatedVideosHook
import com.github.xingzheli.bilidetox.hook.SearchPlaceholderHook
import com.github.xingzheli.bilidetox.hook.SearchSquaresHook
import com.github.xingzheli.bilidetox.hook.SearchDiagnosticsHook
import com.github.xingzheli.bilidetox.hook.SearchResultsHook
import com.github.xingzheli.bilidetox.hook.SettingsEntryHook
import com.github.xingzheli.bilidetox.hook.TimeLockHook
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

        RuntimeConfig.init(lpparam.appInfo?.dataDir)
        // 时段锁覆盖宿主所有进程里的 Activity（包含独立 web 页面）。
        try {
            TimeLockHook().hook()
        } catch (e: Throwable) {
            Log.e("TimeLock 安装失败", e)
        }

        // 只在主进程生效。
        //
        // B 站有 :web / :download / :push 等多个子进程，它们不会加载首页，
        // 也不跑更新检查；在那些进程里 hook 纯属浪费，还会让日志变噪。
        // 主进程的 processName 恰好等于包名，带冒号的都是子进程。
        if (lpparam.processName != Config.BILIBILI_PACKAGE) return

        Log.d("已注入 ${lpparam.packageName} (${lpparam.processName})")

        Log.d("当前配置: ${RuntimeConfig.get()}")

        val hooks = mutableListOf<BaseHook>(
            BottomNavHook(lpparam.classLoader),
            HomeTabHook(lpparam.classLoader),
            BlockUpdateHook(lpparam.classLoader),
            SettingsEntryHook(lpparam.classLoader),
            RelatedVideosHook(lpparam.classLoader),
            DetailAdPanelHook(lpparam.classLoader),
            DetailUnderPlayerAdHook(lpparam.classLoader),
            PlayerRecommendationsHook(lpparam.classLoader),
            CommentsHook(lpparam.classLoader),
            SearchPlaceholderHook(lpparam.classLoader),
            SearchSquaresHook(lpparam.classLoader),
            SearchResultsHook(lpparam.classLoader),
        )
        if (BuildConfig.SEARCH_DIAGNOSTICS) hooks.add(SearchDiagnosticsHook(lpparam.classLoader))

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
