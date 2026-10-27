package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.Msgs
import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbText
import com.yegorkapomidorka.headswap.Schemas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import kotlin.math.abs
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** 记录元数据和 MODULE_STATUS 的链接。 */
class MetaLink : CarlifeLink {
    val infos = ArrayList<CarlifeMediaInfo>()
    val progress = ArrayList<Int>()
    val turns = ArrayList<NaviTurnInfo>()
    val modules = ArrayList<Pair<Int, Int>>()
    val audio = ArrayList<Int>()
    override fun sendVideo(payload: ByteArray) = true
    override fun showPlaceholder(text: String) {}
    override fun hidePlaceholder() {}
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        audio.add(type)
        return true
    }

    /** 像真正的本田车机一样:对每个 NAVI=1 的 MODULE_STATUS 回复 SUBSCRIBE_START(测试通过 pumpSubscribe 交付)。 */
    var huResubscribes = false
    var pendingSubscribe = 0

    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        modules.add(module to status)
        if (huResubscribes && module == CarlifeAudio.MODULE_NAVI && status == 1) pendingSubscribe++
        return true
    }

    override fun sendMediaInfo(info: CarlifeMediaInfo): Boolean {
        infos.add(info)
        return true
    }

    override fun sendMediaProgress(progressBar: Int): Boolean {
        progress.add(progressBar)
        return true
    }

    override fun sendNaviTurn(info: NaviTurnInfo): Boolean {
        turns.add(info)
        return true
    }

    fun navi() = modules.filter { it.first == CarlifeAudio.MODULE_NAVI }.map { it.second }
}

class AaMetaTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    // 默认启用整条路线 NAVI=1(Desay G6S 除外);关闭该标志的测试在这里恢复它
    @After
    fun routeNaviOn() {
        AaBridgeCore.ROUTE_NAVI_STATUS_ENABLED = true
    }

    private fun status(state: Int, pos: Int, source: String = "Spotify") =
        AapPb().v(1, state).str(2, source).v(3, pos).bool(4, false).bool(5, false).bool(6, false).toByteArray()

    private fun metadata(song: String, art: ByteArray? = png, duration: Int = 215): ByteArray {
        val p = AapPb().str(1, song).str(2, "Artist").str(3, "Album")
        if (art != null) p.bytes(4, art)
        return p.str(5, "Mix").v(6, duration).v(7, 0).toByteArray()
    }

    private fun navState(type: Int, road: String, exit: Int? = null): ByteArray {
        val man = AapPb().v(1, type)
        if (exit != null) man.v(2, exit)
        val step = AapPb().msg(1, man).msg(2, AapPb().str(1, road))
            .msg(3, AapPb().msg(1, AapPb().v(1, 1).bool(2, true)))
            .msg(4, AapPb().str(1, "Proceed to $road"))
        return AapPb().msg(1, step).msg(2, AapPb().str(1, "Home")).toByteArray()
    }

    private fun navPosition(meters: Int, time: Int, dest: Int = 5000) = AapPb()
        .msg(1, AapPb().msg(1, AapPb().v(1, meters).str(2, "$meters").v(3, 1)).v(2, time))
        .msg(2, AapPb().msg(1, AapPb().v(1, dest)).str(2, "12:30").v(3, 600))
        .msg(3, AapPb().str(1, "Current St"))
        .toByteArray()

    private fun legacyTurn(event: Int, side: Int, road: String) = AapPb().str(1, road).v(2, side).v(3, event).toByteArray()
    private fun legacyDistance(m: Int, t: Int) = AapPb().v(1, m).v(2, t).toByteArray()

    private class Rec : AaMetaSink {
        val events = ArrayList<String>()
        override fun onMediaStatus(s: AaMediaStatus) { events.add("status ${s.state} ${s.playbackSeconds} ${s.source}") }
        override fun onMediaMetadata(m: AaMediaMetadata) { events.add("meta ${m.song} ${m.durationSeconds} ${m.art?.size}") }
        override fun onNavCluster(start: Boolean) { events.add("cluster $start") }
        override fun onNavStatus(status: Int) { events.add("navstatus $status") }
        override fun onNavTurn(t: AaNavTurn) { events.add("turn ${t.nextTurn} ${t.road} ${t.maneuver} ${t.roundaboutExit}") }
        override fun onNavDistance(d: AaNavDistance) { events.add("dist ${d.meters} ${d.timeSeconds} ${d.destinationMeters}") }
    }

    // --- 解析 ch9 / ch10 ---
    @Test
    fun parsesMediaPlaybackChannel() {
        val r = Rec()
        assertNotNull(AaMeta.dispatch(M.CH_MEDIA_PLAYBACK, 0x8001, status(2, 42), r))
        assertNotNull(AaMeta.dispatch(M.CH_MEDIA_PLAYBACK, 0x8003, metadata("Song"), r))
        assertNull(AaMeta.dispatch(M.CH_MEDIA_PLAYBACK, 0x8002, ByteArray(0), r))
        assertEquals(listOf("status 2 42 Spotify", "meta Song 215 7"), r.events)
        val m = AaMeta.parseMediaMetadata(metadata("Песня"))!!
        assertEquals("Песня", m.song)
        assertEquals("Artist", m.artist)
        assertEquals("Album", m.album)
        assertEquals("Mix", m.playlist)
        assertArrayEquals(png, m.art)
    }

    @Test
    fun parsesNavigationChannelBothVariants() {
        val r = Rec()
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8001, ByteArray(0), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8003, AapPb().v(1, 1).toByteArray(), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8006, navState(8, "Main St"), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8007, navPosition(110, 12), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8004, legacyTurn(3, 1, "Old Rd"), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8005, legacyDistance(250, 20), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8006, navState(30, "Ring", exit = 2), r)
        AaMeta.dispatch(M.CH_NAVIGATION, 0x8002, ByteArray(0), r)
        assertEquals(
            listOf(
                "cluster true", "navstatus 1", "turn 3 Main St TURN_NORMAL_RIGHT null", "dist 110 12 5000",
                "turn 8 Old Rd SLIGHT_TURN/LEFT null", "dist 250 20 null", "turn 9 Ring ROUNDABOUT_ENTER 2", "cluster false",
            ),
            r.events,
        )
    }

    @Test
    fun navStateWithoutRoadNameFallsBackToCue() {
        val step = AapPb().msg(1, AapPb().v(1, 36)).msg(4, AapPb().str(1, "Proceed to the route"))
        val t = AaMeta.parseNavState(AapPb().msg(1, step).toByteArray())!!
        assertEquals("Proceed to the route", t.road)
        assertEquals(AaMeta.T_FRONT, t.nextTurn)
    }

    @Test
    fun brokenPayloadDoesNotThrow() {
        val r = Rec()
        val res = AaMeta.dispatch(M.CH_NAVIGATION, 0x8006, byteArrayOf(0x0a, 0x7f), r)
        assertTrue(res!!.isNotEmpty())
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun maneuverMapping() {
        assertEquals(1, AaMeta.turnFromManeuver(36))   // STRAIGHT
        assertEquals(2, AaMeta.turnFromManeuver(6))    // SLIGHT_RIGHT
        assertEquals(8, AaMeta.turnFromManeuver(5))    // SLIGHT_LEFT
        assertEquals(3, AaMeta.turnFromManeuver(8))
        assertEquals(7, AaMeta.turnFromManeuver(7))
        assertEquals(4, AaMeta.turnFromManeuver(10))
        assertEquals(6, AaMeta.turnFromManeuver(9))
        assertEquals(5, AaMeta.turnFromManeuver(11))
        assertEquals(9, AaMeta.turnFromManeuver(30))
        assertEquals(10, AaMeta.turnFromManeuver(31))
        assertEquals(23, AaMeta.turnFromManeuver(1))
        assertEquals(24, AaMeta.turnFromManeuver(39))
        assertEquals(24, AaMeta.turnFromManeuver(42))
        assertEquals(20, AaMeta.turnFromManeuver(25))
        assertEquals(21, AaMeta.turnFromManeuver(26))
        assertEquals(1, AaMeta.turnFromManeuver(0))
        assertEquals(1, AaMeta.turnFromManeuver(999))
        // 旧版:event × side
        assertEquals(2, AaMeta.turnFromLegacy(3, 2))
        assertEquals(8, AaMeta.turnFromLegacy(3, 1))
        assertEquals(3, AaMeta.turnFromLegacy(4, 2))
        assertEquals(7, AaMeta.turnFromLegacy(4, 1))
        assertEquals(4, AaMeta.turnFromLegacy(5, 2))
        assertEquals(6, AaMeta.turnFromLegacy(5, 1))
        assertEquals(5, AaMeta.turnFromLegacy(6, 3))
        assertEquals(24, AaMeta.turnFromLegacy(19, 3))
        assertEquals(1, AaMeta.turnFromLegacy(4, 3))
        // 每个机动动作代码都能绘制
        for (t in 0..45) assertNotNull(AaMeta.glyph(AaMeta.turnFromManeuver(t)))
        assertEquals(90, AaMeta.glyph(3).angle)
        assertEquals(-90, AaMeta.glyph(7).angle)
        assertEquals(AaMeta.GlyphKind.DEST, AaMeta.glyph(24).kind)
    }

    // --- 按通道的日志消息名 ---
    @Test
    fun logNamesDependOnChannel() {
        assertEquals("MEDIA_CONFIG", M.messageName(M.CH_AUDIO_MEDIA, 0x8003))
        assertEquals("MEDIA_START", M.messageName(M.CH_VIDEO, 0x8001))
        assertEquals("MEDIA_PLAYBACK_STATUS", M.messageName(M.CH_MEDIA_PLAYBACK, 0x8001))
        assertEquals("MEDIA_PLAYBACK_METADATA", M.messageName(M.CH_MEDIA_PLAYBACK, 0x8003))
        assertEquals("NAVIGATION_STATUS", M.messageName(M.CH_NAVIGATION, 0x8003))
        assertEquals("NAVIGATION_TURN_EVENT", M.messageName(M.CH_NAVIGATION, 0x8004))
        assertEquals("NAVIGATION_CURRENT_POSITION", M.messageName(M.CH_NAVIGATION, 0x8007))
        assertEquals("UNKNOWN_0x8009", M.messageName(M.CH_NAVIGATION, 0x8009))

        val big = ByteArray(100_000) { 7 }
        val meta = M.describe(M.CH_MEDIA_PLAYBACK, 0x8003, metadata("Song", art = png + big), 2000)
        assertTrue(meta, meta.startsWith("{song=\"Song\" artist=\"Artist\" album=\"Album\" album_art=<100007 Б PNG>"))
        assertTrue(meta, meta.contains("duration_seconds=215"))
        assertEquals("{status=2}", M.describe(M.CH_NAVIGATION, 0x8003, AapPb().v(1, 2).toByteArray()))
        assertTrue(M.describe(M.CH_AUDIO_MEDIA, 0x8003, AapPb().v(1, 2).v(2, 30).toByteArray()).contains("max_unacked=30"))
        val st = M.describe(M.CH_NAVIGATION, 0x8006, navState(8, "Main St"), 2000)
        assertTrue(st, st.contains("maneuver={type=8}") && st.contains("road={name=\"Main St\"}") && st.contains("alternate_text="))
        val pos = M.describe(M.CH_NAVIGATION, 0x8007, navPosition(110, 12), 2000)
        assertTrue(pos, pos.contains("step_distance={distance={meters=110") && pos.contains("time_to_step_seconds=12"))
        assertTrue(M.isPeriodicMeta(M.CH_MEDIA_PLAYBACK, 0x8001))
        assertFalse(M.isPeriodicMeta(M.CH_MEDIA_PLAYBACK, 0x8003))
        assertFalse(M.isPeriodicMeta(M.CH_AUDIO_MEDIA, 0x8001))
    }

    // --- 桥接核心中的中继 ---
    private class Rig {
        var now = 1_000_000L
        val link = MetaLink()
        val logs = ArrayList<String>()
        val core = AaBridgeCore({ s, _ -> logs.add(s) }, clockMs = { now })

        init {
            core.attach(link)
        }

        fun start() = core.onCarlifeStart()

        /** 交付响应的 SUBSCRIBE_START;轮数受限,使重新激活循环可见而不挂起。 */
        fun pumpSubscribe(maxRounds: Int = 10): Int {
            var rounds = 0
            while (link.pendingSubscribe > 0 && rounds < maxRounds) {
                link.pendingSubscribe--
                rounds++
                core.onCarlifeSubscribe(listOf(0), true)
            }
            return rounds
        }
    }

    @Test
    fun musicDeferredUntilStartThenProgressInMsThrottled() {
        val r = Rig()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 10))!!)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A"))!!)
        assertTrue("до START ничего", r.link.infos.isEmpty() && r.link.progress.isEmpty())
        r.start()
        assertEquals(1, r.link.infos.size)
        val i = r.link.infos[0]
        assertEquals("A", i.song)
        assertEquals(215, i.durationSeconds)
        assertEquals("Spotify#1", i.source)
        assertEquals(listOf(10_000), r.link.progress)

        r.now += 400
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 11))!!)
        assertEquals("чаще раза в секунду не шлём", listOf(10_000), r.link.progress)
        r.now += 700
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 12))!!)
        assertEquals(listOf(10_000, 12_000), r.link.progress)
        r.now += 100
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 3))!!)
        assertEquals("перемотка назад сразу", listOf(10_000, 12_000, 3_000), r.link.progress)
        r.now += 2000
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 3))!!)
        assertEquals("без изменений не шлём", 3, r.link.progress.size)

        // 重复相同元数据 - 静默;同一曲目的新封面 - 相同 source;新曲目 - 新 source
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A"))!!)
        assertEquals(1, r.link.infos.size)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A", art = png + byteArrayOf(9)))!!)
        assertEquals(2, r.link.infos.size)
        assertEquals("Spotify#1", r.link.infos[1].source)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("B", art = null))!!)
        assertEquals("Spotify#2", r.link.infos[2].source)
        assertNull(r.link.infos[2].art)
    }

    @Test
    fun musicSubscriptionStopSilences() {
        val r = Rig()
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onCarlifeSubscribe(listOf(2), false)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A"))!!)
        assertTrue(r.link.infos.isEmpty())
        r.core.onCarlifeSubscribe(listOf(2), true)
        assertEquals(1, r.link.infos.size)
    }

    @Test
    fun navigationNeedsSubscription() {
        val r = Rig()
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        assertTrue(r.link.turns.isEmpty())
        // NAVI=1 在订阅前发出:VW BT 1538 (h244) 只在它之后订阅
        assertEquals(listOf(1), r.link.navi())
        // 订阅 START moduleID=0 -> 立即发送当前机动动作,NAVI=1 不重复
        r.core.onCarlifeSubscribe(listOf(0), true)
        assertEquals(listOf(1), r.link.navi())
        assertEquals(1, r.link.turns.size)
        assertEquals(1, r.link.turns[0].action)
    }

    @Test
    fun navigationFlow() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(2)            // START 时为 INACTIVE:既无「到达」也无 NAVI
        assertTrue(r.link.turns.isEmpty())
        assertTrue(r.link.navi().isEmpty())

        r.core.onNavStatus(1)
        assertEquals(listOf(1), r.link.navi())
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        assertTrue("без дистанции не шлём", r.link.turns.isEmpty())
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        assertEquals(1, r.link.turns.size)
        with(r.link.turns[0]) {
            assertEquals(1, action)
            assertEquals(3, nextTurn)
            assertEquals("Main St", roadName)
            assertEquals(300, totalDistance)
            assertEquals(300, remainDistance)
            assertEquals(30, timeSeconds)
        }
        r.now += 300
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(280, 28))!!)
        assertEquals("троттлинг 1 с", 1, r.link.turns.size)
        r.now += 800
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(250, 25))!!)
        assertEquals(2, r.link.turns.size)
        with(r.link.turns[1]) {
            assertEquals(2, action)
            assertEquals(300, totalDistance)
            assertEquals(250, remainDistance)
        }
        // 新机动动作:距离重置,第一条距离不经过节流直接发出,total 取自它
        r.now += 10
        r.core.onNavTurn(AaMeta.parseNavState(navState(7, "Second Ave"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(900, 60))!!)
        assertEquals(3, r.link.turns.size)
        with(r.link.turns[2]) {
            assertEquals("новый манёвр - SHOW", 1, action)
            assertEquals(7, nextTurn)
            assertEquals(900, totalDistance)
        }
        // 路线结束:action 3,nextTurn 24,NAVI=0
        r.core.onNavStatus(2)
        with(r.link.turns.last()) {
            assertEquals(3, action)
            assertEquals(24, nextTurn)
            assertEquals(AaMeta.END_ROAD, roadName)
        }
        assertEquals(listOf(1, 0), r.link.navi())
    }

    @Test
    fun stationaryKeepaliveAndReshow() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 6))!!)
        assertEquals(1, r.link.turns.size)
        // 停着:AA 每秒重复相同数据;SHOW -> UPDATE 立即发出,之后静默到 5 秒
        val same = { r.now += 1000; r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 6))!!) }
        same()
        assertEquals(2, r.link.turns.size)
        assertEquals(2, r.link.turns[1].action)
        repeat(4) { same() }
        assertEquals("дедуп до 5 с", 2, r.link.turns.size)
        same()
        assertEquals("keepalive через 5 с", 3, r.link.turns.size)
        assertEquals(2, r.link.turns[2].action)
        // AA 沉默了 15 秒:下一个数据包再次 SHOW
        r.now += 15_000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(20, 4))!!)
        assertEquals(4, r.link.turns.size)
        assertEquals(1, r.link.turns[3].action)
        r.now += 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(10, 2))!!)
        assertEquals(2, r.link.turns[4].action)
    }

    @Test
    fun periodicShowWhileDriving() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        var m = 2000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(m, 200))!!)
        // 行驶中:距离每秒变化,SHOW 至少每 10 秒一次,其余为 UPDATE
        repeat(25) {
            r.now += 1000
            m -= 10
            r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(m, 200))!!)
        }
        assertEquals(26, r.link.turns.size)
        val shows = r.link.turns.withIndex().filter { it.value.action == 1 }.map { it.index }
        assertEquals(listOf(0, 10, 20), shows)
    }

    @Test
    fun sameKeyTurnDetectedByDistanceJump() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(7, "Krasnodarskaya"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(100, 20))!!)
        r.now += 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(10, 1))!!)
        assertEquals(100, r.link.turns.last().totalDistance)
        // 下一个转弯相同(键未变化),距离 10 -> 50:新机动动作,不节流
        r.now += 100
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(50, 12))!!)
        with(r.link.turns.last()) {
            assertEquals("новый поворот - SHOW", 1, action)
            assertEquals(50, remainDistance)
            assertEquals("total с нового поворота", 50, totalDistance)
        }
        // 10-20 米的抖动不算新转弯
        r.now += 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(40, 10))!!)
        r.now += 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(60, 12))!!)
        assertEquals(60, r.link.turns.last().totalDistance)
    }

    @Test
    fun legacyEventsWork() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavTurn(AaMeta.parseLegacyTurn(legacyTurn(4, 1, "Left St"))!!)
        r.core.onNavDistance(AaMeta.parseLegacyDistance(legacyDistance(120, 9))!!)
        assertEquals(listOf(1), r.link.navi())
        assertEquals(7, r.link.turns.single().nextTurn)
        assertEquals(120, r.link.turns.single().remainDistance)
        r.core.onNavCluster(false)
        assertEquals(3, r.link.turns.last().action)
        assertEquals(listOf(1, 0), r.link.navi())
    }

    @Test
    fun mixedLegacyAndNewStreamUsesNewOnly() {
        fun run(withLegacy: Boolean): MetaLink {
            val r = Rig()
            r.core.onCarlifeSubscribe(listOf(0), true)
            r.start()
            r.core.onNavStatus(1)
            for (k in 0 until 6) {
                r.now += 500
                r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
                if (withLegacy) r.core.onNavTurn(AaMeta.parseLegacyTurn(legacyTurn(4, 2, "Old Rd"))!!)
                r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(500 - k * 50, 40 - k))!!)
                if (withLegacy) r.core.onNavDistance(AaMeta.parseLegacyDistance(legacyDistance(999, 99))!!)
            }
            return r.link
        }
        val clean = run(false)
        val mixed = run(true)
        assertEquals(clean.turns.size, mixed.turns.size)
        assertTrue(mixed.turns.all { it.roadName == "Main St" && it.remainDistance != 999 })
        assertEquals(1, mixed.turns.count { it.action == 1 })
    }

    @Test
    fun cueTextChangeIsNotANewManeuver() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        fun cueOnly(cue: String): ByteArray =
            AapPb().msg(1, AapPb().msg(1, AapPb().v(1, 8)).msg(4, AapPb().str(1, cue))).toByteArray()
        r.core.onNavTurn(AaMeta.parseNavState(cueOnly("Turn right in 300 m"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        r.now += 200
        r.core.onNavTurn(AaMeta.parseNavState(cueOnly("Turn right in 250 m"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(250, 25))!!)
        assertEquals("тот же манёвр: троттлинг не обойдён", 1, r.link.turns.size)
    }

    @Test
    fun tailEventsAfterInactiveIgnored() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        r.core.onNavStatus(2)
        val turns = r.link.turns.size
        assertEquals(listOf(1, 0), r.link.navi())
        r.now += 800
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(10, 1))!!)
        r.core.onNavTurn(AaMeta.parseNavState(navState(39, "Home"))!!)
        assertEquals("хвост не поднимает NAVI", listOf(1, 0), r.link.navi())
        assertEquals("и не шлёт NEXT_TURN", turns, r.link.turns.size)
        // 之后 - 无状态的新路线隐式启动
        r.now += MetaRelay.TAIL_MS
        r.core.onNavTurn(AaMeta.parseNavState(navState(7, "Next"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(100, 10))!!)
        assertEquals(listOf(1, 0, 1), r.link.navi())
        assertEquals(1, r.link.turns.last().action)
    }

    @Test
    fun ttsAndRouteShareNaviModule() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        assertEquals(listOf(1), r.link.navi())
        r.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)
        assertEquals("NAVI уже 1: перед TTS не повторяем", listOf(1), r.link.navi())
        r.core.onNavStatus(2)
        assertEquals("NAVI держит TTS", listOf(1), r.link.navi())
        r.core.onAaEnded(true)
        assertEquals(listOf(1, 0), r.link.navi())

        // TTS 早于路线,中途热 START:NAVI=1 只一次,路线结束不熄灭它
        val q = Rig()
        q.core.onCarlifeSubscribe(listOf(0), true)
        q.start()
        q.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)
        q.core.onNavStatus(1)
        assertEquals(listOf(1), q.link.navi())
        q.core.onCarlifeStart()          // 热 START:不动声音
        q.core.onNavStatus(2)
        assertEquals(listOf(1), q.link.navi())
    }

    @Test
    fun naviReleasedWhenTtsEndsAfterRouteEnded() {
        AaBridgeCore.TTS_DEBOUNCE_ENABLED = false
        try {
            val r = Rig()
            r.core.onCarlifeSubscribe(listOf(0), true)
            r.start()
            r.core.onNavStatus(1)
            r.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)
            r.core.onNavStatus(2)                                   // 「您已到达」仍在播放
            assertEquals(listOf(1), r.link.navi())
            r.core.onAudioStop(M.CH_AUDIO_SPEECH)
            assertEquals("TTS кончился после маршрута: NAVI=0, приборка гаснет", listOf(1, 0), r.link.navi())

            // 路线进行中:TTS 结束不影响 NAVI
            val q = Rig()
            q.core.onCarlifeSubscribe(listOf(0), true)
            q.start()
            q.core.onNavStatus(1)
            q.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)
            q.core.onAudioStop(M.CH_AUDIO_SPEECH)
            assertEquals(listOf(1), q.link.navi())
            q.core.onNavStatus(2)                                   // 路线中间的叉号,TTS 已沉默
            assertEquals("отмена маршрута: NAVI=0", listOf(1, 0), q.link.navi())
        } finally {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = true
        }
    }

    @Test
    fun replayAfterNewCarlifeSession() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 10))!!)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A"))!!)
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        r.core.onCarlifeClosed()
        val link2 = MetaLink()
        r.core.attach(link2)
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.core.onCarlifeStart()
        assertEquals("A", link2.infos.single().song)
        assertEquals(listOf(1), link2.navi())
        assertEquals(1, link2.turns.single().action)
        assertEquals(300, link2.turns.single().remainDistance)
    }

    @Test
    fun routeNaviOffWhenFlagDisabled() {
        AaBridgeCore.ROUTE_NAVI_STATUS_ENABLED = false
        assertRouteWithoutNavi(Rig())
    }

    /** 不带 NAVI=1 的路线:机动动作照常,NAVI=1 只在 TTS 周围保持。 */
    private fun assertRouteWithoutNavi(r: Rig) {
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 30))!!)
        assertTrue("NAVI=1 на маршрут не шлём", r.link.navi().isEmpty())
        assertEquals("а манёвры идут", 1, r.link.turns.size)
        r.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)
        assertEquals("NAVI=1 вокруг TTS остаётся", listOf(1), r.link.navi())
    }

    @Test
    fun routeNaviOnForUsualHu() {
        val r = Rig()
        r.core.onHuInfo("", "")                  // Honda Vezel:空的 manufacturer / model
        r.core.onHuStatistic("20832125")
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        assertEquals("старт маршрута - NAVI=1", listOf(1), r.link.navi())
        r.core.onNavStatus(2)
        assertEquals("конец маршрута - NAVI=0", listOf(1, 0), r.link.navi())
    }

    @Test
    fun routeNaviOnForOtherMazda() {
        val r = Rig()
        r.core.onHuInfo("alps", "MAZDA")
        r.core.onHuStatistic("21520001")
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        assertEquals(listOf(1), r.link.navi())
    }

    @Test
    fun routeNaviOffForDesayByManufacturer() {
        val r = Rig()
        r.core.onHuInfo("Desay SV Automotive", "G6S")
        assertTrue(r.core.huDesayG6S)
        assertRouteWithoutNavi(r)
    }

    @Test
    fun vwIdFlagByManufacturerAndChannelAndReset() {
        val r = Rig()
        assertTrue(!r.core.huVw20082138)
        r.core.onHuInfo("VW BT 1538", "VW BT 1538")
        assertTrue("одного имени мало", !r.core.huVw20082138)
        r.core.onHuStatistic("20082138")
        assertTrue(r.core.huVw20082138)
        r.core.onCarlifeClosed()
        r.core.attach(MetaLink())
        assertTrue("новая сессия - флаг заново", !r.core.huVw20082138)
        r.core.onHuStatistic("20082138")                       // 顺序任意:通道先于名称
        assertTrue("одного канала мало", !r.core.huVw20082138)
        r.core.onHuInfo("VW BT 1538", "VW BT 1538")
        assertTrue(r.core.huVw20082138)
    }

    @Test
    fun pioneerRm56FlagByManufacturerAndModelAndReset() {
        val r = Rig()
        assertTrue(!r.core.huPioneerRm56)
        r.core.onHuInfo("Pioneer", "AVH-Z9200")                 // 其他 Pioneer 型号不纳入该绕行
        assertTrue(!r.core.huPioneerRm56)
        r.core.onHuInfo("Other", "RM56E8B")
        assertTrue(!r.core.huPioneerRm56)
        r.core.onHuInfo("Pioneer", "RM56E8B", null)
        assertTrue(r.core.huPioneerRm56)
        r.core.onCarlifeClosed()
        r.core.attach(MetaLink())
        assertTrue("новая сессия - флаг заново", !r.core.huPioneerRm56)
        r.core.onHuInfo("Honda", "CR-V")
        assertTrue(!r.core.huPioneerRm56)
    }

    @Test
    fun vwIdFlagNotSetForVwBt1420OnSameChannel() {
        val r = Rig()
        r.core.onHuInfo("VW BT 1420", "VW BT 1420")             // Pavel:同一固件通道,不同的 BT 名称
        r.core.onHuStatistic("20082138")
        assertTrue(!r.core.huVw20082138)
        assertTrue(r.core.huVwBt1420)
        r.core.onCarlifeClosed()
        r.core.attach(MetaLink())
        assertTrue("новая сессия - флаг заново", !r.core.huVwBt1420)
        r.core.onHuInfo("VW BT 1538", "VW BT 1538")
        assertTrue(!r.core.huVwBt1420)
    }

    @Test
    fun vwIdFlagNotSetForOtherVw() {
        val r = Rig()
        r.core.onHuInfo("", "")
        r.core.onHuStatistic("20082120")
        assertTrue(r.core.huVw20082120)
        assertTrue(!r.core.huVw20082138)
    }

    @Test
    fun pauseHeartbeatForVwCns3Channel20082122() {
        val r = Rig()
        r.core.onHuInfo("Volkswagen", "CNS3")
        r.core.onHuStatistic("20082122")
        assertTrue(r.core.huVw20082120)
        assertTrue(!r.core.huVw20082138)
        r.core.onCarlifeClosed()
        r.core.attach(MetaLink())
        assertTrue("новая сессия - флаг заново", !r.core.huVw20082120)
    }

    @Test
    fun pauseHeartbeatForVwCns2DelphiChannel20082114() {
        val r = Rig()
        r.core.onHuInfo("VW", "")
        r.core.onHuStatistic("20082114")                  // h425:Tharu 2020 CNS2,EIO 在 6-8 秒后 PAUSE
        assertTrue(r.core.huVw20082120)
    }

    @Test
    fun routeNaviOffForDesayByChannel() {
        val r = Rig()
        r.core.onHuInfo("", "")
        r.core.onHuStatistic("20622116")
        assertTrue(r.core.huDesayG6S)
        assertRouteWithoutNavi(r)
    }

    @Test
    fun desayFlagResetsOnNewSession() {
        val r = Rig()
        r.core.onHuInfo("Desay SV Automotive", "G6S")
        r.core.onCarlifeClosed()
        val link2 = MetaLink()
        r.core.attach(link2)
        assertTrue("новая сессия - флаг заново по HU_INFO", !r.core.huDesayG6S)
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.core.onCarlifeStart()
        r.core.onNavStatus(1)
        assertEquals(listOf(1), link2.navi())
    }

    @Test
    fun hyundaiCn7FlagByProduct() {
        val r = Rig()
        r.core.onHuInfo("QTI", "sm6150_au for arm64")
        assertFalse("без product флага нет", r.core.huHyundaiCn7)
        r.core.onHuInfo("QTI", "sm6150_au for arm64", "other_product")
        assertFalse("чужой product - нет", r.core.huHyundaiCn7)
        r.core.onHuInfo("QTI", "sm6150_au for arm64", "wp_cn7cpe_cn")
        assertTrue(r.core.huHyundaiCn7)
    }

    @Test
    fun hyundaiCn7FlagResetsOnNewSession() {
        val r = Rig()
        r.core.onHuInfo("QTI", "sm6150_au for arm64", "wp_cn7cpe_cn")
        r.core.onCarlifeClosed()
        r.core.attach(MetaLink())
        assertFalse("новая сессия - флаг заново по HU_INFO", r.core.huHyundaiCn7)
    }

    /** 记住 INIT 和 MEDIA 数据的链接。 */
    private class CapLink(private val d: MetaLink = MetaLink()) : CarlifeLink by d {
        val inits = ArrayList<ByteArray>()
        val data = java.io.ByteArrayOutputStream()
        val dataBytes get() = data.size()
        override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
            if (type == CarlifeAudio.MEDIA_INIT) inits.add(payload)
            if (type == CarlifeAudio.MEDIA_DATA) data.write(payload)
            return d.sendAudio(channel, type, payload)
        }
    }

    private fun pattern() = ByteArray(8192) { (it * 7 + it / 5).toByte() }

    private fun mediaOut(cn7: Boolean, rate: MusicRate = MusicRate.AUTO, logs: MutableList<String>? = null): CapLink {
        val link = CapLink()
        val core = AaBridgeCore({ l, _ -> logs?.add(l) }, clockMs = { 1_000_000L })
        core.musicRateProvider = { rate }
        core.attach(link)
        if (cn7) core.onHuInfo("QTI", "sm6150_au for arm64", "wp_cn7cpe_cn")
        core.onCarlifeStart()
        repeat(3) { core.onAudioData(M.CH_AUDIO_MEDIA, pattern(), 0) }
        return link
    }

    @Test
    fun hyundaiCn7MediaIs32kHz() {
        val cn7 = mediaOut(true)
        assertEquals(1, cn7.inits.size)
        assertArrayEquals(CarlifeAudio.initBody(32000, 2, 16), cn7.inits[0])
        assertTrue("3 x 2048 кадра -> 4096 кадров", cn7.dataBytes == 4096 * 4)
    }

    @Test
    fun autoRateOnUsualHuIs48kHzUntouched() {
        val usual = mediaOut(false)
        assertArrayEquals(CarlifeAudio.initBody(48000, 2, 16), usual.inits[0])
        assertEquals(3 * 8192, usual.dataBytes)
        assertArrayEquals(pattern() + pattern() + pattern(), usual.data.toByteArray())
    }

    @Test
    fun explicit44kHzOnUsualHu() {
        val logs = ArrayList<String>()
        val link = mediaOut(false, MusicRate.HZ44, logs)
        assertEquals(1, link.inits.size)
        assertArrayEquals(CarlifeAudio.initBody(44100, 2, 16), link.inits[0])
        val frames = link.dataBytes / 4
        assertTrue("6144 кадра x 147/160 = 5644,8, получили $frames", abs(frames - 5645) <= 1)
        val rateLogs = logs.filter { it.startsWith("BRIDGE музыка") }
        assertEquals(listOf("BRIDGE музыка 44100 Гц, ресэмпл 48000→44100 (настройка)"), rateLogs)
    }

    @Test
    fun explicit48kHzOnCn7OverridesAuto() {
        val logs = ArrayList<String>()
        val link = mediaOut(true, MusicRate.HZ48, logs)
        assertArrayEquals(CarlifeAudio.initBody(48000, 2, 16), link.inits[0])
        assertArrayEquals(pattern() + pattern() + pattern(), link.data.toByteArray())
        assertTrue(logs.none { it.startsWith("BRIDGE музыка") })
    }

    @Test
    fun explicitRateOnCn7BeatsAuto() {
        val link = mediaOut(true, MusicRate.HZ24)
        assertArrayEquals(CarlifeAudio.initBody(24000, 2, 16), link.inits[0])
        assertEquals(3 * 2048 / 2 * 4, link.dataBytes)
    }

    @Test
    fun autoRateLogNamesCn7() {
        val logs = ArrayList<String>()
        mediaOut(true, MusicRate.AUTO, logs)
        assertEquals(listOf("BRIDGE музыка 32000 Гц, ресэмпл 48000→32000 (авто: Hyundai CN7)"), logs.filter { it.startsWith("BRIDGE музыка") })
    }

    @Test
    fun musicRateParse() {
        assertEquals(MusicRate.AUTO, MusicRate.DEFAULT)
        assertEquals(MusicRate.AUTO, MusicRate.parse(null))
        assertEquals(MusicRate.AUTO, MusicRate.parse("мусор"))
        for (r in MusicRate.values()) assertEquals(r, MusicRate.parse(r.name))
        assertEquals(listOf(null, 48000, 44100, 32000, 24000, 22050), MusicRate.values().map { it.hz })
    }

    @Test
    fun mediaInfoWaitsForPlaying() {
        val r = Rig()
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 5))!!)
        r.core.onMediaMetadata(AaMeta.parseMediaMetadata(metadata("A"))!!)
        assertTrue("на паузе MEDIA_INFO не шлём", r.link.infos.isEmpty())
        assertTrue("и прогресс тоже", r.link.progress.isEmpty())
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 6))!!)
        assertEquals("заиграл: отложенный трек уходит", "A", r.link.infos.single().song)
        assertEquals("прогресс ушёл", 1, r.link.progress.size)

        // AA 已死:曲目过期,重启后没有新 metadata 就不显示任何内容
        r.core.onAaEnded(true)
        r.now += 5000
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        assertEquals(1, r.link.infos.size)
    }

    @Test
    fun huMusicOffPausesAaWithAndWithoutPause() {
        assertEquals(127, AaBridgeCore.KEYCODE_MEDIA_PAUSE)
        // Desay G6S:先 PAUSE,再 MUSIC=0
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onCarlifePause()
        r.core.onCarlifeMusicControl(1)
        assertTrue("MUSIC=1 не пауза", peer.keys.isEmpty())
        r.core.onCarlifeMusicControl(0)
        assertEquals(listOf(AaBridgeCore.KEYCODE_MEDIA_PAUSE), peer.keys.toList())

        // 其他车机:MUSIC=0 不带 PAUSE
        val q = Rig()
        val qPeer = FakePeer()
        q.core.peer = qPeer
        q.start()
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        q.core.onCarlifeMusicControl(0)
        assertEquals(listOf(AaBridgeCore.KEYCODE_MEDIA_PAUSE), qPeer.keys.toList())
    }

    @Test
    fun huLeavesForOwnSourceNoAutoPlayOnStartAndNoSecondPause() {
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onCarlifePause()
        r.core.onCarlifeMusicControl(0)
        r.core.onCarlifeMusicControl(0)
        assertEquals("повторный MUSIC=0 не шлёт второй 127", listOf(127), peer.keys.toList())

        r.start()
        assertEquals("START не запускает музыку (126 нет)", listOf(127), peer.keys.toList())
        r.core.onCarlifePause()
        r.start()
        assertEquals(listOf(127), peer.keys.toList())
    }

    /** Desay G6S,AA 播放中,车机切到收音机(PAUSE 之后 MUSIC=0)。 */
    private fun g6sPausedByUs(): Pair<Rig, FakePeer> {
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.core.onHuInfo("Desay SV Automotive", "G6S")
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onCarlifePause()
        assertFalse(r.core.onCarlifeMusicControl(0))
        assertEquals(listOf(127), peer.keys.toList())
        return r to peer
    }

    @Test
    fun g6sMusicOnAfterOurPauseResumesAaOnceForWholeBurst() {
        // h214:START,然后一串 MUSIC=1 - play 一次,MUSIC=1 覆盖整串
        val (r, peer) = g6sPausedByUs()
        r.now += 950
        r.start()
        assertEquals("START сам музыку не запускает", listOf(127), peer.keys.toList())
        r.now += 300
        assertTrue(r.core.onCarlifeMusicControl(1))
        assertEquals(listOf(127, 126), peer.keys.toList())
        for (i in 0 until 10) {
            r.now += 50
            assertTrue("пачка: MUSIC=1, пока AA раскачивается", r.core.onCarlifeMusicControl(1))
        }
        assertEquals("126 один раз", listOf(127, 126), peer.keys.toList())
        assertTrue(r.logs.any { it.contains("вернулось на CarLife-музыку") })
        // MEDIA 再次转发
        val pcm = ByteArray(640)
        r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        assertEquals(1, r.link.audio.count { it == CarlifeAudio.MEDIA_DATA })
        // 窗口过后,应答按实际情况恢复
        r.now += AaBridgeCore.HU_RESUME_ANSWER_MS
        r.core.onCarlifeMusicControl(1)
        assertEquals(listOf(127, 126), peer.keys.toList())

        // MUSIC=1 早于 START(日志 2026-10-13):同样 play
        val (q, qPeer) = g6sPausedByUs()
        q.now += 5000
        assertTrue(q.core.onCarlifeMusicControl(1))
        q.start()
        assertEquals(listOf(127, 126), qPeer.keys.toList())
    }

    @Test
    fun g6sMusicOnWithoutOurPauseDoesNotPlay() {
        // 新会话:HU_INFO 后约 2 秒一串 MUSIC=1,没有我们的暂停(h33:不静音收音机)
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.core.onHuInfo("Desay SV Automotive", "G6S")
        r.start()
        repeat(10) { assertFalse(r.core.onCarlifeMusicControl(1)) }
        assertTrue(peer.keys.isEmpty())

        // 用户在切到车机前自行暂停了 AA:MUSIC=1 不启动曲目
        val q = Rig()
        val qPeer = FakePeer()
        q.core.peer = qPeer
        q.core.onHuInfo("Desay SV Automotive", "G6S")
        q.start()
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 1))!!)
        q.core.onCarlifeMusicControl(0)
        q.start()
        assertFalse(q.core.onCarlifeMusicControl(1))
        assertTrue(qPeer.keys.isEmpty())

        // 无 MUSIC=1 的返回(NAVI):只有 START - 没有 play
        val (n, nPeer) = g6sPausedByUs()
        n.now += 7000
        n.start()
        n.core.onCarlifePause()
        n.start()
        assertEquals(listOf(127), nPeer.keys.toList())

        // 非 Desay G6S:我们暂停后 MUSIC=1 不发送 play
        val o = Rig()
        val oPeer = FakePeer()
        o.core.peer = oPeer
        o.start()
        o.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        o.core.onCarlifeMusicControl(0)
        o.core.onCarlifeMusicControl(1)
        assertEquals(listOf(127), oPeer.keys.toList())

        // 标志关闭 - 旧行为
        AaBridgeCore.HU_MUSIC_RESUME_ENABLED = false
        try {
            val (f, fPeer) = g6sPausedByUs()
            assertFalse(f.core.onCarlifeMusicControl(1))
            assertEquals(listOf(127), fPeer.keys.toList())
        } finally {
            AaBridgeCore.HU_MUSIC_RESUME_ENABLED = true
        }
    }

    @Test
    fun huMusicOffDropsAaBufferTailUntilAaPlaysAgain() {
        val pcm = ByteArray(640)
        fun media(r: Rig) = r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        fun Rig.mediaPackets(type: Int) = link.audio.count { it == type }

        // 根据 AA 媒体通道启动退出暂停
        val r = Rig()
        r.core.peer = FakePeer()
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        media(r)
        assertEquals(1, r.mediaPackets(CarlifeAudio.MEDIA_INIT))
        assertEquals(1, r.mediaPackets(CarlifeAudio.MEDIA_DATA))
        r.core.onCarlifeMusicControl(0)
        assertEquals(1, r.mediaPackets(CarlifeAudio.MEDIA_PAUSE))
        val sent = r.link.audio.size
        media(r)
        media(r)
        assertEquals("хвост буфера на ГУ не идёт: ни INIT, ни DATA", sent, r.link.audio.size)
        r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        media(r)
        assertEquals("после MEDIA START данные снова идут", 2, r.mediaPackets(CarlifeAudio.MEDIA_INIT))
        assertEquals(2, r.mediaPackets(CarlifeAudio.MEDIA_DATA))

        // 根据 paused 之后的 playing 状态退出暂停
        val q = Rig()
        q.core.peer = FakePeer()
        q.start()
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        media(q)
        q.core.onCarlifeMusicControl(0)
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 2))!!)
        val qSent = q.link.audio.size
        media(q)
        assertEquals("на паузе AA данные не идут", qSent, q.link.audio.size)
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 3))!!)
        media(q)
        assertEquals("заиграл: данные снова идут", 2, q.mediaPackets(CarlifeAudio.MEDIA_INIT))
        assertEquals(2, q.mediaPackets(CarlifeAudio.MEDIA_DATA))
    }

    /** AA 播放中,车机切到收音机(MUSIC=0):127 已发送,huPausedAa。返回 rig、peer 和 MEDIA 包计数。 */
    private inner class PausedRig {
        val r = Rig()
        val peer = FakePeer()
        val pcm = ByteArray(640)

        init {
            r.core.peer = peer
            r.start()
            r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
            r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
            r.core.onCarlifeMusicControl(0)
            r.now += 2000
        }

        fun media() = r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        fun mediaData() = r.link.audio.count { it == CarlifeAudio.MEDIA_DATA }
    }

    @Test
    fun btAutoplayAfterHfpKeepsHuPauseAndRepauses() {
        val p = PausedRig()
        val r = p.r
        assertEquals(listOf(127), p.peer.keys.toList())
        r.core.onHuBtHfpConnection(2)
        r.now += 6000
        r.core.onCarlifeTouch(1, 10, 10)          // UP 不是用户输入
        r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        assertEquals("автоплей после HFP: снова 127", listOf(127, 127), p.peer.keys.toList())
        assertTrue(r.logs.any { it.contains("автоплей, снова KEYCODE 127") })
        val before = p.mediaData()
        p.media()
        assertEquals("MEDIA не уходит", before, p.mediaData())

        // 不超过每秒一次
        r.now += 500
        r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        assertEquals(listOf(127, 127), p.peer.keys.toList())
        // 通过播放器状态自动播放:paused -> playing
        r.now += 600
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 2))!!)
        r.now += 1500
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 3))!!)
        assertEquals(listOf(127, 127, 127), p.peer.keys.toList())
        p.media()
        assertEquals("MEDIA всё ещё не уходит", before, p.mediaData())
    }

    @Test
    fun btAutoplayAfterHfpReleasedByUserInput() {
        for (input in listOf<(Rig) -> Unit>(
            { it.core.onCarlifeTouch(0, 10, 10) },
            { it.core.onCarlifeHardKey(0x06) },
            { it.core.onCarlifeVoiceKey() },
        )) {
            val p = PausedRig()
            val r = p.r
            r.core.onHuBtHfpConnection(2)
            r.now += 6000
            input(r)
            r.now += 1000
            r.core.onAudioStart(M.CH_AUDIO_MEDIA)
            val before = p.mediaData()
            p.media()
            assertEquals("play после ввода пользователя: данные идут", before + 1, p.mediaData())
            assertEquals("второго 127 нет", 1, p.peer.keys.count { it == 127 })
        }
    }

    @Test
    fun autoplayWithoutHfpReleasesPause() {
        val p = PausedRig()
        val before = p.mediaData()
        p.r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        p.media()
        assertEquals("без HFP play снимает паузу", before + 1, p.mediaData())
        assertEquals(listOf(127), p.peer.keys.toList())

        // HFP 断开(state=0)不打开窗口
        val q = PausedRig()
        q.r.core.onHuBtHfpConnection(0)
        q.r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        assertEquals(listOf(127), q.peer.keys.toList())
        val qBefore = q.mediaData()
        q.media()
        assertEquals(qBefore + 1, q.mediaData())
    }

    @Test
    fun autoplayAfterHfpWindowReleasesPause() {
        val p = PausedRig()
        p.r.core.onHuBtHfpConnection(2)
        p.r.now += AaBridgeCore.BT_AUTOPLAY_WINDOW_MS + 1
        p.r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 2))!!)
        p.r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 3))!!)
        assertEquals("окно HFP истекло: 127 нет", listOf(127), p.peer.keys.toList())
        val before = p.mediaData()
        p.media()
        assertEquals(before + 1, p.mediaData())
    }

    @Test
    fun btAutoplayGuardCanBeDisabled() {
        AaBridgeCore.BT_AUTOPLAY_GUARD_ENABLED = false
        try {
            val p = PausedRig()
            p.r.core.onHuBtHfpConnection(2)
            p.r.core.onAudioStart(M.CH_AUDIO_MEDIA)
            assertEquals(listOf(127), p.peer.keys.toList())
        } finally {
            AaBridgeCore.BT_AUTOPLAY_GUARD_ENABLED = true
        }
    }

    @Test
    fun playerStopLogsDiagnostics() {
        val p = PausedRig()
        p.r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 2))!!)
        val line = p.r.logs.single { it.contains("AA плеер остановился") }
        assertTrue(line, line.contains("KEYCODE 127 2000 мс назад") && line.contains("ввод пользователя не было") && line.contains("BT HFP не было"))
    }

    @Test
    fun huMusicOffIgnoredWhenAaNotPlaying() {
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 1))!!)
        r.core.onCarlifeMusicControl(0)
        r.core.onCarlifePause()
        r.core.onCarlifeMusicControl(0)
        r.start()
        assertTrue(peer.keys.isEmpty())
    }

    @Test
    fun huMusicOffDropsAaBufferTailEvenWhenAaAlreadyPaused() {
        val pcm = ByteArray(640)
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 1))!!)       // AA 暂停中(助手 / 用户)
        assertFalse("MUSIC=0: ответ MUSIC=0", r.core.onCarlifeMusicControl(0))
        assertTrue("AA не играет: 127 не шлём", peer.keys.isEmpty())
        assertTrue(r.logs.any { it.contains("AA не играет: хвост MEDIA не пересылаю до play") })

        r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        assertEquals("хвост буфера на ГУ не идёт: ни MEDIA INIT, ни DATA", 0, r.link.audio.count { it == CarlifeAudio.MEDIA_INIT || it == CarlifeAudio.MEDIA_DATA })

        r.core.onAudioStart(M.CH_AUDIO_MEDIA)
        r.core.onAudioData(M.CH_AUDIO_MEDIA, pcm, 0)
        assertEquals("после MEDIA START данные идут", 1, r.link.audio.count { it == CarlifeAudio.MEDIA_INIT })
        assertEquals(1, r.link.audio.count { it == CarlifeAudio.MEDIA_DATA })
        assertTrue("второго 127 нет", peer.keys.isEmpty())
    }

    @Test
    fun huMusicOffAnswersZeroWhileTtsDefersMediaPause() {
        val r = Rig()
        val peer = FakePeer()
        r.core.peer = peer
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onAudioData(M.CH_AUDIO_MEDIA, ByteArray(640), 0)
        r.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)            // TTS 已初始化
        assertTrue("MEDIA играет: MUSIC активна", r.core.musicActive)
        assertFalse("MUSIC=0: ответ MUSIC=0, хотя пауза MEDIA отложена", r.core.onCarlifeMusicControl(0))
        assertFalse("пауза MEDIA ждёт конца TTS", r.link.audio.contains(CarlifeAudio.MEDIA_PAUSE))
        assertTrue(r.logs.any { it.contains("MEDIA пауза отложена") })
        assertEquals("AA на паузе: 127 ушёл", listOf(AaBridgeCore.KEYCODE_MEDIA_PAUSE), peer.keys.toList())

        // TTS 期间 MEDIA 尾部不发送到车机
        val before = r.link.audio.count { it == CarlifeAudio.MEDIA_DATA }
        r.core.onAudioData(M.CH_AUDIO_MEDIA, ByteArray(640), 0)
        assertEquals(before, r.link.audio.count { it == CarlifeAudio.MEDIA_DATA })
    }

    @Test
    fun huMusicOffAnswersZeroWhenMediaPlayingOverUsb() {
        val r = Rig()
        r.core.peer = FakePeer()
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        r.core.onAudioData(M.CH_AUDIO_MEDIA, ByteArray(640), 0)
        assertTrue("MEDIA играет: MUSIC активна", r.core.musicActive)
        assertFalse("MUSIC=0: MEDIA на паузе, ответ MUSIC=0", r.core.onCarlifeMusicControl(0))
        assertTrue(r.link.audio.contains(CarlifeAudio.MEDIA_PAUSE))
    }

    @Test
    fun musicModuleByStateOnlyWithoutUsbAudio() {
        val r = Rig()
        r.start()
        r.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        assertTrue("звук по USB: MUSIC владеет MEDIA INIT", r.link.modules.none { it.first == CarlifeAudio.MODULE_MUSIC })
        assertFalse(r.core.musicActive)

        val q = Rig()
        q.core.usbAudioEnabled = false
        q.start()
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(2, 1))!!)
        assertTrue(q.core.musicActive)
        q.core.onMediaStatus(AaMeta.parseMediaStatus(status(3, 2))!!)
        assertFalse(q.core.musicActive)
        assertEquals(
            listOf(CarlifeAudio.MODULE_MUSIC to 1, CarlifeAudio.MODULE_MUSIC to 0),
            q.link.modules.filter { it.first == CarlifeAudio.MODULE_MUSIC },
        )
    }

    // --- CarLife 构建器 ---
    @Test
    fun mediaInfoWritesAllRequiredFields() {
        val body = Msgs.mediaInfo(CarlifeMediaInfo("src", "", "", "", null, 0), ByteArray(0))
        val f = PbReader.parse(body)!!
        assertEquals((1..9).toList(), f.map { it.number })
        assertEquals(listOf(2, 2, 2, 2, 2, 0, 0, 2, 0), f.map { it.wire })
        assertEquals("-1", PbReader.str(f, 8))
        val full = Msgs.mediaInfo(CarlifeMediaInfo("s#1", "Песня", "Артист", "Альбом", null, 215), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))
        val g = PbReader.parse(full)!!
        assertEquals("Песня", PbReader.str(g, 2))
        assertEquals(215, PbReader.int(g, 6))
        assertEquals(3, g.first { it.number == 5 }.data!!.size)
        val text = PbText.render(full, Schemas.byType[Msg.MEDIA_INFO])
        assertTrue(text, text.contains("albumArt=<3 Б JPEG>") && text.contains("duration=215"))
        assertArrayEquals(byteArrayOf(0x08, 0xE8.toByte(), 0x07), Msgs.mediaProgress(1000))
    }

    @Test
    fun naviNextTurnField6Modes() {
        val t = NaviTurnInfo(1, 3, "Main", 300, 250, 25)
        val icon = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        fun f6(mode: NaviField6) = PbReader.parse(Msgs.naviNextTurn(t, mode, icon))!!.filter { it.number == 6 }.map { it.wire }
        assertEquals(listOf(0, 2), f6(NaviField6.BOTH))
        assertEquals(listOf(2), f6(NaviField6.ICON))
        assertEquals(listOf(0), f6(NaviField6.TIME))
        assertEquals(emptyList<Int>(), f6(NaviField6.NONE))
        val f = PbReader.parse(Msgs.naviNextTurn(t, NaviField6.TIME, icon))!!
        assertEquals(listOf(1, 2, 3, 4, 5, 6), f.map { it.number })
        assertEquals(listOf(1, 3, 300, 250, 25), listOf(1, 2, 4, 5, 6).map { PbReader.int(f, it) })
        assertEquals("Main", PbReader.str(f, 3))
        assertEquals(NaviField6.BOTH, NaviField6.of(icon = true, time = true))
        assertEquals(NaviField6.NONE, NaviField6.of(icon = false, time = false))
        val text = PbText.render(Msgs.naviNextTurn(t, NaviField6.BOTH, icon), Schemas.byType[Msg.NAVI_NEXT_TURN_INFO])
        assertTrue(text, text.contains("time|turnIconData=25") && text.contains("time|turnIconData=<4 Б PNG>"))
        assertEquals("MEDIA_INFO", Msg.name(Msg.MEDIA_INFO))
        assertEquals("NAVI_NEXT_TURN_INFO", Msg.name(Msg.NAVI_NEXT_TURN_INFO))
    }

    // --- Honda:SUBSCRIBE_START 后的箭头,NAVI 0->1 重新激活 ---


    // --- Honda(仪表盘/HUD):SHOW 前 NAVI 0->1 重新激活,TTS 后释放 NAVI ---
    private fun hondaRoute(honda: Boolean = true): Rig {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        if (honda) r.core.onHuStatistic("20832117")
        r.link.huResubscribes = honda                // 本田车机对每个 NAVI=1 回复 SUBSCRIBE_START
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 18))!!)
        r.pumpSubscribe()
        return r
    }


    @Test
    fun hondaPeriodicAndManeuverShowDoNotRearm() {
        val r = hondaRoute()
        assertEquals(listOf(1), r.link.navi())
        var m = 2000
        repeat(25) {                                     // 行驶中:每 10 秒 SHOW
            r.now += 1000
            m -= 10
            r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(m, 200))!!)
        }
        r.now += 1000
        r.core.onNavTurn(AaMeta.parseNavState(navState(7, "Second Ave"))!!)   // 新机动动作 - SHOW
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(300, 40))!!)
        assertTrue("периодические SHOW были", r.link.turns.count { it.action == 1 } >= 4)
        assertEquals(1, r.link.turns.last().action)
        assertEquals("ни периодический SHOW, ни SHOW манёвра не перевзводят NAVI", listOf(1), r.link.navi())
        assertEquals(0, r.link.pendingSubscribe)
    }


    @Test
    fun hondaEmptyRoadGetsManeuverText() {
        // Google Maps 在驶出院子时发送 road="" 的 legacy 0x8004:Honda - 机动动作标题,其余按 AA 原样
        for (honda in listOf(true, false)) for (road in listOf("", "  ", "ул. Мадояна")) {
            val r = Rig()
            r.link.huResubscribes = honda
            r.core.onCarlifeSubscribe(listOf(0), true)
            r.start()
            if (honda) r.core.onHuStatistic("20832117")
            r.core.onNavStatus(1)
            r.pumpSubscribe()
            r.core.onNavTurn(AaMeta.parseLegacyTurn(legacyTurn(4, 2, road))!!)
            r.core.onNavDistance(AaMeta.parseLegacyDistance(legacyDistance(50, 10))!!)
            r.pumpSubscribe()
            with(r.link.turns.last()) {
                if (honda && road.isBlank()) assertEquals("Honda, пустая улица «$road»", AaMeta.noRoadName(nextTurn), roadName)
                else assertEquals("honda=$honda: улица как прислал AA", road, roadName)
            }
        }
        assertEquals("Направо", AaMeta.noRoadName(AaMeta.T_RIGHT))
        assertEquals("Налево", AaMeta.noRoadName(AaMeta.T_LEFT_FRONT))
        assertEquals("Правее", AaMeta.noRoadName(AaMeta.T_BRANCH_RIGHT))
        for (code in 0..40) assertTrue("код $code: не длиннее 16 Б", AaMeta.noRoadName(code).toByteArray().size <= 16)
    }

    @Test
    fun hondaShowWaitsForSubscribe() {
        // 17.10 16:18:02:NAVI=1 和机动动作一包到达 - SHOW 早于 SUBSCRIBE_START 发出
        val r = Rig()
        r.link.huResubscribes = true
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onHuStatistic("20832117")
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(540, 123))!!)
        r.now += 500
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(530, 120))!!)
        assertTrue("до подписки ГУ стрелки копятся", r.link.turns.isEmpty())
        assertEquals(1, r.pumpSubscribe())
        assertEquals("после подписки - один SHOW с последней дистанцией", listOf(1), r.link.turns.map { it.action })
        assertEquals(530, r.link.turns.last().remainDistance)
        assertEquals("без перевзвода", listOf(1), r.link.navi())
        assertEquals(0, r.link.pendingSubscribe)
    }


    @Test
    fun hondaNoSubscribeSendsAfterTimeout() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onHuStatistic("20832117")
        r.core.onNavStatus(1)                                   // 车机不回复订阅
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(540, 123))!!)
        assertTrue(r.link.turns.isEmpty())
        r.now += MetaRelay.SUB_WAIT_MS
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(530, 120))!!)
        assertEquals("по таймауту SHOW всё равно уходит", listOf(1), r.link.turns.map { it.action })
        assertEquals("и без перевзвода", listOf(1), r.link.navi())
    }


    @Test
    fun otherHuDoesNotWaitForSubscribe() {
        val r = Rig()
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onNavStatus(1)
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(540, 123))!!)
        assertEquals("не Honda: SHOW сразу", listOf(1), r.link.turns.map { it.action })
    }


    @Test
    fun hondaReshowRearmThenSubscribeShowsOnceNoLoop() {
        val r = hondaRoute()
        val before = r.link.turns.size
        r.now += MetaRelay.NAVI_RESHOW_MS + 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 17))!!)
        assertEquals("перевзвод перед повторным показом", listOf(1, 0, 1), r.link.navi())
        assertEquals("SHOW придержан до подписки", before, r.link.turns.size)
        assertEquals("ГУ переподписалась один раз", 1, r.pumpSubscribe())
        assertEquals("на подписку SHOW без нового перевзвода", listOf(1, 0, 1), r.link.navi())
        assertEquals(1, r.link.turns.last().action)
        assertEquals(0, r.link.pendingSubscribe)
    }


    @Test
    fun hondaSlowFirstTurnAfterRouteStartNoRearm() {
        // 16:15:33 NAVI=1(路线),车机订阅在 0.7 秒后,AA 的第一个 TURN_EVENT 在 2.1 秒后
        val r = Rig()
        r.link.huResubscribes = true
        r.core.onCarlifeSubscribe(listOf(0), true)
        r.start()
        r.core.onHuStatistic("20832117")
        r.core.onNavStatus(1)
        assertEquals(listOf(1), r.link.navi())
        r.now += 700
        assertEquals(1, r.pumpSubscribe())
        r.now += 1800
        r.core.onNavTurn(AaMeta.parseNavState(navState(8, "Main St"))!!)
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 18))!!)
        assertEquals(1, r.link.turns.last().action)
        assertEquals("NAVI=1 свежий, стрелок ещё не было: не перевзводим", listOf(1), r.link.navi())
    }


    @Test
    fun hondaReshowAfterSilenceRearmsNavi() {
        val r = hondaRoute()
        assertEquals(1, r.link.turns.last().action)
        assertEquals("NAVI=1 только что поднят маршрутом: перед первым SHOW не перевзводим", listOf(1), r.link.navi())
        r.now += MetaRelay.NAVI_RESHOW_MS + 1000          // AA 沉默了
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 17))!!)
        r.pumpSubscribe()
        assertEquals(1, r.link.turns.last().action)
        assertEquals("перед SHOW после тишины - NAVI 0->1", listOf(1, 0, 1), r.link.navi())
    }


    @Test
    fun otherHuReshowWithoutNaviRearm() {
        val r = hondaRoute(honda = false)
        r.now += MetaRelay.NAVI_RESHOW_MS + 1000
        r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(30, 17))!!)
        assertEquals(1, r.link.turns.last().action)
        assertEquals("не Honda: MODULE_STATUS как раньше", listOf(1), r.link.navi())
    }


    @Test
    fun hondaRerouteWhileTtsHeldRearmsNavi() {
        val was = AaBridgeCore.TTS_DEBOUNCE_ENABLED
        AaBridgeCore.TTS_DEBOUNCE_ENABLED = false
        try {
            val r = hondaRoute()
            r.core.onAudioData(M.CH_AUDIO_SPEECH, ByteArray(640), 0)   // 重新规划路线时 TTS 正在播放
            r.core.onNavStatus(2)
            assertEquals("NAVI держит TTS", listOf(1), r.link.navi())
            r.now += 6000
            r.core.onNavStatus(1)
            r.core.onNavTurn(AaMeta.parseNavState(navState(7, "Second Ave"))!!)
            r.core.onNavDistance(AaMeta.parseNavPosition(navPosition(190, 37))!!)
            r.pumpSubscribe()
            assertEquals(1, r.link.turns.last().action)
            assertEquals("перестроение: перед SHOW переход NAVI 0->1", listOf(1, 0, 1), r.link.navi())
        } finally {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = was
        }
    }
}
