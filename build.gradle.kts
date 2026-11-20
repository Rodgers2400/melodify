// Top-level build file
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    //  新增：Compose Compiler 插件（Kotlin 2.0.21 必需，修复上一轮报错）
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
