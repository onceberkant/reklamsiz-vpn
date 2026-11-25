plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.onceberkant.reklamsizvpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.onceberkant.reklamsizvpn"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"

        // :openvpn modülündeki tek çeşit
        missingDimensionStrategy("implementation", "skeleton")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Herkese açık, ortak imza anahtarı: her derleme aynı anahtarla imzalanır,
        // böylece yeni sürümler eskisinin üzerine kurulabilir.
        // Play Store'a çıkmadan önce gizli bir anahtarla değiştirin.
        create("ortak") {
            storeFile = rootProject.file("keystore/imza.jks")
            storePassword = "reklamsizvpn"
            keyAlias = "reklamsiz"
            keyPassword = "reklamsizvpn"
        }
    }

    buildTypes {
        getByName("release") {
            // ics-openvpn yansıma (reflection) kullandığı için küçültme kapalı
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ortak")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ortak")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // OpenVPN 2 ikili dosyası (libovpnexec.so) diskten çalıştırıldığı için şart
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":openvpn"))

    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
