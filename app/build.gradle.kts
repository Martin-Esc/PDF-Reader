plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.martin.pdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.martin.pdfreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Modern phones only; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A fixed key checked into the repo, so every build can be installed
        // over the previous one without uninstalling first.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += "onnx" }

    packaging {
        jniLibs {
            // Only the JNI library and onnxruntime are needed.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    // Speech engine. The .aar is downloaded by the build workflow.
    implementation(files("libs/sherpa-onnx.aar"))
    // PDF text extraction.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
