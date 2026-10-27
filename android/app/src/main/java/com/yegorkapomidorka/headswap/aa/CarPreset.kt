package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/** 桥接器设置，由「我的汽车」部分中的车辆选择来设置。 */
data class CarSettings(
    val transcode: Boolean = true,
    val videoMode: VideoMode = VideoMode.DEFAULT,
    val aaDensity: Int = AaDensity.AUTO,
    val fps: FpsSetting = FpsSetting.DEFAULT,
    val musicRate: MusicRate = MusicRate.DEFAULT,
)

/** 韩国车机性能较弱：30 帧和 48 kHz 会导致 USB 中断，建议 15 帧、32 kHz（h139, h218, h260）。 */
private val KOREAN = CarSettings(musicRate = MusicRate.HZ32)

/**
 * 已确认支持的车型（README「兼容性」）以及社区为每款车验证过的设置。
 * 选择车型会把 [settings] 一次性写入 AaPrefs，之后任何设置都可以手动修改。
 * 所有车型通用：转码和 15 帧（默认 1280×720，特殊屏幕的车型用各自的分辨率），音乐「自动」；单独的值只保留经过实车验证的。
 * 桥接器自行识别的车机特性（Hyundai CN7 - 「自动」下音乐为 32 kHz，VW ID - 比特率上限）这里不重复。
 * 枚举项名称存储在 prefs 中：不要重命名。
 */
enum class CarPreset(val titleRes: Int, val settings: CarSettings = CarSettings()) {
    /** 车机 1920×640 (h269)。 */
    CITROEN_C5_X(R.string.car_citroen_c5_x, CarSettings(videoMode = VideoMode.WIDE_640)),
    /** 屏幕 1024×600，USB 较弱的车机 (h277)：桥接器自行强制转码并限制比特率上限 800 kbps。 */
    DONGFENG_DF6_2023(R.string.car_dongfeng_df6_2023, CarSettings(videoMode = VideoMode.R1024)),
    PEUGEOT_5008_2024(R.string.car_peugeot_5008_2024),
    HONDA_BREEZE_2(R.string.car_honda_breeze_2),
    /** h243：1920×1080，「运行良好」。 */
    HONDA_CRV_6(R.string.car_honda_crv_6, CarSettings(videoMode = VideoMode.R1080)),
    /** SEMIDRIVE X9，屏幕 1920×720；「自动」帧率下有条纹和闪烁 (h72)。 */
    HONDA_CRV_2026(R.string.car_honda_crv_2026, CarSettings(videoMode = VideoMode.WIDE_HD)),
    HONDA_CRV_2026_HYBRID(R.string.car_honda_crv_2026_hybrid, CarSettings(videoMode = VideoMode.WIDE_HD)),
    /** 作者 Yegor 的车：1920×1080，30 帧。 */
    HONDA_VEZEL_2022(R.string.car_honda_vezel_2022, CarSettings(videoMode = VideoMode.R1080)),
    HYUNDAI_ELANTRA_7(R.string.car_hyundai_elantra_7, KOREAN),
    /** HSAE，竖屏：1280×480 运行流畅 (h86)。 */
    HYUNDAI_IX35_2021(R.string.car_hyundai_ix35_2021, KOREAN.copy(videoMode = VideoMode.WIDE)),
    JETTA_VS(R.string.car_jetta_vs),
    KIA_K3_2023(R.string.car_kia_k3_2023, KOREAN),
    KIA_SPORTAGE_ACE(R.string.car_kia_sportage_ace, KOREAN),

    /** 中国版 Mazda Connect 2（channel 20622111, h238/h359）：车机请求 1280×480。 */
    MAZDA_3_CN_2025(R.string.car_mazda_3_cn_2025, CarSettings(videoMode = VideoMode.WIDE)),
    /** Desay SV G6S：原生屏幕 1728×648。 */
    MAZDA_CX5_2025(R.string.car_mazda_cx5_2025, CarSettings(videoMode = VideoMode.WIDE_MID)),
    /** alps，CarLife 4.1：1920×720 (h166)。 */
    MAZDA_CX50(R.string.car_mazda_cx50, CarSettings(videoMode = VideoMode.WIDE_HD)),
    /** yftech：15 帧下正常工作 (h10, h41)。 */
    MITSUBISHI_OUTLANDER_4(R.string.car_mitsubishi_outlander_4, CarSettings()),
    SKODA_KAMIQ_GT_2021(R.string.car_skoda_kamiq_gt_2021),
    /** CNS3 请求 2:1，1280×640 (h292)；1080p 下卡死 (h305)。 */
    SKODA_OCTAVIA_PRO(R.string.car_skoda_octavia_pro, CarSettings(videoMode = VideoMode.WIDE_2_1)),
    TOYOTA_COROLLA_CROSS_2024(R.string.car_toyota_corolla_cross_2024),
    TOYOTA_LEVIN(R.string.car_toyota_levin),
    /** 中国：1920×1080，30 帧（Yegor，18.10）。 */
    VW_BORA_2021(R.string.car_vw_bora_2021, CarSettings(videoMode = VideoMode.R1080)),
    /** 1920×1080，其余「自动」（@Aleksandr_456_55）。 */
    VW_GOLF_8_PRO(R.string.car_vw_golf_8_pro, CarSettings(videoMode = VideoMode.R1080)),
    VW_ID_UNYX(R.string.car_vw_id_unyx),
    VW_LAMANDO_L(R.string.car_vw_lamando_l),
    VW_SAGITAR_2021(R.string.car_vw_sagitar_2021),
    VW_TROC_2026(R.string.car_vw_troc_2026),
    VW_THARU_XR(R.string.car_vw_tharu_xr),

    /** VW CNS3：2240×1260 屏幕上的 CarLife 窗口为 2240×976。 */
    VW_TIGUAN_L_PRO(R.string.car_vw_tiguan_l_pro, CarSettings(videoMode = VideoMode.WIDE_976));

    companion object {
        /** 按 prefs 中的名称解析；未知/为空 = 未选择车辆。 */
        fun parse(key: String?): CarPreset? = values().firstOrNull { it.name == key }
    }
}
