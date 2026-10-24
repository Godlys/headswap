package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.ui.UpgradeNotice.Launch
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeNoticeTest {
    @Test
    fun updatedUserSeesNoticeOnce() {
        assertEquals(Launch.NOTICE, UpgradeNotice.onLaunch(onboardingDone = true, noticeShown = false))
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(onboardingDone = true, noticeShown = true))
    }

    @Test
    fun newcomerGetsOnboardingNotNotice() {
        assertEquals(Launch.ONBOARDING, UpgradeNotice.onLaunch(onboardingDone = false, noticeShown = false))
        assertEquals(Launch.ONBOARDING, UpgradeNotice.onLaunch(onboardingDone = false, noticeShown = true))
    }
}
