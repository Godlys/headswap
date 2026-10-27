package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchDecimatorTest {
    private val out = ArrayList<String>()      // "D", "M<y>", "U", "P<y>"（第二根手指 / 带 px,py 的 MOVE）
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
        move(2, 2)                                                 // 被跳过，等待中
        dec.onTouch(5 or (1 shl 8), 100, 2, 300, 300, 2)           // POINTER_DOWN
        move(3, 2)                                                 // 重新计数：第一个发出
        dec.onTouch(AapMessages.TOUCH_MOVE, 100, 4, 310, 310, 2)   // 带第二根手指的 MOVE 原样发出
        assertEquals(listOf("D", "M1", "M2", "P2", "M3", "P4"), out)
    }

    @Test
    fun newGestureCountsFromFirstMove() {
        down(2)
        move(1, 2)
        move(2, 2)
        up(2)
        down(2)
        move(5, 2)                                                 // DOWN 之后的第一个 MOVE 总是发出
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
