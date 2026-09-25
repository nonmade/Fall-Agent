import java.util.Properties

// AGP 9 内置 Kotlin 支持，无需显式应用 org.jetbrains.kotlin.android
plugins {
    alias(libs.plugins.android.application)
}

// 与 App **同一套签名策略**（keystore/signing.properties 存在时用正式签名，否则回落 debug 签名）：
// App 会把本模块的 APK 打进 assets，并在运行期校验"归档签名 == App 自身签名"后才用它拉起守护进程。
// 两边签名不一致会让内置 APK 被拒（回落手工 push 路径），因此这里必须与 app/build.gradle.kts 对齐。
val signingPropsFile = rootProject.file("keystore/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

// 版本号同样取 gradle.properties 的唯一来源：守护进程是否"同构建"其实靠 APK 哈希判定（见 A2），
// 这里保持一致只是为了让 `dumpsys package com.fall.shell` / 日志里的版本号也对得上。
val fallVersionName: String = providers.gradleProperty("fall.versionName").get()
val fallVersionCode: Int = providers.gradleProperty("fall.versionCode").get().toInt()

android {
    namespace = "com.fall.shell"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fall.shell"
        minSdk = 26
        targetSdk = 37
        versionCode = fallVersionCode
        versionName = fallVersionName
    }

    signingConfigs {
        if (signingPropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = if (signingPropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // 本模块刻意依赖非 SDK 反射（ActivityThread 回填、VirtualDisplayConfig 镜像等 ROM 变通），
        // 且它不面向用户发布（只作为 app_process 的 dex 容器）→ 不因 BlockedPrivateApi 阻断构建。
        // 现在 release 变体会被打包进 App 的内置 assets（见 app/build.gradle.kts），
        // 若这里不放开，:app:assembleRelease 会连带失败。
        disable += "BlockedPrivateApi"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
}