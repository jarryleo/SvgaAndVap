plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.leo.base"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {

    // okhttp 供 lib_svga / lib_vap 的下载器使用, 需以 api 传递
    api(platform(libs.okhttpPlatformBom))
    api(libs.okhttp3)
    implementation(libs.okHttp3LoggingInterceptor)
    api(libs.coroutines)

}