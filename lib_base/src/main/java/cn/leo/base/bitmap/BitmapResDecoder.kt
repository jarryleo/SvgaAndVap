package cn.leo.base.bitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 通过资源id解码 Bitmap
 */
class BitmapResDecoder(val context: Context) : BitmapDecoder<Int>() {

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: Int): ImageDecoder.Source? =
        ImageDecoder.createSource(context.resources, data)

    override fun onDecode(data: Int, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeResource(context.resources, data, ops)
        }.getOrNull()
    }
}
