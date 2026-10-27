package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 转码器供给队列:顺序、溢出后等待 IDR、被 IDR 驱逐、命令从不被拒绝。 */
class FeedQueueTest {
    private fun p(pts: Long) = FeedItem.Frame(byteArrayOf(1), pts, false, 0)
    private fun idr(pts: Long) = FeedItem.Frame(byteArrayOf(5), pts, true, 0)

    private fun FeedQueue.drain(): List<FeedItem> {
        val res = ArrayList<FeedItem>()
        while (true) res.add(take(0) ?: return res)
    }

    @Test
    fun fifoWithCommandsInterleaved() {
        val q = FeedQueue(8)
        val cfg = FeedItem.Config(byteArrayOf(7))
        assertEquals(Offer.ACCEPTED, q.offerFrame(idr(1)))
        q.post(cfg)
        assertEquals(Offer.ACCEPTED, q.offerFrame(p(2)))
        q.post(FeedItem.Reset)
        assertEquals(Offer.ACCEPTED, q.offerFrame(p(3)))
        val got = q.drain()
        assertEquals(5, got.size)
        assertEquals(1L, (got[0] as FeedItem.Frame).ptsUs)
        assertSame(cfg, got[1])
        assertEquals(2L, (got[2] as FeedItem.Frame).ptsUs)
        assertSame(FeedItem.Reset, got[3])
        assertEquals(3L, (got[4] as FeedItem.Frame).ptsUs)
        assertEquals(0, q.pendingFrames)
    }

    @Test
    fun overflowDropsThenGatesUntilIdr() {
        val q = FeedQueue(2)
        assertEquals(Offer.ACCEPTED, q.offerFrame(idr(1)))
        assertEquals(Offer.ACCEPTED, q.offerFrame(p(2)))
        assertEquals(Offer.DROPPED_FULL, q.offerFrame(p(3)))
        assertEquals(Offer.DROPPED_GATED, q.offerFrame(p(4)))
        // 空间已释放,但仍不能发 P 帧:丢帧后需要 IDR
        assertEquals(1L, (q.take(0) as FeedItem.Frame).ptsUs)
        assertEquals(Offer.DROPPED_GATED, q.offerFrame(p(5)))
        assertEquals(Offer.ACCEPTED, q.offerFrame(idr(6)))
        assertEquals(Offer.ACCEPTED, q.offerFrame(p(7)))
        assertEquals(listOf(6L, 7L), q.drain().map { (it as FeedItem.Frame).ptsUs })
    }

    @Test
    fun idrPassesWhenQueueFullAndEvictsPendingFrames() {
        val q = FeedQueue(2)
        q.offerFrame(idr(1))
        q.offerFrame(p(2))
        assertEquals(Offer.DROPPED_FULL, q.offerFrame(p(3)))
        assertEquals(Offer.ACCEPTED, q.offerFrame(idr(4)))
        assertEquals(1, q.pendingFrames)
        assertEquals(listOf(4L), q.drain().map { (it as FeedItem.Frame).ptsUs })
    }

    @Test
    fun idrEvictsFramesButKeepsCommandsAndOrder() {
        val q = FeedQueue(8)
        val cfg1 = FeedItem.Config(byteArrayOf(1))
        val cfg2 = FeedItem.Config(byteArrayOf(2))
        q.offerFrame(idr(1))
        q.post(cfg1)
        q.offerFrame(p(2))
        q.post(FeedItem.Reset)
        q.offerFrame(p(3))
        q.post(cfg2)
        q.offerFrame(idr(4))
        val got = q.drain()
        assertEquals(4, got.size)
        assertSame(cfg1, got[0])
        assertSame(FeedItem.Reset, got[1])
        assertSame(cfg2, got[2])
        assertEquals(4L, (got[3] as FeedItem.Frame).ptsUs)
    }

    @Test
    fun postNeverRefusesEvenWhenFull() {
        val q = FeedQueue(1)
        q.offerFrame(idr(1))
        assertEquals(Offer.DROPPED_FULL, q.offerFrame(p(2)))
        repeat(10) { q.post(FeedItem.Config(byteArrayOf(it.toByte()))) }
        q.post(FeedItem.Reset)
        q.post(FeedItem.Stop)
        val got = q.drain()
        assertEquals(13, got.size)
        assertSame(FeedItem.Stop, got.last())
    }

