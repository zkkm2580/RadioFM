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

    private var activeChannel: RadioChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentChannel = findViewById(R.id.tv_current_channel)
        btnToggle = findViewById(R.id.btn_toggle_play)
        btnStop = findViewById(R.id.btn_stop)
        rvChannels = findViewById(R.id.rv_channels)

        val channels = RadioRepository.getPresetChannels()
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = ChannelAdapter(channels) { channel ->
            activeChannel = channel
            tvCurrentChannel.text = "${channel.name} (${channel.frequency})"
            RadioPlayerService.play(this, channel)
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
