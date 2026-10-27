package com.yegorkapomidorka.headswap.aa

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** 均衡器设置：7 个频段（dB，EQ_FREQS）和前置增益。不可变：桥接器按 equals 比较。 */
data class EqSettings(val bands: List<Int> = FLAT_BANDS, val preamp: Int = 0) {
    /** 全部为零：桥接器逐位透传 PCM。 */
    val isFlat: Boolean get() = preamp == 0 && bands.all { it == 0 }

    /** 频段数恰好为 EQ_FREQS.size，值在允许范围内。 */
    fun normalized(): EqSettings = EqSettings(
        List(EQ_FREQS.size) { (bands.getOrNull(it) ?: 0).coerceIn(EQ_BAND_MIN, EQ_BAND_MAX) },
        preamp.coerceIn(EQ_PREAMP_MIN, EQ_PREAMP_MAX),
    )

    fun bandsCsv(): String = bands.joinToString(",")

    companion object {
        val FLAT_BANDS: List<Int> = List(7) { 0 }

        /** 来自 prefs 的 CSV；垃圾数据及缺失的频段 = 0。 */
        fun parseBands(csv: String?): List<Int> {
            val parts = csv?.split(',').orEmpty()
            return List(EQ_FREQS.size) { parts.getOrNull(it)?.trim()?.toIntOrNull() ?: 0 }
        }
    }
}

    /** 各频段的中心频率，Hz。 */
val EQ_FREQS = intArrayOf(63, 160, 400, 1000, 2500, 6300, 16000)
const val EQ_BAND_MIN = -12
const val EQ_BAND_MAX = 12
const val EQ_PREAMP_MIN = -12
const val EQ_PREAMP_MAX = 6

    /** 预设；「响亮」类预设的 preamp 为负值，避免增益顶到限幅器。CUSTOM - 用户自定义值。 */
enum class EqPreset(val key: String, val settings: EqSettings?) {
    FLAT("flat", EqSettings()),
    BASS("bass", EqSettings(listOf(6, 4, 1, 0, 0, 0, 0), -5)),
    VOCAL("vocal", EqSettings(listOf(-2, -1, 1, 3, 3, 1, 0), -3)),
    /** 160 Hz 冲击力和 2.5k 吉他临场感，无超低音和「空气感」。 */
    ROCK("rock", EqSettings(listOf(3, 4, 0, -1, 3, 2, 0), -5)),

    /** 深「微笑」曲线：超低音和 16k 高频，中频凹陷。 */
    ELECTRONIC("electronic", EqSettings(listOf(7, 3, -2, -3, 0, 3, 6), -7)),
    CUSTOM("custom", null);

    companion object {
        fun parse(key: String?): EqPreset = values().firstOrNull { it.key == key } ?: FLAT
    }
}

    /** 桥接器当前的 EQ 设置：UI 通过 AaPrefs 写入，桥接器在每个缓冲上读取（同一进程）。 */
object EqLive {
    @Volatile var settings: EqSettings = EqSettings()
}

/**
 * 7 个 peaking-biquad（RBJ cookbook，Q=1）+ 前置增益 + −1 dBFS 以上的软限幅器。
 * 就地处理交织的 PCM16 LE；每个声道有独立的滤波器状态。
 * 纯 Kotlin，无 Android API。
 */
class Equalizer(sampleRate: Int = 48000, channels: Int = 2) {
    private var rate = sampleRate
    private var chans = channels
    private var settings = EqSettings()
    private var preGain = 1.0

    /** 实际生效的前置增益：在一个缓冲内线性趋近 preGain，避免切换预设时产生爆音。 */
    private var curGain = 1.0
    private var rampStep = 0.0
    private var rampLeft = 0

    /** 生效的频段（增益 ≠ 0 且低于奈奎斯特频率）：索引和归一化系数。 */
    private var active = IntArray(0)
    private val b0 = DoubleArray(BANDS)
    private val b1 = DoubleArray(BANDS)
    private val b2 = DoubleArray(BANDS)
    private val a1 = DoubleArray(BANDS)
    private val a2 = DoubleArray(BANDS)

    /** TDF-II 状态：[声道 * BANDS + 频段]。 */
    private var z1 = DoubleArray(BANDS * chans)
    private var z2 = DoubleArray(BANDS * chans)
    private var bypassed = true

