plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.v2f.framegrab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v2f.framegrab"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
    }

    // ใช้กุญแจเซ็นชุดเดิมทุกครั้ง เพื่อให้ติดตั้งเวอร์ชันใหม่ทับของเดิมได้
    signingConfigs {
        getByName("debug") {
            storeFile = file("framegrab.keystore")
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
