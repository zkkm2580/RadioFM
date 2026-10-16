package io.microshow.fm.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.player.RadioPlayerService

class MainActivity : AppCompatActivity() {

    private lateinit var tvCurrentChannel: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnStop: Button
    private lateinit var rvChannels: RecyclerView
    private lateinit var channelAdapter: ChannelAdapter

    private var activeChannel: RadioChannel? = null
    private var currentChannels: List<RadioChannel> = emptyList()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RadioPlayerService.EXTRA_STATUS) ?: return
            val index = intent.getIntExtra(RadioPlayerService.EXTRA_CURRENT_INDEX, -1)
            val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(RadioPlayerService.EXTRA_CHANNEL, RadioChannel::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(RadioPlayerService.EXTRA_CHANNEL) as? RadioChannel
            }

            if (channel != null) {
                activeChannel = channel
                tvCurrentChannel.text = "${channel.name} · $status"
            }
            if (index >= 0) {
                channelAdapter.setSelectedIndex(index)
                rvChannels.smoothScrollToPosition(index)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置夜间模式自动跟随系统
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentChannel = findViewById(R.id.tv_current_channel)
        btnToggle = findViewById(R.id.btn_toggle_play)
        btnStop = findViewById(R.id.btn_stop)
        rvChannels = findViewById(R.id.rv_channels)

        // 1. 初始化预设电台
        currentChannels = RadioRepository.getPresetChannels()
        channelAdapter = ChannelAdapter(currentChannels) { channel, index ->
            activeChannel = channel
            tvCurrentChannel.text = "${channel.name} · 正在连接..."
            RadioPlayerService.play(this, channel, currentChannels)
        }
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter

        // 2. 异步拉取最新在线电台源并同步至播放服务
        RadioRepository.fetchOnlineChannels { onlineChannels ->
            runOnUiThread {
                currentChannels = onlineChannels
                channelAdapter.updateChannels(onlineChannels)
                RadioPlayerService.updatePlaylist(this, onlineChannels)
            }
        }

        btnToggle.setOnClickListener {
            RadioPlayerService.togglePause(this)
        }

        btnStop.setOnClickListener {
            RadioPlayerService.stop(this)
            tvCurrentChannel.text = "已停止播放"
        }

        val filter = IntentFilter(RadioPlayerService.ACTION_STATUS_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    /**
     * 拦截车载方向盘物理按键、车载旋钮与蓝牙媒体切台按键
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    RadioPlayerService.next(this)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    RadioPlayerService.prev(this)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    RadioPlayerService.togglePause(this)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
