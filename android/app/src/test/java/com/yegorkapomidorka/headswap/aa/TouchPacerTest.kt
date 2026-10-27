package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPacerTest {
    private val timers = FakeTimers()
    private val out = ArrayList<Pair<Long, Int>>()     // 发送时间, y * 10 + action
    private val pacer = TouchBurstPacer({ timers.now }, timers::schedule) { a, _, y, _, _ -> out.add(timers.now to (y * 10 + (a and 0xFF))) }

    private fun move(y: Int) = pacer.onTouch(AapMessages.TOUCH_MOVE, 100, y, 0, 0)

    /** 由 n 个 MOVE 组成的连发，间隙 gap ms，与 Delphi 一致。 */
    private fun burst(from: Int, n: Int, gap: Long = 1) {
        for (i in 0 until n) {
            move(from + i)
            if (i < n - 1) timers.advance(gap)
        }
    }

    private fun moveTimes() = out.filter { it.second % 10 == AapMessages.TOUCH_MOVE }.map { it.first }

    @Test
    fun burstIsSpreadByItsOwnSize() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14)                                               // 0..13 ms；步长 196 / 14 = 14 ms
        assertEquals(listOf(0L), moveTimes())                      // 第一个 MOVE 立即发送，其余等待
        timers.advance(196 - 13)
        assertEquals((0..13).map { it * 14L }, moveTimes())
        out.clear()
        burst(15, 7)                                               // 手势末尾的短连发：步长 196 / 7 = 28 ms
        timers.advance(196)
        assertEquals((0..6).map { 196 + it * 28L }, moveTimes())
        assertEquals((15..21).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
    }

    @Test
    fun slowBurstReplansOnEveryEvent() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14, gap = 2)                                      // 连发持续 26 ms，长于步长
        timers.advance(196 - 26)
        // 连发未完成时，第二个 MOVE 按估计值 196 / 10 发出；最后一个事件之后按 14 ms 网格
        assertEquals(listOf(0L, 19L) + (2..13).map { it * 14L }, moveTimes())
    }

    @Test
    fun tailIsFlushedInOrderWhenNextBurstComesEarly() {
        burst(1, 14)
        timers.advance(100 - 13)                                   // 已发出 0, 14 .. 98：前 8 个
        assertEquals(8, out.size)
        burst(15, 2)
        assertEquals((1..15).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
        assertEquals(List(7) { 100L }, out.drop(8).map { it.first })  // 6 个尾部事件 + 新连发的第一个 MOVE
    }

    @Test
    fun fingerPauseDoesNotStretchInterval() {
        burst(1, 14)
        timers.advance(1000 - 13)                                  // 手指停住：1000 ms > 1.5 x 196
        out.clear()
        burst(15, 14)
        timers.advance(196)
        assertEquals((0..13).map { 1000 + it * 14L }, moveTimes())  // 步长仍为 14 ms
    }

    @Test
    fun upFlushesQueueAndStopsTimer() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14)
        timers.advance(196 - 13)
        burst(15, 14)
        timers.advance(5)                                          // 连发 196..209，第二个 MOVE 在 196 + 14 发出
        val sent = out.size
        pacer.onTouch(AapMessages.TOUCH_UP, 100, 99, 0, 0)          // UP：先发送整个队列，再发送 UP
        assertEquals((17..28).map { it * 10 + AapMessages.TOUCH_MOVE } + (99 * 10 + AapMessages.TOUCH_UP), out.drop(sent).map { it.second })
        timers.advance(1000)
        assertEquals(sent + 13, out.size)                          // UP 之后计时器不再发送任何内容
        assertEquals(1, pacer.bursts)
    }
}
