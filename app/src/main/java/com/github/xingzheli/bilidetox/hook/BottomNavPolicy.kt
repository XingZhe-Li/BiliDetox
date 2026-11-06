package com.github.xingzheli.bilidetox.hook

/** 只识别 9.6.0 的底栏发布按钮与会员购路由，其他栏目原样保留。 */
object BottomNavPolicy {
    fun shouldShow(showPublish: Boolean, showMall: Boolean, isPublish: Boolean, uri: String?): Boolean {
        if (isPublish && !showPublish) return false
        val route = uri?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
        if (!showMall && (route.equals("bilibili://mall/home", ignoreCase = true) ||
                route.equals("bilibili://mall/homepage", ignoreCase = true))) return false
        return true
    }
}
