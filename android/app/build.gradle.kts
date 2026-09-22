plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scanx.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scanx.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OpenCV AAR đóng gói native lib cho mọi ABI (~110MB). Giới hạn còn 2 ABI phổ biến nhất
        // trên điện thoại thật (bỏ x86/x86_64 chỉ dùng cho emulator) để giảm đáng kể kích thước APK.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // TopAppBar (Material3) trong bản compose-bom đang dùng vẫn còn đánh dấu @ExperimentalMaterial3Api.
        // Opt-in ở mức compiler để không phải thêm @OptIn thủ công ở từng file, tránh lặp lỗi này về sau
        // khi dùng thêm các API thử nghiệm khác của Material3 (ví dụ ModalBottomSheet, ExposedDropdownMenuBox...).
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // OCR on-device, hỗ trợ tiếng Việt (Latin script)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("androidx.core:core-splashscreen:1.0.1")

    // CameraX: camera tự viết (preview + phân tích từng khung hình) thay cho UI camera có sẵn
    // của Google ML Kit Document Scanner — cần để tự kiểm soát tốc độ/độ chính xác tự động chụp.
    val cameraXVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // OpenCV (bản chính thức publish thẳng lên Maven Central từ 4.9.0, không cần app OpenCV Manager
    // riêng): dùng để phát hiện 4 góc tài liệu real-time trên từng khung hình camera (Canny edge +
    // findContours) và làm phẳng ảnh nghiêng (perspective transform) sau khi chụp — thuật toán cùng
    // họ với Scanner Pro/CamScanner/Adobe Scan dùng, thay vì phụ thuộc hộp đen của Google.
    implementation("org.opencv:opencv:4.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
