package cn.leo.svgaandvap

import android.os.Bundle
import android.os.Debug
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.leo.base.download.DownloadState
import cn.leo.base.download.DownloadStatus
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGAManager
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.WeakHashMap

/**
 * SVGA 列表复用测试页。
 *
 * 压力特征：10 个头像框 svga，每个按原图解码约占 100MB 内存；列表 100 条循环这 10 个 url。
 * 页面用于验证库的条目复用与内存治理：
 * 1. 复用：LazyColumn + AndroidView(onReset)，view 滚出后被回收，重新绑定给其它条目（跨条目复用）；
 *    view detach 自动停帧清资源、在途加载取消，attach 后按新条目 url 重新加载。
 * 2. 省内存：默认按视图尺寸解码（bitmap 只缩不放），单实体内存从 ~100MB 降到 MB 级，
 *    配合内存缓存（isCacheToMemory）同 url 反复 bind 直接命中，不重复解码。
 * 3. 压力对比：切"原图"模式按 100MB/个解码（超内存缓存预算，不进缓存），
 *    用于观察无缓存复用下的内存与流畅度差异。
 * 4. 可观测：每秒采样进程 PSS 与实体内存缓存占用，逐条目显示下载/解码/播放状态。
 */
private val ITEM_URLS = listOf(
    "https://res.fancyliveapp.com/headwear/1694771685260?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095126632?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095193469?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095244663?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095289004?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095359896?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095408915?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095462029?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095548666?imageslim",
    "https://res.fancyliveapp.com/headwear/1695095567925?imageslim",
)

private const val ITEM_COUNT = 100
private val ITEM_SIZE = 96.dp

class SvgaListTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SvgaAndVapTheme {
                SvgaListScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SvgaListScreen() {
    var originalSize by remember { mutableStateOf(false) }
    var memCache by remember { mutableStateOf(true) }
    var infiniteLoop by remember { mutableStateOf(true) }
    var pauseOnScroll by remember { mutableStateOf(false) }
    // frameWidth/Height 保持 0：按 view 实际 px 解码（省内存）；isOriginal 时按原图解码
    val config = SVGAConfig(
        isOriginal = originalSize,
        isCacheToMemory = memCache,
        loopCount = if (infiniteLoop) 0 else 1,
        autoPlay = true,
    )
    val itemStates = remember {
        List(ITEM_COUNT) { i -> ItemUi(i, ITEM_URLS[i % ITEM_URLS.size]) }
    }
    val listState = rememberLazyListState()
    //当前存活（已绑定未回收）的 SVGAImageView，弱引用持有，供滑动暂停使用；仅主线程访问
    val activeViews =
        remember { Collections.newSetFromMap(WeakHashMap<SVGAImageView, Boolean>()) }
    //滑动期间被本策略暂停的 view，停止后只恢复这些（不误恢复已自然结束的动画）
    val pausedByScroll = remember { mutableSetOf<SVGAImageView>() }
    val playingCount by remember {
        derivedStateOf { itemStates.count { it.animState == ItemAnimState.PLAYING } }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    var pssText by remember { mutableStateOf("--") }
    var cacheText by remember { mutableStateOf("--") }

    //每秒采样进程 PSS 与实体内存缓存占用，观察复用命中/资源释放效果
    LaunchedEffect(Unit) {
        while (true) {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            pssText = Formatter.formatFileSize(context, memInfo.totalPss * 1024L)
            cacheText = Formatter.formatFileSize(
                context,
                SVGAMemoryCache.INSTANCE.sizeBytes().toLong()
            )
            delay(1000)
        }
    }

    //拖动/惯性滚动期间暂停可见动画（省去每帧 canvas 绘制），停止后仅恢复被本策略暂停的
    LaunchedEffect(pauseOnScroll) {
        if (!pauseOnScroll) {
            pausedByScroll.forEach { it.resumeAnimation() }
            pausedByScroll.clear()
            return@LaunchedEffect
        }
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                activeViews.forEach { view ->
                    if (view.isAnimating) {
                        view.pauseAnimation()
                        pausedByScroll.add(view)
                    }
                }
            } else {
                pausedByScroll.forEach { it.resumeAnimation() }
                pausedByScroll.clear()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SVGA 列表复用测试") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChipRow(title = "解码") {
                FilterChip(
                    selected = !originalSize,
                    onClick = { originalSize = false },
                    label = { Text("视图尺寸(省内存)") },
                )
                FilterChip(
                    selected = originalSize,
                    onClick = { originalSize = true },
                    label = { Text("原图(~100MB)") },
                )
            }
            ChipRow(title = "缓存") {
                FilterChip(
                    selected = memCache,
                    onClick = { memCache = true },
                    label = { Text("内存缓存(复用命中)") },
                )
                FilterChip(
                    selected = !memCache,
                    onClick = { memCache = false },
                    label = { Text("仅磁盘(每次重解码)") },
                )
            }
            ChipRow(title = "循环") {
                FilterChip(
                    selected = infiniteLoop,
                    onClick = { infiniteLoop = true },
                    label = { Text("无限") },
                )
                FilterChip(
                    selected = !infiniteLoop,
                    onClick = { infiniteLoop = false },
                    label = { Text("1 次") },
                )
            }
            ChipRow(title = "滚动") {
                FilterChip(
                    selected = pauseOnScroll,
                    onClick = { pauseOnScroll = !pauseOnScroll },
                    label = { Text("滑动时暂停动画") },
                )
            }
            Text(
                text = "进程内存: $pssText · 实体缓存: $cacheText · 播放中: $playingCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (originalSize) {
                Text(
                    text = "压力模式：单个实体按原图解码约 100MB，超出缓存预算不进内存缓存，仅供对比",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val px = with(density) { ITEM_SIZE.toPx() }.toInt()
                        SVGAManager.preload(ITEM_URLS, px, px)
                        Toast.makeText(
                            context,
                            "开始预下载 ${ITEM_URLS.size} 个资源",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("预加载")
                }
                OutlinedButton(
                    onClick = { listState.requestScrollToItem(ITEM_COUNT - 1) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("跳到底部")
                }
                OutlinedButton(
                    onClick = { listState.requestScrollToItem(0) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("回顶部")
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(itemStates, key = { it.index }) { ui ->
                    SvgaListItem(
                        ui = ui,
                        config = config,
                        activeViews = activeViews,
                        pausedByScroll = pausedByScroll,
                    )
                }
            }
        }
    }
}

@Composable
private fun SvgaListItem(
    ui: ItemUi,
    config: SVGAConfig,
    activeViews: MutableSet<SVGAImageView>,
    pausedByScroll: MutableSet<SVGAImageView>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF15171C))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ITEM_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            //静态头像占位
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AVATAR_COLORS[ui.index % AVATAR_COLORS.size]),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${'A' + ui.index % 26}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            //SVGA 头像框：onReset 使 LazyColumn 在条目滚出后回收 view 供其它条目复用
            val binding = remember { ItemBinding() }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SVGAImageView(ctx).apply {
                        //detach 自动清资源释放实体（默认 true），再额外清 lastSource：
                        //避免复用重挂载时 onAttachedToWindow 按"上一条目"的 url 空跑一轮解码
                        clearsAfterDetached = true
                        clearsLastSourceOnDetached = true
                    }
                },
                onReset = { view ->
                    //view 被收回复用池：停播清资源并复位状态，避免统计残留"播放中"
                    activeViews.remove(view)
                    pausedByScroll.remove(view)
                    view.clear()
                    view.clearLastSource()
                    ui.animState = ItemAnimState.RECYCLED
                    ui.detail = "已回收"
                },
                onRelease = { view ->
                    activeViews.remove(view)
                    pausedByScroll.remove(view)
                    view.clear()
                    view.clearLastSource()
                    ui.animState = ItemAnimState.RECYCLED
                    ui.detail = "已回收"
                },
                update = { view ->
                    activeViews.add(view)
                    //回调每次重绑都重装：复用的 view 换了条目，回调必须命中当前槽位的状态
                    view.callback = object : SVGACallback {
                        override fun onStart() {
                            ui.animState = ItemAnimState.PLAYING
                            ui.detail = "播放中 · ${view.getSvgaMemorySizeFormat()}"
                        }

                        override fun onFinished() {
                            ui.animState = ItemAnimState.FINISHED
                            ui.detail = "已结束"
                        }
                    }
                    if (binding.url == ui.url && binding.config == config) return@AndroidView
                    binding.url = ui.url
                    binding.config = config
                    ui.animState = ItemAnimState.LOADING
                    ui.detail = "解码中…"
                    //先 clear 再 load：切换解码尺寸/缓存开关后，同 url 直接 load 会命中库内
                    //"原地重播"路径跳过重解码，clear 保证按新 config 真实重解码
                    view.clear()
                    view.load(
                        ui.url,
                        config,
                        onError = {
                            ui.animState = ItemAnimState.FAILED
                            ui.detail = "加载失败"
                        },
                        onDownloadState = { status -> applyDownloadStatus(ui, status) },
                    )
                },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = "#${ui.index + 1} · ${shortId(ui.url)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ui.detail,
                style = MaterialTheme.typography.labelSmall,
                color = when (ui.animState) {
                    ItemAnimState.LOADING -> Color(0xFFB0B8C4)
                    ItemAnimState.PLAYING -> Color(0xFF66D88A)
                    ItemAnimState.FINISHED -> Color(0xFF7FA8FF)
                    ItemAnimState.FAILED -> Color(0xFFFF7B7B)
                    ItemAnimState.RECYCLED -> Color(0xFF6B7480)
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 条目动画状态 */
private enum class ItemAnimState { LOADING, PLAYING, FINISHED, FAILED, RECYCLED }

/** 每条目的 UI 状态：条目身份固定（index+url），view 复用不影响其归属 */
private class ItemUi(val index: Int, val url: String) {
    var animState by mutableStateOf(ItemAnimState.LOADING)
    var detail by mutableStateOf("等待绑定")
}

/** 单个 view 槽位的最近一次绑定记录，防止重复 load */
private class ItemBinding {
    var url: String? = null
    var config: SVGAConfig? = null
}

private fun applyDownloadStatus(ui: ItemUi, status: DownloadStatus) {
    when (status.state) {
        DownloadState.QUEUED -> {
            ui.animState = ItemAnimState.LOADING
            ui.detail = "排队中…"
        }

        DownloadState.DOWNLOADING -> {
            ui.animState = ItemAnimState.LOADING
            ui.detail = "下载 ${status.progress}%"
        }

        DownloadState.FAILED -> {
            ui.animState = ItemAnimState.FAILED
            ui.detail = "下载失败: ${status.exception?.message ?: "未知"}"
        }

        else -> Unit // COMPLETED 后进入解码，终态由 onStart/onFinished/onError 更新
    }
}

private fun shortId(url: String): String = url.substringAfterLast('/').substringBefore('?')

private val AVATAR_COLORS = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF9C6BFF),
    Color(0xFFFF7EA0),
    Color(0xFFFFB65C),
    Color(0xFF4FC3A1),
    Color(0xFF6BCB77),
    Color(0xFF64B5F6),
    Color(0xFFF06292),
    Color(0xFF9575CD),
    Color(0xFFFFA726),
)
