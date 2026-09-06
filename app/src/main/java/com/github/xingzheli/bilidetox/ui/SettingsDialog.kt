package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.github.xingzheli.bilidetox.ConfigData
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig

/**
 * BiliDetox 设置对话框。
 *
 * 实现说明：
 * - 模块以 NPatch 嵌入宿主，**没有自己的资源与清单**，所以整个界面
 *   用代码构建，不引用任何 R 资源。
 * - 对话框 builder 优先用宿主类加载器里的 Material/appcompat 实现
 *   （跟随 B 站日夜间主题），加载失败退回平台 AlertDialog.Builder；
 *   三者的 setTitle(CharSequence)/setView(View)/show() 签名一致，
 *   统一经 [invoke] 反射调用。
 * - 开关直接写 RuntimeConfig（保存即生效），hook 侧按 mtime 自动重读。
 */
class SettingsDialog(
    private val activity: Activity,
    private val hostClassLoader: ClassLoader,
) {

    /** 界面开关项：key 对应 RuntimeConfig 的 JSON 字段。顺序即展示顺序。 */
    private data class Item(
        val key: String,
        val title: String,
        val summary: String,
    )

    fun show() {
        try {
            val builder = createBuilder()
            invoke(builder, "setTitle", "BiliDetox 设置")
            invoke(builder, "setView", buildContent(activity))
            invoke(builder, "show", null)
        } catch (e: Throwable) {
            Log.e("展示设置对话框失败", e)
        }
    }

    /** 实例化对话框 builder：Material 优先，appcompat 次之，平台兜底。 */
    private fun createBuilder(): Any {
        val candidates = listOf(
            "com.google.android.material.dialog.MaterialAlertDialogBuilder",
            "androidx.appcompat.app.AlertDialog\$Builder",
        )
        for (name in candidates) {
            try {
                val cls = hostClassLoader.loadClass(name)
                return cls.getConstructor(Context::class.java).newInstance(activity)
            } catch (_: Throwable) {
                // 尝试下一个候选
            }
        }
        return android.app.AlertDialog.Builder(activity)
    }

    private fun buildContent(context: Context): View {
        val items = listOf(
            Item("removeHomeTabs", "移除首页「推荐」「热门」Tab", "关闭后首页顶栏恢复云端下发"),
            Item("blockUpdate", "禁用应用内更新检查", "屏蔽启动检查、手动检查与强更提示"),
            Item("hideRelatedVideos", "视频页隐藏「相关推荐」", "关闭详情页的相关视频列表"),
            Item("hideComments", "视频页隐藏评论区", "移除详情页的评论 Tab"),
            Item("hideSearchPlaceholder", "搜索框隐藏自动填充话题", "首页搜索框不再显示默认搜索词"),
            Item("hideSearchSquares", "搜索页隐藏热搜/搜索发现", "移除「bilibili热搜」与「搜索发现」板块"),
        )

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 8), pad, dp(context, 12))
        }

        val config = RuntimeConfig.get()
        for (item in items) {
            list.addView(buildRow(context, item, configValue(config, item.key)) { value ->
                RuntimeConfig.update { c -> withKey(c, item.key, value) }
            })
        }

        val scroll = ScrollView(context).apply {
            addView(list)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            addView(
                TextView(context).apply {
                    text = "改动即时保存；回到相应页面后生效。"
                    textSize = 12f
                    alpha = 0.65f
                    setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 12))
                },
            )
        }
    }

    private fun buildRow(
        context: Context,
        item: Item,
        current: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(
            TextView(context).apply {
                text = item.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        textCol.addView(
            TextView(context).apply {
                text = item.summary
                textSize = 12f
                alpha = 0.65f
            },
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val switch = Switch(context).apply {
            isChecked = current
            setOnCheckedChangeListener { _, checked ->
                try {
                    onChange(checked)
                } catch (e: Throwable) {
                    Log.e("保存配置 ${item.key} 失败", e)
                }
            }
        }
        row.addView(switch)

        // 点击整行也能切换
        row.setOnClickListener { switch.toggle() }
        return row
    }

    private fun configValue(c: ConfigData, key: String): Boolean = when (key) {
        "removeHomeTabs" -> c.removeHomeTabs
        "blockUpdate" -> c.blockUpdate
        "hideRelatedVideos" -> c.hideRelatedVideos
        "hideComments" -> c.hideComments
        "hideSearchPlaceholder" -> c.hideSearchPlaceholder
        "hideSearchSquares" -> c.hideSearchSquares
        else -> false
    }

    private fun withKey(c: ConfigData, key: String, value: Boolean): ConfigData = when (key) {
        "removeHomeTabs" -> c.copy(removeHomeTabs = value)
        "blockUpdate" -> c.copy(blockUpdate = value)
        "hideRelatedVideos" -> c.copy(hideRelatedVideos = value)
        "hideComments" -> c.copy(hideComments = value)
        "hideSearchPlaceholder" -> c.copy(hideSearchPlaceholder = value)
        "hideSearchSquares" -> c.copy(hideSearchSquares = value)
        else -> c
    }

    /**
     * 反射调用 builder 方法。三个候选 builder 的目标方法签名一致：
     * setTitle(CharSequence) / setView(View) / show()。
     * [arg] 为 null 表示无参方法（show），否则按参数运行时类型挑选单参重载。
     */
    private fun invoke(target: Any, method: String, arg: Any?) {
        val candidates = target.javaClass.methods.filter { it.name == method }
        val m = if (arg == null) {
            candidates.firstOrNull { it.parameterTypes.isEmpty() }
        } else {
            candidates.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(arg) }
                ?: candidates.firstOrNull {
                    it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(arg.javaClass)
                }
        } ?: throw NoSuchMethodException("${target.javaClass.name}#$method")
        m.isAccessible = true
        m.invoke(target, *(if (arg == null) emptyArray() else arrayOf(arg)))
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
