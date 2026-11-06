package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findMethodOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/** 过滤底栏最终装配列表，保留宿主缓存以便重新开启。基于 B站 9.6.0。 */
class BottomNavHook(private val classLoader: ClassLoader) : BaseHook {
    override val name = "BottomNav"

    override fun hook() {
        val factory = classLoader.loadClass("tv.danmaku.bili.ui.main2.V")
        val frame = classLoader.loadClass("tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment")
        val item = classLoader.loadClass("tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment\$o")
        val model = classLoader.loadClass("tv.danmaku.bili.ui.main2.resource.z")
        val manager = classLoader.loadClass("tv.danmaku.bili.ui.main2.resource.MainResourceManager")
        val cache = classLoader.loadClass("tv.danmaku.bili.ui.main2.resource.MainResourceManager\$a")

        val build = factory.findMethodOrNull("a") ?: error("未找到底栏列表构建方法")
        val onStart = frame.findMethodOrNull("onStart") ?: error("未找到底栏返回刷新入口")
        check(List::class.java.isAssignableFrom(build.returnType)) { "底栏列表返回类型不匹配" }
        val ownerField = factory.getDeclaredField("a").apply { isAccessible = true }
        val modelField = item.getDeclaredField("c").apply { isAccessible = true }
        val publishField = model.getDeclaredField("k").apply { isAccessible = true }
        val uriField = model.getDeclaredField("d").apply { isAccessible = true }
        val managerField = manager.getDeclaredField("q").apply { isAccessible = true }
        val cacheField = manager.getDeclaredField("c").apply { isAccessible = true }
        val dirtyField = cache.getDeclaredField("c").apply { isAccessible = true }
        val tabHostField = frame.getDeclaredField("n").apply { isAccessible = true }
        val pageManagerField = frame.getDeclaredField("s").apply { isAccessible = true }
        check(frame.isAssignableFrom(ownerField.type) && modelField.type == model &&
            publishField.type == Boolean::class.javaPrimitiveType && uriField.type == String::class.java &&
            dirtyField.type == Boolean::class.javaPrimitiveType) { "底栏模型字段类型不匹配" }

        // 只追踪已装配的 Fragment，避免持有 Activity。
        val applied = WeakHashMap<Any, Pair<Boolean, Boolean>>()
        XposedBridge.hookMethod(build, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (param.hasThrowable()) return
                    val original = param.result as? List<*> ?: return
                    val config = RuntimeConfig.get().bottomNav
                    val filtered = original.filter { entry ->
                        if (!item.isInstance(entry)) return@filter true
                        val tab = modelField.get(entry) ?: return@filter true
                        if (!model.isInstance(tab)) return@filter true
                        BottomNavPolicy.shouldShow(config.showPublish, config.showMall,
                            publishField.getBoolean(tab), uriField.get(tab) as? String)
                    }
                    // 宿主后续始终访问首项；遇到未知或异常配置时保留原列表。
                    if (filtered.isEmpty()) {
                        Log.w("底栏过滤结果为空，保留宿主原列表")
                        return
                    }
                    param.result = ArrayList(filtered)
                    val owner = ownerField.get(param.thisObject)
                    if (owner != null) synchronized(applied) {
                        applied[owner] = config.showPublish to config.showMall
                    }
                } catch (e: Throwable) {
                    Log.e("底栏过滤失败，保留宿主原列表", e)
                }
            }
        })
        XposedBridge.hookMethod(onStart, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val previous = synchronized(applied) { applied[param.thisObject] } ?: return
                    val config = RuntimeConfig.get().bottomNav
                    if (previous == (config.showPublish to config.showMall)) return
                    // onStart 的宿主刷新需要 TabHost 和页面管理器已创建。
                    if (tabHostField.get(param.thisObject) == null || pageManagerField.get(param.thisObject) == null) return
                    val managerObject = managerField.get(null) ?: return
                    val cacheObject = cacheField.get(managerObject) ?: return
                    dirtyField.setBoolean(cacheObject, true)
                } catch (e: Throwable) {
                    Log.e("底栏返回刷新失败", e)
                }
            }
        })
        Log.d("已安装底栏栏目开关")
    }
}
