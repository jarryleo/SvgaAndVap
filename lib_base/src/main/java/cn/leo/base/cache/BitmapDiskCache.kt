package cn.leo.base.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * @Author     :Leo
 * Date        :2026/11/6
 * Description : bitmap 磁盘缓存（svga/vap 共享, 目录 cacheDir/bitmap/）
 */
object BitmapDiskCache {

    private const val DIR_NAME = "bitmap"

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
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$cacheKey.png")
    }

    /**
     * 清空 bitmap 磁盘缓存
     */
    fun clear(context: Context) {
        val dir = File(context.cacheDir, DIR_NAME)
        clearDir(dir.absolutePath)
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
