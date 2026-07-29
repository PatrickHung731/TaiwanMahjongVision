// 根專案：只宣告插件版本，實際套用在各 module 的 build.gradle.kts
//
// 版本組合是刻意挑「已經穩定很久」的搭配，不追最新——
// 因為這台開發機沒有 Android 工具鏈，編譯只能在 GitHub Actions 上跑，
// 版本踩雷的話除錯回合會很痛。
plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.android.application") version "8.7.3" apply false
}
