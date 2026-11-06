package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * 通过输入流解码 Bitmap
 */
object BitmapInputStreamDecoder : BitmapDecoder<InputStream>() {

    override fun onDecode(data: InputStream, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            data.use {
                BitmapFactory.decodeStream(it, null, ops)
            }
        }.getOrNull()
    }
}
