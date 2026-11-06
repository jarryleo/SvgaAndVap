# SVGA 动画使用指南（中文）

SVGA 是一种矢量动画格式，由设计工具（AE + Bodymovin 等）导出，体积小、可缩放不失真，广泛用于礼物动效、勋章、点赞彩蛋等场景。

本库在原版 [SVGAPlayer](https://github.com/yyued/SVGAPlayer-Android) 基础上做了大量优化：协程化加载、内存/磁盘缓存、共享动画时钟、动态图（gif）支持、列表场景内存治理等。

> [English version](svga_guide_en.md)

## 目录

- [添加依赖](#添加依赖)
- [View 版：SVGAImageView](#view-版svgaimageview)
  - [XML 声明](#xml-声明)
  - [加载动画 load()](#加载动画-load)
  - [加载配置 SVGAConfig](#加载配置-svgaconfig)
  - [播放控制](#播放控制)
  - [播放回调 SVGACallback](#播放回调-svgacallback)
  - [动态内容（图片/文字替换）](#动态内容图片文字替换)
  - [点击区域](#点击区域)
- [Compose 版：SvgaPlayer](#compose-版-svgaplayer)
  - [基础用法](#compose-基础用法)
  - [SvgaState 控制](#svgastate-控制)
- [进阶](#进阶)
- [最佳实践](#最佳实践)

## 添加依赖

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts —— 只用 SVGA，只需这一行（lib_base 会自动传递引入）
implementation("com.github.jarryleo.SvgaAndVap:lib_svga:1.0.0")
```

版本号请替换为最新的 [Release tag](https://github.com/jarryleo/SvgaAndVap/tags)。

## View 版：SVGAImageView

### XML 声明

```xml
<com.opensource.svgaplayer.SVGAImageView
    android:id="@+id/svgaView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:source="svga/rose.svga"
    app:loopCount="0"
    app:autoPlay="true"
    app:antiAlias="true"
    app:fillMode="0"
    app:clearsAfterDetached="true" />
```

| 属性 | 说明 | 默认值 |
| --- | --- | --- |
| `app:source` | 动画资源（url / assets 名 / 文件路径） | 无 |
| `app:loopCount` | 循环次数，`0` 或负数为无限循环 | `0` |
| `app:autoPlay` | 加载完成后自动播放 | `true` |
| `app:antiAlias` | 抗锯齿 | `true` |
| `app:fillMode` | 播放结束后的表现：`0` 停在最后一帧 / `1` 回到第一帧 / `2` 清空画布释放内存 | `0` |
| `app:clearsAfterDetached` | View 从窗口移除时自动清理动画资源 | `true` |

### 加载动画 load()

`load()` 会自动识别资源类型，三种来源统一一个入口：

```kotlin
// 网络地址（自动下载并磁盘缓存，下次直接读缓存）
svgaView.load("https://example.com/rose.svga")

// assets 路径
svgaView.load("svga/rose.svga")

// 本地文件绝对路径
svgaView.load("/sdcard/anim/rose.svga")
```

完整签名：

```kotlin
svgaView.load(
    source = "svga/rose.svga",          // 资源，null 或空串会停止动画并走 onError
    config = SVGAConfig(loopCount = 3), // 加载配置，见下文
    onError = { /* 加载失败回调 */ },
    keepPlaceholderOnReload = false,    // 重新加载期间是否保留静态占位图（列表场景防闪白）
    dynamicBlock = {                    // 动态内容，见下文
        setDynamicImage("avatar", "https://example.com/avatar.png")
    }
)
```

说明：

- View 没有宽高时（如 `wrap_content` 未测量），`load()` 会等待布局完成后自动加载，**在 `onCreate` 里直接调用即可**，不需要 post 延迟。
- 对**同一资源**重复调用 `load()`：不会重新解码，直接重播；若传入的 `dynamicBlock` 有变化，会原地更新动态内容。
- 加载/播放全部在协程中执行，View detach 时自动取消在途任务，不会泄漏。

### 加载配置 SVGAConfig

```kotlin
data class SVGAConfig(
    val frameWidth: Int = 0,        // 解码宽度，0 表示按 View 尺寸加载（默认，省内存）
    val frameHeight: Int = 0,       // 解码高度
    val isCacheToMemory: Boolean = false, // 是否缓存解码结果到内存，一次性页面建议 false
    val isOriginal: Boolean = false,      // 是否按原图尺寸加载（不缩放）
    val autoPlay: Boolean = true,         // 加载完成后自动播放
    val loopCount: Int = -1               // 循环次数，<= 0 无限循环
)
```

默认按 View 尺寸解码位图，显著降低内存；只有需要拿到与设计稿一致的原始尺寸时才用 `isOriginal = true`。

```kotlin
// 播 3 遍后停在最后一帧
svgaView.load(url, SVGAConfig(loopCount = 3, autoPlay = true))
```

### 播放控制

```kotlin
svgaView.startAnimation()                    // 从头播放
svgaView.startAnimation(SVGARange(5, 10), reverse = false) // 播放第 5~14 帧
svgaView.pauseAnimation()                    // 暂停（保持当前帧）
svgaView.resumeAnimation()                   // 恢复
svgaView.stopAnimation()                     // 停止，保留当前帧画面
svgaView.stopAnimation(clear = true)         // 停止并清空画布
svgaView.stepToFrame(10, andPlay = true)     // 跳到第 10 帧（0 起始），可顺带播放
svgaView.stepToPercentage(0.5, andPlay = false) // 跳到 50% 处
svgaView.setVolume(0.5f)                     // 音量 [0,1]，svga 内嵌音频时有效
svgaView.clear()                             // 清理动画资源（drawable、在途加载）
svgaView.isAnimating                         // 是否播放中
svgaView.loops = 3                           // 循环次数（<=0 无限循环），下次播放生效
svgaView.fillMode = SVGAImageView.FillMode.Clear  // 播完清屏释放内存
```

FillMode 枚举：

| 值 | 播放结束后 |
| --- | --- |
| `Backward` | 停在最后一帧（默认） |
| `Forward` | 回到第一帧 |
| `Clear` | 清空画布并释放内存 |

### 播放回调 SVGACallback

```kotlin
svgaView.callback = object : SVGACallback {
    override fun onStart() { }                            // 开始播放
    override fun onStep(frame: Int, percentage: Double) { } // 每帧回调（0 起始，百分比 0~1）
    override fun onRepeat() { }                           // 每循环一次
    override fun onPause() { }                            // 暂停
    override fun onResume() { }                           // 恢复
    override fun onCancel() { }                           // 被取消
    override fun onFinished() { }                         // 播放完成（循环次数耗尽）
}
```

### 动态内容（图片/文字替换）

SVGA 资源中的占位元素（设计稿里命名的图片/文本图层）可以在运行时替换，通过 `dynamicBlock` 配置：

```kotlin
svgaView.load("svga/gift.svga") {
    // 替换图片图层：支持 Bitmap / 资源 id / url / assets 名 / 文件路径
    setDynamicImage(R.drawable.avatar, "avatar")     // 图层 key 叫 "avatar"
    setDynamicImage("https://example.com/head.png", "avatar2")
        // ↑ 网络图自动下载，支持变换：
    setDynamicImage(
        "https://example.com/head.png", "avatar2",
        CircleCropTransFormation()   // 圆形裁剪（头像常用），也可自定义 BitmapTransformation
    )

    // 替换文本图层
    setDynamicText("Leo", textPaint, "nickName")
    setDynamicText("等级 30", textPaint, "level")

    // 隐藏某个图层
    setHidden(true, "banner")

    // 自定义绘制（在指定图层位置画任意内容，返回 true 表示已消费）
    setDynamicDrawer({ canvas, frameIndex ->
        // 用 canvas 画点什么都行
        true
    }, "slot")
}
```

> 图片图层 key、文本图层 key 由设计稿导出时命名，与设计师约定即可。
> 网络图片带三级缓存（内存 / 磁盘源文件 / 解码），gif 动图会自动按动画时钟逐帧播放。

### 点击区域

```kotlin
svgaView.load("svga/gift.svga") {
    // 声明哪些图层需要响应点击（框架会自动跟踪该图层的实时位置）
    setClickArea(listOf("btnJump", "btnClose"))
}

svgaView.setOnAnimKeyClickListener(object : SVGAClickAreaListener {
    override fun onClick(clickKey: String) {
        when (clickKey) {
            "btnJump" -> { /* 点击了跳转按钮 */ }
            "btnClose" -> { /* 点击了关闭按钮 */ }
        }
    }
})
```

## Compose 版：SvgaPlayer

依赖改为：

```kotlin
implementation("com.github.jarryleo.SvgaAndVap:lib_anim_compose:1.0.0")
// 会自动传递引入 lib_svga / lib_vap / lib_base，无需重复声明
```

### Compose 基础用法

```kotlin
import cn.leo.lib_anim_compose.SvgaPlayer
import cn.leo.lib_anim_compose.rememberSvgaPlayerState
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAImageView

@Composable
fun GiftAnimation(url: String) {
    val state = rememberSvgaPlayerState()

    SvgaPlayer(
        modifier = Modifier.fillMaxSize(),
        svgaUrl = url,                       // 变化时自动重新加载；null 表示清屏
        state = state,                       // 控制句柄，见下文
        fillMode = SVGAImageView.FillMode.Backward,
        dynamicBlock = {                     // 与 View 版 dynamicBlock 一致
            setDynamicImage("https://example.com/avatar.png", "avatar")
        },
        onAnimStart = { },                   // 开始播放
        onFrameStart = { },                  // 首帧渲染完成（适合隐藏占位图）
        onAnimEnd = { },                     // 播放完成
        onError = { },                       // 加载失败
    )
}
```

参数说明：

| 参数 | 说明 |
| --- | --- |
| `svgaUrl` | 动画资源（url / assets / 文件路径），变化自动重载，`null` 清屏 |
| `state` | `SvgaState`，命令式控制入口 |
| `fillMode` | 同 View 版 FillMode |
| `dynamicBlock` | 动态内容；仅在（重新）加载时执行，内容变化请调 `state.load()` |
| `onFrameStart` | 首帧渲染回调，仅在自动播放路径下触发 |

### SvgaState 控制

```kotlin
val state = rememberSvgaPlayerState(SVGAConfig(loopCount = 3))

// config 是 Compose 状态：修改后自动按新配置重新加载当前资源
state.config = SVGAConfig(loopCount = 0)  // 切成无限循环

state.load(newUrl, dynamicBlock = { ... })  // 手动换源（同资源只更新动态内容）
state.startAnimation()   // 从头重播
state.pauseAnimation()   // 暂停
state.resumeAnimation()  // 恢复
state.stop()             // 停止，保留当前帧
state.clear()            // 清理资源
state.stepToFrame(10)    // 跳帧，默认顺带播放
state.stepToPercentage(0.5)
state.setVolume(0.5f)
state.isAnimating        // 查询播放状态（非响应式，刷新 UI 请配合回调）
```

完整示例（选择资源 + 循环次数 + 播放控制）可参考 demo：[SvgaTestActivity.kt](../app/src/main/java/cn/leo/svgaandvap/SvgaTestActivity.kt)。

## 进阶

### 全局 URL 改写 UrlDecoder

图片服务通常需要在 url 上追加裁剪/缩放参数，注册全局 `UrlDecoder` 即可，对 svga 资源 url 与动态图片 url 同时生效：

```kotlin
UrlDecoderManager.setUrlDecoder(object : UrlDecoder {
    override fun decodeSvgaUrl(url: String, width: Int, height: Int) = url
    override fun decodeImageUrl(url: String, width: Int, height: Int): String {
        // 按目标尺寸让 CDN 服务端裁剪，节省流量与内存
        return "$url?imageMogr2/thumbnail/${width}x$height"
    }
})
```

### 低层解析 SVGAParser

需要自己管理 `SVGAVideoEntity`（如预解码、跨 View 共享）时可直接使用解析器：

```kotlin
SVGAParser.init(context) // 通常无需手动调用，SVGAImageView 会按需初始化
SVGAParser.shareParser()?.decodeFromURL(
    URL("https://example.com/rose.svga"),
    SVGAConfig(frameWidth = 300, frameHeight = 300)
) { entity -> }  // ParseCompletion：onComplete(videoItem) / onError()
```

### 内存诊断

```kotlin
svgaView.getSvgaMemorySize()        // 当前动画占用内存（字节，含动态图）
svgaView.getSvgaMemorySizeFormat()  // 格式化字符串，如 "12.5 MB"
```

库内会在单动画内存超过 10MB / 30MB 时输出黄色/红色告警日志，便于在开发期发现超大资源。

## 最佳实践

1. **一次性动效页面**（如活动页礼物）：保持 `isCacheToMemory = false`（默认），磁盘缓存已足够，避免内存缓存堆积。
2. **列表中播放**：本库已针对 RecyclerView 场景优化——detach 自动停帧清资源、attach 自动恢复、在途加载自动取消；同一 url 反复 bind 不会重复解码。`load(..., keepPlaceholderOnReload = true)` 可在滑动中保留静态占位图、停止滑动后再播动画。
3. **播完即释放**：一次性礼物动画建议 `fillMode = FillMode.Clear`，播放结束直接回收位图内存。
4. **无限循环动画注意**：`loopCount <= 0` 为无限循环，离开页面时依赖 detach 自动清理；若 View 常驻（如头像框），请自行在合适的时机调用 `stopAnimation()` / `clear()`。
5. **超大动画**：单动画内存超过 10MB 会打告警日志，优先让设计师减小尺寸，或用 `SVGAConfig(frameWidth, frameHeight)` 按实际 View 尺寸解码（默认行为已按 View 尺寸解码）。

---

← 返回 [README](../README.md) · 查看姊妹篇 [VAP 使用指南](vap_guide_zh.md)
