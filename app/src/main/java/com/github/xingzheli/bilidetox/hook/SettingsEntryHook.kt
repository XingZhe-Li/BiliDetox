package com.github.xingzheli.bilidetox.hook

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import com.github.xingzheli.bilidetox.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy

/**
 * 在 B 站设置页里加入「BiliDetox 设置」入口。
 *
 * ## 目标（基于 9.6.0 的反编译结果）
 *
 * B 站 9.6.0 的主设置页是 androidx.preference 框架：
 * - 宿主 Activity：`com.bilibili.app.preferences.BiliPreferencesActivity`
 * - 主设置 Fragment：其内部类 `BiliPreferencesFragment`
 *   （extends `com.bilibili.lib.ui.BasePreferenceFragment`，
 *   最终是 `androidx.preference.PreferenceFragmentCompat`）
 *
 * hook `onCreatePreferences(Bundle, String)` 的 after 阶段，此时
 * PreferenceScreen 已经填充完毕，往末尾追加一个普通 Preference，
 * 点击后展示 [SettingsDialog]。
 *
 * androidx.preference 的类都在宿主 classloader 里，模块编译期没有
 * 这个依赖，因此全部走反射；点击监听器用动态代理实现。
 */
class SettingsEntryHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "SettingsEntry"

    override fun hook() {
        val fragmentCls =
            "com.bilibili.app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment"
                .findClassOrNull(classLoader)
        if (fragmentCls == null) {
            Log.w("未找到 BiliPreferencesFragment，设置入口未生效")
            return
        }

        val onCreate = fragmentCls.findMethodOrNull(
            "onCreatePreferences",
            Bundle::class.java,
            String::class.java,
        )
        if (onCreate == null) {
            Log.w("未找到 onCreatePreferences，设置入口未生效")
            return
        }

        XposedBridge.hookMethod(
            onCreate,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        addEntry(param.thisObject)
                    } catch (e: Throwable) {
                        Log.e("添加设置入口失败", e)
                    }
                }
            },
        )
        Log.d("已 hook 设置页 onCreatePreferences")
    }

    private fun addEntry(fragment: Any) {
        val activity = XposedHelpers.callMethod(fragment, "getActivity") as? Activity
        if (activity == null) {
            Log.w("设置页 getActivity() 为 null，本次不添加入口")
            return
        }
        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen")
        if (screen == null) {
            Log.w("设置页 PreferenceScreen 为 null，本次不添加入口")
            return
        }

        val prefCls = classLoader.loadClass("androidx.preference.Preference")
        val pref = prefCls.getConstructor(Context::class.java).newInstance(activity)

        prefCls.findMethodOrNull("setTitle", CharSequence::class.java)
            ?.invoke(pref, "BiliDetox 设置")
        prefCls.findMethodOrNull("setSummary", CharSequence::class.java)
            ?.invoke(pref, "隐藏推荐内容、热搜板块等自定义选项")

        // 监听器接口的名字在 B 站构建里被 R8 改过（直接 loadClass
        // "Preference$OnPreferenceClickListener" 会抛 ClassNotFoundException），
        // 所以从 setter 的参数类型里取真实的接口 Class。
        val setter = prefCls.methods
            .filter { it.name == "setOnPreferenceClickListener" && it.parameterTypes.size == 1 }
            .also { if (it.size != 1) Log.w("setOnPreferenceClickListener 匹配到 ${it.size} 个") }
            .firstOrNull() ?: run {
            Log.w("未找到 setOnPreferenceClickListener，设置入口未生效")
            return
        }
        val listenerCls = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerCls)) { _, _, _ ->
            SettingsDialog(activity, classLoader).show()
            true
        }
        setter.invoke(pref, listener)

        // PreferenceGroup#addPreference(Preference)。注意该方法声明在父类
        // PreferenceGroup 而不是运行时类 PreferenceScreen 上，必须搜公共
        // 方法（含继承），declaredMethods 会漏。
        val addPref = screen.javaClass.methods
            .filter { it.name == "addPreference" && it.parameterTypes.size == 1 }
        when (addPref.size) {
            1 -> addPref.single().invoke(screen, pref)
            else -> Log.w("PreferenceScreen#addPreference 匹配到 ${addPref.size} 个，拒绝注入")
        }
    }
}
