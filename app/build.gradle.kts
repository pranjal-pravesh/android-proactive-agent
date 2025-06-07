plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.proactiiveagentv1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.proactiiveagentv1"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ADDED: Block to configure the native C++ build
        externalNativeBuild {
            cmake {
                // Pass arguments to the C++ compiler. -std=c++17 is a good standard.
                cppFlags("-std=c++17")
            }
        }

        // ADDED: Block to specify which CPU architectures to build for.
        // 'arm64-v8a' is required for all modern 64-bit Android devices.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    // ADDED: This links your Gradle project to your C++ build script.
    externalNativeBuild {
        cmake {
            // Tells Gradle where to find the instructions for building your C++ code.
            // You will need to create this file.
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {

    // ONNX Runtime for Silero VAD
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    
    implementation(libs.dafruits.webrtc)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}