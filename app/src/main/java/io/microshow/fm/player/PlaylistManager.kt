package io.microshow.fm.player

import android.util.Log
import io.microshow.fm.data.RadioRepository
import io.microshow.fm.model.RadioChannel

/**
 * 参考 VLC (org.videolan.vlc.media.PlaylistManager) 实现的播放列表条目管理器
 * 核心逻辑：上下键换台实质上是切换播放列表条目（PlaylistItem）
 */
class PlaylistManager {

    companion object {
        private const val TAG = "PlaylistManager"
    }

    private val channelList = ArrayList<RadioChannel>()
    private var currentIndex = -1

    init {
        // 初始填充预设电台列表
        channelList.addAll(RadioRepository.getPresetChannels())
    }

    @Synchronized
    fun setPlaylist(list: List<RadioChannel>) {
        if (list.isEmpty()) return
        val currentChannel = getCurrentChannel()
        channelList.clear()
        channelList.addAll(list)

        // 重新矫正当前频道的索引
        if (currentChannel != null) {
            val found = channelList.indexOfFirst {
                it.streamUrl == currentChannel.streamUrl || it.name == currentChannel.name
            }
            if (found >= 0) {
                currentIndex = found
            }
        }
    }

    @Synchronized
    fun getPlaylist(): List<RadioChannel> = ArrayList(channelList)

    @Synchronized
    fun getCurrentIndex(): Int = currentIndex

    @Synchronized
    fun getCurrentChannel(): RadioChannel? {
        return if (currentIndex in channelList.indices) {
            channelList[currentIndex]
        } else {
            null
        }
    }

    @Synchronized
    fun hasNext(): Boolean = channelList.isNotEmpty()

    @Synchronized
    fun hasPrevious(): Boolean = channelList.isNotEmpty()

    /**
     * 切换到下一台（参考 VLC next(force: Boolean)）
     * 电台播放场景下默认循环列表
     */
    @Synchronized
    fun next(force: Boolean = true): RadioChannel? {
        if (channelList.isEmpty()) {
            channelList.addAll(RadioRepository.getPresetChannels())
        }
        if (channelList.isEmpty()) return null

        val nextIndex = (currentIndex + 1) % channelList.size
        currentIndex = nextIndex
        val target = channelList[currentIndex]
        Log.d(TAG, "VLC模式换下一台: index=$currentIndex, name=${target.name}")
        return target
    }

    /**
     * 切换到上一台（参考 VLC previous(force: Boolean)）
     * 在电台直播流场景下，直播流不可 seek，因此永远切到上一台（即 force=true），不执行 5 秒回到开头
     */
    @Synchronized
    fun previous(force: Boolean = true): RadioChannel? {
        if (channelList.isEmpty()) {
            channelList.addAll(RadioRepository.getPresetChannels())
        }
        if (channelList.isEmpty()) return null

        val prevIndex = if (currentIndex <= 0) channelList.size - 1 else currentIndex - 1
        currentIndex = prevIndex
        val target = channelList[currentIndex]
        Log.d(TAG, "VLC模式换上一台: index=$currentIndex, name=${target.name}")
        return target
    }

    /**
     * 指定频道或索引播放
     */
    @Synchronized
    fun selectChannel(channel: RadioChannel): Int {
        val found = channelList.indexOfFirst {
            it.streamUrl == channel.streamUrl || it.name == channel.name
        }
        currentIndex = if (found >= 0) {
            found
        } else {
            channelList.add(channel)
            channelList.size - 1
        }
        return currentIndex
    }

    @Synchronized
    fun selectIndex(index: Int): RadioChannel? {
        if (index in channelList.indices) {
            currentIndex = index
            return channelList[index]
        }
        return null
    }
}
