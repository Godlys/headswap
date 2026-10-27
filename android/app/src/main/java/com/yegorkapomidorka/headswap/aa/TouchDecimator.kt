package com.yegorkapomidorka.headswap.aa

/**
 * 单指 MOVE 事件稀疏化（设置项 [TouchDecimation]）。VW CNS2 Delphi 之类的车机每秒最多发送约 70 个事件，
 * 而视频只有最多 30 帧/秒，多余的点 Android Auto 也不会显示。在连续的单指 MOVE 中，从上一个其他事件之后的
 * 第一个起，每 every 个放行一个。被跳过的 MOVE 会作为挂起事件保留：任何其他事件（DOWN、UP、第二根手指、
 * 带第二根手指坐标的 MOVE）都会先补发它，因为手指抬起前的最后一个点不能丢失。同步实现，无定时器。
 */
class TouchDecimator(
    private val sink: (action: Int, x: Int, y: Int, px: Int, py: Int) -> Unit,
) {
    private class Ev(val action: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private val lock = Any()
    private var count = 0                      // 自上一个其他事件以来的单指 MOVE 数量
    private var pending: Ev? = null            // 最近一次被跳过的 MOVE

    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int, every: Int) = synchronized(lock) {
        val single = (action and 0xFF) == AapMessages.TOUCH_MOVE && px == 0 && py == 0 && action ushr 8 == 0
        if (every <= 1 || !single) {
            flushLocked()
            count = 0
            sink(action, x, y, px, py)
            return@synchronized
        }
        if (count++ % every == 0) {
            pending = null
            sink(action, x, y, px, py)
        } else {
            pending = Ev(action, x, y, px, py)
        }
    }

    /** 会话已关闭：重置计数器和挂起的 MOVE。 */
    fun reset() = synchronized(lock) {
        count = 0
        pending = null
    }

    private fun flushLocked() {
        val e = pending ?: return
        pending = null
        sink(e.action, e.x, e.y, e.px, e.py)
    }
}
