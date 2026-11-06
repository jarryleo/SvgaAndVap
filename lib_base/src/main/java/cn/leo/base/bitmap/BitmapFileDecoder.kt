package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

/**
 * 通过文件解码 Bitmap
 */
object BitmapFileDecoder : BitmapDecoder<String>() {

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreateSource(data: String): ImageDecoder.Source? =
        ImageDecoder.createSource(File(data))

    override fun onDecode(data: String, ops: BitmapFactory.Options): Bitmap? {
        return runCatching { BitmapFactory.decodeFile(data, ops) }.getOrNull()
    }
}
