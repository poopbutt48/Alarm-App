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
        versionCode = 2
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("androidx.core:core:1.13.1")
}
