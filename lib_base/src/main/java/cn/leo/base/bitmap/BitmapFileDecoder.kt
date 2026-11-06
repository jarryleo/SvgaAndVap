package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过文件解码 Bitmap
 */
object BitmapFileDecoder : BitmapDecoder<String>() {

    override fun onDecode(data: String, ops: BitmapFactory.Options): Bitmap? {
        return runCatching { BitmapFactory.decodeFile(data, ops) }.getOrNull()
    }
}
