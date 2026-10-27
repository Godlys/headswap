package com.yegorkapomidorka.headswap.aa

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 调试探测 `--es cmd aa_probe`：与桥接器相同的真实 Android Auto 会话（AapSession），连接 127.0.0.1:5277，但没有 CarLife 和 USB。
 * 视频会被计数并丢弃（ack 由会话自身发送）。PROBE_MS 之后会话以标准方式关闭（ByeBye、EOF、close）：
 * 服务器 5277 是一次性的。用于在 targetSdk 37（本地网络权限）下验证 loopback 访问。
 * 桥接器运行时不要启动：服务器只接受一个连接。
 */
object AaProbe {
    private const val TAG = "AAPROBE"
    private const val PROBE_MS = 15_000L
    private const val CLOSE_WAIT_MS = 20_000L

    @Volatile private var running = false

    private class CountingSink : AaVideoSink {
        val configs = AtomicLong()
        val frames = AtomicLong()
        val bytes = AtomicLong()

        override fun onCodecConfig(annexB: ByteArray) {
            configs.incrementAndGet()
            bytes.addAndGet(annexB.size.toLong())
        }

        override fun onFrame(annexB: ByteArray, ptsUs: Long) {
            frames.incrementAndGet()
            bytes.addAndGet(annexB.size.toLong())
        }
    }

    fun run(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (running) {
                AppLog.i("$TAG уже идёт, пропускаю")
                return
            }
            running = true
        }
        Thread({
            try {
                probe(app)
            } catch (e: Throwable) {
                AppLog.i("$TAG ИТОГ: сбой пробы ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
            }
        }, "aa-probe").start()
    }

    private fun probe(ctx: Context) {
        val host = AapOptions().host
        val port = AapOptions().port
        AppLog.i("$TAG старт: проба AA-сессии к $host:$port (${PROBE_MS / 1000} с)")
        val loaded = try {
            AaCreds.load(ctx, AaPrefs(ctx).certSet)
        } catch (e: Throwable) {
            AppLog.i("$TAG ИТОГ: сертификаты не загрузились ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val sink = CountingSink()
        val ended = CountDownLatch(1)
        // 由会话线程写入，await ended 后读取（latch 提供 happens-before）
        var endReason = "-"
        var endOk = false
        var discovery = false
        val channelOpens = AtomicLong()
        var connectLogged = false
        var connectError: String? = null

        val log: (String, Boolean) -> Unit = { line, ui ->
            AppLog.i(line, ui)
            when {
                line.startsWith("AA TCP подключено") && !connectLogged -> {
                    connectLogged = true
                    AppLog.i("$TAG connect OK $host:$port")
                }
                line.startsWith("AA сервер ") && line.contains("недоступен") -> {
                    connectError = line
                    AppLog.i("$TAG connect FAIL: $line")
                }
                line.startsWith("AA ошибка сокета:") || line.startsWith("AA сбой:") || line.startsWith("AA TLS ошибка:") -> {
                    if (!connectLogged) connectError = line
                    AppLog.i("$TAG ERROR: $line")
                }
                line.contains("RX CONTROL(0) SERVICE_DISCOVERY_REQUEST") && !discovery -> {
                    discovery = true
                    AppLog.i("$TAG service discovery: запрос телефона получен, SDR отправлен")
                }
                line.contains("RX ") && line.contains("CHANNEL_OPEN_REQUEST") -> {
                    AppLog.i("$TAG канал открыт #${channelOpens.incrementAndGet()}: $line")
                }
            }
        }

        val session = AapSession(
            loaded.creds, sink,
            AapOptions(sdProvider = { VideoMode.DEFAULT.sdOptions(fullAudio = false) }),
            log,
            onEstablished = { AppLog.i("$TAG TLS + AuthComplete пройдены, сессия установлена") },
            onFirstVideo = { AppLog.i("$TAG первый видеокадр получен") },
            onEnd = { reason, ok ->
                endReason = reason
                endOk = ok
                ended.countDown()
            },
        )
        session.start()
        val endedEarly = ended.await(PROBE_MS, TimeUnit.MILLISECONDS)
        if (!endedEarly) {
            AppLog.i("$TAG ${PROBE_MS / 1000} с прошло, закрываю сессию штатно (ByeBye)")
            session.requestStop("проба завершена")
            if (!ended.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) AppLog.i("$TAG сессия не закрылась за ${CLOSE_WAIT_MS / 1000} с")
        }
        val s = session.stats
        val verdict = when {
            !session.connected -> "FAIL connect: ${connectError ?: endReason}"
            session.videoStarted && sink.frames.get() > 0 -> "OK, видео идёт"
            session.videoStarted -> "частично: MEDIA_START был, кадров нет"
            session.established -> "частично: сессия установлена, видео не началось"
            else -> "частично: TCP есть, до установки сессии не дошли ($endReason)"
        }
        AppLog.i(
            "$TAG ИТОГ: $verdict | connected=${session.connected} established=${session.established} discovery=$discovery " +
                "channelOpens=${channelOpens.get()} videoStarted=${session.videoStarted} " +
                "видео config=${sink.configs.get()} кадров=${sink.frames.get()} байт=${sink.bytes.get()} " +
                "rx=${s.rxFrames} tx=${s.txFrames} | закрытие чистое=$endOk, причина: $endReason"
        )
    }
}
