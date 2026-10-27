package com.yegorkapomidorka.headswap.aa

/** 手机发热数据源（设备上为基于 PowerManager 的 PhoneThermal；JVM 测试中为桩实现）。 */
interface ThermalSource {
    /** PowerManager.THERMAL_STATUS_*（0 = NONE）。 */
    fun status(): Int

    /** getThermalHeadroom 对未来 10 秒的预测（1.0 = SEVERE），NaN = 不可用。 */
    fun headroom(): Float

    object NONE : ThermalSource {
        override fun status() = 0
        override fun headroom() = Float.NaN
    }
}

/**
 * 按手机发热对转码降载的档位。[fpsCap] - 编码器帧率上限（null = 不限制），
 * [bitrateFraction] - 当前模式比特率上限的比例。
 */
enum class ThermalLevel(val fpsCap: Int?, val bitrateFraction: Double) {
    NORMAL(null, 1.0),
    WARM(20, 0.75),
    HOT(15, 0.5),
}

/**
 * 按发热对转码降载（用户反馈：手机在行车途中过热）。输入为 PowerManager 状态
 * （getCurrentThermalStatus：0 NONE .. 6 SHUTDOWN）和 getThermalHeadroom 预测（1.0 = SEVERE，NaN = 未知）。
 * MODERATE 或预测 >= [WARM_HEADROOM] 时进入 [ThermalLevel.WARM]，SEVERE 及以上进入 [ThermalLevel.HOT]。
 * 降档（更强降载）立即生效；回升则每次一档，且仅在持续低于 [RELAX_MS] 之后，
 * 避免帧率在状态边界来回跳动。纯逻辑：时间由参数传入。[update] 从单线程调用。
 */
class ThermalThrottle(private val relaxMs: Long = RELAX_MS) {
    /** 可从任意线程读取（[capFps] 在 FRAME_RATE_CHANGE 处理和创建转码器时调用）。 */
    @Volatile var level = ThermalLevel.NORMAL
        private set

    /** 会话期间最强档位以及在 NORMAL 及以上档位的时间 - 用于摘要。 */
    var maxLevel = ThermalLevel.NORMAL
        private set
    var throttledMs = 0L
        private set

    private var lowerSinceMs = NEVER
    private var lastMs = NEVER

    /** 根据单次测量确定档位，无迟滞。 */
    fun levelFor(status: Int, headroom: Float): ThermalLevel = when {
        status >= STATUS_SEVERE -> ThermalLevel.HOT
        status == STATUS_MODERATE -> ThermalLevel.WARM
        !headroom.isNaN() && headroom >= WARM_HEADROOM -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }

    /** 下一次测量。若档位发生变化则返回新档位，否则返回 null。 */
    fun update(nowMs: Long, status: Int, headroom: Float): ThermalLevel? {
        if (lastMs != NEVER && level != ThermalLevel.NORMAL) throttledMs += maxOf(nowMs - lastMs, 0L)
        lastMs = nowMs
        val want = levelFor(status, headroom)
        if (want > level) {
            level = want
            lowerSinceMs = NEVER
            if (want > maxLevel) maxLevel = want
            return want
        }
        if (want == level) {
            lowerSinceMs = NEVER
            return null
        }
        if (lowerSinceMs == NEVER) lowerSinceMs = nowMs
        if (nowMs - lowerSinceMs < relaxMs) return null
        level = ThermalLevel.values()[level.ordinal - 1]
        lowerSinceMs = if (want < level) nowMs else NEVER      // 距离 want 还差一档：重新等待 relaxMs
        return level
    }

    /** 考虑发热后的帧率：不超过当前档位的上限。 */
    fun capFps(fps: Int): Int = level.fpsCap?.let { minOf(fps, it) } ?: fps

    /** 考虑发热后的比特率上限。 */
    fun capBitrate(ceilingBps: Int): Int = (ceilingBps * level.bitrateFraction).toInt()

    companion object {
        /** PowerManager.THERMAL_STATUS_* 的值。 */
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3

        /** 10 秒预测：距 SEVERE 阈值这么远时就开始降载，无需等到 MODERATE。 */
        const val WARM_HEADROOM = 0.85f
        const val RELAX_MS = 60_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
