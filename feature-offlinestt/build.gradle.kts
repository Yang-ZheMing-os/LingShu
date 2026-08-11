plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lingshu.feature.offlinestt"
    compileSdk = 34
    // ndkVersion = "26.1.10909125"  // 取消注释并完成以下步骤后启用：
    // 1. 安装 Android NDK (SDK Manager → SDK Tools)
    // 2. cd src/main/cpp/ && git clone https://github.com/ggerganov/whisper.cpp.git whisper
    // 3. 取消下方两个 externalNativeBuild 块的注释

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // NDK / CMake 配置 — 需要 whisper.cpp 源码时取消注释
        // externalNativeBuild {
        //     cmake {
        //         cppFlags("-std=c++17", "-O3")
        //         arguments("-DANDROID_STL=c++_static")
        //         abiFilters += listOf("arm64-v8a")
        //     }
        // }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // CMake 构建脚本路径 — 需要 whisper.cpp 源码时取消注释
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }
}

dependencies {
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation(project(":core-common"))
    implementation(project(":core-ui"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
