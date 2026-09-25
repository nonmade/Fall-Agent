import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名：keystore/signing.properties（不入库）存在时启用，否则 release 回落 debug 签名
val signingPropsFile = rootProject.file("keystore/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

// 内置守护进程 APK 的生成 assets 目录（见 android.sourceSets 与文件末尾的 copyShellApk* 任务）
val shellApkAssetRoot = "generated/shellApk"

// AGP 9 不允许往 SourceSet 塞 Provider，这里直接给生成目录的绝对路径（任务依赖另行声明）
fun shellApkAssetDir(buildType: String): String =
    layout.buildDirectory.dir("$shellApkAssetRoot/$buildType").get().asFile.absolutePath

// 版本号唯一来源 = gradle.properties（发版只改那一处；tag 名 = "v" + fallVersionName，release.yml 会校验）
val fallVersionName: String = providers.gradleProperty("fall.versionName").get()
val fallVersionCode: Int = providers.gradleProperty("fall.versionCode").get().toInt()

android {
    namespace = "com.fall.assistant"
    compileSdk {
        version = release(37)
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

    defaultConfig {
        applicationId = "com.fall.assistant"
        minSdk = 26
        targetSdk = 37
        versionCode = fallVersionCode
        versionName = fallVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // onnxruntime 随 4 个 ABI 打入会显著膨胀 APK；手机端只保留 arm
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    buildFeatures {
        compose = true
    }

    // 内置守护进程 APK（A2）见文件末尾的 copyShellApk* 任务。
    // 用 build/ 下的**生成目录**而不是 src/main/assets：二进制产物不能污染源码树/入库。
    sourceSets {
        getByName("debug").assets.directories.add(shellApkAssetDir("debug"))
        getByName("release").assets.directories.add(shellApkAssetDir("release"))
    }
}

// A2：把与 App **同构建类型**的 shell APK 复制进生成 assets（运行期由 ShellApkBundle 解压 +
// 校验签名后作为守护进程 CLASSPATH）。这样设备上不必手工 adb push，换设备/换版本也能自动自举。
// 依赖 :shell:assemble*，因此 shell 模块必须与 App 用同一签名（见 shell/build.gradle.kts）。
listOf("debug", "release").forEach { buildType ->
    val cap = buildType.replaceFirstChar { it.uppercase() }
    val copyShellApk = tasks.register<Copy>("copyShellApk$cap") {
        dependsOn(":shell:assemble$cap")
        from(rootProject.file("shell/build/outputs/apk/$buildType/shell-$buildType.apk"))
        into(layout.buildDirectory.dir("$shellApkAssetRoot/$buildType"))
        rename { "fall-shell.apk" }
    }
    tasks.matching { it.name == "pre${cap}Build" || it.name == "merge${cap}Assets" }
        .configureEach { dependsOn(copyShellApk) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":automation"))
    implementation(libs.gson)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}