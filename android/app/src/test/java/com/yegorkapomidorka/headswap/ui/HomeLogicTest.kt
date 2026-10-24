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
    fun parkedAfterSessionEnded() {
        // мост сам штатно закрыл установленную сессию: «Машина отключена», сервер ГУ в фоне «работает», без «проверяю» и кнопок
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
        // отказы и зависания прошлых подключений PARKED не меняют: SERVER_DOWN только при живом мосте
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
        // короткий отказ (~6 с после ByeBye) норма: обычное «Подключаюсь», без красного и без «Запустите сервер ГУ»
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
        // одно зависание ещё не вывод: обычное «Подключаюсь»
        val one = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = 1, worked = true))
        assertEquals(Phase.WAITING, one.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), one.title)
    }

    @Test
    fun serverDownNeedsEvidenceThatItWasUp() {
        // новичок: сервер ни разу не работал, длинный отказ и зависания не дают красного - прежнее «Запустите сервер ГУ»
        val fresh = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", refusedMs = 60_000))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(true, fresh.showChain)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(running = true, status = "мост: AA отключился", hangs = 5)).phase)
    }

    @Test
    fun serverDownOnlyWhileBridgeAttemptsAndNotOnceConnected() {
        // моста нет (кабеля нет): прошлые отказы ничего не значат
        assertEquals(Phase.OFF, HomeLogic.compute(inp(refusedMs = 60_000, hangs = 3, worked = true)).phase)
        // сессия идёт: обычное «Всё работает», даже если счётчики ещё не обнулились
        val connected = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.CONNECTED, connected.phase)
        // обрыв USB: grace держит сессию, отказов нет
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
