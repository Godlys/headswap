package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Серия проб сервера ГУ на фейковом транспорте (без сети): ретраи, ограничения, гейты, отмена. */
class AaServerCheckTest {
    private class FakeProbe(private vararg val script: ProbeResult) : ServerProbe {
        val calls = AtomicInteger()
        val times = CopyOnWriteArrayList<Long>()

        @Volatile var gate: CountDownLatch? = null
        @Volatile var entered = CountDownLatch(1)

        override fun probeOnce(): ProbeResult {
            val i = calls.getAndIncrement()
            times.add(System.nanoTime() / 1_000_000)
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            return script[minOf(i, script.size - 1)]
        }
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val clock = AtomicLong(1_000_000L)
    @Volatile private var busy = false

    private fun controller(
        probe: ServerProbe,
        afterCloseDelayMs: Long = 20,
        minIntervalMs: Long = 100,
        staleMs: Long = 600_000,
        pauseMs: Long = 10,
        real: Boolean = false,
    ) = ServerCheckController(
        probe, { busy }, { if (real) System.nanoTime() / 1_000_000 else clock.get() }, { logs.add(it) },
        afterCloseDelayMs = afterCloseDelayMs, minIntervalMs = minIntervalMs, staleMs = staleMs, attempts = 4, pauseMs = pauseMs,
    )

    private fun run(c: ServerCheckController, trigger: () -> Unit) {
        trigger()
        assertTrue("серия не закончилась\n" + logs.joinToString("\n"), c.awaitIdle(5000))
    }

    @Test
    fun refusedIsRetriedUntilServerAnswers() {
        val p = FakeProbe(ProbeResult.REFUSED, ProbeResult.REFUSED, ProbeResult.ALIVE)
        val c = controller(p)
        run(c) { c.afterBridgeClosed() }
        assertEquals(3, p.calls.get())
        assertEquals(ServerState.ALIVE, c.state)
        assertTrue(c.everAlive)
    }

    @Test
    fun refusedAllFourAttemptsIsDownByLastResult() {
        val p = FakeProbe(ProbeResult.REFUSED)
        val c = controller(p, pauseMs = 40)
        run(c) { c.afterBridgeClosed() }
        assertEquals(4, p.calls.get())
        assertEquals(ServerState.DOWN, c.state)
        assertFalse(c.everAlive)
        // пауза между попытками выдержана
        val gaps = p.times.zipWithNext { a, b -> b - a }
        assertTrue(gaps.toString(), gaps.all { it >= 35 })
    }

    @Test
    fun refusedThenHungFinishesWithHung() {
        val p = FakeProbe(ProbeResult.REFUSED, ProbeResult.HUNG, ProbeResult.ALIVE)
        val c = controller(p)
        run(c) { c.afterBridgeClosed() }
        assertEquals("HUNG финален: голые подключения к зависшему серверу не повторяем", 2, p.calls.get())
        assertEquals(ServerState.DOWN, c.state)
    }

    @Test
    fun errorIsFinalAndIsNotDown() {
        val p = FakeProbe(ProbeResult.ERROR, ProbeResult.ALIVE)
        val c = controller(p)
        run(c) { c.afterBridgeClosed() }
        assertEquals(1, p.calls.get())
        assertEquals(ServerState.ERROR, c.state)
    }

    @Test
    fun afterCloseWaitsEightSecondsRule() {
        val p = FakeProbe(ProbeResult.ALIVE)
        val c = controller(p, afterCloseDelayMs = 150, real = true)
        val t0 = System.nanoTime() / 1_000_000
        c.afterBridgeClosed()
        assertEquals(ServerState.CHECKING, c.state)
        assertTrue(c.awaitIdle(5000))
        assertTrue("проба раньше задержки: ${p.times[0] - t0} мс", p.times[0] - t0 >= 140)
        assertEquals(8_000L, ServerCheckController.AFTER_CLOSE_DELAY_MS)
        assertEquals(60_000L, ServerCheckController.MIN_INTERVAL_MS)
        assertEquals(4, ServerCheckController.ATTEMPTS)
        assertEquals(5_000L, ServerCheckController.PAUSE_MS)
    }

    @Test
    fun busyBridgeOrUsbSkipsProbeAndKeepsOldState() {
        val p = FakeProbe(ProbeResult.ALIVE)
        val c = controller(p)
        busy = true
        run(c) { c.afterBridgeClosed() }
        run(c) { c.onAppOpened() }
        assertEquals(0, p.calls.get())
        assertEquals(ServerState.UNKNOWN, c.state)
        busy = false
        run(c) { c.onAppOpened() }
        assertEquals(1, p.calls.get())
        assertEquals(ServerState.ALIVE, c.state)
    }

