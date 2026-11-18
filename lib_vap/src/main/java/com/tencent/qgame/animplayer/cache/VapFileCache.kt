package com.tencent.qgame.animplayer.cache

import android.content.Context
import cn.leo.base.cache.LruDiskCache
import com.tencent.qgame.animplayer.util.ALog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * vap 缓存管理（文件缓存）
 *
 * 存储委托 [LruDiskCache]: 默认 512MB 容量上限(mp4 体积较大), 命中刷新 LRU 顺序,
 * 下载落盘后台记账并按 lastModified 淘汰最旧条目
 */
object VapFileCache : CoroutineScope by MainScope() {

    private const val TAG = "VapFileCache"

    /** 默认容量上限(mp4 动画单文件数 MB, 取较大额度) */
    internal const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024 * 1024

    private var cacheDir: String = ""
        get() {
            if (field != "") {
                val dir = File(field)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            return field
        }

    @Volatile
    private var diskCache: LruDiskCache? = null


    internal fun init(context: Context, onInitComplete: () -> Unit = {}) {
        if (isInitialized()) return
        launch(Dispatchers.IO) {
            cacheDir = "${context.cacheDir.absolutePath}/vap/"
            runCatching { File(cacheDir).takeIf { !it.exists() }?.mkdirs() }
            diskCache = LruDiskCache(File(cacheDir), DEFAULT_MAX_CACHE_BYTES).apply { initAsync() }
            onInitComplete.invoke()
        }
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
            ALog.e(TAG, "vapCache is not init!")
            return
        }
        launch(Dispatchers.IO) {
            clearDir(cacheDir)
            diskCache?.resetIndex()
            ALog.i(TAG, "Clear vap cache done!")
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
            ALog.e(TAG, "Clear vap cache path: $path fail", e)
        }
    }

    private fun isInitialized(): Boolean {
        return ("" != cacheDir) && File(cacheDir).exists()
    }

    /** 命中缓存文件后调用, 刷新 LRU 顺序 */
    internal fun touch(cacheKey: String) {
        diskCache?.touch("$cacheKey.mp4")
    }

    /** 下载落盘完成后调用, 记账并按上限触发淘汰 */
    internal fun recordWrite(cacheKey: String) {
        diskCache?.recordWrite("$cacheKey.mp4")
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

    internal fun buildCacheFile(cacheKey: String, context: Context): Flow<File> {
        return callbackFlow {
            if (cacheDir.isEmpty()) {
                init(context) {
                    trySend(File("$cacheDir$cacheKey.mp4"))
                }
            } else {
                trySend(File("$cacheDir$cacheKey.mp4"))
            }
            awaitClose {
                cancel()
            }
        }
    }
}
