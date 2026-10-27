package com.yegorkapomidorka.headswap.aa

import java.io.ByteArrayOutputStream

/**
 * AAP 帧（移植自 pc-headunit/aap/framing.py）：
 *   [chan][flags][len BE16]；FIRST 且非 LAST 时：另有 [total BE32]；消息体 = TLS-record（握手之后）
 *   或 [type BE16][data]（握手之前）。纯 JVM 逻辑。
 */
object AapFraming {
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    const val FLAGS_PLAIN_BULK = FLAG_FIRST or FLAG_LAST                    // 0x03
    const val FLAGS_ENC_BULK = FLAG_ENCRYPTED or FLAG_FIRST or FLAG_LAST     // 0x0b
    const val FLAGS_ENC_BULK_CONTROL = FLAGS_ENC_BULK or FLAG_CONTROL        // 0x0f
    const val FLAGS_ENC_FIRST = FLAG_ENCRYPTED or FLAG_FIRST                 // 0x09
    const val FLAGS_ENC_MIDDLE = FLAG_ENCRYPTED                              // 0x08
    const val FLAGS_ENC_LAST = FLAG_ENCRYPTED or FLAG_LAST                   // 0x0a

    const val CHANNEL_CONTROL = 0

    /** 单个 TLS-record 最多携带 16384 字节明文；更大的消息按此大小切成帧。 */
    const val MAX_FRAGMENT = 0x4000
    const val MAX_BODY = 0xFFFF

    /** 非 LAST 的 FIRST 帧携带 4 字节的 total。 */
    fun hasTotalSize(flags: Int) = (flags and (FLAG_FIRST or FLAG_LAST)) == FLAG_FIRST

    fun buildFrame(chan: Int, flags: Int, body: ByteArray, total: Int? = null): ByteArray {
        require(body.size <= MAX_BODY) { "тело кадра ${body.size} Б не лезет в 16 бит" }
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(chan)
        out.write(flags)
        out.write(body.size ushr 8)
        out.write(body.size and 0xFF)
        if (hasTotalSize(flags)) {
            requireNotNull(total) { "FIRST-фрагменту нужен total" }
            out.write(total ushr 24)
            out.write(total ushr 16)
            out.write(total ushr 8)
            out.write(total)
        }
        out.write(body)
        return out.toByteArray()
    }

    /** 未加密消息：[chan][flags][len = 2 + data][type BE16][data]。 */
    fun buildPlain(chan: Int, msgType: Int, data: ByteArray = ByteArray(0), flags: Int = FLAGS_PLAIN_BULK): ByteArray =
        buildFrame(chan, flags, typed(msgType, data))

    fun typed(msgType: Int, data: ByteArray): ByteArray {
        val b = ByteArray(2 + data.size)
        b[0] = (msgType ushr 8).toByte()
        b[1] = msgType.toByte()
        System.arraycopy(data, 0, b, 2, data.size)
        return b
    }

    /** headunit-revived 的 AapMessage.flags()：非 control 通道上的 control 类型 1..26 会加上 0x04。 */
    fun controlFlagNeeded(chan: Int, msgType: Int) = chan != CHANNEL_CONTROL && msgType in 1..26

    fun messageFlags(chan: Int, msgType: Int) =
        if (controlFlagNeeded(chan, msgType)) FLAGS_ENC_BULK_CONTROL else FLAGS_ENC_BULK

    class Fragment(val flags: Int, val chunk: ByteArray, val total: Int?)

    /**
     * 将单个消息拆成帧。能装下时 - 一个 bulk 帧（total 为 null）。否则拆为 FIRST（0x09，带 total）、
     * MIDDLE（0x08）* 和 LAST（0x0a）。每个 chunk 按顺序单独加密。
     */
    fun splitMessage(plaintext: ByteArray, chan: Int, msgType: Int, maxFragment: Int = MAX_FRAGMENT): List<Fragment> {
        if (plaintext.size <= maxFragment) return listOf(Fragment(messageFlags(chan, msgType), plaintext, null))
        val total = plaintext.size
        val res = ArrayList<Fragment>()
        var pos = 0
        while (pos < total) {
            val end = minOf(pos + maxFragment, total)
            val chunk = plaintext.copyOfRange(pos, end)
            res.add(
                when {
                    pos == 0 -> Fragment(FLAGS_ENC_FIRST, chunk, total)
                    end >= total -> Fragment(FLAGS_ENC_LAST, chunk, null)
                    else -> Fragment(FLAGS_ENC_MIDDLE, chunk, null)
                }
            )
            pos = end
        }
        return res
    }

