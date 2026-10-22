package com.yegorkapomidorka.headswap.diag

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.ui.LogShare
import java.io.File
import java.io.RandomAccessFile

/** Обвязка LogDiag для приложения: правила из assets, логи из LogShare, работа в фоне, результат на главном потоке. */
object DiagRunner {
    private const val RULES_ASSET = "logdiag_rules.json"
    private const val MAX_LOGS = 5
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val CUT_MARK = "[... начало обрезано ...]"

    fun run(ctx: Context, onDone: (Result<LogDiag.Result>) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = runCatching { analyze(app) }
            r.exceptionOrNull()?.let { AppLog.e("диагностика подключения не удалась", it) }
            main.post { onDone(r) }
        }, "logdiag").start()
    }

    private fun analyze(ctx: Context): LogDiag.Result {
        val rules = ctx.assets.open(RULES_ASSET).use { LogDiag.parseRules(it.readBytes().toString(Charsets.UTF_8)) }
        val logs = LogShare.logFiles(ctx).take(MAX_LOGS).reversed().map { it.name to readTail(it) }
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        val env = LogDiag.Env(ver, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        val en = ctx.resources.configuration.locales[0].language != "ru"
        return LogDiag.run(logs, env, rules, en)
    }

    /** Файл целиком или последние MAX_FILE_BYTES: без неполной первой строки, с пометкой в начале (как в zip отчёта). */
    private fun readTail(f: File): String = RandomAccessFile(f, "r").use { raf ->
        val len = raf.length()
        val cut = len > MAX_FILE_BYTES
        if (cut) raf.seek(len - MAX_FILE_BYTES)
        val bytes = ByteArray((if (cut) MAX_FILE_BYTES else len).toInt())
        raf.readFully(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        if (cut) CUT_MARK + "\n" + text.substringAfter('\n', "") else text
    }
}
