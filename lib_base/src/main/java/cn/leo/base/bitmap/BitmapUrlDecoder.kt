package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import cn.leo.base.download.FileDownloadManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

/**
 * 通过Url解码 Bitmap
 */
object BitmapUrlDecoder : BitmapDecoder<URL>() {
    private const val TIMEOUT = 30L
    private val okHttpClient: OkHttpClient by lazy {
        FileDownloadManager.okHttpClient
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreateSource(data: URL): ImageDecoder.Source? = runCatching {
        val request = Request.Builder()
            .url(data)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            response.body?.bytes()?.let { ImageDecoder.createSource(it) }
        }
    }.getOrNull()

    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        try {
            val request = Request.Builder()
                .url(data)
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.body?.byteStream()?.use { stream ->
                return BitmapFactory.decodeStream(stream, null, ops)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
