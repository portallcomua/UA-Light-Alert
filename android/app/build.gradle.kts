import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.pp.uaserver.lightalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.pp.uaserver.lightalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 481
        versionName = "4.8.1"

        buildConfigField("String", "SITE_BASE_URL", "\"https://uaserver.pp.ua\"")
        buildConfigField("String", "GITHUB_REPO", "\"portallcomua/UA-Light-Alert\"")
    }

    signingConfigs {
        val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
        val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val ksAlias = System.getenv("ANDROID_KEY_ALIAS")
        val ksKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !ksKeyPass.isNullOrBlank() && File(ksPath).exists()) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Keep identical applicationId so updates from GitHub Releases replace the installed app in-place.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
