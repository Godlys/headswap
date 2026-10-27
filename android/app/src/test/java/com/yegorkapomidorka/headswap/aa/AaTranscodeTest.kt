package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FakeTranscoder : VideoTranscoder {
    val configs = CopyOnWriteArrayList<ByteArray>()
    val frames = CopyOnWriteArrayList<ByteArray>()
    val resets = AtomicInteger()
    val syncs = AtomicInteger()
    val stops = AtomicInteger()

    /** 按到达顺序记录的桥接调用：config、frame:idr、frame:p、reset、sync、intro、stop。 */
    val journal = CopyOnWriteArrayList<String>()
    override fun feedConfig(annexB: ByteArray) {
        journal.add("config")
        configs.add(annexB)
    }

    override fun feedFrame(annexB: ByteArray, ptsUs: Long) {
        journal.add(if (5 in AnnexB.nalTypes(annexB)) "frame:idr" else "frame:p")
        frames.add(annexB)
    }

    override fun resetDecoder() {
        journal.add("reset")
        resets.incrementAndGet()
    }

    override fun requestSyncFrame() {
        journal.add("sync")
        syncs.incrementAndGet()
    }

    override fun startIntro(banner: android.graphics.Bitmap) {
        journal.add("intro")
    }

    override fun statsLine() = "fake"
    override fun stop() {
        journal.add("stop")
        stops.incrementAndGet()
    }
}

class TranscodeLink : CarlifeLink {
    val sent = CopyOnWriteArrayList<ByteArray>()
    val texts = CopyOnWriteArrayList<String>()
    @Volatile var hides = 0
    @Volatile var accept = true
    var transcoder: FakeTranscoder? = FakeTranscoder()
    var created = 0
    var air = false
    override val isAir: Boolean get() = air
    override fun sendVideo(payload: ByteArray): Boolean {
        if (!accept) return false
        sent.add(payload)
        return true
    }

    override fun showPlaceholder(text: String) {
        texts.add(text)
    }

    override fun hidePlaceholder() {
        hides++
    }

    @Volatile var takeovers = 0
    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        val ok = sendVideo(payload)
        if (ok) takeovers++
        return ok
    }

    override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = true
    override fun sendModuleStatus(module: Int, status: Int) = true
    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? {
        created++
        return transcoder
    }
}

class AaTranscodeCoreTest {
    private val aaSps = Golden.hex("00000001 67 42c01f da0140")
    private val aaPps = Golden.hex("00000001 68 ce3c80")
    private val aaCfg = aaSps + aaPps
    private val aaIdr = Golden.hex("00000001 65") + ByteArray(100) { (it + 1).toByte() }
    private val aaP = Golden.hex("00000001 41") + ByteArray(30) { 0x22 }
    private val encCfg = Golden.hex("00000001 67 42001f ab 00000001 68 ce06e2")
    private val encIdr = Golden.hex("00000001 65") + ByteArray(200) { (it * 3).toByte() }
    private val encP = Golden.hex("00000001 41") + ByteArray(40) { 0x33 }
    private var now = 0L
    private val link = TranscodeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also {
        it.transcodeEnabled = true
        it.attach(link)
    }
    private val tr get() = link.transcoder!!

    private fun waitStop(t: FakeTranscoder) {
        val t0 = System.currentTimeMillis()
        while (t.stops.get() == 0 && System.currentTimeMillis() - t0 < 2000) Thread.sleep(10)
    }

    @Test
    fun startCreatesTranscoderRequestsSyncFeedsKnownConfig() {
        core.onCodecConfig(aaCfg)                              // config 先于 START 到达
        core.onCarlifeStart()
        assertEquals(1, link.created)
        assertTrue(core.isTranscoding)
        assertEquals(1, tr.syncs.get())
        assertArrayEquals(aaCfg, tr.configs.last())
        // 重复 START：仍用同一个转码器
        core.onCarlifeStart()
        assertEquals(1, link.created)
    }

