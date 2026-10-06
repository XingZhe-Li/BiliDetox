package com.github.xingzheli.bilidetox.hook

import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.findClassOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Explicit diagnostic builds only. Records search card content, never account credentials. */
class SearchDiagnosticsHook(private val loader: ClassLoader) : BaseHook {
    override val name = "SearchDiagnostics"
    private val sequence = AtomicInteger()

    override fun hook() {
        val mapper = "com.bilibili.search2.utils.f".findClassOrNull(loader) ?: return
        val method = mapper.declaredMethods.singleOrNull {
            it.name == "a" && it.returnType.name == "com.bilibili.search2.api.SearchResultAll" &&
                it.parameterTypes.firstOrNull()?.name == "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse"
        } ?: return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val id = sequence.incrementAndGet()
                    val response = param.args[0]
                    Log.d("SearchProbe batch=$id response=${scalarMetadata(response)}")
                    val annotation = getter(response, "getAnnotationMap") as? Map<*, *>
                    val safeAnnotation = annotation?.filterKeys {
                        val key = it.toString().lowercase()
                        !key.contains("id") && !key.contains("track") && !key.contains("user") && !key.contains("keyword")
                    }
                    Log.d("SearchProbe batch=$id annotationKeys=${annotation?.keys} annotation=$safeAnnotation")
                    Log.d("SearchProbe batch=$id pagination=${getter(response, "getPagination")}")
                    val raw = getter(response, "getItemList") as? List<*>
                        ?: getter(response, "getItemsList") as? List<*>
                    raw?.forEachIndexed { i, item ->
                        Log.d("SearchProbe batch=$id raw=$i ${scalarMetadata(item)}")
                        // getAv() returns a default message even for ads/author cards.
                        // Only actual AV oneof members are meaningful video samples.
                        if (getter(item, "getCardItemCase").toString() == "AV") {
                            val av = getter(item, "getAv")
                            val detail = JSONObject().apply {
                                put("title", limited(getter(av, "getTitle"), 500))
                                put("author", limited(getter(av, "getAuthor"), 100))
                                put("desc", limited(getter(av, "getDesc"), 600))
                                put("rcmdReason", limited(getter(av, "getRcmdReason"), 400))
                                put("highlightTags", limited(getter(av, "getHighlightTagsList"), 300))
                                put("hasFullText", getter(av, "hasFullText"))
                                val fullText = getter(av, "getFullText")
                                put("fullText", JSONObject().apply {
                                    put("type", getter(fullText, "getType"))
                                    put("text", limited(getter(fullText, "getText"), 600))
                                    put("prefix", limited(getter(fullText, "getPrefix"), 100))
                                })
                            }
                            Log.d("SearchProbe batch=$id raw=$i avDetail=$detail")
                        }
                    }
                    val result = param.result ?: return
                    val items = result.javaClass.getField("items").get(result) as? List<*> ?: return
                    Log.d("SearchProbe batch=$id count=${items.size}")
                    items.forEachIndexed { i, item ->
                        Log.d("SearchProbe batch=$id index=$i class=${item?.javaClass?.name} ${scalarMetadata(item)}")
                        Log.d("SearchProbe batch=$id index=$i mappedTitle=${JSONObject.quote(limited(getter(item, "getTitle"), 500))}")
                    }
                } catch (e: Throwable) { Log.e("SearchProbe failed", e) }
            }
        })
        Log.d("SearchProbe installed")
    }

    private fun getter(value: Any?, name: String): Any? = value?.javaClass?.methods
        ?.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(value)

    private fun limited(value: Any?, limit: Int): String {
        val text = value?.toString().orEmpty()
        return if (text.length > limit) text.take(limit) + "<truncated>" else text
    }

    private fun scalarMetadata(value: Any?): String {
        if (value == null) return "null"
        val allowed = setOf("getGoTo", "getGoto", "getSubGoto", "getCardType", "getCardItemCase",
            "getPage", "getPageNum", "getServerPagePos", "getPosition", "isInAlienationArea",
            "getModuleId", "getCardGroupId", "getIsRecommend", "getIsRec", "getResultType")
        return value.javaClass.methods.filter { it.parameterCount == 0 && it.name in allowed }
            .joinToString(" ") { "${it.name}=${it.invoke(value)}" }
    }
}
