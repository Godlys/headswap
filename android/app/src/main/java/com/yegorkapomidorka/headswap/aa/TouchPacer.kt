package com.yegorkapomidorka.headswap.aa

/**
 * Сглаживание пачек тача (h425, VW CNS2 Delphi 20082114). ГУ копит MOVE ~196 мс и отдаёт пачкой по 9-14 событий
 * за 5-20 мс: точки целые, время сбито, в AA скролл идёт рывками с частотой ~5 Гц. Первый MOVE пачки уходит сразу,
 * остальные раздаём равномерно на интервал между пачками: k-й - в момент начало + k * интервал / размер пачки. Размер
 * берём у текущей пачки и пересчитываем на каждое её событие: к первой отправке пачка обычно уже вся в очереди, а угадывать
 * по прошлой нельзя - на краях жеста пачки короче. Интервал - среднее по пачкам без пауз пальца. Цена - постоянная
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
    private var sent = 0                       // сколько событий текущей пачки уже отправлено
    private var interval = DEFAULT_INTERVAL_MS

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
                if (burstSize > 1) bursts++
                val gap = now - burstStart
                // палец стоял (пачку-другую ГУ не прислала): в среднее не берём
                if (gap <= interval * 3 / 2) interval = ((interval + gap + 1) / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            }
            // хвост прошлой пачки не успели раздать: досылаем, чтобы задержка не копилась
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

    /** Сессия закрыта: очередь и таймер выбрасываем. */
    fun reset() = synchronized(lock) {
        queue.clear()
        cancelTickLocked()
        resetBurstLocked()
        interval = DEFAULT_INTERVAL_MS
    }

    /** Таймер на следующее событие очереди по текущему размеру пачки; новое событие пачки переставляет его раньше. */
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
        // лог h425 22.10 (66 пачек): внутри пачки зазоры 0-4 мс, между пачками от 176 мс, интервал медиана 196 мс, p10-p90 191-199
        const val BURST_GAP_MS = 7L
        const val DEFAULT_INTERVAL_MS = 196L
        const val MIN_INTERVAL_MS = 30L
        const val MAX_INTERVAL_MS = 300L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
