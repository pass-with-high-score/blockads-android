import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties

android {
    namespace = "app.pwhs.blockadstv"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "app.pwhs.blockadstv"
        minSdk = 24
        targetSdk = 36
        versionCode = 50
        versionName = "6.5.1"
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
                val ksPath = keyProperties["storeFile"] as String
                val ksFile = file(ksPath)
                storeFile = if (ksFile.exists()) ksFile else rootProject.file(ksPath)
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs = listOf("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
}

// Where the Go tunnel comes from. See the root build file and docs/TUNNEL.md.
//   unset      the published app.pwhs:tunnel release artifact, checksum-verified
//   local      built from tunnel/ by :buildGoTunnel
//   prebuilt   an aar already sitting at build/tunnel/tunnel.aar, used as-is
val tunnelSource = providers.gradleProperty("tunnel.source").orNull
val tunnelFromFile = tunnelSource == "local" || tunnelSource == "prebuilt"

when (tunnelSource) {
    "local" -> tasks.named("preBuild") { dependsOn(":buildGoTunnel") }
    // "prebuilt" means the caller already produced the aar, so there is nothing
    // to run first; building it here would just repeat their work.
    "prebuilt" -> Unit
    else -> tasks.named("preBuild") { dependsOn(":verifyTunnelAar") }
}

dependencies {
    // Go tunnel engine
    if (tunnelFromFile) {
        implementation(files(rootProject.layout.buildDirectory.file("tunnel/tunnel.aar")))
    } else {
        implementation(libs.tunnel) {
            // No Ivy/Maven metadata on a release asset, so name the artifact
            // explicitly; otherwise Gradle looks for tunnel-<version>.jar.
            artifact {
                name = "tunnel"
                type = "aar"
                extension = "aar"
            }
        }
    }

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    // TV Material
    implementation(libs.androidx.tv.material)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Koin DI
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Logging
    implementation(libs.timber)

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
