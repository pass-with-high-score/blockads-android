package app.pwhs.blockads.ui.browser

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.browser.elementrules.ElementRulesScreen
import app.pwhs.blockads.ui.theme.BlockadsTheme
import app.pwhs.blockads.utils.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.pwhs.blockads.ui.browser.media.BrowserMediaCoordinator
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
    private val _currentUrl = mutableStateOf("https://m.youtube.com")
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun attachBaseContext(newBase: Context) {
        val appPrefs = AppPreferences(newBase)
        val savedLang = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(500L) { appPrefs.appLanguage.first() } ?: AppPreferences.LANGUAGE_SYSTEM
        }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, savedLang))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.getStringExtra(EXTRA_URL) ?: intent.dataString
        if (!newUrl.isNullOrBlank()) {
            _currentUrl.value = newUrl
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        checkNotificationPermission()
        requestMediaAudioFocus()

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: intent.dataString ?: "https://m.youtube.com"
        _currentUrl.value = targetUrl

        isAutoPipEnabled = getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_pip_enabled", false)

        setContent {
            val appPrefs: AppPreferences = getKoin().get()
            val themeMode by appPrefs.themeMode.collectAsState(initial = AppPreferences.THEME_SYSTEM)
            val accentColor by appPrefs.accentColor.collectAsState(initial = AppPreferences.ACCENT_GREEN)
            var showElementRules by remember { mutableStateOf(false) }
            var autoPipEnabledState by remember { mutableStateOf(isAutoPipEnabled) }

            BlockadsTheme(themeMode = themeMode, accentColor = accentColor) {
                BackHandler(enabled = showElementRules) {
                    showElementRules = false
                }

                if (showElementRules) {
                    ElementRulesScreen(
                        onNavigateBack = { showElementRules = false }
                    )
                } else {
                    BrowserScreen(
                        initialUrl = _currentUrl.value,
                        isInPipMode = _isInPipMode.value,
                        isAutoPipEnabled = autoPipEnabledState,
                        onToggleAutoPip = {
                            val newMode = !autoPipEnabledState
                            autoPipEnabledState = newMode
                            isAutoPipEnabled = newMode
                            getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("auto_pip_enabled", newMode)
                                .apply()
                            updatePipParams()
                        },
                        onEnterPip = { enterPipMode() },
                        onVideoPlaybackChanged = { playing ->
                            if (isVideoPlaying != playing) {
                                isVideoPlaying = playing
                                if (playing) {
                                    requestMediaAudioFocus()
                                }
                                updatePipParams()
                            }
                        },
                        onVideoBoundsChanged = { rect, ratio, playing ->
                            updateVideoBounds(rect, ratio, playing)
                        },
                        onCloseBrowser = { finish() },
                        onNavigateToElementRules = { showElementRules = true }
                    )
                }
            }
        }
        updatePipParams()
    }

    private var isVideoPlaying = false
    private var currentSourceRect: Rect? = null
    private var currentAspectRatio = Rational(16, 9)
    private var isAutoPipEnabled = false

    fun updateVideoBounds(
        sourceRect: Rect?,
        aspectRatio: Rational,
        playing: Boolean
    ) {
        var changed = false
        if (isVideoPlaying != playing) {
            isVideoPlaying = playing
            changed = true
            if (playing) {
                requestMediaAudioFocus()
            }
        }
        if (currentSourceRect != sourceRect) {
            currentSourceRect = sourceRect
            changed = true
        }
        if (currentAspectRatio != aspectRatio) {
            currentAspectRatio = aspectRatio
            changed = true
        }
        if (changed) {
            updatePipParams()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePipParams()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoPlaying && isAutoPipEnabled) {
            enterPipMode()
        }
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
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(currentAspectRatio)

            currentSourceRect?.let { rect ->
                if (rect.width() > 50 && rect.height() > 50) {
                    builder.setSourceRectHint(rect)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            return runCatching { enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
        }
        return false
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(currentAspectRatio)

            currentSourceRect?.let { rect ->
                if (rect.width() > 50 && rect.height() > 50) {
                    builder.setSourceRectHint(rect)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(isVideoPlaying && isAutoPipEnabled)
            }
            runCatching { setPictureInPictureParams(builder.build()) }
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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        if (isFinishing) {
            BrowserMediaCoordinator.stopMedia(this)
        }
    }
}
