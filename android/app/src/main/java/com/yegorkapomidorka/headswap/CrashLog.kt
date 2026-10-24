package com.yegorkapomidorka.headswap

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Почему умер процесс. Java-краш пишется в лог перехватчиком до передачи системному обработчику.
 * Нативный краш, ANR, kill по памяти перехватить нельзя: на следующем старте (SDK 30+) читаем
 * ApplicationExitInfo и пишем в новый лог все смерти, которых ещё не видели.
 */
object CrashLog {
    private const val PREFS = "crashlog"
    private const val KEY_SEEN = "exit_seen_ts"
    private const val TRACE_MAX = 64 * 1024
    private const val STRINGS_MAX = 120

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                AppLog.e("CRASH !!!!!! необработанное исключение в потоке '${t.name}'", e)
                AppLog.detail(sw.toString().prependIndent("    "))
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
        try {
            logExitReasons(ctx.applicationContext ?: ctx)
        } catch (e: Exception) {
            AppLog.e("CRASH причины прошлых завершений не прочитаны", e)
        }
    }

    private fun logExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        if (list.isEmpty()) return
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (x in list) {
            val bad = x.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
            val line = "EXIT прошлое завершение ${fmt.format(Date(x.timestamp))}: ${reasonName(x.reason)} " +
                "status=${x.status} pid=${x.pid} процесс=${x.processName} важность=${x.importance} " +
                "pss=${x.pss / 1024} МБ rss=${x.rss / 1024} МБ описание=${x.description}"
            if (bad) AppLog.e("$line !!!!!!") else AppLog.i(line)
            if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                dumpTrace(x)
            }
        }
        prefs.edit().putLong(KEY_SEEN, list.last().timestamp).apply()
    }

    /** ANR - текстовый дамп потоков; нативный краш - tombstone в protobuf (SDK 31+), из него берём печатные строки. */
    @TargetApi(30)
    private fun dumpTrace(x: ApplicationExitInfo) {
        val bytes = try {
            x.traceInputStream?.use { s ->
                val buf = ByteArray(TRACE_MAX)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        } catch (e: Exception) {
            AppLog.e("EXIT трейс не прочитан", e)
            null
        } ?: run {
            AppLog.i("EXIT трейса нет")
            return
        }
        val text = if (x.reason == ApplicationExitInfo.REASON_ANR) {
            String(bytes, Charsets.UTF_8)
        } else {
            printableStrings(bytes).take(STRINGS_MAX).joinToString("\n")
        }
        AppLog.i("EXIT трейс (${bytes.size} Б${if (bytes.size >= TRACE_MAX) ", обрезан" else ""}):")
        AppLog.detail(text.prependIndent("    "))
    }

    private fun printableStrings(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (v in b) {
            val c = v.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 6) out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.length >= 6) out.add(cur.toString())
        return out
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$r"
    }
}
