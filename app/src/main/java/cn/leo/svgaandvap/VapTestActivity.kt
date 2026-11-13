package cn.leo.svgaandvap

import android.os.Bundle
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
import cn.leo.base.download.DownloadState
import cn.leo.lib_anim_compose.VapConfig
import cn.leo.lib_anim_compose.VapPlayer
import cn.leo.lib_anim_compose.rememberVapPlayerState
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme
import com.tencent.qgame.animplayer.DynamicResource

private const val VAP_URL = "https://res.dawalive.com/gift/1767838791158?imageslim"

private data class VapItem(
    val label: String,
    val path: String,
    val dynamic: (DynamicResource.() -> Unit)? = null,
)

private val VAP_ITEMS = listOf(
    VapItem("demo", "vap/demo.mp4"),
    VapItem("pk_start", "vap/pk_start.mp4") {
        // 内置动态元素配置, 用 assets 头像图填充(用法见 lib_vap/README.MD)
        setImage("avatar1", "vap/head.png")
        setImage("avatar2", "https://res.dawalive.com/FtRBVni0G0T7zcSWmShW-fnHwktQ?imageslim")
        //setImage("avatar2", "https://picsum.photos/300/300?random=2")
        setText("nick1", "测试长文本跑马灯效果测试长文本跑马灯效果测试长文本跑马灯效果")
        setText("nick2", "nick2")
        setText("ID1", "ID1")
        setText("ID2", "ID2")
    },
    VapItem("vapx", "vap/vapx.mp4"),
    VapItem("gift (网络)", VAP_URL),
)

class VapTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SvgaAndVapTheme {
                VapTestScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VapTestScreen() {
    var selected by remember { mutableIntStateOf(0) }
    val state = rememberVapPlayerState()
    // AnimView.load: loopCount 小于 0 次无限循环；config 变化会自动触发重新加载
    val loops = state.config.loopCount
    var status by remember { mutableStateOf("idle") }
    val item = VAP_ITEMS[selected]

    Scaffold(
        topBar = { TopAppBar(title = { Text("VAP 测试") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChipRow(title = "资源") {
                VAP_ITEMS.forEachIndexed { index, item ->
                    FilterChip(
                        selected = index == selected,
                        onClick = {
                            selected = index
                            status =
                                if (VAP_ITEMS[index].path.startsWith("http")) "downloading 0%"
                                else "idle"
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            ChipRow(title = "循环") {
                listOf(1 to "1 次", 3 to "3 次", 10 to "10 次").forEach { (count, label) ->
                    FilterChip(
                        selected = loops == count,
                        onClick = { state.config = VapConfig(loopCount = count) },
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
                VapPlayer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    source = item.path,
                    state = state,
                    dynamic = item.dynamic,
                    onAnimStart = { status = "playing" },
                    onFrameStart = { status = "first frame" },
                    onAnimEnd = { status = "complete" },
                    onError = { _, errorMsg -> status = "failed: $errorMsg" },
                    onDownloadState = { ds ->
                        status = when (ds.state) {
                            DownloadState.DOWNLOADING -> "downloading ${ds.progress}%"
                            DownloadState.FAILED -> "download failed: ${ds.exception?.message}"
                            else -> status
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
                    onClick = { state.startAnimation() },
                ) {
                    Text("重播")
                }
                OutlinedButton(
                    onClick = {
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
