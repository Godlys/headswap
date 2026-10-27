package com.yegorkapomidorka.headswap.link.bt

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import com.yegorkapomidorka.headswap.Wire

/**
 * 无线 CarLife 蓝牙通道的帧（Baidu MD 8.6.7、CommonParams、BluetoothConnect.readData）：
 * [正文长度 u16 BE][保留 u16 = 0][类型 u32 BE] + protobuf。长度不含头部。
 */
object BtMsg {
    const val MD_WIRELESS_INFO_REQUEST = 0x00100001
    const val HU_WIRELESS_INFO = 0x00108002
    const val MD_TARGET_INFO_REQUEST = 0x00100004
    const val HU_TARGET_INFO = 0x00108005
    const val HU_IP_REQUEST = 0x00108006
    const val MD_WIFI_IP = 0x00100007
    const val MD_STATUS = 0x00100008
    const val HU_STATUS = 0x00108009

    const val HDR = 8
    const val MAX_BODY = 4096

    fun name(type: Int): String = when (type) {
        MD_WIRELESS_INFO_REQUEST -> "WIRELESS_INFO_REQUEST"
        HU_WIRELESS_INFO -> "WIRELESS_INFO"
        MD_TARGET_INFO_REQUEST -> "TARGET_INFO_REQUEST"
        HU_TARGET_INFO -> "TARGET_INFO"
        HU_IP_REQUEST -> "IP_REQUEST"
        MD_WIFI_IP -> "WIFI_IP"
        MD_STATUS -> "MD_STATUS"
        HU_STATUS -> "HU_STATUS"
        else -> "0x%08X".format(type)
    }

    /** INFO_RESPONSE 中的 wirelessType：2..4 会启动 Baidu 的无线分支，1 (WIFI) 不会。 */
    fun wirelessTypeName(t: Int?): String = when (t) {
        null -> "-"
        1 -> "1(wifi)"
        2 -> "2(p2p)"
        3 -> "3"
        4 -> "4"
        else -> t.toString()
    }

    fun frame(type: Int, body: ByteArray? = null): ByteArray = Wire.cmdInner(type, body)

    /**
     * MD_WIFI_IP 的正文：手机在车机 P2P 组中的地址，以字符串形式放在字段 1。格式未经 MD 源码确认
     * （Baidu 8.6.7 我们没读过，LifeAuto 在原生部分组装正文）：需根据实车的第一份报告核实。
     */
    fun wifiIp(ip: String): ByteArray = PbWriter().string(1, ip).toByteArray()
}

/** 来自 TARGET_INFO 的车机 P2P 组凭据：Wi-Fi Direct 设备名和 p2pNetInfo「ssid,密码[,频率]」。 */
class P2pTarget(val deviceName: String?, val ssid: String?, val passphrase: String?, val freqMhz: Int?) {
    /** 能否直接加入组（WifiP2pConfig.Builder）：ssid 为 DIRECT-xy 且密码 8..63 个字符。 */
    val canJoinBySsid: Boolean
        get() = ssid != null && ssid.startsWith("DIRECT-") && ssid.length >= 9 && passphrase != null && passphrase.length in 8..63

    /** 凭据相同：车机对我们 TARGET_INFO_REQUEST 的应答与其已发送的内容相同。 */
    fun sameAs(o: P2pTarget): Boolean =
        deviceName == o.deviceName && ssid == o.ssid && passphrase == o.passphrase && freqMhz == o.freqMhz

    companion object {
        fun parse(body: ByteArray): P2pTarget? {
            val f = PbReader.parse(body) ?: return null
            val net = PbReader.str(f, 4)?.split(',')
            return P2pTarget(
                deviceName = PbReader.str(f, 1)?.takeIf { it.isNotEmpty() },
                ssid = net?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                passphrase = net?.getOrNull(1)?.takeIf { it.isNotEmpty() },
                freqMhz = net?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 2400..5900 },
            )
        }
    }
}

class BtFrame(val type: Int, val body: ByteArray)

/** 从 RFCOMM 流片段组装帧。头部中有垃圾数据（保留 != 0、长度 > MAX_BODY）：逐字节偏移。 */
class BtFrameParser {
    private var buf = ByteArray(0)

    /** 重新同步时丢弃了多少字节（用于日志）。 */
    var skipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int = chunk.size): List<BtFrame> {
        buf += chunk.copyOf(n)
        val out = ArrayList<BtFrame>()
        var p = 0
        while (buf.size - p >= BtMsg.HDR) {
            val len = Wire.u16(buf, p)
            if (len > BtMsg.MAX_BODY || Wire.u16(buf, p + 2) != 0) {
                p++
                skipped++
                continue
            }
            if (buf.size - p < BtMsg.HDR + len) break
            out += BtFrame(Wire.u32(buf, p + 4), buf.copyOfRange(p + BtMsg.HDR, p + BtMsg.HDR + len))
            p += BtMsg.HDR + len
        }
        buf = buf.copyOfRange(p, buf.size)
        return out
    }
}

/**
 * 供 WL 日志使用的车机响应摘要，不含机密：p2pNetInfo 中的网络名和密码不输出，只输出部分数量和频率；
 * scanRecord（盒子的加密凭据）——只输出长度。
 */
object BtSummary {
    fun wirelessInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("wirelessType" to BtMsg.wirelessTypeName(PbReader.int(f, 1)), "wifiFreq" to PbReader.int(f, 2))
    }

    fun targetInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        val name = PbReader.str(f, 1)
        val target = PbReader.str(f, 2)
        val scan = PbReader.all(f, 3).firstOrNull()?.data
        val net = PbReader.str(f, 4)
        return listOf(
            "p2pNameLen" to (name?.length ?: -1),
            "p2pNameDirect" to (name?.startsWith("DIRECT-") ?: false),
            "targetInfoLen" to (target?.length ?: -1),
            "scanRecordLen" to (scan?.size ?: -1),
        ) + netInfo(net)
    }

    /** p2pNetInfo = 「ssid,密码[,频率 MHz]」：是否存在、部分数量、频率。 */
    fun netInfo(net: String?): List<Pair<String, Any?>> {
        if (net == null) return listOf("p2pNetInfo" to "нет")
        val parts = net.split(',')
        val freq = parts.getOrNull(2)?.trim()?.toIntOrNull()
        return listOf("p2pNetInfo" to "есть", "netParts" to parts.size, "netFreq" to freq)
    }

    /** 正文结构，不含字符串值：字段号、类型、数值或长度（IP_REQUEST 和车机响应的格式未经确认）。 */
    fun shape(body: ByteArray): String {
        val f = PbReader.parse(body) ?: return "parse-fail/${body.size}"
        if (f.isEmpty()) return "empty"
        return f.joinToString(",") { if (it.data == null) "${it.number}=${it.num}" else "${it.number}:len${it.data.size}" }
    }

    fun status(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("status" to PbReader.int(f, 1))
    }
}
