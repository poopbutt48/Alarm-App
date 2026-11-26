plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cartel.qralarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cartel.qralarm"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // On-device Gemini Nano (via Android AICore)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // On-device body pose tracking for counting reps
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
}
