plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本號直接取自 GitHub Actions，讓 App 自己講得出它是哪一次 build 出來的。
// 之前發生過「下載到舊的 APK 卻以為是新版」，光看畫面分不出來，很浪費時間。
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val commitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

android {
    namespace = "com.tmvision.mahjong"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tmvision.mahjong"
        minSdk = 26
        targetSdk = 35
        versionCode = if (buildNumber > 0) buildNumber else 1
        versionName = if (buildNumber > 0) "b$buildNumber · $commitSha" else "本機開發版"
    }

    buildTypes {
        debug {
            // 自用，直接裝 debug 版就好，不需要簽名與混淆
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true        // 要在畫面上顯示版本，需要產生 BuildConfig
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    // Phase 1 的算牌 + 放槍機率引擎
    implementation(project(":core-engine"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 相機。刻意不加任何辨識框架（TFLite / OpenCV）——
    // 手牌是一列等寬的牌，用「對準框 + 等分切片」就能定位，不需要物件偵測模型。
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
