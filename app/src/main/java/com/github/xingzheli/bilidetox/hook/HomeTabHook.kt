package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Config
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import com.github.xingzheli.bilidetox.findUniqueMethodByNameOrNull
import com.github.xingzheli.bilidetox.uriMatches
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 移除首页顶部的推荐类 Tab。
 *
 * ## 数据流（基于 9.6.0 / versionCode 9060300 的反编译结果）
 *
 * ```
 * 云端 JSON --fastjson--> MainResourceManager$TabResponse
 *                              .tabData.tab : List<MainResourceManager$Tab>
 *                                    |
 *                     MainResourceManager.c(int, List)      <-- hook 点 1
 *                                    |
 *                              List<A>   (A implements 接口 i)
 *                                    |
 *                     HomeFragmentV2.Kf(List<A>)            <-- hook 点 2
 *                                    |
 *                          真正构建出的 Fragment 页面
 * ```
 *
 * ## 为什么要两个 hook 点
 *
 * hook 点 1（`MainResourceManager.c`）负责云端下发的数据，是最干净的收敛点 ——
 * 我核对过它的全部调用者（MainResourceManager 行 1032、HomeTabServiceImpl 行 98），
 * 云端数据必经此处。
 *
 * 但 `c()` 只覆盖云端路径。当网络失败、或处于青少年/课堂模式时，B 站会走
 * 硬编码兜底列表 —— `C0553a.a()` / `k.a()` / `E.a()`，这三个方法各自
 * `new A(...)` 直接构造 Tab，完全绕过 `c()`。它们的产物同样流入 `Kf()`。
 *
 * 所以 hook 点 2（`HomeFragmentV2.Kf`）作为总闸，覆盖包含兜底在内的全部路径。
 * 两点都做，是因为在点 1 提前过滤能让 B 站自身的缓存/diff 逻辑看到一致的数据，
 * 减少「Tab 闪一下又消失」这类视觉问题。
 *
 * ## 两个必须处理的副作用
 *
 * 1. **不能把列表清空。** MainResourceManager 拿到云端数据后有一步校验
 *    （反编译行号约 1066）：`if (bottom.size() < 1 || tab.size() < 1) { 走失败回调 }`。
 *    Tab 一旦为空，B 站认为数据无效，转而使用硬编码兜底 —— 推荐 Tab 又回来了。
 *    因此过滤时保留至少 [Config.MIN_KEPT_TABS] 项。
 *
 * 2. **defaultSelected 必须转移。** 推荐 Tab 的 `d`（即 defaultSelected）为 true，
 *    是首页的默认落地页。`HomeFragmentV2.Kf` 靠它决定初始选中下标
 *    （反编译行 993：`if (a2.d) { this.I = i4; ... }`）。删掉推荐后若没有任何 Tab
 *    带这个标记，初始下标不会被赋值。所以过滤后要把标记补给剩下的第一个 Tab。
 */
class HomeTabHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "HomeTab"

    /**
     * `tv.danmaku.bili.ui.main2.resource.i` —— Tab 模型的接口。
     *
     * 用接口而不是具体类 `A` 来读写字段，是因为接口方法名
     * （getUri / getDefaultSelected / setDefaultSelected）是语义化的、未被混淆，
     * 而 `A` 的字段名 a/b/c/d 是混淆产物，跨版本一定会漂移。
     */
    private var tabInterface: Class<*>? = null
    private var getUri: java.lang.reflect.Method? = null
    private var getDefaultSelected: java.lang.reflect.Method? = null
    private var setDefaultSelected: java.lang.reflect.Method? = null

    override fun hook() {
        if (!resolveTabInterface()) return

        hookMainResourceManager()
        hookHomeFragment()
    }

    /**
     * 解析 Tab 模型接口及其访问方法。
     *
     * 这几个是后续过滤的前提，任何一个拿不到就整体放弃 —— 与其半残地跑，
     * 不如明确报错让用户知道该更新模块了。
     */
    private fun resolveTabInterface(): Boolean {
        val iface = "tv.danmaku.bili.ui.main2.resource.i".findClassOrNull(classLoader)
        if (iface == null) {
            Log.w("未找到 Tab 模型接口 resource.i，移除推荐 Tab 未生效")
            return false
        }
        tabInterface = iface

        getUri = iface.findMethodOrNull("getUri")
        getDefaultSelected = iface.findMethodOrNull("getDefaultSelected")
        setDefaultSelected = iface.findMethodOrNull(
            "setDefaultSelected",
            Boolean::class.javaPrimitiveType!!,
        )

        if (getUri == null || getDefaultSelected == null || setDefaultSelected == null) {
            Log.w(
                "Tab 接口方法不完整 " +
                    "(getUri=${getUri != null}, " +
                    "getDefaultSelected=${getDefaultSelected != null}, " +
                    "setDefaultSelected=${setDefaultSelected != null})，" +
                    "移除推荐 Tab 未生效",
            )
            return false
        }
        return true
    }

    /**
     * hook 点 1：`MainResourceManager.c(int, List)` —— 云端数据的收敛点。
     *
     * 按名称查找而不写死签名：`c` 在这个类里是唯一的同名方法（我确认过同类中还有
     * b/d/e/g，但没有第二个 c），而参数签名 `(int, List)` 相对更容易随版本变化。
     */
    private fun hookMainResourceManager() {
        val clazz = "tv.danmaku.bili.ui.main2.resource.MainResourceManager"
            .findClassOrNull(classLoader)
        if (clazz == null) {
            Log.w("未找到 MainResourceManager，跳过云端数据过滤")
            return
        }

        val method = clazz.findUniqueMethodByNameOrNull("c")
        if (method == null) {
            Log.w("未能唯一定位 MainResourceManager#c，跳过云端数据过滤")
            return
        }

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = filterTabs(param.result, "MainResourceManager#c")
                }
            },
        )
        Log.d("已 hook MainResourceManager#c")
    }

    /**
     * hook 点 2：`HomeFragmentV2.Kf(List<A>)` —— 覆盖兜底路径的总闸。
     *
     * 在 before 阶段改入参而不是在 after 改返回值：`Kf` 的返回类型是
     * `List<BasePrimaryMultiPageFragment.b>`，那是已经建好的页面描述对象，
     * 里面不再保留 uri 之外的原始信息，改起来更脆。直接在入口把 `List<A>`
     * 过滤掉，让 `Kf` 自己按干净的数据去建页面，副作用最小。
     *
     * 这样做也顺带让 `Kf` 内部的 defaultSelected 处理（行 993）拿到我们
     * 转移后的标记。
     */
    private fun hookHomeFragment() {
        val clazz = "tv.danmaku.bili.ui.main2.HomeFragmentV2".findClassOrNull(classLoader)
        if (clazz == null) {
            Log.w("未找到 HomeFragmentV2，兜底路径未覆盖")
            return
        }

        val method = clazz.findMethodOrNull("Kf", List::class.java)
        if (method == null) {
            Log.w("未找到 HomeFragmentV2#Kf，兜底路径未覆盖")
            return
        }

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = filterTabs(param.args[0], "HomeFragmentV2#Kf")
                }
            },
        )
        Log.d("已 hook HomeFragmentV2#Kf")
    }

    /**
     * 过滤 Tab 列表。
     *
     * 返回一个**新的** ArrayList 而不是就地修改：入参可能是
     * `Collections.emptyList()` 或其它不可变实现，就地改会抛
     * UnsupportedOperationException；而且 B 站会把这个列表存进缓存字段
     * （MainResourceManager.d.a），就地改会污染缓存。
     *
     * 任何异常都吞掉并返回原始列表 —— 宁可功能不生效，也不能让首页崩。
     */
    private fun filterTabs(raw: Any?, tag: String): Any? {
        return try {
            filterTabsOrThrow(raw, tag)
        } catch (e: Throwable) {
            Log.e("[$tag] 过滤 Tab 时出现异常，保持原始列表", e)
            raw
        }
    }

    private fun filterTabsOrThrow(raw: Any?, tag: String): Any? {
        // 开关是运行时的：关闭时原样返回，Tab 列表完全不经过滤。
        if (!RuntimeConfig.get().removeHomeTabs) return raw
        val iface = tabInterface ?: return raw
        val list = raw as? List<*> ?: return raw
        if (list.isEmpty()) return raw

        val kept = ArrayList<Any?>(list.size)
        val removedUris = ArrayList<String>()

        for (item in list) {
            // 非目标类型的元素（理论上不该出现）原样保留，不擅自丢弃。
            if (item == null || !iface.isInstance(item)) {
                kept.add(item)
                continue
            }
            val uri = getUri?.invoke(item) as? String
            if (shouldRemove(uri)) {
                removedUris.add(uri.orEmpty())
            } else {
                kept.add(item)
            }
        }

        if (removedUris.isEmpty()) return raw

        // 留底保护：过滤后剩得太少就整体放弃，避免触发 B 站的兜底逻辑
        // 反而把推荐 Tab 换回来。
        if (kept.size < Config.MIN_KEPT_TABS) {
            Log.w(
                "[$tag] 过滤后仅剩 ${kept.size} 个 Tab，" +
                    "低于下限 ${Config.MIN_KEPT_TABS}，放弃本次过滤",
            )
            return raw
        }

        ensureDefaultSelected(kept, iface, tag)

        Log.d("[$tag] 已移除 ${removedUris.size} 个 Tab: $removedUris，剩余 ${kept.size} 个")
        return kept
    }

    private fun shouldRemove(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        return Config.REMOVED_TAB_URIS.any { uriMatches(uri, it) }
    }

    /**
     * 保证过滤后仍有一个 Tab 带 defaultSelected 标记。
     *
     * 推荐 Tab 原本是默认落地页（C0553a.a() 里显式 `a.d = true`）。删掉它以后
     * 若无人接手，HomeFragmentV2.Kf 中的 `this.I` 不会被赋值，首页初始选中
     * 状态就落到了未定义行为上。这里把标记补给剩下的第一个 Tab。
     */
    private fun ensureDefaultSelected(kept: List<Any?>, iface: Class<*>, tag: String) {
        val getter = getDefaultSelected ?: return
        val setter = setDefaultSelected ?: return

        val hasDefault = kept.any { item ->
            item != null && iface.isInstance(item) && getter.invoke(item) == true
        }
        if (hasDefault) return

        val first = kept.firstOrNull { it != null && iface.isInstance(it) } ?: return
        setter.invoke(first, true)
        Log.d("[$tag] 默认选中的 Tab 已被移除，标记已转移至 ${getUri?.invoke(first)}")
    }
}
