package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.Msgs
import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.Wire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** AA 麦克风：帧格式、切片与窗口、车机 / 手机 / fallback 路由。 */
class AaMicTest {
    // ---------------------------------------------------------------- 格式
    @Test
    fun dataPayloadIsU64BigEndianMicrosThenPcm() {
        val p = AaMicFormat.dataPayload(0x0102030405060708L, byteArrayOf(9, 8, 7))
        assertArrayEquals(Golden.hex("0102030405060708 090807"), p)
        val out = M.micData(1_000_000L, ByteArray(4096))
        assertEquals(M.CH_MIC, out.chan)
        assertEquals(M.MEDIA_DATA, out.type)
        assertEquals(8 + 4096, out.payload.size)
        // AAP 帧：MIC 通道上 type 0 -> 无 control 标志的加密 bulk
        assertEquals(AapFraming.FLAGS_ENC_BULK, AapFraming.messageFlags(M.CH_MIC, out.type))
    }

    @Test
    fun microphoneResponseBytes() {
        assertArrayEquals(Golden.hex("08 00 10 01"), M.micResponse(true, 1).payload)
        assertArrayEquals(Golden.hex("08 f9ffffffffffffffff01 10 02"), M.micResponse(false, 2).payload)
        // 来自手机的 MicrophoneRequest：open=1, max_unacked=4
        val f = PbReader.parse(AapPb().bool(1, true).v(4, 4).toByteArray())!!
        assertEquals(1, PbReader.int(f, 1))
        assertEquals(4, PbReader.int(f, 4))
    }

    @Test
    fun chunkIsTwoThousandFortyEightFramesOf128Ms() {
        assertEquals(4096, AaMicFormat.CHUNK_BYTES)
        assertEquals(128_000L, AaMicFormat.usForBytes(AaMicFormat.CHUNK_BYTES))
        assertEquals(32_000, AaMicFormat.BYTES_PER_SEC)
    }

    // ---------------------------------------------------------------- 上行链路
    private class Emitted(val sid: Int, val ts: Long, val pcm: ByteArray)

    private fun uplink(out: MutableList<Emitted>, maxPending: Int = 4) = AaMicUplink({ sid, ts, pcm -> out.add(Emitted(sid, ts, pcm)) }, maxPending)

    private fun pcm(n: Int, seed: Int = 0) = ByteArray(n) { (seed + it).toByte() }

