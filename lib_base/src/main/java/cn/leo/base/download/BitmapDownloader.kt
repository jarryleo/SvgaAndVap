package cn.leo.base.download

import android.content.Context
import android.graphics.Bitmap
import cn.leo.base.bitmap.BitmapFileDecoder
import cn.leo.base.bitmap.BitmapUrlDecoder
import cn.leo.base.cache.BitmapCache
import cn.leo.base.cache.BitmapDiskCache
import cn.leo.base.cache.BitmapSourceCache
import cn.leo.base.log.BaseLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * @Author     :Leo
 * Date        :2024/7/2
 * Description : Bitmap下载器（svga/vap 共享, 内存缓存 -> 磁盘缓存 -> 网络下载）
 */
object BitmapDownloader : BitmapDownloadInterface {
    private const val TAG = "BitmapDownloader"
    private val downLoadQueue = ConcurrentLinkedQueue<String>()

    override suspend fun downloadBitmap(
        context: Context,
        url: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        // 初始化源文件缓存目录, 供 BitmapUrlDecoder 下载落盘后再解码
        BitmapSourceCache.init(context)
        val key = BitmapCache.createKey(url, reqWidth, reqHeight)
        // 从内存缓存中获取
        val cacheData = BitmapCache.INSTANCE.getData(key)
        if (cacheData != null) {
            BaseLog.d(
                TAG,
                "memory cache hit, bitmap = $cacheData, reqWidth = $reqWidth, reqHeight = $reqHeight"
            )
            return cacheData
        }
        // 从磁盘缓存中获取
        val diskData = getBitmapFromDisk(context, key, reqWidth, reqHeight)
        if (diskData != null) {
            BitmapCache.INSTANCE.putData(key, diskData)
            BaseLog.d(
                TAG,
                "disk cache hit, bitmap = $cacheData, reqWidth = $reqWidth, reqHeight = $reqHeight"
            )
            return diskData
        }
        if (downLoadQueue.contains(url)) {
            return null
        }
        BaseLog.d(
            TAG,
            "downloadBitmap url = $url, reqWidth = $reqWidth, reqHeight = $reqHeight"
        )
        downLoadQueue.add(url)
        val bitmap = withTimeoutOrNull(30.seconds) {
            suspendCancellableCoroutine {
                val decode = try {
                    URLDecoder.decode(url, "UTF-8")
                } catch (e: Exception) {
                    e.printStackTrace()
                    url
                }
                val urlSafe = try {
                    URL(URLDecoder.decode(decode, "UTF-8"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    it.resume(null)
                    return@suspendCancellableCoroutine
                }
                val bitmap = BitmapUrlDecoder.decodeBitmapFrom(
                    urlSafe,
                    reqWidth,
                    reqHeight
                )
                if (bitmap != null) {
                    launch(Dispatchers.IO) {
                        cacheBitmapToDisk(context, key, bitmap)
                    }
                }
                it.resume(bitmap)
            }
        }
        downLoadQueue.remove(url)
        BaseLog.d(TAG, "downloadBitmap bitmap = $bitmap")
        if (bitmap != null) {
            BitmapCache.INSTANCE.putData(key, bitmap)
        }
        return bitmap
    }

    private fun getBitmapFromDisk(
        context: Context,
        cacheKey: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val cacheFile = BitmapDiskCache.cacheFile(context, cacheKey)
        if (!cacheFile.exists()) {
            return null
        }
        return BitmapFileDecoder.decodeBitmapFrom(cacheFile.toString(), reqWidth, reqHeight)
    }

    private suspend fun cacheBitmapToDisk(
        context: Context,
        cacheKey: String,
        bitmap: Bitmap
    ) = withContext(Dispatchers.IO) {
        val cacheFile = BitmapDiskCache.cacheFile(context, cacheKey)
        if (!cacheFile.exists()) {
            cacheFile.createNewFile()
        }
        cacheFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
