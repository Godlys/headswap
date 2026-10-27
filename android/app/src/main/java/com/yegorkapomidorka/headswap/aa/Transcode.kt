package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB

/** 转码器输出结果的目标（由桥接器核心实现）。调用发生在 MediaCodec 线程上。 */
interface TranscodeOutput {
    /** 编码器的 SPS+PPS（Annex-B，4 字节起始码）。 */
    fun onEncoderConfig(csd: ByteArray)

    /** 编码器帧（Annex-B）。 */
    fun onEncoderFrame(data: ByteArray, key: Boolean)

    fun onTranscodeError(msg: String)

    /** 解码器需要 Android Auto 发来新的 IDR（帧已丢失）。 */
    fun onNeedAaKeyFrame()
}

/**
 * Android Auto H.264 → 解码器（Surface）→ 编码器输入 Surface → 供 CarLife 使用的 H.264。基于 MediaCodec
 * 的实现（AaTranscoder）；桥接器核心面向接口编程，以便逻辑可在 JVM 上测试。
 */
interface VideoTranscoder {
    /**
     * Android Auto 的 CODEC_CONFIG（SPS+PPS）。新的 SPS → 解码器将在下一个 IDR 时重建。
     * 不阻塞，在转码器线程上处理；tuning 中的 "nofeedthread" 是旧的同步路径。
     */
    fun feedConfig(annexB: ByteArray)

    /**
     * Android Auto 帧（access unit）。给解码器的第一帧必须是 IDR。
     * 不阻塞，在转码器线程上处理（队列满 → 帧被丢弃直到 IDR，onNeedAaKeyFrame）；
     * tuning 中的 "nofeedthread" 是旧的同步路径。
     */
    fun feedFrame(annexB: ByteArray, ptsUs: Long)

    /**
     * 重置解码器（新的 Android Auto 会话）：编码器继续存活。不阻塞，在已接收的帧之后于转码器线程上执行；
     * tuning 中的 "nofeedthread" 是旧的同步路径。
     */
    fun resetDecoder()

    fun requestSyncFrame()

    /** 从下一帧开始，由占位帧过渡到 Android Auto 画面（缩放溶解）。没有 GL 则不做任何事。 */
    fun startIntro(banner: android.graphics.Bitmap) {}

    /** 目标输出帧率（0 = 不限制）。 */
    val targetFps: Int get() = 0

    /** 动态更改目标帧率，无需重建编解码器。 */
    fun setTargetFps(fps: Int) {}

    /** 编码器当前比特率，比特/秒（0 = 未知）。 */
    val currentBitrate: Int get() = 0

    /** 动态更改编码器比特率（适配车机排空速度），无需重建编解码器。不抛异常。 */
    fun setBitrate(bps: Int) {}

    /** 自上次调用以来的窗口统计字符串（解码器→编码器延迟、fps、重复帧数）。 */
    fun statsLine(): String

    fun stop()
}

/** 解析 Annex-B H.264 流（纯 JVM 逻辑）：NAL 单元和 access unit。 */
object H264Stream {
    class AccessUnit(val data: ByteArray, val idr: Boolean)

    /** 整个流的 NAL 单元，每个带 4 字节起始码。 */
    fun nalUnits(stream: ByteArray): List<ByteArray> {
        val d = AnnexB.normalize(stream)
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                starts.add(i)
                i += 4
            } else {
                i++
            }
        }
        val res = ArrayList<ByteArray>()
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] else d.size
            res.add(d.copyOfRange(s, e))
        }
        return res
    }

    fun nalType(nal: ByteArray): Int = if (nal.size > 4) nal[4].toInt() and 0x1F else -1

    /** 从配置/流中取 SPS 和 PPS（第一个）。 */
    fun spsPps(stream: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = nalUnits(stream)
        return Pair(nals.firstOrNull { nalType(it) == 7 }, nals.firstOrNull { nalType(it) == 8 })
    }

    /**
     * 将流切分为 access unit：新图像从 first_mb_in_slice == 0 的 slice 开始
     * （NAL 头之后第一个字节的最高位 = 1），或者从 slice 之后的 SPS/PPS/SEI/AUD 开始。
     * 返回（第一帧的 SPS+PPS，AU 列表）。
     */
    fun split(stream: ByteArray): Pair<ByteArray?, List<AccessUnit>> {
        val nals = nalUnits(stream)
        val aus = ArrayList<AccessUnit>()
        var cur = ArrayList<ByteArray>()
        var curVcl = false
        var curIdr = false
        fun flush() {
            if (cur.isNotEmpty() && curVcl) {
                val size = cur.sumOf { it.size }
                val out = ByteArray(size)
                var p = 0
                for (n in cur) {
                    System.arraycopy(n, 0, out, p, n.size)
                    p += n.size
                }
                aus.add(AccessUnit(out, curIdr))
            }
            cur = ArrayList()
            curVcl = false
            curIdr = false
        }
        for (n in nals) {
            val t = nalType(n)
            when (t) {
                1, 5 -> {
                    val firstSlice = n.size > 5 && (n[5].toInt() and 0x80) != 0
                    if (curVcl && firstSlice) flush()
                    cur.add(n)
                    curVcl = true
                    if (t == 5) curIdr = true
                }
                6, 7, 8, 9 -> {
                    if (curVcl) flush()
                    cur.add(n)
                }
                else -> cur.add(n)
            }
        }
        flush()
        val cfgNals = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
        val sps = cfgNals.firstOrNull { nalType(it) == 7 }
        val pps = cfgNals.firstOrNull { nalType(it) == 8 }
        val cfg = if (sps != null && pps != null) sps + pps else null
        return Pair(cfg, aus)
    }
}
