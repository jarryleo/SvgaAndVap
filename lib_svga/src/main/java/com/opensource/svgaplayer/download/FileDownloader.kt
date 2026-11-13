package com.opensource.svgaplayer.download

import cn.leo.base.download.DownloadState
import cn.leo.base.download.DownloadStatus
import cn.leo.base.download.FileDownloadManager
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import java.io.InputStream
import java.net.URL

/**
 * [FileDownloadManager.shared] 的回调式适配层：
 * 下载本体统一走共享下载器（并发额度、同 url+file 在途去重、取消中断网络读），
 * 对 SVGAParser/SVGAManager 保持原有的回调 API 不变
 */
open class FileDownloader {

    companion object {
        private const val TAG = "SVGAFileDownloader"
    }

    /**
     * 下载文件
     * @param url URL
     * @param complete 完成回调
     * @param failure 失败回调
     * @param onDownloadState 下载状态回调（含 QUEUED/DOWNLOADING 进度与终态），
     * 在调用方协程（IO）上回调，主线程切换由上层负责；与 vap 的 downloadState 语义对齐
     * @return 协程 job ，可取消下载任务
     */
    open fun resume(
        url: URL,
        complete: (inputStream: InputStream) -> Unit,
        failure: (e: Exception) -> Unit = {},
        onDownloadState: ((DownloadStatus) -> Unit)? = null
    ): Job {
        val urlString = url.toString()
        //下载到缓存地址
        val cacheKey = SVGAFileCache.buildCacheKey(url)
        val cacheFile = SVGAFileCache.buildCacheFile(cacheKey)
        return SvgaCoroutineManager.launchIo {
            LogUtils.info(
                TAG, "================ file download start ================ " +
                        "\r\n url = $urlString"
            )
            try {
                //StateFlow 不会正常结束，用 first 只等首个终态后返回，包装 Job 才能按期完成，
                //SVGADiskLoadingQueue 的 in-flight 移交（下载完成→解码协程注册）依赖这一点
                val status = FileDownloadManager.shared.download(urlString, cacheFile)
                    .onEach { status ->
                        //转发含进度与终态的全部状态，供上层做下载进度回调（终态仍由下方统一处理）
                        onDownloadState?.invoke(status)
                    }
                    .first {
                        it.state != DownloadState.QUEUED && it.state != DownloadState.DOWNLOADING
                    }
                when {
                    status.isSuccessful() -> {
                        LogUtils.info(
                            TAG, "================ file download success ================"
                        )
                        complete((status.file ?: cacheFile).inputStream())
                    }

                    status.state == DownloadState.CANCELLED -> {
                        LogUtils.info(
                            TAG, "================ file download cancel ================"
                        )
                        //manager 侧被外部 cancelDownload：包装 Job 也必须以取消态结束，
                        //SVGAParser 注册的 invokeOnCompletion 才会走等待者接管分支
                        failure(CancellationException("download cancel"))
                        throw CancellationException("download cancel")
                    }

                    else -> {
                        val e = status.exception ?: Exception("download failed")
                        LogUtils.error(TAG, "================ file download fail ================")
                        LogUtils.error(TAG, "error: ${e.message}")
                        failure(e)
                    }
                }
            } catch (e: CancellationException) {
                //自身被取消（view 回收等）：原样上抛，由 invokeOnCompletion 统一决策接管
                throw e
            }
        }.apply {
            invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    //包装任务取消 → 停掉 manager 侧下载（不删正式缓存文件：
                    //取消时最多存在 shadow 半成品，由 manager 任务自身的异常清理删除）
                    FileDownloadManager.shared.cancelDownload(urlString, cacheFile, deleteFile = false)
                }
            }
        }
    }
}
