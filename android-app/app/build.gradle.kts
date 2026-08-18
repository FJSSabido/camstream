plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miconstelacion.camstream"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miconstelacion.camstream"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Motor WebRTC (fork mantenido activamente, usado por LiveKit/Stream).
    // Si Android Studio no encuentra esta versión exacta, prueba a subirla:
    // https://mvnrepository.com/artifact/io.github.webrtc-sdk/android
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Cliente WebSocket sencillo para hablar con el servidor de señalización
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
