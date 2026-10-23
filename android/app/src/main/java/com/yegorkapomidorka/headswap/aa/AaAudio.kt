package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbWriter

/** Куда падает звук от AA: чистый PCM16 LE без AAP-заголовков и timestamp. chan = канал AAP (4 SPEECH, 5 SYSTEM, 6 MEDIA). */
interface AaAudioSink {
    /** Телефон открыл аудио-канал (MEDIA_START). */
    fun onAudioStart(chan: Int)

    fun onAudioData(chan: Int, pcm: ByteArray, ptsUs: Long)

    /** Телефон закрыл аудио-канал (MEDIA_STOP). */
    fun onAudioStop(chan: Int)

    /** VOICE_SESSION_NOTIFICATION телефона (голосовая сессия Ассистента): status 1 = началась, 2 = закончилась. */
    fun onVoiceSession(status: Int) {}
}

/**
 * Аудио CarLife (MD -> HU), по коду MD Baidu CarLife 8.6.7 (re/carlife-8.6.7/sources/com/baidu/carlife/core):
 *  - каналы: 3 MEDIA, 4 TTS; заголовок 12 байт [len u32][time u32][type u32] (audio/PCMPackageHead.java:33-65);
 *  - MEDIA: INIT 0x30001 (CommonParams.java:428), STOP 0x30002 (:432), PAUSE 0x30003 (:429), RESUME 0x30004 (:430), DATA 0x30006 (:426);
 *  - TTS: INIT 0x40001 (:538), END/STOP 0x40002 (:537), DATA 0x40003 (:536);
 *  - тело INIT: CarlifeMusicInit / CarlifeTTSInit {sampleRate=1, channelConfig=2 (число каналов), sampleFormat=3 (разрядность)}
 *    (PCMPackageHead.java:62-92; audio/PCMMediaManager.java:188-211, audio/NaviAudioManager.java:36-57);
 *  - в DATA time = System.currentTimeMillis() & 0xFFFFFFFF (PCMPackageHead.java:49-56), в INIT/STOP время не ставится.
 */
object CarlifeAudio {
    const val CH_MEDIA = 3
    const val CH_TTS = 4

    const val MEDIA_INIT = 0x00030001
    const val MEDIA_STOP = 0x00030002
    const val MEDIA_PAUSE = 0x00030003
    const val MEDIA_RESUME = 0x00030004
    const val MEDIA_DATA = 0x00030006
    const val TTS_INIT = 0x00040001
    const val TTS_STOP = 0x00040002
    const val TTS_DATA = 0x00040003

    /** Канал VR MD->HU: звук ответа голосового помощника (docs/carlife-protocol.md §7.2), INIT как у MEDIA. */
    const val CH_VR = 5
    const val VR_INIT = 0x00050002
    const val VR_DATA = 0x00050003
    const val VR_STOP = 0x00050004

    /** moduleID в MODULE_STATUS (ModuleStatusManage): 2 NAVI (перед TTS), 3 MUSIC (MEDIA играет). */
    const val MODULE_NAVI = 2
    const val MODULE_MUSIC = 3
    const val MODULE_VR = 4

    /** MAX_MUSIC_DATA_SIZE / MAX_TTS_DATA_SIZE (audio/AudioUtil.java:14-15); кратно 4, не рвёт PCM-кадр. */
    const val MAX_CHUNK = 10240

    /** Режим AUDIO_TRANSMISSION_MODE = 1 -> звук по Bluetooth (AudioUtil.java:109-112,152-154; FeatureConfigManager.java:254-258). */
    const val AUDIO_MODE_BT = 1

    fun initType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_INIT else TTS_INIT
    fun stopType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_STOP else TTS_STOP
    fun dataType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_DATA else TTS_DATA

    /** peak (макс. |сэмпл|) и RMS PCM16 LE: для диагностики, не нули ли в данных. */
    fun pcm16Stats(pcm: ByteArray): Pair<Int, Double> {
        val n = pcm.size / 2
        if (n == 0) return 0 to 0.0
        var peak = 0
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSq += s.toDouble() * s
        }
        return peak to Math.sqrt(sumSq / n)
    }

    fun initBody(sampleRate: Int, channels: Int, bits: Int): ByteArray =
        PbWriter().int32(1, sampleRate).int32(2, channels).int32(3, bits).toByteArray()
}
