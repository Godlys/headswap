package com.yegorkapomidorka.headswap.aa

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 将 48000 Hz 的 16 位 LE 立体声 PCM 重采样到 [outRate]，并在多次调用之间保持状态。
 *
 * 有理数比 L/M（44100 = 147/160，32000 = 2/3，24000 = 1/2，22050 = 147/320），多相 FIR：
 * 低通滤波器原型在 48000·L Hz 采样率下设计，每个输出帧取一组 [TAPS_PER_PHASE]
 * 个系数中的某一相，插入的零不参与计算。低通滤波器使用 Kaiser 窗（[KAISER_BETA]），截止点（[CUTOFF_RATIO] ×
 * 输出频率，-6 dB 电平）低于输出的奈奎斯特频率。系数在构造函数中一次性计算，
 * 每相增益归一化为 1（直流分量通过时无电平失真）。
 * 滤波器历史与相位在多次调用之间保持，因此数据包边界无缝：结果与输入的切块方式无关。
 * 输入为 4 字节一帧；不完整的尾部（大小不是 4 的倍数）会被丢弃。
 * 48000 Hz 时不需要本类：AaBridge 在这种情况下原样透传 PCM。
 */
class MusicResampler(val outRate: Int) {
    private val up: Int                       // L
    private val down: Int                     // M
    private val coef: FloatArray              // [phase * TAPS_PER_PHASE + j]：帧 x[i0 - j] 的权重
    private val hist = ShortArray(HIST * 2)   // 上次调用保留的最后 HIST 个输入帧 (L, R)
    private var work = ShortArray(0)          // 历史和新的输入依次存放；按需扩容
    private var pos = 0                       // 下一个输出在 48000·L Hz 网格上的位置，相对新输入起点

    init {
        require(outRate in 1 until IN_RATE) { "outRate=$outRate" }
        val g = gcd(IN_RATE, outRate)
        up = outRate / g
        down = IN_RATE / g
        coef = design(up, outRate)
    }

    fun reset() {
        hist.fill(0)
        pos = 0
    }

    fun process(pcm: ByteArray): ByteArray {
        val n = pcm.size / 4
        if (n == 0) return ByteArray(0)
        if (work.size < (HIST + n) * 2) work = ShortArray((HIST + n) * 2)
        val x = work
        System.arraycopy(hist, 0, x, 0, HIST * 2)
        var p = 0
        var q = HIST * 2
        while (p < n * 4) {
            x[q++] = ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort()
            x[q++] = ((pcm[p + 2].toInt() and 0xFF) or (pcm[p + 3].toInt() shl 8)).toShort()
            p += 4
        }
        val total = n.toLong() * up
        val outFrames = if (total > pos) ((total - pos - 1) / down + 1).toInt() else 0
        val out = ByteArray(outFrames * 4)
        val c = coef
        var u = pos
        var o = 0
        for (k in 0 until outFrames) {
            val i0 = u / up                       // 影响输出的最后一个输入帧
            val cb = (u - i0 * up) * TAPS_PER_PHASE
            var base = (HIST + i0) * 2            // 卷积沿时间向后进行
            var l = 0f
            var r = 0f
            for (j in 0 until TAPS_PER_PHASE) {
                val h = c[cb + j]
                l += h * x[base]
                r += h * x[base + 1]
                base -= 2
            }
            val sl = sat(l)
            val sr = sat(r)
            out[o++] = sl.toByte()
            out[o++] = (sl shr 8).toByte()
            out[o++] = sr.toByte()
            out[o++] = (sr shr 8).toByte()
            u += down
        }
        pos = (u - total).toInt()
        System.arraycopy(x, n * 2, hist, 0, HIST * 2)
        return out
    }

    private fun sat(v: Float): Int {
        val i = Math.round(v)
        return if (i > 32767) 32767 else if (i < -32768) -32768 else i
    }

    companion object {
        const val IN_RATE = 48000
        const val TAPS_PER_PHASE = 32
        const val CUTOFF_RATIO = 0.42
        const val KAISER_BETA = 6.0
        private const val HIST = TAPS_PER_PHASE - 1

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** 48000·L Hz 采样率上的低通滤波器原型，长度 L·TAPS_PER_PHASE，按 L 相展开；每相之和 = 1。 */
        private fun design(up: Int, outRate: Int): FloatArray {
            val len = up * TAPS_PER_PHASE
            val fc = CUTOFF_RATIO * outRate / (IN_RATE.toDouble() * up)    // 相对原型的采样率
            val mid = (len - 1) / 2.0
            val h = DoubleArray(len)
            for (j in 0 until len) {
                val t = j - mid
                val s = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
                val u = t / mid
                h[j] = s * bessel0(KAISER_BETA * sqrt(max(0.0, 1 - u * u))) / bessel0(KAISER_BETA)
            }
            val out = FloatArray(len)
            for (ph in 0 until up) {
                var sum = 0.0
                for (j in 0 until TAPS_PER_PHASE) sum += h[ph + up * j]
                for (j in 0 until TAPS_PER_PHASE) out[ph * TAPS_PER_PHASE + j] = (h[ph + up * j] / sum).toFloat()
            }
            return out
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 50) {
                val f = x / (2 * k)
                term *= f * f
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}
