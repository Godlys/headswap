package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.yegorkapomidorka.headswap.aa.AapSessionTest.Phone as FakePhone

class FakeLifecycle(private val stopDelayMs: Long = 0) : AaLifecycle {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    @Volatile var stopDoneAt = 0L
    override fun start() {
        starts.incrementAndGet()
    }

    override fun stop(onDone: () -> Unit) {
        stops.incrementAndGet()
        Thread {
            Thread.sleep(stopDelayMs)
            stopDoneAt = System.nanoTime()
            onDone()
        }.start()
    }
}

class BridgeHostTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val logs = CopyOnWriteArrayList<String>()
    private fun core() = AaBridgeCore({ l, _ -> logs.add(l) })

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }

    @Test
    fun usbBackInsideGraceKeepsAaAndDoesNotRestartIt() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 400)
        assertTrue(host.onUsbSessionStarted())
        host.onUsbSessionClosed(immediate = false)
        assertTrue(host.isInGrace)
        Thread.sleep(150)
        assertTrue(host.onUsbSessionStarted())            // 在窗口内回来了
        assertFalse(host.isInGrace)
        Thread.sleep(600)                                  // 定时器已取消:不会触发
        assertEquals(1, lc.starts.get())
        assertEquals(0, lc.stops.get())
        assertFalse(host.isDead)
    }

    @Test
    fun usbNotBackClosesAaAfterGrace() {
        val lc = FakeLifecycle()
        val down = CountDownLatch(1)
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 300, onTornDown = { down.countDown() })
        host.onUsbSessionStarted()
        host.onUsbSessionClosed(immediate = false)
        Thread.sleep(100)
        assertEquals("до истечения grace AA не закрывается", 0, lc.stops.get())
        assertTrue(down.await(3, TimeUnit.SECONDS))
        assertEquals(1, lc.stops.get())
        assertTrue(host.isDead && host.isTornDown)
        assertFalse("мёртвый хост не принимает новую сессию", host.onUsbSessionStarted())
    }

    @Test
    fun stopButtonClosesAaImmediately() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 10_000)
        host.onUsbSessionStarted()
        host.onUsbSessionClosed(immediate = true)
        assertEquals(1, lc.stops.get())
    }

    @Test
    fun manyUsbFlapsNeverTouchAa() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 300)
        repeat(62) {
            assertTrue(host.onUsbSessionStarted())
            host.onUsbSessionClosed(immediate = false)
            Thread.sleep(15)
        }
        assertTrue(host.onUsbSessionStarted())
        assertEquals(1, lc.starts.get())
        assertEquals(0, lc.stops.get())
        host.onUsbSessionClosed(immediate = false)
        waitFor { lc.stops.get() == 1 }
        assertEquals(1, lc.stops.get())
    }

    @Test
    fun shutdownBeforeAnyUsbSessionNeverStartsAa() {
        val lc = FakeLifecycle()
        val down = CountDownLatch(1)
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, onTornDown = { down.countDown() })
        host.shutdown("тест")
        assertTrue(down.await(1, TimeUnit.SECONDS))
        assertEquals(0, lc.starts.get())
        assertEquals(0, lc.stops.get())
    }

    @Test
    fun newHostDoesNotConnectUntilPreviousAaIsFullyClosed() {
        val lc1 = FakeLifecycle(stopDelayMs = 500)
        val host1 = BridgeHost(core(), lc1, scheduler, { l, _ -> logs.add(l) })
        host1.onUsbSessionStarted()
        host1.shutdown("тест")                              // 关闭需要 500 毫秒
        val lc2 = FakeLifecycle()
        val host2 = BridgeHost(core(), lc2, scheduler, { l, _ -> logs.add(l) }, previous = host1)
        assertTrue(host2.onUsbSessionStarted())
        Thread.sleep(150)
        assertEquals("сервер 5277 одноразовый: пока предыдущая AA не закрыта, нового подключения нет", 0, lc2.starts.get())
        waitFor { lc2.starts.get() == 1 }
        assertEquals(1, lc2.starts.get())
        assertTrue(host1.isTornDown)
    }
}

/** 存活的 AA 会话能扛过 USB 断开:同一会话、keyframe 循环、音源切回 AA;grace 到期后 - ByeBye。 */
class BridgeGraceIntegrationTest {
    @Test
    fun usbDropAndReturnInsideGraceReusesSameAaSessionAndRecoversByKeyframe() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link1 = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) })
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val created = AtomicInteger()
        val sessionRef = AtomicReference<AapSession>()
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                created.incrementAndGet()
                AapSession(creds, sink, o, lg, onEnd = end).also { sessionRef.compareAndSet(null, it) }
            },
        )
        val down = CountDownLatch(1)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val host = BridgeHost(core, connector, scheduler, { l, _ -> logs.add(l) }, graceMs = 1500, onTornDown = { down.countDown() })

        // USB 会话 1:CarLife START,AA 连接并输出 config + IDR + P
        core.attach(link1)
        host.onUsbSessionStarted()
        core.onCarlifeStart()
        val sock = server.accept()
        val phone = FakePhone(sock, "jvc", false, "bridge_grace")
        phone.start()
        waitUntil { link1.sent.size >= 2 }
        assertEquals("видео до обрыва не дошло\n" + logs.joinToString("\n"), 2, link1.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS + FakePhone.IDR, link1.sent[0])
        assertTrue(phone.ready.await(10, TimeUnit.SECONDS))
        val aaSession = sessionRef.get()

        // USB 断开(EIO):CarLife 已关闭,AA 仍存活
        core.onCarlifeClosed(link1)
        host.onUsbSessionClosed(immediate = false)
        Thread.sleep(300)
        assertEquals("AA-сессия не пересоздавалась", 1, created.get())
        assertTrue("AA-сессия жива", aaSession.established)
        assertEquals(null, phone.error)

        // USB 在 grace 内回来了:新的 CarLife 会话、"等待帧"占位、keyframe、同一 AA 的 IDR
        val link2 = FakeLink()
        core.attach(link2)
        assertTrue(host.onUsbSessionStarted())
        assertEquals(listOf(AaBridgeCore.TEXT_WAIT_FRAME), link2.texts.toList())
        core.onCarlifeStart()
        waitUntil { link2.sent.isNotEmpty() }
        assertEquals("после keyframe-цикла IDR не дошёл\n" + logs.joinToString("\n"), 1, link2.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS2 + FakePhone.IDR2, link2.sent[0])      // 全新的 SPS+PPS+IDR
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(1, link2.hides)
        assertEquals("всё ещё та же AA-сессия", 1, created.get())
        assertTrue(aaSession === sessionRef.get() && aaSession.established)

        // USB 未在 grace 内返回:AA 正常关闭(ByeBye),手机能看到
        core.onCarlifeClosed(link2)
        host.onUsbSessionClosed(immediate = false)
        assertTrue("AA не закрылась после grace\n" + logs.joinToString("\n"), down.await(15, TimeUnit.SECONDS))
        phone.join(10_000)
        assertNull("телефон: ${phone.error}", phone.error)
        assertTrue("сокет закрыт штатно", phone.sawEof && !phone.reset)
        assertTrue(aaSession.exitOk)
        assertEquals(1, created.get())
        server.close()
    }

    private fun waitUntil(ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }
}
