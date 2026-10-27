package com.yegorkapomidorka.headswap.aa

/** CarLife 视频队列快照，用于桥接器统计（CarlifeLink.videoStats）。 */
class LinkVideoStats(
    /** 队列向 USB 的排空速度，字节/秒（0 = 尚未测量）。 */
    val drainBytesPerSec: Double,
    /** 队列中视频字节数 / 排空速度，毫秒。 */
    val backlogMs: Long,
    val queueFrames: Int,
    val queueBytes: Long,
)

/**
 * 视频队列向 USB 排空的速度：按写入（write+flush）耗时统计视频帧字节数，窗口约 1 秒，EWMA 平滑。
 * 耗时按所有数据包（音频和命令与视频共享通道）的写入计算，字节只统计视频：得到
 * 当前混流下通道对视频的实际吞吐量。单线程写入（writer），任意线程读取。
 */
class DrainMeter(private val windowMs: Long = WINDOW_MS) {
    /** 字节/秒，0 = 尚未测量。 */
    @Volatile var bytesPerSec = 0.0
        private set

    private var winStartMs = NEVER
    private var winVideoBytes = 0L
    private var winBusyNs = 0L

    fun onWrite(nowMs: Long, video: Boolean, bytes: Int, durNs: Long) {
        if (winStartMs == NEVER) winStartMs = nowMs
        if (video) winVideoBytes += bytes
        winBusyNs += durNs
        if (nowMs - winStartMs < windowMs) return
        if (winVideoBytes > 0 && winBusyNs >= MIN_BUSY_NS) {
            val sample = winVideoBytes * 1e9 / winBusyNs
            val old = bytesPerSec
            bytesPerSec = if (old <= 0) sample else ALPHA * sample + (1 - ALPHA) * old
        }
        winStartMs = nowMs
        winVideoBytes = 0
        winBusyNs = 0
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val ALPHA = 0.5
        const val MIN_BUSY_NS = 50_000_000L     // 窗口内几乎没有写入：估算噪声大，保留原值
        const val NEVER = Long.MIN_VALUE
    }
}

/** CarLife 视频队列上限及其长度到时间的换算。纯函数。 */
object VideoQueueLimit {
    const val MAX_FRAMES = 30
    const val MAX_BACKLOG_MS = 500L

    /** 排空速度尚未测量时使用的值。 */
    const val UNMEASURED_MAX_BYTES = 512 * 1024L

    /** 字节上限的下限：当排空非常慢时，单个 IDR 本身不应被视为溢出。 */
    const val MIN_BYTES = 64 * 1024L

    /** 溢出发生后多长时间内仍计入比特率压力，以及按多大 backlog 计入（参见 [policyBacklogMs]）。 */
    const val OVERFLOW_HOLD_MS = 1500L
    const val OVERFLOW_BACKLOG_MS = 1000.0

    /** 队列 backlog（毫秒）；排空速度尚未测量时为 0。 */
    fun backlogMs(queueBytes: Long, drainBytesPerSec: Double): Long =
        if (drainBytesPerSec > 0) (queueBytes * 1000 / drainBytesPerSec).toLong() else 0L

    /**
     * 队列溢出：其中已有的视频帧超过 30 帧，或其字节数超过约 500 毫秒的排空量。新帧不计入：
     * 空队列总是接受任意帧（160 KB 的 IDR），否则车机将一帧都收不到。
     */
    fun overflow(queueFrames: Int, queueBytes: Long, drainBytesPerSec: Double): Boolean {
        if (queueFrames > MAX_FRAMES) return true
        val limit = if (drainBytesPerSec > 0) maxOf((drainBytesPerSec * MAX_BACKLOG_MS / 1000).toLong(), MIN_BYTES) else UNMEASURED_MAX_BYTES
        return queueBytes > limit
    }

    /**
     * 供比特率策略使用的 backlog。队列上限本身会截断 backlog（溢出时帧进不了队列，
     * backlog 在零到 IDR 之间来回波动），而 EWMA 对这种锯齿波达不到 300 毫秒。因此，最近的溢出
     * （[sinceOverflowMs] < [OVERFLOW_HOLD_MS]）按不小于 [OVERFLOW_BACKLOG_MS] 的 backlog 计。
     */
    fun policyBacklogMs(realMs: Long, sinceOverflowMs: Long): Double =
        if (sinceOverflowMs in 0 until OVERFLOW_HOLD_MS) maxOf(realMs.toDouble(), OVERFLOW_BACKLOG_MS) else realMs.toDouble()
}

/** 队列溢出时对编码器 IDR 请求的限流器：间隔不小于 [minIntervalMs]。 */
class IdrThrottle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastMs = NEVER

    /** true = 可以发送请求（记录时间）；false = 等待已请求的或最近的自然 IDR。 */
    fun tryRequest(nowMs: Long): Boolean {
        if (lastMs != NEVER && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }

    fun reset() {
        lastMs = NEVER
    }

    companion object {
        const val MIN_INTERVAL_MS = 1500L
        private const val NEVER = Long.MIN_VALUE
    }
}
