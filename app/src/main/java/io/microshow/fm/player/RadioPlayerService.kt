package io.microshow.fm.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity

/**
 * \u7545\u542cFM \u6838\u5fc3\u64ad\u653e\u670d\u52a1 (ExoPlayer \u8f7b\u91cf\u7ea7\u9ad8\u80fd\u6548\u5f15\u64ce)
 * \u4fdd\u7559\u8f66\u8f7d\u65b9\u5411\u76d8\u6309\u952e\u6df1\u5ea6\u62e6\u622a\u3001MediaSession \u8f66\u8f7d\u63a7\u5236\u534f\u8bae\u3001\u591c\u95f4\u6a21\u5f0f\u4e0e\u64ad\u653e\u5217\u8868\u5207\u6362
 */
class RadioPlayerService : Service() {

    private lateinit var exoPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // \u91c7\u7528 PlaylistManager \u7edf\u4e00\u7ba1\u7406\u9891\u9053\u5217\u8868\u4e0e\u5e73\u6ed1\u5207\u53f0
    private val playlistManager = PlaylistManager()
    private var lastStatusText: String = "\u5c31\u7eea"

    companion object {
        private const val TAG = "RadioPlayerService"

        const val ACTION_PLAY = "io.microshow.fm.ACTION_PLAY"
        const val ACTION_TOGGLE_PAUSE = "io.microshow.fm.ACTION_TOGGLE_PAUSE"
        const val ACTION_STOP = "io.microshow.fm.ACTION_STOP"
        const val ACTION_NEXT = "io.microshow.fm.ACTION_NEXT"
        const val ACTION_PREV = "io.microshow.fm.ACTION_PREV"
        const val ACTION_UPDATE_PLAYLIST = "io.microshow.fm.ACTION_UPDATE_PLAYLIST"
        const val ACTION_REQUEST_STATUS = "io.microshow.fm.ACTION_REQUEST_STATUS"
        const val ACTION_STATUS_CHANGE = "io.microshow.fm.ACTION_STATUS_CHANGE"

        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_CHANNEL_LIST = "extra_channel_list"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_CURRENT_INDEX = "extra_current_index"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        private const val CHANNEL_ID = "radio_playback_channel"
        private const val NOTIFICATION_ID = 1001

        fun play(context: Context, channel: RadioChannel, list: List<RadioChannel>? = null) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_CHANNEL, channel)
                if (list != null) {
                    putExtra(EXTRA_CHANNEL_LIST, ArrayList(list))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun next(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_NEXT
            }
            context.startService(intent)
        }

