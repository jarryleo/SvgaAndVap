package com.tencent.qgame.animplayer

import android.content.Context
import android.graphics.Bitmap
import cn.leo.base.cache.BitmapCache
import cn.leo.base.cache.BitmapDiskCache
import cn.leo.base.download.BitmapDownloadInterface
import cn.leo.base.download.BitmapDownloader
import cn.leo.base.download.DownloadStatus
import cn.leo.base.download.FileDownloadManager
import com.tencent.qgame.animplayer.cache.VapFileCache
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * @Author     :Leo
 * Date        :2026/2/3
 * Description : 初始化vap
 */
object VapManager : BitmapDownloadInterface {

    /** init 时记录，供 clearCache 清理共享 bitmap 磁盘缓存使用（避免持有外部 Context） */
    private var appContext: Context? = null

    /**
     * 下载器：统一走进程级共享实例，
     * 与 svga/bitmap 共享并发额度并按 url+file 做在途去重
     */
    val downloader by lazy {
        FileDownloadManager.shared
    }

    private var bitmapDownloader: BitmapDownloadInterface = BitmapDownloader

    /**
     * 初始化，不初始也能用，就是第一个vap没有缓存
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        VapFileCache.init(context)
    }

    /**
     * 设置图片下载器
     */
    fun setBitmapDownload(bitmapDownloadInterface: BitmapDownloadInterface) {
        bitmapDownloader = bitmapDownloadInterface
    }

    fun clearCache() {
        VapFileCache.clearCache()
        BitmapCache.INSTANCE.clear()
        appContext?.let { BitmapDiskCache.clear(it) }
    }

    /**
     * 下载文件
     */
    fun downLoad(url: String, file: File): Flow<DownloadStatus> {
        return downloader.download(url, file)
    }

    /**
     * 下载图片并返回bitmap
     */
    override suspend fun downloadBitmap(
        context: Context,
        url: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return bitmapDownloader.downloadBitmap(context, url, reqWidth, reqHeight)
    }
}