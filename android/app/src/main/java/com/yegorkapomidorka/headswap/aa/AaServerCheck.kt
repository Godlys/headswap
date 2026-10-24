package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.hardware.usb.UsbManager
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Итог одной пробы сервера ГУ (127.0.0.1:5277). */
enum class ProbeResult {
    /** Полное рукопожатие прошло, сессия закрыта штатно. */
    ALIVE,

    /** ECONNREFUSED: порт никто не слушает. */
    REFUSED,

    /** TCP принят, но VERSION_RESPONSE или TLS не пришли (или порт не принимает подключения). */
    HUNG,

    /** Прочее: нет сертификатов, сервер сразу закрыл соединение, TLS отвергнут, сбой сокета. */
    ERROR,
}

/** Что показывает главный экран про сервер ГУ. */
enum class ServerState { UNKNOWN, CHECKING, ALIVE, DOWN, ERROR }

/** Одна попытка пробы. Настоящая - AaSessionProbe, в тестах подменяется. */
fun interface ServerProbe {
    fun probeOnce(): ProbeResult
}

/**
 * Проба живости сервера ГУ: настоящая AA-сессия (AapSession, как в мосте и AaProbe), а не голый connect.
 * Голое connect/close без рукопожатия вешает сервер 5277, поэтому после TCP попытка идёт до конца:
 * VERSION, TLS, AuthComplete, ответ на SERVICE_DISCOVERY_REQUEST и сразу штатное закрытие (ByeBye, EOF, close).
 * Видео и каналы не открываем: после SDR телефон шлёт запросы каналов, но сессия уже останавливается.
 * Возвращается только когда сессия закрылась полностью (onEnd): иначе мост подключился бы поверх неё.
 */
