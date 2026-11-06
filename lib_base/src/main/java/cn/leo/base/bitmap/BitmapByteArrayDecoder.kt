package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过字节码解码 Bitmap
 */
object BitmapByteArrayDecoder : BitmapDecoder<ByteArray>() {

    override fun onDecode(data: ByteArray, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.count(), ops)
        }.getOrNull()
    }
}
