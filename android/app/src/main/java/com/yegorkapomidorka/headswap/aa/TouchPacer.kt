package com.yegorkapomidorka.headswap.aa

/**
 * 触摸突发（burst）平滑（h425，VW CNS2 Delphi 20082114）。车机累积 MOVE 约 196 毫秒，然后在 5-20 毫秒内
 * 以 9-14 个事件一批发出：点的坐标完整但时间被打乱，Android Auto 中滚动以约 5 Hz 的频率卡顿。每批的第一个
 * MOVE 立即发出，其余的均匀分摊到两批之间的间隔：第 k 个在 开始 + k * 间隔 / 批次大小 时刻发出。批次大小取当前批
 * 并随每个事件重新计算：首次发送时整批通常已全部入队，而按上一批猜测不可靠——手势边缘处批次更短。间隔取
 * 无手指停顿各批次的平均值。代价是指尖跟踪固定延迟一批。DOWN / UP / 第二根手指：先补发队列，再原样发出该事件。
 */
class TouchBurstPacer(
    private val clockMs: () -> Long,
    private val schedule: (Long, Runnable) -> MicTimerHandle,
    private val sink: (action: Int, x: Int, y: Int, px: Int, py: Int) -> Unit,
) {
    private class Ev(val action: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private val lock = Any()
    private val queue = ArrayDeque<Ev>()
    private var tick: MicTimerHandle? = null
    private var gen = 0
    private var lastArrival = NEVER
    private var burstStart = NEVER
    private var burstSize = 0
    private var sent = 0                       // 当前批次已发送的事件数
    private var interval = DEFAULT_INTERVAL_MS

    /** 已平滑的批次数量（用于日志）。 */
    var bursts = 0
        private set

    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int) = synchronized(lock) {
        val act = action and 0xFF
        val single = px == 0 && py == 0 && action ushr 8 == 0
        if (act != AapMessages.TOUCH_MOVE || !single) {
            flushLocked()
            resetBurstLocked()
            sink(action, x, y, px, py)
            return@synchronized
        }
        val now = clockMs()
        val newBurst = now - lastArrival > BURST_GAP_MS
        lastArrival = now
        if (newBurst) {
            if (burstStart != NEVER) {
                if (burstSize > 1) bursts++
                val gap = now - burstStart
                // 手指停住了（车机一两批没发）：不计入平均值
                if (gap <= interval * 3 / 2) interval = ((interval + gap + 1) / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            }
            // 上一批的尾部还没来得及分发：补发，避免延迟累积
            flushLocked()
            burstStart = now
            burstSize = 1
            sent = 1
            sink(action, x, y, px, py)
            return@synchronized
        }
        burstSize++
        queue.addLast(Ev(action, x, y, px, py))
        armLocked()
    }

    /** 会话已关闭：丢弃队列和定时器。 */
    fun reset() = synchronized(lock) {
        queue.clear()
        cancelTickLocked()
        resetBurstLocked()
        interval = DEFAULT_INTERVAL_MS
    }

    /** 为队列中下一个事件按当前批次大小设置定时器；批次新事件会将其提前。 */
    private fun armLocked() {
        cancelTickLocked()
        if (queue.isEmpty()) return
        val at = burstStart + sent * interval / burstSize
        val g = ++gen
        tick = schedule((at - clockMs()).coerceAtLeast(0), Runnable {
            synchronized(lock) {
                if (g != gen) return@synchronized
                tick = null
                val e = queue.removeFirstOrNull() ?: return@synchronized
                sink(e.action, e.x, e.y, e.px, e.py)
                sent++
                armLocked()
            }
        })
    }

    private fun flushLocked() {
        cancelTickLocked()
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            sink(e.action, e.x, e.y, e.px, e.py)
        }
    }

    private fun cancelTickLocked() {
        gen++
        tick?.cancel()
        tick = null
    }

    private fun resetBurstLocked() {
        lastArrival = NEVER
        burstStart = NEVER
        burstSize = 0
        sent = 0
    }

    companion object {
        // h425 日志 22.10（66 批）：批内间隔 0-4 ms，批间至少 176 ms，间隔中位数 196 ms，p10-p90 191-199
        const val BURST_GAP_MS = 7L
        const val DEFAULT_INTERVAL_MS = 196L
        const val MIN_INTERVAL_MS = 30L
        const val MAX_INTERVAL_MS = 300L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
