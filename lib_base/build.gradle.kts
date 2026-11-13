plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "cn.leo.base"
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

    implementation(libs.androidx.annotation)
    // okhttp 供 lib_svga / lib_vap 的下载器使用, 需以 api 传递
    api(platform(libs.okhttpPlatformBom))
    api(libs.okhttp3)
    implementation(libs.okHttp3LoggingInterceptor)
    api(libs.coroutines)

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