    @Test
    fun busyDuringSeriesStopsBeforeNextAttempt() {
        val busyAfterFirst = object : ServerProbe {
            val calls = AtomicInteger()
            override fun probeOnce(): ProbeResult {
                calls.incrementAndGet()
                busy = true                 // USB подключили между попытками
                return ProbeResult.REFUSED
            }
        }
        val c = controller(busyAfterFirst)
        run(c) { c.afterBridgeClosed() }
        assertEquals(1, busyAfterFirst.calls.get())
        assertEquals("итога нет: серия прервана", ServerState.UNKNOWN, c.state)
    }

    @Test
    fun appOpenProbesOnlyWhenNeverProbedOrOlderThanTenMinutes() {
        val p = FakeProbe(ProbeResult.ALIVE)
        val c = controller(p)
        run(c) { c.onAppOpened() }
        assertEquals("проб не было", 1, p.calls.get())
        clock.addAndGet(9 * 60_000L)
        run(c) { c.onAppOpened() }
        assertEquals("9 минут: свежая", 1, p.calls.get())
        clock.addAndGet(2 * 60_000L)
        run(c) { c.onAppOpened() }
        assertEquals("11 минут: старая", 2, p.calls.get())
    }

    @Test
    fun recheckAfterOpenAaSkipsStalenessButNotThrottle() {
        val p = FakeProbe(ProbeResult.REFUSED, ProbeResult.REFUSED, ProbeResult.REFUSED, ProbeResult.REFUSED, ProbeResult.ALIVE)
        val c = controller(p, minIntervalMs = 200, real = true)
        run(c) { c.afterBridgeClosed() }
        assertEquals(ServerState.DOWN, c.state)
        assertEquals(4, p.calls.get())
        val finished = System.nanoTime() / 1_000_000
        // без нажатия «Открыть Android Auto» свежий отказ повторно не проверяется
        run(c) { c.onAppOpened() }
        assertEquals(4, p.calls.get())
        c.onOpenAaRequested()
        run(c) { c.onAppOpened() }
        assertEquals(5, p.calls.get())
        assertEquals(ServerState.ALIVE, c.state)
        // не чаще minInterval: ранний повтор отложен, а не отброшен
        assertTrue("повтор через ${p.times[4] - finished} мс", p.times[4] - finished >= 100)
    }

    @Test
    fun openAaWithoutPriorFailureDoesNotForceRecheck() {
        val p = FakeProbe(ProbeResult.ALIVE)
        val c = controller(p)
        run(c) { c.onAppOpened() }
        c.onOpenAaRequested()
        run(c) { c.onAppOpened() }
        assertEquals(1, p.calls.get())
    }

    @Test
    fun secondTriggerWhileRunningIsSkipped() {
        val p = FakeProbe(ProbeResult.ALIVE)
        p.gate = CountDownLatch(1)
        val c = controller(p)
        c.onAppOpened()
        assertTrue(p.entered.await(5, TimeUnit.SECONDS))
        c.onAppOpened()
        c.afterBridgeClosed()
        p.gate!!.countDown()
        assertTrue(c.awaitIdle(5000))
        assertEquals("одна проба за раз", 1, p.calls.get())
    }

    @Test
    fun bridgeConnectingCancelsPendingProbeAndDropsOldResult() {
        val p = FakeProbe(ProbeResult.ALIVE)
        val c = controller(p, afterCloseDelayMs = 5_000)
        c.afterBridgeClosed()
        c.cancelAndAwait(2000)
        assertTrue(c.awaitIdle(2000))
        assertEquals(0, p.calls.get())
        assertEquals(ServerState.UNKNOWN, c.state)
    }

    @Test
    fun bridgeConnectingWaitsForAttemptInFlight() {
        val p = FakeProbe(ProbeResult.REFUSED)
        p.gate = CountDownLatch(1)
        val c = controller(p)
        c.onAppOpened()
        assertTrue(p.entered.await(5, TimeUnit.SECONDS))
        val returned = AtomicLong(0)
        val t = Thread {
            c.cancelAndAwait(5000)
            returned.set(System.nanoTime())
        }
        t.start()
        Thread.sleep(150)
        assertEquals("мост не должен подключаться, пока попытка держит сервер", 0L, returned.get())
        val released = System.nanoTime()
        p.gate!!.countDown()
        t.join(5000)
        assertTrue(returned.get() >= released)
        assertEquals("после отмены повторов нет", 1, p.calls.get())
        assertEquals("итог отменённой серии не записан", ServerState.UNKNOWN, c.state)
        assertFalse(c.everAlive)
    }

