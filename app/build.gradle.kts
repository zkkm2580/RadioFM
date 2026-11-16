plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.microshow.fm"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.nbhg.fm"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 经典车载 Media 兼容支持
    implementation("androidx.media:media:1.7.0")

    // Media3 / ExoPlayer：仅作为 MediaSession 的 Player 载体，不参与解码播放
    // （不再依赖 media3-exoplayer-hls / rtsp，所有流格式统一由 LibVLC 处理）
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")

    // VideoLAN LibVLC 官方底层核心引擎（唯一播放引擎：网络电台 / HLS / TS 全格式）
    // 3.7.1 对应内核 LibVLC 3.0.23 正式版（3.6.5 对应的是 3.0.22-rc1 候选版），
    // 与官方 VLC 3.7.1 客户端同款；TS/HLS demux 由内核 probe 负责，版本对齐可修复 RC 版缺陷。
    implementation("org.videolan.android:libvlc-all:3.7.1")
}
