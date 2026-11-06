package cn.leo.svgaandvap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAImageView

private const val SVGA_URL =
    "https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true"

private data class SvgaItem(val label: String, val path: String)

private val SVGA_ITEMS = listOf(
    SvgaItem("angel", "svga/angel.svga"),
    SvgaItem("Castle", "svga/Castle.svga"),
    SvgaItem("MerryChristmas", "svga/MerryChristmas.svga"),
    SvgaItem("rose", "svga/rose.svga"),
    SvgaItem("posche (网络)", SVGA_URL),
)

class SvgaTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SvgaAndVapTheme {
                SvgaTestScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SvgaTestScreen() {
    var selected by remember { mutableIntStateOf(0) }
    // SVGAImageView: loops <= 0 表示无限循环
    var loops by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("idle") }
    var paused by remember { mutableStateOf(false) }
    var replayTick by remember { mutableIntStateOf(0) }
    var svgaView by remember { mutableStateOf<SVGAImageView?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SVGA 测试") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChipRow(title = "资源") {
                SVGA_ITEMS.forEachIndexed { index, item ->
                    FilterChip(
                        selected = index == selected,
                        onClick = {
                            selected = index
                            paused = false
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            ChipRow(title = "循环") {
                listOf(0 to "无限", 1 to "1 次", 3 to "3 次").forEach { (count, label) ->
                    FilterChip(
                        selected = loops == count,
                        onClick = { loops = count },
                        label = { Text(label) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF15171C), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    factory = { context ->
                        SVGAImageView(context).apply {
                            callback = object : SVGACallback {
                                override fun onStart() {
                                    status = "playing"
                                }

                                override fun onPause() {
                                    status = "paused"
                                }

                                override fun onResume() {
                                    status = "playing"
                                }

                                override fun onFinished() {
                                    status = "finished"
                                    paused = false
                                }

                                override fun onRepeat() {
                                    status = "repeat"
                                }

                                override fun onCancel() {
                                    status = "canceled"
                                }
                            }
                            svgaView = this
                        }
                    },
                    update = { view ->
                        // 资源或循环次数变化时才重新加载, 避免 update 反复触发 load
                        val item = SVGA_ITEMS[selected]
                        val key = "${item.path}#$loops#$replayTick"
                        if (view.tag != key) {
                            view.tag = key
                            status = if (item.path.startsWith("http")) "loading" else "idle"
                            view.load(
                                source = item.path,
                                config = SVGAConfig(loopCount = loops),
                                onError = {
                                    status = "load failed"
                                    paused = false
                                },
                            )
                        }
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                OutlinedButton(
                    onClick = {
                        svgaView?.let { view ->
                            if (paused) view.resumeAnimation() else view.pauseAnimation()
                            paused = !paused
                        }
                    },
                ) {
                    Text(if (paused) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = {
                        replayTick++
                        paused = false
                    },
                ) {
                    Text("重播")
                }
                OutlinedButton(
                    onClick = {
                        svgaView?.stopAnimation()
                        paused = false
                        status = "stopped"
                    },
                ) {
                    Text("停止")
                }
            }
            Text(
                text = "状态: $status",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
internal fun ChipRow(
    title: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
        )
        content()
    }
}
