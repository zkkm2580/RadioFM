package io.microshow.fm.data

import io.microshow.fm.model.RadioChannel

object RadioRepository {

    fun getPresetChannels(): List<RadioChannel> = listOf(
        RadioChannel(
            id = "cnr_1",
            name = "中央人民广播电台 - 中国之声",
            category = "国家台",
            frequency = "FM 106.1",
            streamUrl = "http://ngcdn001.cnr.cn/live/zgzs/index.m3u8"
        ),
        RadioChannel(
            id = "cnr_2",
            name = "中央人民广播电台 - 经济之声",
            category = "国家台",
            frequency = "FM 96.6",
            streamUrl = "http://ngcdn002.cnr.cn/live/jjzs/index.m3u8"
        ),
        RadioChannel(
            id = "cnr_3",
            name = "中央人民广播电台 - 音乐之声",
            category = "国家台",
            frequency = "FM 90.0",
            streamUrl = "http://ngcdn003.cnr.cn/live/yyzs/index.m3u8"
        ),
        RadioChannel(
            id = "cnr_8",
            name = "中央人民广播电台 - 经典音乐广播",
            category = "国家台",
            frequency = "FM 101.8",
            streamUrl = "http://ngcdn008.cnr.cn/live/jdyygb/index.m3u8"
        ),
        RadioChannel(
            id = "cnr_10",
            name = "中国交通广播",
            category = "国家台",
            frequency = "FM 99.6",
            streamUrl = "http://ngcdn010.cnr.cn/live/zgjtgb/index.m3u8"
        ),
        RadioChannel(
            id = "bj_news",
            name = "北京广播电视台 - 新闻广播",
            category = "省市台",
            frequency = "FM 94.5",
            streamUrl = "http://listen.rbc.cn/bjnews.m3u8"
        ),
        RadioChannel(
            id = "bj_music",
            name = "北京广播电视台 - 音乐广播",
            category = "省市台",
            frequency = "FM 97.4",
            streamUrl = "http://listen.rbc.cn/bjmusic.m3u8"
        ),
        RadioChannel(
            id = "bj_traffic",
            name = "北京广播电视台 - 交通广播",
            category = "省市台",
            frequency = "FM 103.9",
            streamUrl = "http://listen.rbc.cn/traffic.m3u8"
        ),
        RadioChannel(
            id = "sh_traffic",
            name = "上海交通广播",
            category = "省市台",
            frequency = "FM 105.7",
            streamUrl = "http://live-hls.eastradio.com/traffic/playlist.m3u8"
        ),
        RadioChannel(
            id = "gd_news",
            name = "广东广播电视台 - 新闻广播",
            category = "省市台",
            frequency = "FM 91.4",
            streamUrl = "http://lvod.itv.mgtv.com/live/gd_news.m3u8"
        )
    )
}
