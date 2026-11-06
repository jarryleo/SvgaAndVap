package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import cn.leo.base.cache.BitmapSourceCache
import cn.leo.base.download.FileDownloadManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * 通过Url解码 Bitmap
 *
 * 解码前先参照 FileDownloader 将源文件下载到 [BitmapSourceCache] 磁盘缓存,
 * 再以文件流方式解码: 大文件不会整体读入内存, 命中缓存也无需重复下载;
 * 缓存目录未初始化(未经 BitmapDownloader 走过一遍)时退化为直接解码网络流
 */
object BitmapUrlDecoder : BitmapDecoder<URL>() {
    private const val SIZE = 8192
    private val okHttpClient: OkHttpClient by lazy {
        FileDownloadManager.okHttpClient
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: URL): ImageDecoder.Source? =
        resolveCacheFile(data)?.let { ImageDecoder.createSource(it) }

    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        // 优先从磁盘缓存文件解码, 避免重复下载
        resolveCacheFile(data)?.let { file ->
            return runCatching {
                file.inputStream().use { BitmapFactory.decodeStream(it, null, ops) }
            }.getOrNull()
        }
        // 无缓存目录时退化为直接解码网络流
        return try {
            val request = Request.Builder()
                .url(data)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.body?.byteStream()?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, ops)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
     * 先写临时文件, 完整下载后原子发布:
     * 避免并发解码读到半成品文件, 失败也只清临时文件
     */
    private fun downloadToFile(url: URL, cacheFile: File) {
        val tmpFile = File("${cacheFile.absolutePath}.tmp_${System.nanoTime()}_${Thread.currentThread().id}")
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body ?: throw Exception("Response body is null")
                tmpFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
                FileOutputStream(tmpFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            if (!tmpFile.renameTo(cacheFile)) {
                // rename 失败（目标被并发占用等）时退化为拷贝发布
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }
}
