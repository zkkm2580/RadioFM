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
