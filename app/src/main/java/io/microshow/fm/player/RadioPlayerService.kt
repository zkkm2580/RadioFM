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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class RadioPlayerService : Service() {

    private var libVLC: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

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
        initMediaSession()
        createNotificationChannel()
        registerCarMediaReceiver()
        initLibVLC()
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

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioFMSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumePlayback() }
                override fun onPause() { pausePlayback() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onStop() { stopAllPlayback() }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (handleMediaKeyEvent(keyEvent)) {
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
    }

    private fun initLibVLC() {
        if (libVLC == null) {
            // \u91c7\u7528\u4e0e\u5b98\u65b9 VLC \u5ba2\u6237\u7aef\u5b8c\u5168\u4e00\u81f4\u7684\u7eaf\u51c0\u81ea\u9002\u5e94\u53c2\u6570\uff0c\u4e0d\u5199\u6b7b aout \u6216 no-video\uff0c\u786e\u4fdd\u517c\u5bb9\u5404\u7c7b\u8f66\u673a\u58f0\u5361\u4e0e TS \u65f6\u949f
            val options = ArrayList<String>().apply {
                add("--network-caching=1500")   // 1.5 \u79d2\u7f51\u7edc\u7f13\u5b58\uff0c\u79d2\u5f00\u6297\u6296\u52a8
                add("--http-reconnect")          // \u7f51\u7edc\u95ea\u65ad\u81ea\u52a8\u91cd\u8fde
                add("--no-spu")                  // \u5173\u95ed\u5b57\u5e55
            }
            try {
                libVLC = LibVLC(applicationContext, options)
            } catch (e: Exception) {
                Log.e(TAG, "\u521d\u59cb\u5316 LibVLC \u5931\u8d25", e)
            }
        }

        if (vlcPlayer == null && libVLC != null) {
            try {
                vlcPlayer = MediaPlayer(libVLC).apply {
                    setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Buffering -> {
                                val percent = event.buffering.toInt()
                                val status = "\u6b63\u5728\u7f13\u51b2 $percent%..."
                                updateNotification(status)
                                broadcastStatus(status)
                                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                            }
                            MediaPlayer.Event.Playing -> {
                                updateNotification("\u6b63\u5728\u64ad\u653e")
                                broadcastStatus("\u6b63\u5728\u64ad\u653e")
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                // \u515c\u5e95\u786e\u4fdd\u97f3\u91cf\u4e3a 100% \u5e76\u4e14\u6fc0\u6d3b\u53ef\u7528\u97f3\u8f68
                                try {
                                    vlcPlayer?.volume = 100
                                    val currentTrack = vlcPlayer?.audioTrack ?: -1
                                    if (currentTrack == -1) {
                                        val tracks = vlcPlayer?.audioTracks
                                        val firstValid = tracks?.firstOrNull { it.id > 0 }
                                        firstValid?.let {
                                            Log.d(TAG, "\u81ea\u52a8\u6fc0\u6d3b\u97f3\u8f68: ${it.id} (${it.name})")
                                            vlcPlayer?.setAudioTrack(it.id)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "\u68c0\u67e5\u97f3\u8f68\u914d\u7f6e\u5f02\u5e38", e)
                                }
                            }
                            MediaPlayer.Event.Paused -> {
                                updateNotification("\u5df2\u6682\u505c")
                                broadcastStatus("\u5df2\u6682\u505c")
                                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            }
                            MediaPlayer.Event.Stopped -> {
                                updateNotification("\u5df2\u505c\u6b62")
                                broadcastStatus("\u5df2\u505c\u6b62")
                                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                            }
                            MediaPlayer.Event.EndReached -> {
                                updateNotification("\u64ad\u653e\u7ed3\u675f")
                                broadcastStatus("\u64ad\u653e\u7ed3\u675f")
                                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                Log.e(TAG, "LibVLC \u64ad\u653e\u9047\u5230\u9519\u8bef")
                                updateNotification("\u8fde\u63a5\u5931\u8d25\uff0c\u91cd\u8bd5\u4e2d...")
                                broadcastStatus("\u8fde\u63a5\u5931\u8d25\uff0c\u91cd\u8bd5\u4e2d...")
                                updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "\u521b\u5efa LibVLC MediaPlayer \u5931\u8d25", e)
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

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

        try {
            initLibVLC()
            val player = vlcPlayer ?: return
            val vlc = libVLC ?: return

            player.stop()

            // \u4f18\u96c5\u91ca\u653e\u4e0a\u4e00\u4e2a\u5a92\u4f53\u5b9e\u4f8b
            currentMedia?.release()
            currentMedia = null

            val media = Media(vlc, Uri.parse(cleanUrl)).apply {
                setHWDecoderEnabled(false, false)
                addOption(":network-caching=1500")
                addOption(":http-user-agent=VLC/3.0.18 (Linux; Android)")
            }
            currentMedia = media

            player.media = media
            player.volume = 100 // \u663e\u5f0f\u786e\u4fdd\u64ad\u653e\u5668\u97f3\u91cf\u4e3a 100%
            player.play()

            updateNotification("\u6b63\u5728\u8fde\u63a5...")
            broadcastStatus("\u6b63\u5728\u8fde\u63a5...")
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
        } catch (e: Exception) {
            Log.e(TAG, "LibVLC \u64ad\u653e\u542f\u52a8\u5f02\u5e38", e)
            broadcastStatus("\u8fde\u63a5\u5931\u8d25")
        }
    }

    fun isCurrentlyPlaying(): Boolean {
        return vlcPlayer?.isPlaying == true
    }

    fun togglePlayback() {
        if (isCurrentlyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        vlcPlayer?.pause()
        updateNotification("\u5df2\u6682\u505c")
        broadcastStatus("\u5df2\u6682\u505c")
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    fun resumePlayback() {
        requestAudioFocus()
        vlcPlayer?.play()
        updateNotification("\u6b63\u5728\u64ad\u653e")
        broadcastStatus("\u6b63\u5728\u64ad\u653e")
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
    }

    fun stopAllPlayback() {
        abandonAudioFocus()
        try {
            vlcPlayer?.stop()
            currentMedia?.release()
            currentMedia = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
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
                    .setMediaSession(mediaSession.sessionToken)
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
            vlcPlayer?.release()
            vlcPlayer = null
            currentMedia?.release()
            currentMedia = null
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            mediaSession.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
