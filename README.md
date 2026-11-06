### Svga 和 Vap 动画库优化项目

[![](https://jitpack.io/v/jarryleo/SvgaAndVap.svg)](https://jitpack.io/#jarryleo/SvgaAndVap)

项目作用：优化和测试svga库和vap库。

## 依赖接入

本库通过 [JitPack](https://jitpack.io) 发布，共 3 个可自由组合的依赖（`lib_base` 为内部公共模块，会作为传递依赖自动引入，无需单独声明）：

| 依赖 | 内容 |
| --- | --- |
| `lib_svga` | SVGA 动画播放（View 体系） |
| `lib_vap` | VAP 动画播放（View 体系） |
| `lib_anim_compose` | Compose 封装版（传递依赖 `lib_svga` / `lib_vap`，自动包含全部能力） |

**1. 在 `settings.gradle.kts` 中添加 JitPack 仓库：**

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. 按需组合依赖**（版本号请替换为最新的 [Release tag](https://github.com/jarryleo/SvgaAndVap/tags)）：

```kotlin
// 只要 SVGA
implementation("com.github.jarryleo.SvgaAndVap:lib_svga:1.0.0")

// 只要 VAP
implementation("com.github.jarryleo.SvgaAndVap:lib_vap:1.0.0")

// SVGA + VAP 都要
implementation("com.github.jarryleo.SvgaAndVap:lib_svga:1.0.0")
implementation("com.github.jarryleo.SvgaAndVap:lib_vap:1.0.0")

// Compose 版（自动包含 lib_svga + lib_vap）
implementation("com.github.jarryleo.SvgaAndVap:lib_anim_compose:1.0.0")
```
