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
        versionCode = 76
        versionName = "8.8"
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

dependencies {
    // อ่านชื่อบัญชีจากภาพ (OCR ตัวอังกฤษ) — โมเดลอยู่ในแอป ใช้ได้แม้ไม่มี Google Play services / ไม่มีเน็ต
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
