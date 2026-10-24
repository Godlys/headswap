package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Экспериментальная настройка «Прореживание тача» (раздел для разработчиков): сколько одиночных MOVE одного пальца
 * мост пропускает перед отправкой в AA. ×2 = уходит каждый второй (см. [TouchDecimator]). Явное значение действует
 * на любой ГУ, по умолчанию выкл. Мост читает значение на каждое событие.
 */
enum class TouchDecimation(val titleRes: Int, val every: Int) {
    OFF(R.string.tdec_off, 1),
    X2(R.string.tdec_x2, 2);

    companion object {
        val DEFAULT = OFF

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): TouchDecimation = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
