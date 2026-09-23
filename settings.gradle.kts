pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Where the prebuilt tunnel artifact is resolved from. Defaults to upstream's
// releases, so a plain clone needs no configuration. A fork, mirror or local
// test overrides it with either -Ptunnel.repo=<base url> (or tunnel.repo in
// gradle.properties) or the TUNNEL_REPO environment variable, which the
// workflows wire to the repository-level TUNNEL_REPO Actions variable. A blank
// value is treated as unset, so an undefined Actions variable falls through to
// the default rather than producing an empty URL.
val tunnelRepo: String =
    providers.gradleProperty("tunnel.repo").map { it.trim() }.filter { it.isNotEmpty() }
        .orElse(providers.environmentVariable("TUNNEL_REPO").map { it.trim() }.filter { it.isNotEmpty() })
        .getOrElse("https://github.com/pass-with-high-score/blockads-android/releases/download")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")

        // Prebuilt Go tunnel (app.pwhs:tunnel), published as a GitHub Release
        // asset by .github/workflows/update_tunnel.yml. Release assets have no
        // Maven/Ivy metadata, so the layout is declared explicitly and only the
        // artifact itself is fetched. The download is checksum-pinned in
        // gradle/tunnel.aar.sha256 and checked by the :verifyTunnelAar task.
        //
        // Building the tunnel from source instead: ./gradlew -Ptunnel.source=local
        // exclusiveContent: app.pwhs:tunnel is resolvable ONLY here, so the
        // coordinate cannot be shadowed by a squatter on Maven Central or JitPack.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "blockadsTunnelReleases"
                    url = uri(tunnelRepo)
                    patternLayout {
                        artifact("tunnel-[revision]/[artifact]-[revision](-[classifier]).[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("app.pwhs", "tunnel") }
        }
    }
}

rootProject.name = "blockads"
include(":app")
include(":blockadstv")
