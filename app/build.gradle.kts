plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.blockblast.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blockblast.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-debug"
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
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
