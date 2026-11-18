package com.tencent.qgame.animplayer

/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.tencent.qgame.animplayer.file.CustomAssetsFileContainer
import com.tencent.qgame.animplayer.file.FileContainer
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.mix.TxtMarqueeConfig
import com.tencent.qgame.animplayer.textureview.InnerTextureView
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IScaleType
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.animplayer.util.ScaleTypeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

open class AnimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : IAnimView, FrameLayout(context, attrs, defStyleAttr),
    TextureView.SurfaceTextureListener, CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "AnimView"
    }

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var surface: SurfaceTexture? = null
    private var animListener: IAnimListener? = null
    private var innerTextureView: InnerTextureView? = null
    private var lastFile: IFileContainer? = null
    private val scaleTypeUtil = ScaleTypeUtil()

    @Volatile
    private var afterStopRunnable: Runnable? = null

    // 首帧回调: 主线程 startPlayForce 写, 解码线程 onVideoRender 消费, 需保证可见性
    @Volatile
    private var onStartRenderCallback: (() -> Unit)? = null

    // 播放请求代际号:每次 startPlayForce 递增,用于作废停止-重启链路中
    // 过期未执行的 afterStopRunnable,避免其抢占新请求。读写均在主线程。
    private var playGeneration = 0

    @Volatile
    internal var loadJob: Job? = null

    // 动态网络图预下载任务(见 AnimViewExt.startDynamicImageDownload)
    @Volatile
    internal var dynamicDownloadJob: Job? = null

    // 动态图加载会话代数号:每次 load(dynamic=...) 递增,
    // 异步下载完成后的注入据此校验会话有效性,防止过期会话的图片注入新会话的同名tag
    private val dynamicLoadEpoch = AtomicInteger(0)

    private val player: AnimPlayer by lazy {
        AnimPlayer(this).apply {
            animListener = animProxyListener
        }
    }

    // 代理监听
    // 对外回调线程契约: 除 onVideoConfigReady 外, animListener 的所有回调
    // (onVideoStart/onVideoRender/onVideoComplete/onVideoDestroy/onFailed)
    // 与 startPlayForce 的 onStartRenderOnce 均统一派发到主线程;
    // onVideoConfigReady 因返回值参与启动决策, 保持在渲染线程同步回调(实现方不应在其中做耗时操作)
    private val animProxyListener by lazy {
        object : IAnimListener {

            override fun onVideoConfigReady(config: AnimConfig): Boolean {
                ALog.d(
                    TAG, "onVideoConfigReady width = ${config.width}, height = ${config.height}"
                )
                updateVideoSize(config.width, config.height)
                return animListener?.onVideoConfigReady(config) ?: super.onVideoConfigReady(config)
            }

            override fun onVideoStart() {
                ALog.d(TAG, "onVideoStart isForcePlayRunner = false")
                notifyUserOnMain { it.onVideoStart() }
            }

            override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
                // 每帧回调仅在用户监听存在时才派发, 避免无监听时的空转消息
                if (animListener != null) {
                    notifyUserOnMain { it.onVideoRender(frameIndex, config) }
                }
                if (onStartRenderCallback != null && frameIndex == 1) {
                    ALog.d(TAG, "onVideoRender isForcePlayRunner = false")
                    val callback = onStartRenderCallback
                    onStartRenderCallback = null
                    ui { callback?.invoke() }
                }
            }

            override fun onVideoComplete() {
                notifyUserOnMain { it.onVideoComplete() }
                ALog.d(
                    TAG,
                    "onVideoComplete player.playLoop = ${player.playLoop}"
                )
                // 防御:上一个会话的完成回调迟到时,若新会话已在启动/播放,
                // 不再清屏/销毁,避免拆掉新会话刚创建的纹理视图。
                if (player.isRunning()) {
                    ALog.d(TAG, "onVideoComplete ignore stale completion, new session is running")
                    return
                }
                // 停止-重启交接中(afterStopRunnable 待执行):lastFile 已指向新会话的容器,
                // 走销毁流程回收旧会话并触发 onVideoDestroy 交棒,但不得关闭新容器。
                if (afterStopRunnable != null) {
                    ALog.d(TAG, "onVideoComplete handoff pending, destroy old session only")
                    destroy()
                    return
                }
                if (player.playLoop <= 0) {
                    destroy()
                } else {
                    clearView()
                }
            }

            override fun onVideoDestroy() {
                // onVideoDestroy 来自 renderThread:对外回调与停止-重启交接统一切回主线程,
                // 保证 afterStopRunnable 的读取、代际校验与 startPlayForce 串行化。
                ui {
                    notifyUserOnMain { it.onVideoDestroy() }
                    val runnable = afterStopRunnable
                    afterStopRunnable = null
                    ALog.d(
                        TAG,
                        "onVideoDestroy isForcePlayRunner = false, afterStopRunnable = $runnable"
                    )
                    runnable?.run()
                }
            }

            override fun onFailed(errorType: Int, errorMsg: String?) {
                ALog.d(
                    TAG,
                    "onFailed isForcePlayRunner = false, errorType = $errorType, errorMsg = $errorMsg"
                )
                notifyUserOnMain { it.onFailed(errorType, errorMsg) }
            }

        }
    }

    /** 对外回调统一派发到主线程(线程契约见 animProxyListener 注释); 无监听时 no-op */
    private inline fun notifyUserOnMain(crossinline notify: (IAnimListener) -> Unit) {
        val listener = animListener ?: return
        ui { notify(listener) }
    }

    // 保证AnimView已经布局完成才加入TextureView
    private var onSizeChangedCalled = false

    // 已创建 TextureView 使用的 LayoutParams 尺寸(MATCH_PARENT 时为 -1),
    // 用于判断是否需要重建,不能用测量后的实际宽高比较,否则每次都会重建
    private var lastTextureLpWidth = 0
    private var lastTextureLpHeight = 0
    private val prepareTextureViewRunnable = Runnable {
        val lp = scaleTypeUtil.getLayoutParam(this@AnimView)
        if (innerTextureView == null || lastTextureLpWidth != lp.width || lastTextureLpHeight != lp.height) {
            removeAllViews()
            innerTextureView = InnerTextureView(context).apply {
                player = this@AnimView.player
                isOpaque = false
                surfaceTextureListener = this@AnimView
                layoutParams = lp
            }
            lastTextureLpWidth = lp.width
            lastTextureLpHeight = lp.height
            addView(innerTextureView, lp)
            ALog.d(
                TAG, "prepareTextureViewRunnable width = ${lp.width}, height = ${lp.height}"
            )
        }
    }

    private fun updateVideoSize(width: Int, height: Int) {
        ui {
            scaleTypeUtil.setVideoSize(width, height)
            ALog.d(TAG, "updateVideoSize scaleTypeUtil.setVideoSize($width, $height)")
            if (!onSizeChangedCalled) {
                ALog.d(TAG, "updateVideoSize onSizeChanged not called")
                return@ui
            }
            val textureView = innerTextureView
            if (textureView == null) {
                uiHandler.removeCallbacks(prepareTextureViewRunnable)
                uiHandler.post(prepareTextureViewRunnable)
                ALog.d(TAG, "updateVideoSize prepareTextureViewRunnable called")
                return@ui
            }
            scaleTypeUtil.getRealSize().let {
                if (it.first != width || it.second != height) {
                    val lp = scaleTypeUtil.getLayoutParam(this@AnimView)
                    textureView.layoutParams = lp
                    lastTextureLpWidth = lp.width
                    lastTextureLpHeight = lp.height
                    ALog.d(TAG, "updateVideoSize width = $width height = $height")
                }
            }
        }
    }

    private fun clearView() {
        ui {
            removeAllViews()
            innerTextureView?.surfaceTextureListener = null
            innerTextureView = null
        }
    }

    /**
     * 每次播放都需要调用一次，获取视频尺寸用来设置TextureView的LayoutParams
     */
    override fun prepareTextureView() {
        if (onSizeChangedCalled) {
            ALog.d(TAG, "prepareTextureViewRunnable called")
            uiHandler.removeCallbacks(prepareTextureViewRunnable)
            uiHandler.post(prepareTextureViewRunnable)
        } else {
            ALog.d(TAG, "prepareTextureView onSizeChanged not called")
        }
    }

    override fun getSurfaceTexture(): SurfaceTexture? {
        return innerTextureView?.surfaceTexture ?: surface
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.d(TAG, "onSurfaceTextureSizeChanged $width x $height")
        player.onSurfaceTextureSizeChanged(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        ALog.d(TAG, "onSurfaceTextureDestroyed")
        this.surface = null
        player.onSurfaceTextureDestroyed()
        return true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.d(TAG, "onSurfaceTextureAvailable width=$width height=$height")
        this.surface = surface
        player.onSurfaceTextureAvailable(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scaleTypeUtil.setLayoutSize(w, h)
        onSizeChangedCalled = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player.isDetachedFromWindow = false
        onResume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        player.isDetachedFromWindow = true
        loadJob?.cancel("onDetachedFromWindow")
        loadJob = null
        dynamicDownloadJob?.cancel("onDetachedFromWindow")
        dynamicDownloadJob = null
        onPause()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        when (visibility) {
            VISIBLE -> onResume()
            INVISIBLE, GONE -> onPause()
        }
    }


    override fun setAnimListener(animListener: IAnimListener?) {
        this.animListener = animListener
    }

    override fun setFetchResource(fetchResource: IFetchResource?) {
        player.pluginManager.getMixAnimPlugin()?.resourceRequest = fetchResource
    }

    /**
     * 运行中更新动态图片元素(如网络动态图异步下载完成后注入, 见 load(dynamic=...) 说明)
     * @param tag 动态元素 srcTag
     * @param image [android.graphics.Bitmap] 或动图 [com.tencent.qgame.animplayer.anim.AnimImage]
     */
    fun updateDynamicImage(tag: String, image: Any?) {
        updateDynamicImage(tag, image, dynamicLoadEpoch.get())
    }

    /**
     * 带会话校验的注入:post 前后均校验代数号,过期会话(已被新 load 取代)的注入被丢弃,
     * 防止旧会话图片落到新会话同名 tag 的 src 上
     */
    internal fun updateDynamicImage(tag: String, image: Any?, epoch: Int) {
        val plugin = player.pluginManager.getMixAnimPlugin() ?: return
        val update = Runnable {
            if (dynamicLoadEpoch.get() != epoch) {
                ALog.i(TAG, "updateDynamicImage drop stale session tag=$tag epoch=$epoch")
                return@Runnable
            }
            plugin.updateSrcResource(tag, image)
        }
        // 配置解析与渲染都在渲染线程串行执行,注入同样切到渲染线程,避免位图/GL状态竞态
        val handler = player.decoder?.renderThread?.handler
        if (handler != null) {
            handler.post(update)
        } else {
            // 渲染线程尚未创建(未开播)或已销毁:srcMap 未建立或已清空,更新为no-op
            update.run()
        }
    }

    /** 递增并返回动态图加载会话代数号(每次 load(dynamic=...) 调用) */
    internal fun nextDynamicLoadEpoch(): Int = dynamicLoadEpoch.incrementAndGet()

    override fun setOnResourceClickListener(resourceClickListener: OnResourceClickListener?) {
        player.pluginManager.getMixAnimPlugin()?.resourceClickListener = resourceClickListener
    }

    /**
     * 兼容方案，优先保证表情显示
     */
    open fun enableAutoTxtColorFill(enable: Boolean) {
        player.pluginManager.getMixAnimPlugin()?.autoTxtColorFill = enable
    }

    /**
     * 文字跑马灯配置，文字超出src区域时不再缩小字号改为横向滚动
     * 播放前设置生效
     */
    open fun setTxtMarqueeConfig(config: TxtMarqueeConfig) {
        player.pluginManager.getMixAnimPlugin()?.txtMarqueeConfig = config
    }

    override fun setLoop(playLoop: Int) {
        player.playLoop = playLoop
    }

    override fun supportMask(isSupport: Boolean, isEdgeBlur: Boolean) {
        player.supportMaskBoolean = isSupport
        player.maskEdgeBlurBoolean = isEdgeBlur
    }

    override fun updateMaskConfig(maskConfig: MaskConfig?) {
        player.updateMaskConfig(maskConfig)
    }


    @Deprecated("Compatible older version mp4, default false")
    fun enableVersion1(enable: Boolean) {
        player.enableVersion1 = enable
    }

    // 兼容老版本视频模式
    @Deprecated("Compatible older version mp4")
    fun setVideoMode(mode: Int) {
        player.videoMode = mode
    }

    override fun setFps(fps: Int) {
        ALog.d(TAG, "setFps=$fps")
        player.defaultFps = fps
    }

    override fun setScaleType(type: ScaleType) {
        scaleTypeUtil.currentScaleType = type
    }

    override fun setScaleType(scaleType: IScaleType) {
        scaleTypeUtil.scaleTypeImpl = scaleType
    }

    /**
     * @param isMute true 静音
     */
    override fun setMute(isMute: Boolean) {
        ALog.i(TAG, "set mute=$isMute")
        player.isMute = isMute
    }

    /**
     * 设置音量,范围 [0.0, 1.0],超出范围会被截断。
     * - 播放过程中调用会立即生效(对当前 AudioTrack 生效);
     * - 播放前调用会在下一次 startPlay 创建 AudioPlayer 时自动应用。
     * 线程安全:可在任意线程调用。
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        ALog.i(TAG, "setVolume=$clamped")
        player.volume = clamped
    }

    override fun startPlay(file: File) {
        try {
            val fileContainer = FileContainer(file)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            animProxyListener.onFailed(
                Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR
            )
            animProxyListener.onVideoComplete()
        }
    }

    override fun startPlay(assetManager: AssetManager, assetsPath: String) {
        try {
            val fileContainer = CustomAssetsFileContainer(assetManager, assetsPath)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            animProxyListener.onFailed(
                Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR
            )
            animProxyListener.onVideoComplete()
        }
    }


    override fun startPlay(fileContainer: IFileContainer) {
        startPlayInternal(fileContainer, true)
    }

    private fun startPlayInternal(fileContainer: IFileContainer, copyContainer: Boolean) {
        if (lastFile != fileContainer) {
            lastFile?.close() //关闭上一次播放的文件流
        }
        lastFile = if (copyContainer && fileContainer is CustomAssetsFileContainer) {
            fileContainer.copy() //资源文件对象结束播放后不能再次播放bug
        } else {
            fileContainer
        }
        ui {
            if (visibility != VISIBLE) {
                ALog.e(TAG, "AnimView is GONE, can't play")
                return@ui
            }
            if (!player.isRunning()) {
                post { player.startPlay(fileContainer) }
                ALog.d(
                    TAG, "startPlay called $fileContainer ,player = ${player.isSurfaceAvailable}"
                )
            } else {
                ALog.d(TAG, "is running can not start")
            }
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放。
     * 所有状态变更在主线程串行化,并通过代际号作废过期的停止-重启链路,
     * 避免快速连续切换时旧请求的 afterStopRunnable 抢占/拆掉新请求。
     */
    fun startPlayForce(fileContainer: IFileContainer, onStartRenderOnce: () -> Unit = {}) {
        ui {
            playGeneration++
            val generation = playGeneration
            val target = if (fileContainer is CustomAssetsFileContainer) {
                fileContainer.copy() //资源文件对象结束播放后不能再次播放bug
            } else {
                fileContainer
            }
            if (lastFile != target) {
                lastFile?.close() //关闭上一次播放的文件流
            }
            lastFile = target
            if (player.isRunning()) {
                // 启动阶段（decoder 还没起来）的早退路径：此时 stopPlay() 设的 isStopReq
                // 会被即将到来的 decoder.start() 抹掉，afterStopRunnable 永远不会被触发，
                // 导致上一轮 fileContainer 仍然占着 lastFile、新一轮被静默丢弃。
                if (player.isInStartupPhase) {
                    ALog.d(TAG, "startPlayForce cancel pending startup and start fresh")
                    // tryCancelStartup 触发的 onVideoComplete 在 playLoop<=0 时可能走
                    // destroy() 关掉 lastFile,先把新容器摘出来防止被误关。
                    lastFile = null
                    if (player.tryCancelStartup()) {
                        lastFile = target
                        onStartRenderCallback = onStartRenderOnce
                        startPlayInternal(target, false)
                        return@ui
                    }
                    // 会话恰好越过启动阶段(decoder 刚启动),落入下方正常停止链路
                    lastFile = target
                }
                ALog.d(TAG, "startPlayForce called first stopPlay ${this.hashCode()}")
                afterStopRunnable = Runnable {
                    // 已有更新的请求抢先执行,丢弃本次过期重启
                    if (generation != playGeneration) {
                        ALog.d(
                            TAG,
                            "afterStopRunnable stale generation=$generation current=$playGeneration, ignore"
                        )
                        return@Runnable
                    }
                    onStartRenderCallback = onStartRenderOnce
                    if (isAttachedToWindow) {
                        ALog.d(TAG, "afterStopRunnable running startPlay")
                        startPlayInternal(target, false)
                    } else {
                        ALog.d(TAG, "afterStopRunnable isAttachedToWindow = false")
                    }
                }
                player.stopPlay()
            } else {
                // 空闲:清掉可能残留的过期 afterStopRunnable,直接启动
                afterStopRunnable = null
                ALog.d(TAG, "startPlayForce called ${this.hashCode()}")
                onStartRenderCallback = onStartRenderOnce
                startPlayInternal(target, false)
            }
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放
     * @param assetsPath 资源路径
     * @param onStartRenderOnce 首帧回调，动画真实开始回调，不是初始化完成回调
     */
    fun startPlayForce(
        assetsPath: String, onStartRenderOnce: () -> Unit = {}
    ) {
        launch(Dispatchers.IO) {
            val fileContainer =
                runCatching { CustomAssetsFileContainer(context.assets, assetsPath) }.getOrNull()
            if (fileContainer != null) {
                startPlayForce(fileContainer, onStartRenderOnce)
            }
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放
     * @param file 动画文件
     * @param onStartRenderOnce 首帧回调，动画真实开始回调，不是初始化完成回调
     */
    fun startPlayForce(
        file: File, onStartRenderOnce: () -> Unit = {}
    ) {
        launch(Dispatchers.IO) {
            val fileContainer = runCatching { FileContainer(file) }.getOrNull()
            if (fileContainer != null) {
                startPlayForce(fileContainer, onStartRenderOnce)
            }
        }
    }


    override fun stopPlay() {
        ALog.d(TAG, "stopPlay called")
        afterStopRunnable = null
        player.stopPlay()
    }

    override fun isRunning(): Boolean {
        return player.isRunning()
    }

    override fun getRealSize(): Pair<Int, Int> {
        return scaleTypeUtil.getRealSize()
    }

    private fun destroy() {
        player.onSurfaceTextureDestroyed()
        // 停止-重启交接中 lastFile 已指向新会话的容器,旧会话完成回调不得关闭它,
        // 否则 afterStopRunnable 拉起的新会话 parseConfig 读取失败(0x5 parse config fail)。
        if (afterStopRunnable == null) {
            lastFile?.close()
            lastFile = null
        }
        clearView()
    }

    /**
     * 释放资源
     * 注意:不要取消整个 CoroutineScope(MainScope),否则 view 被复用时
     * load(url) 中的 launch(Dispatchers.IO) 将永远不会执行,导致
     * "读取缓存文件"之后的下载/播放链路全部失效。
     */
    fun release() {
        player.isDetachedFromWindow = true
        // 作废尚未执行的停止-重启链路,防止释放后旧 runnable 又把播放拉起来
        playGeneration++
        afterStopRunnable = null
        destroy()
        uiHandler.removeCallbacksAndMessages(null)
        setFetchResource(null)
        loadJob?.cancel("release")
        loadJob = null
        dynamicDownloadJob?.cancel("release")
        dynamicDownloadJob = null
    }

    private fun ui(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else uiHandler.post { f() }
    }

    fun onPause() {
        //停止播放
        stopPlay()
    }

    fun onResume() {
        // 恢复播放
        if (player.playLoop > 0) {
            lastFile?.apply {
                startPlayForce(this)
            }
        }
    }
}