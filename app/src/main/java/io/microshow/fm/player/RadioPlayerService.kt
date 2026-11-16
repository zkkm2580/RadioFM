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
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media

/**
 * FM网络收音机核心播放服务
 * 单引擎架构：所有电台流（网络电台、HLS .m3u8、AAC/MP3、Tvheadend MPEG-TS）
 * 统一交由 VideoLAN LibVLC 解码播放。
 *
 * Media3 仅在三处保留用途，均不参与解码：
 * 1. MediaSession 车载会话需要一个 Player 实例作为载体；
 * 2. 车机方向盘与中控的播放/暂停/上下台指令入口；
 * 3. 通知栏与大屏会话的播放状态同步。
 * 因此 ExoPlayer 实例只作为“会话外壳”存在，从不会被赋予媒体源，也不会真正播放。
 */
class RadioPlayerService : Service() {

    /**
     * 仅作为 MediaSession 的 Player 外壳，不承载任何解码播放。
     * 真正的声音全部由 vlcMediaPlayer 输出。
     */
    private lateinit var sessionShellPlayer: ExoPlayer

    // VideoLAN LibVLC 原生播放核心（唯一播放引擎）
    private var libVLC: LibVLC? = null
    private var vlcMediaPlayer: org.videolan.libvlc.MediaPlayer? = null

    /**
     * 当前电台的解码档位，失败时逐级放宽：
     * 0 = 硬解优先（MediaCodec → ffmpeg 软解兜底），默认档
     * 1 = 强制软解（只用 libavcodec）
     * 2 = 强制软解 + 选最低码率 + 缩短缓冲（应对弱网、长链路与高码率源）
     * 切台时重置为 0。
     *
     * 注意：这里不再按失败次数去“猜容器格式”。强制 `:demux=ts` 会让本来
     * 能被 probe 识别的 HLS / MP3 / AAC 源直接解析失败，等于把好源打死。
     */
    private var playbackMode = 0

