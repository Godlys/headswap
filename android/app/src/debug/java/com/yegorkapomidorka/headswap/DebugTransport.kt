package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 调试传输：CarLife 流经 TCP 到 PC 上的车机模拟器，而非 USB 附件。
 * 在 PC 上：`adb reverse tcp:7240 tcp:7240`、`python pc-headunit/carlife_hu.py`；在手机上：
 * `adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ei debug_tcp 7240`。
 */
object DebugTransport {
    const val EXTRA_PORT = "debug_tcp"

    const val EXTRA_TCP6 = "debug_tcp6"

    /**
     * 无线格式（6 个 socket，无外部头）：手机在 127.0.0.1 上监听 7240..9340，PC 上的车机
     * 通过 `adb forward tcp:P tcp:P` 连接；`--ez debug_tcp6 true`，PC 上运行 `carlife_hu.py --transport tcp6`。
     */
    fun tcp6(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_TCP6, false) == true

    /** 无线分支 B：手机热点，信标 7999，6 个端口监听 0.0.0.0（`--ez debug_wifi_b true`）。 */
    fun wifiB(intent: Intent?): Boolean = intent?.getBooleanExtra("debug_wifi_b", false) == true

    /** tcp6 的 bind 地址：默认 127.0.0.1；`--es debug_tcp6_bind 0.0.0.0` 用于测试来自 LAN 的入站 TCP。 */
    fun tcp6Bind(intent: Intent?): String = intent?.getStringExtra("debug_tcp6_bind") ?: "127.0.0.1"

    fun port(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }

    /** 阻塞式连接，勿在 main 线程调用。socket 被 dup 进 PFD，socket 本身不再需要。 */
    fun connect(port: Int): ParcelFileDescriptor? = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            ParcelFileDescriptor.fromSocket(s)
        }
    } catch (e: Exception) {
        AppLog.e("debug TCP 127.0.0.1:$port", e)
        null
    }
}
