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
     * 默认内置预设频道（来源与 http://zj.537224.xyz/radio.txt 保持一致）
     * 注意：喜马拉雅电台的 443 端口未开放，必须使用 http 协议
     */
    fun getPresetChannels(): List<RadioChannel> = listOf(
        RadioChannel(
            id = "radio_1",
            name = "亚洲粤语台",
            category = "网络电台",
            frequency = "网络台",
            streamUrl = "http://live.ximalaya.com/radio-first-page-app/live/999/64.m3u8"
        ),
        RadioChannel(
            id = "radio_2",
            name = "亚洲音乐台",
            category = "网络电台",
            frequency = "网络台",
            streamUrl = "http://live.ximalaya.com/radio-first-page-app/live/764/64.m3u8"
        ),
        RadioChannel(
            id = "radio_3",
            name = "亚洲经典台",
            category = "网络电台",
            frequency = "网络台",
            streamUrl = "http://live.ximalaya.com/radio-first-page-app/live/1005/64.m3u8"
        ),
        RadioChannel(
            id = "radio_4",
            name = "HD音乐台",
            category = "网络电台",
            frequency = "网络台",
            streamUrl = "http://live.ximalaya.com/radio-first-page-app/live/1427/64.m3u8"
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
     * 自动处理 URL 兼容性（如喜马拉雅 443 端口无法连接，自动转 http）
     */
    fun sanitizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("https://live.ximalaya.com", ignoreCase = true)) {
            url = "http://" + url.substring("https://".length)
        }
        return url
    }

    /**
     * 解析广播源文本：
     * 同时支持：
     * 1. 两行制格式（第一行频道名称，第二行播放URL）
     * 2. 单行逗号格式（频道名称,播放URL）
     * 3. 分组标签（#genre# 或 #group#）
     */
    fun parseRadioText(text: String): List<RadioChannel> {
        val result = mutableListOf<RadioChannel>()
        var currentCategory = "广播电台"
        var pendingName: String? = null
        var index = 1

        BufferedReader(StringReader(text)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#EXT") || line.startsWith("//")) {
                    continue
                }

                // 识别分类/分组
                if (line.contains(",#genre#") || line.contains(",#group#") || (line.startsWith("[") && line.endsWith("]"))) {
                    currentCategory = line.replace(",#genre#", "")
                        .replace(",#group#", "")
                        .removeSurrounding("[", "]")
                        .trim()
                    pendingName = null
                    continue
                }

                // 格式 1：单行逗号制 "频道名称,URL"
                if (line.contains(",") && (line.contains("http://", ignoreCase = true) || line.contains("https://", ignoreCase = true))) {
                    val parts = line.split(",", limit = 2)
                    val name = parts[0].trim()
                    val url = sanitizeUrl(parts[1])
                    if (name.isNotEmpty() && url.startsWith("http", ignoreCase = true)) {
                        result.add(
                            RadioChannel(
                                id = "ch_$index",
                                name = name,
                                category = currentCategory,
                                frequency = "网络广播",
                                streamUrl = url
                            )
                        )
                        index++
                        pendingName = null
                        continue
                    }
                }

                // 格式 2：两行制（当前行为 URL，上一行为名称）
                if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)) {
                    val url = sanitizeUrl(line)
                    val name = pendingName ?: "电台 $index"
                    result.add(
                        RadioChannel(
                            id = "ch_$index",
                            name = name,
                            category = currentCategory,
                            frequency = "网络广播",
                            streamUrl = url
                        )
                    )
                    index++
                    pendingName = null
                } else {
                    // 当前行是频道名称，暂存等待下一行读取 URL
                    pendingName = line
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
