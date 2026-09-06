package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 视频页隐藏「相关推荐」。
 *
 * ## 目标（基于 9.6.0 的反编译结果）
 *
 * 统一详情页（ViewUnite/theseus）的简介 Tab 是一个 RecyclerView，内容
 * 由 `com.bilibili.ship.theseus.united.page.intro.IntroRecycleViewService`
 * 持有与装配：
 *
 * - 构造器第 2 个参数是初始组件列表（由 IntroModuleListMapper
 *   `Iy0/j#a(IntroductionTab)` 映射服务端 Module 得来，其中相关推荐的
 *   尾部组件/广告楼层都在 `intro.module.relate` 包里）；
 * - `e(int, List)` 是运行中动态插入组件的唯一入口（相关推荐信息流
 *   `RelatesFeedReq` 异步加载完成后就是从这里插入的）。
 *
 * 因此把两处入参里 `...intro.module.relate.` 包的组件全部过滤，即可同时
 * 去掉静态楼层与动态信息流，评论 Tab 之外的其它模块（staffs/seasons 等）
 * 不受影响。
 *
 * 组件对象只是被丢弃，没有任何代码依赖「 relate 组件一定在列表里」
 * （服务端模块解析与上报均在进入列表前完成），移除是安全的。
 */
class RelatedVideosHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "RelatedVideos"

    private companion object {
        const val SERVICE = "com.bilibili.ship.theseus.united.page.intro.IntroRecycleViewService"
        const val RELATE_PKG = "com.bilibili.ship.theseus.united.page.intro.module.relate."
    }

    override fun hook() {
        val service = SERVICE.findClassOrNull(classLoader)
        if (service == null) {
            Log.w("未找到 IntroRecycleViewService，隐藏相关推荐未生效")
            return
        }

        // 初始组件列表
        val ctor = service.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 5 && it.parameterTypes[1] == List::class.java
        }
        if (ctor == null) {
            Log.w("未匹配 IntroRecycleViewService 构造器，隐藏相关推荐未生效")
            return
        }
        ctor.isAccessible = true
        XposedBridge.hookMethod(
            ctor,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().hideRelatedVideos) return
                    val filtered = filter(param.args[1])
                    if (filtered !== param.args[1]) {
                        Log.d("[init] 相关推荐组件已过滤，剩余 ${sizeOf(filtered)} 个")
                        param.args[1] = filtered
                    }
                }
            },
        )

        // 动态插入（信息流）
        val insert = service.findMethodOrNull(
            "e",
            Int::class.javaPrimitiveType!!,
            List::class.java,
        )
        if (insert == null) {
            Log.w("未找到 IntroRecycleViewService#e(int,List)，信息流过滤未生效")
            return
        }
        XposedBridge.hookMethod(
            insert,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().hideRelatedVideos) return
                    val filtered = filter(param.args[1])
                    if (filtered !== param.args[1]) {
                        Log.d("[insert] 相关推荐组件已过滤，剩余 ${sizeOf(filtered)} 个")
                        param.args[1] = filtered
                    }
                }
            },
        )
        Log.d("已 hook IntroRecycleViewService（初始列表 + 信息流插入）")
    }

    /**
     * 返回过滤后的列表；无命中时原样返回入参，避免无谓拷贝。
     *
     * 列表元素是 `com.bilibili.app.gemini.ui.RunningUIComponent` 包装器，
     * 其 `a` 字段才是真正的委托组件（relate 系组件的类名在这里），
     * 因此需要向内递归检查，最多两层。
     */
    private fun filter(raw: Any?): Any? {
        val list = raw as? List<*> ?: return raw
        if (list.isEmpty()) return raw
        val kept = ArrayList<Any?>(list.size)
        var removed = 0
        for (item in list) {
            if (isRelate(item, depth = 0)) {
                removed++
            } else {
                kept.add(item)
            }
        }
        return if (removed == 0) raw else kept
    }

    private fun isRelate(item: Any?, depth: Int): Boolean {
        if (item == null) return false
        if (item.javaClass.name.startsWith(RELATE_PKG)) return true
        if (depth >= 2) return false
        val inner = innerComponent(item) ?: return false
        return isRelate(inner, depth + 1)
    }

    /** 读 RunningUIComponent 的委托字段（名为 a 的对象字段）。 */
    private fun innerComponent(wrapper: Any): Any? = try {
        val f = wrapper.javaClass.declaredFields.firstOrNull {
            it.name == "a" && !it.type.isPrimitive
        }
        f?.isAccessible = true
        f?.get(wrapper)
    } catch (_: Throwable) {
        null
    }

    private fun sizeOf(list: Any?): Int = (list as? List<*>)?.size ?: -1
}
