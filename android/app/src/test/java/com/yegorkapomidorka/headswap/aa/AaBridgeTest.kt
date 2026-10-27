package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.yegorkapomidorka.headswap.aa.AapSessionTest.Phone as FakePhone

class FakeLink : CarlifeLink {
    val sent = CopyOnWriteArrayList<ByteArray>()
    val texts = CopyOnWriteArrayList<String>()
    @Volatile var hides = 0
    @Volatile var accept = true
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

    class Audio(val chan: Int, val type: Int, val payload: ByteArray)

    val audio = CopyOnWriteArrayList<Audio>()
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (!accept) return false
        audio.add(Audio(channel, type, payload))
        events.add("A${channel}:0x%x".format(type))
        return true
    }

    val modules = CopyOnWriteArrayList<Pair<Int, Int>>()
    /** 所有音频控制，按发送顺序："A<通道>:0x<类型>" 和 "M<模块>=<状态>"。 */
    val events = CopyOnWriteArrayList<String>()
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (!accept) return false
        modules.add(module to status)
        events.add("M$module=$status")
        return true
    }

    @Volatile var desktops = 0
    override fun goToDesktop() {
        desktops++
    }
}

class FakePeer : AaPeer {
    /** 第一个指针的单指投影（action, x, y）- 用于旧版断言。 */
    val touches = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    /** 完整形式：（action, action_index, 手指列表）。 */
    val events = CopyOnWriteArrayList<Triple<Int, Int, List<TouchPoint>>>()
    @Volatile var keyframes = 0
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        events.add(Triple(action, actionIndex, points))
        touches.add(Triple(action, points[0].x, points[0].y))
    }

    override fun requestKeyFrame() {
        keyframes++
    }

    val keys = CopyOnWriteArrayList<Int>()
    override fun sendKey(keycode: Int) {
        keys.add(keycode)
    }

    val rotary = CopyOnWriteArrayList<Int>()
    override fun sendRotary(delta: Int) {
        rotary.add(delta)
    }

    @Volatile var natives = 0
    override fun sendVideoFocusNative() {
        natives++
    }

    val nights = CopyOnWriteArrayList<Boolean>()
    override fun sendNightMode(night: Boolean) {
        nights.add(night)
    }
}

class AaBridgeCoreTest {
    private val sps = Golden.hex("00000001 67 42c01f da0140")
    private val pps = Golden.hex("00000001 68 ce3c80")
    private val cfg = sps + pps
    private val idr = Golden.hex("00000001 65") + ByteArray(100) { (it + 1).toByte() }
    private val p1 = Golden.hex("00000001 41") + ByteArray(30) { 0x22 }
    private var now = 0L
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also { it.attach(link) }

    private fun types(b: ByteArray) = AnnexB.nalTypes(b)

    @Test
    fun huThemeDrivesAaNightModeAndSurvivesPeerReconnect() {
        val p = FakePeer()
        core.peer = p
        assertEquals(listOf(false), p.nights.toList())        // 在 CHANGE_MD_THEME 之前为白天
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        core.onCarlifeTheme(AaBridgeCore.THEME_AUTO)
        core.onCarlifeTheme(null)                              // 无字段 - 不修改主题
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        assertEquals(listOf(false, true, false, true), p.nights.toList())
        assertEquals(AaBridgeCore.THEME_DARK, core.huTheme)
        val p2 = FakePeer()
        core.peer = p2                                         // 新的 AA 会话立即获得当前主题
        assertEquals(listOf(true), p2.nights.toList())
    }

    @Test
    fun startsWithPlaceholderAndHidesItOnFirstIdrGluedWithConfig() {
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(1, link.sent.size)
        assertArrayEquals(cfg + idr, link.sent[0])            // SPS+PPS+IDR 合并为一条消息
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
        assertEquals(1, link.hides)
        assertEquals(1, link.takeovers)                       // 第一个 AA-IDR 原子地停用占位帧
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
    }

