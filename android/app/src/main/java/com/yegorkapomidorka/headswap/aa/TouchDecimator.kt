package com.yegorkapomidorka.headswap.aa

/**
 * Прореживание MOVE одного пальца (настройка [TouchDecimation]). ГУ вроде VW CNS2 Delphi шлёт до ~70 событий в секунду,
 * видео идёт до 30 к/с, лишние точки AA не показывает. Из подряд идущих одиночных MOVE уходит каждый every-й, считая
 * с первого после последнего другого события. Пропущенный MOVE держим как отложенный: любое другое событие (DOWN, UP,
 * второй палец, MOVE с координатами второго пальца) сначала досылает его, потому что последняя точка перед отпусканием
 * пальца не должна теряться. Синхронный, без таймеров.
 */
class TouchDecimator(
    private val sink: (action: Int, x: Int, y: Int, px: Int, py: Int) -> Unit,
) {
    private class Ev(val action: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private val lock = Any()
    private var count = 0                      // одиночных MOVE с последнего другого события
    private var pending: Ev? = null            // последний пропущенный MOVE

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

    /** Сессия закрыта: счётчик и отложенный MOVE сбрасываем. */
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
