pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 讓 Gradle 在找不到指定版本的 JDK 時自動下載（jvmToolchain(17) 需要）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TaiwanMahjongVision"

// Phase 1：純 Kotlin 的算牌引擎，不依賴 Android SDK，可以直接跑單元測試
include(":core-engine")

// Android APK。目前是「手動輸入版」：點選牌面輸入，不需要相機與模型。
// Phase 2 的相機辨識做好之後，只是把「手動點選」換成「自動填入」，介面不用改。
include(":app")
