package com.yegorkapomidorka.headswap.aa

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 面向不同传输方式的 [BitratePolicy] 参数。[USB] - 原有行为（有线：从上限起步，向下最多到排空速度的 0.8，
 * 每 10 秒向上 +10%）。[WIFI] - 无线（方案 E3、5.3）：从上限的 0.75 起步，向下每档不小于 ×0.7，
 * 静默 10 秒后每 5 秒向上 +20%，超过起始值则须先有 60 秒无下降。
 */
data class BitrateProfile(
    val name: String,
    /** 会话起始值：上限的比例。 */
    val startFraction: Double,
    /** 向下每档不小于：目标不高于 current * downStep（1.0 = 仅按排空速度）。 */
    val downStep: Double,
    /** 即使未测得排空速度也向下调整（仅按 [downStep] 步进）。 */
    val downWithoutDrain: Boolean,
    val upFactor: Double,
    /** 下降后回升前需等待的静默时间。 */
    val upWaitMs: Long,
    /** 相邻回升档位之间的间隔。 */
    val upIntervalMs: Long,
    /** 超过起始比特率——仅在如此多毫秒无下降之后（自会话开始或上次下降起）。 */
    val aboveStartAfterMs: Long,
) {
    companion object {
        val USB = BitrateProfile("usb", 1.0, 1.0, false, 1.1, 10_000L, 10_000L, 0L)
        val WIFI = BitrateProfile("wifi", 0.75, 0.7, true, 1.2, 10_000L, 5_000L, 60_000L)
    }
}

/**
 * 根据 CarLife 视频队列状态调整编码器目标比特率：车机接收视频的速度有限
 * （Kia Seltos 约 210 KiB/s），6 Mbps 的编码器会塞满队列，延迟增长到数秒。向下调整——最多降到排空速度的 0.8，
 * 向上调整——静默后按档位回升（参见 [BitrateProfile]）。纯逻辑：时间由参数传入，不触碰编解码器。
 * [update] 从单个线程调用，[lowerCeiling] 可从任意线程调用；[current] 可从任意线程读取。
 */
class BitratePolicy(ceilingBps: Int, floorBps: Int = FLOOR_BPS, val profile: BitrateProfile = BitrateProfile.USB) {
    val floorBps = min(floorBps, ceilingBps)

    /** 上限，比特/秒。只能向下且动态调整 - [lowerCeiling]（对帧大小有限制的车机）。 */
    @Volatile var ceilingBps = ceilingBps
        private set

    /**
     * 按手机发热设置的上限，比特/秒（Int.MAX_VALUE = 无）。与 [ceilingBps] 不同，手机冷却后
     * 该上限会恢复：后续回升按普通档位进行。
     */
    @Volatile var thermalCapBps = Int.MAX_VALUE
        private set

    /** 会话起始比特率：上限（USB）或其比例（无线）。 */
    val startBps = max(this.floorBps, (ceilingBps * profile.startFraction).toInt())

    /** 当前目标，比特/秒。 */
    @Volatile var current = startBps
        private set

    /** 平滑后的 backlog，毫秒（用于日志和测试）。 */
    var smoothedMs = 0.0
        private set

    /** 最近一次变更的说明（供调用方写一行日志）。 */
    var lastReason = ""
        private set

    var downs = 0
        private set
    var ups = 0
        private set
    var minBps = startBps
        private set

    private var lastSampleMs = NEVER
    private var lastDownMs = NEVER
    private var calmSinceMs = NEVER
    private var upNotBeforeMs = NEVER
    private var cleanSinceMs = NEVER

