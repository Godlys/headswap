package com.yegorkapomidorka.headswap.diag

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog

/**
 * 诊断建议下方的“有帮助 / 没帮助”标记。存储在手机上（原因 id -> “helped|not_helped|时间毫秒”）
 * 并作为一行写入应用日志：开发者随日志一起看到哪些建议有效。
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
