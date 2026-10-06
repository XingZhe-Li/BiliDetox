package com.github.xingzheli.bilidetox.hook

import java.text.Normalizer
import java.util.Locale
import com.huaban.analysis.jieba.JiebaSegmenter
import java.util.concurrent.atomic.AtomicBoolean

/** Literal matching only; comments, subtitles and recommendation reasons are excluded. */
object StrictSearchMatcher {
    private val markup = Regex("<[^>]*>")
    private val queryRuns = Regex("[\\u3400-\\u9fff]+|[a-z0-9]+[+#]*")
    private val chinese = Regex("[\\u3400-\\u9fff]+")
    private val stopWords = setOf("的", "了", "和", "与", "是", "在", "我", "你", "他", "她", "它",
        "这个", "那个", "一个", "什么", "怎么", "如何", "我们", "你们", "他们")
    private val segmenter by lazy { JiebaSegmenter() }
    private val preloadStarted = AtomicBoolean(false)

    /** Shares the same synchronized lazy instance with searches; never load a second dictionary. */
    fun preload(onReady: (Long) -> Unit, onFailure: (Throwable) -> Unit) {
        if (!preloadStarted.compareAndSet(false, true)) return
        try {
            Thread({
                try {
                    val started = System.nanoTime()
                    segmenter
                    onReady((System.nanoTime() - started) / 1_000_000)
                } catch (e: Throwable) {
                    preloadStarted.set(false)
                    onFailure(e)
                }
            }, "BiliDetox-tokenizer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        } catch (e: Throwable) {
            preloadStarted.set(false)
            onFailure(e)
        }
    }
    private val queries = object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 32
    }
    fun matches(query: String, title: String, description: String, author: String): Boolean {
        val terms = keywords(query)
        if (terms.isEmpty()) return true
        val fields = listOf(title, description, author).map(::normalize)
        return terms.any { term ->
            fields.any { it.contains(term) }
        }
    }

    /** Segment the query once; document fields remain literal substring matches. */
    @Synchronized
    fun keywords(query: String): List<String> {
        val canonical = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        queries[canonical]?.let { return it }
        val terms = queryRuns.findAll(canonical).flatMap { match ->
            val run = match.value
            if (chinese.matches(run)) {
                segmenter.process(run, JiebaSegmenter.SegMode.INDEX).asSequence()
                    .map { it.word }.filter { it.length >= 2 && it !in stopWords }
            } else sequenceOf(run)
        }.distinct().toList().ifEmpty {
            // One-character/unknown queries still use their complete literal form.
            listOf(normalize(query)).filter(String::isNotEmpty)
        }
        queries[canonical] = terms
        return terms
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        markup.replace(value, "").replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">"), Normalizer.Form.NFKC
    ).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' || it == '#' }
}