        fun prev(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PREV
            }
            context.startService(intent)
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updatePlaylist(context: Context, list: List<RadioChannel>) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_UPDATE_PLAYLIST
                putExtra(EXTRA_CHANNEL_LIST, ArrayList(list))
            }
            context.startService(intent)
        }

        fun requestStatus(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_REQUEST_STATUS
            }
            context.startService(intent)
        }
    }

    /**
     * \u8f66\u8f7d\u5382\u5546\u79c1\u6709\u5207\u6b4c\u5e7f\u64ad\u63a5\u6536\u5668
     */
    private val carMediaCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "\u6536\u5230\u8f66\u8f7d\u5916\u90e8\u5e7f\u64ad: $action")
            if (action == "com.android.music.musicservicecommand") {
                val cmd = intent.getStringExtra("command")
                when {
                    cmd.equals("next", ignoreCase = true) -> playNext()
                    cmd.equals("previous", ignoreCase = true) -> playPrevious()
                    cmd.equals("togglepause", ignoreCase = true) -> togglePlayback()
                    cmd.equals("pause", ignoreCase = true) -> pausePlayback()
                    cmd.equals("play", ignoreCase = true) -> resumePlayback()
                }
            } else if (action == Intent.ACTION_MEDIA_BUTTON) {
                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                handleMediaKeyEvent(keyEvent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initExoPlayer()
        createNotificationChannel()
        registerCarMediaReceiver()
    }

    private fun registerCarMediaReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.android.music.musicservicecommand")
            addAction("com.android.music.musicservicecommand.next")
            addAction("com.android.music.musicservicecommand.previous")
            addAction("com.android.music.musicservicecommand.togglepause")
            addAction(Intent.ACTION_MEDIA_BUTTON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(carMediaCommandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(carMediaCommandReceiver, filter)
        }
    }

    private fun initExoPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val status = when (state) {
                    Player.STATE_BUFFERING -> "\u6b63\u5728\u7f13\u51b2..."
                    Player.STATE_READY -> if (exoPlayer.isPlaying) "\u6b63\u5728\u64ad\u653e" else "\u5df2\u6682\u505c"
                    Player.STATE_ENDED -> "\u64ad\u653e\u7ed3\u675f"
                    else -> "\u5c31\u7eea"
                }
                updateNotification(status)
                broadcastStatus(status)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) "\u6b63\u5728\u64ad\u653e" else "\u5df2\u6682\u505c"
                updateNotification(status)
                broadcastStatus(status)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer \u64ad\u653e\u9519\u8bef: ${error.errorCodeName}", error)
                val status = "\u8fde\u63a5\u91cd\u8bd5\u4e2d..."
                updateNotification(status)
                broadcastStatus(status)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        })

        // \u901a\u8fc7 ForwardingPlayer \u62e6\u622a\u8f66\u8f7d\u4e2d\u63a7\u5207\u53f0\u3001\u4e0b\u4e00\u66f2\u6307\u4ee4
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                resumePlayback()
            }
            override fun pause() {
                pausePlayback()
            }
            override fun isPlaying(): Boolean {
                return isCurrentlyPlaying()
            }
            override fun seekToNext() {
                playNext()
            }
            override fun seekToNextMediaItem() {
                playNext()
            }
            override fun seekToPrevious() {
                playPrevious()
            }
            override fun seekToPreviousMediaItem() {
                playPrevious()
            }
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
            override fun isCommandAvailable(command: Int): Boolean {
                if (command == Player.COMMAND_PLAY_PAUSE ||
                    command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    return true
                }
                return super.isCommandAvailable(command)
            }
        }

        val sessionCallback = object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (handleMediaKeyEvent(keyEvent)) {
                    return true
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(sessionCallback)
            .build()
    }

    /**
     * \u5904\u7406\u65b9\u5411\u76d8\u786c\u4ef6\u4e0e\u5a92\u4f53\u6309\u952e
     */
    private fun handleMediaKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    playNext()
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    playPrevious()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlayback()
                    return true
                }
            }
        }
        return false
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
                        AudioManager.AUDIOFOCUS_GAIN -> resumePlayback()
                    }
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
                        AudioManager.AUDIOFOCUS_GAIN -> resumePlayback()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_CHANNEL_LIST) == true) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_CHANNEL_LIST, ArrayList::class.java) as? ArrayList<RadioChannel>
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_CHANNEL_LIST) as? ArrayList<RadioChannel>
            }
            if (!list.isNullOrEmpty()) {
                playlistManager.setPlaylist(list)
            }
        }

        when (intent?.action) {
            ACTION_PLAY -> {
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CHANNEL, RadioChannel::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CHANNEL) as? RadioChannel
                }
                channel?.let {
                    playlistManager.selectChannel(it)
                    playChannel(it)
                }
            }

            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_TOGGLE_PAUSE -> togglePlayback()

            ACTION_REQUEST_STATUS -> {
                broadcastStatus(lastStatusText)
            }

            ACTION_STOP -> {
                stopAllPlayback()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                broadcastStatus("\u5df2\u505c\u6b62")
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun playNext() {
        val target = playlistManager.next(force = true)
        target?.let { playChannel(it) }
    }

    fun playPrevious() {
        val target = playlistManager.previous(force = true)
        target?.let { playChannel(it) }
    }

    private fun playChannel(channel: RadioChannel) {
        requestAudioFocus()
        val cleanUrl = RadioRepository.sanitizeUrl(channel.streamUrl)

        val notification = buildNotification("\u6b63\u5728\u8fde\u63a5...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(cleanUrl)
        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.play()
        updateNotification("\u6b63\u5728\u8fde\u63a5...")
        broadcastStatus("\u6b63\u5728\u8fde\u63a5...")
    }

    fun isCurrentlyPlaying(): Boolean {
        return exoPlayer.isPlaying
    }

    fun togglePlayback() {
        if (isCurrentlyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        exoPlayer.pause()
        updateNotification("\u5df2\u6682\u505c")
        broadcastStatus("\u5df2\u6682\u505c")
    }

    fun resumePlayback() {
        requestAudioFocus()
        exoPlayer.play()
        updateNotification("\u6b63\u5728\u64ad\u653e")
        broadcastStatus("\u6b63\u5728\u64ad\u653e")
    }

    fun stopAllPlayback() {
        abandonAudioFocus()
        try {
            exoPlayer.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastStatus(status: String) {
        lastStatusText = status
        val currentChannel = playlistManager.getCurrentChannel()
        val currentIndex = playlistManager.getCurrentIndex()
        val intent = Intent(ACTION_STATUS_CHANGE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(EXTRA_CHANNEL, currentChannel)
            putExtra(EXTRA_IS_PLAYING, isCurrentlyPlaying())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(statusText: String): Notification {
        val currentChannel = playlistManager.getCurrentChannel()
        val channelName = currentChannel?.name ?: "\u7545\u542c\u7535\u53f0"
        val freq = currentChannel?.frequency ?: "\u7f51\u7edc\u5e7f\u64ad"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val pPrev = PendingIntent.getService(
            this, 2, Intent(this, RadioPlayerService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val pToggle = PendingIntent.getService(
            this, 1, Intent(this, RadioPlayerService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val pNext = PendingIntent.getService(
            this, 3, Intent(this, RadioPlayerService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val isPlaying = isCurrentlyPlaying()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(channelName)
            .setContentText("$freq \u00b7 $statusText")
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pOpenApp)
            .addAction(android.R.drawable.ic_media_previous, "\u4e0a\u4e00\u53f0", pPrev)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "\u6682\u505c" else "\u64ad\u653e",
                pToggle
            )
            .addAction(android.R.drawable.ic_media_next, "\u4e0b\u4e00\u53f0", pNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "\u7535\u53f0\u540e\u53f0\u64ad\u653e",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "\u5c55\u793a\u5f53\u524d\u64ad\u653e\u7684\u5e7f\u64ad\u8282\u76ee\u5e76\u63d0\u4f9b\u64ad\u653e\u63a7\u5236"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(carMediaCommandReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopAllPlayback()
        try {
            exoPlayer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
