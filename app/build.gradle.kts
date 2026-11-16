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
        versionCode = 84
        versionName = "9.4"
        // ใส่เฉพาะไลบรารีของ ARM 64 บิต (S23 Ultra / Xiaomi 14T และมือถือรุ่นใหม่เกือบทั้งหมด) แอปจะเล็กลงมาก
        ndk { abiFilters += "arm64-v8a" }
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
        debug { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
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
    // อ่านชื่อภาษาไทย (Tesseract) — ไฟล์ภาษา tha/eng ดาวน์โหลดตอน build (ดู .github/workflows/build.yml)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
