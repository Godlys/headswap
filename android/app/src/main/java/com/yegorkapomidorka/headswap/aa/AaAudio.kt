package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbWriter

/** Android Auto 音频的输出目标：不带 AAP 头和时间戳的纯 PCM16 LE。chan = AAP 通道（4 SPEECH、5 SYSTEM、6 MEDIA）。 */
interface AaAudioSink {
    /** 手机打开了音频通道（MEDIA_START）。 */
    fun onAudioStart(chan: Int)

    fun onAudioData(chan: Int, pcm: ByteArray, ptsUs: Long)

    /** 手机关闭了音频通道（MEDIA_STOP）。 */
    fun onAudioStop(chan: Int)

    /** 手机的 VOICE_SESSION_NOTIFICATION（助手语音会话）：status 1 = 已开始，2 = 已结束。 */
    fun onVoiceSession(status: Int) {}
}

/**
 * CarLife 音频（MD → HU），依据百度 CarLife 8.6.7 MD 代码（re/carlife-8.6.7/sources/com/baidu/carlife/core）：
 *  - 通道：3 MEDIA、4 TTS；头部 12 字节 [len u32][time u32][type u32]（audio/PCMPackageHead.java:33-65）；
 *  - MEDIA：INIT 0x30001（CommonParams.java:428）、STOP 0x30002（:432）、PAUSE 0x30003（:429）、RESUME 0x30004（:430）、DATA 0x30006（:426）；
 *  - TTS：INIT 0x40001（:538）、END/STOP 0x40002（:537）、DATA 0x40003（:536）；
 *  - INIT 消息体：CarlifeMusicInit / CarlifeTTSInit {sampleRate=1, channelConfig=2（声道数）, sampleFormat=3（位深）}
 *    （PCMPackageHead.java:62-92；audio/PCMMediaManager.java:188-211、audio/NaviAudioManager.java:36-57）；
 *  - DATA 中 time = System.currentTimeMillis() & 0xFFFFFFFF（PCMPackageHead.java:49-56），INIT/STOP 不写时间。
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

    /** VR 通道 MD→HU：语音助手回复的声音（docs/carlife-protocol.md §7.2），INIT 与 MEDIA 相同。 */
    const val CH_VR = 5
    const val VR_INIT = 0x00050002
    const val VR_DATA = 0x00050003
    const val VR_STOP = 0x00050004

    /** MODULE_STATUS（ModuleStatusManage）中的 moduleID：2 NAVI（TTS 之前），3 MUSIC（MEDIA 播放中）。 */
    const val MODULE_NAVI = 2
    const val MODULE_MUSIC = 3
    const val MODULE_VR = 4

    /** MAX_MUSIC_DATA_SIZE / MAX_TTS_DATA_SIZE（audio/AudioUtil.java:14-15）；为 4 的倍数，不会撕裂 PCM 帧。 */
    const val MAX_CHUNK = 10240

    /** AUDIO_TRANSMISSION_MODE = 1 → 声音经蓝牙传输（AudioUtil.java:109-112,152-154；FeatureConfigManager.java:254-258）。 */
    const val AUDIO_MODE_BT = 1

    fun initType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_INIT else TTS_INIT
    fun stopType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_STOP else TTS_STOP
    fun dataType(carlifeChan: Int) = if (carlifeChan == CH_MEDIA) MEDIA_DATA else TTS_DATA

    /** PCM16 LE 的 peak（最大 |采样值|）和 RMS：用于诊断数据是否全为零。 */
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
