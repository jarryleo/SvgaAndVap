package com.tencent.qgame.animplayer

import android.content.Context
import android.graphics.Bitmap
import cn.leo.base.bitmap.BitmapByteArrayDecoder
import cn.leo.base.bitmap.BitmapFileDecoder
import cn.leo.base.bitmap.BitmapResDecoder
import cn.leo.base.cache.BitmapDiskCache
import cn.leo.base.download.DownloadState
import cn.leo.base.download.DownloadStatus
import com.tencent.qgame.animplayer.anim.AnimImage
import com.tencent.qgame.animplayer.anim.GifDecoder
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.mix.Resource
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.SourceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络动态图下载失败标记:ConcurrentHashMap 不接受 null 值,用哨兵对象占位
 */
private object UrlDownloadFailed

class DynamicResource {
    internal val imageMap = mutableMapOf<String, Any>()
    internal val textMap = mutableMapOf<String, String>()

    /**
     * 设置动态图片,可以是bitmap，文件，url，assert, res
     * gif动图会自动识别并按视频时钟逐帧播放
     */
    fun setImage(tag: String, image: Any) {
        imageMap[tag] = image
    }

    fun setText(tag: String, text: String) {
        textMap[tag] = text
    }
}

/**
 * 加载VAP动画
 * @param data 动画数据源, 可以是文件，url，asset
 * @param loopCount 循环次数，小于1为播放1次。
 * @param onStartRenderOnce 动画开始渲染一次的回调
 * @param dynamic 动态图片和文字, 图片可以是 bitmap，文件，url，asset
 */
fun AnimView.load(
    data: Any?,
    loopCount: Int? = null,
    onStartRenderOnce: () -> Unit = {},
    downloadState: (DownloadStatus) -> Unit = {},
    dynamic: DynamicResource.() -> Unit,
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    val dynamicResource = DynamicResource()
    dynamic.invoke(dynamicResource)
    // 图片解析缓存: fetchAnimImage与fetchImage共用一次解析结果
    val imageResolveCache = HashMap<String, Any?>()
    // 网络动态图下载结果缓存(url -> 已下载File / 失败哨兵):
    // 下载协程写入,资源解析与注入读取,须先于播放启动
    val urlFileCache = ConcurrentHashMap<String, Any?>()
    startDynamicImageDownload(dynamicResource, urlFileCache)
    load(
        data = data,
        loopCount = loopCount,
        onStartRenderOnce = onStartRenderOnce,
        downloadState = downloadState,
        dynamicImage = { resource ->
            resolveDynamicImage(context, imageResolveCache, urlFileCache, dynamicResource, resource)
        },
        dynamicText = {
            val tag = it.tag
            dynamicResource.textMap[tag]
        },
        onRelease = onRelease
    )
}

/**
 * 网络动态图异步预下载(先播放后嵌入):
 * 资源解析阶段(MixAnimPlugin.fetchResourceSync)不等待网络结果,未就绪的图片按缺失
 * 处理(透明占位)让动画立即开播;单个url下载完成后解码并注入正在播放的动画。
 * 磁盘缓存命中的图片解析时同步可得,首帧即正确。
 */
private fun AnimView.startDynamicImageDownload(
    dynamicResource: DynamicResource,
    urlFileCache: ConcurrentHashMap<String, Any?>
) {
    dynamicDownloadJob?.cancel("load new dynamic resource")
    dynamicDownloadJob = null
    // 每次load都递增代数号:即使本次无网络图,也要作废上一会话尚未落地的注入
    val epoch = nextDynamicLoadEpoch()
    val urls = dynamicResource.imageMap.values
        .filterIsInstance<String>()
        .filter { SourceUtil.isUrl(it) }
        .distinct()
    if (urls.isEmpty()) return
    dynamicDownloadJob = launch(Dispatchers.IO) {
        urls.forEach { url ->
            val file = downloadImageFile(context, url)
            // 失败也写入(哨兵):让解析阶段短路磁盘查询,直接按缺失占位
            urlFileCache[url] = file ?: UrlDownloadFailed
            if (file == null) {
                ALog.e("AnimView", "download dynamic image fail url=$url")
                return@forEach
            }
            // 同一url可能被多个tag引用,逐一注入;会话被新load取代时由代数号校验丢弃
            dynamicResource.imageMap.forEach { (tag, image) ->
                if (image == url) {
                    // 0,0=全尺寸解码,与解析阶段行为一致(此时无目标尺寸信息)
                    updateDynamicImage(tag, decodeImageFile(file, 0, 0), epoch)
                }
            }
        }
    }
}