class AaSessionProbe(
    private val loadCreds: () -> AapCredentials,
    private val sd: () -> AapSdOptions,
    private val log: (String, Boolean) -> Unit,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val sdrWaitMs: Long = SDR_WAIT_MS,
) : ServerProbe {
    private object NoSink : AaVideoSink {
        override fun onCodecConfig(annexB: ByteArray) {}
        override fun onFrame(annexB: ByteArray, ptsUs: Long) {}
    }

    override fun probeOnce(): ProbeResult {
        val creds = try {
            loadCreds()
        } catch (e: Throwable) {
            log("SRVCHECK сертификаты не загрузились: ${e.javaClass.simpleName}: ${e.message}", true)
            return ProbeResult.ERROR
        }
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val connectError = AtomicReference<IOException?>(null)
        val ref = AtomicReference<AapSession?>(null)
        val session = AapSession(
            creds, NoSink, AapOptions(host = host, port = port, sdProvider = sd),
            log = { line, _ ->
                log("SRVCHECK | $line", false)
                // телефон спросил возможности ГУ: это последний шаг, дальше штатное закрытие. Ответ на SDR
                // лог-хук опережает (dispatch логирует до onControl), но в очереди он встанет раньше ByeBye
                if ("RX CONTROL(0) SERVICE_DISCOVERY_REQUEST" in line) ref.get()?.requestStop("проба: SDR получен")
            },
            socketFactory = {
                Socket().also { s ->
                    try {
                        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    } catch (e: IOException) {
                        connectError.set(e)
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                        throw e
                    }
                }
            },
            onEnd = { reason, _ ->
                endReason.set(reason)
                ended.countDown()
            },
        )
        ref.set(session)
        session.start()
        // Пока TCP принят, прерывать нельзя: stop до TLS закрыл бы сокет без ByeBye. Сессия сама ограничена
        // таймаутами (version 10 с, TLS 15 с, bye и EOF ещё несколько секунд), поэтому ждём её конца.
        var establishedAtMs = 0L
        var interrupted = false
        var polls = 0
        while (true) {
            try {
                if (ended.await(50, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true      // нить не бросаем: сессия ещё держит сокет
            }
            if (session.established) {
                val nowMs = System.nanoTime() / 1_000_000
                if (establishedAtMs == 0L) establishedAtMs = nowMs
                else if (nowMs - establishedAtMs > sdrWaitMs) session.requestStop("проба: SDR не пришёл за ${sdrWaitMs / 1000} с")
            }
            if (++polls > MAX_POLLS) {
                log("SRVCHECK сессия не закрылась за ${MAX_POLLS * 50 / 1000} с, выхожу не дожидаясь", true)
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val result = classify(connectError.get(), session.connected, session.established, endReason.get())
        log(
            "SRVCHECK сессия: connected=${session.connected} established=${session.established} закрытие чистое=${session.exitOk} " +
                "rx=${session.stats.rxFrames} tx=${session.stats.txFrames}, причина: ${endReason.get()}",
            true,
        )
        return result
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val SDR_WAIT_MS = 3000L
        private const val MAX_POLLS = 120 * 20      // 120 с при опросе раз в 50 мс

        /** Итог по тому, докуда дошла сессия. Таймауты рукопожатия = HUNG, всё остальное необычное = ERROR. */
        fun classify(connectError: Throwable?, connected: Boolean, established: Boolean, endReason: String): ProbeResult = when {
            !connected -> when {
                connectError is ConnectException && connectError.message?.contains("refused", ignoreCase = true) == true -> ProbeResult.REFUSED
                connectError is SocketTimeoutException -> ProbeResult.HUNG       // backlog полон: порт не принимает
                else -> ProbeResult.ERROR
            }
            established -> ProbeResult.ALIVE
            endReason.startsWith("нет VERSION_RESPONSE") || endReason.startsWith("TLS handshake: таймаут") -> ProbeResult.HUNG
            else -> ProbeResult.ERROR
        }
    }
}

/**
 * Серия проб и правила, когда её можно запускать. Без Android: проба, "занято" и часы приходят снаружи.
 * Одна серия за раз; не чаще раза в minIntervalMs (ранний запуск откладывается, а не отбрасывается);
 * REFUSED повторяется до attempts раз с паузой pauseMs (сразу после ByeBye сервер ГУ ещё не слушает), остальные итоги финальны
 * (повторять голые подключения к зависшему серверу незачем). Попытку, дошедшую до TCP, прервать нельзя:
 * cancelAndAwait отменяет только ожидания и следующие попытки, а текущую дожидается.
 */
class ServerCheckController(
    private val probe: ServerProbe,
    private val busy: () -> Boolean,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val afterCloseDelayMs: Long = AFTER_CLOSE_DELAY_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val staleMs: Long = STALE_MS,
    private val attempts: Int = ATTEMPTS,
    private val pauseMs: Long = PAUSE_MS,
) {
    enum class Trigger { AFTER_CLOSE, APP_OPEN }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // всё ниже под lock
    private var worker: Thread? = null
    private var token = 0
    private var checking = false
    private var result: ProbeResult? = null
    private var finishedAt = NONE
    private var recheck = false
    private var alive = false

    val state: ServerState
        get() = lock.withLock {
            when {
                checking -> ServerState.CHECKING
                else -> when (result) {
                    null -> ServerState.UNKNOWN
                    ProbeResult.ALIVE -> ServerState.ALIVE
                    ProbeResult.REFUSED, ProbeResult.HUNG -> ServerState.DOWN
                    ProbeResult.ERROR -> ServerState.ERROR
                }
            }
        }

    /** Сервер хоть раз отвечал в этом процессе: красное "не отвечает" тогда уместно, а не для новичка. */
    val everAlive: Boolean get() = lock.withLock { alive }

    /** Мост сам закрыл AA (ByeBye позади): проверить сервер через afterCloseDelayMs. */
    fun afterBridgeClosed() = launch(Trigger.AFTER_CLOSE)

    /** Приложение открыто: проверить, если сессии нет, проб не было или последняя старая. */
    fun onAppOpened() = launch(Trigger.APP_OPEN)

    /** Пользователь нажал "Открыть Android Auto": после неудачной пробы вернётся и ждёт свежую, без правила старости. */
    fun onOpenAaRequested() {
        lock.withLock { recheck = result == ProbeResult.REFUSED || result == ProbeResult.HUNG }
    }

    /**
     * Мост подключается к серверу: проба не должна быть на нём ни секунды. Отменяет ожидания и повторы,
     * дожидается текущей попытки (она сама ограничена по времени) и сбрасывает прошлый итог.
     */
    fun cancelAndAwait(timeoutMs: Long) {
        lock.withLock {
            token++
            result = null
            finishedAt = NONE
            recheck = false
            if (worker == null) return
            log("SRVCHECK мост подключается: жду конца пробы")
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var interrupted = false
            while (worker != null && left > 0) {
                try {
                    left = changed.awaitNanos(left)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (worker != null) log("SRVCHECK проба не закончилась за $timeoutMs мс, подключаюсь всё равно")
        }
    }

    /** Дождаться конца запущенной серии (для тестов). */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (worker != null && left > 0) left = changed.awaitNanos(left)
        worker == null
    }

    private fun launch(trigger: Trigger) {
        lock.withLock {
            if (worker != null) {
                log("SRVCHECK пропуск ($trigger): проба уже идёт или ждёт")
                return
            }
            if (trigger == Trigger.APP_OPEN && !allowOpen()) return
            // не чаще раза в minIntervalMs: слишком ранний запуск ждёт, пока интервал не пройдёт
            val sinceLast = if (finishedAt == NONE) Long.MAX_VALUE else now() - finishedAt
            val throttle = if (sinceLast >= minIntervalMs) 0L else minIntervalMs - sinceLast
            val delay = maxOf(if (trigger == Trigger.AFTER_CLOSE) afterCloseDelayMs else 0L, throttle)
            val my = ++token
            checking = true
            val t = Thread({ work(trigger, delay, my) }, "aa-srvcheck")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    /** Правила открытия приложения; зовётся под lock. */
    private fun allowOpen(): Boolean {
        if (busy()) {
            log("SRVCHECK пропуск (APP_OPEN): мост или USB заняты")
            return false
        }
        val age = if (finishedAt == NONE) Long.MAX_VALUE else now() - finishedAt
        val forced = recheck
        if (!forced && age < staleMs) {
            log("SRVCHECK пропуск (APP_OPEN): последняя проба ${age / 1000} с назад")
            return false
        }
        recheck = false
        return true
    }

    private fun work(trigger: Trigger, delayMs: Long, my: Int) {
        var verdict: ProbeResult? = null
        try {
            if (delayMs > 0 && !sleepOrCancel(delayMs, my)) {
                log("SRVCHECK ($trigger) отменена до старта")
                return
            }
            if (!gate(my)) return
            log("SRVCHECK старт ($trigger, ждала ${delayMs} мс)")
            val t0 = now()
            verdict = series(my)
            if (verdict != null) log("SRVCHECK ИТОГ: $verdict за ${now() - t0} мс")
        } catch (e: Throwable) {
            log("SRVCHECK сбой: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            lock.withLock {
                if (verdict != null && token == my) {
                    result = verdict
                    finishedAt = now()
                    if (verdict == ProbeResult.ALIVE) alive = true
                }
                checking = false
                worker = null
                changed.signalAll()
            }
        }
    }

    /** null = серия прервана (занято, отмена): прошлый итог остаётся как был. */
    private fun series(my: Int): ProbeResult? {
        var last: ProbeResult? = null
        for (i in 1..attempts) {
            if (i > 1 && !sleepOrCancel(pauseMs, my)) {
                log("SRVCHECK серия отменена перед попыткой $i")
                return null
            }
            if (!gate(my)) return null
            val t1 = now()
            val r = try {
                probe.probeOnce()
            } catch (e: Throwable) {
                log("SRVCHECK попытка $i/$attempts: сбой ${e.javaClass.simpleName}: ${e.message}")
                ProbeResult.ERROR
            }
            log("SRVCHECK попытка $i/$attempts: $r за ${now() - t1} мс")
            last = r
            if (r != ProbeResult.REFUSED) break
        }
        return last
    }

    /** Можно ли идти на сервер: нас не отменили, мост не работает, USB не подключён. */
    private fun gate(my: Int): Boolean = lock.withLock {
        when {
            token != my -> {
                log("SRVCHECK отменена: мост подключается")
                false
            }
            busy() -> {
                log("SRVCHECK отложена: мост или USB заняты")
                false
            }
            else -> true
        }
    }

    /** false = отменили (cancelAndAwait) за время ожидания. */
    private fun sleepOrCancel(ms: Long, my: Int): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(ms)
        while (token == my && left > 0) {
            try {
                left = changed.awaitNanos(left)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@withLock false
            }
        }
        token == my
    }

    companion object {
        /** После ByeBye сервер ГУ ещё ~6 с не слушает: даём ему время до первой попытки. */
        const val AFTER_CLOSE_DELAY_MS = 8_000L
        const val MIN_INTERVAL_MS = 60_000L
        const val STALE_MS = 10 * 60_000L
        const val ATTEMPTS = 4
        const val PAUSE_MS = 5_000L
        private const val NONE = Long.MIN_VALUE
    }
}

/**
 * Проверка живости сервера ГУ для главного экрана. Состояние (`state`) читает UI, сигналы шлют сервис, activity и кнопки.
 * Не работает, пока жив мост или подключён USB-аксессуар: одноразовый сервер 5277 тогда нужен ему.
 */
object AaServerCheck {
    @Volatile private var ctl: ServerCheckController? = null

    /** Сколько мост ждёт конец пробы перед подключением. Попытка сама укладывается в ~35 с. */
    private const val AWAIT_MS = 45_000L

    val state: ServerState get() = ctl?.state ?: ServerState.UNKNOWN
    val everAlive: Boolean get() = ctl?.everAlive ?: false

    private fun controller(ctx: Context): ServerCheckController {
        ctl?.let { return it }
        synchronized(this) {
            ctl?.let { return it }
            val app = ctx.applicationContext
            val usb = app.getSystemService(UsbManager::class.java)
            val c = ServerCheckController(
                probe = AaSessionProbe(
                    loadCreds = { AaCreds.load(app, AaPrefs(app).certSet).creds },
                    sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false) },
                    log = { line, ui -> AppLog.i(line, ui) },
                ),
                busy = { CarLifeState.running || usb.accessoryList?.isNotEmpty() == true },
                now = { System.nanoTime() / 1_000_000 },
                log = { AppLog.i(it) },
            )
            ctl = c
            return c
        }
    }

    /** MainActivity.onResume. */
    fun onAppOpened(ctx: Context) = controller(ctx).onAppOpened()

    /** Мост закрыл AA штатно (grace истёк или Стоп). */
    fun afterBridgeClosed(ctx: Context) = controller(ctx).afterBridgeClosed()

    /** Кнопка "Открыть Android Auto". */
    fun onOpenAaRequested(ctx: Context) = controller(ctx).onOpenAaRequested()

    /** AaConnector перед каждым подключением к серверу ГУ (нить коннектора, не main). */
    fun beforeBridgeConnect() {
        ctl?.cancelAndAwait(AWAIT_MS)
    }
}
