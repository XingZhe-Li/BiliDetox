package com.github.xingzheli.bilidetox.hook

import android.widget.TextView
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findFieldOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field

/**
 * 搜索框隐藏自动填充话题（默认搜索词）。覆盖两处入口：
 *
 * ## 1. 首页顶栏搜索框
 *
 * ```
 * 首页 frame 数据 --> Ui1/s（SearchDefaultWord：a = word、k = show）
 *   -> 存进首页 UI state Vi1/e（字段 j = searchDefaultWord、k = 零卡片词）
 *   -> 顶栏 Compose（topbar/f#o）直读 state 渲染搜索框文案
 * ```
 *
 * 实测两条教训：
 * 1. 第一版 hook `UpdateSearchDefaultWordAction` 构造器 —— 该 action 在此
 *    流程里根本不会被构造，Compose 是直读状态的；
 * 2. 第二版 hook Ui1/s 的两个构造器 + copy 方法 —— 日志显示一个都没触发，
 *    说明 Ui1/s 是被 Gson 之类通过 Unsafe 反射实例化的，完全绕过构造器。
 *
 * 因此改为 hook **状态对象 Vi1/e** 的构造（12 参）与拷贝方法 a(...)：
 * after 阶段把其 j / k 两个字段指向的 Ui1/s 的 word 置空、show 置 false。
 * 这一层是所有数据源（云端 frame、零卡片、缓存恢复）的必经合并点，且
 * 即使 Ui1/s 本体被 Unsafe 创建、字段已由解析器填好，在进状态的这一刻
 * 改字段依然有效。
 *
 * ## 2. 搜索页输入框 placeholder
 *
 * 搜索页（BiliMainSearchActivity）的输入框提示词来自独立链路：
 * `SearchMoss.executeDefaultWords` → proto `DefaultWordsReply` →
 * `com.bilibili.search2.api.DefaultKeyword(DefaultWordsReply)`（字段名
 * 未混淆：word / value / show 等）→ LiveData → 搜索页输入框。
 * hook 其构造器，把 word / value 置空；每次刷新重建都会被拦。
 */
class SearchPlaceholderHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "SearchPlaceholder"

    private companion object {
        const val STATE = "Vi1.e"
        const val DEFAULT_WORD = "Ui1.s"
        const val SEARCH_KEYWORD = "com.bilibili.search2.api.DefaultKeyword"
        const val FALLBACK_KEY = "main.search_zero_signal_default_word"

        /** 诊断开关：定位搜索页输入框的词到底走不走 setText（现已定位，默认关闭）。 */
        private const val DEBUG_EDITTEXT = false
    }

    /** Ui1.s 的字段引用（懒取一次，null 表示结构对不上）。 */
    private var wordField: Field? = null
    private var showField: Field? = null

    override fun hook() {
        val stateCls = STATE.findClassOrNull(classLoader)
        val wordCls = DEFAULT_WORD.findClassOrNull(classLoader)
        if (stateCls == null || wordCls == null) {
            Log.w("未找到 Vi1.e / Ui1.s，隐藏搜索占位词未生效")
            return
        }

        wordField = wordCls.findFieldOrNull("a")
        showField = wordCls.findFieldOrNull("k")
        if (wordField == null || showField == null) {
            Log.w("Ui1.s 字段结构变化，隐藏搜索占位词未生效")
            return
        }

        // state 里的两个 Ui1/s 字段：j（searchDefaultWord）与 k（零卡片词）
        val slots = stateCls.declaredFields.filter { it.type == wordCls }
        if (slots.isEmpty()) {
            Log.w("Vi1.e 中未找到 Ui1.s 字段，隐藏搜索占位词未生效")
            return
        }

        var hooked = 0
        val onState = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val state = param.result ?: param.thisObject ?: return
                blankState(state, slots)
            }
        }

        // 携带 Ui1.s 参数的构造器（完整状态 12 参）+ 拷贝方法 a(旧state, ...)
        for (ctor in stateCls.declaredConstructors) {
            val wordParams = ctor.parameterTypes.count { it == wordCls }
            if (wordParams == 0) continue
            ctor.isAccessible = true
            XposedBridge.hookMethod(ctor, onState)
            hooked++
        }
        for (m in stateCls.declaredMethods) {
            if (m.name != "a" || m.parameterTypes.isEmpty()) continue
            if (m.parameterTypes[0] != stateCls) continue
            m.isAccessible = true
            XposedBridge.hookMethod(m, onState)
            hooked++
        }

        if (hooked == 0) {
            Log.w("Vi1.e 无可 hook 的构造/拷贝方法，隐藏搜索占位词未生效")
        } else {
            Log.d("已 hook Vi1.e 创建点 $hooked 处（j/k 槽位 ${slots.size} 个）")
        }

        hookSearchPageKeyword()
        hookConfigFallbackWord()
        hookSearchTextView()
    }

    /**
     * 最底层的保险：提示词最终都落到具体 View 上——
     * - 首页顶栏：`tv.danmaku.bili:id/search_text`（TextView）
     * - 搜索页输入框：`tv.danmaku.bili:id/search_src_text`（EditText，
     *   默认词以 setText 预填，而不是 hint）
     * hook TextView.setText，按 view id 精确拦截（整数比较，开销可忽略）。
     */
    private fun hookSearchTextView() {
        val targets = setOf("search_text", "search_src_text")
        var hookedCount = 0
        val overloads = TextView::class.java.declaredMethods.filter {
            it.name == "setText" && it.parameterTypes.size in 1..2 &&
                it.parameterTypes[0] == CharSequence::class.java
        }
        for (m in overloads) {
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    @Volatile
                    private var targetIds: Set<Int>? = null

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val newWord = param.args[0] as? CharSequence ?: return
                        if (newWord.isEmpty()) return
                        if (!RuntimeConfig.get().hideSearchPlaceholder) return
                        val view = param.thisObject as? android.widget.TextView ?: return
                        var ids = targetIds
                        if (ids == null) {
                            ids = try {
                                targets.mapNotNull { name ->
                                    view.resources.getIdentifier(
                                        name, "id", "tv.danmaku.bili",
                                    ).takeIf { it != 0 }
                                }.toSet()
                            } catch (_: Throwable) {
                                emptySet()
                            }
                            if (ids.isEmpty()) {
                                Log.w("未解析到 search_text/search_src_text id，占位词视图过滤未生效")
                                return
                            }
                            targetIds = ids
                        }
                        if (view.id in ids) {
                            Log.d("已拦截搜索框占位词（视图层）")
                            param.args[0] = ""
                        } else if (view is android.widget.EditText && DEBUG_EDITTEXT) {
                            // 诊断：搜索页词若不走 setText(CharSequence)，从这里看它走哪条路
                            val name = try {
                                view.resources.getResourceEntryName(view.id)
                            } catch (_: Throwable) {
                                "?"
                            }
                            Log.d("EditText setText 未匹配: id=$name len=${newWord.length}")
                        }
                    }
                },
            )
            hookedCount++
        }
        if (hookedCount == 0) {
            Log.w("未找到 TextView.setText(CharSequence)，占位词视图过滤未生效")
        } else {
            Log.d("已 hook TextView.setText（${hookedCount} 个重载，按 search_text/search_src_text id 过滤）")
        }

        /** char[] 重载：诊断 + 拦截。 */
        TextView::class.java.declaredMethods.firstOrNull {
            it.name == "setText" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == CharArray::class.java
        }?.let { m ->
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    @Volatile
                    private var targetIds: Set<Int>? = null

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!RuntimeConfig.get().hideSearchPlaceholder) return
                        val view = param.thisObject as? android.widget.TextView ?: return
                        val ids = targetIds ?: resolveIds(view)?.also { targetIds = it }
                        if (ids != null && view.id in ids) {
                            Log.d("已拦截搜索框占位词（视图层/char[]）")
                            param.args = arrayOf(CharArray(0), 0, 0)
                        } else if (DEBUG_EDITTEXT && view is android.widget.EditText) {
                            Log.d("EditText char[] setText 未匹配 id=${idName(view)}")
                        }
                    }
                },
            )
            hookedCount++
        }

        /** hint 重载：有的页面把默认词当 hint 设置。 */
        for (m in TextView::class.java.declaredMethods) {
            if (m.name != "setHint" || m.parameterTypes.size != 1) continue
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    @Volatile
                    private var targetIds: Set<Int>? = null

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val newHint = param.args[0] as? CharSequence ?: return
                        if (newHint.isEmpty()) return
                        if (!RuntimeConfig.get().hideSearchPlaceholder) return
                        val view = param.thisObject as? android.widget.TextView ?: return
                        val ids = targetIds ?: resolveIds(view)?.also { targetIds = it }
                        if (ids != null && view.id in ids) {
                            Log.d("已拦截搜索框占位词（视图层/hint）")
                            param.args[0] = ""
                        } else if (DEBUG_EDITTEXT && view is android.widget.EditText) {
                            Log.d("EditText setHint 未匹配 id=${idName(view)} len=${newHint.length}")
                        }
                    }
                },
            )
            hookedCount++
        }
    }

    private fun resolveIds(view: android.widget.TextView): Set<Int>? = try {
        setOf("search_text", "search_src_text").mapNotNull { name ->
            view.resources.getIdentifier(name, "id", "tv.danmaku.bili").takeIf { it != 0 }
        }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun idName(view: android.widget.TextView): String = try {
        view.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 兜底词：首页 frame 没带默认词时，顶栏渲染会回退到远程配置
     * `main.search_zero_signal_default_word`（ConfigManager.getConfig）。
     * 直接把该 key 的读取结果置空。
     */
    private fun hookConfigFallbackWord() {
        val companionCls = "com.bilibili.lib.blconfig.ConfigManager\$Companion"
            .findClassOrNull(classLoader)
        if (companionCls == null) {
            Log.w("未找到 ConfigManager\$Companion，兜底搜索词过滤未生效")
            return
        }
        val methods = companionCls.declaredMethods.filter {
            it.name == "getConfig" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java
        }
        if (methods.isEmpty()) {
            Log.w("未找到 ConfigManager.getConfig，兜底搜索词过滤未生效")
            return
        }
        for (m in methods) {
            m.isAccessible = true
            XposedBridge.hookMethod(
                m,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!RuntimeConfig.get().hideSearchPlaceholder) return
                        if (param.args[0] == FALLBACK_KEY) {
                            val old = param.result as? String
                            if (!old.isNullOrEmpty()) {
                                param.result = ""
                                Log.d("已置空兜底搜索词（配置原值长度 ${old.length}）")
                            }
                        }
                    }
                },
            )
        }
        Log.d("已 hook ConfigManager.getConfig（${methods.size} 个重载）")
    }

    /** 搜索页输入框的默认词：DefaultKeyword(DefaultWordsReply) 构造后置空。 */
    private fun hookSearchPageKeyword() {
        val keywordCls = SEARCH_KEYWORD.findClassOrNull(classLoader)
        if (keywordCls == null) {
            Log.w("未找到 DefaultKeyword，搜索页占位词过滤未生效")
            return
        }
        val ctor = keywordCls.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name.endsWith("DefaultWordsReply")
        }
        if (ctor == null) {
            Log.w("未匹配 DefaultKeyword(DefaultWordsReply) 构造器，搜索页占位词过滤未生效")
            return
        }
        ctor.isAccessible = true

        val word = keywordCls.findFieldOrNull("word")
        val value = keywordCls.findFieldOrNull("value")
        if (word == null || value == null) {
            Log.w("DefaultKeyword 字段结构变化，搜索页占位词过滤未生效")
            return
        }

        XposedBridge.hookMethod(
            ctor,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().hideSearchPlaceholder) return
                    val instance = param.thisObject
                    try {
                        word.set(instance, "")
                        value.set(instance, "")
                        Log.d("已置空搜索页默认词")
                    } catch (e: Throwable) {
                        Log.e("置空搜索页默认词失败", e)
                    }
                }
            },
        )
        Log.d("已 hook DefaultKeyword 构造器")
    }

    private fun blankState(state: Any, slots: List<Field>) {
        if (!RuntimeConfig.get().hideSearchPlaceholder) return
        val wf = wordField ?: return
        val sf = showField ?: return
        for (slot in slots) {
            val word = try {
                slot.isAccessible = true
                slot.get(state) ?: continue
            } catch (_: Throwable) {
                continue
            }
            try {
                sf.isAccessible = true
                sf.setBoolean(word, false)
                wf.isAccessible = true
                val old = wf.get(word) as? String
                when {
                    old == null -> Log.d("搜索默认词槽位为 null，无需置空")
                    old.isEmpty() -> Log.d("搜索默认词已是空串")
                    else -> {
                        wf.set(word, "")
                        Log.d("已置空首页搜索默认词（原词长度 ${old.length}）")
                    }
                }
            } catch (e: Throwable) {
                Log.e("置空搜索默认词失败", e)
            }
        }
    }
}
