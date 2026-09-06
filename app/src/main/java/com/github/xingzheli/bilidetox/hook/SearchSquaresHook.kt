package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findUniqueMethodByNameOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 搜索页隐藏「bilibili热搜」「搜索发现」板块。
 *
 * ## 目标（基于 9.6.0 的反编译结果）
 *
 * 点开搜索框后进入发现页（BiliMainSearchDiscoverFragment），其数据由
 * `com.bilibili.search2.discover.p#f(List<SearchSquareType>)` 分发：
 *
 * - type == "trending"  -> LiveData e ->「bilibili热搜」榜
 * - type == "recommend" -> LiveData a ->「搜索发现」
 * - type == "history"   -> 搜索历史（保留）
 *
 * 同时它会把原始列表存进 ViewModel（q.g），供 section 适配器使用。
 * 在 f 的 before 阶段把入参替换成过滤后的副本，两条消费路径一并干净。
 * 历史板块不在过滤范围。
 */
class SearchSquaresHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "SearchSquares"

    private companion object {
        const val HOLDER = "com.bilibili.search2.discover.p"
        val HIDDEN_TYPES = setOf("trending", "recommend")
    }

    override fun hook() {
        val holder = HOLDER.findClassOrNull(classLoader)
        if (holder == null) {
            Log.w("未找到 search2.discover.p，隐藏热搜/搜索发现未生效")
            return
        }

        val method = holder.findUniqueMethodByNameOrNull("f")
        if (method == null) {
            Log.w("未能唯一定位 discover.p#f，隐藏热搜/搜索发现未生效")
            return
        }

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().hideSearchSquares) return
                    val original = param.args[0] as? List<*> ?: return
                    if (original.isEmpty()) return

                    val kept = ArrayList<Any?>(original.size)
                    var removed = 0
                    for (item in original) {
                        val type = squareTypeOf(item)
                        if (type in HIDDEN_TYPES) {
                            removed++
                        } else {
                            kept.add(item)
                        }
                    }
                    if (removed == 0) return
                    param.args[0] = kept
                    Log.d("已移除 $removed 个搜索板块（trending/recommend），剩余 ${kept.size} 个")
                }
            },
        )
        Log.d("已 hook search2.discover.p#f")
    }

    /** SearchSquareType#getType()；反射调用，异常一律视为不匹配。 */
    private fun squareTypeOf(item: Any?): String? = try {
        item?.javaClass?.methods
            ?.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
            ?.invoke(item) as? String
    } catch (_: Throwable) {
        null
    }
}