/**
 * 加载VAP动画
 * @param data 动画数据源, 可以是文件，url，asset
 * @param loopCount 循环次数，默认1次，负数为无限循环
 * @param onStartRenderOnce 动画开始渲染一次的回调
 * @param dynamicImage 动态图片, 返回Bitmap或AnimImage(gif动图)
 * @param dynamicText 动态文字
 * @param onRelease 资源释放的回调
 */
fun AnimView.load(
    data: Any?,
    loopCount: Int? = null,
    onStartRenderOnce: () -> Unit = {},
    downloadState: (DownloadStatus) -> Unit = {},
    dynamicImage: (resource: Resource) -> Any? = { null },
    dynamicText: (resource: Resource) -> String? = { null },
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    val loop = loopCount ?: 1
    if (loop < 0) {
        setLoop(Int.MAX_VALUE)
    } else {
        setLoop(loop)
    }
    setFetchResource(object : IFetchResource {
        override fun fetchImage(
            resource: Resource,
            result: (Bitmap?) -> Unit
        ) {
            result.invoke(dynamicImage(resource) as? Bitmap)
            ALog.d("setFetchResource: fetchImage ->", "resource.tag = ${resource.tag}")
        }

        override fun fetchAnimImage(
            resource: Resource,
            result: (AnimImage?) -> Unit
        ) {
            result.invoke(dynamicImage(resource) as? AnimImage)
            ALog.d("setFetchResource: fetchAnimImage ->", "resource.tag = ${resource.tag}")
        }

        override fun fetchText(
            resource: Resource,
            result: (String?) -> Unit
        ) {
            result.invoke(dynamicText(resource))
            ALog.d("setFetchResource: fetchText ->", "resource.tag = ${resource.tag}")
        }

        override fun releaseResource(resources: List<Resource>) {
            onRelease.invoke(resources)
        }
    })
    if (data == null) {
        loadJob?.cancel("load null data")
        loadJob = null
        stopPlay()
        return downloadState(DownloadStatus(state = DownloadState.FAILED))
    }
    if (data is File) {
        loadJob?.cancel("load file source")
        loadJob = null
        startPlayForce(data, onStartRenderOnce)
        return downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = data))
    }
    if (data !is String) {
        return downloadState(DownloadStatus(state = DownloadState.FAILED))
    }
    val isUrl = SourceUtil.isUrl(data)
    if (isUrl) {
        //下载文件并缓存
        loadJob?.cancel("load new url")
        loadJob = launch(Dispatchers.IO) {
            // 当前下载任务的身份标识,过期任务(已被新 load 取消/取代)不得再发起播放,
            // 否则会抢占用户最新切换的视频。
            val currentJob = coroutineContext[Job]
            //读取缓存文件
            val cacheKey = VapFileCache.buildCacheKey(data)
            val cacheFile = VapFileCache.buildCacheFile(cacheKey, context).first()
            if (cacheFile.exists()) {
                if (loadJob == currentJob) {
                    startPlayForce(cacheFile, onStartRenderOnce)
                }
                withContext(Dispatchers.Main) {
                    downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = cacheFile))
                }
                return@launch
            }
            VapManager.downLoad(data, cacheFile).collectLatest {
                ALog.d("AnimView", "load url: $data, state = $it")
                withContext(Dispatchers.Main) {
                    downloadState(it)
                }
                if (it.isSuccessful() && isAttachedToWindow && loadJob == currentJob) {
                    it.file?.let { file ->
                        startPlayForce(file, onStartRenderOnce)
                    }
                }
            }
        }
        return
    }
    val isFilePath = SourceUtil.isFilePath(data)
    if (isFilePath) {
        loadJob?.cancel("load filepath source")
        loadJob = null
        val file = File(data)
        startPlayForce(file, onStartRenderOnce)
        return downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = file))
    }
    //尝试从asset中加载
    loadJob?.cancel("load asset source")
    loadJob = null
    startPlayForce(data, onStartRenderOnce)
    return downloadState(DownloadStatus(state = DownloadState.COMPLETED))
}

/**
 * 解析动态图片资源: gif动图返回AnimImage, 其它返回Bitmap
 * 同一tag一次加载内只解析一次(fetchAnimImage与fetchImage共用结果)
 */