    @Test
    fun dropsPFramesUntilIdrThenPassesThem() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(p1, 1)
        core.onFrame(p1, 2)
        assertEquals(0, link.sent.size)
        assertEquals(2L, core.dropped)
        assertEquals(0, link.hides)                           // 在 IDR 之前，占位保持显示
        core.onFrame(idr, 3)
        core.onFrame(p1, 4)
        assertEquals(2, link.sent.size)
        assertArrayEquals(p1, link.sent[1])
        assertEquals(2L, core.dropped)
    }

    @Test
    fun idrWithInlineSpsPpsIsNotGluedTwice() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(cfg + idr, 1)
        assertArrayEquals(cfg + idr, link.sent[0])
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
    }

    @Test
    fun threeByteStartCodesAreNormalizedToFour() {
        val cfg3 = Golden.hex("000001 67 42c01f da0140 000001 68 ce3c80")
        val idr3 = Golden.hex("000001 65 aabbcc")
        val p3 = Golden.hex("000001 41 11")
        core.onCarlifeStart()
        core.onCodecConfig(cfg3)
        core.onFrame(idr3, 1)
        core.onFrame(p3, 2)
        assertArrayEquals(cfg + Golden.hex("00000001 65 aabbcc"), link.sent[0])
        assertArrayEquals(Golden.hex("00000001 41 11"), link.sent[1])
    }

    @Test
    fun idrWithoutAnyConfigIsDropped() {
        core.onCarlifeStart()
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
    }

    @Test
    fun nothingGoesBeforeCarlifeStart() {
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(1L, core.dropped)
    }

    @Test
    fun carlifeStartResetsGateAndRequestsKeyframeWhenAaIsAlreadyStreaming() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)                                  // gate 已打开
        core.onFrame(p1, 2)
        assertEquals(2, link.sent.size)
        now = 10_000
        core.onCarlifeStart()                                 // 车机重启了视频：需要新的 IDR
        core.onFrame(p1, 3)                                   // IDR 之前的 P 帧被丢弃
        assertEquals(2, link.sent.size)
        core.tick()
        assertEquals("ключевой кадр сразу: AA уже играет", 1, peer.keyframes)
        core.tick()
        assertEquals("не чаще раза в 4 с", 1, peer.keyframes)
        now += 4000
        core.tick()
        assertEquals(2, peer.keyframes)
        core.onFrame(idr, 4)                                  // IDR 之后不再请求
        assertArrayEquals(cfg + idr, link.sent[2])
        now += 10_000
        core.tick()
        assertEquals(2, peer.keyframes)
    }

    /** Passthrough：IDR 在 now=0 时发出，随后是 P 帧；peer 已连接。 */
    private fun h295Setup(hondaFlag: Boolean): FakePeer {
        val peer = FakePeer()
        core.peer = peer
        if (hondaFlag) core.onHuStatistic(AaBridgeCore.HONDA_20772121_CHANNEL)
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        core.onFrame(p1, 2)
        return peer
    }

    @Test
    fun h295TouchAfterIdleAndStaleIdrRequestsKeyframeCycleAndKeepsGateOpen() {
        val peer = h295Setup(true)
        val sentBefore = link.sent.size
        now = 5000
        core.onCarlifeTouch(0, 10, 10)
        assertEquals("тач ушёл в AA", 1, peer.touches.size)
        assertEquals("сразу полный цикл", 1, peer.keyframes)
        assertTrue(logs.any { it.startsWith("BRIDGE h295: тач после 5000 мс без IDR, пауза в тачах ") && it.endsWith(" мс -> keyframe-цикл") })
        core.onFrame(p1, 3)                                   // gate 已打开：P 帧通过
        assertEquals(sentBefore + 1, link.sent.size)
        assertArrayEquals(p1, link.sent.last())
        core.onTouch(0)                                       // 车机第二次 DOWN（约 2 毫秒）：触摸间隔约 0，不触发
        assertEquals(1, peer.keyframes)
        core.tick()                                           // 没有单独的 fallback
        assertEquals(1, peer.keyframes)
        now = 5400
        core.onFrame(idr, 4)                                  // IDR 走普通分支（gate 已打开），无 takeover
        assertEquals(1, link.takeovers)
        assertEquals(1, link.hides)
        core.onFrame(p1, 5)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295AwaitedBareIdrIsGluedWithAaConfig() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 7500
        core.onFrame(idr, 4)
        assertArrayEquals(cfg + idr, link.sent.last())
        assertEquals(listOf(7, 8, 5), types(link.sent.last()))
        assertTrue(logs.any { it.startsWith("BRIDGE h295: IDR через 2500 мс после тача, nal=[5] -> [7, 8, 5]") })
        now = 7600
        core.onFrame(idr, 5)                                  // 等待结束：下一个 IDR 同 main 一样
        assertArrayEquals(idr, link.sent.last())
        core.onFrame(p1, 6)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295AwaitedIdrWithInlineSpsPpsIsNotGluedAgain() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 6000
        core.onFrame(cfg + idr, 4)
        assertArrayEquals(cfg + idr, link.sent.last())
        assertTrue(logs.any { it.startsWith("BRIDGE h295: IDR через 1000 мс после тача, nal=[7, 8, 5] -> [7, 8, 5]") })
    }

    @Test
    fun h295IdrWithoutTriggerIsNotGlued() {
        h295Setup(true)
        now = 100
        core.onFrame(idr, 4)
        assertArrayEquals(idr, link.sent.last())
        now = 2000                                            // IDR 新鲜：即使间隔后也不触发
        core.onTouch(0)
        core.onFrame(idr, 5)
        assertArrayEquals(idr, link.sent.last())
        assertTrue(logs.none { it.startsWith("BRIDGE h295: IDR через") })
    }

    @Test
    fun h295PFramesAfterTriggerAreNotTouched() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        core.onFrame(p1, 3)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295IdrAfterGiveUpIsNotGlued() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 15_100
        core.tick()                                           // 10.1 秒无 IDR：等待已重置
        core.onFrame(idr, 4)
        assertArrayEquals(idr, link.sent.last())
        assertTrue(logs.none { it.startsWith("BRIDGE h295: IDR через") })
    }

    @Test
    fun h295NoTriggerIfPreviousTouchWasLessThanIdleAgo() {
        val peer = h295Setup(true)
        now = 1000
        core.onTouch(0)                                       // IDR 新鲜，但触摸被记住
        now = 3000
        core.onTouch(2)                                       // MOVE 也视为触摸
        now = 6900                                            // IDR 已旧，上次触摸在 3.9 秒前（< 4 秒）
        core.onTouch(0)
        assertEquals("пауза 3.9 с < 4 с", 0, peer.keyframes)
        now = 10_900                                          // 上次触摸正好在 4 秒前
        core.onTouch(0)
        assertEquals(1, peer.keyframes)
    }

    @Test
    fun h295FreshIdrDoesNotTriggerEvenAfterIdle() {
        val peer = h295Setup(true)
        now = 2000                                            // IDR 在 2 秒前（<= 2.5 秒）
        core.onTouch(0)
        assertEquals(0, peer.keyframes)
    }

    @Test
    fun h295RateLimitsRepeatedTriggers() {
        val peer = h295Setup(true)
        now = 5000
        core.onTouch(0)
        assertEquals(1, peer.keyframes)
        now = 9100                                            // 间隔 4.1 秒，但距上次触发 4.1 秒 > 3 秒：可再次触发
        core.onTouch(0)
        assertEquals(2, peer.keyframes)
        now = 13_200
        core.onFrame(idr, 4)
        now = 20_000
        core.tick()
        assertEquals("tick циклов не добавляет", 2, peer.keyframes)
    }

    @Test
    fun h295DoesNothingWithoutHondaFlag() {
        val peer = h295Setup(false)
        now = 60_000
        core.onTouch(0)
        assertEquals(0, peer.keyframes)
        core.onFrame(p1, 3)
        assertArrayEquals(p1, link.sent.last())               // gate 已打开
    }

    @Test
    fun h295MoveUpAreNotTriggersAndAttachResetsFlag() {
        val peer = h295Setup(true)
        now = 60_000
        core.onTouch(2)                                       // MOVE - 不触发
        core.onTouch(1)                                       // UP - 不触发
        assertEquals(0, peer.keyframes)
        assertTrue(core.huHonda20772121)
        core.attach(link)                                     // 新会话重置车机标志
        assertFalse(core.huHonda20772121)
    }

    private fun AaBridgeCore.onTouch(action: Int) = onCarlifeTouch(action, 10, 10)

    @Test
    fun keyframeRequestWaitsForDelayWhenAaVideoAppearsAfterStart() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        now = 1000
        core.onCodecConfig(cfg)                               // AA 视频已开始，但没有 IDR
        core.tick()                                           // 开始等待计时
        now = 2000
        core.tick()
        assertEquals(0, peer.keyframes)                       // 1 秒 < 1.5 秒
        now = 2600
        core.tick()
        assertEquals(1, peer.keyframes)
    }

    @Test
    fun touchMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(2, 41, 676)
        core.onCarlifeTouch(1, 41, 676)
        core.onCarlifeTouch(3, 1, 1)                          // 无对应项
        assertEquals(listOf(Triple(0, 40, 675), Triple(2, 41, 676), Triple(1, 41, 676)), peer.touches.toList())
        core.peer = null
        core.onCarlifeTouch(0, 5, 5)                          // AA 未连接：忽略，不抛异常
        assertEquals(3, peer.touches.size)
        assertTrue(logs.any { it.contains("DOWN 40,675") })
    }

    @Test
    fun vwCns2DelphiSpreadsMoveBurst() {
        val timers = FakeTimers()
        val c = AaBridgeCore({ l, _ -> logs.add(l) }, { timers.now }, audioSchedule = timers::schedule).also { it.attach(link) }
        val peer = FakePeer()
        c.peer = peer
        c.onHuStatistic("20082114")                           // h425：车机累积约 190 毫秒的 MOVE 后成批发送
        c.onCarlifeTouch(0, 10, 10)
        c.onCarlifeTouch(2, 11, 11)                           // 批次中的第一个 MOVE 立即发出
        c.onCarlifeTouch(2, 12, 12)
        c.onCarlifeTouch(2, 13, 13)
        c.onCarlifeTouch(2, 14, 14)
        assertEquals(listOf(Triple(0, 10, 10), Triple(2, 11, 11)), peer.touches.toList())
        timers.advance(TouchBurstPacer.DEFAULT_INTERVAL_MS / 4)  // 共 4 个的批次：步长 = 间隔 / 4
        assertEquals(Triple(2, 12, 12), peer.touches.last())
        assertEquals(3, peer.touches.size)                    // 其余仍在队列中
        c.onCarlifeTouch(1, 14, 14)                           // UP：先发队列，再发 UP
        assertEquals(
            listOf(Triple(0, 10, 10), Triple(2, 11, 11), Triple(2, 12, 12), Triple(2, 13, 13), Triple(2, 14, 14), Triple(1, 14, 14)),
            peer.touches.toList(),
        )
    }

    @Test
    fun touchOnOtherHuDispatchesMovesImmediately() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(2, 11, 11)
        core.onCarlifeTouch(2, 12, 12)
        assertEquals(3, peer.touches.size)
    }

    @Test
    fun touchDecimationX2SkipsEverySecondMove() {
        val peer = FakePeer()
        core.peer = peer
        core.touchDecimationProvider = { TouchDecimation.X2 }
        core.onCarlifeTouch(0, 10, 10)
        for (y in 11..14) core.onCarlifeTouch(2, y, y)
        core.onCarlifeTouch(1, 14, 14)
        assertEquals(
            listOf(Triple(0, 10, 10), Triple(2, 11, 11), Triple(2, 13, 13), Triple(2, 14, 14), Triple(1, 14, 14)),
            peer.touches.toList(),
        )
        assertTrue(logs.any { it.contains("прореживание MOVE ×2") })
    }

    private fun pts(vararg p: Int) = p.toList().chunked(3).map { TouchPoint(it[0], it[1], it[2]) }

    @Test
    fun pinchSecondFingerUpFirst() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 100, 100)
        core.onCarlifeTouch(5 or (1 shl 8), 100, 100, 300, 300)        // 261
        core.onCarlifeTouch(2, 90, 90, 310, 310)
        core.onCarlifeTouch(2, 85, 85)                                 // px/py = 0：第二根手指保持在原位置
        core.onCarlifeTouch(2, 80, 80, 320, 320)
        core.onCarlifeTouch(6 or (1 shl 8), 80, 80, 320, 320)          // 262
        core.onCarlifeTouch(2, 81, 81)
        core.onCarlifeTouch(1, 81, 81)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 100, 100)),
                Triple(5, 1, pts(0, 100, 100, 1, 300, 300)),
                Triple(2, 0, pts(0, 90, 90, 1, 310, 310)),
                Triple(2, 0, pts(0, 85, 85, 1, 310, 310)),
                Triple(2, 0, pts(0, 80, 80, 1, 320, 320)),
                Triple(6, 1, pts(0, 80, 80, 1, 320, 320)),
                Triple(2, 0, pts(0, 81, 81)),
                Triple(1, 0, pts(0, 81, 81)),
            ),
            peer.events.toList(),
        )
        assertTrue(logs.any { it.contains("POINTER_DOWN[1] пальцев 2") })
    }

    @Test
    fun pinchFirstFingerUpKeepsId() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10, 50, 50)
        core.onCarlifeTouch(6, 12, 12, 52, 52)                         // 索引 0 的手指抬起
        core.onCarlifeTouch(2, 55, 55)                                 // 剩余手指现在位于 x,y，id 1
        core.onCarlifeTouch(1, 56, 56)
        core.onCarlifeTouch(0, 7, 7)                                   // 新手势：再次使用 id 0
        core.onCarlifeTouch(1, 7, 7)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 50, 50)),
                Triple(6, 0, pts(0, 12, 12, 1, 52, 52)),
                Triple(2, 0, pts(1, 55, 55)),
                Triple(1, 0, pts(1, 56, 56)),
                Triple(0, 0, pts(0, 7, 7)),
                Triple(1, 0, pts(0, 7, 7)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun pinchLostPointerUpAndThirdFinger() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10)                               // 无 pointer 字段：第二根手指在 x,y（如 Baidu）
        core.onCarlifeTouch(5 or (2 shl 8), 10, 10, 20, 20)            // 第三根手指：不传递
        core.onCarlifeTouch(1, 11, 11)                                 // POINTER_UP 丢失：先释放第二根手指
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(6, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(1, 0, pts(0, 11, 11)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun hardKeyMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(16)                             // SEEK_ADD
        core.onCarlifeHardKey(15)                             // SEEK_SUB
        core.onCarlifeHardKey(0x1F)                           // MEDIA_START
        core.onCarlifeHardKey(0x2F)                           // DEL：无对应项
        assertEquals(listOf(87, 88, 126), peer.keys.toList())
        assertTrue(logs.any { it.contains("руль keycode=16 -> AA KEYCODE 87") })
        core.peer = null
        core.onCarlifeHardKey(16)                             // AA 未连接：忽略，不抛异常
        assertEquals(3, peer.keys.size)
        assertTrue(AaBridgeCore.HARD_KEY_TO_ANDROID.values.all { it in AapMessages.KEYCODES_SUPPORTED })
    }

    @Test
    fun seekKeysAnswerCall() {
        val peer = FakePeer()
        core.peer = peer
        var call = PhoneCall.RINGING
        core.phoneCallProvider = { call }
        core.onCarlifeHardKey(16)                             // 其他车机：照常 seek
        core.onCarlifeHardKey(0)                              // 其他车机：keycode 0 忽略
        assertEquals(listOf(87), peer.keys.toList())
        peer.keys.clear()
        core.onHuStatistic("20082114")                        // VW CNS2 Delphi（Dmitry）
        core.onCarlifeHardKey(15)                             // 来电中：后退（左侧绿色）= 接听
        core.onCarlifeHardKey(16)                             // 来电中：前进（右侧红色）= 拒接
        call = PhoneCall.ACTIVE
        core.onCarlifeHardKey(15)                             // 通话中：吞掉后退键
        core.onCarlifeHardKey(16)                             // 通话中：前进 = 挂断
        call = PhoneCall.IDLE
        core.onCarlifeHardKey(16)                             // 无来电时恢复 seek
        core.onCarlifeHardKey(0)                              // 长按 seek 键 = 暂停/播放
        assertEquals(listOf(5, 6, 6, 87, 85), peer.keys.toList())
        assertTrue(logs.any { it.contains("руль keycode=15 -> AA KEYCODE 5 (звонок RINGING)") })
    }

    @Test
    fun selectorKnobToRotary() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(6)                              // SELECTOR_NEXT
        core.onCarlifeHardKey(7)                              // SELECTOR_PREVIOUS
        core.onCarlifeHardKey(0x14)                           // OK：按下旋钮
        assertEquals(listOf(1, -1), peer.rotary.toList())
        assertEquals(listOf(23), peer.keys.toList())
        assertTrue(logs.any { it.contains("шайба keycode=6 -> AA rotary 1") })
        assertTrue(AapMessages.KEYCODE_ROTARY_CONTROLLER in AapMessages.KEYCODES_SUPPORTED)
        core.peer = null
        core.onCarlifeHardKey(6)                              // AA 未连接：忽略，不抛异常
        assertEquals(2, peer.rotary.size)
    }

    @Test
    fun sourceSwitchPlaceholderToAaAndBackThenToNewAaStream() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        // AA 在建立连接后结束：显示相应文本的占位，不触碰 CarLife
        core.onAaEnded(established = true)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // 服务器已重启：新会话使用不同 config -> 新的 SPS+PPS+IDR
        core.onAaConnecting()
        val sps2 = Golden.hex("00000001 67 4d401f ac2c a5")
        val cfg2 = sps2 + pps
        core.onFrame(idr, 5)                                  // 新会话的 IDR 没有 config：无法拼接
        assertEquals(1, link.sent.size)
        core.onCodecConfig(cfg2)
        core.onFrame(p1, 6)
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 7)
        assertEquals(2, link.sent.size)
        assertArrayEquals(cfg2 + idr, link.sent[1])
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(2, link.hides)
    }

    @Test
    fun refusedConnectionKeepsConnectingText() {
        core.onAaConnecting()
        core.onAaEnded(established = false)
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING), link.texts.toList())
        assertEquals(AaBridgeCore.TEXT_CONNECTING, core.currentPlaceholderText)
    }

    @Test
    fun rejectedFrameClosesGateUntilNextIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        link.accept = false
        core.onFrame(p1, 2)                                   // CarLife 队列已满：帧丢失
        link.accept = true
        core.onFrame(p1, 3)                                   // 不能再发 P 帧：等待 IDR
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 4)
        assertEquals(2, link.sent.size)
    }

    // --- AA 中的"退出"（VIDEO_FOCUS NATIVE）-> CarLife GO_TO_DESKTOP ---
    @Test
    fun exitRequestSendsGoToDesktopThenNativeOnHuPause() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        val keyframesBefore = peer.keyframes
        core.onAaNativeFocusRequest()
        assertEquals(1, link.desktops)
        assertEquals("AA сразу получает цикл NATIVE -> PROJECTED (Desay не шлёт PAUSE/START)", keyframesBefore + 1, peer.keyframes)
        assertEquals("NATIVE уходит в AA только когда ГУ реально вышел (PAUSE)", 0, peer.natives)
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        assertTrue(logs.any { it.contains("«Выйти»") })
        core.onCarlifePause()                                 // 重复 PAUSE：标志已清除
        assertEquals(1, peer.natives)
    }

    @Test
    fun huPauseWithoutExitRequestSendsNothingToAa() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
        assertEquals(0, link.desktops)
    }

    @Test
    fun exitRequestWithoutCarlifeStartOrLinkIsIgnored() {
        val peer = FakePeer()
        core.peer = peer
        core.onAaNativeFocusRequest()                         // CarLife 尚未 START
        core.onCarlifePause()
        assertEquals(0, link.desktops)
        assertEquals(0, peer.natives)
        val bare = AaBridgeCore({ _, _ -> }, { now })         // 完全没有指向 CarLife 的引用
        bare.peer = peer
        bare.onAaNativeFocusRequest()
        bare.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun exitPendingIsClearedByCloseAndReattach() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onAaNativeFocusRequest()
        core.onCarlifeClosed(link)
        core.attach(link)
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun returnToCarlifeAfterExitRequestsKeyFrameCycle() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        core.onAaNativeFocusRequest()
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        val before = peer.keyframes
        core.onCarlifeStart()                                 // 车机返回：循环 NATIVE -> PROJECTED
        assertEquals(before + 1, peer.keyframes)
    }
}

