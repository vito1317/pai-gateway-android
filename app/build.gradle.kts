plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vito.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.gateway"
        minSdk = 26
        targetSdk = 34
        // 版本號自動化：CI 用 GitHub run number 遞增（每次更新程式自動 +1）
        val ci = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ci ?: 1
        versionName = if (ci != null) "1.0.$ci" else "1.0-dev"
    }
    signingConfigs {
        // 固定簽章：用 repo 內的 release.keystore（CI 第一次自動產生並 commit）→
        // 每個 build 簽章一致，能直接覆蓋更新（不會再「與現有套件衝突」）。
        val ks = rootProject.file("release.keystore")
        if (ks.exists()) {
            create("fixed") {
                storeFile = ks
                storePassword = "paigateway"
                keyAlias = "pai"
                keyPassword = "paigateway"
            }
        }
    }
    buildTypes {
        debug {
            if (signingConfigs.findByName("fixed") != null) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("fixed") != null) signingConfig = signingConfigs.getByName("fixed")
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // cloudflared 以 .so 打包進 jniLibs，需保留為可執行（不壓縮、解壓到 nativeLibraryDir）
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")

    // 內嵌 HTTP server（MCP / HTTP）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // JSON
    implementation("org.json:json:20240303")
    // QR 掃描配對
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // 全雙工語音（Socket.IO 連 voice_server）
    implementation("io.socket:socket.io-client:2.1.0") { exclude(group = "org.json", module = "json") }
    // 鏡頭即時擷取（live vision 鏡頭模式）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    // 前向警戒：本地物體偵測（離線、含內建模型）
    implementation("com.google.mlkit:object-detection:17.0.2")
    // 健康守護：Health Connect 讀心率/睡眠/步數（Pixel Watch/Fitbit/Samsung Health 都寫進這）
    implementation("androidx.health.connect:connect-client:1.1.0")
}
