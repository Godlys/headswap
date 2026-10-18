package com.yegorkapomidorka.headswap.aa

/** Источник нагрева телефона (на устройстве - PhoneThermal поверх PowerManager; в JVM-тестах - заглушка). */
interface ThermalSource {
    /** PowerManager.THERMAL_STATUS_* (0 = NONE). */
    fun status(): Int

    /** Прогноз getThermalHeadroom на 10 с (1.0 = SEVERE), NaN = недоступен. */
    fun headroom(): Float

    object NONE : ThermalSource {
        override fun status() = 0
        override fun headroom() = Float.NaN
    }
}

/**
 * Ступень разгрузки перекодирования по нагреву телефона. [fpsCap] - потолок частоты энкодера (null = без ограничения),
 * [bitrateFraction] - доля потолка битрейта режима.
 */
enum class ThermalLevel(val fpsCap: Int?, val bitrateFraction: Double) {
    NORMAL(null, 1.0),
    WARM(20, 0.75),
    HOT(15, 0.5),
}

/**
 * Разгрузка перекодирования по нагреву (жалобы: телефон перегревается в поездке). Вход - статус PowerManager
 * (getCurrentThermalStatus: 0 NONE .. 6 SHUTDOWN) и прогноз getThermalHeadroom (1.0 = SEVERE, NaN = неизвестно).
 * MODERATE или прогноз >= [WARM_HEADROOM] - [ThermalLevel.WARM], SEVERE и выше - [ThermalLevel.HOT].
 * Вниз (сильнее разгрузка) - сразу; обратно - по одной ступени и только если ниже уже [RELAX_MS] подряд,
 * чтобы частота не прыгала туда-сюда на границе статуса. Чистая логика: время приходит параметром. [update] - с одной нити.
 */
class ThermalThrottle(private val relaxMs: Long = RELAX_MS) {
    /** Читается с любой нити ([capFps] из обработки FRAME_RATE_CHANGE и создания транскодера). */
    @Volatile var level = ThermalLevel.NORMAL
        private set

    /** Самая сильная ступень за сессию и время на ней/выше NORMAL - для резюме. */
    var maxLevel = ThermalLevel.NORMAL
        private set
    var throttledMs = 0L
        private set

    private var lowerSinceMs = NEVER
    private var lastMs = NEVER

    /** Ступень по одному замеру, без гистерезиса. */
    fun levelFor(status: Int, headroom: Float): ThermalLevel = when {
        status >= STATUS_SEVERE -> ThermalLevel.HOT
        status == STATUS_MODERATE -> ThermalLevel.WARM
        !headroom.isNaN() && headroom >= WARM_HEADROOM -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }

    /** Очередной замер. Возвращает новую ступень, если она сменилась, иначе null. */
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
        lowerSinceMs = if (want < level) nowMs else NEVER      // до want ещё ступень: снова ждать relaxMs
        return level
    }

    /** Частота с учётом нагрева: не выше потолка ступени. */
    fun capFps(fps: Int): Int = level.fpsCap?.let { minOf(fps, it) } ?: fps

    /** Потолок битрейта с учётом нагрева. */
    fun capBitrate(ceilingBps: Int): Int = (ceilingBps * level.bitrateFraction).toInt()

    companion object {
        /** Значения PowerManager.THERMAL_STATUS_*. */
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3

        /** Прогноз на 10 с: столько от порога SEVERE - уже разгружаемся, не дожидаясь MODERATE. */
        const val WARM_HEADROOM = 0.85f
        const val RELAX_MS = 60_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
