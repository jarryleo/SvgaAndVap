package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import cn.leo.base.cache.BitmapSourceCache
import cn.leo.base.download.DownloadException
import cn.leo.base.download.DownloadState
import cn.leo.base.download.FileDownloadManager
import cn.leo.base.log.BaseLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URL

/**
 * 通过Url解码 Bitmap
 *
 * 解码前先经 [FileDownloadManager.shared] 将源文件下载到 [BitmapSourceCache] 磁盘缓存
 * (在途去重与并发额度与其他模块统一), 再以文件流方式解码:
 * 大文件不会整体读入内存, 命中缓存也无需重复下载。
 * 缓存目录由 BaseInitProvider 进程启动时自动初始化；
 * 若宿主移除了自动初始化且从未手动 init，则放弃解码而不是自建网络流下载
 */
object BitmapUrlDecoder : BitmapDecoder<URL>() {
    private const val TAG = "BitmapUrlDecoder"

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: URL): ImageDecoder.Source? =
        resolveCacheFile(data)?.let { ImageDecoder.createSource(it) }

    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        // 缓存目录不可用（自动初始化被移除且从未手动 init）：放弃解码，
        // 不再退化为自建网络流下载——那会绕开统一下载链路且同一图解码两遍要下两次
        val file = resolveCacheFile(data)
        if (file == null) {
            BaseLog.e(TAG, "source cache dir unavailable, skip decode: $data")
            return null
        }
        return runCatching {
            file.inputStream().use { BitmapFactory.decodeStream(it, null, ops) }
        }.getOrNull()
    }

    /**
     * 获取可解码的缓存文件: 命中直接返回, 未命中则下载到临时文件后原子发布
     */
    private fun resolveCacheFile(url: URL): File? {
        val cacheFile = BitmapSourceCache.cacheFile(url.toString()) ?: return null
        if (cacheFile.exists()) return cacheFile
        return runCatching {
            downloadToFile(url, cacheFile)
            cacheFile
        }.getOrNull()
    }

    /**
     * 统一走 FileDownloadManager：同 url+file 的在途下载直接复用（并发解码不再重复下载），
     * 临时文件与原子发布由下载器负责。本方法是同步解码路径（onDecode 内调用），
     * 以 runBlocking 桥接，阻塞时长与旧的自建 OkHttp 下载一致（受 callTimeout 约束）
     */
    private fun downloadToFile(url: URL, cacheFile: File) {
        val status = runBlocking {
            FileDownloadManager.shared.download(url.toString(), cacheFile)
                .first { it.isSuccessful() || it.isFailed() || it.state == DownloadState.CANCELLED }
        }
        if (!status.isSuccessful()) {
            throw DownloadException(
                "download fail: ${status.exception?.message}", status.exception
            )
        }
    }
}
