package com.yegorkapomidorka.headswap.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.yegorkapomidorka.headswap.AppLog

/**
 * 手机麦克风（Android Auto 的备用音频源）：AudioRecord VOICE_RECOGNITION，16 kHz 单声道 PCM16。
 * 每块 2048 个采样（4096 字节）。需要 RECORD_AUDIO 权限；Android 11+ 在后台会静默输出静音，
 * 因此日志中记录前几块的信号峰值：峰值为零 = 系统正在静音麦克风。
 */
class PhoneMicCapture(private val ctx: Context) : PhoneMic {
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var running = false

    @Synchronized
    override fun start(sink: (ByteArray) -> Unit): Boolean {
        if (running) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("MIC phone: нет разрешения RECORD_AUDIO (дай в UI)", true)
            return false
        }
        val min = AudioRecord.getMinBufferSize(AaMicFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            AppLog.i("MIC phone: getMinBufferSize=$min, 16 кГц моно не поддерживается", true)
            return false
        }
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, AaMicFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, AaMicFormat.CHUNK_BYTES * 2)
            )
        } catch (e: Exception) {
            AppLog.e("MIC phone: AudioRecord не создан", e)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.i("MIC phone: AudioRecord не инициализирован (state=${r.state})", true)
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            AppLog.e("MIC phone: startRecording", e)
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            AppLog.i("MIC phone: запись не началась (recordingState=${r.recordingState}): микрофон занят?", true)
            r.release()
            return false
        }
        rec = r
        running = true
        val t = Thread({ loop(r, sink) }, "phone-mic")
        t.isDaemon = true
        t.start()
        AppLog.i("MIC phone: AudioRecord VOICE_RECOGNITION ${AaMicFormat.SAMPLE_RATE} Гц моно запущен", true)
        return true
    }

    private fun loop(r: AudioRecord, sink: (ByteArray) -> Unit) {
        val buf = ByteArray(AaMicFormat.CHUNK_BYTES)
        var blocks = 0
        var total = 0L
        var peakAll = 0
        // 线程存活直到 rec 变为当前记录实例：stop() 后立即 start()（重新 open）时 running 又会变成 true，
        // 仅凭一个标志，旧线程会一直轮询已停止的 AudioRecord 而不释放它
        while (running && rec === r) {
            val n = r.read(buf, 0, buf.size)
            if (rec !== r) break               // read 期间 stop()：不发送已停止录音的尾部数据
            if (n <= 0) {
                if (n < 0 && running) AppLog.i("MIC phone: read вернул $n, останавливаюсь", true)
                if (n < 0) break
                continue
            }
            blocks++
            total += n
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            if (peak > peakAll) peakAll = peak
            if (blocks == 1 || blocks == 10) AppLog.i("MIC phone: блок #$blocks $n Б, пик=$peak (0 = тишина/микрофон заглушен системой)", false)
            sink(buf.copyOf(n))
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        AppLog.i("MIC phone: поток записи закончен: блоков $blocks, $total Б, максимальный пик $peakAll", true)
    }

    /** 不等待录音线程（其 sink 可能阻塞在路由器的锁上）：线程自己会释放 AudioRecord。 */
    @Synchronized
    override fun stop() {
        running = false
        val r = rec
        rec = null
        try {
            r?.stop()
        } catch (_: Exception) {
        }
    }
}
