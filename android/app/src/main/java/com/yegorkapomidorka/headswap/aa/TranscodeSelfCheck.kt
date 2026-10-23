package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Самопроверка транскодера без AA и USB: `--es cmd transcode_test`. Берёт самый крупный aa-*.h264 (сырой Annex-B от AA, кладётся в getExternalFilesDir через adb push),
 * гонит его через декодер -> энкодер ~10 с (30 fps, посреди пауза 3 с без входных кадров: энкодер обязан
 * повторять кадр) и логирует кадры на входе/выходе, fps, первые NAL выхода, задержку.
 *
 * Строка настроек идёт extra-ом `opts` (в AaTranscoder уходит как tuning) и разбирается по подстрокам. Токены самой проверки
 * (в транскодер не передаются): `slowout=NN`, `resetevery=NN`, `burst`, `nopause`, `placeholder`, `phonly`.
 * Остальное (`nogl`, `nobase`, `cbr`, `norealtime`, `nolowlat`) уходит в транскодер как есть.
 *
 * Как и мост, проверка после потери кадра (onNeedAaKeyFrame) и после resetDecoder() перестаёт подавать P-кадры
 * и подаёт ближайший IDR из файла с SPS/PPS.
 *
 * Запуск (лог в AppLog, `adb logcat -s Headswap` или файл лога приложения; ищи `transcode_test`):
 * ```
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test
 * # медленный слив в ГУ / занятый монитор моста: onEncoderFrame спит 20 мс (петля энкодер -> GL -> декодер -> вход).
 * # Ждём: нет остановки, кадры выхода идут (реже), есть потери и запросы IDR
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=20"
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=80"
 * # resetDecoder() с другой нити раз в 700 мс (как START после PAUSE). Ждём: декодер пересоздаётся на следующем IDR, выход возобновляется
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "resetevery=700"
 * # пачки: 10 кадров без задержки, пауза 330 мс (переполнение очереди кормления). Ждём: потери и восстановление по IDR, без зависания
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "burst"
 * # всё вместе
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=30 resetevery=900 burst"
 * ```
 * Сторожок: выход энкодера молчит > 3 с вне запланированной паузы -> `!!!!!! выход энкодера молчит N с`.
 * В конце ИТОГ2 и проверка нитей `aa-trans-*` / `aa-gl-relay` через 2 с после stop().
 */
object TranscodeSelfCheck {
    private const val TOTAL_MS = 10_000L
    private const val PAUSE_AT_FRAME = 90          // после 3 с при 30 fps
    private const val PAUSE_MS = 3_000L
    private const val FRAME_MS = 33L
    private const val BURST_FRAMES = 10
    private const val BURST_PAUSE_MS = 330L
    private const val SILENCE_MS = 3_000L
    private const val THREAD_CHECK_DELAY_MS = 2_000L

    fun run(ctx: Context, tuning: String = "") {
        Thread({ runBlocking(ctx, tuning) }, "transcode-test").start()
    }

    private fun runBlocking(ctx: Context, tuning: String) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val file = dir.listFiles { f -> f.name.startsWith("aa-") && f.name.endsWith(".h264") }?.maxByOrNull { it.length() }
        if (file == null) {
            AppLog.i("transcode_test: в $dir нет aa-*.h264")
            return
        }
        val (cfg, aus) = H264Stream.split(file.readBytes())
        AppLog.i("transcode_test: файл ${file.name} ${file.length() / 1024} КиБ, access unit-ов ${aus.size} (IDR ${aus.count { it.idr }}), config ${cfg?.size ?: 0} Б")
        if (cfg == null || aus.isEmpty()) {
            AppLog.i("transcode_test: нет SPS/PPS или кадров")
            return
        }
        val firstIdr = aus.indexOfFirst { it.idr }
        if (firstIdr < 0) {
            AppLog.i("transcode_test: в файле нет IDR")
            return
        }

