plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lingshu.feature.offlinetts"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation(project(":core-common"))
    implementation(project(":core-ui"))

    // ★ Sherpa-ONNX（本地 TTS 引擎，使用 XDcobra Maven 仓库发布版）
    // sherpa-onnx AAR 下载阻塞，暂时注释，运行时走 ChatTTS/EdgeTTS fallback
    // compileOnly("com.xdcobra.sherpa:sherpa-onnx:1.12.24")
    implementation("com.xdcobra.sherpa:onnxruntime:1.24.2-qnn2.43.1.260218@aar")

    // JSON 解析（tokenizer.json）
    implementation("org.json:json:20240303")

    // OkHttp（EdgeTtsEngine WebSocket）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
