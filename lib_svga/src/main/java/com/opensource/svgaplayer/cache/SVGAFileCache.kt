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

    /**
     * 缓存根目录, 仅 [init] 赋值。
     * 旧实现是 String 哨兵值("/") + 读取时 mkdirs 的副作用 getter——
     * 未初始化时读取会静默落到文件系统根目录(冷启动加载失败的根源之一),
     * 且"读路径即建目录"难以推理。现改为显式 [dir]: 未初始化时打错误日志并返回根目录兜底,
     * 目录创建只发生在 [init](经 LruDiskCache.initAsync 的 mkdirs)
     */
    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var diskCache: LruDiskCache? = null


    internal fun init(context: Context) {
        if (isInitialized()) return
        val dir = File(context.cacheDir, "svga")
        cacheDir = dir
        diskCache = LruDiskCache(dir, DEFAULT_MAX_CACHE_BYTES).apply { initAsync() }
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
            clearDir(dir().absolutePath)
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
        val dir = cacheDir
        return dir != null && dir.exists()
    }

    /** 未初始化时打错误日志并以根目录兜底(与旧行为一致, 便于暴露未 init 的接入错误) */
    private fun dir(): File {
        return cacheDir ?: File("/").also {
            LogUtils.error(TAG, "SVGACache is not init! call SVGAManager.init first")
        }
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
        return File(dir(), cacheKey)
    }

    internal fun buildCacheFile(cacheKey: String): File {
        return File(dir(), cacheKey)
    }

    internal fun buildAudioFile(audio: String): File {
        return File(dir(), "$audio.mp3")
    }

}