private fun resolveDynamicImage(
    context: Context,
    cache: HashMap<String, Any?>,
    urlFileCache: ConcurrentHashMap<String, Any?>,
    dynamicResource: DynamicResource,
    resource: Resource
): Any? {
    val tag = resource.tag
    if (cache.containsKey(tag)) {
        return cache[tag]
    }
    val reqWidth = resource.curPoint?.w ?: 0
    val reqHeight = resource.curPoint?.h ?: 0
    val resolved: Any? = when (val image = dynamicResource.imageMap[tag]) {
        is Bitmap -> image
        is File -> decodeImageFile(image, reqWidth, reqHeight)
        is Int -> decodeImageRes(context, image, reqWidth, reqHeight)
        is String -> when {
            //网络图不阻塞播放:已就绪(本次下载完成或磁盘缓存命中)则同步解码,
            //否则返回null按缺失占位,待下载完成后异步注入(见startDynamicImageDownload)
            SourceUtil.isUrl(image) -> resolveUrlImage(context, urlFileCache, image, reqWidth, reqHeight)

            SourceUtil.isFilePath(image) -> decodeImageFile(File(image), reqWidth, reqHeight)
            else -> decodeImageAsset(context, image, reqWidth, reqHeight) //尝试Assert
        }

        else -> null
    }
    cache[tag] = resolved
    return resolved
}

/**
 * 取网络动态图并解码:优先读本次加载的下载结果缓存,其次读磁盘缓存(历史会话已下载)。
 * 均未就绪(下载仍在进行或已失败)返回null,调用方按资源缺失占位处理
 */
private fun resolveUrlImage(
    context: Context,
    urlFileCache: ConcurrentHashMap<String, Any?>,
    url: String,
    reqWidth: Int,
    reqHeight: Int
): Any? {
    val file = urlFileCache[url] as? File
        ?: runCatching {
            BitmapDiskCache.cacheFile(context, BitmapDiskCache.buildKey(url)).takeIf { it.exists() }
        }.getOrNull()
        ?: return null
    return decodeImageFile(file, reqWidth, reqHeight)
}

/**
 * 图片文件解析: gif动图返回AnimImage(解析失败回退静态首帧), 其它返回Bitmap
 */
private fun decodeImageFile(file: File, reqWidth: Int, reqHeight: Int): Any? {
    if (GifDecoder.isGif(file)) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        val anim = bytes?.let { GifDecoder.decode(it, reqWidth, reqHeight) }
        if (anim != null) {
            return anim
        }
        ALog.e("AnimView", "decode gif fail, fallback static image file=${file.absolutePath}")
    }
    return BitmapFileDecoder.decodeBitmapFrom(file.absolutePath, reqWidth, reqHeight)
}

private fun decodeImageAsset(context: Context, name: String, reqWidth: Int, reqHeight: Int): Any? {
    val bytes = runCatching {
        context.assets.open(name).use { it.readBytes() }
    }.getOrNull() ?: return null
    if (GifDecoder.isGif(bytes)) {
        return GifDecoder.decode(bytes, reqWidth, reqHeight)
    }
    return BitmapByteArrayDecoder.decodeBitmapFrom(bytes, reqWidth, reqHeight)
}

private fun decodeImageRes(context: Context, resId: Int, reqWidth: Int, reqHeight: Int): Any? {
    val bytes = runCatching {
        context.resources.openRawResource(resId).use { it.readBytes() }
    }.getOrNull() ?: return null
    if (GifDecoder.isGif(bytes)) {
        return GifDecoder.decode(bytes, reqWidth, reqHeight)
    }
    return BitmapResDecoder(context).decodeBitmapFrom(resId, reqWidth, reqHeight)
}

/**
 * 下载图片原始文件(带缓存), 供gif检测与解码
 */
private suspend fun downloadImageFile(context: Context, url: String): File? {
    return runCatching {
        val cacheFile = BitmapDiskCache.cacheFile(context, BitmapDiskCache.buildKey(url))
        if (!cacheFile.exists()) {
            val status =
                VapManager.downLoad(url, cacheFile).first { it.isSuccessful() || it.isFailed() }
            if (!status.isSuccessful()) {
                ALog.e("AnimView", "download image fail url=$url")
                return null
            }
        }
        cacheFile.takeIf { it.exists() }
    }.getOrNull()
}