package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R
import java.util.Locale

    /** 原始帧的中心矩形（以比例表示；u 向右，v 向上；裁剪是对称的，因此 v 的方向不重要）。 */
data class VideoCrop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = VideoCrop(0f, 0f, 1f, 1f)
    }
}

/**
 * 视频几何：是 Android Auto（SDR）、CarLife 输出（INIT_DONE、编码器）、裁剪和触摸的唯一事实来源。
 * 每次连接读取一次（AaBridgeCore.videoMode），之后各处都从这里取值，不再读 prefs。
 * 由预设 [VideoMode] 和用户自定义 [CustomVideoMode] 实现。
 */
interface VideoGeometry {
    /** SDR 中的 Android Auto 分辨率（SDR 字符串 AapMessages.serviceDiscoveryResponse）。 */
    val aaResolution: String
    val aaW: Int
    val aaH: Int
    /** SDR VideoConfiguration 中的 margin_width / margin_height 字段（总量，两侧各分一半）。 */
    val marginW: Int
    val marginH: Int
    val density: Int
    /** 发送到车机的帧尺寸（INIT_DONE 和编码器）。 */
    val outW: Int
    val outH: Int
    val bitrate: Int

    /** 日志用的短标签：枚举名或「custom 2240x976」。 */
    val label: String

    /** 转码器裁剪：不含 margin 的中央区域（Android Auto 只在 margin 内侧绘制界面）。 */
    val crop: VideoCrop
        get() = if (marginW == 0 && marginH == 0) VideoCrop.NONE
        else VideoCrop(marginW / 2f / aaW, marginH / 2f / aaH, 1f - marginW / 2f / aaW, 1f - marginH / 2f / aaH)

    /** 只有转码时才能裁剪：passthrough 模式下 WIDE 不可用。 */
    val needsTranscode: Boolean get() = !crop.isNone

    /** 在当前转码开关下实际生效的几何。 */
    fun effective(transcode: Boolean): VideoGeometry = if (!transcode && needsTranscode) VideoMode.R720 else this

    /** UI 的输出标签：「1280×720」。 */
    val outLabel: String get() = "${outW}×$outH"

    /**
     * 车机触摸坐标（outW×outH 帧）→ Android Auto 坐标。这是唯一的映射位置。
     * 有 margin 时 Android Auto 期望触摸坐标按界面区域（不含边距）计算，而非整个帧：
     * 偏移 margin/2 曾导致向下偏差 120 px（Desay G6S，日志 2026-10-10）。因此不做偏移，只 clamp 到区域内。
     * 若界面区域被缩放到车机帧（WIDE_976：1920×836 → 2240×976），坐标按比例重新计算。
     * 无 margin 时 - 原样 1:1，不做 clamp。
     */
    fun mapTouch(x: Int, y: Int): Pair<Int, Int> {
        if (marginW == 0 && marginH == 0) return x to y
        val cw = aaW - marginW
        val ch = aaH - marginH
        val sx = if (cw == outW) x else (x.toLong() * cw / outW).toInt()
        val sy = if (ch == outH) y else (y.toLong() * ch / outH).toInt()
        return sx.coerceIn(0, cw - 1) to sy.coerceIn(0, ch - 1)
    }

    /** 写入 SDR 的 density：手动设置值或模式自带值（AaDensity.AUTO）。不影响裁剪和触摸。 */
    fun densityFor(densityOverride: Int): Int = if (densityOverride == AaDensity.AUTO) density else densityOverride

    /** 该几何对应的 Android Auto SDR 选项；densityOverride - Android Auto 界面的手动 DPI（AaDensity.AUTO = 模式 density）。 */
    fun sdOptions(fullAudio: Boolean, densityOverride: Int = AaDensity.AUTO, carMake: String? = null) = AapSdOptions(
        resolution = aaResolution, density = densityFor(densityOverride), marginW = marginW, marginH = marginH, fullAudio = fullAudio,
        carMake = carMake,
    )

    /** 用于日志的字符串。 */
    fun describe(densityOverride: Int = AaDensity.AUTO): String
}

    /** 视频分辨率 - 预设。 */
