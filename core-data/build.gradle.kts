plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inkread.reader.core"
    compileSdk = 36
    // 只安装了 36.0.0（SDK 目录只读，无法自动安装 35.0.0）
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnit()
            // 本仓开发环境(.tooling)已预下载 android-all jar → 离线跑 Robolectric；
            // 普通 clone/CI 没有该目录 → 不设置离线，Robolectric 自行从 Maven 下载。
            val robolectricDeps = rootProject.file(".tooling/robolectric-deps")
            if (robolectricDeps.isDirectory) {
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricDeps.absolutePath)
            }
            it.maxHeapSize = "2g"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // 仓库/同步接口在公开 API 中暴露 Flow / StateFlow
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