    /** 先把 AA 视频送入 CarLife，然后 PAUSE：编码器输出被丢弃（如同暂停时 sendVideo=false）。 */
    private fun playThenPause() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onEncoderConfig(encCfg)
        core.onFrame(aaIdr, 1)
        core.onEncoderFrame(encIdr, true)
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        core.onCarlifePause()
        link.accept = false
        core.onFrame(aaP, 2)
        core.onEncoderFrame(encP, false)
        link.accept = true
    }

    @Test
    fun airStartAfterPauseKeepsDecoderAndSendsEncoderIdrAtOnce() {
        link.air = true
        core.onHuInfo("VW BT 1420", "VW BT 1420")
        playThenPause()
        val fed = tr.frames.size
        val syncs = tr.syncs.get()
        val sent = link.sent.size
        core.onCarlifeStart()
        assertTrue(syncs < tr.syncs.get())                     // 立即向编码器请求了 IDR
        core.onFrame(aaP, 3)                                   // AA 的 P 帧送入解码器，不等待 AA 的 IDR
        assertEquals(fed + 1, tr.frames.size)
        core.onEncoderFrame(encP, false)                       // 编码器的 P 帧在其 IDR 之前不发送
        assertEquals(sent, link.sent.size)
        core.onEncoderFrame(encIdr, true)
        assertArrayEquals(encCfg + encIdr, link.sent.last())
        assertTrue(logs.any { it.contains("AA не жду") })
    }

    @Test
    fun airStartAfterPauseOnOtherHuStillWaitsForAaIdr() {
        link.air = true                                        // Mazda G6S 等无线机型：与 main 中一致
        core.onHuInfo("Desay SV", "G6S")
        playThenPause()
        val fed = tr.frames.size
        core.onCarlifeStart()
        core.onFrame(aaP, 3)
        assertEquals(fed, tr.frames.size)
        assertFalse(logs.any { it.contains("AA не жду") })
    }

    @Test
    fun usbStartAfterPauseStillWaitsForAaIdr() {
        core.onHuInfo("VW BT 1420", "VW BT 1420")              // 即便 Pavel 的 USB 也是如此 - 保持原状
        playThenPause()
        val fed = tr.frames.size
        core.onCarlifeStart()
        core.onFrame(aaP, 3)                                   // USB：与原来一样，解码器等待 AA 的 IDR
        assertEquals(fed, tr.frames.size)
        assertFalse(logs.any { it.contains("AA не жду") })
    }

    @Test
    fun disabledModeCreatesNoTranscoder() {
        core.transcodeEnabled = false
        core.onCarlifeStart()
        assertEquals(0, link.created)
        assertFalse(core.isTranscoding)
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)                                 // passthrough 同以前：拼接后发送
        assertArrayEquals(aaCfg + aaIdr, link.sent[0])
    }

    @Test
    fun transcoderCreationFailureFallsBackToPassthrough() {
        link.transcoder = null
        core.onCarlifeStart()
        assertEquals(1, link.created)
        assertFalse(core.isTranscoding)
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        assertArrayEquals(aaCfg + aaIdr, link.sent[0])
        assertTrue(logs.any { it.contains("откат на passthrough") })
    }

    @Test
    fun aaFramesGoToDecoderOnlyFromIdrAndConfigIsGlued() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaP, 1)                                   // IDR 之前不交给解码器
        assertEquals(0, tr.frames.size)
        core.onFrame(aaIdr, 2)
        assertEquals(1, tr.frames.size)
        assertArrayEquals(aaCfg + aaIdr, tr.frames[0])         // IDR 附带 SPS/PPS
        core.onFrame(aaP, 3)
        assertArrayEquals(aaP, tr.frames[1])
        assertEquals(0, link.sent.size)                        // 发往 CarLife 的只有编码器输出
    }

    @Test
    fun encoderOutputGluesCsdWithFirstIdrDropsPBeforeAndSwitchesSource() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onEncoderConfig(encCfg)
        core.onEncoderFrame(encIdr, true)                      // AA IDR 尚未进入解码器：输出被忽略
        assertEquals(0, link.sent.size)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        core.onFrame(aaIdr, 1)                                 // 现在允许编码器输出
        core.onEncoderFrame(encP, false)                       // 输出首个 IDR 之前的 P 帧被丢弃
        assertEquals(0, link.sent.size)
        core.onEncoderFrame(encIdr, true)
        assertEquals(1, link.sent.size)
        assertArrayEquals(encCfg + encIdr, link.sent[0])       // SPS+PPS+IDR 在同一条消息中
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(link.sent[0]))
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(1, link.hides)
        assertEquals(1, link.takeovers)                       // 首个 AA-IDR 原子地关闭占位帧
        core.onEncoderFrame(encP, false)
        assertArrayEquals(encP, link.sent[1])
    }

    @Test
    fun aaSessionEndResetsDecoderIgnoresRepeatedFramesAndNewAaIdrSwitchesBack() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onEncoderConfig(encCfg)
        core.onFrame(aaIdr, 1)
        core.onEncoderFrame(encIdr, true)
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        core.onAaEnded(established = true)
        assertEquals(1, tr.resets.get())
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        val before = link.sent.size
        core.onEncoderFrame(encP, false)                       // 编码器重发旧帧：不发往 CarLife
        core.onEncoderFrame(encIdr, true)
        assertEquals(before, link.sent.size)
        // 服务器已重启：新会话、新 config
        core.onAaConnecting()
        assertEquals(2, tr.resets.get())
        val cfg2 = Golden.hex("00000001 67 4d401f ac2c a5") + aaPps
        core.onCodecConfig(cfg2)
        core.onFrame(aaP, 5)
        assertEquals(1, tr.frames.size)                        // IDR 之前的 P 帧未送出
        core.onFrame(aaIdr, 6)
        assertArrayEquals(cfg2 + aaIdr, tr.frames.last())
        core.onEncoderFrame(encIdr, true)
        assertArrayEquals(encCfg + encIdr, link.sent.last())
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(2, link.hides)
    }

    @Test
    fun carlifeCloseStopsTranscoderAndNextStartCreatesNew() {
        core.onCarlifeStart()
        val first = tr
        core.onCarlifeClosed()
        waitStop(first)
        assertEquals(1, first.stops.get())
        assertFalse(core.isTranscoding)
        link.transcoder = FakeTranscoder()
        core.attach(link)
        core.onCarlifeStart()
        assertEquals(2, link.created)
        assertTrue(tr !== first)
    }

    @Test
    fun rejectedFrameRequestsSyncAndWaitsForNextIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onEncoderConfig(encCfg)
        core.onFrame(aaIdr, 1)
        core.onEncoderFrame(encIdr, true)
        val syncs = tr.syncs.get()
        link.accept = false
        core.onEncoderFrame(encP, false)
        link.accept = true
        assertEquals(syncs + 1, tr.syncs.get())
        core.onEncoderFrame(encP, false)                       // 丢失 P 帧之后不能再发
        assertEquals(1, link.sent.size)
        core.onEncoderFrame(encIdr, true)
        assertEquals(2, link.sent.size)
        assertArrayEquals(encCfg + encIdr, link.sent[1])
    }

    @Test
    fun decoderLossRequestsAaKeyframeAndHuKeyframeRequestGoesToEncoder() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        now = 10_000
        core.onNeedAaKeyFrame()
        core.onFrame(aaP, 2)
        assertEquals(1, tr.frames.size)                        // 等待 IDR
        now += 2000
        core.tick()
        assertEquals(1, peer.keyframes)
        core.onCarlifeKeyFrameRequest()
        assertEquals(3, tr.syncs.get())                        // START、首个 AA-IDR 进解码器、head unit 请求
    }

    @Test
    fun transcoderErrorFallsBackToPassthrough() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        core.onTranscodeError("тест")
        assertFalse(core.isTranscoding)
        core.onFrame(aaP, 2)                                   // 等待用于 passthrough 的 IDR
        assertEquals(0, link.sent.size)
        core.onFrame(aaIdr, 3)
        assertArrayEquals(aaCfg + aaIdr, link.sent[0])
    }

    @Test
    fun statsLogIsEmittedEveryFiveSecondsWithMode() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        now = 1000
        core.tick()
        now = 7000
        core.tick()
        assertTrue(logs.any { it.contains("видео[перекодирование]") && it.contains("fps") })
        core.transcodeEnabled = false
    }

    @Test
    fun passthroughLogsIdleWithoutKeepalive() {
        core.transcodeEnabled = false
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        now = 1000
        core.onFrame(aaIdr, 1)
        now = 1500
        core.tick()
        now = 9000                                             // 屏幕静止，没有帧
        core.tick()
        assertTrue(logs.any { it.contains("видео[passthrough]") && it.contains("простой AA") })
        assertEquals("в простое ничего не шлём", 1, link.sent.size)
    }

    // --- 桥接器对转码器的契约：调用顺序与对回调的响应（转码器异步喂入）---

    /** 仅取日志中的喂入调用（config/frame:*）- 即转码器看到 AA 数据的顺序。 */
    private fun feedLog(t: FakeTranscoder) = t.journal.filter { it == "config" || it.startsWith("frame:") }

    /** 两个线程同时运行，都必须在 2 秒内结束（否则说明挂起/死锁）。 */
    private fun runConcurrently(a: () -> Unit, b: () -> Unit) {
        val errors = CopyOnWriteArrayList<Throwable>()
        val go = CountDownLatch(1)
        val threads = listOf(a, b).mapIndexed { i, body ->
            Thread({
                go.await()
                try { body() } catch (e: Throwable) { errors.add(e) }
            }, "test-thread-$i").also { it.isDaemon = true; it.start() }
        }
        go.countDown()
        val t0 = System.currentTimeMillis()
        threads.forEach { it.join(maxOf(1, 2000 - (System.currentTimeMillis() - t0))) }
        threads.forEach { assertFalse("нить ${it.name} зависла", it.isAlive) }
        assertTrue("исключения в нитях: $errors", errors.isEmpty())
    }

    @Test
    fun startOrderConfigBeforeIdrAndNoPBeforeIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaP, 1)                                   // 首个 IDR 之前的 P 帧被丢弃
        assertEquals(listOf("config"), feedLog(tr))
        core.onFrame(aaIdr, 2)
        core.onFrame(aaP, 3)
        core.onFrame(aaP, 4)
        assertEquals(listOf("config", "frame:idr", "frame:p", "frame:p"), feedLog(tr))
    }

    @Test
    fun bareIdrIsFedGluedWithKnownConfig() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)                              // config 先到达
        core.onFrame(aaIdr, 1)                                 // 裸 IDR：只有 NAL 5
        assertEquals(listOf(5), AnnexB.nalTypes(aaIdr))
        assertEquals(1, tr.frames.size)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(tr.frames[0]))
        assertArrayEquals(aaCfg + aaIdr, tr.frames[0])
    }

    @Test
    fun needAaKeyFrameDropsPUntilNextIdrAndIdrGoesWithConfig() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        core.onFrame(aaP, 2)
        core.onNeedAaKeyFrame()                                // 解码器丢失了帧
        core.onFrame(aaP, 3)
        core.onFrame(aaP, 4)
        core.onFrame(aaIdr, 5)
        core.onFrame(aaP, 6)
        assertEquals(listOf("config", "frame:idr", "frame:p", "frame:idr", "frame:p"), feedLog(tr))
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(tr.frames[2]))
        assertArrayEquals(aaCfg + aaIdr, tr.frames[2])
        assertArrayEquals(aaP, tr.frames[3])
    }

    /** PAUSE 后的 START：gotIdr 被重置，解码器等待 AA 的 IDR（桥接器在 START 时不调用解码器 reset）。 */
    @Test
    fun startAfterPauseDropsPUntilIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        core.onFrame(aaP, 2)
        core.onCarlifePause()
        core.onCarlifeStart()
        val fed = tr.frames.size
        core.onFrame(aaP, 3)
        assertEquals(fed, tr.frames.size)
        core.onFrame(aaIdr, 4)
        core.onFrame(aaP, 5)
        assertEquals(listOf("frame:idr", "frame:p", "frame:idr", "frame:p"), tr.journal.filter { it.startsWith("frame:") })
        assertArrayEquals(aaCfg + aaIdr, tr.frames[2])
    }

    /** 内核真实调用 resetDecoder - 新的 AA 会话（onAaConnecting/onAaEnded）：reset 先于 IDR，IDR 之前的 P 帧被丢弃。 */
    @Test
    fun aaReconnectResetsDecoderBeforeNextIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        core.onFrame(aaP, 2)
        core.onAaConnecting()
        assertEquals(1, tr.resets.get())
        core.onFrame(aaP, 3)                                   // 重置后的 P 帧 - 在 IDR 之前不送出
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 4)
        core.onFrame(aaP, 5)
        val j = tr.journal
        val reset = j.indexOf("reset")
        val idr2 = j.lastIndexOf("frame:idr")
        assertTrue("reset=$reset idr2=$idr2 journal=$j", reset in 0 until idr2)
        assertEquals(listOf("config", "frame:idr", "frame:p"), j.subList(reset + 1, j.size).filter { it == "config" || it.startsWith("frame:") })
        assertEquals("frame:p", j.last())
    }

    @Test
    fun transcodeErrorFromOtherThreadDoesNotHangAndStopsFeeding() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        val old = tr
        runConcurrently(
            { for (i in 0 until 200) { core.onFrame(aaP, 2L + i); Thread.sleep(1) } },
            { Thread.sleep(20); core.onTranscodeError("тест") },
        )
        waitStop(old)
        assertEquals(1, old.stops.get())
        assertFalse(core.isTranscoding)
        val fed = feedLog(old).size
        core.onFrame(aaP, 1000)
        core.onFrame(aaIdr, 1001)
        assertEquals("старый транскодер больше не кормится", fed, feedLog(old).size)
        assertArrayEquals(aaCfg + aaIdr, link.sent.last())     // 回退到 passthrough
    }

    @Test
    fun needAaKeyFrameFromOtherThreadDoesNotHangAndNextIdrIsFed() {
        core.onCarlifeStart()
        core.onCodecConfig(aaCfg)
        core.onFrame(aaIdr, 1)
        runConcurrently(
            { for (i in 0 until 200) { core.onFrame(aaP, 2L + i); Thread.sleep(1) } },
            { for (i in 0 until 20) { core.onNeedAaKeyFrame(); Thread.sleep(1) } },
        )
        val fed = tr.frames.size
        core.onFrame(aaP, 1000)                                // 最后一次回调后解码器等待 IDR
        assertEquals(fed, tr.frames.size)
        core.onFrame(aaIdr, 1001)
        assertEquals(fed + 1, tr.frames.size)
        assertArrayEquals(aaCfg + aaIdr, tr.frames.last())
        assertEquals("frame:idr", tr.journal.last { it.startsWith("frame:") })
        assertEquals(0, tr.stops.get())
    }
}

