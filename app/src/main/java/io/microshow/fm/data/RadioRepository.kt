package io.microshow.fm.data

import io.microshow.fm.model.RadioChannel
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object RadioRepository {

    const val RADIO_SOURCE_URL = "http://zj.537224.xyz/radio.txt"

    /**
     * 默认内置预设频道（与 http://zj.537224.xyz/radio.txt 保持一致，离线/首次进入时兜底秒开）
     */
    fun getPresetChannels(): List<RadioChannel> = listOf(
        RadioChannel(
            id = "radio_1",
            name = "亚洲粤语台",
            category = "网络电台",
            frequency = "流行音乐",
            streamUrl = "https://live.ximalaya.com/radio-first-page-app/live/999/64.m3u8"
        ),
        RadioChannel(
            id = "radio_2",
            name = "亚洲音乐台",
            category = "网络电台",
            frequency = "流行音乐",
            streamUrl = "https://live.ximalaya.com/radio-first-page-app/live/764/64.m3u8"
        ),
        RadioChannel(
            id = "radio_3",
            name = "亚洲经典台",
            category = "网络电台",
            frequency = "经典怀旧",
            streamUrl = "https://live.ximalaya.com/radio-first-page-app/live/1005/64.m3u8"
        ),
        RadioChannel(
            id = "radio_4",
            name = "HD音乐台",
            category = "网络电台",
            frequency = "高清音频",
            streamUrl = "https://live.ximalaya.com/radio-first-page-app/live/1427/64.m3u8"
        ),
        RadioChannel(
            id = "radio_5",
            name = "中国之声",
            category = "国家台",
            frequency = "FM 106.1",
            streamUrl = "http://dg.881488.xyz:8168/stream/channelid/767929144?auth=PoYyyJYz-DZDGwzaVSzKqA6BhE8o&profile=pass"
        ),
        RadioChannel(
            id = "radio_6",
            name = "经典音乐广播",
            category = "国家台",
            frequency = "FM 101.8",
            streamUrl = "http://dg.881488.xyz:8168/stream/channelid/646411749?auth=PoYyyJYz-DZDGwzaVSzKqA6BhE8o&profile=pass"
        ),
        RadioChannel(
            id = "radio_7",
            name = "经济之声",
            category = "国家台",
            frequency = "FM 96.6",
            streamUrl = "http://dg.881488.xyz:8168/stream/channelid/326940051?auth=PoYyyJYz-DZDGwzaVSzKqA6BhE8o&profile=pass"
        ),
        RadioChannel(
            id = "radio_8",
            name = "音乐之声",
            category = "国家台",
            frequency = "FM 90.0",
            streamUrl = "http://dg.881488.xyz:8168/stream/channelid/1206573996?auth=PoYyyJYz-DZDGwzaVSzKqA6BhE8o&profile=pass"
        )
    )

    /**
     * 解析形如 "频道名称,流地址" 或 带分组 "#genre#" 的 txt 列表
     */
    fun parseRadioText(text: String): List<RadioChannel> {
        val result = mutableListOf<RadioChannel>()
        var currentCategory = "网络电台"
        var index = 1

        BufferedReader(StringReader(text)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#EXT") || line.startsWith("//")) {
                    continue
                }
                if (line.contains(",#genre#") || line.contains(",#group#")) {
                    currentCategory = line.split(",")[0].trim()
                    continue
                }
                val parts = line.split(",", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val url = parts[1].trim()
                    if (name.isNotEmpty() && url.startsWith("http", ignoreCase = true)) {
                        result.add(
                            RadioChannel(
                                id = "online_$index",
                                name = name,
                                category = currentCategory,
                                frequency = "网络广播",
                                streamUrl = url
                            )
                        )
                        index++
                    }
                }
            }
        }
        return result
    }

    /**
     * 异步从指定 URL 拉取最新频道列表
     */
    fun fetchOnlineChannels(urlStr: String = RADIO_SOURCE_URL, onResult: (List<RadioChannel>) -> Unit) {
        thread {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioFM")
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val content = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val channels = parseRadioText(content)
                    if (channels.isNotEmpty()) {
                        onResult(channels)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
