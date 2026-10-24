package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchDecimatorTest {
    private val out = ArrayList<String>()      // "D", "M<y>", "U", "P<y>" (второй палец / MOVE с px,py)
    private val dec = TouchDecimator { a, _, y, px, _ ->
        out.add(
            when {
                px != 0 -> "P$y"
                (a and 0xFF) == AapMessages.TOUCH_DOWN -> "D"
                (a and 0xFF) == AapMessages.TOUCH_UP -> "U"
                else -> "M$y"
            },
        )
    }

    private fun down(every: Int) = dec.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0, every)
    private fun up(every: Int) = dec.onTouch(AapMessages.TOUCH_UP, 100, 0, 0, 0, every)
    private fun move(y: Int, every: Int) = dec.onTouch(AapMessages.TOUCH_MOVE, 100, y, 0, 0, every)

    @Test
    fun offPassesEverything() {
        down(1)
        for (y in 1..5) move(y, 1)
        up(1)
        assertEquals(listOf("D", "M1", "M2", "M3", "M4", "M5", "U"), out)
    }

    @Test
    fun x2SkipsEverySecondMove() {
        down(2)
        for (y in 1..5) move(y, 2)
        up(2)
        assertEquals(listOf("D", "M1", "M3", "M5", "U"), out)
    }

    @Test
    fun x2KeepsLastSkippedMoveBeforeUp() {
        down(2)
        for (y in 1..4) move(y, 2)
        up(2)
        assertEquals(listOf("D", "M1", "M3", "M4", "U"), out)
    }

    @Test
    fun secondFingerFlushesPending() {
        down(2)
        move(1, 2)
        move(2, 2)                                                 // пропущен, ждёт
        dec.onTouch(5 or (1 shl 8), 100, 2, 300, 300, 2)           // POINTER_DOWN
        move(3, 2)                                                 // счёт заново: уходит первым
        dec.onTouch(AapMessages.TOUCH_MOVE, 100, 4, 310, 310, 2)   // MOVE с вторым пальцем идёт как есть
        assertEquals(listOf("D", "M1", "M2", "P2", "M3", "P4"), out)
    }

    @Test
    fun newGestureCountsFromFirstMove() {
        down(2)
        move(1, 2)
        move(2, 2)
        up(2)
        down(2)
        move(5, 2)                                                 // первый MOVE после DOWN уходит всегда
        assertEquals(listOf("D", "M1", "M2", "U", "D", "M5"), out)
    }

    @Test
    fun resetDropsPending() {
        down(2)
        move(1, 2)
        move(2, 2)
        dec.reset()
        up(2)
        assertEquals(listOf("D", "M1", "U"), out)
    }
}