/** 音频内核定时器的虚拟时间：advance(ms) 按顺序执行任务，跳过已取消的任务。 */
class FakeTimers {
    private class T(val at: Long, val task: Runnable) : MicTimerHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private val list = ArrayList<T>()
    var now = 0L
        private set

    fun schedule(delayMs: Long, task: Runnable): MicTimerHandle = T(now + delayMs, task).also { list.add(it) }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val t = list.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            list.remove(t)
            now = t.at
            t.task.run()
        }
        now = end
    }
}

class AaBridgeAudioTest {
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val timers = FakeTimers()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, audioSchedule = timers::schedule).also { it.attach(link) }
    private val MEDIA = AapMessages.CH_AUDIO_MEDIA
    private val SPEECH = AapMessages.CH_AUDIO_SPEECH
    private fun pcm(n: Int, v: Int = 1) = ByteArray(n) { (v + it % 5).toByte() }
    private fun hexs(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun mediaInitThenDataOnMediaChannelWithExactCodesAndBodies() {
        core.onCarlifeStart()
        val d = pcm(3840)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 2)
        assertEquals(3, link.audio.size)
        val init = link.audio[0]
        assertEquals(3, init.chan)
        assertEquals(0x00030001, init.type)
        assertEquals("0880f70210021810", hexs(init.payload))           // CarlifeMusicInit{48000, 2, 16}
        assertEquals(3, link.audio[1].chan)
        assertEquals(0x00030006, link.audio[1].type)
        assertArrayEquals(d, link.audio[1].payload)
        assertEquals(0x00030006, link.audio[2].type)                   // 第二个数据包不再重复 INIT
    }

    @Test
    fun mediaChunkSettingSplitsMusicIntoFixedPackets() {
        core.mediaChunkProvider = { MediaChunk.B2048 }
        core.onCarlifeStart()
        val d = pcm(8192)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        assertEquals(0x00030001, link.audio[0].type)
        val data = link.audio.drop(1)
        assertEquals(List(4) { 0x00030006 }, data.map { it.type })
        assertEquals(List(4) { 2048 }, data.map { it.payload.size })
        assertArrayEquals(d, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("пакеты по 2048 Б") })
    }

    @Test
    fun mediaChunkAutoKeepsAaPacketWhole() {
        core.mediaChunkProvider = { MediaChunk.AUTO }
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(8192), 1)
        assertEquals(listOf(0x00030001, 0x00030006), link.audio.map { it.type })
        assertEquals(8192, link.audio[1].payload.size)
        assertEquals(0, logs.count { it.contains("пакеты по") })
    }

    @Test
    fun speechGoesToTtsChannel() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(640), 1)
        assertEquals(listOf(4 to 0x00040001, 4 to 0x00040003), link.audio.map { it.chan to it.type })
        assertEquals("08807d10011810", hexs(link.audio[0].payload))     // CarlifeTTSInit{16000, 1, 16}
    }

    @Test
    fun bigChunkIsSplitAtTenKib() {
        core.onCarlifeStart()
        val d = pcm(25000)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        val data = link.audio.filter { it.type == 0x00030006 }
        assertEquals(listOf(10240, 10240, 4520), data.map { it.payload.size })
        assertArrayEquals(d, data.fold(ByteArray(0)) { a, c -> a + c.payload })
    }

    @Test
    fun mediaAndGuidanceSwitchEachInitOnceAndStopIndependently() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)       // 提示音盖在音乐之上
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 4)
        core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)                    // 提示音结束
        timers.advance(1500)                                             // TTS_STOP 延迟 1500 毫秒发出
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 5)        // 音乐继续播放，无需新的 INIT
        assertEquals(
            listOf(
                3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003, 3 to 0x30006, 4 to 0x40003,
                4 to 0x40002, 3 to 0x30006,
            ),
            link.audio.map { it.chan to it.type }
        )
        assertEquals(0, link.audio[6].payload.size)                      // STOP 无负载数据
        // 下一条提示音：新的 INIT
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 6)
        assertEquals(listOf(4 to 0x40001, 4 to 0x40003), link.audio.takeLast(2).map { it.chan to it.type })
    }

    @Test
    fun mediaStopSendsPauseOnlyIfStreamWasInitedAndNextDataReinits() {
        core.onCarlifeStart()
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // 没有播放任何内容：不发送任何内容
        assertEquals(0, link.audio.size)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // 发送 PAUSE 0x30003，而非 STOP
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // 重复的 PAUSE 不重复发送
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1, 3 to 0, 3 to 1), link.modules.toList())   // MUSIC=1 在 INIT 时，0 在 PAUSE 时
    }

    @Test
    fun aaSessionEndStopsOpenStreams() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)
        core.onAaEnded(established = true)
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.takeLast(2).map { it.chan to it.type }.toSet())
    }

    @Test
    fun carlifeStartAfterPauseResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifePause()
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30001, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun carlifeStartAfterPauseFlushesPendingTtsStop() {
        // h482：提示音在车机屏幕上结束，START 早于 1500 毫秒到达 - TTS_STOP 丢失，音乐被静音
        core.onCarlifeStart()
        core.onCarlifePause()
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 1)
        core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)
        timers.advance(800)
        core.onCarlifeStart()
        assertEquals(4 to 0x40002, link.audio.last().let { it.chan to it.type })
        timers.advance(1500)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
    }

    @Test
    fun carlifeStartWithoutPauseKeepsAudio() {
        // Desay G6S：每次按 NAVI/MEDIA 都会发 START 而没有 PAUSE - 重复 INIT 导致声音中断
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1), link.modules.toList())
    }

    @Test
    fun startAfterReattachResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        val link2 = FakeLink()
        core.attach(link2)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link2.audio.map { it.type })
    }

    @Test
    fun systemChannelAndNotStartedAreDropped() {
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)          // CarLife 尚未 START
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SYSTEM, pcm(100), 2)
        assertEquals(0, link.audio.size)
        assertEquals(2L, core.audioDropped)
    }

    @Test
    fun bluetoothModeAndUiSwitchStopUsbAudio() {
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(0)
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(1)
        assertFalse(core.audioViaUsb)
        assertTrue(logs.any { it.contains("AUDIO_TRANSMISSION_MODE=1") && it.contains("Bluetooth") })
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        assertEquals(0, link.audio.size)
        val c2 = AaBridgeCore({ _, _ -> }).also { it.attach(FakeLink()) }
        c2.usbAudioEnabled = false
        assertFalse(c2.audioViaUsb)
    }

    @Test
    fun rejectedInitIsRetriedOnNextPacket() {
        core.onCarlifeStart()
        link.accept = false
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        link.accept = true
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link.audio.map { it.type })
    }

    // ---------------------------------------------------------------- NAVI、TTS 防抖、MEDIA 队列、语音
    @Test
    fun naviStatusGoesBeforeFirstTtsInitOnlyOncePerSession() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40003"), link.events.toList())
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        core.onAudioData(SPEECH, pcm(640), 3)                            // 新的 INIT，但 NAVI 仍保持
        assertEquals(1, link.modules.count { it == 2 to 1 })
    }

    @Test
    fun naviResetByStopAllAndByCarlifeStart() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAaEnded(established = true)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "M2=0"), link.events.toList())
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(2, link.modules.count { it == 2 to 1 })             // 标志已重置：新会话中再次 NAVI=1
        core.onCarlifeClosed(link)
        assertEquals(1, link.modules.count { it == 2 to 0 })             // 会话关闭时不发送 NAVI=0（USB 已死）
    }

    @Test
    fun vwCns3TtsGetsSilencePrerollAfterInitOnlyOnRealInit() {
        core.onHuInfo("Volkswagen", "CNS3")
        core.onHuStatistic("20082122")                                   // h222：提示音开头有嘶嘶声
        core.onCarlifeStart()
        val speech = pcm(2048, 7)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 1)
        val tts = link.audio.filter { it.chan == 4 }
        assertEquals(0x40001, tts[0].type)
        val pre = tts.drop(1).dropLast(1)
        assertEquals(listOf(4096, 4096, 4096, 512), pre.map { it.payload.size })  // 400 毫秒 16 kHz 单声道 = 12800 字节，按 Baidu 分块
        assertTrue(pre.all { p -> p.type == 0x40003 && p.payload.all { it == 0.toByte() } })
        assertArrayEquals(speech, tts.last().payload)                    // 静音后的语音，未改动
        // STOP 被新的 START 取消：不重复 INIT，也没有前导静音
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 2)
        assertEquals(tts.size + 1, link.audio.count { it.chan == 4 })
        // 真正的 TTS_STOP 之后，下一次 INIT 再次带前导静音
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        val before = link.audio.size
        core.onAudioData(SPEECH, speech, 3)
        assertEquals(listOf(0x40001) + List(5) { 0x40003 }, link.audio.drop(before).map { it.type })
    }

    @Test
    fun ttsWithoutVwFlagHasNoPreroll() {
        core.onHuInfo("", "")
        core.onHuStatistic("20622116")                                   // Desay：另一款车机
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
    }

    @Test
    fun ttsPrerollSettingSendsZerosOnAnyHuAndAutoSkipsNonVw() {
        core.ttsPrerollProvider = { TtsPreroll.MS100 }
        core.onHuStatistic("20622116")                                   // 非 VW
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        val tts = link.audio.filter { it.chan == 4 }
        assertEquals(listOf(0x40001, 0x40003, 0x40003), tts.map { it.type })
        assertEquals(3200, tts[1].payload.size)                          // 100 毫秒 16 kHz 单声道，单个数据块（< 4096）
        assertTrue(tts[1].payload.all { it == 0.toByte() })
        assertEquals(1, logs.count { it.contains("пре-ролл 100 мс тишины (3200 Б)") })
        // 非 VW 上的自动模式：无前导静音
        val link2 = FakeLink()
        val c2 = AaBridgeCore({ _, _ -> }, audioSchedule = timers::schedule).also { it.attach(link2) }
        c2.ttsPrerollProvider = { TtsPreroll.AUTO }
        c2.onCarlifeStart()
        c2.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link2.audio.map { it.type })
    }

    @Test
    fun ttsPrerollOffSkipsPrerollOnVw() {
        core.ttsPrerollProvider = { TtsPreroll.MS0 }
        core.onHuStatistic("20082122")
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
    }

    @Test
    fun voiceChunkAutoOnDelphiSplitsVrSpeechIntoKib() {
        core.onHuStatistic("20082114")
        core.micModeProvider = { MicMode.CAR }
        core.onCarlifeStart()
        core.onVoiceSession(1)
        val s = pcm(2048, 3)
        core.onAudioData(SPEECH, s, 1)
        val data = link.audio.filter { it.type == 0x50003 }
        assertEquals(listOf(1024, 1024), data.map { it.payload.size })
        assertArrayEquals(s, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("BRIDGE голос: пакеты по 1024 Б (авто ГУ)") })
    }

    @Test
    fun voiceChunkSettingSplitsTtsOnNonDelphi() {
        core.voiceChunkProvider = { VoiceChunk.B512 }
        core.onCarlifeStart()
        val s = pcm(2048, 4)
        core.onAudioData(SPEECH, s, 1)
        assertEquals(0x40001, link.audio[0].type)
        val data = link.audio.drop(1)
        assertEquals(List(4) { 0x40003 }, data.map { it.type })
        assertEquals(List(4) { 512 }, data.map { it.payload.size })
        assertArrayEquals(s, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("BRIDGE голос: пакеты по 512 Б (настройка)") })
    }

    @Test
    fun voiceChunkAutoOnOtherHuKeepsAaPacketWhole() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(2048), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
        assertEquals(2048, link.audio[1].payload.size)
        assertEquals(0, logs.count { it.contains("BRIDGE голос") })
    }

    @Test
    fun voiceChunkSizesPrerollZerosWhenSet() {
        core.voiceChunkProvider = { VoiceChunk.B1024 }
        core.onHuStatistic("20082122")                                   // VW CNS3，自动前导静音 400 毫秒 = 12800 字节
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        val pre = link.audio.filter { it.chan == 4 }.drop(1).dropLast(1)
        assertEquals(List(12) { 1024 } + 512, pre.map { it.payload.size })
    }

    @Test
    fun ttsStartStopStartWithinOneSecondGivesOneInitAndNoStopBetween() {
        core.onCarlifeStart()
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)                                        // SPEECH START 取消延迟的 STOP
        core.onAudioData(SPEECH, pcm(640), 2)
        timers.advance(5000)
        assertEquals(listOf(0x40001, 0x40003, 0x40003), link.audio.map { it.type })
        // ch4 的数据同样取消定时器
        core.onAudioStop(SPEECH)
        timers.advance(1400)
        core.onAudioData(SPEECH, pcm(640), 3)
        timers.advance(5000)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        // 而真正的 STOP 恰好发生在 1500 毫秒后
        core.onAudioStop(SPEECH)
        timers.advance(1499)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
    }

    @Test
    fun stopAllAudioSendsTtsStopImmediatelyAndCancelsTimer() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        core.onAaEnded(established = true)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(5000)
        assertEquals(1, link.audio.count { it.type == 0x40002 })         // 定时器已取消：没有第二个 STOP
    }

    @Test
    fun mediaStopDuringTtsWaitsForTtsStopPlus300() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioStop(MEDIA)                                          // PAUSE 被推迟
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1500)                                             // 实际的 TTS_STOP
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(299)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(
            listOf("A3:0x30001", "M3=1", "A3:0x30006", "M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "A3:0x30003", "M3=0"),
            link.events.toList()
        )
    }

    @Test
    fun mediaDataIsDroppedWhileTtsBusyAndMediaNotInitedThenReinitsAfterRelease() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioStop(MEDIA)                                          // 立即 PAUSE：TTS 未进行
        core.onAudioData(SPEECH, pcm(100), 2)
        for (i in 0 until 1001) core.onAudioData(MEDIA, pcm(100), 10L + i)   // MEDIA 未初始化，TTS 忙
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x40001, 0x40003), link.audio.map { it.type })
        assertEquals(3, logs.count { it.contains("MEDIA данные отброшены") })   // #1, #500, #1000
        core.onAudioStop(SPEECH)
        timers.advance(1500 + 299)
        core.onAudioData(MEDIA, pcm(100), 3)                             // 仍在 +300 窗口内
        assertEquals(1, link.audio.count { it.type == 0x30001 })         // 暂时没有重复 INIT
        timers.advance(1)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(listOf(0x40002, 0x30001, 0x30006), link.audio.takeLast(3).map { it.type })
    }

    @Test
    fun mediaDataKeepsFlowingWhenInitedAndTtsPlays() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioData(MEDIA, pcm(100), 3)                             // 车机自行应答（ack）
        assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun vwCns2DelphiRoutesAssistantSpeechToVrDuringVoiceSession() {
        core.onHuStatistic("20082114")                                   // h425：助手回复在 TTS 中听不到
        core.micModeProvider = { MicMode.CAR }
        core.onCarlifeStart()
        core.onVoiceSession(1)
        val s = pcm(2048, 3)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, s, 1)
        core.onAudioData(SPEECH, s, 2)
        core.onAudioStop(SPEECH)
        val vr = link.audio.filter { it.chan == 5 }
        assertEquals(listOf(0x50002, 0x50003, 0x50003, 0x50003, 0x50003, 0x50004), vr.map { it.type })
        assertEquals("08807d10011810", hexs(vr[0].payload))             // {16000, 1, 16}
        assertArrayEquals(s.copyOfRange(0, 1024), vr[1].payload)        // Delphi 上的自动模式：每个数据包 1024 字节
        assertArrayEquals(s.copyOfRange(1024, 2048), vr[2].payload)
        assertTrue(link.audio.none { it.chan == 4 })
        assertTrue("VR=0 не раньше выхода из голоса", "M4=0" !in link.events)
        core.onVoiceSession(2)
        timers.advance(1000)
        assertTrue("после голоса и VR STOP - снова VR=0 (громкость)", "M4=0" in link.events)
    }

    @Test
    fun speechInVoiceSessionStaysOnTtsWithoutDelphiOrWithPhoneMic() {
        core.onCarlifeStart()
        core.onVoiceSession(1)
        core.onAudioData(SPEECH, pcm(2048), 1)
        assertTrue(link.audio.none { it.chan == 5 })
        assertEquals(0x40001, link.audio.first { it.chan == 4 }.type)

        val l2 = FakeLink()
        val c2 = AaBridgeCore({ l, _ -> logs.add(l) }, audioSchedule = timers::schedule).also { it.attach(l2) }
        c2.onHuStatistic("20082114")
        c2.micModeProvider = { MicMode.PHONE }
        c2.onCarlifeStart()
        c2.onVoiceSession(1)
        c2.onAudioData(SPEECH, pcm(2048), 1)
        assertTrue(l2.audio.none { it.chan == 5 })
    }

    @Test
    fun voiceKeyDoubleFromHuIsDebounced() {
        var now = 1_000L
        val c = AaBridgeCore({ l, _ -> logs.add(l) }, clockMs = { now })
        val peer = FakePeer()
        c.peer = peer
        c.onCarlifeHardKey(0x21)                                         // h425：Delphi 在 1-2 毫秒内发送两次 VR_START
        now += 2
        c.onCarlifeHardKey(0x21)
        assertEquals(listOf(AaBridgeCore.KEYCODE_SEARCH), peer.keys.toList())
        now += 600
        c.onCarlifeHardKey(0x21)
        assertEquals(listOf(AaBridgeCore.KEYCODE_SEARCH, AaBridgeCore.KEYCODE_SEARCH), peer.keys.toList())
    }

    @Test
    fun voiceStartPausesMediaStopsForwardingAndBurstExitsOnce() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onVoiceSession(1)
        assertEquals(listOf("A3:0x30001", "M3=1", "A3:0x30006", "A3:0x30003", "M3=0"), link.events.toList())
        core.onAudioData(MEDIA, pcm(100), 2)                             // 语音进行中：不转发 MEDIA_DATA
        assertEquals(3, link.audio.size)
        // 序列 2/2/1/2/2：只退出一次，在最后一组 2 之后 500 毫秒
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(1)                                           // 退出已取消
        timers.advance(1000)
        core.onAudioData(MEDIA, pcm(100), 3)
        assertEquals(3, link.audio.size)                                 // 语音仍在进行
        core.onVoiceSession(2)
        timers.advance(100)
        core.onVoiceSession(2)
        timers.advance(399)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(3, link.audio.size)
        timers.advance(1)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
        core.onAudioData(MEDIA, pcm(100), 5)                             // 退出后：在首批数据时 INIT
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        timers.advance(5000)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
    }

    @Test
    fun voicePauseWaitsForTtsSerialization() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onVoiceSession(1)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1799)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x30003 })
    }

    @Test
    fun ttsFirstPacketsLogPeakAndRms() {
        core.onCarlifeStart()
        val pcm = ByteArray(8)                                           // 采样值 0, 1000, -2000, 0
        pcm[2] = (1000 and 0xFF).toByte(); pcm[3] = (1000 shr 8).toByte()
        pcm[4] = ((-2000) and 0xFF).toByte(); pcm[5] = ((-2000) shr 8).toByte()
        for (i in 1..5) core.onAudioData(SPEECH, pcm, i.toLong())
        val diag = logs.filter { it.contains("BRIDGE TTS пакет") }
        assertEquals(3, diag.size)
        assertTrue(diag[0].contains("peak=2000") && diag[0].contains("rms=1118"))
        core.onAudioData(SPEECH, ByteArray(8), 6L)
        assertEquals(3, logs.count { it.contains("BRIDGE TTS пакет") })
        // 新的 TTS 会话：再次三个数据包，零值被标记
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        for (i in 1..4) core.onAudioData(SPEECH, ByteArray(8), i.toLong())
        val diag2 = logs.filter { it.contains("BRIDGE TTS пакет") }.drop(3)
        assertEquals(3, diag2.size)
        assertTrue(diag2.all { it.contains("peak=0") && it.contains("ВСЕ НУЛИ") })
    }

    @Test
    fun mediaInitedFlagFollowsInitAndPause() {
        core.onCarlifeStart()
        assertFalse(core.isMediaInited)
        core.onAudioData(MEDIA, pcm(100), 1)
        assertTrue(core.isMediaInited)
        core.onAudioStop(MEDIA)
        assertFalse(core.isMediaInited)
    }

    @Test
    fun flagsOffRestoreImmediateBehaviour() {
        try {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = false
            AaBridgeCore.NAVI_STATUS_ENABLED = false
            AaBridgeCore.MODULE_MUSIC_ENABLED = false
            AaBridgeCore.MEDIA_PAUSE_ENABLED = false
            AaBridgeCore.VOICE_SESSION_ENABLED = false
            core.onCarlifeStart()
            core.onAudioData(MEDIA, pcm(100), 1)
            core.onAudioData(SPEECH, pcm(100), 2)
            core.onAudioStop(MEDIA)                                      // 无队列且无 PAUSE：直接 MEDIA_STOP
            core.onAudioStop(SPEECH)                                     // 无防抖：直接 TTS_STOP
            core.onVoiceSession(1)
            assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30002, 0x40002), link.audio.map { it.type })
            assertEquals(0, link.modules.size)
        } finally {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = true
            AaBridgeCore.NAVI_STATUS_ENABLED = true
            AaBridgeCore.MODULE_MUSIC_ENABLED = true
            AaBridgeCore.MEDIA_PAUSE_ENABLED = true
            AaBridgeCore.VOICE_SESSION_ENABLED = true
        }
    }
}

