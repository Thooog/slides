// 顶层构建文件：只声明插件版本，不在这里放任务
// AGP 9.0+ 已内置 Kotlin，无需再声明 org.jetbrains.kotlin.android
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
