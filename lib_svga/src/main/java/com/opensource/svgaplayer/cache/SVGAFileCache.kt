package com.opensource.svgaplayer.cache

import android.content.Context
import cn.leo.base.cache.LruDiskCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * SVGA 缓存管理（文件缓存）
 *
 * 存储委托 [LruDiskCache]: 默认 256MB 容量上限, 命中刷新 LRU 顺序,
 * 写入(下载文件/解压目录)后台记账并按 lastModified 淘汰最旧条目
 */
object SVGAFileCache {
    enum class Type {
        ZIP,
        FILE
    }

    private const val TAG = "SVGACache"

    /** 默认容量上限(svga 通常为 zip 包 + 解压目录, 取较大额度) */
    internal const val DEFAULT_MAX_CACHE_BYTES = 256L * 1024 * 1024

    private var cacheDir: String = "/"
        get() {
            if (field != "/") {
                val dir = File(field)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            return field
        }

    @Volatile
    private var diskCache: LruDiskCache? = null


    internal fun init(context: Context) {
        if (isInitialized()) return
        cacheDir = "${context.cacheDir.absolutePath}/svga/"
        File(cacheDir).takeIf { !it.exists() }?.mkdirs()
        diskCache = LruDiskCache(File(cacheDir), DEFAULT_MAX_CACHE_BYTES).apply { initAsync() }
    }

    /** 调整容量上限(字节), 下一次写入/淘汰时生效 */
    internal fun resize(maxBytes: Long) {
        diskCache?.maxBytes = maxBytes
    }

    /**
     * 清理缓存
     */
    internal fun clearCache() {
        if (!isInitialized()) {
            LogUtils.error(TAG, "SVGACache is not init!")
            return
        }
        SvgaCoroutineManager.launchIo {
            clearDir(cacheDir)
            diskCache?.resetIndex()
            LogUtils.info(TAG, "Clear svga cache done!")
        }
    }

    // 清除目录下的所有文件
    internal fun clearDir(path: String) {
        try {
            val dir = File(path)
            dir.takeIf { it.exists() }?.let { parentDir ->
                parentDir.listFiles()?.forEach { file ->
                    if (!file.exists()) {
                        return@forEach
                    }
                    if (file.isDirectory) {
                        clearDir(file.absolutePath)
                    }
                    file.delete()
                }
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "Clear svga cache path: $path fail", e)
        }
    }

    private fun isInitialized(): Boolean {
        return "/" != cacheDir && File(cacheDir).exists()
    }

    /**
     * 判断缓存是否存在，存在则返回缓存类型（并刷新 LRU 顺序）
     */
    internal fun getCachedType(cacheKey: String): Type? {
        if (buildCacheFile(cacheKey).exists()) {
            diskCache?.touch(cacheKey)
            return Type.FILE
        }
        if (buildCacheDir(cacheKey).exists()) {
            diskCache?.touch(cacheKey)
            return Type.ZIP
        }
        return null
    }

    /**
     * 条目写入完成(下载文件落盘/解压目录完成)后记账, 超上限时后台淘汰
     */
    internal fun recordWrite(cacheKey: String) {
        diskCache?.recordWrite(cacheKey)
    }

    internal fun buildCacheKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    internal fun buildCacheKey(url: URL): String = buildCacheKey(url.toString())

    internal fun buildCacheDir(cacheKey: String): File {
        return File("$cacheDir$cacheKey/")
    }

    internal fun buildCacheFile(cacheKey: String): File {
        return File("$cacheDir$cacheKey")
    }

    internal fun buildAudioFile(audio: String): File {
        return File("$cacheDir$audio.mp3")
    }

}
