plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nex.robotpet.v6"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nex.robotpet.v6"
        minSdk = 24
        targetSdk = 34
        versionCode = 60
        versionName = "6.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release builds cleanly with standard debug signing if no external release keystore is injected,
            // ensuring zero private signing keys are ever committed to GitHub while producing an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Secure HTTPS Networking (Gemini Backend + Open-Meteo Weather)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX (Front/Rear Camera Preview, Lifecycle & Frame Analysis)
    val cameraXVersion = "1.3.2"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // On-Device Face Detection & Tracking (Works Offline)
    implementation("com.google.mlkit:face-detection:16.1.6")

    // Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
