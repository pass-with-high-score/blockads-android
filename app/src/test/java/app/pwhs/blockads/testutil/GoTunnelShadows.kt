package app.pwhs.blockads.testutil

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Skips `System.loadLibrary("gojni")` in the gomobile runtime so `tunnel.*` classes load on the JVM.
 * Use with `@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])`;
 * Robolectric then stubs every native method to return a default, and MockK can replace `Tunnel.newEngine()`.
 */
@Implements(className = "go.Seq", isInAndroidSdk = false)
class ShadowGoSeq {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() = Unit
    }
}
