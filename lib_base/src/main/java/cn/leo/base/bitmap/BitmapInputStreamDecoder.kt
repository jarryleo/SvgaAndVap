package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * 通过输入流解码 Bitmap
 */
object BitmapInputStreamDecoder : BitmapDecoder<InputStream>() {

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: InputStream): ImageDecoder.Source? = runCatching {
        // ImageDecoder 没有公开的 InputStream 重载, 读入内存后建源
        val bytes = data.use { it.readBytes() }
        ImageDecoder.createSource(bytes)
    }.getOrNull()

    override fun onDecode(data: InputStream, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            data.use {
                BitmapFactory.decodeStream(it, null, ops)
            }
        }.getOrNull()
    }
}
