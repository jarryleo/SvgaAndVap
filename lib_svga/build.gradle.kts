plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.opensource.svgaplayer"
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(project(":lib_base"))
    implementation(libs.wire)
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
