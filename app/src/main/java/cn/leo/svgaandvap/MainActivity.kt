package cn.leo.svgaandvap

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.leo.svgaandvap.ui.theme.SvgaAndVapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SvgaAndVapTheme {
                HomeScreen(
                    onOpenSvga = { startActivity(Intent(this, SvgaTestActivity::class.java)) },
                    onOpenVap = { startActivity(Intent(this, VapTestActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(onOpenSvga: () -> Unit, onOpenVap: () -> Unit) {
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
        }
    }
}
