package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Экспериментальная настройка «Размер пакета музыки» (раздел для разработчиков): на какие куски мост режет PCM музыки
 * перед CarLife MEDIA_DATA. Авто = как отдаёт Android Auto (до CarlifeAudio.MAX_CHUNK). Размеры кратны 4 (кадр стерео PCM16).
 * Мост читает значение на каждый буфер музыки, смена действует сразу.
 */
enum class MediaChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.mchunk_auto, null),
    B4096(R.string.mchunk_4096, 4096),
    B2560(R.string.mchunk_2560, 2560),
    B2048(R.string.mchunk_2048, 2048),
    B1024(R.string.mchunk_1024, 1024);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): MediaChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
