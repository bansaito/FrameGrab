pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // Tesseract4Android (อ่านตัวหนังสือภาษาไทย)
    }
}
rootProject.name = "FrameGrab"
include(":app")
