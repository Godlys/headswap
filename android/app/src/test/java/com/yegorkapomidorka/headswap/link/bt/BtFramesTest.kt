package com.yegorkapomidorka.headswap.link.bt

import com.yegorkapomidorka.headswap.PbWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtFramesTest {
    private fun kv(l: List<Pair<String, Any?>>) = l.toMap()

    @Test
    fun requestFrameIsEightBytesBigEndian() {
        val f = BtMsg.frame(BtMsg.MD_WIRELESS_INFO_REQUEST)
        assertEquals(listOf(0, 0, 0, 0, 0x00, 0x10, 0x00, 0x01), f.map { it.toInt() and 0xFF })
    }

    @Test
    fun parserAssemblesFramesFromChunksAndResyncs() {
        val body = PbWriter().int32(1, 2).int32(2, 5745).toByteArray()
        val a = BtMsg.frame(BtMsg.HU_WIRELESS_INFO, body)
        val b = BtMsg.frame(BtMsg.HU_STATUS, PbWriter().int32(1, 1).toByteArray())
        val stream = byteArrayOf(0x7F) + a + b       // 前面有一个垃圾字节
        val p = BtFrameParser()
        val got = ArrayList<BtFrame>()
        for (i in stream.indices) got += p.feed(byteArrayOf(stream[i]))
        assertEquals(listOf(BtMsg.HU_WIRELESS_INFO, BtMsg.HU_STATUS), got.map { it.type })
        assertEquals(1L, p.skipped)
        val m = kv(BtSummary.wirelessInfo(got[0].body))
        assertEquals("2(p2p)", m["wirelessType"])
        assertEquals(5745, m["wifiFreq"])
    }

    @Test
    fun targetInfoSummaryHasNoSecrets() {
        val body = PbWriter().string(1, "DIRECT-ab-Car").string(2, "x")
            .bytes(3, ByteArray(31)).string(4, "DIRECT-ab-Car,secretpw,5745").toByteArray()
        val l = BtSummary.targetInfo(body)
        val text = l.joinToString(" ") { "${it.first}=${it.second}" }
        assertFalse(text, "secretpw" in text)
        assertFalse(text, "DIRECT-ab" in text)
        val m = kv(l)
        assertEquals(13, m["p2pNameLen"])
        assertEquals(true, m["p2pNameDirect"])
        assertEquals(31, m["scanRecordLen"])
        assertEquals("есть", m["p2pNetInfo"])
        assertEquals(3, m["netParts"])
        assertEquals(5745, m["netFreq"])
    }

    @Test
    fun targetInfoWithoutNetInfo() {
        val m = kv(BtSummary.targetInfo(PbWriter().string(1, "Car").string(2, "").toByteArray()))
        assertEquals("нет", m["p2pNetInfo"])
        assertEquals(-1, m["scanRecordLen"])
        assertTrue(BtMsg.name(0x12345678).startsWith("0x"))
    }

    @Test
    fun p2pTargetFromTargetInfo() {
        val body = PbWriter().string(1, "Mazda").string(4, "DIRECT-ab-Car,secretpw, 5745").toByteArray()
        val t = P2pTarget.parse(body)!!
        assertEquals("Mazda", t.deviceName)
        assertEquals("DIRECT-ab-Car", t.ssid)
        assertEquals("secretpw", t.passphrase)
        assertEquals(5745, t.freqMhz)
        assertTrue(t.canJoinBySsid)
    }

    @Test
    fun p2pTargetWithoutNetInfoFallsBackToPeerName() {
        val t = P2pTarget.parse(PbWriter().string(1, "Mazda").toByteArray())!!
        assertEquals(null, t.ssid)
        assertFalse(t.canJoinBySsid)
        val short = P2pTarget.parse(PbWriter().string(4, "DIRECT-ab-Car,short").toByteArray())!!
        assertFalse(short.canJoinBySsid)
    }

    @Test
    fun wifiIpBodyIsStringField1() {
        val f = com.yegorkapomidorka.headswap.PbReader.parse(BtMsg.wifiIp("192.168.49.20"))!!
        assertEquals("192.168.49.20", com.yegorkapomidorka.headswap.PbReader.str(f, 1))
    }
}
