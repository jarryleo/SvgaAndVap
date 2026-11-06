package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 通过字节码解码 Bitmap
 */
object BitmapByteArrayDecoder : BitmapDecoder<ByteArray>() {

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: ByteArray): ImageDecoder.Source? =
        ImageDecoder.createSource(data)

    override fun onDecode(data: ByteArray, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.count(), ops)
        }.getOrNull()
    }
}
