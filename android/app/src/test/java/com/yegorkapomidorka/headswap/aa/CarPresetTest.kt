package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPresetTest {
    @Test
    fun presetsApplyAsWritten() {
        // режим с кропом без перекодирования молча стал бы 1280×720: такой пресет «изменён вручную» сразу после выбора
        for (p in CarPreset.values()) {
            assertEquals(p.name, p.settings.videoMode, p.settings.videoMode.effective(p.settings.transcode))
            assertEquals(p.name, p.settings.aaDensity, AaDensity.parse(p.settings.aaDensity))
        }
    }

    @Test
    fun verifiedValues() {
        assertEquals(VideoMode.WIDE_MID, CarPreset.MAZDA_CX5_2025.settings.videoMode)
        assertEquals(VideoMode.WIDE_976, CarPreset.VW_TIGUAN_L_PRO.settings.videoMode)
        assertEquals(CarSettings(), CarPreset.HONDA_CRV_2026.settings)
    }

    @Test
    fun parse() {
        for (p in CarPreset.values()) assertEquals(p, CarPreset.parse(p.name))
        assertNull(CarPreset.parse(null))
        assertNull(CarPreset.parse("NO_SUCH_CAR"))
    }
}
