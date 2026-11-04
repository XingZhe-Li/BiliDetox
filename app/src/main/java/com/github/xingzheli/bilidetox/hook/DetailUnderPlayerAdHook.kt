package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

/**
 * 隐藏视频播放器下方的广告视图（基于 B 站 9.6.0 / 9060300）。
 *
 * DetailAdService 创建广告视图后收集其 visibleFlow。收集器接到 true 时会
 * 显示容器、播放入场动画并上报曝光；false 分支则由宿主自己隐藏容器。
 * 将 true 改为 false 可以复用宿主的隐藏路径，避免动画提前显示广告。
 * 宿主还暴露了直接显示容器的入口，需单独拦截。
 */
class DetailUnderPlayerAdHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "DetailUnderPlayerAd"

    private companion object {
        const val SERVICE = "com.bilibili.ship.theseus.ugc.ad.DetailAdService"
        const val VISIBILITY_CALLBACK =
            "com.bilibili.ship.theseus.ugc.ad.DetailAdService\$onCreateViews\$4\$2\$1"
        const val CONTINUATION = "kotlin.coroutines.Continuation"
    }

    override fun hook() {
        val service = SERVICE.findClassOrNull(classLoader)
        if (service == null) {
            Log.w("未找到 $SERVICE，播放器下方广告未拦截")
            return
        }

        hookVisibilityCallback(service)
        hookDirectShow(service)
    }

    private fun hookVisibilityCallback(service: Class<*>) {
        val callback = VISIBILITY_CALLBACK.findClassOrNull(classLoader)
        val continuation = CONTINUATION.findClassOrNull(classLoader)
        if (callback == null || continuation == null) {
            Log.w("未找到播放器下方广告可见性回调，无法拦截该路径")
            return
        }

        // 回调包含桥接重载；只匹配原始 boolean 参数，并确认它属于目标服务。
        val owner = callback.declaredFields.firstOrNull {
            it.name == "this\$0" && it.type == service
        }
        val invoke = callback.findMethodOrNull("invoke", Boolean::class.javaPrimitiveType!!, continuation)
        if (owner == null || invoke == null || invoke.returnType != Any::class.java ||
            Modifier.isStatic(invoke.modifiers)) {
            Log.w("播放器下方广告可见性回调签名不匹配，拒绝 hook")
            return
        }

        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!RuntimeConfig.get().purify.blockVideoDetailAdPanel) return
                if (param.args[0] != true) return
                param.args[0] = false
                Log.d("已隐藏播放器下方广告视图")
            }
        })
        Log.d("已 hook $VISIBILITY_CALLBACK#invoke(boolean, Continuation)")
    }

    private fun hookDirectShow(service: Class<*>) {
        val show = service.findMethodOrNull("showAdContainer")
        val hide = service.findMethodOrNull("hideAdContainer")
        if (show == null || hide == null || show.returnType != Void.TYPE || hide.returnType != Void.TYPE ||
            Modifier.isStatic(show.modifiers) || Modifier.isStatic(hide.modifiers)) {
            Log.w("播放器下方广告容器显示／隐藏方法签名不匹配，无法拦截直接显示")
            return
        }

        XposedBridge.hookMethod(show, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!RuntimeConfig.get().purify.blockVideoDetailAdPanel) return
                // 如果设置改变前容器已经显示，也使用宿主自己的隐藏入口将其收起。
                try {
                    hide.invoke(param.thisObject)
                } catch (e: Throwable) {
                    Log.e("隐藏播放器下方广告容器失败", e)
                }
                param.result = null
                Log.d("已拦播放器下方广告容器直接显示")
            }
        })
        Log.d("已 hook $SERVICE#showAdContainer()")
    }
}
