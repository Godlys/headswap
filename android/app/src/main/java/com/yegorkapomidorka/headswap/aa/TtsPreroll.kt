package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Экспериментальная настройка «Тишина перед подсказкой» (раздел для разработчиков): сколько миллисекунд нулей мост
 * шлёт в CarLife сразу после TTS_INIT, чтобы ГУ успела открыть звук до начала речи. Авто = 400 мс на VW
 * (20082120/20082122/20082134, h222), на остальных ГУ нет. Явное значение действует на любой ГУ, «Нет» = выключено.
 * Мост читает значение на каждый TTS_INIT.
 */
enum class TtsPreroll(val titleRes: Int, val ms: Int?) {
    AUTO(R.string.tpreroll_auto, null),
    MS0(R.string.tpreroll_0, 0),
    MS100(R.string.tpreroll_100, 100),
    MS200(R.string.tpreroll_200, 200),
    MS400(R.string.tpreroll_400, 400);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): TtsPreroll = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
