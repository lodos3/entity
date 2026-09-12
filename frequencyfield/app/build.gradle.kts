plugins {
    id("com.android.application")
}

android {
    namespace = "dev.dgdigital.frequencyfield"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dgdigital.frequencyfield"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

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
}
