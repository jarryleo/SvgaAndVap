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
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAImageView

/**
 * 命令式控制句柄，与内部持有的 [SVGAImageView] 一一绑定。
 * view 释放后由 [SvgaPlayer] 解绑，此后所有方法均为 no-op。
 */
class SvgaController internal constructor(
    internal val view: SVGAImageView,
    private val onError: () -> Unit,
) {

    /** 是否正在播放（非 Compose 状态，值变化不触发重组，仅用于命令式查询） */
    val isAnimating: Boolean
        get() = view.isAnimating

    /** 手动加载/换源 */
    fun load(
        url: String?,
        config: SVGAConfig? = null,
        dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null,
    ) {
        view.load(url, config, onError = { onError() }, dynamicBlock = dynamicBlock)
    }

    /** 清理动画资源（含 drawable 与在途加载） */
    fun clear() = view.clear()

    /** 停止播放，保留当前帧画面 */
    fun stop() = view.stopAnimation()

    /** 暂停动画 */
    fun pause() = view.pauseAnimation()

    /** 恢复动画 */
    fun resume() = view.resumeAnimation()

    /** 从头重播当前已加载的动画 */
    fun startAnimation() = view.startAnimation()

    /** 设置音量 */
    fun setVolume(volume: Float) = view.setVolume(volume)

    /** 跳转到指定帧 */
    fun stepToFrame(frame: Int, andPlay: Boolean = true) = view.stepToFrame(frame, andPlay)

    /** 跳转到指定百分比 */
    fun stepToPercentage(percentage: Double, andPlay: Boolean = true) =
        view.stepToPercentage(percentage, andPlay)
}

class SvgaState(initialConfig: SVGAConfig? = null) {

    internal var controller: SvgaController? = null

    /** 加载配置；修改后会驱动 [SvgaPlayer] 按新配置重新加载当前 url */
    var config: SVGAConfig? by mutableStateOf(initialConfig)

    /**
     * 是否正在播放动画。非 Compose 状态：值变化不会触发重组，
     * 需要随播放状态刷新 UI 时请结合 onAnimStart/onAnimEnd 回调自行驱动。
     */
    val isAnimating: Boolean
        get() = controller?.isAnimating ?: false

    /** 手动加载/换源；同一资源时库会原地更新 dynamicItem 而不重新解码 */
    fun load(url: String?, dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null) {
        controller?.load(url, config, dynamicBlock)
    }

    /** 清理动画资源 */
    fun clear() {
        controller?.clear()
    }

    /** 停止播放，保留当前帧 */
    fun stop() {
        controller?.stop()
    }

    /** 暂停动画 */
    fun pause() {
        controller?.pause()
    }

    /** 恢复动画 */
    fun resume() {
        controller?.resume()
    }

    /** 从头重播当前已加载的动画 */
    fun startAnimation() {
        controller?.startAnimation()
    }

    /** 设置音量 */
    fun setVolume(volume: Float) {
        controller?.setVolume(volume)
    }

    /** 跳转到指定帧 */
    fun stepToFrame(frame: Int, andPlay: Boolean = true) {
        controller?.stepToFrame(frame, andPlay)
    }

    /** 跳转到指定百分比 */
    fun stepToPercentage(percentage: Double, andPlay: Boolean = true) {
        controller?.stepToPercentage(percentage, andPlay)
    }

    internal fun bind(controller: SvgaController) {
        this.controller = controller
    }

    internal fun unbindFor(view: SVGAImageView) {
        if (this.controller?.view === view) {
            this.controller = null
        }
    }
}

@Composable
@NonRestartableComposable
fun rememberSvgaPlayerState(initialConfig: SVGAConfig? = null): SvgaState =
    //注意：initialConfig 仅首次组合生效
    remember { SvgaState(initialConfig) }

/**
 * @param svgaUrl 变化时自动重新加载；null 表示不展示（清屏）
 * @param dynamicBlock 仅在（重新）加载时执行，只修改它不会触发重载；
 *        动态内容变化请调用 [SvgaState.load]（同一资源时库会原地更新 dynamicItem）
 * @param onFrameStart 首帧渲染回调，仅在播放（autoPlay）路径下触发
 */
@Composable
fun SvgaPlayer(
    modifier: Modifier = Modifier,
    svgaUrl: String? = null,
    state: SvgaState = rememberSvgaPlayerState(),
    fillMode: SVGAImageView.FillMode = SVGAImageView.FillMode.Backward,
    dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null,
    onAnimStart: () -> Unit = {},
    onFrameStart: () -> Unit = {},
    onAnimEnd: () -> Unit = {},
    onError: () -> Unit = {},
) {
    //factory/update 中安装的引用只创建一次，rememberUpdatedState 保证调用时读到最新 lambda，
    //避免 stale capture（调用方 lambda 捕获了后续变化的状态时尤为明显）
    val currentOnAnimStart by rememberUpdatedState(onAnimStart)
    val currentOnFrameStart by rememberUpdatedState(onFrameStart)
    val currentOnAnimEnd by rememberUpdatedState(onAnimEnd)
    val currentOnError by rememberUpdatedState(onError)
    val currentDynamicBlock by rememberUpdatedState(dynamicBlock)

    //组合期读取：config 变化 → 本组件重组 → update 检测到差异后重新加载
    val config = state.config

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SVGAImageView(context).apply {
                callback = object : SVGACallback {
                    override fun onStart() {
                        currentOnAnimStart()
                    }

                    override fun onStep(frame: Int, percentage: Double) {
                        //帧号从 0 开始；标记在每次 load 前重置，保证换源后仍能回调
                        if (frame == 0 && getTag(R.id.svga_compose_frame_state) != true) {
                            setTag(R.id.svga_compose_frame_state, true)
                            currentOnFrameStart()
                        }
                    }

                    override fun onFinished() {
                        currentOnAnimEnd()
                    }
                }
                state.bind(SvgaController(this) { currentOnError() })
            }
        },
        update = { view ->
            view.fillMode = fillMode
            val lastUrl = view.getTag(R.id.svga_compose_last_url) as? String
            val lastConfig = view.getTag(R.id.svga_compose_last_config) as? SVGAConfig
            if (svgaUrl != lastUrl || config != lastConfig) {
                view.setTag(R.id.svga_compose_last_url, svgaUrl)
                view.setTag(R.id.svga_compose_last_config, config)
                //新一轮加载：重置首帧标记，否则 onFrameStart 只会回调一次
                view.setTag(R.id.svga_compose_frame_state, false)
                if (svgaUrl == null) {
                    //主动清屏不算错误，避免走 load(null) 触发库内 onError
                    view.clear()
                } else {
                    view.load(
                        svgaUrl,
                        config,
                        onError = { currentOnError() },
                        dynamicBlock = currentDynamicBlock,
                    )
                }
            }
        },
        onRelease = { view ->
            //先解绑再清理，杜绝 state 对已释放 view 的悬挂引用
            state.unbindFor(view)
            view.callback = null
            view.clear()
            view.clearLastSource()
        }
    )
}
