package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** golden.txt 参照样本由 pc-headunit（aap/messages.py、aap/framing.py）基于 Python protobuf 生成。 */
object Golden {
    private val map: Map<String, ByteArray> by lazy {
        val text = Golden::class.java.getResourceAsStream("/aa/golden.txt")!!.readBytes().toString(Charsets.UTF_8)
        text.lines().filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            k.trim() to hex(v.trim())
        }
    }

    fun hex(s: String): ByteArray {
        val t = s.replace(" ", "")
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    operator fun get(name: String): ByteArray = map[name] ?: error("нет эталона $name")
}

class AapFramingTest {
    @Test
    fun parserBulkAndExtendedHeaderAnySlicing() {
        val bulk = F.buildFrame(2, 0x0B, "abc".toByteArray())
        val first = F.buildFrame(2, 0x09, ByteArray(5) { 'X'.code.toByte() }, total = 12)
        val last = F.buildFrame(2, 0x0A, ByteArray(7) { 'Y'.code.toByte() })
        assertArrayEquals(byteArrayOf(2, 0x09, 0, 5, 0, 0, 0, 12), first.copyOfRange(0, 8))     // 4 字节的 total
        val stream = bulk + first + last
        for (step in listOf(1, 2, 3, 7, stream.size)) {
            val p = F.Parser()
            val frames = ArrayList<F.Frame>()
            var i = 0
            while (i < stream.size) {
                val n = minOf(step, stream.size - i)
                frames += p.feed(stream, i, n)
                i += n
            }
            assertEquals("step $step", 3, frames.size)
            assertEquals(listOf(2 to 0x0B, 2 to 0x09, 2 to 0x0A), frames.map { it.chan to it.flags })
            assertEquals("abc", String(frames[0].body))
            assertEquals(null, frames[0].total)
            assertEquals(12, frames[1].total)
            assertEquals("XXXXX", String(frames[1].body))
            assertEquals(null, frames[2].total)
            assertEquals("YYYYYYY", String(frames[2].body))
            assertEquals(0, p.pending)
        }
    }

    @Test
    fun buildFrameFirstNeedsTotal() {
        try {
            F.buildFrame(2, 0x09, byteArrayOf(1))
            error("должно упасть")
        } catch (_: NullPointerException) {
        } catch (_: IllegalArgumentException) {
        }
        // FIRST|LAST (0x0b) - total 不写入
        assertEquals(4 + 1, F.buildFrame(2, 0x0B, byteArrayOf(1)).size)
    }

    @Test
    fun flagsControlOnNonControlChannel() {
        assertEquals(0x0B, F.messageFlags(0, M.SERVICE_DISCOVERY_RESPONSE))
        assertEquals(0x0F, F.messageFlags(2, M.CHANNEL_OPEN_RESPONSE))        // ch2 上的 control 类型 8
        assertEquals(0x0B, F.messageFlags(2, M.MEDIA_CONFIG))                 // 0x8003 - 非 control 类型
        assertEquals(0x0B, F.messageFlags(1, M.SENSOR_EVENT))
    }

    @Test
    fun splitMessageFragmentFlagsAndReassembly() {
        val plaintext = F.typed(M.MEDIA_DATA, ByteArray(256 * 200) { (it % 256).toByte() })          // 51202 字节
        val parts = F.splitMessage(plaintext, M.CH_VIDEO, M.MEDIA_DATA, 0x4000)
        assertEquals(listOf(0x09, 0x08, 0x08, 0x0A), parts.map { it.flags })
        assertEquals(plaintext.size, parts[0].total)
        assertTrue(parts.drop(1).all { it.total == null })
        val joined = parts.fold(ByteArray(0)) { a, p -> a + p.chunk }
        assertArrayEquals(plaintext, joined)
        val warnings = ArrayList<String>()
        val r = F.Reassembler { warnings.add(it) }
        var out: ByteArray? = null
        for (p in parts) out = r.push(M.CH_VIDEO, p.flags, p.chunk, p.total)
        assertArrayEquals(plaintext, out)
        assertTrue(warnings.isEmpty() && r.openRuns().isEmpty())
        val one = F.splitMessage(byteArrayOf(0, 0, 'h'.code.toByte(), 'i'.code.toByte()), 2, 0)
        assertEquals(1, one.size)
        assertEquals(0x0B, one[0].flags)
        assertNull(one[0].total)
    }

