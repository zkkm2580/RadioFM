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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media

/**
 * FM网络收音机核心播放服务
 * 双引擎架构：
 * 1. VideoLAN LibVLC 原生底层引擎：专用于 Tvheadend MPEG-TS 纯音频广播流（支持 TS 解封装与软硬解自动容灾）；
 * 2. AndroidX Media3 (ExoPlayer) 引擎：用于常规网络电台、HLS (.m3u8) 与标准 AAC/MP3 流。
 * 完善的车载方向盘切台、MediaSession 协议与音频焦点（AudioFocus）管理。
 */
class RadioPlayerService : Service() {

    private lateinit var exoPlayer: ExoPlayer

    // VideoLAN LibVLC 原生播放核心（专用于 Tvheadend TS 流）
    private var libVLC: LibVLC? = null
    private var vlcMediaPlayer: org.videolan.libvlc.MediaPlayer? = null
    private var isUsingVlcPlayer = false
    private var hasTriedVlcSoftwareFallback = false

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val playlistManager = PlaylistManager()
    private var lastStatusText: String = "就绪"

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "RadioPlayerService"
        const val VLC_USER_AGENT = "VLC/3.0.18 (Linux; Android)"

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
     * 监听各种车载（掌讯、方易通、诺威等）私有或系统媒体按键广播
     */
    private val carMediaCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "收到车载外部广播: $action")
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
        initLibVlc()
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

    /**
     * 识别 Tvheadend 特征流与 MPEG-TS 广播流
     */
    private fun isTvheadendOrTs(url: String): Boolean {
        return url.contains(":8168", ignoreCase = true) ||
                url.contains(":9981", ignoreCase = true) ||
                url.contains("/stream/", ignoreCase = true) ||
                url.contains("profile=pass", ignoreCase = true) ||
                url.contains(".ts", ignoreCase = true) ||
                url.contains("audio/mp2t", ignoreCase = true)
    }

    /**
     * 初始化 VideoLAN LibVLC 原生解码引擎
     */
    private fun initLibVlc() {
        try {
            val options = arrayListOf(
                "--http-user-agent=$VLC_USER_AGENT",
                "--network-caching=2500",
                "--live-caching=2000",
                "--file-caching=2000",
                "--sout-mux-caching=2000",
                "--codec=mediacodec_ndk,mediacodec_jni,all",
                "--http-reconnect",
                "--ts-trust-pcr",
                "--no-sub-autodetect-file",
                "-vvv"
            )
            libVLC = LibVLC(this, options)
            vlcMediaPlayer = org.videolan.libvlc.MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    if (!isUsingVlcPlayer) return@setEventListener
                    when (event.type) {
                        org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                            val status = "正在缓冲(TS)..."
                            updateNotification(status)
                            broadcastStatus(status)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                            val status = "正在播放"
                            updateNotification(status)
                            broadcastStatus(status)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                            Log.w(TAG, "LibVLC 播放 Tvheadend 异常，准备容灾降级软解重试")
                            handleVlcPlaybackError()
                        }
                        org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                            playlistManager.getCurrentChannel()?.let {
                                val cleanUrl = RadioRepository.sanitizeUrl(it.streamUrl)
                                playWithLibVlc(cleanUrl, useSoftwareDecoder = false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化 LibVLC 异常: ${e.message}", e)
        }
    }

    private fun initExoPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VLC_USER_AGENT)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (isUsingVlcPlayer) return
                val status = when (state) {
                    Player.STATE_BUFFERING -> "正在缓冲..."
                    Player.STATE_READY -> if (exoPlayer.isPlaying) "正在播放" else "已暂停"
                    Player.STATE_ENDED -> "播放结束"
                    else -> "就绪"
                }
                updateNotification(status)
                broadcastStatus(status)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isUsingVlcPlayer) return
                val status = if (isPlaying) "正在播放" else "已暂停"
                updateNotification(status)
                broadcastStatus(status)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer 播放错误: ${error.errorCodeName}", error)
                playlistManager.getCurrentChannel()?.let { ch ->
                    val cleanUrl = RadioRepository.sanitizeUrl(ch.streamUrl)
                    if (!isUsingVlcPlayer) {
                        Log.d(TAG, "自动故障转移切换至 LibVLC 原生底层引擎播放...")
                        hasTriedVlcSoftwareFallback = false
                        playWithLibVlc(cleanUrl, useSoftwareDecoder = false)
                        return
                    }
                }
                val status = "播放重试中..."
                updateNotification(status)
                broadcastStatus(status)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        })

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
     * 处理车载物理键与媒体按键
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
                broadcastStatus("已停止")
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

        val notification = buildNotification("正在连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (isTvheadendOrTs(cleanUrl)) {
            Log.d(TAG, "检测到 Tvheadend/TS 流，使用 VideoLAN LibVLC 原生底层核心播放: $cleanUrl")
            hasTriedVlcSoftwareFallback = false
            playWithLibVlc(cleanUrl, useSoftwareDecoder = false)
        } else {
            Log.d(TAG, "使用 ExoPlayer 引擎播放: $cleanUrl")
            playWithExoPlayer(cleanUrl)
        }
    }

    /**
     * 使用 VideoLAN LibVLC 原生核心播放 Tvheadend MPEG-TS 流
     */
    private fun playWithLibVlc(url: String, useSoftwareDecoder: Boolean = false) {
        stopAllPlayback()
        isUsingVlcPlayer = true

        val vlc = libVLC ?: run {
            initLibVlc()
            libVLC
        } ?: return

        val player = vlcMediaPlayer ?: return

        try {
            player.stop()
            val media = Media(vlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(!useSoftwareDecoder, false)
                addOption(":http-user-agent=$VLC_USER_AGENT")
                addOption(":network-caching=2500")
                addOption(":http-reconnect=true")
                addOption(":demux=ts")
                addOption(":ts-extra-pmt=1")
                addOption(":ts-trust-pcr")
            }
            player.media = media
            media.release()
            player.play()

            val status = if (useSoftwareDecoder) "正在连接(TS软解)..." else "正在连接(TS)..."
            updateNotification(status)
            broadcastStatus(status)
        } catch (e: Exception) {
            Log.e(TAG, "LibVLC 播放启动异常: ${e.message}")
            handleVlcPlaybackError()
        }
    }

    /**
     * Tvheadend 播放异常自愈容灾：当硬解对特定广播 MP2 音频流不支持时，自动切换 LibVLC 软解
     */
    private fun handleVlcPlaybackError() {
        val channel = playlistManager.getCurrentChannel() ?: return
        val cleanUrl = RadioRepository.sanitizeUrl(channel.streamUrl)
        if (!hasTriedVlcSoftwareFallback) {
            hasTriedVlcSoftwareFallback = true
            Log.d(TAG, "Tvheadend 硬解受限，自动切换至 LibVLC 原生软解模式...")
            playWithLibVlc(cleanUrl, useSoftwareDecoder = true)
        } else {
            val status = "正在重连(TS)..."
            updateNotification(status)
            broadcastStatus(status)
            mainHandler.postDelayed({
                hasTriedVlcSoftwareFallback = false
                playWithLibVlc(cleanUrl, useSoftwareDecoder = false)
            }, 2500L)
        }
    }

    private fun playWithExoPlayer(url: String) {
        stopAllPlayback()
        isUsingVlcPlayer = false

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.play()
        updateNotification("正在连接...")
        broadcastStatus("正在连接...")
    }

    fun isCurrentlyPlaying(): Boolean {
        return if (isUsingVlcPlayer) {
            vlcMediaPlayer?.isPlaying == true
        } else {
            exoPlayer.isPlaying
        }
    }

    fun togglePlayback() {
        if (isCurrentlyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        if (isUsingVlcPlayer) {
            vlcMediaPlayer?.pause()
        } else {
            exoPlayer.pause()
        }
        updateNotification("已暂停")
        broadcastStatus("已暂停")
    }

    fun resumePlayback() {
        requestFocus()
        if (isUsingVlcPlayer) {
            vlcMediaPlayer?.play()
        } else {
            exoPlayer.play()
        }
        updateNotification("正在播放")
        broadcastStatus("正在播放")
    }

    private fun requestFocus() {
        requestAudioFocus()
    }

    fun stopAllPlayback() {
        abandonAudioFocus()
        try {
            if (exoPlayer.isPlaying || exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            vlcMediaPlayer?.stop()
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
        val channelName = currentChannel?.name ?: "网络收音机"
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
        try {
            unregisterReceiver(carMediaCommandReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopAllPlayback()
        vlcMediaPlayer?.release()
        libVLC?.release()
        vlcMediaPlayer = null
        libVLC = null

        mediaSession?.run {
            exoPlayer.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
