/*
 * OpenVPN for Android (ics-openvpn, Arne Schwabe) çekirdeğini kütüphane olarak derler.
 * Kaynaklar third_party/ics-openvpn alt modülünden okunur; orijinal "skeleton" (arayüzsüz)
 * yapılandırması ve OpenVPN 2.x motoru kullanılır. Lisans: GPLv2 (bkz. LICENSE).
 */
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider

plugins {
    id("com.android.library")
}

val icsMain: File = rootProject.file("third_party/ics-openvpn/main")

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // OpenVPN 2.x motoru: VPN Gate (SoftEther) sunucularıyla en uyumlu seçenek
        buildConfigField("boolean", "openvpn3", "false")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // ics-openvpn kodu BuildConfig.FLAVOR == "skeleton" kontrolü yapıyor
    flavorDimensions += listOf("implementation")
    productFlavors {
        create("skeleton") {
            dimension = "implementation"
        }
    }

    externalNativeBuild {
        cmake {
            path = File(icsMain, "src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            java.directories.add(File(icsMain, "src/main/java").absolutePath)
            // AppCustomControl.kt gibi Kotlin dosyaları da java klasöründe duruyor
            kotlin.directories.add(File(icsMain, "src/main/java").absolutePath)
            aidl.directories.add(File(icsMain, "src/main/aidl").absolutePath)
            res.directories.add(File(icsMain, "src/main/res").absolutePath)
            assets.directories.add(File(icsMain, "src/main/assets").absolutePath)
            // CMake, OpenVPN ikili dosyalarını buraya kopyalıyor
            assets.directories.add(File(icsMain, "build/ovpnassets").absolutePath)
        }
        maybeCreate("skeleton").apply {
            java.directories.add(File(icsMain, "src/skeleton/java").absolutePath)
            kotlin.directories.add(File(icsMain, "src/skeleton/java").absolutePath)
            res.directories.add(File(icsMain, "src/skeleton/res").absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// OpenVPN 3 için SWIG Java bağlayıcılarını üretir (ics-openvpn'deki görevle aynı).
abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(variantName: String, variantDirName: String): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/$variantDirName")

    return tasks.register<GenerateSwigTask>("generateOpenVPN3Swig$variantName") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)
        workingDir = icsMain

        doFirst {
            genDir.mkdirs()
        }
        commandLine(
            listOf(
                "swig",
                "-outdir", genDir.absolutePath,
                "-outcurrentdir",
                "-c++",
                "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client",
                "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "$genDir/ovpncli_wrap.cxx",
                "-oh", "$genDir/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files(File(icsMain, "src/main/cpp/openvpn3/client/ovpncli.i"))
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core:1.19.0")
}
