package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 「音乐采样率」设置：自动（48 kHz，Hyundai CN7 车机为 32 kHz）或为任意车机指定固定值。
 * 桥接器在 MEDIA INIT 时固定采样率，因此修改从下一次 INIT（重新连接或新曲目）开始生效。
 */
enum class MusicRate(val titleRes: Int, val hz: Int?) {
    AUTO(R.string.mrate_auto, null),
    HZ48(R.string.mrate_48, 48000),
    HZ44(R.string.mrate_44, 44100),
    HZ32(R.string.mrate_32, 32000),
    HZ24(R.string.mrate_24, 24000),
    HZ22(R.string.mrate_22, 22050);

    companion object {
        val DEFAULT = AUTO

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): MusicRate = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
