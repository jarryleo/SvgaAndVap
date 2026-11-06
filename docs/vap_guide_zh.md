# VAP 动画使用指南（中文）

VAP（Video Animation Player）是腾讯开源的动画方案：动画导出为**带透明通道的 MP4**（视频左右/上下分屏存储 alpha），由硬件解码 + OpenGL 渲染。相比序列帧/矢量动画，它能把复杂的粒子特效压到很小的体积，适合直播礼物、全屏特效等高复杂度场景。

本库在原版 [VAP](https://github.com/Tencent/vap) 基础上优化：随意切换动画无需手动停止、新增 url/assets 数据源与磁盘缓存、动态元素自动加载（url/file/bitmap/gif 等）、协程化下载与状态流。

> [English version](vap_guide_en.md)

## 目录

- [添加依赖](#添加依赖)
- [动画资源格式](#动画资源格式)
- [View 版：AnimView](#view-版animview)
  - [XML 声明](#xml-声明-1)
  - [加载动画 load()](#加载动画-load-1)
  - [动态元素（图片/文字替换）](#动态元素图片文字替换)
  - [播放监听](#播放监听)
  - [播放控制与配置](#播放控制与配置)
  - [错误码](#错误码)
- [Compose 版：VapPlayer](#compose-版-vapplayer)
  - [基础用法](#compose-基础用法-1)
  - [VapState 控制](#vapstate-控制)
- [最佳实践](#最佳实践-1)

## 添加依赖

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts —— 只用 VAP，只需这一行（lib_base 会自动传递引入）
implementation("com.github.jarryleo.SvgaAndVap:lib_vap:1.0.0")
```

版本号请替换为最新的 [Release tag](https://github.com/jarryleo/SvgaAndVap/tags)。

## 动画资源格式

VAP 动画是 MP4 文件，由设计工具（AE + [导出插件](https://github.com/Tencent/vap#工具)）生成，动画中可埋点：

- **图片占位**：运行时填充头像、图片（`setImage`）
- **文字占位**：运行时填充昵称、等级等（`setText`）

新版格式（vapx）还支持融合动画、遮罩等能力，本库全部兼容。

## View 版：AnimView

### XML 声明

```xml
<com.tencent.qgame.animplayer.AnimView
    android:id="@+id/animView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layout_gravity="center" />
```

`AnimView` 内部是 `TextureView` + OpenGL 渲染，无需额外配置，直接声明即可。

### 加载动画 load()

`load()` 是核心 API（扩展函数），自动识别数据源类型：

```kotlin
animView.load(
    data = url,        // 动画数据源：url / assets 名 / 文件路径 / File 对象
    loopCount = 1,     // 循环次数，小于 1 按播放 1 次
    dynamic = {        // 动态元素，见下文
        setImage("avatar1", "vap/head.png")
        setText("nick1", "Leo")
    }
)
```

完整签名：

```kotlin
animView.load(
    data: Any?,                        // url / 文件路径 / assets 名 / File；null 表示停止播放
    loopCount: Int? = null,            // 循环次数，< 1 按 1 次
    onStartRenderOnce: () -> Unit = {},// 首帧真实渲染回调（不是初始化完成）
    downloadState: (DownloadStatus) -> Unit = {}, // 网络源下载状态
    dynamic: DynamicResource.() -> Unit = {},     // 动态元素
    onRelease: (List<Resource>) -> Unit = {}      // 资源释放回调
)
```

要点：

- **url 源自动磁盘缓存**：首次下载后缓存为本地文件，再次播放同一 url 直接读缓存、不产生流量。
- **切换动画无需先 stop**：底层做了会话代际管理，连续快速切换会自动作废过期会话，不会出现新旧动画互相抢占。
- **`data = null`** 表示停止播放（主动停止不会触发错误回调）。
- View 生命周期已托管：detach / 不可见自动暂停，重新可见自动恢复（有限循环的动画）。
- `downloadState` 回调（仅 url 源触发）：

```kotlin
downloadState = { status ->
    when (status.state) {
        DownloadState.DOWNLOADING -> "下载中 ${status.progress}%"  // status.progress: 0~100
        DownloadState.COMPLETED   -> "完成"                        // status.file: 缓存文件
        DownloadState.FAILED      -> "失败: ${status.exception?.message}"
        else -> Unit // QUEUED / PAUSED / CANCELLED
    }
}
```

### 动态元素（图片/文字替换）

`dynamic` 块内用 tag（设计稿埋点命名）填充占位元素：

```kotlin
animView.load(
    data = "https://example.com/pk_start.mp4",
    loopCount = 1,
    dynamic = {
        // 图片元素：Bitmap / 资源 id / url / assets 名 / 文件路径，随便传
        setImage("avatar1", "vap/head.png")                  // assets 图片
        setImage("avatar1", "https://example.com/head.png")  // 网络图片（自动下载缓存）
        setImage("avatar2", R.drawable.avatar)               // 资源 id
        setImage("badge", File(cacheDir, "badge.gif"))       // gif 动图！

        // 文字元素
        setText("nick1", "测试长文本跑马灯效果")   // 超长自动跑马灯（需资源埋点支持）
        setText("ID1", "10086")
    }
)
```

- **gif 自动识别**：图片元素传入 gif 时自动逐帧解码并按视频时钟同步播放。
- 图片按占位区域尺寸自动解码缩放，网络图片带磁盘缓存。
- 混合模式（vapx）动画的融合元素同样通过 `dynamic` 填充。

### 播放监听

```kotlin
animView.setAnimListener(object : SimpleAnimListener() {
    override fun onVideoStart() { }                              // 开始播放
    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) { } // 每帧
    override fun onVideoComplete() { }                           // 播放完成
    override fun onVideoDestroy() { }                            // 播放器销毁
    override fun onFailed(errorType: Int, errorMsg: String?) { } // 失败，见错误码
})
```

需要拦截配置时实现完整 `IAnimListener`（`onVideoConfigReady(config)` 返回 `false` 可阻止播放）。

### 播放控制与配置

```kotlin
animView.stopPlay()                     // 停止播放
animView.isRunning()                    // 是否播放中
animView.setLoop(3)                     // 循环次数（也可在 load() 传）
animView.setMute(true)                  // 静音
animView.setVolume(0.5f)                // 音量 [0,1]，播放中调用即时生效
animView.setScaleType(ScaleType.FIT_CENTER) // 显示模式，见下表
animView.setFps(30)                     // 指定 fps（默认取视频帧率）
animView.getRealSize()                  // 视频真实尺寸 (width, height)
animView.release()                      // 彻底释放资源（View 不再复用时调用）
```

| ScaleType | 表现 |
| --- | --- |
| `FIT_XY` | 拉伸填满整个 View（View 版默认） |
| `FIT_CENTER` | 等比缩放，完整显示在中间（Compose 版默认） |
| `CENTER_CROP` | 等比缩放，填满 View，超出裁剪 |

其它能力：

```kotlin
animView.supportMask(true, true)        // 开启遮罩能力（vapx 资源），含边缘模糊
animView.updateMaskConfig(maskConfig)   // 更新遮罩配置
animView.setTxtMarqueeConfig(config)    // 文字跑马灯配置（文字超区滚动），播放前设置
animView.enableAutoTxtColorFill(true)   // 文字自动取色填充
animView.setOnResourceClickListener { tag, x, y -> } // 动态元素点击回调
animView.enableVersion1(true)           // 兼容旧版 mp4（deprecated，仅老资源需要）
```

### 错误码

`onFailed(errorType, errorMsg)` 的 errorType 定义在 `Constant` 中：

| 错误码 | 含义 |
| --- | --- |
| `10001` | MediaExtractor 异常（文件/流读取失败） |
| `10002` | MediaCodec 异常（解码失败） |
| `10003` | 线程创建失败 |
| `10004` | 渲染创建失败 |
| `10005` | 配置解析失败（mp4 数据段异常） |
| `10006` | vapx 融合动画资源获取失败 |
| `10007` | 文件无法读取 |
| `10008` | 设备不支持 H.265（请让设计导出 H.264） |

## Compose 版：VapPlayer

依赖改为：

```kotlin
implementation("com.github.jarryleo.SvgaAndVap:lib_anim_compose:1.0.0")
// 会自动传递引入 lib_svga / lib_vap / lib_base，无需重复声明
```

### Compose 基础用法

```kotlin
import cn.leo.base.download.DownloadState
import cn.leo.lib_anim_compose.VapPlayer
import cn.leo.lib_anim_compose.rememberVapPlayerState
import com.tencent.qgame.animplayer.DynamicResource
import com.tencent.qgame.animplayer.util.ScaleType

@Composable
fun PkAnim(url: String) {
    VapPlayer(
        modifier = Modifier.fillMaxSize(),
        source = url,                        // url / assets / 路径 / File；变化自动重载；null 停止
        scaleType = ScaleType.FIT_CENTER,
        mute = false,
        enableVersion1 = false,              // 旧版 mp4 资源设为 true
        dynamic = {                          // 与 View 版 dynamic 一致
            setImage("avatar1", "vap/head.png")
            setText("nick1", "Leo")
        },
        onAnimStart = { },                   // 开始播放
        onFrameStart = { },                  // 首帧真实渲染
        onAnimEnd = { },                     // 播放完成
        onError = { errorType, errorMsg -> },// 播放失败
        onDownloadState = { status ->        // url 源下载进度
            when (status.state) {
                DownloadState.DOWNLOADING -> { /* status.progress */ }
                else -> Unit
            }
        },
    )
}
```

### VapState 控制

```kotlin
val state = rememberVapPlayerState()  // 默认 VapConfig(loopCount = 1)

// config 是 Compose 状态：修改后自动按新配置重新加载当前资源
state.config = VapConfig(loopCount = 3)

state.load(source, dynamic = { ... })  // 手动换源（File / url / assets 均可）
state.startAnimation()                 // 从头重播最近一次加载的资源（沿用动态内容）
state.stop()                           // 停止播放
state.setVolume(0.5f)                  // 音量
state.isRunning                        // 查询播放状态（非响应式，刷新 UI 请配合回调）
```

完整示例（资源切换 + 下载进度 + 动态元素 + 播放控制）可参考 demo：[VapTestActivity.kt](../app/src/main/java/cn/leo/svgaandvap/VapTestActivity.kt)。

## 最佳实践

1. **礼物/全屏特效**：`loopCount = 1` 播完自动销毁；`onAnimEnd` 里推进业务（如移除 View）。
2. **url 提前预下载**：礼物动画对首帧时间敏感，可在进房间时用任意下载器预热磁盘缓存，`load()` 同一 url 会直接命中。
3. **头像等动态元素用 url**：`setImage` 的网络图带磁盘缓存，不必预先下载转 Bitmap。
4. **性能**：VAP 走硬件解码 + GL 渲染，CPU 占用低；但同屏多个 VAP 会多个解码器实例，全屏大动画建议同屏只保留 1~2 个。
5. **H.265 兼容**：部分低端设备不支持 H.265（错误码 10008），资源统一导出 H.264 最稳妥。
6. **与 SVGA 选型**：轻量 UI 动效（徽章、进度庆祝）选 SVGA；复杂粒子、视频质感特效选 VAP；两个库可以共存，按需引入。

---

← 返回 [README](../README.md) · 查看姊妹篇 [SVGA 使用指南](svga_guide_zh.md)
