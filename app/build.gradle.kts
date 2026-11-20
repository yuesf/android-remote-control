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
        versionCode = 6
        versionName = "0.6.0"

        // 预置信令服务器地址与令牌，省去在两台手机上手工输入 48 位串。
        // 设备角色仍然不预置，由界面选择。
        //
        // PRESET_VERSION 是这份预置配置的版本号。每次改动上面两个值都必须递增，
        // 否则已经装过旧版本的手机仍会沿用 SharedPreferences 里的旧地址与旧令牌
        // （表现为“保存了却连不上/401”，因为预置值只在全新安装时生效）。
        // 递增后，升级首启会按新版本号同步一次，之后用户手改的配置不再被覆盖。
        buildConfigField("int", "PRESET_VERSION", "2")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://8.152.3.131:8787\"")
        buildConfigField(
            "String",
            "DEFAULT_AUTH_TOKEN",
            "\"cf2b74ded957c9a0ff5cf7534b9bc5920b983f090aa8e828\"",
        )
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
