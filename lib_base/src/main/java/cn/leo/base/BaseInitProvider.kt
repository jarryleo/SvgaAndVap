package cn.leo.base

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import cn.leo.base.cache.BitmapSourceCache

/**
 * lib_base 的自动初始化入口：进程启动时借 ContentProvider 拿到 applicationContext，
 * 保证 [BitmapSourceCache] 缓存目录始终可用，BitmapUrlDecoder 的网络解码
 * 始终能走 FileDownloadManager 落盘后解码，不再退化到裸网络流自下载。
 * init 只记录目录路径、无磁盘 IO，不阻塞启动。
 *
 * 宿主如需禁用自动初始化，可 manifest 中 tools:node="remove" 移除本 Provider，
 * 但须自行保证解码前缓存目录已初始化（走 BitmapDownloader.downloadBitmap 或
 * 显式调用 BitmapSourceCache.init）
 */
class BaseInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        BitmapSourceCache.init(app)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
