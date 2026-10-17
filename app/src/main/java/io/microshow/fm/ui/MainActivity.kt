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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.player.RadioPlayerService

/**
 * 畅听FM 主界面
 * 参考 VLC (VideoPlayerActivity + PlayerKeyListenerDelegate) 架构进行车机按键分发与换台
 */
class MainActivity : AppCompatActivity(), PlayerKeyListenerDelegate.KeycodeListener {

    private lateinit var tvCurrentChannel: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnStop: Button
    private lateinit var rvChannels: RecyclerView
    private lateinit var channelAdapter: ChannelAdapter

    private var activeChannel: RadioChannel? = null
    private var currentChannels: List<RadioChannel> = emptyList()

    // VLC 风格的专用按键监听委托
    private lateinit var playerKeyListenerDelegate: PlayerKeyListenerDelegate
    private var switchToast: Toast? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RadioPlayerService.EXTRA_STATUS) ?: return
            val index = intent.getIntExtra(RadioPlayerService.EXTRA_CURRENT_INDEX, -1)
            val isPlaying = intent.getBooleanExtra(RadioPlayerService.EXTRA_IS_PLAYING, false)
            val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(RadioPlayerService.EXTRA_CHANNEL, RadioChannel::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(RadioPlayerService.EXTRA_CHANNEL) as? RadioChannel
            }

            if (channel != null) {
                activeChannel = channel
                tvCurrentChannel.text = "${channel.name} · $status"
            } else {
                tvCurrentChannel.text = "畅听FM · $status"
            }

            btnToggle.text = if (isPlaying) "暂停" else "播放"

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

        playerKeyListenerDelegate = PlayerKeyListenerDelegate(this)

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
            btnToggle.text = "播放"
        }

        val filter = IntentFilter(RadioPlayerService.ACTION_STATUS_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台主动同步一次 Service 的最新播放信息
        RadioPlayerService.requestStatus(this)
    }

    // =========================================================================
    // 参考 VLC 按键委托机制（PlayerKeyListenerDelegate.KeycodeListener 回调）
    // =========================================================================

    override fun next(): Boolean {
        showSwitchOverlay("下一台")
        RadioPlayerService.next(this)
        return true
    }

    override fun previous(): Boolean {
        showSwitchOverlay("上一台")
        RadioPlayerService.prev(this)
        return true
    }

    override fun play(): Boolean {
        RadioPlayerService.togglePause(this)
        return true
    }

    override fun pause(): Boolean {
        RadioPlayerService.togglePause(this)
        return true
    }

    override fun togglePause(): Boolean {
        RadioPlayerService.togglePause(this)
        return true
    }

    private fun showSwitchOverlay(actionText: String) {
        try {
            switchToast?.cancel()
            switchToast = Toast.makeText(this, "📻 $actionText", Toast.LENGTH_SHORT)
            switchToast?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 第一优先级：在 dispatchKeyEvent 阶段直接由 PlayerKeyListenerDelegate 拦截
     * 解决某些车机控件（如获得焦点的 RecyclerView）吞掉按键的问题
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (playerKeyListenerDelegate.onKeyDown(event.keyCode, event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 第二优先级：兜底 onKeyDown（与 VLC VideoPlayerActivity.onKeyDown 对齐）
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playerKeyListenerDelegate.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
