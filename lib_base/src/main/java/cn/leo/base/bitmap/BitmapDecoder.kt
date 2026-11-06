package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        }.getOrNull()
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
