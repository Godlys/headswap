package com.yegorkapomidorka.headswap.diag

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog

/**
 * Отметки «Помогло / Не помогло» под советами диагноза. Хранятся на телефоне (id причины -> «helped|not_helped|время мс»)
 * и пишутся строкой в лог приложения: с отправленным логом разработчик видит, какие советы работают.
 */
object DiagFeedback {
    private const val PREFS = "diag_feedback"

    fun record(ctx: Context, id: String, helped: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(id, "${if (helped) "helped" else "not_helped"}|${System.currentTimeMillis()}")
            .apply()
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        AppLog.i("DIAG отзыв: $id = ${if (helped) "помогло" else "не помогло"} (версия $ver)")
    }
}
