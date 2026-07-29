plugins {
    kotlin("jvm")
}

// 刻意做成純 JVM module（不是 Android library）：
// 1. 演算法本來就與 Android 無關，這樣寫單元測試不需要模擬器，跑得飛快。
// 2. Phase 2/3 的 app module 只要 implementation(project(":core-engine")) 就能用。
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true          // 讓效能測試印出的數字看得到
    }
}
