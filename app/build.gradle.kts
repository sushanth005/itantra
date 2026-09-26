plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.itantra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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

    // Allow large ONNX model assets (up to ~100MB per model)
    androidResources {
        noCompress += listOf("onnx", "tflite")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // libonnxruntime.so + libonnxruntime4j_jni.so are provided directly in
        // src/main/jniLibs/arm64-v8a/ (Maven ORT 1.29.0, full public symbol table).
        // Sherpa-ONNX bundles its OWN libonnxruntime.so compiled with -fvisibility=hidden,
        // which does NOT export OrtGetApiBase → crash in libonnxruntime4j_jni.so.
        // We use pickFirsts here so Gradle takes the local jniLibs first and discards the AAR duplicates.
        jniLibs {
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
            )
        }
    }

    // Assets directory for ONNX models
    // BUG FIX: Removed proto { srcDir(...) } from here — it is redundant since
    // src/main/proto is the default location the protobuf Gradle plugin discovers.
    // In some AGP + protobuf-plugin version combos it caused:
    //   "Could not find method proto() for arguments [...] on SourceSet"
    // Also removed explicit assets.srcDirs("src/main/assets") to avoid duplicate
    // entry errors during compressDebugAssets.
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.appcompat)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    // sherpa-onnx-*.aar is placed in app/libs/ — download it by running:
    //   python scripts/download_sherpa_models.py --download-aar

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
