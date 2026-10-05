import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing reads ../keystore.properties (never committed). Without it,
// release builds fall back to the debug key so `assembleRelease` still works.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.christiantwu.longhand"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.christiantwu.longhand"
        minSdk = 31
        targetSdk = 37
        versionCode = 7
        versionName = "0.9.0"

        // Phones only: the sherpa-onnx AAR ships four ABIs (~200 MB); keep arm64.
        // `-Pemulator` adds x86_64 for testing in the Android emulator.
        ndk {
            abiFilters += "arm64-v8a"
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
        externalNativeBuild {
            cmake {
                // llama.cpp is unusably slow unoptimized, so debug builds get release native code too.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // The sherpa-onnx AAR also carries its C and C++ API libraries (~5 MB per ABI). Its Kotlin API
            // loads only libsherpa-onnx-jni.so, which needs nothing from them.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// SpeakerResolverParityTest and VoiceSplitParityTest read fixtures that tools/diarization_eval.py
// writes from local sample calls, and MigrationTest the exported Room schemas; declared as inputs so
// changes to them re-run the tests instead of reusing a cached result.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.fileTree("sample_recordings/.cache/fixtures") { include("*.txt") })
        .withPropertyName("speakerParityFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("schemas").withPropertyName("roomSchemas").withPathSensitivity(PathSensitivity.RELATIVE)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
