package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无线（Wi-Fi）配置档（计划 Э3，5.3）。USB 配置档由 BitratePolicyTest 无改动地验证。 */
class BitratePolicyWifiTest {
    private val ceiling = 4_000_000

    /** 与 BitratePolicyTest 相同的队列模型，但排水速率随时间变化（整形器）。 */
    private class Run(val policy: BitratePolicy, val stepMs: Long = 100, val drainBpsAt: (Long) -> Double) {
        var queue = 0.0
        var now = 0L
        val changes = ArrayList<Pair<Long, Int>>()

        fun run(untilMs: Long) {
            while (now < untilMs) {
                now += stepMs
                val dt = stepMs / 1000.0
                val drain = drainBpsAt(now)
                queue = maxOf(queue + policy.current / 8.0 * dt - drain * dt, 0.0)
                val raw = queue / drain * 1000
                policy.update(now, raw, drain)?.let { changes.add(now to it) }
            }
        }
    }

    private fun wifi(c: Int = ceiling) = BitratePolicy(c, profile = BitrateProfile.WIFI)

    @Test
    fun startsAtThreeQuartersOfCeiling() {
        assertEquals(3_000_000, wifi().current)
        assertEquals(6_000_000, wifi(8_000_000).current)
        assertEquals(BitratePolicy.FLOOR_BPS, wifi(900_000).current)
    }

    @Test
    fun cleanChannelReachesCeilingOnlyAfterSixtySeconds() {
        val r = Run(wifi()) { 10.0 * 1024 * 1024 }
        r.run(59_000)
        assertEquals("до 60 с выше старта не поднимаемся", 3_000_000, r.policy.current)
        r.run(80_000)
        assertEquals(ceiling, r.policy.current)
        assertEquals(0, r.policy.downs)
    }

    @Test
    fun shaperDipReactsFastAndRecoversToStartWithinSixtySeconds() {
        // 带宽 6 Mbps，从 20 秒到 25 秒降到 1 Mbps（测试台 6->1 场景）
        val mbps = 1_000_000 / 8.0
        val r = Run(wifi()) { t -> if (t in 20_000 until 25_000) 1 * mbps else 6 * mbps }
        r.run(20_000)
        assertEquals(0, r.policy.downs)
        r.run(25_000)
        val firstDown = r.changes.first { it.second < 3_000_000 }.first
        assertTrue("первая реакция через ${firstDown - 20_000} мс", firstDown - 20_000 <= 1_000)
        val low = r.changes.filter { it.first in 20_000..25_000 }.minOf { it.second }
        assertTrue("за провал битрейт опустился до $low", low <= 1_000_000)
        r.run(85_000)
        assertTrue("через 60 с после провала ${r.policy.current}", r.policy.current >= 3_000_000)
    }

    @Test
    fun stepDownIsAtLeastThirtyPercentEvenIfDrainLooksHigh() {
        val p = wifi()
        // 积压很大，而（过时的）排水速率却显示是快速信道：仍然按 ×0.7 降档
        assertEquals((3_000_000 * 0.7).toInt(), p.update(0, 2_000.0, 10.0 * 1024 * 1024))
        assertEquals("не чаще раза в 1 с", null, p.update(500, 2_000.0, 10.0 * 1024 * 1024))
        assertEquals((3_000_000 * 0.7 * 0.7).toInt(), p.update(1_000, 2_000.0, 10.0 * 1024 * 1024))
    }

    @Test
    fun goesDownWithoutMeasuredDrain() {
        val p = wifi()
        assertEquals((3_000_000 * 0.7).toInt(), p.update(0, 1_000.0, 0.0))
        assertEquals(1, p.downs)
    }
}
