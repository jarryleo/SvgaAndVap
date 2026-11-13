package cn.leo.base.cache

import android.content.Context
import java.io.File

/**
 * @Description bitmap 源文件磁盘缓存（url 下载的原始图片文件, 目录 cacheDir/bitmap_source/）
 *
 * 供 BitmapUrlDecoder 先落盘再解码: 大文件不必整体读入内存,
 * 且命中缓存后重复解码/多尺寸解码无需重新下载
 */
object BitmapSourceCache {

    private const val DIR_NAME = "bitmap_source"

    private var cacheDir: File? = null

    /**
     * 初始化缓存目录（BaseInitProvider 进程启动时已自动调用，重复调用无副作用；
     * 未初始化时 [cacheFile] 返回 null，调用方放弃解码）。
     * 只记录路径不做磁盘 IO——Provider 的 onCreate 跑在主线程启动期，
     * 目录创建延迟到首次写入：FileDownloadManager 发布文件前会 mkdirs 父目录（IO 线程）
     */
    @Synchronized
    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, DIR_NAME)
        }
    }

    /**
     * 缓存文件: cacheDir/bitmap_source/<MD5(url)>（目录未初始化时返回 null）
     */
    fun cacheFile(url: String): File? =
        cacheDir?.let { File(it, BitmapDiskCache.buildKey(url)) }

    /**
     * 清空源文件缓存
     */
    fun clear() {
        cacheDir?.takeIf { it.exists() }?.listFiles()?.forEach { file ->
            file.delete()
        }
    }
}
