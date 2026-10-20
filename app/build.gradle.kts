plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wisefido"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wisefido"
        minSdk = 28
        //noinspection EditedTargetSdkVersion
        targetSdk = 36
        versionCode = 5
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(project.properties["RELEASE_STORE_FILE"] as String)
            storePassword = project.properties["RELEASE_STORE_PASSWORD"] as String
            keyAlias = project.properties["RELEASE_KEY_ALIAS"] as String
            keyPassword = project.properties["RELEASE_KEY_PASSWORD"] as String
        }
    }
        buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // 依赖 A 厂和 B 厂模块
    implementation(project(":libs"))
    implementation(project(":module-radar"))
    implementation(project(":module-sleepace"))

    api(files("../libs/sdkcore.jar"))
    api(files("../libs/wificonfigsdk.jar"))

    // Android 基础库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.recyclerview)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    // Gson 依赖
    implementation("com.google.code.gson:gson:2.10.1")  // 添加 Gson 依赖

    // 二维码扫描
    implementation("com.google.mlkit:barcode-scanning:17.3.0")  // 内置模型，无需下载
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")


    // 测试依赖
    testImplementation(libs.junit)
    // 单元测试用真实 org.json（android.jar 里的是桩实现）
    testImplementation("org.json:json:20240303")
    // 需要 SharedPreferences 的单元测试
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

