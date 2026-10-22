plugins {
    // 插件版本只在根工程声明；子模块用无版本 id 应用（否则 Gradle 会报
    // "plugin is already on the classpath with an unknown version"）。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
