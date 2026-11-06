package com.opensource.svgaplayer.download

import cn.leo.base.download.FileDownloadManager
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

open class FileDownloader {

    companion object {
        private const val TAG = "SVGAFileDownloader"
        private const val SIZE = 4096
    }

    /**
     * 下载文件
     * @param url URL
     * @param complete 完成回调
     * @param failure 失败回调
     * @return 协程 job ，可取消下载任务
     */
    open fun resume(
        url: URL, complete: (inputStream: InputStream) -> Unit, failure: (e: Exception) -> Unit = {}
    ): Job {
        return SvgaCoroutineManager.launchIo {
            //下载到缓存地址
            val cacheKey = SVGAFileCache.buildCacheKey(url)
            val cacheFile = SVGAFileCache.buildCacheFile(cacheKey)
            //先写临时文件，完整下载后再发布：getCachedType 只判存在性，下载中的半成品一旦以正式
            //名字落盘，并发的解读者会把它当完整缓存解析失败并误删；取消/失败也只清临时文件
            val tmpFile =
                File("${cacheFile.absolutePath}.tmp_${System.nanoTime()}_${Thread.currentThread().id}")
            try {
                LogUtils.info(
                    TAG, "================ file download start ================ " +
                            "\r\n url = $url"
                )
                // 走 base 共享 OkHttpClient（连接复用，HTTPS 忽略证书校验与 vap 保持一致）
                val request = Request.Builder()
                    .url(url)
                    .build()
                FileDownloadManager.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body ?: throw Exception("Response body is null")
                    FileOutputStream(tmpFile).use { output ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(SIZE)
                            var count: Int
                            while (isActive) {
                                count = inputStream.read(buffer, 0, SIZE)
                                if (count == -1) {
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                if (isActive) {
                    LogUtils.info(
                        TAG, "================ file download success ================"
                    )
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    if (!tmpFile.renameTo(cacheFile)) {
                        //rename 失败（目标被并发占用等）时退化为拷贝发布
                        tmpFile.copyTo(cacheFile, overwrite = true)
                        tmpFile.delete()
                    }
                    complete(cacheFile.inputStream())
                } else {
                    LogUtils.info(
                        TAG, "================ file download cancel ================"
                    )
                    tmpFile.delete()
                    failure(CancellationException("download cancel"))
                }
            } catch (e: Exception) {
                tmpFile.delete()
                LogUtils.error(TAG, "================ file download fail ================")
                LogUtils.error(TAG, "error: ${e.message}")
                e.printStackTrace()
                failure(e)
            }
        }
    }
}
