package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.VideoGeometry

/** 供屏幕显示的状态；由服务和会话写入，activity 读取（同一进程）。 */
object CarLifeState {
    @Volatile var status: String = "не запущено"
    @Volatile var accessory: String = "-"
    @Volatile var protocol: String = "-"
    @Volatile var auth: String = "-"
    @Volatile var video: String = "-"
    @Volatile var touch: String = "-"

    /** 当前/最近一次会话的视频模式（实际值，考虑 passthrough）；null 表示没有会话。 */
    @Volatile var videoMode: VideoGeometry? = null

    /** 同一会话的手动 AA DPI（AaDensity.AUTO = 模式的 density）。 */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    @Volatile var running: Boolean = false

    /** 当前会话通过 Wi-Fi（TCP）而非数据线进行；false 表示没有会话或通过 USB。 */
    @Volatile var wirelessSession: Boolean = false

    /** 无线（测试版，分支 B）：当前进展，用于屏幕显示；null 表示该模式未启用。 */
    @Volatile var wireless: String? = null

    /** 分支 A 的 Bluetooth 探测：车机通过 RFCOMM `carlife` 发回的内容；null 表示探测未运行。 */
    @Volatile var btProbe: String? = null

    /** 无线自动模式：通过 Bluetooth 检测到所选车辆在附近（由 BridgeService 写入）。 */
    @Volatile var carAuto: Boolean = false

    /** 请求车辆 Wi-Fi 网络：idle / requesting / available / unavailable（由 BridgeService 写入）。 */
    @Volatile var carNet: String = "idle"

    fun resetSession() {
        protocol = "-"
        auth = "-"
        video = "-"
        touch = "-"
    }

    fun summary(): String =
        "статус: $status\nаксессуар: $accessory\nпротокол: $protocol\nauth: $auth\nвидео: $video\nтач: $touch" +
            (wireless?.let { "\nбеспровод: $it" } ?: "")
}