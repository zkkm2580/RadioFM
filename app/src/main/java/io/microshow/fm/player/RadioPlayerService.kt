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
import androidx.media.session.MediaButtonReceiver
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity
import org.videolan.libvlc.media.MediaPlayer as VlcMediaPlayer

class RadioPlayerService : Service() {

    private var vlcPlayer: VlcMediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentChannel: RadioChannel? = null
    private val channelList = ArrayList<RadioChannel>()
    private var currentIndex = -1

    companion object {
        const val ACTION_PLAY = "io.microshow.fm.ACTION_PLAY"
        const val ACTION_TOGGLE_PAUSE = "io.microshow.fm.ACTION_TOGGLE_PAUSE"
        const val ACTION_STOP = "io.microshow.fm.ACTION_STOP"
        const val ACTION_NEXT = "io.microshow.fm.ACTION_NEXT"
        const val ACTION_PREV = "io.microshow.fm.ACTION_PREV"
        const val ACTION_UPDATE_PLAYLIST = "io.microshow.fm.ACTION_UPDATE_PLAYLIST"
        const val ACTION_STATUS_CHANGE = "io.microshow.fm.ACTION_STATUS_CHANGE"

        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_CHANNEL_LIST = "extra_channel_list"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_CURRENT_INDEX = "extra_current_index"

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
    }

    /**
     * 监听车载中控、蓝牙或方向盘切歌广播
     */
    private val carMediaCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d("RadioPlayerService", "收到车载广播: $action")
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
        initVlcPlayer()
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

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioFMSession")
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, MediaButtonReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        mediaSession.setMediaButtonReceiver(pendingIntent)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
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
                return handleMediaKeyEvent(keyEvent) || super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        mediaSession.isActive = true
    }

    private fun handleMediaKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    playNext()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_UP,
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

    private fun initVlcPlayer() {
        try {
            vlcPlayer = VlcMediaPlayer().apply {
                setOnPreparedListener { mp ->
                    mp.start()
                    updateNotification("正在播放")
                    broadcastStatus("正在播放")
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }
                setOnBufferingUpdateListener { _, percent ->
                    val text = "正在缓冲 $percent%..."
                    updateNotification(text)
                    broadcastStatus(text)
                    updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                }
                setOnCompletionListener {
                    updateNotification("播放结束")
                    broadcastStatus("播放结束")
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("RadioPlayerService", "VLC 播放错误: what=$what, extra=$extra")
                    updateNotification("连接失败，重试中...")
                    broadcastStatus("连接失败，重试中...")
                    updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("RadioPlayerService", "初始化 VLC 失败", e)
        }
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        if (intent?.hasExtra(EXTRA_CHANNEL_LIST) == true) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_CHANNEL_LIST, ArrayList::class.java) as? ArrayList<RadioChannel>
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_CHANNEL_LIST) as? ArrayList<RadioChannel>
            }
            if (!list.isNullOrEmpty()) {
                channelList.clear()
                channelList.addAll(list)
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
                    val idx = channelList.indexOfFirst { c -> c.streamUrl == it.streamUrl || c.name == it.name }
                    if (idx >= 0) currentIndex = idx
                    playChannel(it)
                }
            }
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_TOGGLE_PAUSE -> togglePlayback()
            ACTION_STOP -> stopAllPlayback()
        }
        return START_STICKY
    }

    fun playNext() {
        if (channelList.isEmpty()) {
            channelList.addAll(RadioRepository.getPresetChannels())
        }
        if (channelList.isEmpty()) return
        val nextIdx = (currentIndex + 1) % channelList.size
        playChannelAtIndex(nextIdx)
    }

    fun playPrevious() {
        if (channelList.isEmpty()) {
            channelList.addAll(RadioRepository.getPresetChannels())
        }
        if (channelList.isEmpty()) return
        val prevIdx = if (currentIndex <= 0) channelList.size - 1 else currentIndex - 1
        playChannelAtIndex(prevIdx)
    }

    private fun playChannelAtIndex(index: Int) {
        if (index in channelList.indices) {
            currentIndex = index
            playChannel(channelList[index])
        }
    }

    private fun playChannel(channel: RadioChannel) {
        currentChannel = channel
        requestAudioFocus()
        val cleanUrl = RadioRepository.sanitizeUrl(channel.streamUrl)

        val notification = buildNotification("正在连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        broadcastStatus("正在连接...")
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)

        try {
            if (vlcPlayer == null) {
                initVlcPlayer()
            }
            vlcPlayer?.reset()
            vlcPlayer?.setDataSource(applicationContext, Uri.parse(cleanUrl))
            vlcPlayer?.prepareAsync()
        } catch (e: Exception) {
            Log.e("RadioPlayerService", "播放启动异常", e)
            broadcastStatus("连接失败")
        }
    }

    fun isCurrentlyPlaying(): Boolean = vlcPlayer?.isPlaying == true

    fun togglePlayback() {
        if (isCurrentlyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        try {
            vlcPlayer?.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateNotification("已暂停")
        broadcastStatus("已暂停")
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    fun resumePlayback() {
        requestAudioFocus()
        try {
            vlcPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateNotification("正在播放")
        broadcastStatus("正在播放")
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
    }

    fun stopAllPlayback() {
        abandonAudioFocus()
        try {
            vlcPlayer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        broadcastStatus("已停止")
        stopSelf()
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_STATUS_CHANGE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(EXTRA_CHANNEL, currentChannel)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(statusText: String): Notification {
        val channelName = currentChannel?.name ?: "畅听电台"
        val freq = currentChannel?.frequency ?: "网络广播"

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
            .setContentText("$freq · $statusText")
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pOpenApp)
            .addAction(android.R.drawable.ic_media_previous, "上一台", pPrev)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                pToggle
            )
            .addAction(android.R.drawable.ic_media_next, "下一台", pNext)
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
                "电台后台播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示当前播放的广播节目并提供播放控制"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(carMediaCommandReceiver)
        stopAllPlayback()
        try {
            vlcPlayer?.release()
            vlcPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
