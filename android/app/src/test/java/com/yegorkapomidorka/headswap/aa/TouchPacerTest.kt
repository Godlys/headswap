package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPacerTest {
    private val timers = FakeTimers()
    private val out = ArrayList<Pair<Long, Int>>()     // время отправки, y
    private val pacer = TouchBurstPacer({ timers.now }, timers::schedule) { a, _, y, _, _ -> out.add(timers.now to (y * 10 + (a and 0xFF))) }

    private fun move(y: Int) = pacer.onTouch(AapMessages.TOUCH_MOVE, 100, y, 0, 0)

    /** Пачка из n MOVE за ~n мс, как у Delphi. */
    private fun burst(from: Int, n: Int) {
        for (i in 0 until n) {
            move(from + i)
            if (i < n - 1) timers.advance(1)
        }
    }

    @Test
    fun burstIsSpreadOverIntervalAndUpFlushesQueue() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 10)                                               // первая пачка: шаг по умолчанию 16 мс
        assertEquals(listOf(0L to 0, 0L to 12), out)              // DOWN и первый MOVE сразу
        timers.advance(190 - 9)
        burst(11, 10)                                              // вторая: первая уже роздана по 16 мс, шаг теперь 190/10 = 19 мс
        assertEquals((1..11).map { it * 10 + 2 }, out.drop(1).map { it.second })
        val sent = out.size
        timers.advance(19)
        assertEquals(sent + 1, out.size)
        assertEquals(12 * 10 + 2, out.last().second)
        timers.advance(10)
        assertEquals(sent + 1, out.size)                           // следующий MOVE только через 19 мс
        timers.advance(1)
        assertEquals(sent + 2, out.size)
        pacer.onTouch(AapMessages.TOUCH_UP, 100, 99, 0, 0)          // UP: сначала вся очередь, потом UP
        assertEquals((11..20).map { it * 10 + 2 } + (99 * 10 + 1), out.drop(11).map { it.second })
        timers.advance(1000)
        assertEquals(22, out.size)                                 // после UP таймер ничего не шлёт
        assertEquals(1, pacer.bursts)
    }
}
