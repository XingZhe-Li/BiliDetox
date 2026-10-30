package com.github.xingzheli.bilidetox.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
        XposedHelpers.setObjectField(page, "b", "空白")
        XposedHelpers.setObjectField(page, "c", URI)
        XposedHelpers.setObjectField(page, "d", factory)
        XposedHelpers.setObjectField(page, "f", "bilidetox_blank_home")
        return page
    }

    private fun createView(activity: Activity): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            // 宿主夜间主题的系统 colorBackground 仍可能为浅色，沿用按钮的主题判断。
            setBackgroundColor(if (SettingsTheme.isDark(activity)) Color.rgb(23, 24, 26) else Color.rgb(250, 250, 250))
        }
        val actions = listOf(
            "搜索" to "bilibili://search",
            "关注" to "bilibili://following/home",
            "个人收藏" to "bilibili://main/favorite",
            "浏览历史" to "bilibili://user_center/history",
        )
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (title, route) ->
                row.addView(Button(activity).apply {
                    text = title
                    textSize = 18f
                    SettingsTheme.styleButton(this)
                    setOnClickListener {
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)).setPackage(activity.packageName))
                        } catch (e: Exception) {
                            Log.e("打开首页快捷入口失败：$title", e)
                            Toast.makeText(activity, "无法打开$title，请从原应用入口进入", Toast.LENGTH_LONG).show()
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return ScrollView(activity).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
