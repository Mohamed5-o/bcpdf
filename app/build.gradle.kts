plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.pdfreader"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.pdfreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 108
        versionName = "10.6"
        multiDexEnabled = true
    }
    signingConfigs {
        create("rel") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "blackclover"
            keyAlias = "bc"
            keyPassword = "blackclover"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("rel") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("rel")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
