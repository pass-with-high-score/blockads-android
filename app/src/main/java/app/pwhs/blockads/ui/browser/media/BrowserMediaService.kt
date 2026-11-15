package app.pwhs.blockads.ui.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.browser.BrowserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service that manages an Android MediaSession and publishes a MediaStyle
 * notification in the device's notification shade, lockscreen, and system media controls.
 */
class BrowserMediaService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4001
        const val CHANNEL_ID = "browser_media_channel"

        const val ACTION_UPDATE_STATE = "app.pwhs.blockads.media.UPDATE_STATE"
        const val ACTION_PLAY = "app.pwhs.blockads.media.PLAY"
        const val ACTION_PAUSE = "app.pwhs.blockads.media.PAUSE"
        const val ACTION_NEXT = "app.pwhs.blockads.media.NEXT"
        const val ACTION_PREV = "app.pwhs.blockads.media.PREV"
        const val ACTION_STOP = "app.pwhs.blockads.media.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
    }

    private var mediaSession: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockReleaseRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentArtworkUrl: String = ""
    private var currentArtworkBitmap: Bitmap? = null
    private var isPlaying: Boolean = false
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlockAds:BrowserMediaWakeLock")
        createNotificationChannel()
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "BlockAdsBrowserMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    handlePlay()
                }

                override fun onPause() {
                    handlePause()
                }

                override fun onSkipToNext() {
                    handleNext()
                }

                override fun onSkipToPrevious() {
                    handlePrev()
                }

                override fun onSeekTo(pos: Long) {
                    handleSeek(pos)
                }

                override fun onStop() {
                    handleStop()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> handlePlay()
            ACTION_PAUSE -> handlePause()
            ACTION_NEXT -> handleNext()
            ACTION_PREV -> handlePrev()
            ACTION_STOP -> handleStop()
            ACTION_UPDATE_STATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                val artwork = intent.getStringExtra(EXTRA_ARTWORK_URL).orEmpty()
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                val dur = intent.getLongExtra(EXTRA_DURATION_MS, 0L)

                currentTitle = title.ifBlank { "Media" }
                currentArtist = artist.ifBlank { "${getString(app.pwhs.blockads.R.string.app_name)} Browser" }
                isPlaying = playing
                positionMs = pos
                durationMs = dur

                if (artwork.isNotBlank() && artwork != currentArtworkUrl) {
                    currentArtworkUrl = artwork
                    loadArtwork(artwork)
                } else if (artwork.isBlank() && currentArtworkUrl.isNotBlank()) {
                    currentArtworkUrl = ""
                    currentArtworkBitmap = null
                }

                updatePlaybackState()
                updateMetadata()
                updateWakeLock()
                showNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun handlePlay() {
        isPlaying = true
        BrowserMediaCoordinator.actionListener?.onPlay()
        updatePlaybackState()
        updateWakeLock()
        showNotification()
    }

    private fun handlePause() {
        isPlaying = false
        BrowserMediaCoordinator.actionListener?.onPause()
        updatePlaybackState()
        updateWakeLock()
        showNotification()
    }

    private fun handleNext() {
        BrowserMediaCoordinator.actionListener?.onSkipNext()
    }

    private fun handlePrev() {
        BrowserMediaCoordinator.actionListener?.onSkipPrev()
    }

    private fun handleSeek(pos: Long) {
        positionMs = pos
        BrowserMediaCoordinator.actionListener?.onSeekTo(pos)
        updatePlaybackState()
    }

    private fun handleStop() {
        isPlaying = false
        BrowserMediaCoordinator.actionListener?.onPause()
        updateWakeLock(forceRelease = true)
        currentArtworkBitmap = null
        currentArtworkUrl = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun loadArtwork(urlStr: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(urlStr)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                }
                val rawBmp = conn.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
                if (rawBmp != null && currentArtworkUrl == urlStr) {
                    val maxDim = 512
                    val scaled = if (rawBmp.width > maxDim || rawBmp.height > maxDim) {
                        val scale = maxDim.toFloat() / kotlin.math.max(rawBmp.width, rawBmp.height)
                        val w = (rawBmp.width * scale).toInt()
                        val h = (rawBmp.height * scale).toInt()
                        Bitmap.createScaledBitmap(rawBmp, w, h, true)
                    } else {
                        rawBmp
                    }
                    withContext(Dispatchers.Main) {
                        if (currentArtworkUrl == urlStr) {
                            currentArtworkBitmap = scaled
                            updateMetadata()
                            showNotification()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateWakeLock(forceRelease: Boolean = false) {
        if (isPlaying) {
            wakeLockReleaseRunnable?.let { handler.removeCallbacks(it) }
            wakeLockReleaseRunnable = null
            if (wakeLock?.isHeld != true) {
                runCatching { wakeLock?.acquire(2 * 60 * 60 * 1000L) }
            }
        } else if (forceRelease) {
            wakeLockReleaseRunnable?.let { handler.removeCallbacks(it) }
            wakeLockReleaseRunnable = null
            if (wakeLock?.isHeld == true) {
                runCatching { wakeLock?.release() }
            }
        } else {
            if (wakeLockReleaseRunnable == null && wakeLock?.isHeld == true) {
                val runnable = Runnable {
                    if (!isPlaying && wakeLock?.isHeld == true) {
                        runCatching { wakeLock?.release() }
                    }
                    wakeLockReleaseRunnable = null
                }
                wakeLockReleaseRunnable = runnable
                handler.postDelayed(runnable, 15000L)
            }
        }
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO

        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs, if (isPlaying) 1.0f else 0.0f)

        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updateMetadata() {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, currentArtist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)

        currentArtworkBitmap?.let { bmp ->
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun showNotification() {
        val sessionToken = mediaSession?.sessionToken ?: return
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BrowserMediaService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BrowserMediaService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, BrowserMediaService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, BrowserMediaService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val prevIcon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous)
        val playPauseIconObj = android.graphics.drawable.Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val nextIcon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next)

        val notification = builder
            .setStyle(mediaStyle)
            .setSmallIcon(R.drawable.ic_browser)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .apply {
                currentArtworkBitmap?.let { bmp ->
                    setLargeIcon(bmp)
                }
            }
            .addAction(Notification.Action.Builder(prevIcon, "Previous", prevIntent).build())
            .addAction(Notification.Action.Builder(playPauseIconObj, playPauseTitle, playPauseIntent).build())
            .addAction(Notification.Action.Builder(nextIcon, "Next", nextIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media controls for video and audio playback"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        currentArtworkBitmap = null
        currentArtworkUrl = ""
        wakeLockReleaseRunnable?.let { handler.removeCallbacks(it) }
        wakeLockReleaseRunnable = null
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
    }
}