enum class VideoMode(
    val titleRes: Int,
    val subRes: Int,
    override val aaResolution: String,
    override val marginW: Int,
    override val marginH: Int,
    override val density: Int,
    override val outW: Int,
    override val outH: Int,
    override val bitrate: Int,
) : VideoGeometry {
    R800(R.string.video_r800_title, R.string.video_r800_sub, "800x480", 0, 0, 120, 800, 480, 4_000_000),
    /** 1024×600 - 中国车机的常见分辨率（Pioneer RM56E8B, h277）：从 Android Auto 1280×720 帧中裁出中心 1024×600，1:1。 */
    R1024(R.string.video_r1024_title, R.string.video_r1024_sub, "1280x720", 256, 120, 160, 1024, 600, 2_000_000),
    R720(R.string.video_r720_title, R.string.video_r720_sub, "1280x720", 0, 0, 160, 1280, 720, 4_000_000),
    R1080(R.string.video_r1080_title, R.string.video_r1080_sub, "1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
    R1440(R.string.video_r1440_title, R.string.video_r1440_sub, "2560x1440", 0, 0, 320, 2560, 1440, 12_000_000),
    WIDE(R.string.video_wide_title, R.string.video_wide_sub, "1280x720", 0, 240, 160, 1280, 480, 4_000_000),
    /** 1728×648 - Desay SV G6S（马自达）原生屏幕：从 Android Auto 1920×1080 帧中裁出中心，两轴都有边距。 */
    WIDE_MID(R.string.video_wide_mid_title, R.string.video_wide_mid_sub, "1920x1080", 192, 432, 160, 1728, 648, 5_000_000),
    WIDE_HD(R.string.video_wide_hd_title, R.string.video_wide_hd_sub, "1920x1080", 0, 360, 160, 1920, 720, 6_000_000),
    /** 1920×640 - Citroen C5 X 屏幕（车机请求 1920×640, h269）：从 Android Auto 1920×1080 帧中裁出中心条带 1920×640。 */
    WIDE_640(R.string.video_wide_640_title, R.string.video_wide_640_sub, "1920x1080", 0, 440, 160, 1920, 640, 6_000_000),
    /** 1280×640 - Skoda Octavia A8 Pro 屏幕（SKODA CNS3 车机请求 1280×640, h292）：从 Android Auto 1280×720 帧中裁出中心条带 1280×640，1:1。 */
    WIDE_2_1(R.string.video_wide_2_1_title, R.string.video_wide_2_1_sub, "1280x720", 0, 80, 160, 1280, 640, 4_000_000),
    /** 2240×976 - VW CNS3 上的 CarLife 窗口（Tiguan L Pro，2240×1260 屏幕带原厂车机黑边）：从 Android Auto 1920×1080 帧中裁出中心条带 1920×836 并放大 ×1.167。 */
    WIDE_976(R.string.video_wide_976_title, R.string.video_wide_976_sub, "1920x1080", 0, 244, 160, 2240, 976, 8_000_000);

    override val aaW: Int = aaResolution.substringBefore('x').toInt()
    override val aaH: Int = aaResolution.substringAfter('x').toInt()
    override val label: String get() = name

    /** 在当前转码开关下实际生效的模式。 */
    override fun effective(transcode: Boolean): VideoMode = if (!transcode && needsTranscode) R720 else this

    /**
     * 日志字符串：「视频模式 WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, 裁剪 v 0.167..0.833」。
     * 手动 DPI 时：「density 200 (手动, 自动 160)」。
     */
    override fun describe(densityOverride: Int): String {
        fun f(v: Float) = String.format(Locale.US, "%.3f", v)
        val cropText = if (crop.isNone) "без кропа" else buildString {
            append("кроп")
            if (crop.u0 != 0f) append(" u ${f(crop.u0)}..${f(crop.u1)}")
            if (crop.v0 != 0f) append(" v ${f(crop.v0)}..${f(crop.v1)}")
        }
        val dens = if (densityOverride == AaDensity.AUTO) "$density" else "$densityOverride (вручную, авто $density)"
        return "видео-режим $name: AA $aaResolution margins ${marginW}x$marginH density $dens, CarLife ${outW}x$outH, $cropText"
    }

    companion object {
        val DEFAULT = R720

    /** 按 prefs 中的名称解析；未知/为空 = 默认值。 */
        fun parse(key: String?): VideoMode = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

    /** [CustomVideoMode] 不接受某尺寸的原因。 */
enum class CustomResError { WIDTH_RANGE, HEIGHT_RANGE, ODD, PORTRAIT }

/**
 * 自定义分辨率（开发者选项）：给车机的 outW×outH 帧，构造方式与 WIDE 模式相同：
 * Android Auto 底板（800x480、1280x720 或 1920x1080）+ 边距 + 中心裁剪，并在转码器中缩放。
 * 能完整容纳该帧的最小底板 = 边距，缩放 1:1。宽度超过 1920 时，宽高比适配到 1920x1080，
 * 区域拉伸至整帧（如 WIDE_976）。仅限转码模式。除 outW/outH 外的所有字段均为计算得出，
 * 因此按值比较（data class）即可覆盖全部几何。
 * 构造函数不校验范围（这样也能复现 1024x600 之类的旧预设）；用户输入请使用 [from]。
 */
data class CustomVideoMode(override val outW: Int, override val outH: Int) : VideoGeometry {
    private val plate: Pair<Int, Int> = plateFor(outW, outH)
    private val area: Pair<Int, Int> = areaFor(outW, outH, plate)

    override val aaW: Int get() = plate.first
    override val aaH: Int get() = plate.second
    override val aaResolution: String get() = "${aaW}x$aaH"
    override val marginW: Int get() = plate.first - area.first
    override val marginH: Int get() = plate.second - area.second
    override val density: Int get() = DENSITY
    override val label: String get() = "custom ${outW}x$outH"

    /** Android Auto 界面区域尺寸（不含边距的底板），将被缩放到帧。 */
    val areaW: Int get() = area.first
    val areaH: Int get() = area.second

    /** 比特率随帧面积增长：1280x720 为 4 Mbps，范围 2..8 Mbps。 */
    override val bitrate: Int
        get() = (BASE_BITRATE.toLong() * outW * outH / (1280L * 720L)).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()

    /** 区域到帧的缩放比例：1.0 = 1:1。 */
    val scale: Double get() = maxOf(outW.toDouble() / areaW, outH.toDouble() / areaH)

    /** 不转码时自定义尺寸不可用，即使没有边距（1280x720）也一样：只有转码器能构造该帧。 */
    override val needsTranscode: Boolean get() = true

    override fun describe(densityOverride: Int): String {
        val dens = densityFor(densityOverride)
        val mbps = bitrate / 1_000_000.0
        val rate = if (bitrate % 1_000_000 == 0) "${bitrate / 1_000_000}M" else String.format(Locale.US, "%.1fM", mbps)
        return "custom ${outW}x$outH ← AA $aaResolution поля ${marginW}x$marginH ×${String.format(Locale.US, "%.3f", scale)} dpi $dens $rate"
    }

    companion object {
        const val MIN_W = 640
        const val MAX_W = 2240
        const val MIN_H = 640
        const val MAX_H = 1080
        const val DENSITY = 160
        const val BASE_BITRATE = 4_000_000
        const val MIN_BITRATE = 2_000_000
        const val MAX_BITRATE = 8_000_000
        private val PLATES = listOf(800 to 480, 1280 to 720, 1920 to 1080)
        private val MAX_PLATE = PLATES.last()

        /** 尺寸不合规的原因；null = 合规。 */
        fun check(w: Int, h: Int): CustomResError? = when {
            w !in MIN_W..MAX_W -> CustomResError.WIDTH_RANGE
            h !in MIN_H..MAX_H -> CustomResError.HEIGHT_RANGE
            w % 2 != 0 || h % 2 != 0 -> CustomResError.ODD
            w < h -> CustomResError.PORTRAIT
            else -> null
        }

        /** 有效尺寸 → 模式，否则 null。 */
        fun from(w: Int, h: Int): CustomVideoMode? = if (check(w, h) == null) CustomVideoMode(w, h) else null

        /** 来自 prefs 的最终几何：自定义尺寸已启用且有效时用之，否则用预设。已保存的值无效 = 回退到预设。 */
        fun resolve(enabled: Boolean, w: Int, h: Int, preset: VideoMode): VideoGeometry =
            (if (enabled) from(w, h) else null) ?: preset

        private fun plateFor(w: Int, h: Int): Pair<Int, Int> =
            PLATES.firstOrNull { w <= it.first && h <= it.second } ?: MAX_PLATE

        private fun even(v: Double): Int = (Math.round(v / 2.0) * 2).toInt()

        private fun areaFor(w: Int, h: Int, plate: Pair<Int, Int>): Pair<Int, Int> {
            if (w <= plate.first && h <= plate.second) return w to h
            // 放不进任何底板：宽高比适配到 1920x1080
            val (pw, ph) = MAX_PLATE
            return if (w.toDouble() / h >= pw.toDouble() / ph) pw to even(pw.toDouble() * h / w)
            else even(ph.toDouble() * w / h) to ph
        }
    }
}

/**
 * Android Auto 界面的手动 DPI（SDR VideoConfiguration 中的 density 字段）。是绝对值而非自动值的百分比：
 * Android Auto 布局由 dp 宽度 = aaW * 160 / density 决定，即正是绝对 density 起作用。
 */
object AaDensity {
    /** 不覆盖：使用模式的 density。 */
    const val AUTO = 0
    const val MIN = 100
    const val MAX = 280
    const val STEP = 20

    /** 允许的手动值：100, 120 .. 280。 */
    val VALUES: List<Int> = (MIN..MAX step STEP).toList()

    /** 来自 prefs：不在网格/范围内 = 自动。 */
    fun parse(v: Int): Int = if (v in VALUES) v else AUTO

    /**
     * UI 中「-」/「+」的步进。从自动值出发 - 取网格中严格低于/高于模式 density 的最近值
     * （R1440 的自动值 320 高于最大值，因此从那里按「+」无效果，「-」会设为 280）。
     * 位于网格边缘时保持不变。
     */
    fun step(current: Int, auto: Int, up: Boolean): Int {
        val base = if (current == AUTO) auto else current
        return if (up) VALUES.firstOrNull { it > base } ?: current
        else VALUES.lastOrNull { it < base } ?: current
    }
}
