package io.microshow.fm.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.microshow.fm.R
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel
import io.microshow.fm.player.RadioPlayerService
import io.microshow.fm.util.WeatherLocationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FM网络收音机 主界面
 * 参考 VLC (VideoPlayerActivity + PlayerKeyListenerDelegate) 架构进行车机按键分发与换台
 */
class MainActivity : AppCompatActivity(), PlayerKeyListenerDelegate.KeycodeListener {

    private lateinit var tvCurrentChannel: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvWeather: TextView
    private lateinit var rvChannels: RecyclerView
    private lateinit var channelAdapter: ChannelAdapter

    private var activeChannel: RadioChannel? = null
    private var currentChannels: List<RadioChannel> = emptyList()

    // 实时定位与天气辅助类
    private lateinit var weatherLocationHelper: WeatherLocationHelper

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            weatherLocationHelper.refresh(force = true)
        }
    }

    // VLC 风格的专用按键监听委托
    private lateinit var playerKeyListenerDelegate: PlayerKeyListenerDelegate
    private var switchToast: Toast? = null

    // 实时时钟（原“播放 / 暂停 / 停止”按钮位置）
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val clockRunnable = object : Runnable {
        override fun run() {
            tvClock.text = clockFormat.format(Date())
            // 对齐到下一秒，避免秒数漂移
            clockHandler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

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
            } else {
                tvCurrentChannel.text = "FM网络收音机 · $status"
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

        playerKeyListenerDelegate = PlayerKeyListenerDelegate(this)

        tvCurrentChannel = findViewById(R.id.tv_current_channel)
        tvClock = findViewById(R.id.tv_clock)
        tvLocation = findViewById(R.id.tv_location)
        tvWeather = findViewById(R.id.tv_weather)
        rvChannels = findViewById(R.id.rv_channels)

        // 1. 初始化定位与天气辅助类
        weatherLocationHelper = WeatherLocationHelper(this) { loc, weather ->
            tvLocation.text = loc
            tvWeather.text = weather
        }

        // 请求定位权限（若尚未授权）
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        // 2. 初始化预设电台
        currentChannels = RadioRepository.getPresetChannels()
        channelAdapter = ChannelAdapter(currentChannels) { channel, index ->
            activeChannel = channel
            tvCurrentChannel.text = "${channel.name} · 正在连接..."
            RadioPlayerService.play(this, channel, currentChannels)
        }
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter

        // 3. 异步拉取最新在线电台源并同步至播放服务
        RadioRepository.fetchOnlineChannels { onlineChannels ->
            runOnUiThread {
                currentChannels = onlineChannels
                channelAdapter.updateChannels(onlineChannels)
                RadioPlayerService.updatePlaylist(this, onlineChannels)
            }
        }

        // 原“播放 / 暂停”“停止”按钮已替换为实时时钟；
        // 保留点按 = 播放/暂停、长按 = 停止，避免失去触屏控制入口
        tvClock.setOnClickListener {
            RadioPlayerService.togglePause(this)
        }

        tvClock.setOnLongClickListener {
            RadioPlayerService.stop(this)
            tvCurrentChannel.text = "已停止播放"
            true
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
        startClock()
        weatherLocationHelper.refresh()
    }

    override fun onPause() {
        stopClock()
        super.onPause()
    }

    private fun startClock() {
        clockHandler.removeCallbacks(clockRunnable)
        clockRunnable.run()
    }

    private fun stopClock() {
        clockHandler.removeCallbacks(clockRunnable)
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
        weatherLocationHelper.stop()
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
