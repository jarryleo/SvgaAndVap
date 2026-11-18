package com.opensource.svgaplayer

import android.content.Context
import android.net.http.HttpResponseCache
import cn.leo.base.AnimLibrary
import cn.leo.base.cache.BitmapCache
import cn.leo.base.cache.BitmapDiskCache
import cn.leo.base.download.BitmapDownloadInterface
import cn.leo.base.log.ILogger
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.download.FileDownloader
import com.opensource.svgaplayer.url.UrlDecoder
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.log.SVGALogger
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ThreadPoolExecutor

/**
 * @Author     :Leo
 * Date        :2024/2/23
 * Description : SVGAManager 初始化入口
 */
object SVGAManager {

    /** init 时记录，供 clearCache 后重建 http 缓存使用（避免持有外部 Context） */
    private var appContext: Context? = null
    private var httpCacheSize: Long = 64L * 1024 * 1024

    @JvmOverloads
    @JvmStatic
    fun init(
        context: Context,
        memoryCacheMaxBytes: Long = SVGAMemoryCache.defaultMaxBytes,
        httpCacheSize: Long = 64L * 1024 * 1024,
        logEnabled: Boolean = false,
        loggerProxy: ILogger? = null,
        urlDecoder: UrlDecoder? = null,
        threadPoolExecutor: ThreadPoolExecutor? = null
    ) {
        appContext = context.applicationContext
        this.httpCacheSize = httpCacheSize
        try {
            val installed = HttpResponseCache.getInstalled()
            if (installed == null) {
                val cacheDir = File(context.cacheDir, "http")
                HttpResponseCache.install(cacheDir, httpCacheSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        //公共依赖(appContext/lib_base 日志与下载器)统一转投库上下文聚合
        AnimLibrary.init(context, logEnabled, loggerProxy)
        // 配置cache
        SVGAFileCache.init(context)
        //修改实体内存缓存字节预算（按实体 bitmap 真实字节数记账，超限 LRU 驱逐）
        SVGAMemoryCache.defaultMaxBytes = memoryCacheMaxBytes
        //自定义线程池
        threadPoolExecutor?.let {
            SVGAParser.setThreadPoolExecutor(it)
        }
        //初始化url解码器
        urlDecoder?.let {
            UrlDecoderManager.setUrlDecoder(it)
        }
        //初始化
        SVGAParser.init(context)
        // log
        SVGALogger.setLogEnabled(logEnabled)
        //日志代理
        loggerProxy?.let { logger ->
            SVGALogger.injectSVGALoggerImp(logger)
        }
    }

    /**
     * 设置bitmap下载器（转投 [AnimLibrary] 库上下文, svga/vap 共用）
     */
    fun setBitmapDownload(bitmapDownloadInterface: BitmapDownloadInterface) {
        AnimLibrary.setBitmapDownloader(bitmapDownloadInterface)
    }

    /**
     * svga 预加载：批量预下载到磁盘缓存。
     * 并行下发——旧实现按队列递归串行(一次只下一条, 异步回调驱动下一条), 批量预热很慢;
     * 共享下载器自带并发额度(Semaphore)与同 url+file 在途去重, 并行下发天然限流且不会重复下载
     */
    fun preload(urlList: List<String>, width: Int, height: Int) {
        val urlDecoder = UrlDecoderManager.getUrlDecoder()
        val fileDownloader = FileDownloader()
        urlList
            .asSequence()
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .map { urlDecoder.decodeSvgaUrl(it, width, height) }
            .map {
                val decode = try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    it
                }
                val url = try {
                    URL(decode)
                } catch (e: Exception) {
                    null
                }
                url
            }
            .mapNotNull {
                it
            }
            .forEach { url ->
                val cacheKey = SVGAFileCache.buildCacheKey(url)
                if (SVGAFileCache.getCachedType(cacheKey) == null) {
                    //预加载是尽力而为: 成功即落盘, 失败静默(下次正式加载会重试)
                    fileDownloader.resume(url, {}, {})
                }
            }
    }

    /**
     * 低内存时释放缓存
     */
    @JvmStatic
    fun onLowMemory() {
        SVGAMemoryCache.INSTANCE.clear()
        BitmapCache.INSTANCE.clear()
    }

    /**
     * 清除缓存
     */
    @JvmStatic
    fun clearCache() {
        SVGAMemoryCache.INSTANCE.clear()
        BitmapCache.INSTANCE.clear()
        SVGAFileCache.clearCache()
        appContext?.let { BitmapDiskCache.clear(it) }
        reinstallHttpCache()
    }

    /**
     * 进程存活期间缓存目录可能被外部整体删除（系统设置清缓存、AppCacheUtils 直删 cacheDir），
     * 已安装的 HttpResponseCache 会指向不存在的目录导致后续 svga 下载持续失败；
     * 这里卸载后重装，保证清缓存后同进程内的下载恢复可用
     */
    private fun reinstallHttpCache() {
        val context = appContext ?: return
        try {
            HttpResponseCache.getInstalled()?.delete()
            HttpResponseCache.install(File(context.cacheDir, "http"), httpCacheSize)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}