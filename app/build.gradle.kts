plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.v2f.ankirounds"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v2f.ankirounds"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    // ใช้กุญแจเซ็นชุดเดิมทุกครั้ง เพื่อให้ติดตั้งเวอร์ชันใหม่ทับของเดิมได้
    signingConfigs {
        getByName("debug") {
            storeFile = file("rounds.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
