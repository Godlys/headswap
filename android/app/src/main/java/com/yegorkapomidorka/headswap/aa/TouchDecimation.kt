package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 实验性设置「触摸点稀疏化」（开发者选项）：在发送到 Android Auto 之前，桥接器每多少个单指 MOVE 事件放行一个。
 * ×2 = 每第二个才发送（参见 [TouchDecimator]）。显式设置适用于任何 head unit，默认关闭。
 * 桥接器在每次事件时读取该值。
 */
enum class TouchDecimation(val titleRes: Int, val every: Int) {
    OFF(R.string.tdec_off, 1),
    X2(R.string.tdec_x2, 2);

    companion object {
        val DEFAULT = OFF

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): TouchDecimation = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
