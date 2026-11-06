# VAP Animation Guide (English)

VAP (Video Animation Player) is an animation solution open-sourced by Tencent: animations are exported as **MP4 files with an alpha channel** (video split horizontally/vertically to store alpha) and rendered via hardware decoding + OpenGL. Compared to sprite sheets or vector animation, it compresses complex particle effects into very small files — ideal for live-stream gifts and full-screen effects.

This library is an optimized fork of [VAP](https://github.com/Tencent/vap): switch animations freely without manual stops, url/assets data sources with disk caching, automatic dynamic element loading (url/file/bitmap/gif, ...), and coroutine-based downloads with state flows.

> [中文版](vap_guide_zh.md)

## Table of Contents

- [Dependency](#dependency)
- [Animation resource format](#animation-resource-format)
- [View: AnimView](#view-animview)
  - [XML declaration](#xml-declaration)
  - [Loading with load()](#loading-with-load)
  - [Dynamic elements (image/text replacement)](#dynamic-elements-imagetext-replacement)
  - [Playback listeners](#playback-listeners)
  - [Playback control and configuration](#playback-control-and-configuration)
  - [Error codes](#error-codes)
- [Compose: VapPlayer](#compose-vapplayer)
  - [Basic usage](#basic-usage-1)
  - [Controlling with VapState](#controlling-with-vapstate)
- [Best practices](#best-practices-1)

## Dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts — VAP only; lib_base is pulled in transitively
implementation("com.github.jarryleo.SvgaAndVap:lib_vap:1.0.0")
```

Replace the version with the latest [Release tag](https://github.com/jarryleo/SvgaAndVap/tags).

## Animation resource format

A VAP animation is an MP4 file produced by design tools (AE + the [export plugin](https://github.com/Tencent/vap#tools)). Animations can contain embedded slots:

- **Image slots**: filled at runtime with avatars or images (`setImage`)
- **Text slots**: filled at runtime with nicknames, levels, etc. (`setText`)

The newer format (vapx) additionally supports fused animations and masks — all supported by this library.

## View: AnimView

### XML declaration

```xml
<com.tencent.qgame.animplayer.AnimView
    android:id="@+id/animView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layout_gravity="center" />
```

`AnimView` is a `TextureView` + OpenGL renderer internally — declare it as-is, nothing else to configure.

### Loading with load()

`load()` (an extension function) is the core API and auto-detects the source type:

```kotlin
animView.load(
    data = url,        // source: url / asset name / file path / File
    loopCount = 1,     // loop count; < 1 plays once
    dynamic = {        // dynamic elements, see below
        setImage("avatar1", "vap/head.png")
        setText("nick1", "Leo")
    }
)
```

Full signature:

```kotlin
animView.load(
    data: Any?,                        // url / file path / asset name / File; null stops playback
    loopCount: Int? = null,            // loop count, < 1 = once
    onStartRenderOnce: () -> Unit = {},// first frame actually rendered (not "initialized")
    downloadState: (DownloadStatus) -> Unit = {}, // download state for remote sources
    dynamic: DynamicResource.() -> Unit = {},     // dynamic elements
    onRelease: (List<Resource>) -> Unit = {}      // resource release callback
)
```

Highlights:

- **url sources are cached on disk automatically**: the first download stores the file locally; playing the same url again reads the cache with zero network traffic.
- **No need to stop before switching**: session generations invalidate stale sessions, so rapid switching never lets old and new animations fight each other.
- **`data = null`** stops playback (an intentional stop does not trigger the failure callback).
- Lifecycle is handled: detach / invisibility pauses automatically; becoming visible again resumes (for finite loops).
- `downloadState` callback (remote sources only):

```kotlin
downloadState = { status ->
    when (status.state) {
        DownloadState.DOWNLOADING -> "downloading ${status.progress}%"  // status.progress: 0..100
        DownloadState.COMPLETED   -> "done"                            // status.file: cached file
        DownloadState.FAILED      -> "failed: ${status.exception?.message}"
        else -> Unit // QUEUED / PAUSED / CANCELLED
    }
}
```

### Dynamic elements (image/text replacement)

Inside the `dynamic` block, fill slot elements by tag (named when the designer embeds them):

```kotlin
animView.load(
    data = "https://example.com/pk_start.mp4",
    loopCount = 1,
    dynamic = {
        // Image elements: Bitmap / resource id / url / asset name / file path — anything
        setImage("avatar1", "vap/head.png")                  // asset image
        setImage("avatar1", "https://example.com/head.png")  // remote image (auto-downloaded & cached)
        setImage("avatar2", R.drawable.avatar)               // resource id
        setImage("badge", File(cacheDir, "badge.gif"))       // animated gif!

        // Text elements
        setText("nick1", "a long nickname that marquees")  // auto marquee when overflowing
        setText("ID1", "10086")
    }
)
```

- **gif auto-detection**: gif images are decoded frame by frame and played in sync with the video clock.
- Images are decoded & scaled to the slot size; remote images are disk-cached.
- Fused elements of mix-mode (vapx) animations are filled through `dynamic` as well.

### Playback listeners

```kotlin
animView.setAnimListener(object : SimpleAnimListener() {
    override fun onVideoStart() { }                                         // started
    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) { }    // per frame
    override fun onVideoComplete() { }                                      // completed
    override fun onVideoDestroy() { }                                       // player destroyed
    override fun onFailed(errorType: Int, errorMsg: String?) { }            // failure, see error codes
})
```

Implement the full `IAnimListener` if you need to intercept the config (`onVideoConfigReady(config)` returning `false` aborts playback).

### Playback control and configuration

```kotlin
animView.stopPlay()                     // stop playback
animView.isRunning()                    // is playing
animView.setLoop(3)                     // loop count (or pass it in load())
animView.setMute(true)                  // mute
animView.setVolume(0.5f)                // volume [0,1], effective immediately during playback
animView.setScaleType(ScaleType.FIT_CENTER) // display mode, see table
animView.setFps(30)                     // target fps (defaults to the video frame rate)
animView.getRealSize()                  // real video size (width, height)
animView.release()                      // full release (when the view won't be reused)
```

| ScaleType | Behavior |
| --- | --- |
| `FIT_XY` | Stretch to fill the whole view (View default) |
| `FIT_CENTER` | Scale to fit, centered (Compose default) |
| `CENTER_CROP` | Scale to fill the view, cropping the excess |

Additional capabilities:

```kotlin
animView.supportMask(true, true)        // enable mask (vapx resources), with edge blur
animView.updateMaskConfig(maskConfig)   // update mask config
animView.setTxtMarqueeConfig(config)    // text marquee config (scrolling overflow text); set before playing
animView.enableAutoTxtColorFill(true)   // auto text color fill
animView.setOnResourceClickListener { tag, x, y -> } // dynamic element click callback
animView.enableVersion1(true)           // legacy mp4 compat (deprecated; only for old resources)
```

### Error codes

`errorType` in `onFailed(errorType, errorMsg)` is defined in `Constant`:

| Code | Meaning |
| --- | --- |
| `10001` | MediaExtractor exception (file/stream read failure) |
| `10002` | MediaCodec exception (decode failure) |
| `10003` | Thread creation failure |
| `10004` | Render creation failure |
| `10005` | Config parse failure (broken mp4 data section) |
| `10006` | vapx fused-animation resource fetch failure |
| `10007` | File unreadable |
| `10008` | Device does not support H.265 (export H.264 instead) |

## Compose: VapPlayer

Switch the dependency to:

```kotlin
implementation("com.github.jarryleo.SvgaAndVap:lib_anim_compose:1.0.0")
// transitively includes lib_svga / lib_vap / lib_base
```

### Basic usage

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
        source = url,                        // url / asset / path / File; auto reloads on change; null stops
        scaleType = ScaleType.FIT_CENTER,
        mute = false,
        enableVersion1 = false,              // true for legacy mp4 resources
        dynamic = {                          // same as the View dynamic block
            setImage("avatar1", "vap/head.png")
            setText("nick1", "Leo")
        },
        onAnimStart = { },                   // started
        onFrameStart = { },                  // first frame actually rendered
        onAnimEnd = { },                     // completed
        onError = { errorType, errorMsg -> },// playback failure
        onDownloadState = { status ->        // download progress for remote sources
            when (status.state) {
                DownloadState.DOWNLOADING -> { /* status.progress */ }
                else -> Unit
            }
        },
    )
}
```

### Controlling with VapState

```kotlin
val state = rememberVapPlayerState()  // default VapConfig(loopCount = 1)

// config is a Compose state: assigning it reloads the current resource with the new config
state.config = VapConfig(loopCount = 3)

state.load(source, dynamic = { ... })  // manual source switch (File / url / asset all work)
state.startAnimation()                 // replay the last loaded resource (keeps its dynamic content)
state.stop()                           // stop playback
state.setVolume(0.5f)                  // volume
state.isRunning                        // playback state query (not reactive; use callbacks to refresh UI)
```

A complete example (source switching + download progress + dynamic elements + playback controls) lives in the demo: [VapTestActivity.kt](../app/src/main/java/cn/leo/svgaandvap/VapTestActivity.kt).

## Best practices

1. **Gifts / full-screen effects**: use `loopCount = 1` — the player destroys itself after finishing; advance your flow in `onAnimEnd` (e.g. remove the view).
2. **Pre-download remote animations**: gift animations are sensitive to first-frame latency. Warm the disk cache (any downloader) when entering the room; `load()` with the same url hits the cache directly.
3. **Use urls for dynamic images like avatars**: `setImage` with a url is disk-cached — no need to pre-download and convert to Bitmap yourself.
4. **Performance**: VAP uses hardware decoding + GL rendering with low CPU cost; but multiple concurrent VAP views mean multiple decoder instances — keep 1–2 full-screen animations on screen at most.
5. **H.265 compatibility**: some low-end devices don't support H.265 (error 10008); exporting everything as H.264 is the safest.
6. **Choosing between SVGA and VAP**: lightweight UI effects (badges, progress celebrations) → SVGA; complex particle/video-quality effects → VAP. The two libraries coexist fine — depend on what you need.

---

← Back to [README](../README.md) · See also the [SVGA Guide](svga_guide_en.md)