    @Test
    fun splitMessageExactMultipleOfFragment() {
        val plaintext = ByteArray(0x8000) { 1 }
        val parts = F.splitMessage(plaintext, 2, 0)
        assertEquals(listOf(0x09, 0x0A), parts.map { it.flags })
    }

    @Test
    fun reassemblerInterleavedChannelsAndAnomalies() {
        val w = ArrayList<String>()
        val r = F.Reassembler { w.add(it) }
        fun b(s: String) = s.toByteArray()
        assertNull(r.push(2, 0x09, b("aa"), 6))
        assertArrayEquals(b("bulk"), r.push(5, 0x0B, b("bulk")))           // 其他通道不受影响
        assertNull(r.push(2, 0x08, b("bb")))
        assertArrayEquals(b("aabbcc"), r.push(2, 0x0A, b("cc")))
        assertTrue(w.isEmpty())
        assertNull(r.push(2, 0x0A, b("zz")))
        assertTrue(w.last().contains("без FIRST"))
        assertNull(r.push(2, 0x09, b("aa"), 4))
        assertNull(r.push(2, 0x09, b("dd"), 4))
        assertTrue(w.last().contains("старая сброшена"))
        assertArrayEquals(b("ddee"), r.push(2, 0x0A, b("ee"), null))
        assertNull(r.push(2, 0x09, b("a"), 99))
        assertArrayEquals(b("ab"), r.push(2, 0x0A, b("b")))
        assertTrue(w.last().contains("99"))
    }
}

class AapMessagesTest {
    private fun eq(name: String, actual: ByteArray) = assertArrayEquals("эталон $name", Golden[name], actual)

    @Test
    fun versionRequestFrame() {
        eq("version_request", F.buildFrame(0, F.FLAGS_PLAIN_BULK, M.versionRequestBody()))
        assertArrayEquals(Golden.hex("00 03 00 06 00 01 00 01 00 02"), F.buildFrame(0, F.FLAGS_PLAIN_BULK, M.versionRequestBody()))
    }

