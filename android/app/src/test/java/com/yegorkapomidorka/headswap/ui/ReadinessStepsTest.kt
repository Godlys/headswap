package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadinessStepsTest {
    private val ids get() = Readiness.STEPS.map { it.id }

    @Test
    fun unknownSourcesFollowsAaDev() {
        assertEquals(ids.indexOf(StepId.AA_DEV) + 1, ids.indexOf(StepId.AA_UNKNOWN))
    }

    @Test
    fun everyStepListedOnceAndKeysUnique() {
        assertEquals(StepId.values().toSet(), ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(StepId.values().size, StepId.values().map { it.key }.toSet().size)
    }

    @Test
    fun unknownSourcesIsOptionalAndManual() {
        val s = Readiness.info(StepId.AA_UNKNOWN)
        assertEquals("aa_unknown", s.id.key)
        // “已完成”会继续下一步并打上手动标记；该步骤不要求任何操作，也不阻塞引导流程
        assertEquals(R.string.onb_already_done, s.skip)
        assertEquals(R.string.step_aa_unknown_hint, s.hint)
        assertNull(s.extra)
        assertEquals(R.string.step_aa_unknown_short, s.short)
    }

    @Test
    fun serverStepIsManualWithoutExtra() {
        // 没有无障碍服务：步骤是否完成只能靠手动标记“知道了”
        val s = Readiness.info(StepId.AUTOSTART)
        assertEquals(R.string.onb_got_it, s.skip)
        assertEquals(R.string.step_autostart_primary, s.primary)
        assertNull(s.extra)
    }

    @Test
    fun onlyCarStepHasBodyInsteadOfList() {
        for (s in Readiness.STEPS) {
            if (s.id == StepId.CAR) {
                assertNull(s.list)
                assertEquals(R.string.step_car_body, s.body)
            } else {
                assertNotNull(s.id.key, s.list)
                assertNull(s.id.key, s.body)
            }
        }
    }

    @Test
    fun extraOnlyOnBackgroundStep() {
        assertEquals(listOf(StepId.BACKGROUND), Readiness.STEPS.filter { it.extra != null }.map { it.id })
        // 第 1 和第 5 步没有提示
        assertNull(Readiness.info(StepId.AA_DEV).hint)
        assertNull(Readiness.info(StepId.BACKGROUND).hint)
    }

    @Test
    fun carIsLastAndSkippable() {
        assertEquals(StepId.CAR, ids.last())
        assertEquals(StepId.FIRST, ids[ids.size - 2])
        assertEquals(R.string.onb_skip, Readiness.info(StepId.CAR).skip)
    }
}
