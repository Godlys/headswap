package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPolicyTest {
    @Test
    fun firstTargetStarts() {
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false))
    }

    /** h126：尝试未成功（BUSY），车机重连了 BT 并再次发来 TARGET_INFO——从头开始，而不是“已在加入中”。 */
    @Test
    fun newTargetRestartsStuckAttempt() {
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false))
    }

    @Test
    fun newTargetSkippedWhenAlreadyInGroup() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = true))
    }

    /** h211：rejoin 每 5-10 秒询问一次 TARGET_INFO；相同的应答不会打断新发起的尝试，而过期或凭据不同的应答会打断。 */
    @Test
    fun sameTargetKeepsFreshAttempt() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 9_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 30_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = false, ageMs = 1_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false, same = false, ageMs = 0L))
    }

    @Test
    fun targetSameAs() {
        val a = P2pTarget("CarLife-x", null, null, null)
        assertEquals(true, a.sameAs(P2pTarget("CarLife-x", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-y", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-x", "DIRECT-ab", "12345678", null)))
    }

    /** h211：BT 存活但组已解散后，我们自行请求 TARGET_INFO——先快后慢（每 10 秒一次），最后停止。 */
    @Test
    fun rejoinBackoff() {
        assertEquals(2_000L, P2pPolicy.rejoinDelayMs(0))
        assertEquals(5_000L, P2pPolicy.rejoinDelayMs(1))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(2))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS - 1))
        assertEquals(null, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS))
    }

    /** h211/h185：四次请求（约 27 秒）仍未加入，第一次 30 秒的尝试已经结束——在第 4 步（约 37 秒）断开 RFCOMM。 */
    @Test
    fun rejoinDropsBtAfterFirstFailedJoin() {
        assertEquals(false, P2pPolicy.rejoinDropBt(0))
        assertEquals(false, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER - 1))
        assertEquals(true, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER))
        val dropAtMs = (0 until P2pPolicy.REJOIN_DROP_BT_AFTER + 1).sumOf { P2pPolicy.rejoinDelayMs(it)!! }
        assertTrue("сброс BT раньше конца первой попытки входа: $dropAtMs мс", dropAtMs > 32_000L)
    }
}
