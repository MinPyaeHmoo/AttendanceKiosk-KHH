plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.attendancekiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.attendancekiosk"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // Google ML Kit (QR Code)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Room Database
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    // Firebase BOM (Manages versions for all Firebase libraries automatically)
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))

    // Firebase Analytics (Recommended by Google when using Firebase)
    implementation("com.google.firebase:firebase-analytics")

    // Firestore (Database for text logs)
    implementation("com.google.firebase:firebase-firestore")

    // Cloud Storage (For saving the photos)
    implementation("com.google.firebase:firebase-storage")

    // WorkManager (For running the sync in the background)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase BOM (Manages versions)
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))

    // Firestore (Free text database)
    implementation("com.google.firebase:firebase-firestore")

    // WorkManager (Runs the sync safely in the background)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-analytics")
    // Google ML Kit (Face Detection)
    implementation("com.google.mlkit:face-detection:16.1.6")
}