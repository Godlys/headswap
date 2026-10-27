package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPacingTest {
    @Test
    fun idrThrottleLimitsRequestsToOncePerInterval() {
        val t = IdrThrottle()
        assertTrue(t.tryRequest(0))                  // 即使是零时刻，第一个请求也放行
        assertFalse(t.tryRequest(10))
        assertFalse(t.tryRequest(1499))
        assertTrue(t.tryRequest(1500))
        assertFalse(t.tryRequest(2999))              // 拒绝不推移时间：计时从最后一次放行的请求算起
        assertTrue(t.tryRequest(3000))
        t.reset()
        assertTrue(t.tryRequest(3001))
    }

    @Test
    fun idrRequestsOverTenSecondsOfConstantOverflow() {
        val t = IdrThrottle()
        var n = 0
        for (ms in 0 until 10_000 step 33) if (t.tryRequest(ms.toLong())) n++
        assertTrue("запросов $n", n in 6..7)         // 10 秒 / 1.5 秒
    }

    @Test
    fun drainMeterMeasuresVideoBytesOverBusyTime() {
        val m = DrainMeter()
        assertEquals(0.0, m.bytesPerSec, 0.0)
        // 210 KiB/s：7 KB 的帧每 32.5 ms 连续写入
        var now = 0L
        repeat(40) {
            now += 33
            m.onWrite(now, true, 7 * 1024, 32_500_000L)
        }
        assertEquals(215_000.0, m.bytesPerSec, 8_000.0)
    }

    @Test
    fun drainMeterCountsAudioTimeButNotAudioBytes() {
        val m = DrainMeter()
        var now = 0L
        repeat(60) {
            now += 20
            m.onWrite(now, true, 2000, 10_000_000L)   // 视频：写入期间 200 KB/s
            now += 20
            m.onWrite(now, false, 2000, 10_000_000L)  // 音频分占通道
        }
        assertEquals(100_000.0, m.bytesPerSec, 5_000.0)
    }

    @Test
    fun drainMeterIgnoresWindowsWithoutVideoOrBusyTime() {
        val m = DrainMeter()
        var now = 0L
        repeat(30) {
            now += 100
            m.onWrite(now, false, 100, 1_000_000L)
        }
        assertEquals(0.0, m.bytesPerSec, 0.0)
        repeat(5) {
            now += 400
            m.onWrite(now, true, 100, 1_000_000L)      // 窗口内仅 5 ms 写入：噪声太大
        }
        assertEquals(0.0, m.bytesPerSec, 0.0)
    }

    @Test
    fun overflowByFramesBytesAndUnmeasuredDrain() {
        val drain = 210.0 * 1024                         // ~105 KB = 500 ms
        assertFalse(VideoQueueLimit.overflow(0, 0, drain))
        assertFalse(VideoQueueLimit.overflow(30, 100_000, drain))
        assertTrue(VideoQueueLimit.overflow(31, 1_000, drain))
        assertTrue(VideoQueueLimit.overflow(5, 110_000, drain))
        // 吞吐尚未测得时 - 512 KB
        assertFalse(VideoQueueLimit.overflow(5, 500_000, 0.0))
        assertTrue(VideoQueueLimit.overflow(5, 540_000, 0.0))
        // 极慢吞吐：限制不低于 64 KB
        assertFalse(VideoQueueLimit.overflow(5, 60_000, 10_000.0))
        // 空队列接受任何帧
        assertFalse(VideoQueueLimit.overflow(0, 0, 10_000.0))
    }

    @Test
    fun backlogMsAndPolicyBacklog() {
        assertEquals(500L, VideoQueueLimit.backlogMs(105_000, 210_000.0))
        assertEquals(0L, VideoQueueLimit.backlogMs(105_000, 0.0))
        assertEquals(100.0, VideoQueueLimit.policyBacklogMs(100, -1), 0.0)
        assertEquals(100.0, VideoQueueLimit.policyBacklogMs(100, VideoQueueLimit.OVERFLOW_HOLD_MS), 0.0)
        assertEquals(VideoQueueLimit.OVERFLOW_BACKLOG_MS, VideoQueueLimit.policyBacklogMs(100, 200), 0.0)
        assertEquals(1500.0, VideoQueueLimit.policyBacklogMs(1500, 200), 0.0)
    }

    @Test
    fun overflowPressureMakesPolicyCutOnSawtoothBacklog() {
        // 受限队列把积压削成锯齿（每 1.5 s 升到 500 ms 再归零，溢出时帧被丢弃）：平滑积压约 100 ms，不计溢出则不会回退
        val drain = 210.0 * 1024
        fun run(withPressure: Boolean): Int {
            val p = BitratePolicy(6_000_000)
            var lastOverflow = Long.MIN_VALUE
            p.update(0, 0.0, drain)
            var t = 0L
            while (t < 10_000) {
                t += 100
                val phase = t % 1500
                val raw = if (phase < 500) 500.0 - phase else 0.0
                if (phase == 0L) lastOverflow = t           // 队列顶到限制，帧被丢弃
                val since = if (lastOverflow == Long.MIN_VALUE) -1L else t - lastOverflow
                p.update(t, if (withPressure) VideoQueueLimit.policyBacklogMs(raw.toLong(), since) else raw, drain)
            }
            return p.current
        }
        val without = run(false)
        assertEquals("без давления: $without", 6_000_000, without)
        assertTrue(run(true) < 2_000_000)
    }
}