class H264StreamTest {
    private fun hex(s: String) = Golden.hex(s)

    @Test
    fun splitsAccessUnitsAndFindsConfig() {
        val sps = hex("00000001 67 42c01f da0140")
        val pps = hex("00000001 68 ce3c80")
        val idrA = hex("00000001 65 88 aa bb")                 // first_mb_in_slice = 0（首帧宏块）
        val idrB = hex("00000001 65 04 cc dd")                 // 同一画面的第二个 slice（MSB = 0）
        val p1 = hex("00000001 41 9a 11")
        val p2 = hex("00000001 41 9a 22")
        val stream = sps + pps + idrA + idrB + p1 + p2
        val (cfg, aus) = H264Stream.split(stream)
        assertArrayEquals(sps + pps, cfg)
        assertEquals(3, aus.size)
        assertArrayEquals(sps + pps + idrA + idrB, aus[0].data)
        assertTrue(aus[0].idr)
        assertArrayEquals(p1, aus[1].data)
        assertFalse(aus[1].idr)
        assertArrayEquals(p2, aus[2].data)
    }

    @Test
    fun threeByteStartCodesAreNormalized() {
        val stream = hex("000001 67 42c01f da0140 000001 68 ce3c80 000001 65 88 aa 000001 41 9a 11")
        val (cfg, aus) = H264Stream.split(stream)
        assertNotNull(cfg)
        assertEquals(2, aus.size)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(aus[0].data))
    }

    @Test
    fun noConfigGivesNullConfig() {
        val (cfg, aus) = H264Stream.split(hex("00000001 41 9a 11"))
        assertNull(cfg)
        assertEquals(1, aus.size)
    }
}

class NoKeepaliveTest {
    @Test
    fun carLifeSessionNoLongerSendsEmptyVideoKeepalive() {
        val f = listOf(
            File("src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt"),
            File("app/src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt"),
        ).first { it.isFile }
        val text = f.readText()
        assertFalse("пустой VIDEO_DATA (keepalive) не должен отправляться", text.contains("Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), null)"))
        assertFalse(text.contains("KEEPALIVE_IDLE_MS"))
    }
}
