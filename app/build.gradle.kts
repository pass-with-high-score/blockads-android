import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry)
    alias(libs.plugins.kover)
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
        versionCode = 51
        versionName = "6.5.2"

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
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Debug build shares standard applicationId for unified testing and deployment
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        // MigrationTestHelper reads exported schemas as assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's SDK 36 runtime pokes FileDescriptor internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // Robolectric's SDK 36 runtime needs Java 21; the build itself stays on the CI JDK.
            it.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            // A non-UTC, half-hour zone so local-vs-UTC date bugs fail on UTC CI runners too.
            it.environment("TZ", "Asia/Kolkata")
            it.systemProperty("user.timezone", "Asia/Kolkata")
        }
    }

    lint {
        checkReleaseBuilds = true
        // Existing findings live in the baseline; anything new fails the build.
        // Regenerate with ./gradlew :app:updateLintBaseline after fixing baselined issues.
        baseline = file("lint-baseline.xml")
        error += setOf(
            "SetJavaScriptEnabled",
            "JavascriptInterface",
            "AddJavascriptInterface",
            "AllowBackup",
            "ExportedReceiver",
            "ExportedService",
            "SetWorldReadable",
            "SetWorldWritable",
            "WorldReadableFiles",
            "WorldWriteableFiles",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kover {
    currentProject {
        createVariant("unit") { add("debug") }
    }
    reports {
        filters {
            excludes {
                classes("*_Impl", "*_Impl\$*", "*.BuildConfig", "*ComposableSingletons*", "*.R", "*.R\$*")
                annotatedBy("androidx.compose.ui.tooling.preview.Preview", "androidx.compose.runtime.Composable")
            }
        }
    }
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