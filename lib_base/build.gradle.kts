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

    implementation(platform(libs.okhttpPlatformBom))
    implementation(libs.okhttp3)
    implementation(libs.okHttp3LoggingInterceptor)
    api(libs.coroutines)

}