    @Test
    fun takeTimesOutOnEmpty() {
        val q = FeedQueue()
        val t0 = System.nanoTime()
        assertNull(q.take(50))
        assertTrue(System.nanoTime() - t0 >= 40_000_000L)
        assertNull(q.take(0))
    }

    @Test
    fun takeWakesOnOffer() {
        val q = FeedQueue()
        var got: FeedItem? = null
        val t = Thread { got = q.take(5_000) }
        t.start()
        Thread.sleep(50)
        q.offerFrame(idr(9))
        t.join(2_000)
        assertEquals(9L, (got as FeedItem.Frame).ptsUs)
    }

    @Test
    fun maxPendingSeenCountsAndResets() {
        val q = FeedQueue(8)
        assertEquals(0, q.maxPendingSeen)
        q.offerFrame(idr(1))
        q.offerFrame(p(2))
        q.offerFrame(p(3))
        q.post(FeedItem.Reset)
        assertEquals(3, q.maxPendingSeen)
        q.take(0)
        q.take(0)
        assertEquals(3, q.maxPendingSeen)
        assertEquals(3, q.resetMaxPending())
        // 新窗口从当前深度开始(队列中还剩一帧)
        assertEquals(1, q.maxPendingSeen)
        q.offerFrame(p(4))
        assertEquals(2, q.resetMaxPending())
        assertEquals(2, q.resetMaxPending())
    }

    @Test
    fun clearEmptiesAndOpensGate() {
        val q = FeedQueue(1)
        q.offerFrame(idr(1))
        q.offerFrame(p(2))
        assertEquals(Offer.DROPPED_GATED, q.offerFrame(p(3)))
        q.clear()
        assertEquals(0, q.pendingFrames)
        assertNull(q.take(0))
        assertEquals(Offer.ACCEPTED, q.offerFrame(p(4)))
    }

    @Test
    fun twoProducersOneConsumerLoseOnlyAccountedDrops() {
        val q = FeedQueue(4)
        val perProducer = 3_000
        val accepted = AtomicInteger()
        val droppedFull = AtomicInteger()
        val droppedGated = AtomicInteger()
        val postedCmds = AtomicInteger()
        val consumedFrames = ArrayList<Long>()
        var consumedCmds = 0
        var sawStop = false
        val consumer = Thread {
            while (true) {
                when (val x = q.take(2_000) ?: break) {
                    is FeedItem.Frame -> consumedFrames.add(x.ptsUs)
                    is FeedItem.Config -> consumedCmds++
                    FeedItem.Reset -> consumedCmds++
                    FeedItem.Stop -> {
                        sawStop = true
                        break
                    }
                }
                if (consumedFrames.size % 7 == 0) Thread.yield()
            }
        }
        val producers = (1..2).map { id ->
            Thread {
                for (i in 0 until perProducer) {
                    val pts = id * 1_000_000L + i
                    val f = if (i % 50 == 0) idr(pts) else p(pts)
                    when (q.offerFrame(f)) {
                        Offer.ACCEPTED -> accepted.incrementAndGet()
                        Offer.DROPPED_FULL -> droppedFull.incrementAndGet()
                        Offer.DROPPED_GATED -> droppedGated.incrementAndGet()
                    }
                    if (i % 100 == 0) {
                        q.post(if (i % 200 == 0) FeedItem.Reset else FeedItem.Config(byteArrayOf(1)))
                        postedCmds.incrementAndGet()
                    }
                }
            }
        }
        consumer.start()
        producers.forEach { it.start() }
        producers.forEach { it.join() }
        q.post(FeedItem.Stop)
        consumer.join(10_000)
        assertTrue(sawStop)
        assertEquals(2 * perProducer, accepted.get() + droppedFull.get() + droppedGated.get())
        assertEquals("команды не теряются", postedCmds.get(), consumedCmds)
        // 已接受的帧要么送达,要么被更晚的 IDR 驱逐
        assertTrue(consumedFrames.size <= accepted.get())
        assertTrue(consumedFrames.isNotEmpty())
        // 每个生产者内部顺序保持不变
        for (id in 1..2) {
            val mine = consumedFrames.filter { it / 1_000_000L == id.toLong() }
            assertEquals(mine.sorted(), mine)
            assertEquals(mine.distinct(), mine)
        }
        assertEquals(0, q.pendingFrames)
    }
}
