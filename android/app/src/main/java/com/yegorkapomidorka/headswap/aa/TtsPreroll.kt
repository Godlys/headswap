package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 实验性设置「提示音前静音」（开发者选项）：TTS_INIT 之后，桥接器立即向 CarLife 发送多少毫秒的零数据，
 * 让车机在语音开始前来得及打开音频。自动 = VW（20082120/20082122/20082134, h222）车机为 400 ms，其余车机无。
 * 显式设置适用于任何车机，「无」= 关闭。桥接器在每次 TTS_INIT 时读取该值。
 */
enum class TtsPreroll(val titleRes: Int, val ms: Int?) {
    AUTO(R.string.tpreroll_auto, null),
    MS0(R.string.tpreroll_0, 0),
    MS100(R.string.tpreroll_100, 100),
    MS200(R.string.tpreroll_200, 200),
    MS400(R.string.tpreroll_400, 400);

    companion object {
        val DEFAULT = AUTO

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): TtsPreroll = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