    @Test
    fun probeExceptionIsErrorNotCrash() {
        val c = controller({ throw IllegalStateException("boom") })
        run(c) { c.onAppOpened() }
        assertEquals(ServerState.ERROR, c.state)
    }

    // --- классификация итога настоящей пробы ---

    @Test
    fun classifyConnectFailures() {
        val refusedAndroid = ConnectException("failed to connect to /127.0.0.1 (port 5277) from /:: (port 4242) after 3000ms: isConnectionRefused failed: ECONNREFUSED (Connection refused)")
        val c = AaSessionProbe.Companion
        assertEquals(ProbeResult.REFUSED, c.classify(refusedAndroid, false, false, "сервер недоступен"))
        assertEquals(ProbeResult.REFUSED, c.classify(ConnectException("Connection refused: connect"), false, false, "-"))
        assertEquals(ProbeResult.HUNG, c.classify(SocketTimeoutException("failed to connect after 3000ms"), false, false, "-"))
        assertEquals(ProbeResult.ERROR, c.classify(SocketException("socket failed: EPERM (Operation not permitted)"), false, false, "-"))
        assertEquals(ProbeResult.ERROR, c.classify(null, false, false, "не подключено"))
    }

    @Test
    fun classifyHandshakeOutcomes() {
        val c = AaSessionProbe.Companion
        assertEquals(ProbeResult.ALIVE, c.classify(null, true, true, "проба: SDR получен"))
        assertEquals(ProbeResult.HUNG, c.classify(null, true, false, "нет VERSION_RESPONSE. Если соединение открыто, но тишина: сервер ГУ завис"))
        assertEquals(ProbeResult.HUNG, c.classify(null, true, false, "TLS handshake: таймаут"))
        assertEquals("сервер сразу закрыл соединение (h224): не утверждаем, что он умер", ProbeResult.ERROR, c.classify(null, true, false, "peer closed: телефон закрыл соединение (EOF)"))
        assertEquals(ProbeResult.ERROR, c.classify(null, true, false, "TLS handshake не прошёл: SSLHandshakeException"))
    }

    // --- настоящая AapSession против фейкового "телефона" по loopback (как AapSessionTest) ---

    private val creds by lazy { AapCerts.load(TestAssets.opener, "jvc") }

    private fun probe(port: Int) = AaSessionProbe(
        loadCreds = { creds }, sd = { AapSdOptions() }, log = { l, _ -> logs.add(l) },
        port = port, sdrWaitMs = 1500,
    )

    @Test
    fun realProbeAgainstFakePhoneClosesWithByeBye() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            lateinit var phone: AapSessionTest.Phone
            val accepted = CountDownLatch(1)
            Thread {
                phone = AapSessionTest.Phone(server.accept(), "jvc", false, "no_video")
                accepted.countDown()
                phone.start()
            }.start()
            val r = probe(server.localPort).probeOnce()
            assertEquals(logs.joinToString("\n"), ProbeResult.ALIVE, r)
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            phone.join(10_000)
            assertFalse("скрипт телефона завис", phone.isAlive)
            // скрипт no_video проверяет: SDR получил ответ, затем пришёл именно ByeBye, и только потом EOF
            assertNull("ошибка телефона: ${phone.error}\n" + logs.joinToString("\n"), phone.error)
            assertTrue("HU не закрыл сокет штатно (EOF)", phone.sawEof && !phone.reset)
            assertTrue(logs.any { it.contains("SRVCHECK сессия: connected=true established=true закрытие чистое=true") })
        }
    }

    @Test
    fun realProbeOnClosedPortIsRefused() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(logs.joinToString("\n"), ProbeResult.REFUSED, probe(port).probeOnce())
    }

    @Test
    fun realProbeOnServerThatClosesAtOnceIsErrorNotDown() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            Thread {
                try {
                    server.accept().close()
                } catch (_: IOException) {
                }
            }.start()
            assertEquals(logs.joinToString("\n"), ProbeResult.ERROR, probe(server.localPort).probeOnce())
        }
    }
}
