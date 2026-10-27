package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.Handshake
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
        assertNull(t.update(1000, 1, 0.5f))     // LIGHT 且预测有富余：不降载
        assertEquals(ThermalLevel.NORMAL, t.level)
        assertEquals(30, t.capFps(30))
        assertEquals(4_000_000, t.capBitrate(4_000_000))
    }

    @Test
    fun moderateAndSevereStepDownImmediately() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0, ThermalThrottle.STATUS_MODERATE, nan))
        assertEquals(20, t.capFps(30))
        assertEquals(15, t.capFps(15))          // 低于上限 - 原样返回
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
        // 回到 NORMAL 需要再等满一个周期
        assertNull(t.update(100_000, 0, nan))
        assertEquals(ThermalLevel.NORMAL, t.update(121_000, 0, nan))
        assertEquals(ThermalLevel.HOT, t.maxLevel)
    }

    @Test
    fun bounceResetsRelaxTimer() {
        val t = ThermalThrottle(relaxMs = 60_000)
        t.update(0, ThermalThrottle.STATUS_MODERATE, nan)
        t.update(1_000, 0, nan)
        t.update(30_000, ThermalThrottle.STATUS_MODERATE, nan)     // 再次变热：重新计时
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
        // 队列平静：不会升到热上限之上
        var now = 0L
        repeat(60) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(2_000_000, p.current)
        // 已降温：解除上限，按常规步进回升到模式上限
        assertFalse(p.setThermalCap(Int.MAX_VALUE))
        repeat(120) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(4_000_000, p.current)
    }

    @Test
    fun pioneerCeilingHoldsWithThermalCap() {
        // Pioneer RM56E8B (h277)：车机标志下上限 800 kbit/s（= 下限），模式要求 4 Mbit/s；发热按已降低的上限计算
        val ceiling = AaBridgeCore.PIONEER_RM56_CEILING_BPS
        val p = BitratePolicy(4_000_000)
        val t = ThermalThrottle()
        assertTrue(p.lowerCeiling(ceiling))
        assertEquals(ceiling, p.current)
        // 队列平静：不会升到车机上限之上
        var now = 0L
        repeat(120) {
            p.update(now, 0.0, 2_000_000.0)
            now += 1000
            assertTrue(p.current <= ceiling)
        }
        assertEquals(ceiling, p.current)
        // HOT 发热（车机上限 ×0.5，不低于下限）削减更狠，降温后回到车机上限但不更高
        t.update(0, ThermalThrottle.STATUS_SEVERE, nan)
        p.setThermalCap(t.capBitrate(p.ceilingBps))      // 车机上限已等于下限：无处可减，current 不变
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
        p.setThermalCap(Int.MAX_VALUE)
        repeat(300) {
            p.update(now, 0.0, 2_000_000.0)
            now += 1000
            assertTrue(p.current <= ceiling)
        }
        assertEquals(ceiling, p.current)
        // 车机上限晚于热上限到达（发热测量后的标志）：结果仍是 min
        val q = BitratePolicy(4_000_000)
        q.setThermalCap(3_000_000)
        assertTrue(q.lowerCeiling(ceiling))
        assertEquals(ceiling, q.current)
        repeat(120) {
            q.update(now, 0.0, 2_000_000.0)
            now += 1000
        }
        assertEquals(ceiling, q.current)
    }

    @Test
    fun pioneerFpsCapCombinesWithThermalViaMin() {
        val cap = AaBridgeCore.PIONEER_RM56_MAX_FPS
        val t = ThermalThrottle()
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        t.update(0, ThermalThrottle.STATUS_MODERATE, nan)      // WARM：20，车机上限 10 保持
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        assertEquals(8, t.capFps(Handshake.capFps(8, cap)))
        t.update(1_000, ThermalThrottle.STATUS_SEVERE, nan)    // HOT：15，车机上限 10
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        assertEquals(15, t.capFps(30))
    }

    @Test
    fun bitratePolicyThermalCapNotBelowFloor() {
        val p = BitratePolicy(4_000_000)
        p.setThermalCap(100_000)
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
    }
}
