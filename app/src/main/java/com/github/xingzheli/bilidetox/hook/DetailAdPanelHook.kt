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
 * IAdPanelListener)` 分发暂停页广告和其它广告面板：
 *
 * - `panelType == 3 || 4`：暂停页广告（同分支日志 "paused page panel already showing"），
 *   数据来自服务端 `PlayPause`（`KPlayPauseReply.type == PAUSE_TYPE_ADS`）；
 * - 其它 `panelType`：播放器下方的 under-player / upper 广告面板。
 *
 * 暂停广告调用前宿主已设置面板可见状态。拦截前必须通知已确认的暂停监听器
 * 清理状态；无法确认或清理失败时放行，避免宿主停留在错误状态。
 *
 * ## 为什么拦整个方法而不是只拦 3/4
 *
 * 该仓库里构造的只有 `AdPanelHelper`（广告面板），不存在非广告用途，所以按
 * 「所有 panelType 一律不显示」处理；新版本若新增面板类型也会自动覆盖，属于故意
 * 的 fail-closed 设计。反过来说：若将来该入口被复用为非广告面板，必须收窄成白名单。
 *
 * 只拦「面板」这一层：广告请求仍会发出（本 hook 不改请求与服务端判定逻辑）。
 * 播放器下方广告视图由 DetailUnderPlayerAdHook 单独处理。
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
        const val PAUSE_LISTENER = "com.bilibili.ship.theseus.united.page.pausedpage.u"
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
        // 9.6.0 的暂停监听器关闭时清理 toast、待处理标记及 PANEL_VISIBLE 状态。
        // 在调用线程同步完成清理，避免延迟回调误清理下一轮暂停流程。
        val pauseListener = PAUSE_LISTENER.findClassOrNull(classLoader)
            ?.takeIf { listener.isAssignableFrom(it) }
        val dismiss = try {
            listener.getMethod("onPanelDismiss", Boolean::class.javaPrimitiveType!!)
        } catch (_: ReflectiveOperationException) {
            null
        }

        XposedBridge.hookMethod(
            showPanel,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeConfig.get().purify.blockVideoDetailAdPanel) return
                    val panelType = param.args[0] as? Int ?: return
                    if (!AdPanelSuppression.canSuppress(panelType, param.args[2], pauseListener, dismiss)) {
                        Log.w("暂停广告监听器无法安全清理，保留原面板流程 panelType=$panelType")
                        return
                    }
                    param.setResult(null)
                    Log.d("已拦详情页广告面板 panelType=$panelType")
                }
            },
        )
        Log.d("已 hook $REPOSITORY#$SHOW_PANEL：暂停广告先清理宿主状态")
    }
}
