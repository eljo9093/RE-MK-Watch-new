plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.remk.coaching.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.remk.coaching.wear"
        minSdk = 26
        targetSdk = 34
        versionCode = 264
        versionName = "26.4-pixel-watch-fixed"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.tiles:tiles:1.2.0")
    implementation("androidx.wear.protolayout:protolayout:1.0.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.0.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.0.0")
    implementation("com.google.guava:guava:31.1-android")
}
