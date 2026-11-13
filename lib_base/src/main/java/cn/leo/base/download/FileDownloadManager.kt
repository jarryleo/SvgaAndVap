package cn.leo.base.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * @Author     :Leo
 * Date        :2025/12/29
 * Description : 基于kotlin和协程的文件下载器，使用flow进行状态管理
 *
 * 在途任务按 url + saveFile 去重：同目标文件的并发调用共享同一次下载；
 * svga / vap / bitmap 的下载统一走 [shared] 共享实例，共享并发额度
 */

@Suppress("Unused")
data class DownloadStatus(
    val url: String = "",
    val progress: Int = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val headers: Map<String, String> = emptyMap(),
    val file: File? = null,
    val exception: Exception? = null,
) {
    fun isSuccessful() = state == DownloadState.COMPLETED
    fun isFailed() = state == DownloadState.FAILED
    fun getFileOrNull() = file
    fun getExceptionOrNull() = exception
}

enum class DownloadState {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Suppress("Unused")
class FileDownloadManager(
    maxConcurrentDownloads: Int = 3
) {
    companion object {

        // 创建信任所有证书的 TrustManager（沿用 svga 库的忽略证书校验策略, 两库统一行为）
        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // 创建 SSL Context
        private val trustAllSslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        // 创建 HostnameVerifier
        private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier(trustAllHostnameVerifier)
            .build()

        /**
         * 共享实例并发额度：svga 动画 / vap 视频 / bitmap 图片共用一个池，
         * 取值高于模块自建默认，避免批量动画加载时互相排队饿死
         */
        private const val SHARED_MAX_CONCURRENT_DOWNLOADS = 6

        /**
         * 进程级共享实例：全 App 文件下载的统一入口。
         * 相比各模块自建实例，可跨模块共享并发额度并按 url+file 做在途去重
         */
        val shared: FileDownloadManager by lazy { FileDownloadManager(SHARED_MAX_CONCURRENT_DOWNLOADS) }
    }

    /** 并发额度：排队中的任务挂起在 permit 上等待，替代旧的 QUEUED 手动重踢逻辑 */
    private val concurrencyLimiter = Semaphore(maxConcurrentDownloads.coerceAtLeast(1))

    @Volatile
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadStatus = ConcurrentHashMap<String, MutableStateFlow<DownloadStatus>>()

    // 启动下载
    fun download(
        url: String,
        saveFile: File,
        headers: Map<String, String> = emptyMap()
    ): Flow<DownloadStatus> {
        val key = downloadKey(url, saveFile)
        // 在途任务（含信号量排队中）直接复用同一下载；
        // 终态条目在任务结束回调中移除，移除前命中会立即收到重放的历史终态，等价一次缓存命中
        if (downloadJobs[key]?.isActive == true) {
            downloadStatus[key]?.let { return it.asStateFlow() }
        }
        val statusFlow = MutableStateFlow(
            DownloadStatus(url = url, headers = headers, file = saveFile)
        )
        // putIfAbsent 原子仲裁并发首调：赢家启动下载，输家复用赢家的状态流
        val existing = downloadStatus.putIfAbsent(key, statusFlow)
        if (existing != null) return existing.asStateFlow()
        val job = ensureScope().launch {
            concurrencyLimiter.withPermit {
                execute(url, saveFile, headers, statusFlow)
            }
        }
        downloadJobs[key] = job
        job.invokeOnCompletion {
            // 条件移除：若条目已被 cancelDownload/pauseDownload 同步移除，
            // 或同 key 的新下载已登记，不得误删新条目
            downloadJobs.remove(key, job)
            downloadStatus.remove(key, statusFlow)
        }
        return statusFlow.asStateFlow()
    }

    private suspend fun execute(
        url: String,
        destination: File,
        headers: Map<String, String>,
        statusFlow: MutableStateFlow<DownloadStatus>
    ) {
        var call: okhttp3.Call? = null
        var shadow: File? = null
        var totalBytes = 0L
        try {
            statusFlow.value = statusFlow.value.copy(state = DownloadState.DOWNLOADING)
            // 每次尝试独立 shadow：取消排干中的旧任务与接管的新任务可能短暂并存，
            // 固定名会互删互写导致内容损坏
            shadow = destination.shadow()

            // 准备请求
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            call = okHttpClient.newCall(request)
            // 协程取消时中断阻塞中的网络读：read 不响应协作式取消，必须 cancel Call
            val callCanceller = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) call?.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DownloadException("HTTP ${response.code}: ${response.message}")
                    }

                    val responseBody = response.body ?: throw DownloadException("Response body is null")

                    // 获取文件总大小
                    totalBytes = responseBody.contentLength()
                    // 已存在等长文件视为完整缓存直接成功；长度未知(<=0)无法校验，重下后原子覆盖
                    if (destination.exists() && totalBytes > 0 && destination.length() == totalBytes) {
                        statusFlow.value = statusFlow.value.copy(
                            state = DownloadState.COMPLETED,
                            progress = 100,
                            totalBytes = totalBytes,
                            downloadedBytes = totalBytes
                        )
                        return
                    }

                    // 确保目标目录存在
                    shadow.parentFile?.takeIf { !it.exists() }?.mkdirs()

                    FileOutputStream(shadow, false).use { output ->
                        val buffer = ByteArray(8192)
                        var downloadedBytes = 0L
                        var lastProgress = 0
                        responseBody.byteStream().use { input ->
                            while (true) {
                                val byteCount = input.read(buffer)
                                if (byteCount == -1) break
                                output.write(buffer, 0, byteCount)
                                downloadedBytes += byteCount

                                // 更新进度
                                val progress = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt()
                                } else 0

                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    // copy 保留 url/file 等字段：消费方（如 vap）终态取 file 才不为空
                                    statusFlow.value = statusFlow.value.copy(
                                        progress = progress,
                                        totalBytes = totalBytes,
                                        downloadedBytes = downloadedBytes
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                callCanceller.dispose()
            }

            // 原子发布：rename 失败（目标被并发占用等）退化为拷贝发布
            if (!shadow.renameTo(destination)) {
                shadow.copyTo(destination, overwrite = true)
                shadow.delete()
            }

            statusFlow.value = statusFlow.value.copy(
                state = DownloadState.COMPLETED,
                progress = 100,
                totalBytes = totalBytes,
                downloadedBytes = statusFlow.value.downloadedBytes,
                file = destination
            )
        } catch (e: Exception) {
            shadow?.delete()
            // call.cancel() 触发的 IOException 也属于取消，以协程状态为准而非异常类型
            val cancelled = e is CancellationException || !currentCoroutineContext().isActive
            statusFlow.value = if (cancelled) {
                statusFlow.value.copy(state = DownloadState.CANCELLED)
            } else {
                statusFlow.value.copy(state = DownloadState.FAILED, exception = e)
            }
        } finally {
            call?.cancel()
        }
    }

    /**
     * 暂停下载：发 PAUSED 终态并停止任务（半成品 shadow 由任务自身的异常清理删除）。
     * 再次 [download] 同一目标即重新开始（不支持断点续传）。
     * 必须同步移除登记：否则移除前的新调用会复用已终态的旧流，立即收到 PAUSED
     */
    fun pauseDownload(url: String, saveFile: File? = null) {
        matchingKeys(url, saveFile).forEach { key ->
            val statusFlow = downloadStatus.remove(key) ?: return@forEach
            statusFlow.value = statusFlow.value.copy(state = DownloadState.PAUSED)
            downloadJobs.remove(key)?.cancel()
        }
    }

    /**
     * 取消下载并删除文件。
     * 必须先发 CANCELLED 终态、再取消任务并同步移除登记：
     * 若等任务自行排干后再清理，"取消 → 重下"（如 svga 的等待者接管）窗口内的
     * 新调用会复用到已终态的旧流而立即失败
     */
    fun cancelDownload(url: String, saveFile: File? = null, deleteFile: Boolean = true) {
        matchingKeys(url, saveFile).forEach { key ->
            val statusFlow = downloadStatus.remove(key) ?: return@forEach
            statusFlow.value = statusFlow.value.copy(state = DownloadState.CANCELLED)
            downloadJobs.remove(key)?.cancel()
            if (deleteFile) {
                statusFlow.value.file?.let { file ->
                    file.delete()
                    file.deleteShadows()
                }
            }
        }
    }

    // 获取所有下载状态
    fun getAllDownloadStatus(): Map<String, DownloadStatus> {
        return downloadStatus.mapValues { it.value.value }
    }

    // 获取特定下载的状态
    fun getDownloadStatus(url: String, saveFile: File? = null): DownloadStatus? {
        matchingKeys(url, saveFile).firstOrNull()?.let { key ->
            return downloadStatus[key]?.value
        }
        return null
    }

    // 清理资源
    fun cleanup() {
        scope.cancel()
        downloadJobs.clear()
        downloadStatus.clear()
    }

    private fun ensureScope(): CoroutineScope {
        val current = scope
        if (current.isActive) return current
        return CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
    }

    /** 在途去重键：同 url 落到不同文件（如 bitmap 缓存与 vap 缓存路径不同）不能互相复用 */
    private fun downloadKey(url: String, saveFile: File): String {
        return "${saveFile.absolutePath}|$url"
    }

    /** 按 url（可选叠加 saveFile）匹配在途条目键 */
    private fun matchingKeys(url: String, saveFile: File?): List<String> =
        downloadStatus.entries.filter { (_, flow) ->
            val status = flow.value
            status.url == url && (saveFile == null || status.file == saveFile)
        }.map { it.key }

    /**
     * 每次下载尝试独立的临时文件（旧实现固定 .download 后缀，并发尝试会互相覆写）
     */
    fun File.shadow(): File {
        return File("$absolutePath.download_${System.nanoTime()}_${Thread.currentThread().id}")
    }

    /** 删除该目标文件名下所有尝试残留的 shadow 半成品 */
    private fun File.deleteShadows() {
        val prefix = "$name.download"
        runCatching {
            parentFile?.listFiles()?.forEach {
                if (it.name.startsWith(prefix)) it.delete()
            }
        }
    }
}
