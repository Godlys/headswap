package com.yegorkapomidorka.headswap.aa

/** Элемент очереди кормления транскодера: кадр AA или команда нити кормления. */
sealed class FeedItem {
    class Frame(val annexB: ByteArray, val ptsUs: Long, val idr: Boolean, val atNs: Long) : FeedItem()
    class Config(val annexB: ByteArray) : FeedItem()
    object Reset : FeedItem()
    object Stop : FeedItem()
}

enum class Offer { ACCEPTED, DROPPED_FULL, DROPPED_GATED }

/**
 * Очередь между нитью AA и нитью кормления декодера (чистая JVM-логика). Вход никогда не блокируется.
 * Кадры ограничены [maxFrames]: при переполнении кадр теряется и очередь «закрывается» до следующего IDR
 * (P без опорного кадра декодеру не нужны). IDR самодостаточен: он вытесняет все ожидающие кадры
 * (команды остаются на местах), снимает закрытие и проходит всегда. Команды ([post]) не отказывают.
 * Один потребитель ([take]), несколько производителей.
 */
class FeedQueue(private val maxFrames: Int = DEFAULT_MAX_FRAMES) {
    private val items = ArrayDeque<FeedItem>()
    private var frames = 0
    private var gated = false
    private var maxSeen = 0
    private val mon = Object()

    val pendingFrames: Int get() = synchronized(mon) { frames }

    /** Максимум глубины по кадрам с прошлого [resetMaxPending] (без сброса). */
    val maxPendingSeen: Int get() = synchronized(mon) { maxSeen }

    /** Возвращает максимум глубины с прошлого вызова и начинает окно заново с текущей глубины. */
    fun resetMaxPending(): Int = synchronized(mon) {
        val m = maxSeen
        maxSeen = frames
        m
    }

    fun offerFrame(f: FeedItem.Frame): Offer = synchronized(mon) {
        if (gated && !f.idr) return Offer.DROPPED_GATED
        if (f.idr) {
            if (frames > 0) {
                items.removeAll { it is FeedItem.Frame }
                frames = 0
            }
            gated = false
        } else if (frames >= maxFrames) {
            gated = true
            return Offer.DROPPED_FULL
        }
        items.addLast(f)
        frames++
        if (frames > maxSeen) maxSeen = frames
        mon.notifyAll()
        Offer.ACCEPTED
    }

    /** Config/Reset/Stop: кладётся в хвост всегда, независимо от заполнения. */
    fun post(cmd: FeedItem) {
        require(cmd !is FeedItem.Frame) { "кадры - через offerFrame" }
        synchronized(mon) {
            items.addLast(cmd)
            mon.notifyAll()
        }
    }

    /** Ждёт элемент до [timeoutMs] мс; null - таймаут (или прерывание нити). */
    fun take(timeoutMs: Long): FeedItem? = synchronized(mon) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (items.isEmpty()) {
            val leftNs = deadline - System.nanoTime()
            if (leftNs <= 0) return null
            try {
                mon.wait(leftNs / 1_000_000L, (leftNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val x = items.removeFirst()
        if (x is FeedItem.Frame) frames--
        x
    }

    /** Выбросить всё и снять закрытие. */
    fun clear() = synchronized(mon) {
        items.clear()
        frames = 0
        gated = false
    }

    companion object {
        /**
         * ~0,5 с при 30 fps. Декодер создаётся 80-300 мс (на 60 fps это до 18 кадров), и эти кадры должны дождаться
         * его, а не выбить IDR-запрос к AA на каждом старте. На 4 кадрах пачка из 10 кадров переполняла очередь
         * прямо во время createDecoder (transcode_test burst, 23.10.2026).
         */
        const val DEFAULT_MAX_FRAMES = 16
    }
}
