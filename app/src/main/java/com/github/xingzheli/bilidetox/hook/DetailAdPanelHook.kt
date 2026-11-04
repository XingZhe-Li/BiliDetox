package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 隐藏视频详情页的所有广告面板（暂停页广告 + 播放器下方推荐广告等）。
 *
 * ## 目标（基于 9.6.0 / 9060300 的反编译结果）
 *
 * `com.bilibili.ship.theseus.united.page.ad.AdPanelRepository#showPanel(int, IPanelData,
 * IAdPanelListener)` 是详情页广告面板的唯一分发入口，两类面板都从它出去：
 *
 * - `panelType == 3 || 4`：暂停页广告（同分支日志 "paused page panel already showing"），
 *   数据来自服务端 `PlayPause`（`KPlayPauseReply.type == PAUSE_TYPE_ADS`）；
 * - 其它 `panelType`：播放器下方的 under-player / upper 广告面板。
 *
 * 两类都不创建、不显示，就在这里直接返回：不会走到 `new AdPanelHelper(...)`，
 * 因此面板对象、浮层与入场动画都不会出现，也没有空白残留。
 *
 * ## 为什么拦整个方法而不是只拦 3/4
 *
 * 该仓库里构造的只有 `AdPanelHelper`（广告面板），不存在非广告用途，所以按
 * 「所有 panelType 一律不显示」处理；新版本若新增面板类型也会自动覆盖，属于故意
 * 的 fail-closed 设计。反过来说：若将来该入口被复用为非广告面板，必须收窄成白名单。
 *
 * 只拦「面板」这一层：广告请求仍会发出（本 hook 不改请求与服务端判定逻辑），
 * 其它无关 UI（评论区、简介、相关推荐等）完全不受影响。
 *
 * 开关：设置 → 净化管理 → 「详情页广告面板」（`PurifyConfig#blockVideoDetailAdPanel`，
 * 默认开启）；关闭后每次调用直接放行，行为与官方一致。
 */
class DetailAdPanelHook(private val classLoader: ClassLoader) : BaseHook {

    override val name = "DetailAdPanel"

    private companion object {
        const val REPOSITORY = "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository"
        const val SHOW_PANEL = "showPanel"
        const val PANEL_DATA = "com.bilibili.gripper.api.ad.biz.videodetail.IPanelData"
        const val LISTENER = "com.bilibili.gripper.api.ad.biz.videodetail.IAdPanelListener"
    }

    override fun hook() {
        val repository = REPOSITORY.findClassOrNull(classLoader)
        if (repository == null) {
            Log.w("未找到 $REPOSITORY，详情页广告面板未拦截")
            return
        }

        // 同名重载不止一个，按「int + IPanelData + IAdPanelListener」认签名。
        val panelData = PANEL_DATA.findClassOrNull(classLoader)
        val listener = LISTENER.findClassOrNull(classLoader)
        if (panelData == null || listener == null) {
            Log.w("未找到 IPanelData / IAdPanelListener，详情页广告面板未拦截")
            return
        }

        val showPanel = repository.declaredMethods.firstOrNull { method ->
            method.name == SHOW_PANEL &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == panelData &&
                method.parameterTypes[2] == listener
        }
        if (showPanel == null) {
            Log.w("未匹配 $REPOSITORY#$SHOW_PANEL(int, IPanelData, IAdPanelListener)，详情页广告面板未拦截")
            return
        }
        showPanel.isAccessible = true

        XposedBridge.hookMethod(
            showPanel,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().purify.blockVideoDetailAdPanel) return
                    // panelType 3/4 是暂停页广告，其余是 under-player 等广告面板；全部不显示。
                    val panelType = param.args[0] as? Int ?: return
                    param.setResult(null)
                    Log.d("已拦详情页广告面板 panelType=$panelType")
                }
            },
        )
        Log.d("已 hook $REPOSITORY#$SHOW_PANEL：所有 panelType 的广告面板都不创建、不显示")
    }
}
