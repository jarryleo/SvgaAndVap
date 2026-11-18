package cn.leo.base.cache

import cn.leo.base.log.BaseLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 容量上限 + LRU 淘汰的磁盘缓存, 统一承载 svga / vap / bitmap 各模块的文件缓存
 * (条目为目录下的文件或子目录, svga 的 zip 解压目录即子目录条目)。
 *
 * 设计取舍:
 * - LRU 顺序依据条目 lastModified(命中 touch 刷新), 进程重启后顺序依然成立,
 *   免去 journal 文件的写入/恢复/损坏自愈复杂度; 内容完整性由各模块解码期校验兜底
 *   (如 svga 解码失败删缓存自愈、下载器按 Content-Length 校验);
 * - 尺寸记账: 首次 [initAsync] 后台扫描目录建立索引, 之后随 [recordWrite] 增量维护;
 * - 淘汰: 超上限时按 lastModified 升序删除最旧条目, 在共享单线程 executor 上串行执行,
 *   避免与写入方竞争; 下载器 shadow 半成品(.download_ 前缀)不参与记账与淘汰;
 * - 所有方法线程安全且非阻塞(重活全部转投 executor), 可在任意线程调用
 */
class LruDiskCache(
    val dir: File,
    @Volatile var maxBytes: Long
) {

    companion object {
        private const val TAG = "LruDiskCache"

        /** FileDownloadManager 的 shadow 临时文件前缀标记, 扫描/淘汰时跳过 */
        private const val SHADOW_MARKER = ".download_"

        /** 全部实例共享的单线程 executor: 扫描/记账/淘汰串行化, 整个库只占一个后台线程 */
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "anim-disk-cache").apply { isDaemon = true }
        }
    }

    /** 条目名 → 字节数。只在 executor 线程上变更(扫描/写入记账/删除), 读取为近似值 */
    private val entrySizes = ConcurrentHashMap<String, Long>()

    private val scanned = AtomicBoolean(false)

    /**
     * 后台扫描目录建立尺寸索引并按上限淘汰。
     * 模块 init 时调用; 只记录路径不做磁盘 IO, 不阻塞调用线程
     */
    fun initAsync() {
        runCatching { dir.mkdirs() }
        if (scanned.compareAndSet(false, true)) {
            executor.execute { rescanLocked() }
        }
    }

    /** 条目对应的文件/目录(不保证存在) */
    fun entry(name: String): File = File(dir, name)

    fun exists(name: String): Boolean = entry(name).exists()

    /**
     * 命中时刷新 LRU 顺序(lastModified 置为当前时刻)。
     * setLastModified 在部分文件系统上可能失败, 忽略——最多导致该条目被提前淘汰
     */
    fun touch(name: String) {
        val file = entry(name)
        if (file.exists() && !isShadow(name)) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
    }

    /**
     * 条目写入完成后记账(文件或目录均可, 目录递归统计), 超上限时在后台触发 LRU 淘汰
     */
    fun recordWrite(name: String) {
        if (isShadow(name)) return
        executor.execute {
            val size = computeEntrySize(entry(name))
            if (size > 0) {
                entrySizes[name] = size
                trimIfNeeded()
            } else {
                entrySizes.remove(name)
            }
        }
    }

    /** 删除单个条目(文件或目录)并移出索引 */
    fun remove(name: String) {
        executor.execute {
            deleteRecursively(entry(name))
            entrySizes.remove(name)
        }
    }

    /** 清空全部条目与索引 */
    fun clear() {
        executor.execute {
            dir.listFiles()?.forEach { if (!isShadow(it.name)) deleteRecursively(it) }
            entrySizes.clear()
        }
    }

    /**
     * 仅清空索引不删文件: 目录内容已被外部(如各模块既有的 clearDir)整目录清空时调用,
     * 避免索引残留已删除条目的尺寸记账
     */
    fun resetIndex() {
        executor.execute { entrySizes.clear() }
    }

    /** 当前记账总字节数(近似, 后台记账存在延迟窗口) */
    fun sizeBytes(): Long = entrySizes.values.sum()

    /** 手动触发一次按上限淘汰(如宿主收到 onLowMemory/storage 压力时) */
    fun trim() {
        executor.execute { trimIfNeeded() }
    }

    // ------------------------------------------------------------------

    private fun rescanLocked() {
        if (!dir.exists()) return
        val entries = dir.listFiles() ?: return
        val sizes = HashMap<String, Long>()
        entries.forEach { file ->
            if (isShadow(file.name)) return@forEach
            val size = computeEntrySize(file)
            if (size > 0) sizes[file.name] = size
        }
        entrySizes.clear()
        entrySizes.putAll(sizes)
        trimIfNeeded()
        BaseLog.d(TAG, "scan ${dir.name}: ${sizes.size} entries, ${sizes.values.sum()} bytes, cap=$maxBytes")
    }

    private fun trimIfNeeded() {
        var total = entrySizes.values.sum()
        if (total <= maxBytes) return
        // lastModified 升序 = 最旧优先淘汰; 刚写入的条目 mtime 最新, 只有单条目超上限时才会被删
        val victims = entrySizes.keys
            .sortedBy { name -> runCatching { entry(name).lastModified() }.getOrDefault(0L) }
        for (name in victims) {
            if (total <= maxBytes) break
            val fileSize = entrySizes.remove(name) ?: continue
            val deleted = deleteRecursively(entry(name))
            if (deleted) {
                total -= fileSize
                BaseLog.d(TAG, "evict $name (${fileSize}B) from ${dir.name}, total=${total}B cap=${maxBytes}B")
            }
        }
    }

    private fun computeEntrySize(file: File): Long = runCatching {
        if (!file.exists()) return@runCatching 0L
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else file.length()
    }.getOrDefault(0L)

    private fun deleteRecursively(file: File): Boolean = runCatching {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }.getOrDefault(false)

    private fun isShadow(name: String): Boolean = name.contains(SHADOW_MARKER)
}
