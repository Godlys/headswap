package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
import org.junit.Test

class P2pPolicyTest {
    @Test
    fun firstTargetStarts() {
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false))
    }

    /** h126: попытка не вошла (BUSY), ГУ переподключила BT и прислала TARGET_INFO снова - начинаем заново, а не «уже вхожу». */
    @Test
    fun newTargetRestartsStuckAttempt() {
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false))
    }

    @Test
    fun newTargetSkippedWhenAlreadyInGroup() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = true))
    }

    /** h211: rejoin спрашивает TARGET_INFO раз в 5-10 с; тот же ответ не рвёт свежую попытку, протухшую или с другими реквизитами - рвёт. */
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

    /** h211: после распада группы при живом BT просим TARGET_INFO сами - сначала быстро, потом раз в 10 с, потом хватит. */
    @Test
    fun rejoinBackoff() {
        assertEquals(2_000L, P2pPolicy.rejoinDelayMs(0))
        assertEquals(5_000L, P2pPolicy.rejoinDelayMs(1))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(2))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS - 1))
        assertEquals(null, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS))
    }
}
