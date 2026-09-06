package com.github.xingzheli.bilidetox

import org.json.JSONObject
import java.io.File

/**
 * 一份配置的不可变快照。
 *
 * 字段默认值 = 未写过配置文件时的行为，其中 removeHomeTabs / blockUpdate
 * 与旧版硬编码 Config 的行为一致，新增功能默认关闭。
 */
data class ConfigData(
    /** 移除首页顶栏的「推荐」「热门」Tab */
    val removeHomeTabs: Boolean = true,
    /** 禁用应用内更新检查 */
    val blockUpdate: Boolean = true,
    /** 视频页隐藏「相关推荐」 */
    val hideRelatedVideos: Boolean = false,
    /** 视频页隐藏评论区 */
    val hideComments: Boolean = false,
    /** 首页搜索框隐藏自动填充的话题（默认搜索词） */
    val hideSearchPlaceholder: Boolean = false,
    /** 搜索页隐藏「bilibili热搜」「搜索发现」板块 */
    val hideSearchSquares: Boolean = false,
)

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
    fun update(edit: (ConfigData) -> ConfigData) {
        val f = file ?: return
        val next = edit(get())
        try {
            f.parentFile?.mkdirs()
            f.writeText(toJson(next))
            cached = next
            cachedMtime = f.lastModified()
            Log.d("配置已保存: $next")
        } catch (e: Throwable) {
            Log.e("配置保存失败", e)
        }
    }

    private fun read(f: File): ConfigData = try {
        if (f.exists()) {
            val obj = JSONObject(f.readText())
            ConfigData(
                removeHomeTabs = obj.optBoolean("removeHomeTabs", true),
                blockUpdate = obj.optBoolean("blockUpdate", true),
                hideRelatedVideos = obj.optBoolean("hideRelatedVideos", false),
                hideComments = obj.optBoolean("hideComments", false),
                hideSearchPlaceholder = obj.optBoolean("hideSearchPlaceholder", false),
                hideSearchSquares = obj.optBoolean("hideSearchSquares", false),
            )
        } else {
            ConfigData()
        }
    } catch (e: Throwable) {
        Log.e("配置文件解析失败，使用默认配置", e)
        ConfigData()
    }

    private fun toJson(c: ConfigData): String = JSONObject().apply {
        put("removeHomeTabs", c.removeHomeTabs)
        put("blockUpdate", c.blockUpdate)
        put("hideRelatedVideos", c.hideRelatedVideos)
        put("hideComments", c.hideComments)
        put("hideSearchPlaceholder", c.hideSearchPlaceholder)
        put("hideSearchSquares", c.hideSearchSquares)
    }.toString()
}
