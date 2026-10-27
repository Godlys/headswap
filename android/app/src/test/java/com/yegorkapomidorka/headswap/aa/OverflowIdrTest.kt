package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarLife 队列拒绝时向编码器请求 IDR:每 1.5 秒最多一次,流从 IDR 继续。 */
class OverflowIdrTest {
    private val aaCfg = Golden.hex("00000001 67 42c01f da0140") + Golden.hex("00000001 68 ce3c80")
    private val aaIdr = Golden.hex("00000001 65") + ByteArray(100) { (it + 1).toByte() }
    private val encCfg = Golden.hex("00000001 67 42001f ab 00000001 68 ce06e2")
    private val encIdr = Golden.hex("00000001 65") + ByteArray(200) { (it * 3).toByte() }
    private val encP = Golden.hex("00000001 41") + ByteArray(40) { 0x33 }
    private var now = 0L
    private val link = TranscodeLink()
    private val core = AaBridgeCore({ _, _ -> }, { now }).also {
        it.transcodeEnabled = true
        it.attach(link)
    }
    private val tr get() = link.transcoder!!

    private fun startStream() {
        core.onCodecConfig(aaCfg)
        core.onCarlifeStart()
        core.onEncoderConfig(encCfg)
        core.onFrame(aaIdr, 1)
        core.onEncoderFrame(encIdr, true)
    }

    @Test
    fun overflowRequestsIdrNoMoreThanOncePerInterval() {
        startStream()
        val base = tr.syncs.get()
        link.accept = false
        core.onEncoderFrame(encP, false)                       // P 被拒：请求 IDR
        assertEquals(base + 1, tr.syncs.get())
        link.accept = true
        core.onEncoderFrame(encP, false)                       // IDR 之前的 P 帧被丢弃，不发起请求
        link.accept = false
        now += 1000
        core.onEncoderFrame(encIdr, true)                      // IDR 也被拒绝，但 1.5 秒内不重复请求
        assertEquals(base + 1, tr.syncs.get())
        now += 600
        core.onEncoderFrame(encIdr, true)
        assertEquals(base + 2, tr.syncs.get())
        link.accept = true
        val sent = link.sent.size
        core.onEncoderFrame(encP, false)                       // 流只能从 IDR 恢复
        assertEquals(sent, link.sent.size)
        core.onEncoderFrame(encIdr, true)
        assertEquals(sent + 1, link.sent.size)
        assertTrue(AnnexB.nalTypes(link.sent.last()).contains(5))
        core.onEncoderFrame(encP, false)
        assertEquals(sent + 2, link.sent.size)
    }
}
