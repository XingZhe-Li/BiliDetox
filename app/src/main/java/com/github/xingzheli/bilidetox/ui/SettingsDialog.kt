package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.ConfigData
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.TimeLockPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val content = FrameLayout(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private enum class Page { MAIN, HOME, TIME_LOCK, PLEDGE }
    private var page = Page.MAIN
    private var displayedConfig: ConfigData? = null
    private var displayedPledge = false
    private var displayedRestriction: String? = null
    private val refresher = object : Runnable {
        override fun run() {
            if (dialog?.isShowing != true) return
            val config = RuntimeConfig.get()
            val now = System.currentTimeMillis()
            if (config != displayedConfig || config.timeLock.isPledged(now) != displayedPledge ||
                TimeLockPolicy.editRestriction(config.timeLock, now) != displayedRestriction) renderPage()
            handler.postDelayed(this, 1000)
        }
    }

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
            renderPage()
            invoke(builder, "setView", content)
            dialog = invoke(builder, "show", null) as Dialog
            dialog?.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        page = Page.MAIN
                        renderPage()
                    }
                    true
                } else false
            }
            dialog?.setOnDismissListener { handler.removeCallbacks(refresher) }
            handler.post(refresher)
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
        return android.app.AlertDialog.Builder(SettingsTheme.dialogContext(activity))
    }

    private fun renderPage() {
        val config = RuntimeConfig.get()
        val now = System.currentTimeMillis()
        displayedConfig = config
        displayedPledge = config.timeLock.isPledged(now)
        displayedRestriction = TimeLockPolicy.editRestriction(config.timeLock, now)
        dialog?.setTitle(when (page) { Page.MAIN -> "BiliDetox 设置"; Page.HOME -> "首页管理"; Page.TIME_LOCK -> "时段锁"; Page.PLEDGE -> "发誓锁" })
        content.removeAllViews()
        content.addView(when (page) {
            Page.HOME -> buildSubpage(HomeSettingsView(activity))
            Page.TIME_LOCK -> buildSubpage(TimeLockSettingsView(activity))
            Page.PLEDGE -> buildSubpage(PledgeSettingsView(activity))
            Page.MAIN -> buildContent(activity, config, displayedPledge)
        })
    }

    private fun buildSubpage(settings: View): View {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 20)
            setPadding(pad, dp(activity, 8), pad, dp(activity, 12))
            addView(Button(activity).apply {
                SettingsTheme.styleButton(this)
                text = "‹ 返回 BiliDetox 设置"
                setOnClickListener { page = Page.MAIN; renderPage() }
            })
            addView(settings)
        }
        return ScrollView(activity).apply { addView(list) }
    }

    private fun buildContent(context: Context, config: ConfigData, pledged: Boolean): View {
        val items = listOf(
            Item("blockUpdate", "禁用应用内更新检查", "屏蔽启动检查、手动检查与强更提示"),
            Item("hideRelatedVideos", "视频页隐藏「相关推荐」", "关闭详情页的相关视频列表"),
            Item("hideComments", "视频页隐藏评论区", "移除详情页的评论 Tab"),
            Item("hideSearchPlaceholder", "搜索框隐藏自动填充话题", "首页搜索框不再显示默认搜索词"),
            Item("hideSearchSquares", "搜索页隐藏热搜/搜索发现", "移除「bilibili热搜」与「搜索发现」板块"),
            Item("strictSearchResults", "综合搜索关键词过滤", "第一页前10条视频须有命中，不足10条检查全部；通过后仅前5条免过滤"),
        )

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 8), pad, dp(context, 12))
        }

        if (pledged) {
            list.addView(TextView(context).apply {
                val until = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(config.timeLock.pledgeUntil))
                text = "发誓锁定至 $until\n所有开关及其他可编辑选项已锁定，仍可浏览设置。"
                textSize = 14f
                setPadding(0, dp(context, 8), 0, dp(context, 12))
            })
        }
        list.addView(TextView(context).apply {
            text = "首页管理  ›\n${if (config.home.blankHome) "空白首页 · 四个快捷入口" else "原生首页 · 独立栏目开关"}"
            textSize = 16f
            setPadding(0, dp(context, 14), 0, dp(context, 14))
            isFocusable = true
            setOnClickListener { page = Page.HOME; renderPage() }
        })
        list.addView(TextView(context).apply {
            val lock = config.timeLock
            text = "时段锁  ›\n${if (lock.enabled) "已启用" else "已禁用"} · 每日 ${lock.periods.size} 个时段"
            textSize = 16f
            setPadding(0, dp(context, 14), 0, dp(context, 14))
            isFocusable = true
            setOnClickListener { page = Page.TIME_LOCK; renderPage() }
        })
        list.addView(TextView(context).apply {
            val until = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(config.timeLock.pledgeUntil))
            text = "发誓锁  ›\n${if (pledged) "锁定至 $until" else "未启用 · 冻结所有设置"}"
            textSize = 16f
            setPadding(0, dp(context, 14), 0, dp(context, 14))
            isFocusable = true
            setOnClickListener { page = Page.PLEDGE; renderPage() }
        })
        for (item in items) {
            list.addView(buildRow(context, item, configValue(config, item.key), !pledged) { value ->
                RuntimeConfig.update { c -> withKey(c, item.key, value) }
            })
        }
        list.addView(
            TextView(context).apply {
                text = "改动即时保存；回到相应页面后生效。发誓锁定期间所有设置均不可修改。"
                textSize = 12f
                alpha = 0.65f
                setPadding(0, dp(context, 8), 0, dp(context, 12))
            },
        )
        return ScrollView(context).apply { addView(list) }
    }

    private fun buildRow(
        context: Context,
        item: Item,
        current: Boolean,
        editable: Boolean,
        onChange: (Boolean) -> Boolean,
    ): View {
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (editable) 1f else 0.5f
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
            isEnabled = editable
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val switch = Switch(context).apply {
            SettingsTheme.styleSwitch(this)
            isChecked = current
            isEnabled = editable
            contentDescription = item.title
            var binding = false
            setOnCheckedChangeListener { _, checked ->
                if (binding) return@setOnCheckedChangeListener
                try {
                    if (!onChange(checked)) Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Log.e("保存配置 ${item.key} 失败", e)
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                } finally {
                    binding = true
                    isChecked = configValue(RuntimeConfig.get(), item.key)
                    binding = false
                }
            }
        }
        row.addView(switch)

        // 点击整行也能切换
        row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
        return row
    }

    private fun configValue(c: ConfigData, key: String): Boolean = when (key) {
        "blockUpdate" -> c.blockUpdate
        "hideRelatedVideos" -> c.hideRelatedVideos
        "hideComments" -> c.hideComments
        "hideSearchPlaceholder" -> c.hideSearchPlaceholder
        "hideSearchSquares" -> c.hideSearchSquares
        "strictSearchResults" -> c.strictSearchResults
        else -> false
    }

    private fun withKey(c: ConfigData, key: String, value: Boolean): ConfigData = when (key) {
        "blockUpdate" -> c.copy(blockUpdate = value)
        "hideRelatedVideos" -> c.copy(hideRelatedVideos = value)
        "hideComments" -> c.copy(hideComments = value)
        "hideSearchPlaceholder" -> c.copy(hideSearchPlaceholder = value)
        "hideSearchSquares" -> c.copy(hideSearchSquares = value)
        "strictSearchResults" -> c.copy(strictSearchResults = value)
        else -> c
    }

    /**
     * 反射调用 builder 方法。三个候选 builder 的目标方法签名一致：
     * setTitle(CharSequence) / setView(View) / show()。
     * [arg] 为 null 表示无参方法（show），否则按参数运行时类型挑选单参重载。
     */
    private fun invoke(target: Any, method: String, arg: Any?): Any? {
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
        return m.invoke(target, *(if (arg == null) emptyArray() else arrayOf(arg)))
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