    @Test
    fun accumulatesPartialInputIntoWholeChunksWithFirstByteTimestamp() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(1, 8)
        u.activate()
        // 3 块各 1500 字节：第 3 块凑满一帧 (4096 = 1500+1500+1096)，剩余 404 字节等待
        assertEquals(AaMicUplink.Feed.OK, u.feed(pcm(1500, 0), 1_000_000))
        assertEquals(AaMicUplink.Feed.OK, u.feed(pcm(1500, 1), 1_046_875))
        assertTrue(out.isEmpty())
        assertEquals(AaMicUplink.Feed.OK, u.feed(pcm(1500, 2), 1_093_750))
        assertEquals(1, out.size)
        assertEquals(4096, out[0].pcm.size)
        assertEquals("timestamp = момент первого байта кадра", 1_000_000L, out[0].ts)
        // 尾部在关闭时被丢弃，不补零
        val s = u.close()
        assertNotNull(s)
        assertEquals(1, s!!.chunksSent)
        assertEquals(404L, s.discardedBytes)
    }

    @Test
    fun timestampOfChunkStartingInsideAReadIsShiftedByItsOffset() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(3, 8)
        u.activate()
        u.feed(pcm(3000), 0)                                  // 帧 1 未凑满
        u.feed(pcm(6000), 100_000)                             // 1096 字节补满帧 1；帧 2 从该块的第 1096 字节开始
        assertEquals(2, out.size)
        assertEquals(0L, out[0].ts)
        assertEquals(100_000L + AaMicFormat.usForBytes(1096), out[1].ts)
        assertTrue(out.all { it.sid == 3 })
    }

    @Test
    fun windowLimitsInFlightAndAckReleasesNext() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(1, 2)
        u.activate()
        u.feed(pcm(4096 * 4), 0)
        assertEquals("окно 2: уходят два кадра", 2, out.size)
        assertFalse("ack чужой сессии", u.ack(9, 1))
        assertFalse("ack больше отправленного", u.ack(1, 3))
        assertFalse("ack 0", u.ack(1, 0))
        assertTrue(u.ack(1, 1))
        assertEquals(3, out.size)
        assertTrue(u.ack(1, 2))
        assertEquals(4, out.size)
        assertEquals(listOf(0L, 128_000L, 256_000L, 384_000L), out.map { it.ts })
    }

    @Test
    fun dataWaitsForActivation() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(1, 4)
        u.feed(pcm(4096 * 2), 0)                              // MicrophoneResponse 尚未入队
        assertTrue(out.isEmpty())
        u.activate()
        assertEquals(2, out.size)
    }

    @Test
    fun overflowWhenPhoneStopsAcknowledging() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(1, 1)
        u.activate()
        // 窗口 1 + 队列 4 = 5 帧，第 6 帧溢出
        assertEquals(AaMicUplink.Feed.OK, u.feed(pcm(4096 * 5), 0))
        assertEquals(1, out.size)
        assertEquals(AaMicUplink.Feed.OVERFLOW, u.feed(pcm(4096), 0))
        val s = u.close()
        assertNotNull(s)
        assertEquals(1, s!!.chunksSent)
    }

    @Test
    fun feedAfterCloseIsRejectedAndReopenStartsClean() {
        val out = ArrayList<Emitted>()
        val u = uplink(out)
        u.open(1, 4)
        u.activate()
        u.feed(pcm(4096), 0)
        assertNotNull(u.close())
        assertNull("повторный close", u.close())
        assertEquals(AaMicUplink.Feed.CLOSED, u.feed(pcm(4096), 0))
        assertFalse(u.ack(1, 1))
        u.open(2, 4)
        u.activate()
        u.feed(pcm(4096), 500_000)
        assertEquals(2, out.size)
        assertEquals(2, out[1].sid)
        assertEquals(500_000L, out[1].ts)
    }

    // ---------------------------------------------------------------- 路由器
    private class Timer(val at: Long, val task: Runnable) : MicTimerHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakePhoneMic(var startOk: Boolean = true) : PhoneMic {
        var sink: ((ByteArray) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun start(sink: (ByteArray) -> Unit): Boolean {
            starts++
            if (!startOk) return false
            this.sink = sink
            return true
        }

        override fun stop() {
            stops++
            sink = null
        }
    }

    private class Rig(var mode: MicMode, linkUp: Boolean = true, phone: FakePhoneMic? = FakePhoneMic(), var recordOk: Boolean = true, skip: Int = 0) {
        var linkUp = linkUp
        val phoneMic = phone
        val logs = CopyOnWriteArrayList<String>()
        val record = ArrayList<Boolean>()
        val toAa = ArrayList<Pair<Int, Long>>()
        val timers = ArrayList<Timer>()
        var nowMs = 10_000L
        var nowUs = 50_000_000L
        val router = AaMicRouter(
            log = { l, _ -> logs.add(l) },
            mode = { mode },
            huLinkUp = { this.linkUp },
            huRecord = { s -> record.add(s); recordOk },
            phone = { phoneMic },
            toAa = { p, ts -> toAa.add(p.size to ts) },
            schedule = { d, t -> Timer(nowMs + d, t).also { timers.add(it) } },
            nowMs = { nowMs },
            nowUs = { nowUs },
            huSkipPackets = skip,
        )

        fun fire() {
            val t = timers.last { !it.cancelled }
            t.task.run()
        }
    }

    @Test
    fun carModeAsksHuAndForwardsItsPcm() {
        val r = Rig(MicMode.CAR)
        assertTrue(r.router.open())
        assertEquals(listOf(true), r.record)
        assertEquals(AaMicRouter.State.HU_WAIT, r.router.current)
        r.nowMs += 300
        r.nowUs = 60_000_000L
        r.router.onHuData(ByteArray(640))
        assertEquals(AaMicRouter.State.HU, r.router.current)
        assertTrue("таймер fallback снят первым пакетом", r.timers.single().cancelled)
        assertEquals("timestamp = момент прихода минус длительность порции", listOf(640 to 60_000_000L - 20_000L), r.toAa)
        r.router.onHuData(ByteArray(640))
        assertEquals(1280L, r.router.bytesFromHu)
        r.router.close("AA закрыла")
        assertEquals(listOf(true, false), r.record)
        assertEquals(AaMicRouter.State.IDLE, r.router.current)
        assertEquals("телефонный микрофон в режиме «машина» не трогаем", 0, r.phoneMic!!.starts)
        assertTrue(r.logs.any { it.contains("MIC open от AA: режим=машина") })
        assertTrue(r.logs.any { it.contains("MIC_RECORD_RECOG_START") })
        assertTrue(r.logs.any { it.contains("первые данные") })
        assertTrue(r.logs.any { it.contains("MIC close") && it.contains("с ГУ 1280 Б") })
    }

    @Test
    fun carModeStaysSilentWithoutFallback() {
        val r = Rig(MicMode.CAR)
        r.router.open()
        r.nowMs += 2000
        r.fire()
        assertEquals(AaMicRouter.State.HU_WAIT, r.router.current)
        assertEquals(0, r.phoneMic!!.starts)
        assertTrue(r.logs.any { it.contains("fallback выключен") })
    }

    @Test
    fun carModeRefusedWithoutCarlifeOrWhenRequestFails() {
        val a = Rig(MicMode.CAR, linkUp = false)
        assertFalse(a.router.open())
        assertTrue(a.record.isEmpty())
        val b = Rig(MicMode.CAR, recordOk = false)
        assertFalse(b.router.open())
        assertEquals(AaMicRouter.State.IDLE, b.router.current)
    }

    @Test
    fun autoFallsBackToPhoneAfterTwoSecondsOfSilence() {
        val r = Rig(MicMode.AUTO)
        assertTrue(r.router.open())
        assertEquals(listOf(true), r.record)
        assertEquals("таймер на 2000 мс", 12_000L, r.timers.single().at)
        r.nowMs += 2000
        r.fire()
        assertEquals(AaMicRouter.State.PHONE, r.router.current)
        assertEquals("запись на ГУ остановлена", listOf(true, false), r.record)
        assertEquals(1, r.phoneMic!!.starts)
        assertTrue(r.logs.any { it.contains("fallback на микрофон телефона") })
        // 之后手机的声音进入 AA，车机数据被忽略
        r.nowUs = 70_000_000L
        r.phoneMic.sink!!.invoke(ByteArray(4096))
        assertEquals(listOf(4096 to 70_000_000L - 128_000L), r.toAa)
        r.router.onHuData(ByteArray(640))
        assertEquals(1, r.toAa.size)
        r.router.close("AA закрыла")
        assertEquals(1, r.phoneMic.stops)
        assertEquals("после fallback ГУ повторно не останавливаем", listOf(true, false), r.record)
    }

    @Test
    fun autoStaysOnHuIfDataArrivesInTime() {
        val r = Rig(MicMode.AUTO)
        r.router.open()
        r.nowMs += 800
        r.router.onHuData(ByteArray(1024))
        r.nowMs += 5000
        assertTrue("таймер отменён: сработать ему нечем", r.timers.single().cancelled)
        assertEquals(AaMicRouter.State.HU, r.router.current)
        assertEquals(0, r.phoneMic!!.starts)
    }

    @Test
    fun autoGoesStraightToPhoneWithoutCarlife() {
        val r = Rig(MicMode.AUTO, linkUp = false)
        assertTrue(r.router.open())
        assertTrue("ГУ не просили", r.record.isEmpty())
        assertEquals(AaMicRouter.State.PHONE, r.router.current)
        assertEquals(1, r.phoneMic!!.starts)
    }

    @Test
    fun autoFallsBackWhenRecordRequestCannotBeSent() {
        val r = Rig(MicMode.AUTO, recordOk = false)
        assertTrue(r.router.open())
        assertEquals(AaMicRouter.State.PHONE, r.router.current)
    }

    @Test
    fun phoneModeNeverTouchesCarlife() {
        val r = Rig(MicMode.PHONE)
        assertTrue(r.router.open())
        assertTrue(r.record.isEmpty())
        assertEquals(AaMicRouter.State.PHONE, r.router.current)
        r.phoneMic!!.sink!!.invoke(ByteArray(2048))
        assertEquals(1, r.toAa.size)
        r.router.close("x")
        assertTrue(r.record.isEmpty())
        assertEquals(1, r.phoneMic.stops)
    }

    @Test
    fun phoneModeRefusedWhenAudioRecordDoesNotStart() {
        assertFalse(Rig(MicMode.PHONE, phone = FakePhoneMic(startOk = false)).router.open())
        assertFalse(Rig(MicMode.PHONE, phone = null).router.open())
        // 自动模式：车机无声、手机未启动 - 保持无声音，但不崩溃
        val r = Rig(MicMode.AUTO, phone = FakePhoneMic(startOk = false))
        assertTrue(r.router.open())
        r.nowMs += 2000
        r.fire()
        assertEquals(AaMicRouter.State.IDLE, r.router.current)
    }

    @Test
    fun carlifeLostDuringHuRecordingSwitchesAutoToPhone() {
        val r = Rig(MicMode.AUTO)
        r.router.open()
        r.router.onHuData(ByteArray(640))
        r.linkUp = false
        r.router.onCarlifeLost()
        assertEquals(AaMicRouter.State.PHONE, r.router.current)
        assertEquals("CarLife мёртв: MIC_RECORD_END слать некуда", listOf(true), r.record)
        // 「汽车」模式：继续等待车机
        val c = Rig(MicMode.CAR)
        c.router.open()
        c.router.onCarlifeLost()
        assertEquals(AaMicRouter.State.HU_WAIT, c.router.current)
        assertEquals(0, c.phoneMic!!.starts)
    }

    @Test
    fun repeatedOpenClosesPreviousFirstAndIgnoresStaleTimer() {
        val r = Rig(MicMode.AUTO)
        r.router.open()
        val old = r.timers.single()
        r.router.open()
        assertEquals(listOf(true, false, true), r.record)
        assertTrue(old.cancelled)
        old.task.run()                                         // 上次 open 的迟到定时器不应触发切换
        assertEquals(AaMicRouter.State.HU_WAIT, r.router.current)
        assertEquals(0, r.phoneMic!!.starts)
    }

    @Test
    fun skipPacketsOptionDropsFirstHuPackets() {
        val r = Rig(MicMode.CAR, skip = 2)
        r.router.open()
        repeat(3) { r.router.onHuData(ByteArray(100)) }
        assertEquals(1, r.toAa.size)
        assertEquals(100L, r.router.bytesFromHu)
    }

    @Test
    fun modeParsing() {
        assertEquals(MicMode.CAR, MicMode.parse("car"))
        assertEquals(MicMode.PHONE, MicMode.parse("phone"))
        assertEquals(MicMode.AUTO, MicMode.parse("auto"))
        assertEquals(MicMode.AUTO, MicMode.parse(null))
        assertEquals(MicMode.AUTO, MicMode.parse("мусор"))
    }

    // ---------------------------------------------------------------- CarLife 侧
    @Test
    fun carlifeVrMicMessageParsesAsBaiduSendsIt() {
        // HU -> MD：VR 通道，内部头部 12 字节 [len][time=0][type 0x58001] + PCM
        val pcm = ByteArray(640) { it.toByte() }
        val inner = Wire.dataInner(Msg.VR_MIC_DATA, 0, pcm)
        val m = Wire.parseData(inner)!!
        assertEquals(0x00058001, m.type)
        assertEquals(360449, Msg.VR_MIC_DATA)                   // Baidu 8.6.7 中的 CommonParams.MSG_VR_DATA
        assertEquals(640, m.declaredLen)
        assertFalse(m.truncated)
        assertArrayEquals(pcm, m.data)
        assertEquals("VR_MIC_DATA", Msg.name(Msg.VR_MIC_DATA))
    }

    @Test
    fun carlifeMicRecordCommandsMatchBaidu() {
        assertEquals(65570, Msg.MIC_RECORD_WAKEUP_START)        // CommonParams.MSG_CMD_MIC_RECORD_WAKEUP_START
        assertEquals(65571, Msg.MIC_RECORD_END)
        assertEquals(65572, Msg.MIC_RECORD_RECOG_START)
        // 无实体的 CMD：[len=0][resv=0][type]
        assertArrayEquals(Golden.hex("0000 0000 00010024"), Wire.cmdInner(Msg.MIC_RECORD_RECOG_START, null))
        assertArrayEquals(Golden.hex("0000 0000 00010023"), Wire.cmdInner(Msg.MIC_RECORD_END, null))
        // MODULE_STATUS VR=1 / VR=0：cnt=1 且 {moduleID=4, statusID}
        assertArrayEquals(Golden.hex("0801 1204 0804 1001"), Msgs.moduleStatusSingle(4, 1))
        assertArrayEquals(Golden.hex("0801 1204 0804 1000"), Msgs.moduleStatusSingle(4, 0))
    }

    // ---------------------------------------------------------------- 桥接核心：贯通路径
    private class MicLink : CarlifeLink {
        val record = ArrayList<Boolean>()
        override fun sendVideo(payload: ByteArray) = true
        override fun showPlaceholder(text: String) {}
        override fun hidePlaceholder() {}
        override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = true
        override fun sendModuleStatus(module: Int, status: Int) = true
        override fun requestMicRecord(start: Boolean): Boolean {
            record.add(start)
            return true
        }
    }

    private class MicPeer : AaPeer {
        val pcm = ArrayList<Pair<Int, Long>>()
        override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {}
        override fun requestKeyFrame() {}
        override fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {
            this.pcm.add(pcm.size to capturedAtUs)
        }
    }

    @Test
    fun coreRoutesAaMicOpenToCarlifeAndHuPcmBackToAa() {
        val timers = ArrayList<Timer>()
        var us = 1_000_000L
        val core = AaBridgeCore({ _, _ -> }, { 0L }, { us }, { d, t -> Timer(d, t).also { timers.add(it) } })
        val link = MicLink()
        val peer = MicPeer()
        core.attach(link)
        core.peer = peer
        core.micModeProvider = { MicMode.AUTO }
        core.onCarlifeStart()
        assertTrue(core.onMicOpen())
        assertEquals("AA open -> запрос записи у ГУ", listOf(true), link.record)
        us = 2_000_000L
        core.onCarlifeMicData(ByteArray(1280))
        assertEquals(listOf(1280 to 2_000_000L - 40_000L), peer.pcm)
        core.onMicClose("AA закрыла")
        assertEquals("AA close -> остановка записи на ГУ", listOf(true, false), link.record)
    }

    @Test
    fun coreWithoutCarlifeStartRefusesCarMode() {
        val core = AaBridgeCore({ _, _ -> })
        core.attach(MicLink())
        core.micModeProvider = { MicMode.CAR }
        assertFalse("CarLife ещё не START: просить у ГУ нечего", core.onMicOpen())
    }

    @Test
    fun coreClosesMicWhenCarlifeSessionDies() {
        val link = MicLink()
        val core = AaBridgeCore({ _, _ -> })
        core.attach(link)
        core.micModeProvider = { MicMode.CAR }
        core.onCarlifeStart()
        assertTrue(core.onMicOpen())
        core.onCarlifeClosed(link)
        assertFalse(core.hasCarlifeLink)
        // 路由器保持等待车机（「汽车」模式），打开的 AA 麦克风在关闭时不崩溃
        core.onMicClose("AA закрыла")
        assertEquals("после обрыва MIC_RECORD_END слать некуда", listOf(true), link.record)
    }

    private val M = AapMessages
}
