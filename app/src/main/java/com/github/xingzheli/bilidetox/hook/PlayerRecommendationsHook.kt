package com.github.xingzheli.bilidetox.hook

import android.view.View
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import com.github.xingzheli.bilidetox.findMethodOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 隐藏全屏播放器右边缘的「推荐拉条」。
 *
 * ## 目标（基于 9.6.0 的反编译结果）
 *
 * `com.bilibili.ship.theseus.ugc.recommend.j` 是一个 LinearLayout，同时实现
 * `tv.danmaku.biliplayerv2.widget.IControlWidget`：构造器自建 10dp×40dp 的
 * 半透明色块 + 箭头，外加 170dp×106dp 的推荐封面预览；onMeasure 里把自身
 * 平移到贴边位置。
 *
 * - `onWidgetActive()` 是它唯一的装配入口：注册控件观察者后调 `c(List)`
 *   用相关推荐列表算可见性；
 * - 点一下或向左拖动会走 `b(boolean)` → FullScreenPlayingBottomRecommendService
 *   `showRecommendCards` 拉起底部推荐卡片。
 *
 * 因此先在 `onWidgetActive` 处跳过整个装配（不注册观察者、不填数据、
 * 不响应触摸），拉条与它拉起的底部卡片都不会出现；`c(List)` 处则把相关
 * 推荐列表裁空（见 [PlayerRecommendationsPolicy.filterList] 的空列表契约），
 * 由宿主自己的 `size > 3` 规则算出 GONE——不绕过它，因为它覆写的
 * setVisibility 还要同步 translationX。
 *
 * 开关复用 ConfigData.hideRelatedVideos，判定全部交给 [PlayerRecommendationsPolicy]。
 */
class PlayerRecommendationsHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "PlayerRecommendations"

    private companion object {
        const val PULLER = "com.bilibili.ship.theseus.ugc.recommend.j"
        const val CONTROL_WIDGET = "tv.danmaku.biliplayerv2.widget.IControlWidget"
        const val ACTIVE = "onWidgetActive"
        const val UPDATE_LIST = "c"
    }

    override fun hook() {
        val puller = PULLER.findClassOrNull(classLoader)
        if (puller == null) {
            Log.w("未找到全屏推荐拉条 $PULLER，隐藏拉条未生效")
            return
        }

        // 同名短类名不止一处，用接口认人：装配入口与观察者协议都在 IControlWidget 上。
        val controlWidget = CONTROL_WIDGET.findClassOrNull(classLoader)
        if (controlWidget == null || !controlWidget.isAssignableFrom(puller)) {
            Log.w("$PULLER 未实现 $CONTROL_WIDGET，拒绝 hook 以免误伤同名类")
            return
        }

        val active = puller.findMethodOrNull(ACTIVE)
        if (active == null) {
            Log.w("未找到 $PULLER#$ACTIVE()，隐藏拉条未生效")
            return
        }
        XposedBridge.hookMethod(
            active,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PlayerRecommendationsPolicy.shouldSuppress(RuntimeConfig.get())) return
                    hide(param.thisObject)
                    // 跳过原方法：观察者不注册，c(List) 不会被调用，
                    // 触摸回调也就不存在，拉条与底部卡片一起不出现。
                    param.setResult(null)
                }
            },
        )
        Log.d("已 hook $PULLER#$ACTIVE()：开关打开时置 GONE 并跳过装配")

        // 装配之外也会直接刷新列表（未来版本可能从别处调用），这里按同一契约裁空。
        val update = puller.findMethodOrNull(UPDATE_LIST, List::class.java)
        if (update == null) {
            Log.w("未找到 $PULLER#$UPDATE_LIST(List)，推荐列表裁剪未生效")
        } else {
            XposedBridge.hookMethod(
                update,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = RuntimeConfig.get()
                        if (!PlayerRecommendationsPolicy.shouldSuppress(config)) return
                        val filtered = PlayerRecommendationsPolicy.filterList(config, param.args[0] as? List<Any?>)
                        // 同一引用说明无需改动，避免无谓回写。
                        if (filtered !== param.args[0]) param.args[0] = filtered
                    }
                },
            )
            Log.d("已 hook $PULLER#$UPDATE_LIST(List)：开关打开时把相关推荐列表裁空，宿主自行判定不可见")
        }
    }

    /** 控件自身就是 View；取不到位或置位失败都只记录，绝不把异常抛回宿主。 */
    private fun hide(target: Any?) {
        val view = target as? View ?: return
        try {
            view.visibility = View.GONE
        } catch (e: Throwable) {
            Log.w("推荐拉条置 GONE 失败: " + e.javaClass.simpleName)
        }
    }
}
