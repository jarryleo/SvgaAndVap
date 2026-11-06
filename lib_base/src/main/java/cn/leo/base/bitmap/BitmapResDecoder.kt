package cn.leo.base.bitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过资源id解码 Bitmap
 */
class BitmapResDecoder(val context: Context) : BitmapDecoder<Int>() {

    override fun onDecode(data: Int, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeResource(context.resources, data, ops)
        }.getOrNull()
    }
}
