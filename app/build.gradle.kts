plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.hisho"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hisho"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "0.31.0"

        buildConfigField(
            "String",
            "GOOGLE_ANDROID_CLIENT_ID",
            "\"29729119771-f9mkgf460shkb0glqopbkr0sf87l081q.apps.googleusercontent.com\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    testImplementation("junit:junit:4.13.2")
}
