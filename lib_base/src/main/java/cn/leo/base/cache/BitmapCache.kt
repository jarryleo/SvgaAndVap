package cn.leo.base.cache

import android.graphics.Bitmap
import android.util.LruCache
import java.lang.ref.WeakReference
import java.security.MessageDigest

/**
 * @Description bitmap内存缓存
 *
 * 语义说明(与 lib_svga 的 SVGAMemoryCache 区分, 避免误升级/误对标):
 * 本缓存只服务「动态替换图」(svga setDynamicImage / vap mix 动态图)这类
 * 短生命周期、单张、由使用方(drawable/纹理)另行持有强引用的 bitmap——
 * 因此刻意采用 WeakReference + 按个数(默认 20)的轻量缓存:
 * 只做"使用方仍持有时避免重复解码"的加速, 不承担内存驻留责任,
 * 也因此不存在字节记账与引用计数(强引用驻留场景请用 SVGAMemoryCache 的那套语义)。
 * LRU 驱逐时 clear() WeakReference 只影响本缓存的可命中性, 不影响使用方持有。
 * 注意: 若位图仅被本缓存引用(使用方已释放), 条目会随 GC 自然失活,
 * getData 返回 null 或已 recycle 的位图由调用方按未命中处理
 */
class BitmapCache(private val cacheLimit: Int = limitCount) {

    private val lruCache by lazy {
        object : LruCache<String, WeakReference<Bitmap>>(cacheLimit) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String?,
                oldValue: WeakReference<Bitmap>?,
                newValue: WeakReference<Bitmap>?
            ) {
                if (evicted) {
                    oldValue?.clear()
                }
            }
        }

    }

    fun getData(key: String): Bitmap? {
        val bitmap = lruCache.get(key)?.get()
        if (bitmap?.isRecycled == true){
            lruCache.remove(key)
            return null
        }
        return bitmap
    }

    fun putData(key: String, entity: Bitmap) {
        lruCache.put(key, WeakReference(entity))
    }

    /**
     * 重新设置缓存大小，只有在Android 5.0及以上才有效
     * @param limit Int
     */
    fun resizeCache(limit: Int) {
        lruCache.resize(limit)
    }

    fun clear() {
        lruCache.evictAll()
    }

    companion object {

        val INSTANCE by lazy { BitmapCache(limitCount) }

        /** 内存缓存个数 */
        var limitCount = 20
            set(value) {
                field = value
                INSTANCE.resizeCache(value)
            }

        /**
         * 拼接缓存Key所需要的字段
         */
        fun createKey(path: String, width: Int, height: Int): String {
            return "key:{path = $path width = $width height = $height}".let {
                BitmapDiskCache.buildKey(it)
            }
        }
    }
}
