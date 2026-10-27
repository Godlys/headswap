package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 实验性设置「音乐数据包大小」（开发者选项）：在发送 CarLife MEDIA_DATA 之前，桥接器把音乐 PCM 切成多大的块。
 * 自动 = 按 Android Auto 原始输出（最大 CarlifeAudio.MAX_CHUNK）。大小必须是 4 的倍数（立体声 PCM16 帧）。
 * 桥接器对每个音乐缓冲都读取该值，修改立即生效。
 */
enum class MediaChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.mchunk_auto, null),
    B4096(R.string.mchunk_4096, 4096),
    B2560(R.string.mchunk_2560, 2560),
    B2048(R.string.mchunk_2048, 2048),
    B1024(R.string.mchunk_1024, 1024);

    companion object {
        val DEFAULT = AUTO

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): MediaChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
