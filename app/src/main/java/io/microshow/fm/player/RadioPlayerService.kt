package io.microshow.fm.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.microshow.fm.R
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.ui.MainActivity

class RadioPlayerService : Service() {

    private lateinit var player: ExoPlayer
    private var currentChannel: RadioChannel? = null

    companion object {
        const val ACTION_PLAY = "io.microshow.fm.ACTION_PLAY"
        const val ACTION_TOGGLE_PAUSE = "io.microshow.fm.ACTION_TOGGLE_PAUSE"
        const val ACTION_STOP = "io.microshow.fm.ACTION_STOP"
        const val EXTRA_CHANNEL = "extra_channel"
        private const val CHANNEL_ID = "radio_playback_channel"
        private const val NOTIFICATION_ID = 1001

        fun play(context: Context, channel: RadioChannel) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_CHANNEL, channel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
    }

    override fun onCreate() {
        super.onCreate()
        initPlayer()
        createNotificationChannel()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val status = when (state) {
                    Player.STATE_BUFFERING -> "正在缓冲..."
                    Player.STATE_READY -> if (player.isPlaying) "正在播放" else "已暂停"
                    Player.STATE_ENDED -> "播放结束"
                    else -> "就绪"
                }
                updateNotification(status)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification(if (isPlaying) "正在播放" else "已暂停")
            }

            override fun onPlayerError(error: PlaybackException) {
                updateNotification("连接重试中...")
                player.prepare()
                player.play()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val channel = intent.getSerializableExtra(EXTRA_CHANNEL) as? RadioChannel
                channel?.let { playChannel(it) }
            }
            ACTION_TOGGLE_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            ACTION_STOP -> {
                player.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun playChannel(channel: RadioChannel) {
        currentChannel = channel
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        val notification = buildNotification("正在连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

        val toggleIntent = Intent(this, RadioPlayerService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pToggle = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(channelName)
            .setContentText("$freq · $statusText")
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pOpenApp)
            .addAction(
                if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (player.isPlaying) "暂停" else "播放",
                pToggle
            )
            .setOngoing(player.isPlaying)
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
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
