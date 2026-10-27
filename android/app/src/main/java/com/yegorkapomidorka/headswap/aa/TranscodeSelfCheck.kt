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
 * 无需 AA 和 USB 的转码器自检：`--es cmd transcode_test`。选取最大的 aa-*.h264（来自 AA 的原始 Annex-B，通过 adb push 放入 getExternalFilesDir），
 * 让它经过解码器 -> 编码器约 10 秒（30 fps，中途有 3 秒无输入帧的暂停：编码器必须
 * 重复帧），并记录输入/输出帧、fps、输出的前几个 NAL、延迟。
 *
 * 配置字符串通过 extra `opts` 传入（作为 tuning 交给 AaTranscoder），按子字符串解析。自检自身的令牌
 * （不会传给转码器）：`slowout=NN`、`resetevery=NN`、`burst`、`nopause`、`placeholder`、`phonly`。
 * 其余（`nogl`、`nobase`、`cbr`、`norealtime`、`nolowlat`）原样传给转码器。
 *
 * 与桥接器一样，自检在丢帧（onNeedAaKeyFrame）之后和 resetDecoder() 之后停止喂 P 帧，
 * 改为喂文件中带 SPS/PPS 的最近 IDR。
 *
 * 运行（日志进入 AppLog，`adb logcat -s Headswap` 或应用日志文件；搜索 `transcode_test`）：
 * ```
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test
 * # 向车机缓慢输出 / 桥接器监视器繁忙：onEncoderFrame 睡眠 20 毫秒（编码器 -> GL -> 解码器 -> 输入回路）。
 * # 预期：不停止，输出帧继续（更少），有丢失和 IDR 请求
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=20"
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=80"
 * # 每 700 毫秒从另一线程调用 resetDecoder()（如 PAUSE 后的 START）。预期：解码器在下一个 IDR 重建，输出恢复
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "resetevery=700"
 * # 突发：10 帧无延迟，暂停 330 毫秒（喂帧队列溢出）。预期：有丢失并通过 IDR 恢复，不挂起
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "burst"
 * # 全部组合
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=30 resetevery=900 burst"
 * ```
 * 看门狗：编码器输出在计划暂停之外静默 > 3 秒 -> `!!!!!! 编码器输出静默 N 秒`。
 * 最后输出第二条汇总日志，并在 stop() 后 2 秒检查 `aa-trans-*` / `aa-gl-relay` 线程。
 */
object TranscodeSelfCheck {
    private const val TOTAL_MS = 10_000L
    private const val PAUSE_AT_FRAME = 90          // 30 fps 下约 3 秒后
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

        // 自检自身的令牌；不带它们的字符串传给转码器
        val slowOutMs = Regex("""slowout=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val resetEveryMs = Regex("""resetevery=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val burst = Regex("""\bburst\b""").containsMatchIn(tuning)
        val tcTuning = tuning.replace(Regex("""\s*\b(?:slowout|resetevery)=\d+|\s*\bburst\b"""), "").trim()
        if (slowOutMs > 0 || resetEveryMs > 0 || burst) {
            AppLog.i("transcode_test: режим: slowout=$slowOutMs мс, resetevery=$resetEveryMs мс, burst=$burst; в транскодер tuning='$tcTuning'")
        }

        // 编码器输出，与发往车机时相同（SPS/PPS 仅与第一个 IDR 拼接，如 AaBridge.onEncoderFrame 中）：
        // enc-out.h264 就在旁边，可在 PC 上用 ffmpeg 查看
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
            @Volatile var gotIdr = false               // 同 AaBridge.gotIdr：false -> 不喂 P 帧，等待文件中的 IDR
            @Volatile var lastFrameMs = 0L             // 最后一帧输出的时间（0 - 还没有）
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
                if (slowOutMs > 0) Thread.sleep(slowOutMs)      // 向车机缓慢输出 / 桥接器监视器繁忙
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
        // "placeholder"：并行运行第二个占位编码器，如在真实会话中（VideoSource + 转码器）
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
        var dropSum = 0L                                // 来自 statsLine 的「丢失」计数（每次调用时窗口重置）
        fun stats(): String {
            val s = t.statsLine()
            dropSum += Regex("""потеряно (\d+)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return s
        }
        t.feedConfig(cfg)
        t.requestSyncFrame()
        val t0 = SystemClock.elapsedRealtime()
        var fed = 0
        var skippedP = 0                                // 因等待 IDR 而未喂入的文件 P 帧
        var idx = firstIdr
        var pauseStartMs = -1L
        val pauseEndMs = AtomicLong(-1L)
        var lastStats = t0
        val inPause = AtomicBoolean(false)
        val active = AtomicBoolean(true)                // 喂帧进行期间辅助线程工作
        val resets = AtomicInteger()
        val resetGen = AtomicInteger()                  // reset 计数器：喂帧线程据此得知需要等待 IDR
        val silentEpisodes = AtomicInteger()
        val maxSilentMs = AtomicLong()

        // 看门狗：编码器输出在计划暂停之外静默 > 3 秒
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

        // resetevery：从另一线程调用 resetDecoder()，如桥接器在 PAUSE 后 START 时
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
            if (gen != seenGen) {                       // 发生过 reset：在文件中的下一个 IDR 之前不喂 P 帧
                seenGen = gen
                out.gotIdr = false
            }
            if (idx >= aus.size) idx = firstIdr          // 文件结束：从 IDR 开始循环
            val au = aus[idx++]
            if (out.gotIdr) {
                t.feedFrame(au.data, fed * 33_333L)
                fed++
            } else if (au.idr) {
                // 启动/丢帧/reset 后的第一个 IDR：如果帧本身没有 SPS/PPS（reset 后转码器已忘记它们），则使用 config 中的 SPS/PPS
                val types = AnnexB.nalTypes(au.data)
                val payload = if (AnnexB.startsWithSps(au.data) && 8 in types) au.data else AnnexB.glue(cfg, au.data)
                out.gotIdr = true                        // 在 feedFrame 之前：它可能自己调用 onNeedAaKeyFrame
                t.feedFrame(payload, fed * 33_333L)
                fed++
                t.requestSyncFrame()
            } else {
                skippedP++
                continue                                 // AA 转储中约每分钟一个 IDR：无延迟地跳到它，如桥接器通过 socket 一样
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
