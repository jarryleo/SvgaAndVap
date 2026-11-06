package cn.leo.base.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
