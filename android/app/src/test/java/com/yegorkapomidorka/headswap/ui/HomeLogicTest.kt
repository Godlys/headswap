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
        lost: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: UiText? = null,
        wireless: Boolean = false,
    ) = Inputs(running, status, protocol, serverUp, lost, graceLeft, 8, usbAudio, setupError, wireless = wireless)

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
    fun errorAfterLossWhenServiceStopped() {
        val s = HomeLogic.compute(inp(lost = true, status = "мост: AA отключился"))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(PrimaryAction.RESTART_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals(UiText.res(R.string.notif_restart_title), s.notifTitle)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
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
