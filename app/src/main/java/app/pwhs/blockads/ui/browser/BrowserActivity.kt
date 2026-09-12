package app.pwhs.blockads.ui.browser

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.theme.BlockadsTheme
import app.pwhs.blockads.utils.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin

class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, url: String = "https://m.youtube.com"): Intent {
            return Intent(context, BrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
        }
    }

    private val _isInPipMode = mutableStateOf(false)
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun attachBaseContext(newBase: Context) {
        val appPrefs = AppPreferences(newBase)
        val savedLang = runBlocking { appPrefs.appLanguage.first() }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, savedLang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        requestMediaAudioFocus()

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: "https://m.youtube.com"

        setContent {
            val appPrefs: AppPreferences = getKoin().get()
            val themeMode by appPrefs.themeMode.collectAsState(initial = AppPreferences.THEME_SYSTEM)
            val accentColor by appPrefs.accentColor.collectAsState(initial = AppPreferences.ACCENT_GREEN)

            BlockadsTheme(themeMode = themeMode, accentColor = accentColor) {
                BrowserScreen(
                    initialUrl = targetUrl,
                    isInPipMode = _isInPipMode.value,
                    onEnterPip = { enterPipMode() },
                    onCloseBrowser = { finish() }
                )
            }
        }
        updatePipParams()
    }

    override fun onResume() {
        super.onResume()
        updatePipParams()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPipMode.value = isInPictureInPictureMode
    }

    fun enterPipMode(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(true)
                    }
                }
                .build()
            return runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        }
        return false
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(true)
                .build()
            runCatching { setPictureInPictureParams(params) }
        }
    }

    private fun requestMediaAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { /* Keep playing */ }
                .setAcceptsDelayedFocusGain(true)
                .build()
            audioFocusRequest = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    { /* Keep playing */ },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioFocusRequest?.let { req ->
                runCatching { audioManager?.abandonAudioFocusRequest(req) }
            }
        }
    }
}
