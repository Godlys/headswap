package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameGateTest {
    /** 在输入频率 inFps（抖动 ±jitterMs）下，gate 在 seconds 秒内会放行多少帧。 */
    private fun passed(gate: FrameGate, inFps: Int, seconds: Int, jitterMs: Int = 0): Int {
        val rnd = Random(42)
        val periodNs = 1_000_000_000L / inFps
        var n = 0
        for (i in 0 until inFps * seconds) {
            val j = if (jitterMs > 0) (rnd.nextInt(2 * jitterMs + 1) - jitterMs) * 1_000_000L else 0L
            if (gate.shouldEmit(1_000_000_000L + i * periodNs + j)) n++
        }
        return n
    }

    @Test
    fun input30Target15GivesAbout15() {
        assertEquals(150.0, passed(FrameGate(15), 30, 10).toDouble(), 3.0)
        assertEquals(150.0, passed(FrameGate(15), 30, 10, jitterMs = 3).toDouble(), 8.0)
    }

    @Test
    fun input30Target30LosesNothing() {
        assertEquals(300, passed(FrameGate(30), 30, 10))
        // 输入抖动 ±5 毫秒：目标 30 全部放行
        assertEquals(300, passed(FrameGate(30), 30, 10, jitterMs = 5))
    }

    @Test
    fun lowInputPassesEverything() {
        assertEquals(100, passed(FrameGate(30), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10, jitterMs = 5))
    }

    @Test
    fun slotsKeepRateWhenInputIsFasterThanTarget() {
        // 30 -> 20（输入 33.3 毫秒，时隙 50 毫秒）：平均输出频率 20
        assertEquals(200.0, passed(FrameGate(20), 30, 10).toDouble(), 3.0)
        assertEquals(200.0, passed(FrameGate(20), 30, 10, jitterMs = 4).toDouble(), 8.0)
    }

    @Test
    fun unlimitedAndFirstFrame() {
        assertEquals(300, passed(FrameGate(0), 30, 10))
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(5L))                    // 第一帧立即放行，即使时间为零
        assertFalse(g.shouldEmit(5L + 10_000_000L))
        assertFalse(g.shouldEmit(5L + 30_000_000L))     // 时隙 66.7 毫秒，从 33.3 毫秒处取帧
        assertTrue(g.shouldEmit(5L + 40_000_000L))
        g.reset()
        assertTrue(g.shouldEmit(5L + 61_000_000L))
    }

    @Test
    fun lagBeyondPeriodRestartsGrid() {
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(300_000_000L))          // 输入暂停：帧被取走，网格从新位置开始
        assertFalse(g.shouldEmit(310_000_000L))         // 没有追赶帧序列
        assertFalse(g.shouldEmit(330_000_000L))
        assertTrue(g.shouldEmit(370_000_000L))
    }

    @Test
    fun targetChangesOnTheFly() {
        val g = FrameGate(30)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(33_000_000L))
        g.targetFps = 10
        assertEquals(100_000_000L, g.periodNs())
        assertTrue(g.shouldEmit(66_000_000L))           // 切换目标后网格从头开始，不再全部跳过
        assertFalse(g.shouldEmit(99_000_000L))
        assertTrue(g.shouldEmit(66_000_000L + 100_000_000L))
        g.targetFps = 30
        assertTrue(g.shouldEmit(170_000_000L))
        assertTrue(g.shouldEmit(171_000_000L))
    }
}
