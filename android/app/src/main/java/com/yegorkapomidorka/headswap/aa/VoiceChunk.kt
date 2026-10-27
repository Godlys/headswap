package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 实验性设置「语音数据包大小」（开发者选项）：在发送 CarLife 之前，桥接器把语音 PCM（VR 中的助手回复、
 * TTS 中的导航提示）切成多大的块。自动 = VW CNS2 Delphi (h425) 车机为 1024 字节，其余车机按 Android Auto 原始输出
 * （最大 CarlifeAudio.MAX_CHUNK）。大小必须是 2 的倍数（单声道 PCM16）。
 * 桥接器对每个语音包都读取该值，修改立即生效。
 */
enum class VoiceChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.vchunk_auto, null),
    B2048(R.string.vchunk_2048, 2048),
    B1024(R.string.vchunk_1024, 1024),
    B512(R.string.vchunk_512, 512);

    companion object {
        val DEFAULT = AUTO

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): VoiceChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
