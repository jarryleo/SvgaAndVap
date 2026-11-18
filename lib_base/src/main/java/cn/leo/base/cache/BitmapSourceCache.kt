package cn.leo.base.cache

import android.content.Context
import java.io.File

/**
 * @Description bitmap 源文件磁盘缓存（url 下载的原始图片文件, 目录 cacheDir/bitmap_source/）
 *
 * 供 BitmapUrlDecoder 先落盘再解码: 大文件不必整体读入内存,
 * 且命中缓存后重复解码/多尺寸解码无需重新下载。
 * 存储委托 [LruDiskCache]: 默认 128MB 容量上限, 命中刷新 LRU 顺序,
 * 写入后台记账并按 lastModified 淘汰最旧条目
 */
object BitmapSourceCache {

    private const val DIR_NAME = "bitmap_source"

    /** 默认容量上限(原始图片源文件, 可重新下载恢复, 取中额度) */
    private const val DEFAULT_MAX_CACHE_BYTES = 128L * 1024 * 1024

    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var diskCache: LruDiskCache? = null

    /**
     * 初始化缓存目录（BaseInitProvider 进程启动时已自动调用，重复调用无副作用；
     * 未初始化时 [cacheFile] 返回 null，调用方放弃解码）。
     * 只记录路径不做磁盘 IO——Provider 的 onCreate 跑在主线程启动期，
     * 目录创建延迟到首次写入：FileDownloadManager 发布文件前会 mkdirs 父目录（IO 线程）
     */
    @Synchronized
    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, DIR_NAME).also { dir ->
                diskCache = LruDiskCache(dir, DEFAULT_MAX_CACHE_BYTES).apply { initAsync() }
            }
        }
    }

    /**
     * 缓存文件: cacheDir/bitmap_source/<MD5(url)>（目录未初始化时返回 null）
     */
    fun cacheFile(url: String): File? =
        cacheDir?.let { File(it, BitmapDiskCache.buildKey(url)) }

    /** 命中缓存文件后调用, 刷新 LRU 顺序 */
    fun touch(url: String) {
        diskCache?.touch(BitmapDiskCache.buildKey(url))
    }

    /** 下载落盘完成后调用, 记账并按容量上限触发淘汰 */
    fun recordWrite(url: String) {
        diskCache?.recordWrite(BitmapDiskCache.buildKey(url))
    }

    /** 调整容量上限(字节), 下一次写入/淘汰时生效 */
    fun resize(maxBytes: Long) {
        diskCache?.maxBytes = maxBytes
    }

    /**
     * 清空源文件缓存
     */
    fun clear() {
        cacheDir?.takeIf { it.exists() }?.listFiles()?.forEach { file ->
            file.delete()
        }
        diskCache?.resetIndex()
    }
}