    /** 上次处理播放异常的时间戳（elapsedRealtime），用于抑制连续错误事件的抖动 */
    private var lastErrorHandledAt = 0L

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val playlistManager = PlaylistManager()
    private var lastStatusText: String = "就绪"

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "RadioPlayerService"
        /**
         * 与 VLC 官方客户端一致的 UA 形态：`VLC/版本 LibVLC/版本`。
         * 原实现写成 "VLC/3.0.18 (Linux; Android)"，括号注释段并非 VLC 的真实格式，
         * 个别源站会按 UA 前缀做白名单，这里对齐官方式样以降低被拒风险。
         */
        const val VLC_USER_AGENT = "VLC/3.0.23 LibVLC/3.0.23"

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
        initSessionShellPlayer()
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
     * 初始化 VideoLAN LibVLC 原生解码引擎（唯一播放引擎）。
     *
     * 这里刻意保持极少的全局参数，与 VLC 官方 Android 客户端（VLC 3.x）的做法一致：
     * 官方 APK 的核心参数只有 `--network-caching`，其余都是可按需增减的兼容性开关，
     * **不与任何容器格式绑定的参数**。
     *
     * 原因是每种流的解封装方式由 libvlc 的 demux 模块自带 probe 现场判定：
     *   - TS：校验 0x47 同步字节与 188 字节包对齐（失败时报 "No Transport Stream sync byte in data."）；
     *   - HLS：校验 `#EXTM3U` / `#EXT-X-STREAM-INF` / `application/vnd.apple.mpegurl` 等特征；
     *   - 其他：MP4 box、PES 起始码等各自探测。
     * 调用方一旦用 `--codec` / `--ts-trust-pcr` 这类全局参数替它做决定，
     * 就等于把“多格式通吃”的能力关掉了，只剩单一格式可用。
     *
     * 历史问题记录（已修）：原实现依赖 `Media.setHWDecoderEnabled(true, false)`。
     * 反编译 libvlc-all:3.6.5 的 `Media.class` 可见，它在 3.x 分支里拼出的选项是
     * `:codec=<硬解模块名>,all` —— `all` 并不是有效模块名，列表里没有真正的软解
     * 兜底，遇到 MediaCodec 不支持的编码（MP2 / AC3 / HE-AAC(i) / OGG）就只剩静音。
     * 现在改为在媒体级显式写出 `:codec=mediacodec_ndk,mediacodec_jni,avcodec,none`，
     * 把软解排在硬解之后，既保留硬解省算力，也不会再出现「有信号、没声音」。
     */
    private fun initLibVlc() {
        try {
            val options = arrayListOf(
                // 网络/直播缓冲：与官方客户端取同一量级。过大的值（如 2500）会让直播延迟持续堆积，
                // 切台后声音明显滞后，弱网恢复后也要等更久才出声。
                "--network-caching=1500",
                "--live-caching=1500",
                // 纯音频应用，关闭字幕文件自动探测，避免网络流里的字幕轨拖慢解封装
                "--no-sub-autodetect-file",
                // 直播流被服务端掐断（Icecast 超时、CDN 心跳重置）时自动重连，
                // 官方客户端同样开启，否则断流后只能整条重来
                "--http-reconnect",
                // 纯音频电台：不创建视频输出。部分车机在没有 Surface 的情况下
                // vout 初始化失败，会把整条流一起拖死；顺带也省下视频解码的算力
                "--no-video",
                // 不叠加标题 OSD，避免部分车机 ROM 上因无窗口而卡在 OSD 初始化
                "--no-video-title-show"
            )
            libVLC = LibVLC(this, options)
            vlcMediaPlayer = org.videolan.libvlc.MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    when (event.type) {
                        org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                            val percent = event.buffering
                            // 已经在正常播放中且缓冲未归零时保持“正在播放”，仅在未开播且缓冲不足时提示缓冲
                            if (percent < 100.0f && !isPlaying) {
                                val status = "正在缓冲..."
                                updateNotification(status)
                                broadcastStatus(status)
                            } else if (isPlaying || percent >= 100.0f) {
                                val status = "正在播放"
                                if (lastStatusText != status) {
                                    updateNotification(status)
                                    broadcastStatus(status)
                                }
                            }
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                            val status = "正在播放"
                            updateNotification(status)
                            broadcastStatus(status)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Paused -> {
                            val status = "已暂停"
                            updateNotification(status)
                            broadcastStatus(status)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Stopped -> {
                            val status = "已停止"
                            updateNotification(status)
                            broadcastStatus(status)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.TimeChanged -> {
                            // 只要音频在推进，且状态未显示为“正在播放”，立即纠偏
                            if (isPlaying && lastStatusText != "正在播放") {
                                val status = "正在播放"
                                updateNotification(status)
                                broadcastStatus(status)
                            }
                        }
                        org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                            Log.w(TAG, "LibVLC 播放异常，准备切换解码策略重试")
                            handleVlcPlaybackError()
                        }
                        org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                            playlistManager.getCurrentChannel()?.let {
                                val cleanUrl = RadioRepository.sanitizeUrl(it.streamUrl)
                                playbackMode = 0
                                playWithLibVlc(cleanUrl)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化 LibVLC 异常: ${e.message}", e)
        }
    }

    /**
     * 初始化 MediaSession 车载会话外壳。
     * ExoPlayer 不加载任何媒体，仅作为 Player 实例承载 MediaSession，
     * 所有播放指令最终都被 ForwardingPlayer 转发到 LibVLC。
     */
    private fun initSessionShellPlayer() {
        sessionShellPlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val forwardingPlayer = object : ForwardingPlayer(sessionShellPlayer) {
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
                val effectiveStatus = if (vlcMediaPlayer?.isPlaying == true) {
                    "正在播放"
                } else {
                    lastStatusText
                }
                broadcastStatus(effectiveStatus)
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
        val cleanUrl = RadioRepository.sanitizeUrl(channel.streamUrl)

        val notification = buildNotification("正在连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "使用 VideoLAN LibVLC 播放: $cleanUrl")
        // 换台即重置解码档位与错误防抖，新一轮从“硬解优先”开始
        playbackMode = 0
        lastErrorHandledAt = 0L
        playWithLibVlc(cleanUrl)
    }

    /**
     * 使用 VideoLAN LibVLC 播放任意电台流。
     *
     * 三条原则，逐条对齐 VLC 官方 Android 客户端：
     * 1. **不指定容器格式**（不加 `:demux=`），交给 libvlc 的 demux probe 现场判定，
     *    这是 TS、HLS、MP3、AAC、OGG 通吃的根本原因；
     * 2. **解码器列表必须显式带软解兜底**。只写硬解模块时，libvlc 找不到可用解码器
     *    就直接静音/报错，MP2、AC3、HE-AAC(i)、OGG 这类硬解不支持的编码全部播不出来；
     * 3. **不手动 stop 再 setMedia**。libvlc 的 stop 是异步的，紧跟其后的 setMedia
     *    会被丢弃，表现就是“点了没反应、再点一次才出声”。官方同样是直接换 media。
     *
     * @param decodeMode 0 = 硬解优先（默认）；1 = 强制软解；2 = 强制软解 + 最低码率 + 短缓冲
     */
    private fun playWithLibVlc(url: String, decodeMode: Int = 0) {
        // 切台与重试都要握住音频焦点。这里刻意不调 stopAllPlayback()，
        // 那是 ACTION_STOP 的收尾动作，且默认会连带放弃刚申请的焦点。
        requestAudioFocus()

        if (libVLC == null) initLibVlc()
        val vlc = libVLC ?: return
        val player = vlcMediaPlayer ?: return

        try {
            val buffering = if (decodeMode >= 2) 800 else 1500
            val media = Media(vlc, Uri.parse(url)).apply {
                // 顺序：MediaCodec 硬解(NDK/JNI) → libavcodec 软解 → none 交回默认列表。
                // 注意不能只写硬解模块名：3.x 的 setHWDecoderEnabled 拼出的是 `...all`，
                // 而 all 并不是有效模块名，等于把软解兜底丢掉了。
                addOption(
                    if (decodeMode == 0) {
                        ":codec=mediacodec_ndk,mediacodec_jni,avcodec,none"
                    } else {
                        ":codec=avcodec,none"
                    }
                )
                if (decodeMode > 0) {
                    // 关掉 avcodec 的硬解通道（VLC 3.0 的选项名是 avcodec-hw），
                    // 确保真的落到 ffmpeg 软件解码
                    addOption(":avcodec-hw=none")
                }
                addOption(":http-user-agent=$VLC_USER_AGENT")
                addOption(":network-caching=$buffering")
                addOption(":live-caching=$buffering")
                addOption(":http-reconnect")
                if (decodeMode >= 2) {
                    // 弱网/长链路时让 HLS 选最低码率的那条流，先保证能出声再谈音质
                    addOption(":adaptive-logic=lowest")
                }
            }
            player.media = media
            media.release()
            player.play()

            val status = when (decodeMode) {
                0 -> "正在连接..."
                1 -> "正在连接(软解)..."
                else -> "正在连接(低码率)..."
            }
            updateNotification(status)
            broadcastStatus(status)
        } catch (e: Exception) {
            Log.e(TAG, "LibVLC 播放启动异常: ${e.message}")
            handleVlcPlaybackError()
        }
    }

    /**
     * 播放异常自愈。
     *
     * 思路是“先信 probe，再换解码器”，绝不再去猜容器格式：
     *   0 档：硬解优先，容器交给 probe（默认）
     *   1 档：硬解解不动的编码 → 全软解（libavcodec）
     *   2 档：软解仍失败 → 软解 + 只选最低码率 + 缩短缓冲（弱网友好）
     *   之后：延时 3 秒回到 0 档，把源端临时故障当作可恢复，而不是判死刑
     *
     * 历史教训：旧实现在这里强制 `:demux=ts`。一旦首轮因网络抖动报了错，
     * 后续两三档就把 HLS / MP3 / AAC 源全部按 TS 解析，必然失败，
     * 于是所有非 TS 源都表现为“一直重连、怎么点都播不了”。
     */
    private fun handleVlcPlaybackError() {
        // libvlc 在解封装/网络同时失败时可能连续抛多个 EncounteredError，
        // 若不防抖，档位会被瞬间打满并开始空转。
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorHandledAt < 2500L) return
        lastErrorHandledAt = now

        val channel = playlistManager.getCurrentChannel() ?: return
        val cleanUrl = RadioRepository.sanitizeUrl(channel.streamUrl)
        val nextMode = playbackMode + 1

        if (nextMode > 2) {
            val status = "该源暂时无法播放，正在重连..."
            updateNotification(status)
            broadcastStatus(status)
            mainHandler.postDelayed({
                playbackMode = 0
                playWithLibVlc(cleanUrl, 0)
            }, 3000L)
            return
        }

        playbackMode = nextMode
        Log.d(TAG, "第 $nextMode 档解码策略重试: $cleanUrl")
        playWithLibVlc(cleanUrl, nextMode)
    }

    fun isCurrentlyPlaying(): Boolean {
        return vlcMediaPlayer?.isPlaying == true
    }

    fun togglePlayback() {
        if (isCurrentlyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        vlcMediaPlayer?.pause()
        val status = "已暂停"
        updateNotification(status)
        broadcastStatus(status)
    }

    fun resumePlayback() {
        requestAudioFocus()
        vlcMediaPlayer?.play()
        val status = "正在播放"
        updateNotification(status)
        broadcastStatus(status)
    }

    /**
     * 停止播放。
     * @param abandonFocus 是否同时放弃音频焦点。内部切台时应传 false，
     * 避免自行释放刚申请到的焦点，导致导航播报打断后无法恢复。
     */
    fun stopAllPlayback(abandonFocus: Boolean = true) {
        if (abandonFocus) {
            abandonAudioFocus()
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

        // 先释放会话，再释放会话外壳与 LibVLC
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaSession = null

        try {
            sessionShellPlayer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        vlcMediaPlayer?.release()
        libVLC?.release()
        vlcMediaPlayer = null
        libVLC = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
