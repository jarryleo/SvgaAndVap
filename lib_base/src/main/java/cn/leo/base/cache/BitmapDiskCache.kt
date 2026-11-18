package cn.leo.base.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * @Author     :Leo
 * Date        :2026/11/6
 * Description : bitmap 磁盘缓存（svga/vap 共享, 目录 cacheDir/bitmap/）
 *
 * 存储委托 [LruDiskCache]: 默认 64MB 容量上限(解码后的 PNG 重复体), 命中刷新 LRU 顺序,
 * 写入后台记账并按 lastModified 淘汰最旧条目
 */
object BitmapDiskCache {

    private const val DIR_NAME = "bitmap"

    /** 默认容量上限(存放的是可由源文件重新解码的 PNG, 取小额度) */
    private const val DEFAULT_MAX_CACHE_BYTES = 64L * 1024 * 1024

    @Volatile
    private var diskCache: LruDiskCache? = null

    /**
     * 缓存 key: 字符串的 MD5 十六进制（与原 svga/vap 实现保持一致）
     */
    fun buildKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    /**
     * 缓存文件: cacheDir/bitmap/<key>.png（目录不存在时自动创建）
     */
    fun cacheFile(context: Context, cacheKey: String): File {
        ensureDiskCache(context)
        return File(dir(context), "$cacheKey.png")
    }

    /** 命中缓存文件后调用, 刷新 LRU 顺序 */
    fun touch(context: Context, cacheKey: String) {
        ensureDiskCache(context)?.touch("$cacheKey.png")
    }

    /** 写入完成后调用, 记账并按容量上限触发淘汰 */
    fun recordWrite(context: Context, cacheKey: String) {
        ensureDiskCache(context)?.recordWrite("$cacheKey.png")
    }

    /**
     * 清空 bitmap 磁盘缓存
     */
    fun clear(context: Context) {
        val dir = dir(context)
        clearDir(dir.absolutePath)
        diskCache?.resetIndex()
    }

    // ------------------------------------------------------------------

    private fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    @Synchronized
    private fun ensureDiskCache(context: Context): LruDiskCache? {
        if (!dir(context).exists()) {
            dir(context).mkdirs()
        }
        val current = diskCache
        if (current != null && current.dir == dir(context)) return current
        return LruDiskCache(dir(context), DEFAULT_MAX_CACHE_BYTES).apply {
            initAsync()
        }.also { diskCache = it }
    }

    // 清除目录下的所有文件
    private fun clearDir(path: String) {
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
            // ignored
        }
    }
}
