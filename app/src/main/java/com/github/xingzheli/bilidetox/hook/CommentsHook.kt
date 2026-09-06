package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findUniqueMethodByNameOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field

/**
 * 视频页隐藏评论区。
 *
 * ## 目标（基于 9.6.0 的反编译结果）
 *
 * 统一详情页是「简介 / 评论」双 Tab 分页结构，Tab 列表的总装配点是
 * `com.bilibili.ship.theseus.united.di.y#a(ViewReply, ImmutableMap)`：
 * 遍历服务端 `ViewReply.tab.tabModuleList`，按 TabCase 交给各工厂
 * （`tab/n` 实现），把产出的 `TabPage` 收进 `tab.i`（"DetailTabs"，
 * Kotlin data class：List + 两个 String）。
 *
 * - 评论 Tab 的工厂是 `tab/b#a(TabModule)`：gson 解析出
 *   `tab.CommentTab` 数据类，包进 `tab.c`（TabPage 实现）；
 * - 简介 Tab 的工厂是 `tab/k`，产出 `tab/l`；
 * - 之后 `TheseusTabPagerService` 只按 DetailTabs 建分页，不再自己创建
 *   评论页，所以**在装配点把评论 TabPage 移除即可让评论 Tab 不存在**。
 *
 * 识别评论 TabPage：其类字段里有一个类型为
 * `com.bilibili.ship.theseus.united.page.tab.CommentTab` 的属性——
 * CommentTab 未被混淆，比匹配混淆类名 tab.c 稳健。
 */
class CommentsHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "Comments"

    private companion object {
        const val TAB_ASSEMBLER = "com.bilibili.ship.theseus.united.di.y"
        const val COMMENT_TAB_DATA = "com.bilibili.ship.theseus.united.page.tab.CommentTab"
    }

    override fun hook() {
        val assembler = TAB_ASSEMBLER.findClassOrNull(classLoader)
        if (assembler == null) {
            Log.w("未找到 united.di.y，隐藏评论区未生效")
            return
        }
        // 该类只有 a(ViewReply, ImmutableMap) 一个方法
        val method = assembler.findUniqueMethodByNameOrNull("a")
        if (method == null) {
            Log.w("未能唯一定位 united.di.y#a，隐藏评论区未生效")
            return
        }

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().hideComments) return
                    val tabs = param.result ?: return
                    param.result = filterCommentTab(tabs)
                }
            },
        )
        Log.d("已 hook united.di.y#a")
    }

    /** 从 DetailTabs 中移除评论 Tab；没有命中时原样返回。 */
    private fun filterCommentTab(tabs: Any): Any {
        val tabCls = tabs.javaClass
        val listField = tabCls.declaredFields.firstOrNull { it.type == List::class.java }
        if (listField == null) {
            Log.w("DetailTabs 中未找到 List 字段，跳过过滤")
            return tabs
        }
        listField.isAccessible = true
        val list = listField.get(tabs) as? List<*> ?: return tabs
        if (list.isEmpty()) return tabs

        val kept = ArrayList<Any?>(list.size)
        var removed = false
        for (item in list) {
            if (item != null && isCommentPage(item)) {
                removed = true
                continue
            }
            kept.add(item)
        }
        if (!removed) return tabs

        Log.d("已从 DetailTabs 移除评论 Tab，剩余 ${kept.size} 个")
        return rebuild(tabs, tabCls, listField, kept)
    }

    /** TabPage 的字段里带 CommentTab 数据类即为评论页。 */
    private fun isCommentPage(page: Any): Boolean =
        page.javaClass.declaredFields.any { it.type.name == COMMENT_TAB_DATA }

    /**
     * 用过滤后的列表重建 DetailTabs。
     *
     * 原列表可能是不可变实现（kotlin listOf），直接改会抛
     * UnsupportedOperationException；重走 data class 构造器最稳妥。
     */
    private fun rebuild(tabs: Any, tabCls: Class<*>, listField: Field, kept: List<Any?>): Any {
        val ctor = tabCls.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 3 && c.parameterTypes[0] == List::class.java
        } ?: run {
            Log.w("未匹配 DetailTabs 构造器，保持原 Tab 列表")
            return tabs
        }
        ctor.isAccessible = true

        // 其余两个 String 字段（tabBg / tabBgWidescreen）原值透传
        val strings = tabCls.declaredFields
            .filter { it.type == String::class.java }
            .map {
                it.isAccessible = true
                it.get(tabs)
            }
        val args = ArrayList<Any?>(3)
        args.add(kept)
        args.addAll(strings.take(2))
        return ctor.newInstance(*args.toArray())
    }
}
