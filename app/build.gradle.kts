plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oss.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oss.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("ossDebug") {
            storeFile = file("oss-debug.keystore")
            storePassword = System.getenv("OSS_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("OSS_KEY_ALIAS") ?: "ossdebug"
            keyPassword = System.getenv("OSS_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            // Use the stable OSS key when supplied by GitHub Actions.
            // Local builds without the keystore keep the standard Android debug key.
            if (file("oss-debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("ossDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
