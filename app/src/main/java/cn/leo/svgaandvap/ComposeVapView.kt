package cn.leo.svgaandvap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import cn.leo.base.download.DownloadStatus
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.DynamicResource
import com.tencent.qgame.animplayer.inter.SimpleAnimListener
import com.tencent.qgame.animplayer.load
import com.tencent.qgame.animplayer.util.ScaleType

/**
 * VAP 加载配置
 *
 * @param loopCount 循环次数，小于 1 按播放 1 次
 */
data class VapConfig(
    val loopCount: Int = 1,
)

/**
 * 命令式控制句柄，与内部持有的 [AnimView] 一一绑定。
 * view 释放后由 [VapPlayer] 解绑，此后所有方法均为 no-op。
 */
class VapController internal constructor(
    internal val view: AnimView,
    private val onFirstFrame: () -> Unit,
    private val onDownloadState: (DownloadStatus) -> Unit,
) {

    private var lastSource: Any? = null
    private var lastConfig: VapConfig? = null
    private var lastDynamic: (DynamicResource.() -> Unit)? = null

    /** 是否正在播放（非 Compose 状态，值变化不触发重组，仅用于命令式查询） */
    val isRunning: Boolean
        get() = view.isRunning()

    /**
     * 手动加载/换源
     *
     * @param source 动画数据源，可以是 url、文件路径、assets 路径或 [java.io.File]；null 表示停止播放
     * @param dynamic 本次加载的动态内容，null 表示无
     */
    fun load(
        source: Any?,
        config: VapConfig?,
        dynamic: (DynamicResource.() -> Unit)? = null,
    ) {
        lastSource = source
        lastConfig = config
        lastDynamic = dynamic
        if (source == null) {
            //主动停止不算下载失败，不走 load(null) 触发库内 FAILED 回调
            view.stopPlay()
            return
        }
        view.load(
            data = source,
            loopCount = config?.loopCount,
            onStartRenderOnce = onFirstFrame,
            downloadState = onDownloadState,
            dynamic = dynamic ?: {},
        )
    }

    /** 从头重播最近一次加载的资源（沿用其动态内容配置） */
    fun startAnimation() {
        val source = lastSource ?: return
        view.load(
            data = source,
            loopCount = lastConfig?.loopCount,
            onStartRenderOnce = onFirstFrame,
            downloadState = onDownloadState,
            dynamic = lastDynamic ?: {},
        )
    }

    /** 停止播放 */
    fun stop() = view.stopPlay()

    /** 设置音量，范围 [0, 1] */
    fun setVolume(volume: Float) = view.setVolume(volume)
}

class VapState(initialConfig: VapConfig = VapConfig()) {

    internal var controller: VapController? = null

    /** 加载配置；修改后会驱动 [VapPlayer] 按新配置重新加载当前资源 */
    var config: VapConfig by mutableStateOf(initialConfig)

    /**
     * 是否正在播放。非 Compose 状态：值变化不会触发重组，
     * 需要随播放状态刷新 UI 时请结合 onAnimStart/onAnimEnd 回调自行驱动。
     */
    val isRunning: Boolean
        get() = controller?.isRunning ?: false

    /** 手动加载/换源；[dynamic] 为本次加载的动态内容，null 表示无 */
    fun load(source: Any?, dynamic: (DynamicResource.() -> Unit)? = null) {
        controller?.load(source, config, dynamic)
    }

    /** 从头重播最近一次加载的资源 */
    fun startAnimation() {
        controller?.startAnimation()
    }

    /** 停止播放 */
    fun stop() {
        controller?.stop()
    }

    /** 设置音量 */
    fun setVolume(volume: Float) {
        controller?.setVolume(volume)
    }

    internal fun bind(controller: VapController) {
        this.controller = controller
    }

    internal fun unbindFor(view: AnimView) {
        if (this.controller?.view === view) {
            this.controller = null
        }
    }
}

@Composable
@NonRestartableComposable
fun rememberVapPlayerState(initialConfig: VapConfig = VapConfig()): VapState =
    //注意：initialConfig 仅首次组合生效
    remember { VapState(initialConfig) }

/**
 * @param source 变化时自动重新加载；可以是 url、文件路径、assets 路径或 [java.io.File]，
 *        null 表示停止播放
 * @param dynamic 仅在（重新）加载时执行，只修改它不会触发重载；
 *        动态内容变化请调用 [VapState.load]
 * @param onFrameStart 首帧真实渲染回调（非初始化完成回调）
 * @param onError 播放失败回调
 * @param onDownloadState 网络源下载状态回调
 */
@Composable
fun VapPlayer(
    modifier: Modifier = Modifier,
    source: Any? = null,
    state: VapState = rememberVapPlayerState(),
    scaleType: ScaleType = ScaleType.FIT_CENTER,
    mute: Boolean = false,
    enableVersion1: Boolean = false,
    dynamic: (DynamicResource.() -> Unit)? = null,
    onAnimStart: () -> Unit = {},
    onFrameStart: () -> Unit = {},
    onAnimEnd: () -> Unit = {},
    onError: (errorType: Int, errorMsg: String?) -> Unit = { _, _ -> },
    onDownloadState: (DownloadStatus) -> Unit = {},
) {
    //factory/update 中安装的引用只创建一次，rememberUpdatedState 保证调用时读到最新 lambda，
    //避免 stale capture（调用方 lambda 捕获了后续变化的状态时尤为明显）
    val currentOnAnimStart by rememberUpdatedState(onAnimStart)
    val currentOnFrameStart by rememberUpdatedState(onFrameStart)
    val currentOnAnimEnd by rememberUpdatedState(onAnimEnd)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnDownloadState by rememberUpdatedState(onDownloadState)
    val currentDynamic by rememberUpdatedState(dynamic)

    //组合期读取：config 变化 → 本组件重组 → update 检测到差异后重新加载
    val config = state.config

    AndroidView(
        modifier = modifier,
        factory = { context ->
            AnimView(context).apply {
                if (enableVersion1) {
                    enableVersion1(true)
                    setVideoMode(Constant.VIDEO_MODE_SPLIT_HORIZONTAL_REVERSE)
                }
                setAnimListener(object : SimpleAnimListener() {
                    override fun onVideoStart() {
                        currentOnAnimStart()
                    }

                    override fun onVideoComplete() {
                        currentOnAnimEnd()
                    }

                    override fun onFailed(errorType: Int, errorMsg: String?) {
                        currentOnError(errorType, errorMsg)
                    }
                })
                state.bind(
                    VapController(
                        view = this,
                        onFirstFrame = { currentOnFrameStart() },
                        onDownloadState = { currentOnDownloadState(it) },
                    )
                )
            }
        },
        update = { view ->
            view.setScaleType(scaleType)
            view.setMute(mute)
            val lastSource = view.getTag(R.id.vap_compose_last_source)
            val lastConfig = view.getTag(R.id.vap_compose_last_config) as? VapConfig
            if (source != lastSource || config != lastConfig) {
                view.setTag(R.id.vap_compose_last_source, source)
                view.setTag(R.id.vap_compose_last_config, config)
                state.controller?.load(source, config, currentDynamic)
            }
        },
        onRelease = { view ->
            //先解绑再释放，杜绝 state 对已释放 view 的悬挂引用
            state.unbindFor(view)
            view.setAnimListener(null)
            view.release()
        }
    )
}
