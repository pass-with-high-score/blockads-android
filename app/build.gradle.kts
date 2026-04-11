import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry)
}

tasks.register<Exec>("buildGoTunnel") {
    val libsDir = file("libs")
    val aarFile = file("libs/tunnel.aar")
    val tunnelDir = rootProject.file("tunnel")
    
    // Only rebuild if the tunnel source code changes (or if aar is missing)
    inputs.dir(tunnelDir)
    outputs.file(aarFile)

    workingDir = tunnelDir
    
    // For local development, gomobile might not be in PATH for Gradle, so we use bash
    // to load user's profile which usually exports GOPATH/bin to PATH.
    commandLine(
        "bash", "-c",
        "mkdir -p \"${libsDir.absolutePath}\" && " +
        "export GOFLAGS=\"-buildvcs=false\" && " +
        "export PATH=\"\$PATH:\$GOPATH/bin:\$HOME/go/bin:/usr/local/go/bin\" && " +
        "gomobile bind -target=android -androidapi 24 -trimpath " +
        "-ldflags=\"-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384\" " +
        "-o ${aarFile.absolutePath} github.com/nqmgaming/blockads-tunnel"
    )

    doFirst {
        if (!libsDir.exists()) {
            libsDir.mkdirs()
        }
        println("Building Go tunnel for Android...")
    }
    
    doLast {
        println("Go tunnel built successfully.")
    }
}

android {
    namespace = "app.pwhs.blockads"
    compileSdk {
        version = release(36)
    }
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.pwhs.blockads"
        minSdk = 24
        targetSdk = 36
        versionCode = 45
        versionName = "6.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Load signing config from key.properties (CI/CD)
    val keyPropertiesFile = rootProject.file("key.properties")
    val useReleaseKeystore = keyPropertiesFile.exists()

    if (useReleaseKeystore) {
        val keyProperties = Properties().apply {
            load(keyPropertiesFile.inputStream())
        }
        signingConfigs {
            create("release") {
                storeFile = file(keyProperties["storeFile"] as String)
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Disable stripping native libraries to ensure byte-for-byte reproducible builds
            // across different CI environments (F-Droid vs GitHub Actions).
            keepDebugSymbols.add("**/libdatastore_shared_counter.so")
            keepDebugSymbols.add("**/libgojni.so")
        }

        resources {
            excludes += "**/sentry-debug-meta.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs = listOf("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Ktor HTTP Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    
    // Go Tunnel backend
    implementation(files("libs/tunnel.aar"))

    implementation(libs.timber)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Koin DI
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Accompanist
    implementation(libs.accompanist.drawablepainter)

    // WorkManager for auto-update
    implementation(libs.androidx.work.runtime.ktx)

    // ComposeCharts
    implementation(libs.compose.charts)

    // libsu — root shell access for iptables mode
    implementation(libs.core)
    implementation(libs.service)

    // Shizuku — ADB root shell access (alternative to Magisk/KernelSU)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

sentry {
    // Disable Proguard mapping entirely — prevents sentry-debug-meta.properties
    // from being generated (contains random UUID that breaks reproducible builds)
    includeProguardMapping = false
    includeSourceContext = false
    autoUploadProguardMapping = false
    autoUploadSourceContext = false

    // Disable instrumentation that causes manifest/UUID changes
    tracingInstrumentation { enabled = false }
    autoInstallation { enabled = false }

    // Disable telemetry and dependency reporting that cause non-deterministic builds
    includeDependenciesReport = false
}