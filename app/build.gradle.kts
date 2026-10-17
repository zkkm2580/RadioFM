plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.microshow.fm"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.microshow.fm"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 官方 VLC Android 原生核心 (集成 FFmpeg 软解，支持 MPEG-TS、MP2、HLS、AAC 等全量流媒体格式)
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // 车载 MediaSession 与 MediaStyle 通知栏
    implementation("androidx.media:media:1.7.0")
}
