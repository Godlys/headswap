package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Экспериментальная настройка «Размер пакета голоса» (раздел для разработчиков): на какие куски мост режет PCM речи
 * (ответ ассистента в VR, навигационные подсказки в TTS) перед CarLife. Авто = 1024 Б на VW CNS2 Delphi (h425),
 * на остальных ГУ как отдаёт Android Auto (до CarlifeAudio.MAX_CHUNK). Размеры кратны 2 (моно PCM16).
 * Мост читает значение на каждый пакет речи, смена действует сразу.
 */
enum class VoiceChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.vchunk_auto, null),
    B2048(R.string.vchunk_2048, 2048),
    B1024(R.string.vchunk_1024, 1024),
    B512(R.string.vchunk_512, 512);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): VoiceChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
