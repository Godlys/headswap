package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private val rate = 48000

    /** 立体声正弦波 PCM16 LE:L 和 R 相同。 */
    private fun sine(freq: Double, amp: Double, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (amp * 32767 * sin(2 * PI * freq * i / rate)).roundToInt()
            for (c in 0 until 2) {
                val p = i * 4 + c * 2
                out[p] = v.toByte()
                out[p + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, frame: Int, ch: Int): Int {
        val p = frame * 4 + ch * 2
        return ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort().toInt()
    }

    /** 左声道 RMS,跳过开头的瞬态过程。 */
    private fun rms(pcm: ByteArray, skipFrames: Int): Double {
        val frames = pcm.size / 4
        var sum = 0.0
        for (i in skipFrames until frames) {
            val s = sampleAt(pcm, i, 0).toDouble()
            sum += s * s
        }
        return sqrt(sum / (frames - skipFrames))
    }

    private fun gainDb(freq: Double, settings: EqSettings, amp: Double = 0.1): Double {
        val input = sine(freq, amp, rate)      // 1 秒
        val out = input.copyOf()
        Equalizer(rate, 2).apply { update(settings) }.process(out)
        val skip = rate / 4
        return 20 * log10(rms(out, skip) / rms(input, skip))
    }

    private fun band(i: Int, db: Int) = EqSettings(List(7) { if (it == i) db else 0 })

    @Test
    fun flatIsBitExact() {
        val input = sine(440.0, 0.9, 4800)
        val out = input.copyOf()
        Equalizer(rate, 2).apply { update(EqSettings()) }.process(out)
        assertArrayEquals(input, out)
    }

    @Test
    fun boost63() {
        val s = band(0, 6)
        val g63 = gainDb(63.0, s)
        val g1k = gainDb(1000.0, s)
        assertTrue("63 Гц: $g63 дБ", g63 in 5.0..7.0)
        assertTrue("1 кГц: $g1k дБ", g1k in -0.5..0.5)
    }

    @Test
    fun cutAndPreamp() {
        assertTrue(gainDb(1000.0, band(3, -6)) in -7.0..-5.0)
        val pre = gainDb(1000.0, EqSettings(preamp = -6))
        assertTrue("preamp: $pre", pre in -6.2..-5.8)
    }

    @Test
    fun limiterNoOverflow() {
        val input = sine(63.0, 1.0, rate / 2)
        val out = input.copyOf()
        Equalizer(rate, 2).apply { update(EqSettings(listOf(12, 12, 12, 0, 0, 0, 0), 6)) }.process(out)
        var peak = 0
        var prev = sampleAt(out, 0, 0)
        var maxJump = 0
        for (i in 0 until out.size / 4) {
            val v = sampleAt(out, i, 0)
            peak = maxOf(peak, kotlin.math.abs(v))
            maxJump = maxOf(maxJump, kotlin.math.abs(v - prev))
            prev = v
        }
        assertTrue("peak=$peak", peak <= 32767)
        assertTrue("лимитер включился, peak=$peak", peak > 29000)
        // short 溢出会产生符号跳变(~65000);零附近 ~+30 dB 的增益本来就陡(~3000)
        assertTrue("скачок $maxJump", maxJump < 16000)
        assertEquals(1.0, Equalizer.softLimit(100.0), 1e-6)
        assertEquals(0.5, Equalizer.softLimit(0.5), 0.0)
    }

    @Test
    fun continuityAcrossBuffers() {
        val s = EqSettings(listOf(6, 4, 1, 0, -2, 3, 4), -5)
        val input = sine(200.0, 0.5, 9600)
        val whole = input.copyOf()
        Equalizer(rate, 2).apply { update(s) }.process(whole)

        val split = input.copyOf()
        val eq = Equalizer(rate, 2).apply { update(s) }
        val cut = 1237 * 4
        eq.process(split, 0, cut)
        eq.process(split, cut, split.size - cut)
        assertArrayEquals(whole, split)
    }

    @Test
    fun liveUpdateKeepsState() {
        // 运行时切换增益不会重置滤波器:衔接处没有跳变
        val input = sine(63.0, 0.3, 9600)
        val out = input.copyOf()
        val eq = Equalizer(rate, 2).apply { update(band(0, 6)) }
        eq.process(out, 0, 4800 * 4)
        eq.update(band(0, 7))
        eq.process(out, 4800 * 4, 4800 * 4)
        val jump = kotlin.math.abs(sampleAt(out, 4800, 0) - sampleAt(out, 4799, 0))
        assertTrue("скачок $jump", jump < 600)
    }

    @Test
    fun preampChangeIsRamped() {
        // 运行时 −12 dB preamp:没有平滑过渡的话,衔接处会有约 3/4 幅度的跳变
        val input = sine(63.0, 0.8, 9600)
        val out = input.copyOf()
        val eq = Equalizer(rate, 2).apply { update(band(6, 1)) }
        eq.process(out, 0, 4800 * 4)
        eq.update(EqSettings(List(7) { if (it == 6) 1 else 0 }, -12))
        eq.process(out, 4800 * 4, 4800 * 4)
        var maxJump = 0
        for (i in 4790 until 4900) maxJump = maxOf(maxJump, kotlin.math.abs(sampleAt(out, i, 0) - sampleAt(out, i - 1, 0)))
        assertTrue("скачок $maxJump", maxJump < 400)
    }

    @Test
    fun channelsIndependent() {
        // L — 正弦波,R — 静音:R 保持为零
        val frames = 4800
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (0.5 * 32767 * sin(2 * PI * 100.0 * i / rate)).roundToInt()
            pcm[i * 4] = v.toByte()
            pcm[i * 4 + 1] = (v shr 8).toByte()
        }
        Equalizer(rate, 2).apply { update(band(1, 9)) }.process(pcm)
        for (i in 0 until frames) assertEquals(0, sampleAt(pcm, i, 1))
    }

    @Test
    fun settingsParsing() {
        assertEquals(listOf(1, -2, 0, 0, 0, 0, 0), EqSettings.parseBands("1,-2,x"))
        assertEquals(EqSettings.FLAT_BANDS, EqSettings.parseBands(null))
        val n = EqSettings(listOf(20, -20), 10).normalized()
        assertEquals(listOf(12, -12, 0, 0, 0, 0, 0), n.bands)
        assertEquals(6, n.preamp)
        assertEquals(EqPreset.FLAT, EqPreset.parse("nope"))
        for (p in EqPreset.values()) p.settings?.let { assertEquals(it, it.normalized()) }
    }
}
