package com.tencent.qgame.animplayer.file

import android.content.res.AssetManager
import android.media.MediaExtractor
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog

/**
 * assets 源的文件容器(从 AnimView 内嵌类独立出来的正式实现,
 * 解决 Assets 资源暂停重播失败问题)。
 *
 * 线程安全说明:底层 assets 流会被两个线程访问——
 * anim_render_thread 在 AnimConfigManager.parse() 中 read/skip/closeRandomRead,
 * 主线程在 startPlayInternal/startPlayForce/destroy 中 close 上一次播放的容器。
 * 快速切换动画时两者并发,会对 native _FileAsset/IncFsFileMap 形成
 * use-after-free(SIGSEGV)或 double free(scudo heap corruption SIGABRT)。
 * 因此所有流操作都在 streamLock 内串行化,close 幂等,
 * 流关闭后 read 安全返回 EOF(-1),让 parse 优雅失败(默认配置/失败回调),
 * 由 startEpoch/playGeneration 代际校验保证过期会话不会启动。
 */
class CustomAssetsFileContainer(
    private val assetManager: AssetManager,
    private val assetsPath: String
) : IFileContainer {

    companion object {
        private const val TAG = "${Constant.TAG}.FileContainer"
    }

    private val streamLock = Any()
    private var assetsInputStream: AssetManager.AssetInputStream? = null

    init {
        ALog.i(TAG, "AssetsFileContainer init")
        makeStream()
    }

    private fun makeStream() {
        synchronized(streamLock) {
            val stream = try {
                assetManager.open(
                    assetsPath,
                    AssetManager.ACCESS_STREAMING
                )
            } catch (e: Exception) {
                ALog.e(TAG, "$assetsPath not found! $e")
                null
            }
            if (stream != null) {
                assetsInputStream = stream as? AssetManager.AssetInputStream
            }
        }
    }

    override fun setDataSource(extractor: MediaExtractor) {
        try {
            setDataInner(extractor) //修复可能出现IOException: Failed to instantiate extractor.
        } catch (e: Exception) {
            ALog.e(TAG, "AssetsFileContainer setDataSource error $e")
            setDataInner(extractor)
        }
    }

    private fun setDataInner(extractor: MediaExtractor) {
        // 每个extractor独立打开fd并即刻关闭:一次播放中音视频两个MediaExtractor
        // 共享同一fd时,部分机型会因文件偏移竞争读到错位数据
        // (表现为No video/audio track found或readSampleData立刻EOS)。
        // MediaExtractor 内部会 dup fd,本方法返回后关闭 fd 是安全的
        val fd = try {
            assetManager.openFd(assetsPath)
        } catch (e: Exception) {
            throw java.io.IOException("openFd fail: $assetsPath", e)
        }
        try {
            if (fd.declaredLength < 0) {
                extractor.setDataSource(fd.fileDescriptor)
            } else {
                extractor.setDataSource(
                    fd.fileDescriptor,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        } finally {
            try {
                fd.close()
            } catch (e: Exception) {
                ALog.e(TAG, "AssetsFileContainer close fd error $e")
            }
        }
    }

    override fun startRandomRead() {
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        synchronized(streamLock) {
            val stream = assetsInputStream ?: return -1
            return try {
                stream.read(b, off, len)
            } catch (e: Exception) {
                ALog.e(TAG, "AssetsFileContainer read error $e")
                -1
            }
        }
    }

    override fun skip(pos: Long) {
        synchronized(streamLock) {
            try {
                assetsInputStream?.skip(pos)
            } catch (e: Exception) {
                ALog.e(TAG, "AssetsFileContainer skip error $e")
            }
        }
    }

    override fun closeRandomRead() {
        synchronized(streamLock) {
            closeStreamLocked()
        }
    }

    override fun close() {
        synchronized(streamLock) {
            closeStreamLocked()
        }
    }

    // 调用时必须持有 streamLock;幂等,重复调用不会重复释放 native 资源
    private fun closeStreamLocked() {
        try {
            assetsInputStream?.close()
        } catch (e: Exception) {
            ALog.e(TAG, "AssetsFileContainer close stream error $e")
        }
        assetsInputStream = null
    }

    fun copy(): CustomAssetsFileContainer {
        return CustomAssetsFileContainer(assetManager, assetsPath)
    }
}