    /**
     * 下一次测量：队列 backlog（毫秒）和排空速度（字节/秒，0 = 尚未测量）。若比特率发生变化则返回新值，
     * 否则返回 null。backlog 按时间做 EWMA 平滑（tau 1 秒），因此单个 160 KB 的 IDR
     * 不会引发下调，结果也不受调用频率影响。
     */
    @Synchronized
    fun update(nowMs: Long, backlogMs: Double, drainBytesPerSec: Double): Int? {
        smoothedMs = if (lastSampleMs == NEVER) backlogMs else {
            val dt = max(nowMs - lastSampleMs, 0L)
            smoothedMs + (1 - exp(-dt / TAU_MS)) * (backlogMs - smoothedMs)
        }
        lastSampleMs = nowMs
        if (cleanSinceMs == NEVER) cleanSinceMs = nowMs

        if (smoothedMs < UP_BACKLOG_MS) {
            if (calmSinceMs == NEVER) calmSinceMs = nowMs
        } else {
            calmSinceMs = NEVER
        }

        val drainOk = drainBytesPerSec > 0 || profile.downWithoutDrain
        if (smoothedMs > DOWN_BACKLOG_MS && drainOk && (lastDownMs == NEVER || nowMs - lastDownMs >= DOWN_INTERVAL_MS)) {
            var target = current
            if (drainBytesPerSec > 0) target = min(target, (drainBytesPerSec * 8 * DOWN_FACTOR).toInt())
            if (profile.downStep < 1.0) target = min(target, (current * profile.downStep).toInt())
            target = max(floorBps, target)
            if (target < current) {
                lastReason = "бэклог %.0f мс (сглаженный) > %d мс, слив %.0f КиБ/с".format(smoothedMs, DOWN_BACKLOG_MS, drainBytesPerSec / 1024)
                current = target
                lastDownMs = nowMs
                cleanSinceMs = nowMs
                calmSinceMs = NEVER
                upNotBeforeMs = nowMs + profile.upWaitMs
                downs++
                if (target < minBps) minBps = target
                return target
            }
        }

        val limit = min(ceilingBps, thermalCapBps)
        if (calmSinceMs != NEVER && nowMs - calmSinceMs >= profile.upWaitMs && nowMs >= upNotBeforeMs && current < limit) {
            val cap = if (nowMs - cleanSinceMs >= profile.aboveStartAfterMs) limit else min(startBps, limit)
            if (current >= cap) return null
            val target = min(cap, max(current + 1, (current * profile.upFactor).toInt()))
            lastReason = "бэклог < %d мс уже %d с".format(UP_BACKLOG_MS, (nowMs - calmSinceMs) / 1000)
            current = target
            upNotBeforeMs = nowMs + profile.upIntervalMs
            ups++
            return target
        }
        return null
    }

    /**
     * 下调上限（不低于 [floorBps]）；不会升到当前值以上。若 [current] 高于新上限，则
     * 将其截断到上限。true = 上限已变化（调用方核对 [current] 并应用到编码器）。
     */
    @Synchronized
    fun lowerCeiling(newCeilingBps: Int): Boolean {
        val c = max(floorBps, newCeilingBps)
        if (c >= ceilingBps) return false
        ceilingBps = c
        if (current > c) {
            current = c
            lastReason = "потолок снижен до %d кбит/с".format(c / 1000)
            if (c < minBps) minBps = c
        }
        return true
    }

    /**
     * 按发热设置上限（不低于 [floorBps]；Int.MAX_VALUE 解除）。[current] 高于上限时立即截断。
     * true = [current] 已变化（调用方将其应用到编码器）。
     */
    @Synchronized
    fun setThermalCap(capBps: Int): Boolean {
        thermalCapBps = if (capBps == Int.MAX_VALUE) capBps else max(floorBps, capBps)
        if (current <= thermalCapBps) return false
        current = thermalCapBps
        lastReason = "нагрев, потолок %d кбит/с".format(thermalCapBps / 1000)
        if (current < minBps) minBps = current
        return true
    }

    companion object {
        const val FLOOR_BPS = 800_000
        const val DOWN_BACKLOG_MS = 300L
        const val UP_BACKLOG_MS = 100L
        const val DOWN_INTERVAL_MS = 1000L
        const val UP_WAIT_MS = 10_000L
        const val DOWN_FACTOR = 0.8
        const val UP_FACTOR = 1.1
        private const val TAU_MS = 1000.0
        private const val NEVER = Long.MIN_VALUE
    }
}
