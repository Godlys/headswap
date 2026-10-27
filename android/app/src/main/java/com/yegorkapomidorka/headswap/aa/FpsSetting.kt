package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * 「帧率」设置：自动（按车机在 VIDEO_ENCODER_INIT 中请求的值）或固定值；默认 15。
 * 每次连接读取一次（SessionConfig），在 passthrough 模式下不生效。
 */
enum class FpsSetting(val titleRes: Int, val subRes: Int, val fixed: Int?) {
    AUTO(R.string.fps_auto_title, R.string.fps_auto_sub, null),
    FPS15(R.string.fps_15_title, R.string.fps_15_sub, 15),
    FPS30(R.string.fps_30_title, R.string.fps_30_sub, 30);

    companion object {
        val DEFAULT = FPS15

        /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): FpsSetting = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 编码器前的帧门控：根据时间戳决定是否放行帧。Surface 输入的编码器会对每次 swap 都编码，
 * 所以 KEY_FRAME_RATE 限制不了帧率——只有这个门控能限制。
 * 目标 >= 30（或 <= 0）时全部放行：Android Auto 输入不会快于 30，而在 30/30 情况下按时间戳容差反而会因输入抖动丢帧。
 * 低于 30 时使用时隙网格：每 period 一个时隙，帧在时隙前半个周期及之后被采用。
 * 只从一个线程调用（GL 中继）；targetFps 可从任意线程修改。
 */
class FrameGate(initialFps: Int) {
    @Volatile var targetFps: Int = initialFps
    private var nextNs = NONE

    /** true = 放行该帧，false = 跳过。 */
    fun shouldEmit(nowNs: Long): Boolean {
        val fps = targetFps
        if (fps <= 0 || fps >= PASS_ALL_FPS) {
            nextNs = NONE           // 切换到更低目标时，网格将重新开始
            return true
        }
        val period = 1_000_000_000L / fps
        if (nextNs == NONE) {
            nextNs = nowNs + period
            return true
        }
        if (nowNs < nextNs - period / 2) return false
        nextNs += period
        if (nowNs - nextNs > period) nextNs = nowNs + period     // 落后超过一个周期（输入慢于目标）：网格重新开始
        return true
    }

    /** 目标频率的周期（纳秒，0 = 不限制）。 */
    fun periodNs(): Long = targetFps.let { if (it <= 0) 0L else 1_000_000_000L / it }

    /** 新的 Android Auto 会话：第一帧立即放行。 */
    fun reset() {
        nextNs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val PASS_ALL_FPS = 30
    }
}
