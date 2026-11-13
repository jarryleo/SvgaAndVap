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
import cn.leo.lib_anim_compose.SvgaPlayer
import cn.leo.lib_anim_compose.rememberSvgaPlayerState
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme
import com.opensource.svgaplayer.SVGAConfig

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
    val state = rememberSvgaPlayerState()
    // SVGAImageView: loops <= 0 表示无限循环；config 变化会自动触发重新加载
    val loops = state.config?.loopCount ?: 0
    var status by remember { mutableStateOf("idle") }
    var paused by remember { mutableStateOf(false) }
    val item = SVGA_ITEMS[selected]

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
                            status =
                                if (SVGA_ITEMS[index].path.startsWith("http")) "loading" else "idle"
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            ChipRow(title = "循环") {
                listOf(0 to "无限", 1 to "1 次", 3 to "3 次").forEach { (count, label) ->
                    FilterChip(
                        selected = loops == count,
                        onClick = {
                            state.config = SVGAConfig(loopCount = count)
                            //切换循环次数会重启动画，同步复位暂停标记，保持暂停/继续按钮状态一致
                            paused = false
                        },
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
                SvgaPlayer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    svgaUrl = item.path,
                    state = state,
                    onAnimStart = { status = "playing" },
                    onFrameStart = { status = "first frame" },
                    onAnimEnd = {
                        status = "finished"
                        paused = false
                    },
                    onError = {
                        status = "load failed"
                        paused = false
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
                        if (paused) {
                            state.resume()
                            status = "playing"
                        } else {
                            state.pause()
                            status = "paused"
                        }
                        paused = !paused
                    },
                ) {
                    Text(if (paused) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = {
                        paused = false
                        state.startAnimation()
                    },
                ) {
                    Text("重播")
                }
                OutlinedButton(
                    onClick = {
                        paused = false
                        state.stop()
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
