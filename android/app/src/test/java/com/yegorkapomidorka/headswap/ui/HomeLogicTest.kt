package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private fun inp(
        running: Boolean = false,
        status: String = "не запущено",
        protocol: Boolean = false,
        serverUp: Boolean = false,
        ended: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: UiText? = null,
        wireless: Boolean = false,
        refusedMs: Long = 0,
        hangs: Int = 0,
        worked: Boolean = false,
    ) = Inputs(
        running, status, protocol, serverUp, ended, graceLeft, 8, usbAudio, setupError, wireless = wireless,
        refusedForMs = refusedMs, versionHangs = hangs, serverWorked = worked,
    )

    @Test
    fun errorWhenCertificateMissing() {
        val err = UiText.res(R.string.home_err_no_cert)
        val s = HomeLogic.compute(inp(setupError = err))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(err, s.sub)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverUp = true)).phase)
    }

    @Test
    fun offWhenNothingStarted() {
        val s = HomeLogic.compute(inp())
        assertEquals(Phase.OFF, s.phase)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(StageState.TODO, s.stages[0].second.first)
        // 用户需自行启动车机端服务器：琥珀色色调（非 warn）、号召式标题与三步提示区块
        assertEquals(Tone.PRI, s.tone)
        assertEquals(UiText.res(R.string.home_title_off), s.title)
        assertEquals(UiText.res(R.string.home_sub_start_chain), s.sub)
        assertEquals(true, s.showChain)
    }

    @Test
    fun waitingWhenServerStarted() {
        val s = HomeLogic.compute(inp(serverUp = true))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(OutlineAction.OPEN_AA, s.outline)
    }

    @Test
    fun connectedFromBridgeStatus() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)"))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(OutlineAction.STOP, s.outline)
        assertEquals(UiText.raw("USB"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_usb), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
        assertEquals(HeroIcon.CAR_USB, s.icon)
    }

    @Test
    fun connectedOverWifiShowsWifi() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)", wireless = true))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(HeroIcon.CAR_WIFI, s.icon)
        assertEquals(UiText.res(R.string.home_sub_ok_wifi), s.sub)
        assertEquals(UiText.raw("CarLife 2.0 · Wi-Fi"), s.stages[1].second.second)
        assertEquals(UiText.raw("Wi-Fi"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_wifi), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false, wireless = true))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
    }

    @Test
    fun reconnectingShowsCountdown() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", graceLeft = 5))
        assertEquals(Phase.RECONNECTING, s.phase)
        assertEquals(5, s.graceLeftS)
        assertEquals(UiText.res(R.string.notif_reconnecting, 5), s.notifText)
        assertEquals(StageState.PAUSE, s.stages[4].second.first)
    }

    @Test
    fun parkedAfterSessionEnded() {
        // 桥接器按流程自行关闭了已建立的会话：「汽车已断开」，车机服务器在后台「运行中」，无「检查中」提示与按钮
        val s = HomeLogic.compute(inp(ended = true))
        assertEquals(Phase.PARKED, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.CAR, s.icon)
        assertEquals(UiText.res(R.string.home_title_parked), s.title)
        assertEquals(UiText.res(R.string.home_sub_parked), s.sub)
        assertNull(s.primary)
        assertNull(s.outline)
        assertEquals(false, s.logLink)
        assertEquals(StageState.DONE to UiText.res(R.string.home_stage_working), s.stages[0].second)
        assertEquals(StageState.PEND, s.stages[1].second.first)
        assertEquals(UiText.res(R.string.home_stage_hu_parked), s.stages[1].second.second)
        assertEquals(UiText.res(R.string.notif_parked), s.notifText)
        // 以往连接的拒绝与挂起不会改变 PARKED 状态：仅当桥接器存活时才判为 SERVER_DOWN
        val stale = HomeLogic.compute(inp(ended = true, refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.PARKED, stale.phase)
    }

    @Test
    fun serverDownWhenRefusedLongerThanFifteenSeconds() {
        val status = "мост: жду сервер ГУ AA"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(Tone.ERR, s.tone)
        assertEquals(HeroIcon.ALERT, s.icon)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.home_sub_server_down), s.sub)
        assertEquals(StageState.ERR to UiText.res(R.string.home_stage_no_answer), s.stages[0].second)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals(UiText.res(R.string.home_title_server_down), s.notifTitle)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // 短暂拒绝（ByeBye 后约 6 秒）属正常：显示普通「正在连接」，无红色提示，也无「启动车机服务器」
        val short = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS - 1, worked = true))
        assertEquals(Phase.WAITING, short.phase)
        assertEquals(false, short.showChain)
    }

    @Test
    fun serverDownWhenTwoAttemptsInARowHangWithoutVersionResponse() {
        val status = "мост: AA отключился"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = HomeLogic.HANGS_DOWN, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // 仅一次挂起尚不足以判定故障：仍显示普通「正在连接」
        val one = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = 1, worked = true))
        assertEquals(Phase.WAITING, one.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), one.title)
    }

    @Test
    fun serverDownNeedsEvidenceThatItWasUp() {
        // 新用户场景：服务器从未成功运行过，长时间拒绝与挂起不会触发红色错误——仍是原来的「启动车机服务器」
        val fresh = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", refusedMs = 60_000))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(true, fresh.showChain)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(running = true, status = "мост: AA отключился", hangs = 5)).phase)
    }

    @Test
    fun serverDownOnlyWhileBridgeAttemptsAndNotOnceConnected() {
        // 桥接器不存在（未插数据线）：以往的拒绝记录没有任何意义
        assertEquals(Phase.OFF, HomeLogic.compute(inp(refusedMs = 60_000, hangs = 3, worked = true)).phase)
        // 会话进行中：即使计数器尚未清零，也显示普通的「一切正常」
        val connected = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.CONNECTED, connected.phase)
        // USB 断开：宽限期保持会话，不视为故障
        val grace = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", refusedMs = 60_000, worked = true))
        assertEquals(Phase.RECONNECTING, grace.phase)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(true, s.showChain)
        assertEquals(StageState.DONE, s.stages[1].second.first)
    }

    @Test
    fun cableConnectingIsWaiting() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: старт"))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), s.title)
        assertNull(s.primary)
    }
}
