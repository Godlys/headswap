package com.yegorkapomidorka.headswap.aa

/**
 * Сглаживание пачек тача (h425, VW CNS2 Delphi 20082114). ГУ копит MOVE ~190 мс и отдаёт пачкой по 10-14 событий
 * за 5-7 мс: точки целые, время сбито, в AA скролл идёт рывками с частотой ~5 Гц. Первый MOVE пачки уходит сразу,
 * остальные раздаём равномерно на интервал между пачками (шаг = интервал / размер прошлой пачки). Цена - постоянная
 * задержка следования за пальцем на одну пачку. DOWN / UP / второй палец: сначала досылаем очередь, потом событие как есть.
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
    private var interval = DEFAULT_INTERVAL_MS
    private var lastBurstSize = 0
    private var step = DEFAULT_STEP_MS

    /** Сколько пачек разглажено (для лога). */
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
                interval = (now - burstStart).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                if (burstSize > 1) {
                    lastBurstSize = burstSize
                    bursts++
                }
            }
            burstStart = now
            burstSize = 1
            // хвост прошлой пачки не успели раздать: досылаем, чтобы задержка не копилась
            flushLocked()
            step = if (lastBurstSize > 1) (interval / lastBurstSize).coerceIn(MIN_STEP_MS, MAX_STEP_MS) else DEFAULT_STEP_MS
            sink(action, x, y, px, py)
            return@synchronized
        }
        burstSize++
        queue.addLast(Ev(action, x, y, px, py))
        if (tick == null) armLocked()
    }

    /** Сессия закрыта: очередь и таймер выбрасываем. */
    fun reset() = synchronized(lock) {
        queue.clear()
        cancelTickLocked()
        resetBurstLocked()
        interval = DEFAULT_INTERVAL_MS
        lastBurstSize = 0
        step = DEFAULT_STEP_MS
    }

    private fun armLocked() {
        val g = ++gen
        tick = schedule(step, Runnable {
            synchronized(lock) {
                if (g != gen) return@synchronized
                tick = null
                val e = queue.removeFirstOrNull() ?: return@synchronized
                sink(e.action, e.x, e.y, e.px, e.py)
                if (queue.isNotEmpty()) armLocked()
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
    }

    companion object {
        const val BURST_GAP_MS = 5L          // события пачки идут через 0-2 мс, между пачками ~190 мс
        const val DEFAULT_INTERVAL_MS = 190L
        const val MIN_INTERVAL_MS = 30L
        const val MAX_INTERVAL_MS = 300L
        const val DEFAULT_STEP_MS = 16L
        const val MIN_STEP_MS = 8L
        const val MAX_STEP_MS = 40L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
