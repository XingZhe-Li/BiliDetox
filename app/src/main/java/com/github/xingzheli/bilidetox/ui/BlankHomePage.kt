package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.xingzheli.bilidetox.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** 使用宿主自己的基础 Fragment 和分页描述，不依赖模块的 Manifest 或 AndroidX。 */
object BlankHomePage {
    const val URI = "bilidetox://home"
    private const val MARKER = "bilidetox.blank_home"

    fun install(loader: ClassLoader) {
        val fragment = loader.loadClass("androidx.fragment.app.Fragment")
        loader.loadClass("com.bilibili.lib.homepage.startdust.secondary.BasePrimaryMultiPageFragment\$b")
        loader.loadClass("nd0.g")
        XposedHelpers.findAndHookMethod(fragment, "onCreateView", LayoutInflater::class.java,
            ViewGroup::class.java, Bundle::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = XposedHelpers.callMethod(param.thisObject, "getArguments") as? Bundle
                    if (args?.getBoolean(MARKER) != true) return
                    val activity = XposedHelpers.callMethod(param.thisObject, "getActivity") as? Activity ?: return
                    param.result = createView(activity)
                }
            })
    }

    /** 字段由 9.6.0 的 HomeFragmentV2.Kf / secondary.c.a.getPage 核对。 */
    fun descriptor(loader: ClassLoader): Any {
        val page = XposedHelpers.newInstance(loader.loadClass(
            "com.bilibili.lib.homepage.startdust.secondary.BasePrimaryMultiPageFragment\$b"))
        val factoryClass = loader.loadClass("nd0.g")
        val constructor = factoryClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
        val factory = if (constructor != null) {
            constructor.isAccessible = true
            constructor.newInstance()
        } else {
            // 9.6.0 经 R8 优化后，此纯字段载体可能没有可反射的构造器。
            // 只分配这个已核对字段的载体；Fragment 本身仍按正常构造流程创建。
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, factoryClass)
        }
        XposedHelpers.setObjectField(factory, "a", loader.loadClass("androidx.fragment.app.Fragment"))
        XposedHelpers.setObjectField(factory, "b", Bundle().apply { putBoolean(MARKER, true) })
        XposedHelpers.setObjectField(page, "a", "bilidetox_blank_home")
        XposedHelpers.setObjectField(page, "b", "导航页")
        XposedHelpers.setObjectField(page, "c", URI)
        XposedHelpers.setObjectField(page, "d", factory)
        XposedHelpers.setObjectField(page, "f", "bilidetox_blank_home")
        return page
    }

    private fun createView(activity: Activity): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dark = SettingsTheme.isDark(activity)
        val background = if (dark) Color.rgb(23, 24, 26) else Color.rgb(250, 250, 250)
        val surface = if (dark) Color.rgb(34, 36, 41) else Color.WHITE
        val textColor = if (dark) Color.rgb(224, 226, 231) else Color.rgb(53, 55, 61)
        val muted = if (dark) Color.rgb(163, 167, 177) else Color.rgb(117, 122, 133)
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }
        fun clickableBackground(color: Int, radius: Int) = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x14000000),
            rounded(color, radius), rounded(Color.WHITE, radius))
        fun open(title: String, route: String) {
            try {
                if (route == "bilibili://offline/cache/home") {
                    // 新版缓存页仅注册在宿主 BLRouter，不能用外部 ACTION_VIEW 直接打开。
                    val loader = activity.classLoader
                    val builder = XposedHelpers.newInstance(loader.loadClass("com.bilibili.lib.blrouter.RouteRequest\$Builder"), route)
                    val request = XposedHelpers.callMethod(builder, "build")
                    // Compose 页面会在路由内部接管跳转；同步返回码不能代表最终页面是否打开。
                    XposedHelpers.callStaticMethod(loader.loadClass("com.bilibili.lib.blrouter.BLRouter"), "routeTo", request, activity)
                } else {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)).setPackage(activity.packageName))
                }
            } catch (e: Throwable) {
                Log.e("打开导航页入口失败：$title", e)
                Toast.makeText(activity, "无法打开$title，请从原应用入口进入", Toast.LENGTH_LONG).show()
            }
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        val search = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            this.background = clickableBackground(surface, 26)
            isFocusable = true
            contentDescription = "搜索，打开搜索页"
            setOnClickListener { open("搜索", "bilibili://search") }
            addView(NavigationIconView(activity, NavigationIconView.Kind.SEARCH, muted),
                LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(activity).apply {
                text = "搜索视频、UP 主"
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(12) })
        }
        list.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        data class Entry(val title: String, val route: String, val icon: NavigationIconView.Kind, val color: Int)
        val actions = listOf(
            Entry("关注", "bilibili://following/home", NavigationIconView.Kind.FOLLOWING, if (dark) 0xFFE978A0.toInt() else 0xFFB53A67.toInt()),
            Entry("离线缓存", "bilibili://offline/cache/home", NavigationIconView.Kind.DOWNLOADS, if (dark) 0xFF7EAFFF.toInt() else 0xFF386BBB.toInt()),
            Entry("历史记录", "bilibili://user_center/history", NavigationIconView.Kind.HISTORY, if (dark) 0xFFD9AA6B.toInt() else 0xFFA77730.toInt()),
            Entry("我的收藏", "bilibili://main/favorite", NavigationIconView.Kind.FAVORITES, if (dark) 0xFFE6A168.toInt() else 0xFFB97B31.toInt()),
            Entry("稍后再看", "bilibili://user_center/watch_later_v2", NavigationIconView.Kind.WATCH_LATER, if (dark) 0xFFA59AE6.toInt() else 0xFF7661B6.toInt()),
        )
        val shortcuts = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            this.background = rounded(surface, 16)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        actions.forEach { action ->
            val entry = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                this.background = clickableBackground(Color.TRANSPARENT, 12)
                isFocusable = true
                contentDescription = action.title
                setOnClickListener { open(action.title, action.route) }
                addView(FrameLayout(activity).apply {
                    this.background = rounded((action.color and 0x00FFFFFF) or (if (dark) 0x22000000 else 0x16000000), 20)
                    addView(NavigationIconView(activity, action.icon, action.color),
                        FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(activity).apply {
                    text = action.title
                    textSize = 12f
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            shortcuts.addView(entry, LinearLayout.LayoutParams(0, dp(88), 1f))
        }
        list.addView(shortcuts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        return ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
