package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLogicTest {
    @Test
    fun macComparisonIgnoresCase() {
        assertTrue(CarMac.same("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(CarMac.same("aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:ff"))
        assertFalse(CarMac.same("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:00"))
    }

    @Test
    fun macNullNeverMatches() {
        assertFalse(CarMac.same(null, "aa:bb:cc:dd:ee:ff"))
        assertFalse(CarMac.same("aa:bb:cc:dd:ee:ff", null))
        assertFalse(CarMac.same(null, null))
    }

    @Test
    fun debounceFiresOnlyAfterDelay() {
        var now = 1_000L
        val d = AutoOffDebounce(10_000L) { now }
        assertFalse(d.fire())      // 未布防
        d.arm()
        assertTrue(d.pending)
        now += 9_999L
        assertFalse(d.fire())
        assertTrue(d.pending)      // 太早：保持布防状态
        now += 1L
        assertTrue(d.fire())
        assertFalse(d.pending)
        assertFalse(d.fire())      // 只触发一次
    }

    @Test
    fun debounceCancelledByReconnect() {
        var now = 0L
        val d = AutoOffDebounce(10_000L) { now }
        d.arm()
        now += 5_000L
        d.cancel()                 // 收到了 ACL_CONNECTED
        now += 60_000L
        assertFalse(d.fire())
        assertFalse(d.pending)
    }

    @Test
    fun debounceBurstRearmsFromLastDisconnect() {
        // 车机上的 ACL 突发：每个新的 DISCONNECTED 都会延后期限
        var now = 0L
        val d = AutoOffDebounce(10_000L) { now }
        d.arm()
        now += 7_000L
        d.arm()
        now += 7_000L              // 距第一次 14 秒，距第二次 7 秒
        assertFalse(d.fire())
        now += 3_000L
        assertTrue(d.fire())
    }

    @Test
    fun minIntervalLimitsRate() {
        var now = 5_000L
        val m = MinInterval(60_000L) { now }
        assertTrue(m.tryAcquire())
        now += 59_999L
        assertFalse(m.tryAcquire())
        now += 1L
        assertTrue(m.tryAcquire())
        assertEquals(false, m.tryAcquire())
    }

    /** 所有条件均已满足；各测试每次只破坏一个条件。 */
    private fun request(
        carWifiSet: Boolean = true,
        waiting: Boolean = true,
        session: Boolean = false,
        usb: Boolean = false,
        requesting: Boolean = false,
        waitedMs: Long = CAR_NET_DELAY_MS,
        sixOfSix: Boolean = false,
        wifiDirect: Boolean = false,
    ) = CarNetPolicy.shouldRequest(carWifiSet, waiting, session, usb, requesting, waitedMs, sixOfSix, wifiDirect)

    @Test
    fun carNetRequestedAfterDelayWhileWaiting() {
        assertTrue(request())
        assertTrue(request(waitedMs = CAR_NET_DELAY_MS + 1))
    }

    @Test
    fun carNetNotRequestedBeforeDelay() {
        assertFalse(request(waitedMs = CAR_NET_DELAY_MS - 1))
        assertFalse(request(waitedMs = 0))
    }

    @Test
    fun carNetDelayZeroOnlyRightAfterCarWasOnline() {
        assertEquals(CAR_NET_DELAY_MS, CarNetPolicy.delayMs(null))              // 之前没有汽车：在手机网络中等待它
        assertEquals(0L, CarNetPolicy.delayMs(0))
        assertEquals(0L, CarNetPolicy.delayMs(11_000))                         // 拔掉了数据线，AA 宽限期 8 秒
        assertEquals(CAR_NET_DELAY_MS, CarNetPolicy.delayMs(CAR_RECENT_MS))
        assertEquals(CAR_NET_DELAY_MS, CarNetPolicy.delayMs(-1))              // 系统时间回拨：视为从未有车
    }

    @Test
    fun carNetDelayZeroWhenCarConnectedByBluetooth() {
        assertEquals(0L, CarNetPolicy.delayMs(null, carBtNear = true))         // 冷启动，汽车就在附近（BT）
        assertEquals(0L, CarNetPolicy.delayMs(CAR_RECENT_MS * 10, carBtNear = true))
        assertEquals(CAR_NET_DELAY_MS, CarNetPolicy.delayMs(null, carBtNear = false))
        assertEquals(0L, CarNetPolicy.delayMs(null, carNetProven = true))      // 车机已通过自己的网络接入过
        assertEquals("wpa2", CarSecPolicy.first(null, hasPass = true))
        assertEquals("wpa3", CarSecPolicy.first("wpa3", hasPass = true))
        assertEquals("open", CarSecPolicy.first("wpa2", hasPass = false))
        assertTrue(CarSecPolicy.retryWpa3(null, "wpa2", hasPass = true))         // 加密方式未知：尝试 WPA3
        assertFalse(CarSecPolicy.retryWpa3("wpa2", "wpa2", hasPass = true))     // WPA2 已成功过：只是汽车不在信号范围内
        assertFalse(CarSecPolicy.retryWpa3(null, "wpa3", hasPass = true))
        assertEquals(CAR_NET_DELAY_MS, CarNetPolicy.delayMs(CAR_RECENT_MS, carNetProven = false))
    }

    @Test
    fun carNetRequestedAtOnceWithZeroDelay() {
        assertTrue(CarNetPolicy.shouldRequest(true, true, false, false, false, 0, false, false, delayMs = 0))
        assertFalse(CarNetPolicy.shouldRequest(true, true, true, false, false, 0, false, false, delayMs = 0))
        assertFalse(CarNetPolicy.shouldRequest(true, true, false, false, false, 0, false, true, delayMs = 0))
    }

    @Test
    fun carNetNotRequestedWithoutCarWifi() {
        assertFalse(request(carWifiSet = false))
    }

    @Test
    fun carNetNotRequestedWhenNotWaiting() {
        assertFalse(request(waiting = false))
    }

    @Test
    fun carNetNotRequestedDuringAnySession() {
        assertFalse(request(session = true))
        assertFalse(request(usb = true))
        assertFalse(request(session = true, usb = true))
    }

    @Test
    fun carNetNotRequestedTwiceOrAfterSixOfSix() {
        assertFalse(request(requesting = true))
        assertFalse(request(sixOfSix = true))
    }

    @Test
    fun carNetNotRequestedForWifiDirectCar() {
        assertFalse(request(wifiDirect = true))
        assertFalse(request(wifiDirect = true, waitedMs = CAR_NET_DELAY_MS * 10))
    }

    @Test
    fun carNetReleasedWhenWaitingStopsOrServiceDies() {
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.AUTO_OFF))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.STOP))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.USB_WINS))
        assertTrue(CarNetPolicy.shouldRelease(CarNetEvent.DESTROY))
    }

    @Test
    fun carNetHeldAcrossSessions() {
        assertFalse(CarNetPolicy.shouldRelease(CarNetEvent.SESSION_START))
        assertFalse(CarNetPolicy.shouldRelease(CarNetEvent.SESSION_END))
    }

    /** 所有条件均已满足；各测试每次只破坏一个条件。 */
    private fun btHint(
        waiting: Boolean = true,
        session: Boolean = false,
        waitedMs: Long = BT_HINT_MS,
        accepts: Int = 0,
        targetInfo: Boolean = false,
        carBt: Boolean = true,
        mode: CarConnMode = CarConnMode.UNKNOWN,
        shown: Boolean = false,
    ) = BtReconnectHint.shouldShow(waiting, session, waitedMs, accepts, targetInfo, carBt, mode, shown)

    @Test
    fun btHintNotShownAfterTargetInfo() {
        assertFalse(btHint(targetInfo = true))
        assertFalse(btHint(targetInfo = true, mode = CarConnMode.BT_CARLIFE, waitedMs = BT_HINT_MS * 10))
    }

    @Test
    fun btHintShownWhenCarConnectedButSilent() {
        assertTrue(btHint())
        assertTrue(btHint(waitedMs = BT_HINT_MS + 1))
        assertTrue(btHint(mode = CarConnMode.BT_CARLIFE))
    }

    @Test
    fun btHintNotShownBeforeDelay() {
        assertFalse(btHint(waitedMs = BT_HINT_MS - 1))
    }

    @Test
    fun btHintNotShownWhenNotWaitingOrSessionRunning() {
        assertFalse(btHint(waiting = false))
        assertFalse(btHint(session = true))
    }

    @Test
    fun btHintNotShownAfterCarAccepted() {
        assertFalse(btHint(accepts = 1))
    }

    @Test
    fun btHintNotShownWithoutCarBluetooth() {
        assertFalse(btHint(carBt = false))
    }

    @Test
    fun btHintNotShownForHotspotCar() {
        assertFalse(btHint(mode = CarConnMode.HOTSPOT))
    }

    @Test
    fun btHintShownOncePerWait() {
        assertFalse(btHint(shown = true))
    }
}
