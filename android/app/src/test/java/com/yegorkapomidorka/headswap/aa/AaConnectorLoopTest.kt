package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** 连接器循环：车机服务器不可达时重试，以及裸 EOF 后不断增长的暂停时间（h224）。 */
class AaConnectorLoopTest {
    private val events = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()
    private val creds = AapCerts.load(TestAssets.opener, "jvc")

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }

    private fun connector(failures: ServerFailures = ServerFailures(), socket: () -> java.net.Socket): AaConnector {
        val core = AaBridgeCore({ _, _ -> })
        return AaConnector(
            core, creds, { m, _ -> logs.add(m) },
            options = { AapOptions(preVideoTimeoutMs = 1000) },
            retryMs = 50,
            failures = failures,
            sessionFactory = { sink, o, lg, end ->
                events.add("session")
                AapSession(creds, sink, o, lg, socketFactory = socket, onEnd = end)
            },
        )
    }

    @Test
    fun retriesWhileServerStaysUnavailable() {
        val c = connector { throw IOException("refused") }
        c.start()
        waitFor { events.count { it == "session" } >= 3 }
        c.stop()
        assertTrue(events.toString(), events.count { it == "session" } >= 3)
        assertTrue(logs.toString(), logs.count { it.contains("недоступен (connection refused)") } == 1)
    }

    @Test
    fun connectionRefusedFeedsServerFailuresButOtherConnectErrorsDoNot() {
        val refused = ServerFailures()
        val c = connector(refused) { throw java.net.ConnectException("Connection refused") }
        c.start()
        waitFor { events.count { it == "session" } >= 3 && refused.refusedForMs > 0 }
        assertTrue("отказ засчитан", refused.refusedForMs > 0)
        c.stop()
        waitFor { refused.refusedForMs == 0L }
        assertEquals("после остановки коннектора серия обнуляется", 0L, refused.refusedForMs)

        events.clear()
        val other = ServerFailures()
        val c2 = connector(other) { throw IOException("EPERM") }
        c2.start()
        waitFor { events.count { it == "session" } >= 3 }
        assertEquals("не ECONNREFUSED отказом не считаем", 0L, other.refusedForMs)
        c2.stop()
    }

    @Test
    fun bareEofKeepsReconnectingWithGrowingPauses() {
        // “服务器”接受连接后立即关闭：connect+EOF 但没有会话（h224）。计数器增长，持续重连。
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        server.use {
            Thread {
                try {
                    while (true) server.accept().close()
                } catch (_: IOException) {
                }
            }.start()
            val c = connector {
                java.net.Socket().also { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2000) }
            }
            c.start()
            waitFor { c.bareEofs >= 3 }
            c.stop()
            assertTrue(events.toString(), c.bareEofs >= 3)
            assertTrue(events.toString(), events.count { it == "session" } >= 3)
            assertTrue(logs.toString(), logs.none { it.contains("автозапуск") })
        }
    }
}
