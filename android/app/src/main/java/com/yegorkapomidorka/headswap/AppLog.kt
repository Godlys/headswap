package com.yegorkapomidorka.headswap

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志写入三个地方：logcat（标签 Headswap）、getExternalFilesDir(null) 下的 headswap-<timestamp>.log 文件，
 * 以及供屏幕显示的最近行环形缓冲区。详细信息（hexdump）只写入 logcat 和文件。
 */
object AppLog {
    const val TAG = "Headswap"
    private const val RING = 200

    private val ring = ArrayDeque<String>()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var out: FileOutputStream? = null
    var file: File? = null
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (out != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "headswap-$stamp.log")
        try {
            out = FileOutputStream(f, true)
            file = f
        } catch (e: Exception) {
            Log.e(TAG, "не открыть файл лога $f", e)
        }
        i("лог: ${f.absolutePath}")
        CrashLog.install(ctx)
    }

    /** 供所有接收者使用的行（如果 ui=true，屏幕也显示）。 */
    @Synchronized
    fun i(line: String, ui: Boolean = true) {
        Log.i(TAG, line)
        write(line)
        if (ui) {
            ring.addLast(tsFmt.format(Date()) + " " + line)
            while (ring.size > RING) ring.removeFirst()
        }
    }

    /** 详细信息：仅 logcat 和文件。 */
    @Synchronized
    fun detail(text: String) {
        for (l in text.split('\n')) Log.d(TAG, l)
        write(text)
    }

    @Synchronized
    fun e(line: String, t: Throwable? = null) {
        Log.e(TAG, line, t)
        val full = if (t != null) "$line: ${t.javaClass.simpleName}: ${t.message}" else line
        write("ERROR $full")
        ring.addLast(tsFmt.format(Date()) + " ERROR " + full)
        while (ring.size > RING) ring.removeFirst()
    }

    @Synchronized
    fun lastLines(): List<String> = ring.toList()

    /** 清空屏幕上的行（不动日志文件）。 */
    @Synchronized
    fun clearRing() = ring.clear()

    private fun write(text: String) {
        val o = out ?: return
        try {
            o.write((tsFmt.format(Date()) + " " + text + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (_: Exception) {
        }
    }

    /** 记录一条消息：标题 + inner 前 256 字节的 hexdump 和解码后的 protobuf。 */
    fun msg(dir: String, chan: Int, outerLen: Int, type: Int, len: Int, decoded: String?, inner: ByteArray, ui: Boolean = true, note: String = "") {
        val head = "$dir ${Ch.name(chan)} ${Msg.hex(type)} ${Msg.name(type)} len=$len" +
            (if (decoded != null) " $decoded" else "") + note
        i(head, ui)
        detail("    outer: chan=$chan len=$outerLen\n" + Hex.dump(inner, 256).prependIndent("    "))
    }
}