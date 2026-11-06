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
import androidx.compose.runtime.DisposableEffect
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
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.DynamicResource
import com.tencent.qgame.animplayer.inter.SimpleAnimListener
import com.tencent.qgame.animplayer.load

private data class VapItem(
    val label: String,
    val path: String,
    val dynamic: (DynamicResource.() -> Unit)? = null,
)

private val VAP_ITEMS = listOf(
    VapItem("demo", "vap/demo.mp4") {
        // demo.mp4 内置动态元素配置, 用 assets 头像图填充(用法见 lib_vap/README.MD)
        setImage("avatar1", "vap/head.png")
    },
    VapItem("special_size_750", "vap/special_size_750.mp4"),
    VapItem("vapx", "vap/vapx.mp4"),
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
    // AnimView.load: loopCount 小于 1 时按 1 次播放
    var loops by remember { mutableIntStateOf(1) }
    var status by remember { mutableStateOf("idle") }
    var replayTick by remember { mutableIntStateOf(0) }
    var animView by remember { mutableStateOf<AnimView?>(null) }

    DisposableEffect(Unit) {
        onDispose { animView?.stopPlay() }
    }

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
                        onClick = { selected = index },
                        label = { Text(item.label) },
                    )
                }
            }
            ChipRow(title = "循环") {
                listOf(1 to "1 次", 3 to "3 次", 10 to "10 次").forEach { (count, label) ->
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
                        AnimView(context).apply {
                            setAnimListener(object : SimpleAnimListener() {
                                override fun onVideoStart() {
                                    status = "playing"
                                }

                                override fun onVideoComplete() {
                                    status = "complete"
                                }

                                override fun onVideoDestroy() {
                                    status = "destroyed"
                                }

                                override fun onFailed(errorType: Int, errorMsg: String?) {
                                    status = "failed: $errorMsg"
                                }
                            })
                            animView = this
                        }
                    },
                    update = { view ->
                        // 资源或循环次数变化时才重新加载, 避免 update 反复触发 load
                        val item = VAP_ITEMS[selected]
                        val key = "${item.path}#$loops#$replayTick"
                        if (view.tag != key) {
                            view.tag = key
                            view.load(
                                data = item.path,
                                loopCount = loops,
                                dynamic = item.dynamic ?: {},
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
                        replayTick++
                    },
                ) {
                    Text("重播")
                }
                OutlinedButton(
                    onClick = {
                        animView?.stopPlay()
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
