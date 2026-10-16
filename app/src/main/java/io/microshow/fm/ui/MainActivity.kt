package io.microshow.fm.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RadioPlayerService.EXTRA_STATUS) ?: return
            val channelName = activeChannel?.name ?: "畅听电台"
            tvCurrentChannel.text = "$channelName · $status"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentChannel = findViewById(R.id.tv_current_channel)
        btnToggle = findViewById(R.id.btn_toggle_play)
        btnStop = findViewById(R.id.btn_stop)
        rvChannels = findViewById(R.id.rv_channels)

        // 1. 初始化预设电台
        val presetChannels = RadioRepository.getPresetChannels()
        channelAdapter = ChannelAdapter(presetChannels) { channel ->
            activeChannel = channel
            tvCurrentChannel.text = "${channel.name} · 正在连接..."
            RadioPlayerService.play(this, channel)
        }
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter

        // 2. 异步拉取 http://zj.537224.xyz/radio.txt 最新在线电台源，自动同步更新列表
        RadioRepository.fetchOnlineChannels { onlineChannels ->
            runOnUiThread {
                channelAdapter.updateChannels(onlineChannels)
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

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
