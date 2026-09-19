# The Go tunnel (`tunnel.aar`)

`tunnel/` is a Go module built for Android with [gomobile](https://github.com/sagernet/gomobile). The result, `tunnel.aar`, is a ~19MB fat AAR carrying `libgojni.so` for all four ABIs.

It used to be committed to `app/libs/tunnel.aar` and `blockadstv/libs/tunnel.aar`. It no longer is. A new revision landed on nearly every change to `tunnel/`, and because an AAR is an already-compressed zip, git could not delta them — 76 revisions came to roughly 1.1GB, which every clone paid for in full. It is now published as a versioned GitHub Release asset and resolved as an ordinary Gradle dependency.

## How the build gets it

By default, `:app` and `:blockadstv` depend on `app.pwhs:tunnel`, resolved from an Ivy repository pointed at this repo's release downloads (declared in `settings.gradle.kts`). The version lives in `gradle/libs.versions.toml`, and the expected SHA-256 in `gradle/tunnel.aar.sha256`. The `:verifyTunnelAar` task checks the download against that pin and fails the build on a mismatch; it runs automatically before `preBuild`.

Nothing else is needed to build the app — no Go toolchain, no NDK.

## Forks and mirrors

By default the artifact resolves from this repository's releases, so a plain clone needs no configuration. A fork that publishes its own tunnel points at its own releases instead, in precedence order:

1. `-Ptunnel.repo=<base url>`, or `tunnel.repo` in `gradle.properties`
2. the `TUNNEL_REPO` environment variable
3. upstream, as the built-in default

`ci.yml` and `deploy.yml` set `TUNNEL_REPO` from a repository-level Actions variable of the same name, so a fork only has to add one variable under Settings → Secrets and variables → Actions:

```
TUNNEL_REPO = https://github.com/<owner>/<repo>/releases/download
```

A blank value counts as unset and falls through to the default, so an undefined variable is harmless rather than producing an empty URL. `update_tunnel.yml` always publishes to the repository it runs in, so a fork that sets the variable is self-consistent; the release it cuts is the one its builds resolve.

## Building the tunnel from source

Pass `-Ptunnel.source=local` to build `tunnel/` with gomobile into `build/tunnel/tunnel.aar` and use that instead of the published artifact:

```sh
./gradlew -Ptunnel.source=local assembleDebug
```

This needs Go 1.23.5 and Android NDK r26b. `scripts/build_tunnel.sh` does the gomobile invocation on its own if you want the AAR without running a Gradle build.

There is a third mode, `-Ptunnel.source=prebuilt`, for when you have already produced `build/tunnel/tunnel.aar` yourself and want Gradle to consume it as-is: it neither downloads nor rebuilds. This is what the F-Droid recipe uses. F-Droid runs gomobile itself, under its own `GOPATH`, and those shell exports do not reach the Gradle process — so `local` would invoke gomobile a second time and would not find it.

`tunnel.source` therefore has three values:

| value | source of the aar |
|---|---|
| unset | the published `app.pwhs:tunnel` release, checksum-verified |
| `local` | built from `tunnel/` by `:buildGoTunnel` |
| `prebuilt` | `build/tunnel/tunnel.aar`, exactly as the caller left it |

CI builds from source on every PR, so changes to `tunnel/` are always compiled and tested. F-Droid builds from source too, since it must build everything it ships. Release builds (`deploy.yml`) use the pinned, checksum-verified published artifact.

## Publishing a new tunnel

`.github/workflows/update_tunnel.yml` does this automatically when `tunnel/` changes on `main`. It builds the AAR reproducibly, publishes it as a release, and opens a PR bumping the version and checksum — two lines of text rather than 19MB of binary.

The version is `<release date>.<short git tree hash of tunnel/>`, for example `2026.09.19.e5f916c2`. The tree hash is the identity; the date is only there to read. The workflow looks for an existing release whose tag ends in the current tree hash rather than deriving a tag from today's date, which gives it three outcomes:

- no release for this source — build it, publish it, and bump the pin
- a release exists but the pin is stale — bump the pin to it, without rebuilding or touching the published assets
- a release exists and the pin matches — do nothing

So dispatching it twice in a day is harmless, a fresh fork publishes its first release, and a deleted release is recreated. It never cuts a second release for source that already has one, and never replaces the assets of a published release: that is what the checksum pin exists to detect.

This is why the checksum lives in `gradle/` rather than next to the Go code: `tunnel/` is the hashed tree and the workflow's trigger path, so a pin stored there would change the version it is pinning and re-trigger the workflow on its own merge.

Release tag `tunnel-<version>` holds two assets:

- `tunnel-<version>.aar` — the library
- `tunnel-<version>-sources.jar` — sources, for IDE navigation

To publish by hand, run the workflow via `workflow_dispatch`. Do not edit `gradle/libs.versions.toml` or `gradle/tunnel.aar.sha256` without a matching release: the checksum pin is what keeps a replaced release asset from silently entering a build.
