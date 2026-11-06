# SVGA Animation Guide (English)

SVGA is a vector animation format exported by design tools (AE + Bodymovin, etc.). It is small, resolution-independent, and widely used for gift effects, badges, celebration eggs, and similar scenarios.

This library is a heavily optimized fork of [SVGAPlayer](https://github.com/yyued/SVGAPlayer-Android): coroutine-based loading, memory/disk caching, a shared animation ticker, animated image (gif) support, and memory management tuned for lists.

> [中文版](svga_guide_zh.md)

## Table of Contents

- [Dependency](#dependency)
- [View: SVGAImageView](#view-svgaimageview)
  - [XML declaration](#xml-declaration)
  - [Loading with load()](#loading-with-load)
  - [Load configuration: SVGAConfig](#load-configuration-svgaconfig)
  - [Playback control](#playback-control)
  - [Callbacks: SVGACallback](#callbacks-svgacallback)
  - [Dynamic content (image/text replacement)](#dynamic-content-imagetext-replacement)
  - [Click areas](#click-areas)
- [Compose: SvgaPlayer](#compose-svgaplayer)
  - [Basic usage](#basic-usage)
  - [Controlling with SvgaState](#controlling-with-svgastate)
- [Advanced](#advanced)
- [Best practices](#best-practices)

## Dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts — SVGA only; lib_base is pulled in transitively
implementation("com.github.jarryleo.SvgaAndVap:lib_svga:1.0.0")
```

Replace the version with the latest [Release tag](https://github.com/jarryleo/SvgaAndVap/tags).

## View: SVGAImageView

### XML declaration

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

| Attribute | Description | Default |
| --- | --- | --- |
| `app:source` | Animation resource (url / asset name / file path) | none |
| `app:loopCount` | Loop count; `0` or negative = infinite | `0` |
| `app:autoPlay` | Play automatically once loaded | `true` |
| `app:antiAlias` | Anti-aliasing | `true` |
| `app:fillMode` | Behavior after finishing: `0` stay on last frame / `1` back to first frame / `2` clear canvas & free memory | `0` |
| `app:clearsAfterDetached` | Clean up animation resources when the view is detached | `true` |

### Loading with load()

`load()` auto-detects the source type — one entry point for all three:

```kotlin
// Remote url (downloaded and cached on disk; subsequent loads read the cache)
svgaView.load("https://example.com/rose.svga")

// Asset name
svgaView.load("svga/rose.svga")

// Absolute file path
svgaView.load("/sdcard/anim/rose.svga")
```

Full signature:

```kotlin
svgaView.load(
    source = "svga/rose.svga",          // resource; null/empty stops the animation and triggers onError
    config = SVGAConfig(loopCount = 3), // load configuration, see below
    onError = { /* load failure callback */ },
    keepPlaceholderOnReload = false,    // keep a static placeholder during reload (avoids flicker in lists)
    dynamicBlock = {                    // dynamic content, see below
        setDynamicImage("avatar", "https://example.com/avatar.png")
    }
)
```

Notes:

- If the view has no size yet (e.g. unmeasured `wrap_content`), `load()` waits for layout and then starts automatically — **call it directly in `onCreate`**, no post/delay needed.
- Calling `load()` again with the **same source** replays without re-decoding; if `dynamicBlock` changed, dynamic content is updated in place.
- Loading/playing run in coroutines; in-flight work is cancelled automatically on detach — no leaks.

### Load configuration: SVGAConfig

```kotlin
data class SVGAConfig(
    val frameWidth: Int = 0,        // decode width; 0 = decode at view size (default, saves memory)
    val frameHeight: Int = 0,       // decode height
    val isCacheToMemory: Boolean = false, // cache decoded result in memory; keep false for one-shot pages
    val isOriginal: Boolean = false,      // decode at original size (no downscale)
    val autoPlay: Boolean = true,         // play automatically after loading
    val loopCount: Int = -1               // loop count; <= 0 = infinite
)
```

By default bitmaps are decoded at view size, which significantly reduces memory. Only use `isOriginal = true` when you need the exact design dimensions.

```kotlin
// Play 3 times, then stay on the last frame
svgaView.load(url, SVGAConfig(loopCount = 3, autoPlay = true))
```

### Playback control

```kotlin
svgaView.startAnimation()                    // play from the beginning
svgaView.startAnimation(SVGARange(5, 10), reverse = false) // play frames 5..14
svgaView.pauseAnimation()                    // pause (keeps current frame)
svgaView.resumeAnimation()                   // resume
svgaView.stopAnimation()                     // stop, keeps current frame
svgaView.stopAnimation(clear = true)         // stop and clear the canvas
svgaView.stepToFrame(10, andPlay = true)     // seek to frame 10 (0-based), optionally play
svgaView.stepToPercentage(0.5, andPlay = false) // seek to 50%
svgaView.setVolume(0.5f)                     // volume [0,1], for svga files with audio
svgaView.clear()                             // release animation resources (drawable, in-flight loads)
svgaView.isAnimating                         // is playing
svgaView.loops = 3                           // loop count (<=0 infinite), effective next playback
svgaView.fillMode = SVGAImageView.FillMode.Clear  // clear & free memory when finished
```

FillMode values:

| Value | After finishing |
| --- | --- |
| `Backward` | Stay on the last frame (default) |
| `Forward` | Back to the first frame |
| `Clear` | Clear the canvas and free memory |

### Callbacks: SVGACallback

```kotlin
svgaView.callback = object : SVGACallback {
    override fun onStart() { }                              // playback started
    override fun onStep(frame: Int, percentage: Double) { } // per frame (0-based; 0..1)
    override fun onRepeat() { }                             // each loop completed
    override fun onPause() { }                              // paused
    override fun onResume() { }                             // resumed
    override fun onCancel() { }                             // cancelled
    override fun onFinished() { }                           // finished (loop count exhausted)
}
```

### Dynamic content (image/text replacement)

Placeholder elements in the svga resource (image/text layers named by the designer) can be replaced at runtime via `dynamicBlock`:

```kotlin
svgaView.load("svga/gift.svga") {
    // Replace image layers: Bitmap / resource id / url / asset name / file path
    setDynamicImage(R.drawable.avatar, "avatar")     // layer key "avatar"
    setDynamicImage("https://example.com/head.png", "avatar2")
        // ↑ remote images are downloaded automatically and support transformations:
    setDynamicImage(
        "https://example.com/head.png", "avatar2",
        CircleCropTransFormation()   // circle crop (for avatars); implement BitmapTransformation for custom ones
    )

    // Replace text layers
    setDynamicText("Leo", textPaint, "nickName")
    setDynamicText("Level 30", textPaint, "level")

    // Hide a layer
    setHidden(true, "banner")

    // Custom drawing at a layer's position; return true to consume
    setDynamicDrawer({ canvas, frameIndex ->
        // draw anything you like
        true
    }, "slot")
}
```

> Image/text layer keys are named when the designer exports the resource — just agree on them with your designers.
> Remote images go through a 3-tier cache (memory / source file on disk / decoded); gif images are detected automatically and played frame by frame in sync with the animation clock.

### Click areas

```kotlin
svgaView.load("svga/gift.svga") {
    // Declare which layers should respond to clicks (their live position is tracked automatically)
    setClickArea(listOf("btnJump", "btnClose"))
}

svgaView.setOnAnimKeyClickListener(object : SVGAClickAreaListener {
    override fun onClick(clickKey: String) {
        when (clickKey) {
            "btnJump" -> { /* jump button clicked */ }
            "btnClose" -> { /* close button clicked */ }
        }
    }
})
```

## Compose: SvgaPlayer

Switch the dependency to:

```kotlin
implementation("com.github.jarryleo.SvgaAndVap:lib_anim_compose:1.0.0")
// transitively includes lib_svga / lib_vap / lib_base
```

### Basic usage

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
        svgaUrl = url,                       // auto reloads on change; null clears the screen
        state = state,                       // control handle, see below
        fillMode = SVGAImageView.FillMode.Backward,
        dynamicBlock = {                     // same as the View dynamicBlock
            setDynamicImage("https://example.com/avatar.png", "avatar")
        },
        onAnimStart = { },                   // playback started
        onFrameStart = { },                  // first frame rendered (good place to hide a placeholder)
        onAnimEnd = { },                     // finished
        onError = { },                       // load failure
    )
}
```

Parameters:

| Parameter | Description |
| --- | --- |
| `svgaUrl` | Resource (url / asset / file path); reloads on change; `null` clears |
| `state` | `SvgaState`, the imperative control entry |
| `fillMode` | Same as the View FillMode |
| `dynamicBlock` | Dynamic content; runs only on (re)load — to change content call `state.load()` |
| `onFrameStart` | First-frame callback; fires only on the auto-play path |

### Controlling with SvgaState

```kotlin
val state = rememberSvgaPlayerState(SVGAConfig(loopCount = 3))

// config is a Compose state: assigning it reloads the current resource with the new config
state.config = SVGAConfig(loopCount = 0)  // switch to infinite

state.load(newUrl, dynamicBlock = { ... })  // manual source switch (same source = dynamic update only)
state.startAnimation()   // replay from the beginning
state.pauseAnimation()   // pause
state.resumeAnimation()  // resume
state.stop()             // stop, keeps current frame
state.clear()            // release resources
state.stepToFrame(10)    // seek (plays by default)
state.stepToPercentage(0.5)
state.setVolume(0.5f)
state.isAnimating        // playback state query (not reactive; use callbacks to refresh UI)
```

A complete example (source picker + loop count + playback controls) lives in the demo: [SvgaTestActivity.kt](../app/src/main/java/cn/leo/svgaandvap/SvgaTestActivity.kt).

## Advanced

### Global URL rewriting with UrlDecoder

Image CDNs usually need extra query parameters for resizing/cropping. Register a global `UrlDecoder` — it applies to both svga resources and dynamic images:

```kotlin
UrlDecoderManager.setUrlDecoder(object : UrlDecoder {
    override fun decodeSvgaUrl(url: String, width: Int, height: Int) = url
    override fun decodeImageUrl(url: String, width: Int, height: Int): String {
        // let the CDN crop server-side to save bandwidth and memory
        return "$url?imageMogr2/thumbnail/${width}x$height"
    }
})
```

### Low-level parsing with SVGAParser

To manage `SVGAVideoEntity` yourself (e.g. pre-decoding, sharing across views), use the parser directly:

```kotlin
SVGAParser.init(context) // usually unnecessary; SVGAImageView initializes on demand
SVGAParser.shareParser()?.decodeFromURL(
    URL("https://example.com/rose.svga"),
    SVGAConfig(frameWidth = 300, frameHeight = 300)
) { entity -> }  // ParseCompletion: onComplete(videoItem) / onError()
```

### Memory diagnostics

```kotlin
svgaView.getSvgaMemorySize()        // memory used by the current animation (bytes, incl. dynamic images)
svgaView.getSvgaMemorySizeFormat()  // formatted, e.g. "12.5 MB"
```

The library logs a yellow/red warning when a single animation exceeds 10 MB / 30 MB, so oversized resources surface during development.

## Best practices

1. **One-shot pages** (campaign gifts, etc.): keep `isCacheToMemory = false` (default) — the disk cache is enough and avoids memory build-up.
2. **Playing in lists**: the library is tuned for RecyclerView — detach stops and frees, attach restores, in-flight loads are cancelled, and rebinding the same url never re-decodes. Use `load(..., keepPlaceholderOnReload = true)` to keep a static placeholder while scrolling and switch to the animation after.
3. **Free after playing**: for one-shot gifts prefer `fillMode = FillMode.Clear` so bitmaps are recycled right when the animation ends.
4. **Infinite animations**: `loopCount <= 0` loops forever; leaving the page relies on detach cleanup. If the view is long-lived (avatar frame, etc.), call `stopAnimation()` / `clear()` yourself at the right moment.
5. **Oversized animations**: a single animation above 10 MB triggers a warning log. Ask the designer to shrink it, or decode at view size via `SVGAConfig(frameWidth, frameHeight)` (that's already the default behavior).

---

← Back to [README](../README.md) · See also the [VAP Guide](vap_guide_en.md)
