package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Автозапуск сервера ГУ в цикле коннектора: до первой попытки, после конца AA-сессии, повтор при недоступном сервере. */
class AaConnectorPrepTest {
    private val events = CopyOnWriteArrayList<String>()
    private val creds = AapCerts.load(TestAssets.opener, "jvc")

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }

    private fun connector(prep: ((ServerPhase) -> Unit)?, socket: () -> java.net.Socket): AaConnector {
        val core = AaBridgeCore({ _, _ -> })
        return AaConnector(
            core, creds, { _, _ -> },
            options = { AapOptions(preVideoTimeoutMs = 1000) },
            retryMs = 50,
            serverPrep = prep,
            sessionFactory = { sink, o, lg, end ->
                events.add("session")
                AapSession(creds, sink, o, lg, socketFactory = socket, onEnd = end)
            },
        )
    }

    @Test
    fun prepRunsBeforeFirstConnectionAttemptAndBlocksIt() {
        val c = connector({ phase ->
            events.add("prep:$phase:start")
            Thread.sleep(300)
            events.add("prep:$phase:end")
        }) { throw IOException("refused") }
        c.start()
        waitFor { events.count { it == "session" } >= 1 }
        c.stop()
        assertEquals(listOf("prep:FIRST:start", "prep:FIRST:end", "session"), events.take(3))
    }

    @Test
    fun noPrepMeansOldBehaviour() {
        val c = connector(null) { throw IOException("refused") }
        c.start()
        waitFor { events.count { it == "session" } >= 3 }
        c.stop()
        assertTrue(events.count { it == "session" } >= 3)
    }

    @Test
    fun bareEofDoesNotRestartServerAndSuspendsAutostart() {
        // «сервер» принимает и сразу закрывает: connect+EOF без сессии (h224). Перезапуск сервера тут не нужен:
        // AFTER_SESSION не просим, паузы растут, после BARE_EOF_SUSPEND подряд автозапуск приостановлен.
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        server.use {
            Thread {
                try {
                    while (true) server.accept().close()
                } catch (_: IOException) {
                }
            }.start()
            val c = connector({ events.add("prep:$it") }) {
                java.net.Socket().also { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2000) }
            }
            c.start()
            waitFor { c.prepSuspended && events.count { it == "session" } > AaConnector.BARE_EOF_SUSPEND }
            c.stop()
            assertTrue(events.toString(), c.prepSuspended)
            assertTrue(c.bareEofs >= AaConnector.BARE_EOF_SUSPEND)
            assertEquals(listOf("prep:FIRST", "session"), events.take(2))
            assertEquals(events.toString(), listOf("prep:FIRST"), events.filter { it.startsWith("prep:") })
        }
    }

    @Test
    fun retryPrepIsRequestedWhileServerStaysUnavailable() {
        val c = connector({ events.add("prep:$it") }) { throw IOException("refused") }
        c.start()
        waitFor { events.contains("prep:RETRY") }
        c.stop()
        assertTrue(events.toString(), events.contains("prep:RETRY"))
        assertEquals("prep:FIRST", events.first())
        assertTrue("RETRY не раньше второй неудачной попытки", events.take(events.indexOf("prep:RETRY")).count { it == "session" } >= 2)
    }

    @Test
    fun prepFailureDoesNotKillConnector() {
        val c = connector({ throw IllegalStateException("бум") }) { throw IOException("refused") }
        c.start()
        waitFor { events.count { it == "session" } >= 2 }
        c.stop()
        assertTrue(events.count { it == "session" } >= 2)
    }
}
