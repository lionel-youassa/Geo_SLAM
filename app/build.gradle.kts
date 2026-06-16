import java.net.URI
import java.io.FileOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.geo_slam"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.geo_slam"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                // Utilisation de c++_static pour régler le problème d'alignement de libc++_shared.so
                // et forcer l'alignement 16 Ko pour libgeo_slam.so
                arguments("-DANDROID_STL=c++_static", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        viewBinding = true
        prefab = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Recommandé pour la compatibilité 16 Ko sur certains builds AGP
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tflite.main)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.api)
    implementation(libs.tflite.support)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
}

val tfliteAar by configurations.creating
dependencies {
    tfliteAar(libs.tflite.main)
}

val extractTfliteAar by tasks.creating {
    inputs.files(tfliteAar)
    val outputDir = file("$projectDir/src/main/cpp/tflite")
    val tempAarExtractDir = file("$buildDir/intermediates/tflite_aar_temp_extract")
    outputs.dir(outputDir)

    doLast {
        if (!outputDir.exists()) outputDir.mkdirs()
        
        if (tempAarExtractDir.exists()) tempAarExtractDir.deleteRecursively()
        tempAarExtractDir.mkdirs()
        tfliteAar.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            copy {
                from(zipTree(artifact.file))
                into(tempAarExtractDir)
            }
        }

        val jniLibsSource = file("$tempAarExtractDir/jni")
        if (jniLibsSource.exists()) {
            copy {
                from(jniLibsSource)
                into(outputDir)
                eachFile { path = path.replaceFirst("jni/", "") }
                includeEmptyDirs = false
            }
        }

        val manualHeadersSource = file("${project.rootDir}/tflite_headers_temp/tensorflow")
        if (manualHeadersSource.exists()) {
            println("--- Copie des headers TFLite depuis tflite_headers_temp ---")
            copy {
                from(manualHeadersSource)
                into(outputDir) 
                include("tensorflow/lite/**/*.h")
                include("tensorflow/lite/**/*.inc")
            }
        }

        val fbOutputDir = file("$outputDir/flatbuffers")
        if (fbOutputDir.exists()) fbOutputDir.deleteRecursively()
        fbOutputDir.mkdirs()

        println("--- Téléchargement des headers Flatbuffers v23.5.26 complet ---")
        val fbBaseUrl = "https://raw.githubusercontent.com/google/flatbuffers/v23.5.26/include/flatbuffers/"
        val headers = listOf(
            "flatbuffers.h", "base.h", "stl_emulation.h", "vector.h", 
            "string.h", "struct.h", "array.h", "table.h", 
            "default_allocator.h", "allocator.h", "base_generated.h",
            "buffer.h", "verifier.h", "util.h", "buffer_allocator.h",
            "detached_buffer.h", "flatbuffer_builder.h", "buffer_ref.h",
            "vector_downward.h", "registry.h", "vector_ref.h", "flexbuffers.h"
        )

        headers.forEach { headerName ->
            val targetFile = file("$fbOutputDir/$headerName")
            try {
                URI("$fbBaseUrl$headerName").toURL().openStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                println("Erreur téléchargement $headerName : ${e.message}")
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(extractTfliteAar)
    }
}