    @Test
    fun authCompleteFrame() {
        eq("auth_complete", F.buildPlain(0, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
        assertArrayEquals(Golden.hex("00 03 00 04 00 04 08 00"), F.buildPlain(0, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
    }

    @Test
    fun serviceDiscoveryResponseDefault() {
        val o = M.serviceDiscoveryResponse()
        assertEquals(0, o.chan)
        assertEquals(6, o.type)
        eq("sdr_default", o.payload)
        // 手工编码的 VideoConfiguration：res=2 fps=2 margins 0 0 density 160 par 10000 codec 3
        val hex = o.payload.joinToString("") { "%02x".format(it) }
        assertTrue(hex.contains("08 02 10 02 18 00 20 00 28 a0 01 40 90 4e 50 03".replace(" ", "")))
    }

    @Test
    fun serviceDiscoveryResponseVariant() {
        eq("sdr_800", M.serviceDiscoveryResponse(AapSdOptions(resolution = "800x480", fps = 60, density = 140)).payload)
    }

    /** protobuf 顶层字段：(编号, 值)；varint 为 Long，len-delimited 为 ByteArray。 */
    private fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val x = b[i++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        while (i < b.size) {
            val tag = varint().toInt()
            when (tag and 7) {
                0 -> out += (tag shr 3) to varint()
                2 -> {
                    val n = varint().toInt()
                    out += (tag shr 3) to b.copyOfRange(i, i + n)
                    i += n
                }
                else -> error("wire type ${tag and 7}")
            }
        }
        return out
    }

    private fun strField(f: List<Pair<Int, Any>>, n: Int) = String(f.first { it.first == n }.second as ByteArray)

    @Test
    fun serviceDiscoveryResponseCarMake() {
        val def = M.serviceDiscoveryResponse(AapSdOptions(carMake = null)).payload
        assertArrayEquals(M.serviceDiscoveryResponse().payload, def)
        eq("sdr_default", def)
        val d = fields(def)
        assertEquals("Google", strField(d, 2))

        val f = fields(M.serviceDiscoveryResponse(AapSdOptions(carMake = "Honda")).payload)
        val info = fields(f.first { it.first == 17 }.second as ByteArray)
        assertEquals("Honda", strField(f, 2))
        assertEquals("Honda", strField(info, 1))
        // 车机的制造商和型号不变
        assertEquals("Google", strField(f, 7))
        assertEquals("Google", strField(info, 5))
        assertEquals("Desktop Head Unit", strField(f, 3))
        assertEquals("Desktop Head Unit", strField(info, 2))
        // 新的车辆档案：带品牌后缀的 vehicleId
        assertEquals("headlessunit-001-honda", strField(f, 5))
        assertEquals("headlessunit-001-honda", strField(info, 4))
        assertEquals("headlessunit-001", strField(d, 5))

        // moto 后缀保留，品牌追加在其后
        val moto = fields(M.serviceDiscoveryResponse(AapSdOptions(mic = false, carMake = "Mazda")).payload)
        assertEquals("headlessunit-001-moto-mazda", strField(moto, 5))
    }

    @Test
    fun serviceDiscoveryResponseHeadUnitIdentityIgnoresCarMake() {
        for (make in listOf(null, "Honda")) {
            val f = fields(M.serviceDiscoveryResponse(AapSdOptions(carMake = make)).payload)
            val info = fields(f.first { it.first == 17 }.second as ByteArray)
            assertEquals("$make: head_unit_make", "Google", strField(f, 7))
            assertEquals("$make: head_unit_model", "Desktop Head Unit", strField(f, 8))
            assertEquals("$make: 17.head_unit_make", "Google", strField(info, 5))
            assertEquals("$make: 17.head_unit_model", "Desktop Head Unit", strField(info, 6))
        }
        // 无品牌时 make = Google：AA 将车机视为开发者设备并显示第三方视频应用
        val d = fields(M.serviceDiscoveryResponse(AapSdOptions(carMake = null)).payload)
        val dInfo = fields(d.first { it.first == 17 }.second as ByteArray)
        assertEquals("Google", strField(d, 2))
        assertEquals("Google", strField(dInfo, 1))
    }

    @Test
    fun builders() {
        eq("channel_open_response", M.channelOpenResponse(2).payload)
        assertEquals(8, M.channelOpenResponse(2).type)
        eq("ping_response", M.pingResponse(123456789).payload)
        eq("nav_focus", M.navFocusNotification().payload)
        eq("audio_focus_gain", M.audioFocusResponse(1)!!.payload)
        eq("audio_focus_transient", M.audioFocusResponse(2)!!.payload)
        eq("audio_focus_duck", M.audioFocusResponse(3)!!.payload)
        eq("audio_focus_release", M.audioFocusResponse(4)!!.payload)
        assertNull(M.audioFocusResponse(0))
        eq("audio_focus_unsolicited", M.audioFocusGainUnsolicited().payload)
        eq("byebye_request", M.byebyeRequest().payload)
        eq("byebye_response", M.byebyeResponse().payload)
        eq("media_config_video", M.mediaConfig(2, 12).payload)
        eq("media_config_audio", M.mediaConfig(5, 30).payload)
        eq("video_focus_unsol", M.videoFocusNotification(true, true).payload)
        eq("video_focus_sol", M.videoFocusNotification(true, false).payload)
        eq("media_ack", M.mediaAck(2, 7).payload)
        eq("media_ack_neg", M.mediaAck(2, 0).payload)
        eq("mic_refuse", M.micResponse(false).payload)
        eq("mic_ok", M.micResponse(true).payload)
        eq("binding_response", M.bindingResponse().payload)
        eq("sensor_start_response", M.sensorStartResponse().payload)
        eq("driving_status", M.drivingStatusEvent(0).payload)
        eq("night", M.nightModeEvent(false).payload)
        eq("touch_down", M.touchEvent(M.TOUCH_DOWN, 640, 360, 123456789012L).payload)
        eq("touch_up", M.touchEvent(M.TOUCH_UP, 1279, 719, 5).payload)
        eq("touch_move", M.touchEvent(M.TOUCH_MOVE, 0, 0, 1).payload)
    }

    @Test
    fun touchEventMultiBytes() {
        // 通过列表传单个手指：与 golden 中的 touch_down 逐字节一致（多点触控之前）
        assertArrayEquals(
            Golden.hex("0894b4e4f4cb031a0e0a0808800510e802180010001800"),
            M.touchEvent(M.TOUCH_DOWN, 0, listOf(TouchPoint(0, 640, 360)), 123456789012L).payload,
        )
        // InputReport{1: ts=1, 3: TouchEvent{1: {100,50,id 0}, 1: {200,60,id 1}, 2: action_index 1, 3: POINTER_DOWN}}
        val two = M.touchEvent(M.TOUCH_POINTER_DOWN, 1, listOf(TouchPoint(0, 100, 50), TouchPoint(1, 200, 60)), 1)
        assertEquals(M.CH_INPUT, two.chan)
        assertEquals(M.INPUT_EVENT, two.type)
        assertArrayEquals(
            Golden.hex("08 01 1a 15 0a 06 08 64 10 32 18 00 0a 07 08 c8 01 10 3c 18 01 10 01 18 05"),
            two.payload,
        )
    }

    @Test
    fun keyEventBytes() {
        // InputReport{1: ts=5, 4: KeyEvent{1: Key{1: 87, 2: true, 3: 0, 4: false}}}
        val down = M.keyEvent(5, 87, true)
        assertEquals(M.CH_INPUT, down.chan)
        assertEquals(M.INPUT_EVENT, down.type)
        assertArrayEquals(Golden.hex("08 05 22 0a 0a 08 08 57 10 01 18 00 20 00"), down.payload)
        val up = M.keyEvent(300, 88, false)
        assertArrayEquals(Golden.hex("08 ac 02 22 0a 0a 08 08 58 10 00 18 00 20 00"), up.payload)
    }

    @Test
    fun rotaryEventBytes() {
        // InputReport{1: ts=5, 6: RelativeEvent{1: Rel{1: 65536, 2: delta}}}
        val cw = M.rotaryEvent(5, 1)
        assertEquals(M.CH_INPUT, cw.chan)
        assertEquals(M.INPUT_EVENT, cw.type)
        assertArrayEquals(Golden.hex("08 05 32 08 0a 06 08 80 80 04 10 01"), cw.payload)
        val ccw = M.rotaryEvent(5, -1)
        assertArrayEquals(Golden.hex("08 05 32 11 0a 0f 08 80 80 04 10 ff ff ff ff ff ff ff ff ff 01"), ccw.payload)
    }

    @Test
    fun versionResponseParse() {
        val v = M.parseVersionResponse(Golden.hex("00 01 00 07 00 00"))!!
        assertEquals(listOf(1, 7, 0), listOf(v.major, v.minor, v.status))
        val neg = M.parseVersionResponse(Golden.hex("00 01 00 07 ff ff 0a 00"))!!
        assertEquals(-1, neg.status)
        assertArrayEquals(Golden.hex("0a 00"), neg.options)
        assertNull(M.parseVersionResponse(byteArrayOf(0)))
    }

    @Test
    fun describeDecodesNames() {
        val d = M.describe(0, M.PING_REQUEST, Golden.hex("08 95 9a ef 3a"))
        assertTrue(d, d.contains("timestamp=123456789"))
        assertNotNull(M.messageName(2, 1))
        assertEquals("MEDIA_CODEC_CONFIG", M.messageName(2, 1))
        assertEquals("VERSION_REQUEST", M.messageName(0, 1))
        assertEquals("INPUT_EVENT", M.messageName(3, 0x8001))
        assertEquals("MEDIA_START", M.messageName(2, 0x8001))
    }
}
