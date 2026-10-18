package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalThrottleTest {
    private val nan = Float.NaN

    @Test
    fun coolPhoneIsNotThrottled() {
        val t = ThermalThrottle()
        assertNull(t.update(0, 0, nan))
        assertNull(t.update(1000, 1, 0.5f))     // LIGHT и запас по прогнозу: без разгрузки
        assertEquals(ThermalLevel.NORMAL, t.level)
        assertEquals(30, t.capFps(30))
        assertEquals(4_000_000, t.capBitrate(4_000_000))
    }

    @Test
    fun moderateAndSevereStepDownImmediately() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0, ThermalThrottle.STATUS_MODERATE, nan))
        assertEquals(20, t.capFps(30))
        assertEquals(15, t.capFps(15))          // ниже потолка - как есть
        assertEquals(3_000_000, t.capBitrate(4_000_000))
        assertEquals(ThermalLevel.HOT, t.update(1000, ThermalThrottle.STATUS_SEVERE, nan))
        assertEquals(15, t.capFps(30))
        assertEquals(2_000_000, t.capBitrate(4_000_000))
        assertEquals(ThermalLevel.HOT, t.levelFor(6, nan))
    }

    @Test
    fun headroomForecastThrottlesBeforeModerate() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0, 1, 0.9f))
    }

    @Test
    fun relaxesOneStepAfterQuietPeriod() {
        val t = ThermalThrottle(relaxMs = 60_000)
        t.update(0, ThermalThrottle.STATUS_SEVERE, nan)
        assertNull(t.update(1_000, 0, nan))
        assertNull(t.update(60_000, 0, nan))
        assertEquals(ThermalLevel.WARM, t.update(61_000, 0, nan))
        // до NORMAL снова ждать полный период
        assertNull(t.update(100_000, 0, nan))
        assertEquals(ThermalLevel.NORMAL, t.update(121_000, 0, nan))
        assertEquals(ThermalLevel.HOT, t.maxLevel)
    }

    @Test
    fun bounceResetsRelaxTimer() {
        val t = ThermalThrottle(relaxMs = 60_000)
        t.update(0, ThermalThrottle.STATUS_MODERATE, nan)
        t.update(1_000, 0, nan)
        t.update(30_000, ThermalThrottle.STATUS_MODERATE, nan)     // снова тёплый: отсчёт заново
        assertNull(t.update(31_000, 0, nan))
        assertNull(t.update(90_000, 0, nan))
        assertEquals(ThermalLevel.NORMAL, t.update(91_000, 0, nan))
    }

    @Test
    fun countsThrottledTime() {
        val t = ThermalThrottle()
        t.update(0, 0, nan)
        t.update(1_000, ThermalThrottle.STATUS_MODERATE, nan)
        t.update(6_000, ThermalThrottle.STATUS_MODERATE, nan)
        assertEquals(5_000, t.throttledMs)
    }

    @Test
    fun bitratePolicyThermalCapCutsAndReleases() {
        val p = BitratePolicy(4_000_000)
        assertTrue(p.setThermalCap(2_000_000))
        assertEquals(2_000_000, p.current)
        // спокойная очередь: выше теплового потолка не поднимается
        var now = 0L
        repeat(60) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(2_000_000, p.current)
        // остыл: потолок снят, подъём обычными ступенями до потолка режима
        assertFalse(p.setThermalCap(Int.MAX_VALUE))
        repeat(120) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(4_000_000, p.current)
    }

    @Test
    fun bitratePolicyThermalCapNotBelowFloor() {
        val p = BitratePolicy(4_000_000)
        p.setThermalCap(100_000)
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
    }
}
