plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.opensource.svgaplayer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":lib_base"))
    // wire 以 api 传递: SVGAVideoEntity.movieItem 公开暴露 proto 生成类型(MovieEntity),
    // 声明为 implementation 会导致下游引用该字段时编译期缺 wire-runtime
    api(libs.wire)
}

// 发布配置：坐标统一取自 gradle.properties（JitPack 多模块约定 group = com.github.用户名.仓库名）
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = property("GROUP") as String
                artifactId = project.name
                version = property("VERSION_NAME") as String
                from(components["release"])
            }
        }
    }
}
