package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
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
        // «Уже сделано» ведёт дальше и ставит ручную отметку; шаг ничего не требует и не блокирует онбординг
        assertEquals(R.string.onb_already_done, s.skip)
        assertNull(s.hint)
        assertNull(s.extra)
        assertEquals(R.string.step_aa_unknown_short, s.short)
    }

    @Test
    fun carIsLastAndSkippable() {
        assertEquals(StepId.CAR, ids.last())
        assertEquals(StepId.FIRST, ids[ids.size - 2])
        assertEquals(R.string.onb_skip, Readiness.info(StepId.CAR).skip)
    }
}
