package cn.leo.svgaandvap

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensource.svgaplayer.SVGAManager
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGASimpleCallback
import java.io.File

/**
 * 临时调试页: 用 lib_svga 自身的 SVGAParser 解析, 展示 SVGAVideoEntity 里的全部 bitmap, 不会发布
 */
class BitmapViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //兜底: 单独拉起本页时也保证 parser 已初始化(init 幂等)
        SVGAManager.init(applicationContext, logEnabled = true)
        setContent {
            MaterialTheme {
                BitmapViewerScreen(cacheDir = cacheDir, resolver = contentResolver)
            }
        }
    }
}

private data class BitmapItem(
    val name: String,
    val bitmap: Bitmap,
)

@Composable
private fun BitmapViewerScreen(
    cacheDir: File,
    resolver: android.content.ContentResolver,
) {
    val assetNames = listOf("Castle.svga", "MerryChristmas.svga", "angel.svga", "rose.svga")
    var items by remember { mutableStateOf<List<BitmapItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(assetNames.first()) } // 默认加载第一个资产
    var preview by remember { mutableStateOf<BitmapItem?>(null) }
    var specText by remember { mutableStateOf("") }
    var showSpec by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selected = uri.toString()
            loading = true
            error = null
            items = emptyList()
        }
    }

    fun load(key: String, onResult: (List<BitmapItem>, String?, String) -> Unit) {
        val parser = SVGAParser.shareParser()
        if (parser == null) {
            onResult(emptyList(), "SVGAParser 未初始化", "")
            return
        }
        val callback = object : SVGASimpleCallback() {
            override fun onComplete(videoItem: com.opensource.svgaplayer.SVGAVideoEntity) {
                onResult(videoItem.bitmapEntries().toItems(), null, videoItem.specJsonText ?: "")
            }

            override fun onError() {
                onResult(emptyList(), "解析失败", "")
            }
        }
        if (key.startsWith("content://")) {
            // 外部文件: 拷到临时文件再交给库解析
            val tmp = File(cacheDir, "bitmap_viewer_tmp.svga")
            try {
                (resolver.openInputStream(Uri.parse(key)) ?: error("open failed"))
                    .use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                onResult(emptyList(), "读取文件失败: ${e.message}", "")
                return
            }
            parser.decodeFromFile(tmp.absolutePath, com.opensource.svgaplayer.SVGAConfig(), callback)
        } else {
            parser.decodeFromAssets("svga/$key", callback)
        }
    }

    LaunchedEffect(selected) {
        val key = selected ?: return@LaunchedEffect
        load(key) { list, err, json ->
            items = list
            error = err
            specText = json
            loading = false
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                assetNames.forEach { name ->
                    SuggestionChip(
                        onClick = {
                            selected = name
                            loading = true
                            error = null
                            items = emptyList()
                            specText = ""
                        },
                        label = { Text(name) },
                    )
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("选择文件")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showSpec = true },
                    enabled = specText.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (specText.isEmpty()) "控制JSON(加载后可用)" else "控制JSON")
                }
            }
            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.name }) { item ->
                        Card(modifier = Modifier.clickable { preview = item }) {
                            Column {
                                Image(
                                    bitmap = item.bitmap.asImageBitmap(),
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0x22000000)),
                                )
                                Text(
                                    item.name,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                                Text(
                                    "${item.bitmap.width}x${item.bitmap.height}",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
            title = { Text(item.name, fontSize = 14.sp) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = item.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0x11000000)),
                    )
                    Text("${item.bitmap.width} x ${item.bitmap.height}", fontSize = 12.sp)
                }
            },
        )
    }

    if (showSpec && specText.isNotEmpty()) {
        //全屏覆盖层: 长文本上下滚动查看
        Box(modifier = Modifier.fillMaxSize().background(Color(0xEE141414))) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MovieEntity 控制 JSON", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSpec = false }) { Text("关闭", color = Color(0xFF9E86FF)) }
                }
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        specText,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFDDDDDD),
                    )
                }
            }
        }
    }
}

/** 复制一份, 防止实体被 LRU 驱逐回收后网格里显示已回收的 bitmap */
private fun Map<String, Bitmap>.toItems(): List<BitmapItem> {
    val list = mutableListOf<BitmapItem>()
    for ((key, bmp) in this) {
        if (bmp.isRecycled) continue
        val safe = runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: bmp
        list.add(BitmapItem(key, safe))
    }
    return list.sortedBy { it.name }
}
