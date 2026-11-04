package com.github.xingzheli.bilidetox.hook

import java.lang.reflect.Method

/** Preserve the host's pause-page cleanup before suppressing an ad panel. */
object AdPanelSuppression {
    fun canSuppress(
        panelType: Int,
        callback: Any?,
        pauseListenerClass: Class<*>?,
        dismissMethod: Method?,
    ): Boolean {
        if (panelType != 3 && panelType != 4) return true
        if (callback == null || pauseListenerClass?.isInstance(callback) != true || dismissMethod == null) return false
        return try {
            dismissMethod.invoke(callback, false)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
