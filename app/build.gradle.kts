import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuesf.remotecontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuesf.remotecontrol"
        minSdk = 29
        targetSdk = 34
        versionCode = 64
        versionName = "1.0.0"

        // 编译期预置信令服务器地址与令牌，省去手工输入 48 位串。
        // 值从仓库根目录 preset.properties 读取——该文件已被 .gitignore 忽略，
        // 密钥类信息一律不进仓库。文件缺失时预置值为空串，App 在设置页手动填写。
        //
        // PRESET_VERSION 是这份预置配置的版本号。每次改动预置值都必须递增，
        // 否则已经装过旧版本的手机仍会沿用 SharedPreferences 里的旧地址与旧令牌
        // （预置值只在全新安装/版本升级时同步一次，用户手改的配置不再被覆盖）。
        val presetProps = Properties()
        val presetFile = rootProject.file("preset.properties")
        if (presetFile.exists()) {
            presetFile.inputStream().use { presetProps.load(it) }
        }
        val presetServerUrl = presetProps.getProperty("serverUrl", "").trim()
        val presetAuthToken = presetProps.getProperty("authToken", "").trim()
        buildConfigField("int", "PRESET_VERSION", "2")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$presetServerUrl\"")
        buildConfigField("String", "DEFAULT_AUTH_TOKEN", "\"$presetAuthToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        viewBinding = true
        buildConfig = true
    }

    // APK 统一命名 remote-control.apk（用户约定）：本地构建产物和 GitHub Release
    // 的 asset 都用这个名字，应用内更新检查按它找下载地址。
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "remote-control.apk"
        }
    }
}

// 让 preset.properties 成为 BuildConfig 生成任务的输入：改预置文件后
// 正常构建（不带 --rerun-tasks）也会重新生成 BuildConfig，不会 up-to-date 跳过。
val presetInputFile = rootProject.file("preset.properties")
if (presetInputFile.exists()) {
    tasks.matching { it.name.endsWith("BuildConfig") }.configureEach {
        inputs.file(presetInputFile)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
}
