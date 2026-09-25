plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.slides.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slides.app"
        minSdk = 29          // 自用机 Xiaomi13(Android13+)；系统回收站 API 需 API30+，代码内做版本判断
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 自用机为 arm64-v8a，只打这个 ABI，省包体（沿用原 D9 决策）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 测试 runner（T007 真实 Room instrumented test）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // M1 阶段：release 测试包先用 debug 签名，保证可安装；
            // 正式 keystore 与签名流水线按计划 M2-04 再做。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Room schema 导出目录（T007：exportSchema=true，用于迁移可追踪与迁移测试）
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// AGP 9 内置 Kotlin 后，JVM target 用 compilerOptions DSL（不再是 kotlinOptions 块）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // 生命周期（ViewModel / lifecycle 组合）
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // 图片 / 视频缩略图加载（T004：按需、后台解码，不主线程全量解码）
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // 视频播放（Media3 ExoPlayer）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Room 持久化（T006：App 内收藏，本地只读媒体 + 用户数据分离）
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // 测试基础设施（T007：真实 Room 故障测试，非纯内存 Mock）
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}