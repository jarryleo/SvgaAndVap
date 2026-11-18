package cn.leo.base

import android.content.Context
import cn.leo.base.cache.BitmapSourceCache
import cn.leo.base.download.BitmapDownloadInterface
import cn.leo.base.download.BitmapDownloader
import cn.leo.base.download.FileDownloadManager
import cn.leo.base.log.BaseLog
import cn.leo.base.log.DefaultBaseLog
import cn.leo.base.log.ILogger

/**
 * 库上下文：lib_base 全局依赖的唯一聚合入口。
 *
 * 背景：此前 lib_base 的全局配置散落在多个 object 的可变静态字段里
 * (BaseLog.log / BitmapDownloader.bitmapDownloader / FileDownloadManager 的 SSL 策略)，
 * svga 与 vap 各自的 Manager 又分别直接改这些字段——初始化入口分散、顺序敏感。
 * 现统一收敛到本上下文：
 * - 宿主可只调 [init] 一次性下发公共配置，svga/vap 的 Manager init 内部也会转投到这里；
 * - 各单例(下载器/日志)的既有字段与 API 保持不变，仅改为以这里为推荐写入口，
 *   避免破坏既有接入代码；
 * - 未 init 时各组件仍按各自默认值工作(渐进增强, 不强制初始化顺序)
 */
object AnimLibrary {

    /** 进程级 applicationContext。只存 applicationContext, 不持有外部组件 Context */
    @Volatile
    var appContext: Context? = null
        private set

    /**
     * 初始化库上下文（幂等，可重复调用）。
     * 只记录引用与配置、触发 [BitmapSourceCache.init]（无磁盘 IO），不阻塞调用线程
     *
     * @param trustAllCertificates 是否信任所有下载证书(默认 null 表示不改变当前策略;
     * 详见 [FileDownloadManager.setTrustAllCertificates])
     */
    @JvmOverloads
    @JvmStatic
    fun init(
        context: Context,
        logEnabled: Boolean = false,
        loggerProxy: ILogger? = null,
        trustAllCertificates: Boolean? = null,
        bitmapDownloader: BitmapDownloadInterface? = null
    ) {
        appContext = context.applicationContext
        applyLogConfig(logEnabled, loggerProxy)
        trustAllCertificates?.let { FileDownloadManager.setTrustAllCertificates(it) }
        bitmapDownloader?.let { setBitmapDownloader(it) }
        // bitmap 源缓存目录兜底初始化(BaseInitProvider 通常已完成, 此处幂等)
        BitmapSourceCache.init(context)
    }

    /** lib_base 内部日志开关与代理(默认关闭, 与 svga/vap 各自的日志开关互不影响) */
    @JvmStatic
    fun setLogEnabled(enabled: Boolean, loggerProxy: ILogger? = null) {
        applyLogConfig(enabled, loggerProxy)
    }

    /** 外部 bitmap 下载器注入(动态替换图的网络获取, svga/vap 共用) */
    @JvmStatic
    fun setBitmapDownloader(downloader: BitmapDownloadInterface) {
        BitmapDownloader.bitmapDownloader = downloader
    }

    /** 下载证书策略(默认安全校验; 自签证书等场景显式开启信任所有) */
    @JvmStatic
    fun setTrustAllCertificates(enabled: Boolean) {
        FileDownloadManager.setTrustAllCertificates(enabled)
    }

    private fun applyLogConfig(enabled: Boolean, loggerProxy: ILogger?) {
        BaseLog.log = when {
            !enabled -> null
            else -> loggerProxy ?: DefaultBaseLog
        }
    }
}
