package com.github.xingzheli.bilidetox

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 运行时配置存储。
 *
 * 实现约束（按重要性排序）：
 *
 * 1. **模块与宿主同进程**，配置文件放在宿主自己的数据目录
 *    （appDataDir/files/bilidetox.json），读写都不需要跨应用权限；
 *    设置对话框与本对象同进程直写。
 * 2. **hook 调用点每次经 [get] 取值**，内部按文件 mtime 做缓存：
 *     - 未改动时只有一次 stat 的开销；
 *     - 对话框保存后，下一次页面构建立即生效，不需要重启应用。
 * 3. 读失败/文件不存在一律回退到默认值——配置坏了不能影响宿主启动。
 */
object RuntimeConfig {

    private const val FILE_NAME = "bilidetox.json"

    @Volatile
    private var file: File? = null

    @Volatile
    private var cached: ConfigData = ConfigData()

    @Volatile
    private var cachedMtime: Long = Long.MIN_VALUE

    /** 在模块入口调用一次；appDataDir 来自 LoadPackageParam，异常时退回常规路径。 */
    fun init(appDataDir: String?) {
        val base = appDataDir?.takeIf { it.isNotBlank() }
            ?: "/data/data/${Config.BILIBILI_PACKAGE}"
        file = File(base, "files/$FILE_NAME")
        cachedMtime = Long.MIN_VALUE // 强制下次 get() 重新读盘
    }

    /** 取当前配置（带 mtime 缓存）。所有 hook 的运行时行为都从这里读。 */
    fun get(): ConfigData {
        val f = file ?: return cached
        val mtime = try {
            if (f.exists()) f.lastModified() else Long.MIN_VALUE
        } catch (_: Throwable) {
            return cached
        }
        if (mtime != cachedMtime) {
            synchronized(this) {
                if (mtime != cachedMtime) {
                    cached = read(f)
                    cachedMtime = mtime
                }
            }
        }
        return cached
    }

    /** 由设置对话框调用：转换并写盘，随后立即刷新缓存。 */
    @Synchronized
    fun update(edit: (ConfigData) -> ConfigData): Boolean {
        val f = file ?: return false
        val current = get()
        val proposed = edit(current)
        val next = ConfigEditPolicy.prepareChange(current, proposed, System.currentTimeMillis())
        try {
            f.parentFile?.mkdirs()
            // 先完整写入同目录临时文件再原子替换；其他进程不会读到半份 JSON。
            val pending = File(f.parentFile, "$FILE_NAME.tmp")
            try {
                FileOutputStream(pending).use { stream ->
                    stream.write(toJson(next).toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                check(pending.renameTo(f)) { "无法替换配置文件" }
            } catch (e: Throwable) {
                pending.delete()
                throw e
            }
            cached = next
            cachedMtime = f.lastModified()
            Log.d("配置已保存: $next")
            if (next.strictSearchResults) {
                com.github.xingzheli.bilidetox.hook.SearchResultsHook.preloadDictionary()
            }
            return true
        } catch (e: Throwable) {
            Log.e("配置保存失败", e)
            return false
        }
    }

    private fun read(f: File): ConfigData = try {
        if (f.exists()) {
            val obj = JSONObject(f.readText())
            ConfigData(
                home = readHome(obj),
                blockUpdate = obj.optBoolean("blockUpdate", true),
                hideRelatedVideos = obj.optBoolean("hideRelatedVideos", false),
                hideComments = obj.optBoolean("hideComments", false),
                hideSearchPlaceholder = obj.optBoolean("hideSearchPlaceholder", false),
                hideSearchSquares = obj.optBoolean("hideSearchSquares", false),
                strictSearchResults = obj.optBoolean("strictSearchResults", false),
                timeLock = readTimeLock(obj.optJSONObject("timeLock")),
            )
        } else {
            ConfigData()
        }
    } catch (e: Throwable) {
        Log.e("配置文件解析失败，使用默认配置", e)
        ConfigData()
    }

    private fun readTimeLock(obj: JSONObject?): TimeLockConfig {
        if (obj == null) return TimeLockConfig()
        val array = obj.getJSONArray("periods")
        val periods = (0 until array.length()).map { index ->
            val period = array.getJSONObject(index)
            DailyTimeRange(period.getInt("startMinute"), period.getInt("endMinute"))
        }
        TimeLockPolicy.validate(periods)
        return TimeLockConfig(
            enabled = obj.optBoolean("enabled", false),
            periods = periods,
            editableAfter = obj.optLong("editableAfter", 0),
            pledgeUntil = obj.optLong("pledgeUntil", 0),
        )
    }

    private fun readHome(root: JSONObject): HomeConfig {
        val legacy = HomeConfig.fromLegacy(root.optBoolean("removeHomeTabs", true))
        val obj = root.optJSONObject("home") ?: return legacy
        return HomeConfig(
            blankHome = obj.optBoolean("blankHome", true),
            showRecommendation = obj.optBoolean("showRecommendation", legacy.showRecommendation),
            showPopular = obj.optBoolean("showPopular", legacy.showPopular),
            showLive = obj.optBoolean("showLive", true),
            showAnimation = obj.optBoolean("showAnimation", true),
            showCinema = obj.optBoolean("showCinema", true),
        )
    }

    private fun toJson(c: ConfigData): String = JSONObject().apply {
        put("home", JSONObject().apply {
            put("blankHome", c.home.blankHome)
            put("showRecommendation", c.home.showRecommendation)
            put("showPopular", c.home.showPopular)
            put("showLive", c.home.showLive)
            put("showAnimation", c.home.showAnimation)
            put("showCinema", c.home.showCinema)
        })
        put("blockUpdate", c.blockUpdate)
        put("hideRelatedVideos", c.hideRelatedVideos)
        put("hideComments", c.hideComments)
        put("hideSearchPlaceholder", c.hideSearchPlaceholder)
        put("hideSearchSquares", c.hideSearchSquares)
        put("strictSearchResults", c.strictSearchResults)
        put("timeLock", JSONObject().apply {
            put("enabled", c.timeLock.enabled)
            put("editableAfter", c.timeLock.editableAfter)
            put("pledgeUntil", c.timeLock.pledgeUntil)
            put("periods", JSONArray().apply {
                c.timeLock.periods.forEach { period ->
                    put(JSONObject().apply {
                        put("startMinute", period.startMinute)
                        put("endMinute", period.endMinute)
                    })
                }
            })
        })
    }.toString()
}