        // токены самой проверки; в транскодер уходит строка без них
        val slowOutMs = Regex("""slowout=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val resetEveryMs = Regex("""resetevery=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val burst = Regex("""\bburst\b""").containsMatchIn(tuning)
        val tcTuning = tuning.replace(Regex("""\s*\b(?:slowout|resetevery)=\d+|\s*\bburst\b"""), "").trim()
        if (slowOutMs > 0 || resetEveryMs > 0 || burst) {
            AppLog.i("transcode_test: режим: slowout=$slowOutMs мс, resetevery=$resetEveryMs мс, burst=$burst; в транскодер tuning='$tcTuning'")
        }

        // выход энкодера как он уходит на ГУ (SPS/PPS склеены только с первым IDR, как в AaBridge.onEncoderFrame):
        // enc-out.h264 рядом, смотреть ffmpeg на ПК
        val dump = File(dir, "enc-out.h264").outputStream().buffered()
        var dumpGotIdr = false

        class Out : TranscodeOutput {
            val frames = AtomicLong()
            val keys = AtomicLong()
            val needKeys = AtomicLong()
            @Volatile var firstNals: List<Int>? = null
            @Volatile var csd: ByteArray? = null
            @Volatile var pauseStartFrames = -1L
            @Volatile var pauseEndFrames = -1L
            @Volatile var errors = 0
            @Volatile var gotIdr = false               // как AaBridge.gotIdr: false -> P-кадры не подаём, ждём IDR из файла
            @Volatile var lastFrameMs = 0L             // время последнего кадра выхода (0 - ещё не было)
            @Volatile var maxGapMs = 0L
            @Volatile var silentReported = false

            override fun onEncoderConfig(csd: ByteArray) {
                this.csd = csd
            }

            override fun onEncoderFrame(data: ByteArray, key: Boolean) {
                val now = SystemClock.elapsedRealtime()
                val prev = lastFrameMs
                if (prev > 0 && now - prev > maxGapMs) maxGapMs = now - prev
                lastFrameMs = now
                silentReported = false
                if (slowOutMs > 0) Thread.sleep(slowOutMs)      // медленный слив в ГУ / занятый монитор моста
                if (frames.getAndIncrement() == 0L) {
                    val c = csd
                    firstNals = AnnexB.nalTypes(if (c != null) AnnexB.glue(c, data) else data)
                }
                if (key) keys.incrementAndGet()
                val c = csd
                synchronized(dump) {
                    if (!dumpGotIdr && !key) return
                    dump.write(if (!dumpGotIdr && c != null && !AnnexB.startsWithSps(data)) AnnexB.glue(c, data) else data)
                    dumpGotIdr = true
                }
            }

            override fun onTranscodeError(msg: String) {
                errors++
                AppLog.i("transcode_test: ОШИБКА $msg")
            }

            override fun onNeedAaKeyFrame() {
                gotIdr = false
                needKeys.incrementAndGet()
                AppLog.i("transcode_test: декодер потерял кадр")
            }
        }

        val out = Out()
        // "placeholder": параллельно второй энкодер заглушки, как в живой сессии (VideoSource + транскодер)
        val ph = if (tuning.contains("placeholder")) try {
            com.yegorkapomidorka.headswap.VideoSource(1280, 720, 30, 4_000_000, object : com.yegorkapomidorka.headswap.VideoSource.Listener {
                override fun onConfig(csd: ByteArray) {}
                override fun onFrame(data: ByteArray, key: Boolean) {}
                override fun onError(msg: String, t: Throwable?) {
                    AppLog.e("transcode_test: ОШИБКА заглушки $msg", t)
                }
            }).also { it.banner = "test"; it.start(); AppLog.i("transcode_test: заглушка ${it.encoderName} запущена") }
        } catch (e: Exception) {
            AppLog.e("transcode_test: заглушка не создана", e)
            null
        } else null
        if (tuning.contains("phonly")) {
            Thread.sleep(TOTAL_MS)
            ph?.let { AppLog.i("transcode_test: только заглушка, отрисовала ${it.framesRendered} кадров"); it.stop() }
            return
        }
        val t = try {
            AaTranscoder(1280, 720, 30, 4_000_000, out, { AppLog.i("transcode_test: $it") }, tcTuning).also { it.start() }
        } catch (e: Exception) {
            AppLog.e("transcode_test: транскодер не создан", e)
            return
        }
        var dropSum = 0L                                // "потеряно" из statsLine (окна сбрасываются при каждом вызове)
        fun stats(): String {
            val s = t.statsLine()
            dropSum += Regex("""потеряно (\d+)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return s
        }
        t.feedConfig(cfg)
        t.requestSyncFrame()
        val t0 = SystemClock.elapsedRealtime()
        var fed = 0
        var skippedP = 0                                // P-кадры файла, не поданные из-за ожидания IDR
        var idx = firstIdr
        var pauseStartMs = -1L
        val pauseEndMs = AtomicLong(-1L)
        var lastStats = t0
        val inPause = AtomicBoolean(false)
        val active = AtomicBoolean(true)                // вспомогательные нити работают, пока идёт подача
        val resets = AtomicInteger()
        val resetGen = AtomicInteger()                  // счётчик reset-ов: подающая нить по нему узнаёт, что надо ждать IDR
        val silentEpisodes = AtomicInteger()
        val maxSilentMs = AtomicLong()

        // сторожок: выход энкодера молчит > 3 с вне запланированной паузы
        val watch = Thread({
            try {
                while (active.get()) {
                    Thread.sleep(200)
                    if (inPause.get()) continue
                    val now = SystemClock.elapsedRealtime()
                    val ref = maxOf(out.lastFrameMs, t0, pauseEndMs.get())
                    val silent = now - ref
                    if (silent > SILENCE_MS && !out.silentReported) {
                        out.silentReported = true
                        silentEpisodes.incrementAndGet()
                        AppLog.i("transcode_test: !!!!!! выход энкодера молчит ${silent / 1000.0} с")
                    }
                    if (silent > SILENCE_MS && silent > maxSilentMs.get()) maxSilentMs.set(silent)
                }
            } catch (_: InterruptedException) {
            }
        }, "transcode-test-watch").also { it.isDaemon = true; it.start() }

        // resetevery: resetDecoder() с другой нити, как мост на START после PAUSE
        val resetter = if (resetEveryMs > 0) Thread({
            try {
                while (active.get()) {
                    Thread.sleep(resetEveryMs)
                    if (!active.get()) break
                    t.resetDecoder()
                    resetGen.incrementAndGet()
                    AppLog.i("transcode_test: resetDecoder #${resets.incrementAndGet()}")
                }
            } catch (_: InterruptedException) {
            }
        }, "transcode-test-reset").also { it.isDaemon = true; it.start() } else null

        var seenGen = 0
        var inBurst = 0
        while (SystemClock.elapsedRealtime() - t0 < TOTAL_MS) {
            if (fed == PAUSE_AT_FRAME && pauseStartMs < 0 && !tuning.contains("nopause")) {
                pauseStartMs = SystemClock.elapsedRealtime()
                out.pauseStartFrames = out.frames.get()
                AppLog.i("transcode_test: пауза входа ${PAUSE_MS / 1000} с (кадров на выходе до паузы ${out.pauseStartFrames})")
                inPause.set(true)
                Thread.sleep(PAUSE_MS)
                val pauseEnd = SystemClock.elapsedRealtime()
                pauseEndMs.set(pauseEnd)
                inPause.set(false)
                out.pauseEndFrames = out.frames.get()
                AppLog.i("transcode_test: пауза кончилась: за ${(pauseEnd - pauseStartMs) / 1000.0} с энкодер выдал ${out.pauseEndFrames - out.pauseStartFrames} кадров = %.1f fps".format((out.pauseEndFrames - out.pauseStartFrames) * 1000.0 / (pauseEnd - pauseStartMs)))
            }
            val gen = resetGen.get()
            if (gen != seenGen) {                       // был reset: до ближайшего IDR из файла P не подаём
                seenGen = gen
                out.gotIdr = false
            }
            if (idx >= aus.size) idx = firstIdr          // конец файла: по кругу с IDR
            val au = aus[idx++]
            if (out.gotIdr) {
                t.feedFrame(au.data, fed * 33_333L)
                fed++
            } else if (au.idr) {
                // первый IDR после старта/потери кадра/reset: SPS/PPS из конфига, если в самом кадре их нет (после reset транскодер их забыл)
                val types = AnnexB.nalTypes(au.data)
                val payload = if (AnnexB.startsWithSps(au.data) && 8 in types) au.data else AnnexB.glue(cfg, au.data)
                out.gotIdr = true                        // до feedFrame: он может сам позвать onNeedAaKeyFrame
                t.feedFrame(payload, fed * 33_333L)
                fed++
                t.requestSyncFrame()
            } else {
                skippedP++
            }
            if (SystemClock.elapsedRealtime() - lastStats >= 5000) {
                AppLog.i("transcode_test: ${stats()}")
                lastStats = SystemClock.elapsedRealtime()
            }
            if (burst) {
                if (++inBurst >= BURST_FRAMES) {
                    inBurst = 0
                    Thread.sleep(BURST_PAUSE_MS)
                }
            } else {
                Thread.sleep(FRAME_MS)
            }
        }
        Thread.sleep(500)
        val totalMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("transcode_test: ${stats()}")
        val frames = out.frames.get()
        AppLog.i(
            "transcode_test ИТОГ: вход AA=$fed кадров, на выходе энкодера=$frames кадров (ключевых ${out.keys.get()}), %.1f fps за %.1f с, ".format(frames * 1000.0 / totalMs, totalMs / 1000.0) +
                "декодировано=${t.totalDecoded.get()}, первые NAL выхода (SPS+PPS склеены с первым кадром)=${out.firstNals}, ошибок=${out.errors}, энкодер=${t.encoderName}"
        )
        active.set(false)
        watch.interrupt()
        resetter?.interrupt()
        try {
            watch.join(1000)
            resetter?.join(2000)
        } catch (_: InterruptedException) {
        }
        AppLog.i(
            "transcode_test ИТОГ2: вход=$fed (P пропущено до IDR $skippedP), выход=$frames, потеряно транскодером=$dropSum, запросов IDR (onNeedAaKeyFrame)=${out.needKeys.get()}, " +
                "макс. разрыв между кадрами выхода=${out.maxGapMs} мс, эпизодов молчания>${SILENCE_MS / 1000} с=${silentEpisodes.get()}" +
                (if (silentEpisodes.get() > 0) " (!!!!!! макс. ${maxSilentMs.get() / 1000.0} с)" else "") + ", reset-ов=${resets.get()}"
        )
        t.stop()
        synchronized(dump) { dump.close() }
        AppLog.i("transcode_test: выход энкодера записан в ${File(dir, "enc-out.h264")}")
        Thread.sleep(THREAD_CHECK_DELAY_MS)
        val leaked = Thread.getAllStackTraces().keys
            .filter { it.isAlive && (it.name.startsWith("aa-trans-") || it.name.startsWith("aa-gl-relay")) }
            .map { it.name }
        if (leaked.isEmpty()) AppLog.i("transcode_test: нити aa-trans-*/aa-gl-relay завершились")
        else AppLog.i("transcode_test: !!!!!! нити не завершились: ${leaked.joinToString(", ")}")
        ph?.let { AppLog.i("transcode_test: заглушка отрисовала ${it.framesRendered} кадров"); it.stop() }
    }
}
