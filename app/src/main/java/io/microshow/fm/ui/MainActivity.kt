package io.microshow.fm.ui

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentChannel = findViewById(R.id.tv_current_channel)
        btnToggle = findViewById(R.id.btn_toggle_play)
        btnStop = findViewById(R.id.btn_stop)
        rvChannels = findViewById(R.id.rv_channels)

        // 1. 初始化预设电台（与 http://zj.537224.xyz/radio.txt 保持一致）
        val presetChannels = RadioRepository.getPresetChannels()
        channelAdapter = ChannelAdapter(presetChannels) { channel ->
            activeChannel = channel
            tvCurrentChannel.text = "${channel.name} (${channel.frequency})"
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
    }
}
