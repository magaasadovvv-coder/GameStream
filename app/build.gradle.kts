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
        // Номер версии растёт с каждой сборкой на GitHub
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }

    // Постоянный ключ подписи: новые версии ставятся поверх старой, без удаления
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Захват экрана, кодирование H.264/AAC и отправка по RTMP/SRT (open source, без водяных знаков)
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
