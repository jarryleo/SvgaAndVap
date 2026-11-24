package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitmap 解码器
 *
 * <T> 需要加载的数据类型
 */
abstract class BitmapDecoder<T> {

    fun decodeBitmapFrom(data: T, reqWidth: Int, reqHeight: Int): Bitmap? {
        // API 28+ 优先使用 ImageDecoder, 可按任意比例精确缩放到目标尺寸,
        // 相比 inSampleSize 只能按 2 的幂次采样(结果不小于目标尺寸)更省内存
        if (reqWidth > 0 && reqHeight > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            decodeByImageDecoder(data, reqWidth, reqHeight)?.let { return it }
        }
        return decodeByBitmapFactory(data, reqWidth, reqHeight)
    }

    /**
     * ImageDecoder 解码: 按原宽高比缩放到不超过 reqWidth/reqHeight 的最大尺寸(只缩不放), 避免拉伸变形
     *
     * @return 子类未提供 Source 或解码失败时返回 null, 由调用方降级到 BitmapFactory
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeByImageDecoder(data: T, reqWidth: Int, reqHeight: Int): Bitmap? {
        val source = runCatching { onCreateSource(data) }.getOrNull() ?: return null
        return runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 默认allocator在Android S+产出HARDWARE位图: 解码后经EGL同步上传GPU, 而svga/vap需要
                // 可读像素的软件位图, HARDWARE会落到ensureGlCompatibleConfig的copy(ARGB_8888)再做一次GPU回读。
                // 每张图两次CPU<->GPU同步往返, 耗时与图尺寸无关(实测Android 15机型单张约15ms),
                // 序列帧类svga(上百张小图)的磁盘缓存加载被放大到数秒。
                // 显式ALLOCATOR_SOFTWARE直接产出ARGB_8888软件位图
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                // 广色域/16bit源默认解码为RGBA_F16, GLUtils.texImage2D不支持该配置
                // (vap动态图上传纹理时抛invalid Bitmap format, 中断整个播放流程),
                // 指定sRGB目标色彩空间保证输出8bit位图(ARGB_8888),
                // 与BitmapFactory降级路径的RGB_565色彩语义保持一致
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val srcWidth = info.size.width
                val srcHeight = info.size.height
                if (srcWidth > 0 && srcHeight > 0) {
                    val scale = min(
                        1f,
                        min(reqWidth.toFloat() / srcWidth, reqHeight.toFloat() / srcHeight)
                    )
                    decoder.setTargetSize(
                        (srcWidth * scale).roundToInt().coerceAtLeast(1),
                        (srcHeight * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()?.let(::ensureGlCompatibleConfig)
    }

    /**
     * 安全网: 保证解码结果配置可被GLUtils.texImage2D上传(仅支持ARGB_8888/RGB_565),
     * 其余配置(RGBA_F16等)拷贝为ARGB_8888; 拷贝失败返回null, 调用方降级到BitmapFactory路径。
     * HARDWARE位图(默认allocator在Android S+的产物)同样能经此拷贝转换, 但copy是GPU回读,
     * 已在decodeByImageDecoder用ALLOCATOR_SOFTWARE从源头避免, 这里仅作兜底
     */
    private fun ensureGlCompatibleConfig(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return when (bitmap.config) {
            Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565 -> bitmap
            else -> runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()?.also { bitmap.recycle() }
        }
    }

    private fun decodeByBitmapFactory(data: T, reqWidth: Int, reqHeight: Int): Bitmap? {
        return BitmapFactory.Options().run {
            // 如果期望的宽高是合法的, 则开启检测尺寸模式
            inJustDecodeBounds = (reqWidth > 0 && reqHeight > 0)
            inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = onDecode(data, this)
            if (!inJustDecodeBounds) {
                return bitmap
            }

            // Calculate inSampleSize
            inSampleSize = BitmapSampleSizeCalculator.calculate(this, reqWidth, reqHeight)
            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false
            onDecode(data, this)
        }
    }

    /**
     * 提供 ImageDecoder 的解码源, 默认不支持, 走 BitmapFactory 降级逻辑
     */
    open fun onCreateSource(data: T): ImageDecoder.Source? = null

    abstract fun onDecode(data: T, ops: BitmapFactory.Options): Bitmap?
}
