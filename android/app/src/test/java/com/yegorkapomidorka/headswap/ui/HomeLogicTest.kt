package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.ServerState
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
        server: ServerState = ServerState.UNKNOWN,
        seenAlive: Boolean = false,
    ) = Inputs(running, status, protocol, serverUp, ended, graceLeft, 8, usbAudio, setupError, wireless = wireless, server = server, serverSeenAlive = seenAlive)

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
        // сервер ГУ включает пользователь: янтарный тон (не warn), заголовок-призыв и блок из трёх пунктов
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
    fun parkedAfterSessionEndedWhileCheckIsPendingOrAlive() {
        // проба ещё не стартовала, идёт или ответила ALIVE: «Машина отключена», сервер ГУ в фоне, кнопок нет
        for (server in listOf(ServerState.UNKNOWN, ServerState.CHECKING, ServerState.ALIVE, ServerState.ERROR)) {
            val s = HomeLogic.compute(inp(ended = true, server = server))
            assertEquals(server.toString(), Phase.PARKED, s.phase)
            assertEquals(Tone.PRI, s.tone)
            assertEquals(HeroIcon.CAR, s.icon)
            assertEquals(UiText.res(R.string.home_title_parked), s.title)
            assertEquals(UiText.res(R.string.home_sub_parked), s.sub)
            assertNull(s.primary)
            assertNull(s.outline)
            assertEquals(false, s.logLink)
            assertEquals(StageState.PEND, s.stages[1].second.first)
            assertEquals(UiText.res(R.string.home_stage_hu_parked), s.stages[1].second.second)
            assertEquals(UiText.res(R.string.notif_parked), s.notifText)
        }
        val checking = HomeLogic.compute(inp(ended = true, server = ServerState.CHECKING))
        assertEquals(StageState.ACTIVE to UiText.res(R.string.home_stage_checking), checking.stages[0].second)
        val alive = HomeLogic.compute(inp(ended = true, server = ServerState.ALIVE))
        assertEquals(StageState.DONE to UiText.res(R.string.home_stage_working), alive.stages[0].second)
    }

    @Test
    fun serverDownWhenCheckFailedAfterSessionEnded() {
        val s = HomeLogic.compute(inp(ended = true, server = ServerState.DOWN, status = "мост: AA отключился"))
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
    }

    @Test
    fun serverDownNeedsEvidenceThatItWasUp() {
        // новичок: проба видит закрытый порт, но сервер он ещё не включал, поэтому прежнее «Запустите сервер ГУ»
        val fresh = HomeLogic.compute(inp(server = ServerState.DOWN))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(true, fresh.showChain)
        // включал сам (нажал «Открыть Android Auto») или проба раньше отвечала: «не отвечает»
        assertEquals(Phase.ERROR, HomeLogic.compute(inp(server = ServerState.DOWN, serverUp = true)).phase)
        assertEquals(Phase.ERROR, HomeLogic.compute(inp(server = ServerState.DOWN, seenAlive = true)).phase)
    }

    @Test
    fun aliveServerWithoutHistoryIsReadyForTrip() {
        val s = HomeLogic.compute(inp(server = ServerState.ALIVE))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_ready), s.title)
        // проба идёт или не удалась технически: без доказательств состояние прежнее
        assertEquals(Phase.OFF, HomeLogic.compute(inp(server = ServerState.CHECKING)).phase)
        assertEquals(Phase.OFF, HomeLogic.compute(inp(server = ServerState.ERROR)).phase)
    }

    @Test
    fun bridgeStatesIgnoreServerCheck() {
        // пока мост работает, результат пробы не показываем: сервер занят самим мостом
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", server = ServerState.DOWN, ended = true))
        assertEquals(Phase.CONNECTED, s.phase)
        val refused = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", server = ServerState.ALIVE))
        assertEquals(Phase.OFF, refused.phase)
        assertEquals(true, refused.showChain)
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
