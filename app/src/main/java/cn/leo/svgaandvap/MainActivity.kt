package cn.leo.svgaandvap

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme
import com.opensource.svgaplayer.SVGAManager
import com.tencent.qgame.animplayer.VapManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 初始化两个库的缓存目录(幂等), 保证"清除缓存"在未播放过网络动画时也能清到文件缓存
        SVGAManager.init(applicationContext, logEnabled = true)
        VapManager.init(applicationContext, logEnabled = true)
        setContent {
            SvgaAndVapTheme {
                HomeScreen(
                    onOpenSvga = { startActivity(Intent(this, SvgaTestActivity::class.java)) },
                    onOpenVap = { startActivity(Intent(this, VapTestActivity::class.java)) },
                    onClearCache = {
                        // 清除 SVGA 与 VAP 的内存缓存 + 文件缓存
                        SVGAManager.clearCache()
                        VapManager.clearCache()
                        Toast.makeText(this, "SVGA 与 VAP 缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onOpenSvga: () -> Unit,
    onOpenVap: () -> Unit,
    onClearCache: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "SvgaAndVap",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "动画库测试 Demo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            )
            Button(
                onClick = onOpenSvga,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
            ) {
                Text(text = "SVGA 测试")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpenVap,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
            ) {
                Text(text = "VAP 测试")
            }
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedButton(
                onClick = onClearCache,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(48.dp),
            ) {
                Text(text = "清除缓存")
            }
        }
    }
}
