package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPacerTest {
    private val timers = FakeTimers()
    private val out = ArrayList<Pair<Long, Int>>()     // время отправки, y * 10 + action
    private val pacer = TouchBurstPacer({ timers.now }, timers::schedule) { a, _, y, _, _ -> out.add(timers.now to (y * 10 + (a and 0xFF))) }

    private fun move(y: Int) = pacer.onTouch(AapMessages.TOUCH_MOVE, 100, y, 0, 0)

    /** Пачка из n MOVE с зазором gap мс, как у Delphi. */
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
        burst(1, 14)                                               // 0..13 мс; шаг 196 / 14 = 14 мс
        assertEquals(listOf(0L), moveTimes())                      // первый MOVE сразу, остальные ждут
        timers.advance(196 - 13)
        assertEquals((0..13).map { it * 14L }, moveTimes())
        out.clear()
        burst(15, 7)                                               // короткая пачка в конце жеста: шаг 196 / 7 = 28 мс
        timers.advance(196)
        assertEquals((0..6).map { 196 + it * 28L }, moveTimes())
        assertEquals((15..21).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
    }

    @Test
    fun slowBurstReplansOnEveryEvent() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14, gap = 2)                                      // пачка тянется 26 мс, дольше шага
        timers.advance(196 - 26)
        // пока пачка неполная, второй MOVE ушёл по оценке 196 / 10; после последнего события - сетка по 14 мс
        assertEquals(listOf(0L, 19L) + (2..13).map { it * 14L }, moveTimes())
    }

    @Test
    fun tailIsFlushedInOrderWhenNextBurstComesEarly() {
        burst(1, 14)
        timers.advance(100 - 13)                                   // ушли 0, 14 .. 98: первые 8
        assertEquals(8, out.size)
        burst(15, 2)
        assertEquals((1..15).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
        assertEquals(List(7) { 100L }, out.drop(8).map { it.first })  // хвост из 6 и первый MOVE новой пачки
    }

    @Test
    fun fingerPauseDoesNotStretchInterval() {
        burst(1, 14)
        timers.advance(1000 - 13)                                  // палец стоял: 1000 мс > 1,5 x 196
        out.clear()
        burst(15, 14)
        timers.advance(196)
        assertEquals((0..13).map { 1000 + it * 14L }, moveTimes())  // шаг по-прежнему 14 мс
    }

    @Test
    fun upFlushesQueueAndStopsTimer() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14)
        timers.advance(196 - 13)
        burst(15, 14)
        timers.advance(5)                                          // пачка 196..209, второй MOVE ушёл в 196 + 14
        val sent = out.size
        pacer.onTouch(AapMessages.TOUCH_UP, 100, 99, 0, 0)          // UP: сначала вся очередь, потом UP
        assertEquals((17..28).map { it * 10 + AapMessages.TOUCH_MOVE } + (99 * 10 + AapMessages.TOUCH_UP), out.drop(sent).map { it.second })
        timers.advance(1000)
        assertEquals(sent + 13, out.size)                          // после UP таймер ничего не шлёт
        assertEquals(1, pacer.bursts)
    }
}
