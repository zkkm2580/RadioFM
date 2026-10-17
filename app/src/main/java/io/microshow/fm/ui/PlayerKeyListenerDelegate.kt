package io.microshow.fm.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * 参考 VLC (org.videolan.vlc.gui.helpers.PlayerKeyListenerDelegate) 实现的按键监听委托
 * 核心逻辑：
 * 1. 精确拦截 KEYCODE_CHANNEL_UP(166)、KEYCODE_CHANNEL_DOWN(167)、MEDIA_NEXT(87)、MEDIA_PREV(88) 等切台键
 * 2. 避免拦截 KEYCODE_DPAD_UP(19)/DOWN(20)，确保界面列表焦点与滚动正常运行
 * 3. 增加防抖保护，过滤高频重复连击
 */
class PlayerKeyListenerDelegate(private val keycodeListener: KeycodeListener) {

    interface KeycodeListener {
        fun next(): Boolean
        fun previous(): Boolean
        fun play(): Boolean
        fun pause(): Boolean
        fun togglePause(): Boolean
    }

    companion object {
        private const val TAG = "PlayerKeyListener"
        private const val DEBOUNCE_INTERVAL_MS = 250L
    }

    private var lastKeyActionTime = 0L
    private var lastKeyCode = 0

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0 && now - lastKeyActionTime < DEBOUNCE_INTERVAL_MS) {
            // 过滤车机按键的高频重复
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                Log.d(TAG, "VLC键位映射: next() (keyCode=$keyCode)")
                lastKeyActionTime = now
                lastKeyCode = keyCode
                return keycodeListener.next()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                Log.d(TAG, "VLC键位映射: previous() (keyCode=$keyCode)")
                lastKeyActionTime = now
                lastKeyCode = keyCode
                return keycodeListener.previous()
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Log.d(TAG, "VLC键位映射: play() (keyCode=$keyCode)")
                lastKeyActionTime = now
                lastKeyCode = keyCode
                return keycodeListener.play()
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Log.d(TAG, "VLC键位映射: pause() (keyCode=$keyCode)")
                lastKeyActionTime = now
                lastKeyCode = keyCode
                return keycodeListener.pause()
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Log.d(TAG, "VLC键位映射: togglePause() (keyCode=$keyCode)")
                lastKeyActionTime = now
                lastKeyCode = keyCode
                return keycodeListener.togglePause()
            }

            // 特别注意：参考 VLC 源码，DPAD_UP(19)/DPAD_DOWN(20) 用于列表导航，不执行换台
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                return false
            }

            else -> return false
        }
    }
}
