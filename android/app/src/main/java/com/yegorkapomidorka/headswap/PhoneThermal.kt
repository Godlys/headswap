package com.yegorkapomidorka.headswap

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.yegorkapomidorka.headswap.aa.ThermalSource

/** Нагрев телефона из PowerManager: статус (API 29) и прогноз headroom на 10 с (API 30; на старых и без HAL - NaN). */
class PhoneThermal(ctx: Context) : ThermalSource {
    private val pm = ctx.getSystemService(PowerManager::class.java)

    override fun status(): Int = try {
        pm?.currentThermalStatus ?: 0
    } catch (_: Exception) {
        0
    }

    override fun headroom(): Float = if (Build.VERSION.SDK_INT < 30) Float.NaN else try {
        pm?.getThermalHeadroom(HEADROOM_FORECAST_S) ?: Float.NaN
    } catch (_: Exception) {
        Float.NaN
    }

    private companion object {
        const val HEADROOM_FORECAST_S = 10
    }
}
