import java.security.MessageDigest

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The Go tunnel ships as a prebuilt AAR resolved from GitHub Releases (see
// settings.gradle.kts). -Ptunnel.source=local builds it from tunnel/ with
// gomobile instead, which is what CI does. -Ptunnel.source=prebuilt consumes an
// aar the caller already put at build/tunnel/tunnel.aar without rebuilding it,
// which is what the F-Droid recipe does: it runs gomobile itself, under its own
// GOPATH, and those exports do not reach this Gradle process.
//
// :app and :blockadstv pick their tunnel dependency the same way; keep the two
// in step with the branches below.

val tunnelAarOutput: Provider<RegularFile> = layout.buildDirectory.file("tunnel/tunnel.aar")

tasks.register<Exec>("buildGoTunnel") {
    description = "Builds tunnel.aar from tunnel/ with gomobile. Requires Go and the Android NDK."
    group = "build"

    val tunnelDir = layout.projectDirectory.dir("tunnel")
    val aar = tunnelAarOutput.get().asFile

    // Only rebuild when the tunnel source changes (or the aar is missing).
    inputs.dir(tunnelDir).withPropertyName("tunnelSource")
    outputs.file(tunnelAarOutput).withPropertyName("tunnelAar")

    workingDir = tunnelDir.asFile

    // gomobile is usually on PATH only via the user's profile, so go through bash
    // to pick up GOPATH/bin.
    // Paths go in as positional arguments rather than interpolated into the
    // script, so a checkout directory containing spaces still builds.
    commandLine(
        "bash", "-c",
        "mkdir -p \"\$1\" && " +
            "export GOFLAGS=\"-buildvcs=false\" && " +
            "export PATH=\"\$PATH:\$GOPATH/bin:\$HOME/go/bin:/usr/local/go/bin\" && " +
            "gomobile bind -target=android -androidapi 24 -trimpath " +
            "-ldflags=\"-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384\" " +
            "-o \"\$2\" github.com/nqmgaming/blockads-tunnel",
        "buildGoTunnel",
        aar.parentFile.absolutePath,
        aar.absolutePath
    )
}

// Resolved on its own so the checksum can be checked without running the rest of
// the Android build — `./gradlew verifyTunnelAar` is enough.
val tunnelAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    tunnelAar(libs.tunnel) {
        // No Ivy/Maven metadata on a release asset, so name the artifact
        // explicitly; otherwise Gradle looks for tunnel-<version>.jar.
        artifact {
            name = "tunnel"
            type = "aar"
            extension = "aar"
        }
    }
}

tasks.register("verifyTunnelAar") {
    description = "Verifies the downloaded tunnel.aar against the pinned checksum."
    group = "verification"

    val resolved = tunnelAar.incoming.artifacts.resolvedArtifacts.map { it.single().file }
    val pinFile = layout.projectDirectory.file("gradle/tunnel.aar.sha256")
    val version = libs.versions.tunnel.get()

    inputs.file(pinFile).withPropertyName("pinnedChecksum")
    inputs.files(tunnelAar).withPropertyName("tunnelAar")

    doLast {
        val expected = pinFile.asFile.readText().trim().substringBefore(' ')
        val aar = resolved.get()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(aar.readBytes())
            .joinToString("") { "%02x".format(it) }

        if (actual != expected) {
            throw GradleException(
                """
                tunnel.aar checksum mismatch for app.pwhs:tunnel:$version
                  expected $expected
                  actual   $actual
                  file     $aar

                Either the published release asset was replaced, or
                gradle/tunnel.aar.sha256 is stale. Do not ignore this.
                """.trimIndent()
            )
        }
        logger.lifecycle("tunnel.aar $version verified ($expected)")
    }
}
