plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.qgame.animplayer"
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
    // 公共 API（VapManager、AnimView.load 回调等）暴露了 base 的类型，需以 api 传递
    api(project(":lib_base"))
}