    /** 格式变更会重置状态并重新计算系数。 */
    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate == rate && channels == chans) return
        rate = sampleRate
        chans = channels
        z1 = DoubleArray(BANDS * chans)
        z2 = DoubleArray(BANDS * chans)
        recompute()
    }

    /** 新设置：重新计算系数但不重置状态（滑动滑块时无爆音）。 */
    fun update(s: EqSettings) {
        val n = s.normalized()
        if (n == settings) return
        settings = n
        recompute()
    }

    val isBypass: Boolean get() = settings.isFlat

    private fun recompute() {
        preGain = 10.0.pow(settings.preamp / 20.0)
        rampLeft = maxOf(1, rate / 50)
        rampStep = (preGain - curGain) / rampLeft
        val idx = ArrayList<Int>()
        for (i in 0 until BANDS) {
            val g = settings.bands[i]
            val f = EQ_FREQS[i]
            if (g == 0 || f >= rate * 0.45) continue
            val a = 10.0.pow(g / 40.0)
            val w0 = 2 * PI * f / rate
            val alpha = sin(w0) / (2 * Q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            b0[i] = (1 + alpha * a) / a0
            b1[i] = -2 * cw / a0
            b2[i] = (1 - alpha * a) / a0
            a1[i] = -2 * cw / a0
            a2[i] = (1 - alpha / a) / a0
            idx.add(i)
        }
        // 变为 0 dB 的频段会退出链路：清零其状态，避免恢复时残留尾音
        for (i in 0 until BANDS) if (i !in idx) for (c in 0 until chans) {
            z1[c * BANDS + i] = 0.0
            z2[c * BANDS + i] = 0.0
        }
        active = idx.toIntArray()
    }

    /** 就地处理交织的 PCM16 LE；不完整的尾部帧不做处理。 */
    fun process(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        if (enterBypass()) return
        val frames = length / (2 * chans)
        var p = offset
        for (f in 0 until frames) {
            rampFrame()
            for (c in 0 until chans) {
                val s = ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort()
                val out = sample(s, c)
                pcm[p] = out.toByte()
                pcm[p + 1] = (out shr 8).toByte()
                p += 2
            }
        }
    }

    /** 与上面相同，针对 ShortArray（交织）。 */
    fun process(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset) {
        if (enterBypass()) return
        val frames = length / chans
        var p = offset
        for (f in 0 until frames) {
            rampFrame()
            for (c in 0 until chans) {
                pcm[p] = sample(pcm[p], c).toShort()
                p++
            }
        }
    }

    /** 每帧 curGain 向 preGain 前进一步，固定长度约 20 毫秒（与缓冲切分方式无关）。 */
    private fun rampFrame() {
        if (rampLeft <= 0) return
        curGain += rampStep
        if (--rampLeft == 0) curGain = preGain
    }

    /** true = 无需处理。退出 bypass 时状态干净：旧尾音不会以爆音形式出现。 */
    private fun enterBypass(): Boolean {
        if (settings.isFlat) {
            bypassed = true
            curGain = 1.0
            rampLeft = 0
            return true
        }
        if (bypassed) {
            z1.fill(0.0)
            z2.fill(0.0)
            bypassed = false
        }
        return false
    }

    private fun sample(s: Short, c: Int): Int {
        var x = s / 32768.0 * curGain
        val base = c * BANDS
        for (i in active) {
            val k = base + i
            val y = b0[i] * x + z1[k]
            z1[k] = b1[i] * x - a1[i] * y + z2[k]
            z2[k] = b2[i] * x - a2[i] * y
            x = y
        }
        return (softLimit(x) * 32767.0).roundToInt().coerceIn(-32768, 32767)
    }

    companion object {
        const val BANDS = 7
        const val Q = 1.0

        /** 拐点 −1 dBFS：以下为线性，以上为 tanh 压缩，渐近线 1.0（不会溢出 short）。 */
        private const val KNEE = 0.891

        fun softLimit(x: Double): Double {
            val ax = abs(x)
            if (ax <= KNEE) return x
            val y = KNEE + (1 - KNEE) * tanh((ax - KNEE) / (1 - KNEE))
            return if (x < 0) -y else y
        }
    }
}
