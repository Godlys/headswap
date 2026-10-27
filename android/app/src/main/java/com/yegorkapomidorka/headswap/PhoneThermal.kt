package com.yegorkapomidorka.headswap

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.yegorkapomidorka.headswap.aa.ThermalSource

/** 来自 PowerManager 的手机温度：状态（API 29）和 10 秒 headroom 预测（API 30；旧版本和无 HAL 时为 NaN）。 */
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