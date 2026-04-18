plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.dm.videoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dm.videoeditor"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("com.google.android.material:material:1.7.0")
    implementation("com.github.bumptech.glide:glide:4.14.2")
    implementation("com.airbnb.android:lottie:4.2.2")
    implementation("com.arthenica:ffmpeg-kit-full:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
    implementation("com.google.android.gms:play-services-ads:21.0.0")
}

kotlin {
    jvmToolchain(8)
}