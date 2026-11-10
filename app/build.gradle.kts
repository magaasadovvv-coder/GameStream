plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamestream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamestream"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Захват экрана, кодирование H.264/AAC и отправка по RTMP/SRT (open source, без водяных знаков)
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
