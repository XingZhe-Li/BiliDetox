package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.findClassOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/** Bilibili 9.6.0 submitted-query comprehensive search response, before folding/grouping. */
class SearchResultsHook(private val loader: ClassLoader) : BaseHook {
    override val name = "SearchResults"
    private data class Session(val owner: java.lang.ref.WeakReference<Any>, val query: String,
        val gate: SearchResultGate<Any>)
    // P implements equals/hashCode over mutable fields: compare identity, never use it as a map key.
    private val sessions = mutableListOf<Session>()

    companion object {
        fun preloadDictionary() {
            StrictSearchMatcher.preload(
                { elapsed -> Log.d("搜索分词后台预加载完成：${elapsed}ms") },
                { error -> Log.e("搜索分词后台预加载失败，搜索时仍可尝试加载", error) },
            )
        }
    }

    override fun hook() {
        val mapper = "com.bilibili.search2.utils.f".findClassOrNull(loader) ?: return
        val method = mapper.declaredMethods.singleOrNull {
            it.name == "a" && it.returnType.name == "com.bilibili.search2.api.SearchResultAll" &&
                it.parameterTypes.firstOrNull()?.name == "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse"
        } ?: return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!RuntimeConfig.get().strictSearchResults) {
                    synchronized(sessions) { sessions.clear() }
                    return
                }
                val query = param.args[1] as? String ?: return
                if (query.isBlank()) return
                try {
                    val response = param.args[0]
                    val items = call(response, "getItemList") as List<*>
                    val page = (call(response, "getPage") as Number).toInt()
                    val entries = items.map { item ->
                        val value = item!!
                        val isVideo = call(value, "getCardItemCase").toString() == "AV"
                        val matches = if (isVideo) {
                            val av = call(value, "getAv")!!
                            StrictSearchMatcher.matches(query, text(av, "getTitle"),
                                text(av, "getDesc"), text(av, "getAuthor"))
                        } else false
                        SearchResultGate.Entry(value, isVideo, matches)
                    }
                    val decision = synchronized(sessions) {
                        // P is the result loader's per-query state, shared by its pagination calls.
                        val key = param.args[3]
                        sessions.removeAll { it.owner.get() == null }
                        val existing = sessions.firstOrNull { it.owner.get() === key }
                        val session = existing?.takeIf { it.query == query && page != 1 }
                            ?: Session(java.lang.ref.WeakReference(key), query, SearchResultGate()).also {
                                if (existing != null) sessions.remove(existing)
                                if (sessions.size >= 16) sessions.removeAt(0)
                                sessions.add(it)
                            }
                        session.gate.accept(page, entries)
                    }
                    val kept = decision.items
                    val removed = items.size - kept.size
                    if (removed == 0 && decision.status == SearchResultGate.Status.ALLOWED) return
                    // Keep server pagination, positions and metadata; rebuild only the repeated cards.
                    val builder = call(response, "toBuilder")!!
                    call(builder, "clearItem")
                    call(builder, "addAllItem", kept)
                    if (decision.status == SearchResultGate.Status.BLOCKED) {
                        // Terminate this query: later pages must not become a recommendation window.
                        val pagination = call(call(response, "getPagination")!!, "toBuilder")!!
                        call(pagination, "clearNext")
                        call(builder, "setPagination", call(pagination, "build")!!)
                    }
                    // Positional alienation folding is based on the original card count.
                    // Flatten it after removal so unrelated cards cannot reappear from folded lists.
                    val display = call(call(response, "getAppDisplayOption")!!, "toBuilder")!!
                    display.javaClass.getMethod("setAlienationCardCount", Int::class.javaPrimitiveType).invoke(display, 0)
                    display.javaClass.getMethod("setAlienationFoldCount", Int::class.javaPrimitiveType).invoke(display, 0)
                    call(builder, "setAppDisplayOption", call(display, "build")!!)
                    if (kept.isEmpty()) {
                        // A real host tip avoids the adapter's early return on an empty list.
                        val tipClass = loader.loadClass("com.bapis.bilibili.polymer.app.search.v1.SearchRecommendTipCard")
                        val tip = tipClass.getMethod("newBuilder").invoke(null)!!
                        val message = when (decision.status) {
                            SearchResultGate.Status.BLOCKED -> "第一页前10条视频未找到关键词匹配，已屏蔽本次搜索结果"
                            SearchResultGate.Status.ALLOWED -> "本页视频已被过滤，可关闭过滤后重新搜索"
                        }
                        call(tip, "setTitle", message)
                        val itemClass = loader.loadClass("com.bapis.bilibili.polymer.app.search.v1.Item")
                        val item = itemClass.getMethod("newBuilder").invoke(null)!!
                        call(item, "setGoto", "recommend_tips")
                        call(item, "setRecommendTips", call(tip, "build")!!)
                        call(builder, "addItem", call(item, "build")!!)
                    }
                    val filtered = call(builder, "build")!!
                    param.args[0] = filtered
                    Log.d("搜索过滤：验证状态=${decision.status}，当前批次=${items.size}，输出=${kept.size}")
                } catch (e: Throwable) {
                    // Commit only after the entire replacement is valid; incompatible builds retain results.
                    Log.e("严格搜索过滤失败，保留原始结果", e)
                }
            }
        })
        Log.d("已 hook 综合搜索严格过滤")
        if (RuntimeConfig.get().strictSearchResults) preloadDictionary()
    }

    private fun text(target: Any, method: String): String = call(target, method)?.toString().orEmpty()
    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.indices.all { m.parameterTypes[it].isInstance(args[it]) }
        } ?: throw NoSuchMethodException("${target.javaClass.name}#$name")
        return method.invoke(target, *args)
    }
}