    class Frame(val chan: Int, val flags: Int, val body: ByteArray, val total: Int? = null) {
        val encrypted get() = (flags and FLAG_ENCRYPTED) != 0
    }

    /** 字节流 → 帧。TCP 不提供边界，feed() 接受任意大小的数据块。 */
    class Parser {
        private var buf = ByteArray(0)
        private var len = 0

        val pending get() = len

        fun feed(data: ByteArray, off: Int = 0, n: Int = data.size - off): List<Frame> {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n, 4096))
            System.arraycopy(data, off, buf, len, n)
            len += n
            val frames = ArrayList<Frame>()
            var p = 0
            while (true) {
                if (len - p < 4) break
                val chan = buf[p].toInt() and 0xFF
                val flags = buf[p + 1].toInt() and 0xFF
                val bodyLen = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
                val head = if (hasTotalSize(flags)) 8 else 4
                if (len - p < head + bodyLen) break
                val total = if (head == 8) {
                    ((buf[p + 4].toInt() and 0xFF) shl 24) or ((buf[p + 5].toInt() and 0xFF) shl 16) or
                        ((buf[p + 6].toInt() and 0xFF) shl 8) or (buf[p + 7].toInt() and 0xFF)
                } else null
                frames.add(Frame(chan, flags, buf.copyOfRange(p + head, p + head + bodyLen), total))
                p += head + bodyLen
            }
            if (p > 0) {
                System.arraycopy(buf, p, buf, 0, len - p)
                len -= p
            }
            return frames
        }
    }

    /**
     * 按通道将解密后的 FIRST/MIDDLE/LAST 消息体拼接成完整消息。push() 返回完整的
     * plaintext（[type BE16][payload]），或在该序列未完成时返回 null。顺序异常会记录到 warn：
     * 孤立的 MIDDLE/LAST 被丢弃，在未完成的序列之上到达的 FIRST 会替换旧序列，
     * 大小与 total 不符时按原样返回。
     */
    class Reassembler(private val warn: (String) -> Unit = {}) {
        private val runs = HashMap<Int, ByteArrayOutputStream>()
        private val totals = HashMap<Int, Int?>()

        fun push(chan: Int, flags: Int, plaintext: ByteArray, total: Int? = null): ByteArray? {
            val first = (flags and FLAG_FIRST) != 0
            val last = (flags and FLAG_LAST) != 0
            if (first && last) {
                if (runs.containsKey(chan)) {
                    warn("ch$chan: bulk-кадр при открытой серии фрагментов, серия сброшена")
                    drop(chan)
                }
                return plaintext
            }
            if (first) {
                if (runs.containsKey(chan)) warn("ch$chan: FIRST при открытой серии, старая сброшена")
                runs[chan] = ByteArrayOutputStream().also { it.write(plaintext) }
                totals[chan] = total
                return null
            }
            val run = runs[chan]
            if (run == null) {
                warn("ch$chan: ${if (last) "LAST" else "MIDDLE"} без FIRST, отброшен")
                return null
            }
            run.write(plaintext)
            if (!last) return null
            val declared = totals[chan]
            drop(chan)
            val res = run.toByteArray()
            if (declared != null && declared != res.size) warn("ch$chan: склеено ${res.size} Б, а FIRST заявил $declared Б")
            return res
        }

        private fun drop(chan: Int) {
            runs.remove(chan)
            totals.remove(chan)
        }

        fun openRuns(): List<Int> = runs.keys.sorted()
    }
}
