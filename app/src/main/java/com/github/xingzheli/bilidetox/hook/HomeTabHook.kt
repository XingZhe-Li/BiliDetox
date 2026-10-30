package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.HomeConfig
import com.github.xingzheli.bilidetox.HomeTabPolicy
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findMethodOrNull
import com.github.xingzheli.bilidetox.ui.BlankHomePage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 在分页创建之前选择首页模式，空白模式完全不创建原生信息流 Fragment。 */
class HomeTabHook(private val classLoader: ClassLoader) : BaseHook {
    override val name = "HomeTab"
    private val applied = WeakHashMap<Any, HomeConfig>()

    override fun hook() {
        val model = classLoader.loadClass("tv.danmaku.bili.ui.main2.resource.A")
        val iface = classLoader.loadClass("tv.danmaku.bili.ui.main2.resource.i")
        val getUri = iface.getMethod("getUri")
        val getDefault = iface.getMethod("getDefaultSelected")
        val setDefault = iface.getMethod("setDefaultSelected", Boolean::class.javaPrimitiveType)
        val fields = model.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        BlankHomePage.install(classLoader)

        fun filter(raw: Any?, config: HomeConfig): Any? {
            val list = raw as? List<*> ?: return raw
            Log.d("首页栏目识别：" + list.filter { iface.isInstance(it) }.joinToString { item ->
                val uri = getUri.invoke(item) as? String
                "${HomeTabPolicy.kind(uri)}=${uri?.substringBefore('?')}(${if (HomeTabPolicy.shouldShow(config, uri)) "显示" else "隐藏"})"
            })
            val kept = list.filter { !iface.isInstance(it) || HomeTabPolicy.shouldShow(config, getUri.invoke(it) as? String) }
                .map { item ->
                    // 模型来自共享缓存；复制后再规范默认选中标记，避免污染切换后的原始列表。
                    if (model.isInstance(item)) XposedHelpers.newInstance(model).also { copy ->
                        fields.forEach { it.set(copy, it.get(item)) }
                    } else item
                }
            val selected = kept.indexOfFirst { iface.isInstance(it) && getDefault.invoke(it) == true }.let { if (it < 0) 0 else it }
            kept.forEachIndexed { index, item -> if (iface.isInstance(item)) setDefault.invoke(item, index == selected) }
            return ArrayList(kept)
        }

        // 保留宿主云端缓存的完整模型，只在最终装配时过滤。
        // 否则关闭过的栏目会从缓存消失，重新开启时必须等待服务端刷新。
        val home = classLoader.loadClass("tv.danmaku.bili.ui.main2.HomeFragmentV2")
        val buildPages = home.findMethodOrNull("Kf", List::class.java)
            ?: error("未找到 HomeFragmentV2.Kf")
        XposedBridge.hookMethod(buildPages, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = safely {
                val config = RuntimeConfig.get().home
                param.args[0] = if (config.blankHome) ArrayList<Any>() else filter(param.args[0], config)
            }

            override fun afterHookedMethod(param: MethodHookParam) = safely {
                if (param.hasThrowable()) return@safely
                val config = RuntimeConfig.get().home
                if (config.blankHome || (param.result as? List<*>)?.isEmpty() == true) {
                    param.result = arrayListOf(BlankHomePage.descriptor(classLoader))
                    XposedHelpers.setIntField(param.thisObject, "I", 0)
                    val viewModel = XposedHelpers.getObjectField(param.thisObject, "U")
                    if (viewModel != null) XposedHelpers.callMethod(viewModel, "onDefaultTabIndexUpdate", BlankHomePage.URI)
                    Log.d("导航页已创建，未装配原生信息流页面")
                }
                synchronized(applied) { applied[param.thisObject] = config }
            }
        })
        // 设置页返回首页时，按新配置重建；已有配置保持不动，避免重复创建分页。
        val refresh = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = safely {
                val previous = synchronized(applied) { applied[param.thisObject] }
                if (previous != null && previous != RuntimeConfig.get().home) {
                    XposedHelpers.callMethod(param.thisObject, "Hf")
                }
            }
        }
        home.findMethodOrNull("onResume")?.let { XposedBridge.hookMethod(it, refresh) }
        XposedBridge.hookAllMethods(home, "onFragmentShow", refresh)
        Log.d("已安装首页管理：空白模式与独立栏目开关")
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Throwable) { Log.e("首页管理处理失败，保留宿主页面", e) }
    }
}