/** 桥接的 AA 会话对战 loopback 上的脚本电话：视频 -> CarLife 链路，CarLife 触摸 -> 电话。 */
class AaBridgeIntegrationTest {
    @Test
    fun aaVideoReachesCarlifeAndCarlifeTouchReachesPhoneThenPlaceholderOnEnd() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val sessionCreated = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, preVideoTimeoutMs = 10_000) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also {
                    if (sessionRef.compareAndSet(null, it)) sessionCreated.countDown()
                }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(sessionCreated.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true)
        phone.session = sessionRef.get()
        phone.start()
        // 等待视频到达 CarLife 链路
        val t0 = System.currentTimeMillis()
        while (link.sent.size < 2 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        assertEquals("видео не дошло: " + logs.joinToString("\n"), 2, link.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS + FakePhone.IDR, link.sent[0])           // SPS+PPS+IDR 已拼接
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(link.sent[0]))
        assertArrayEquals(FakePhone.SMALL, link.sent[1])
        assertEquals(1, link.hides)
        // 车机触摸 -> AA 电话
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(1, 40, 675)
        // 电话在两次触摸后自行结束会话（requestStop -> ByeBye）
        phone.join(20_000)
        assertEquals(null, phone.error)
        assertEquals(listOf(Triple(0, 40, 675), Triple(1, 40, 675)), phone.touches.toList())
        assertTrue(phone.touchTs.all { it > 0 })
        // AA 已结束：显示文本占位，USB（link）不关闭
        val t1 = System.currentTimeMillis()
        while (link.texts.last() != AaBridgeCore.TEXT_AA_LOST && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // 服务器消失：connection refused，连接器重试连接
        server.close()
        val a0 = connector.attempts
        val t2 = System.currentTimeMillis()
        while (connector.attempts < a0 + 3 && System.currentTimeMillis() - t2 < 10_000) Thread.sleep(20)
        assertTrue("повторов подключения нет: ${connector.attempts} (было $a0)", connector.attempts >= a0 + 3)
        assertEquals("после отказов текст остаётся", AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue("коннектор не остановился", stopped.await(10, TimeUnit.SECONDS))
        assertFalse(logs.isEmpty())
    }

    @Test
    fun refusedConnectionRetriesAndShowsStartServerText() {
        val port = ServerSocket(0).use { it.localPort }        // 端口空闲：connection refused
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        // 自己的计数器：共享的 AaState.serverFailures 会记住相邻测试中"服务器曾工作"的状态
        val connector = AaConnector(core, creds, { l, _ -> logs.add(l) }, options = { AapOptions(port = port) }, retryMs = 100, failures = ServerFailures())
        connector.start()
        val t0 = System.currentTimeMillis()
        while (connector.attempts < 4 && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        assertTrue(connector.attempts >= 4)
        // 该进程中服务器尚未运行过：第一次拒绝后立即提示启用
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING, AaBridgeCore.TEXT_START_SERVER), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals("в логе одна запись про недоступность", 1, logs.count { it.contains("BRIDGE сервер ГУ Android Auto недоступен") })
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun fullAudioSinksAreAnnouncedAndPcmReachesCarlifeChannels() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val created = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, sdProvider = { AapSdOptions(fullAudio = core.audioViaUsb) }) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also { if (sessionRef.compareAndSet(null, it)) created.countDown() }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(created.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true, fullAudio = true)
        phone.session = sessionRef.get()
        phone.start()
        val t0 = System.currentTimeMillis()
        while (link.audio.size < 4 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        // 电话等待两次触摸，然后自行结束会话
        core.onCarlifeTouch(0, 1, 1)
        core.onCarlifeTouch(1, 1, 1)
        phone.join(20_000)
        assertEquals("ошибка телефона (SDR с аудио / ack): ${phone.error}\n" + logs.joinToString("\n"), null, phone.error)
        // 顺序与电话一致：MEDIA init+data、SPEECH init+data、MEDIA stop（-> CarLife PAUSE，TTS 进行中：暂停需等待）；SYSTEM（ch5）不转发
        val seq = link.audio.map { it.chan to it.type }
        assertEquals(listOf(3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003), seq.take(4))
        val t1 = System.currentTimeMillis()
        while (link.audio.size < 6 && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)     // AA 结束：stopAllAudio -> MEDIA_STOP + TTS_STOP
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.drop(4).take(2).map { it.chan to it.type }.toSet())
        assertEquals(3840, link.audio[1].payload.size)
        assertEquals(640, link.audio[3].payload.size)
        assertEquals("0880f70210021810", link.audio[0].payload.joinToString("") { "%02x".format(it) })
        val stopped = CountDownLatch(1)
        server.close()
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(15, TimeUnit.SECONDS))
    }
}
