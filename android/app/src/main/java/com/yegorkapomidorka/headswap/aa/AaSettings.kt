package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.content.SharedPreferences
import com.yegorkapomidorka.headswap.AppLog
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** AA 模式设置（单独的 prefs 文件）。 */
class AaPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("aa", Context.MODE_PRIVATE)

    /** jvc（默认，已在 AA 17.7 实机上验证）或 hur。 */
    var certSet: String
        get() = sp.getString("certSet", "jvc") ?: "jvc"
        set(v) = sp.edit().putString("certSet", v).apply()

    /** AA -> CarLife 的 USB 音频；默认开启。 */
    var usbAudio: Boolean
        get() = sp.getBoolean("usbAudio", true)
        set(v) = sp.edit().putBoolean("usbAudio", v).apply()

    /** CarLife 中的音乐频率（名称 MusicRate，默认“自动”）。桥接器在每次 MEDIA INIT 时读取。 */
    var musicRate: MusicRate
        get() = MusicRate.parse(sp.getString("musicRate", MusicRate.DEFAULT.name))
        set(v) = sp.edit().putString("musicRate", v.name).apply()

    /** CarLife 中的音乐数据包大小（名称 MediaChunk，默认“自动”；实验功能，不包含在车型预设中）。桥接器在每次缓冲时读取。 */
    var mediaChunk: MediaChunk
        get() = MediaChunk.parse(sp.getString("mediaChunk", MediaChunk.DEFAULT.name))
        set(v) = sp.edit().putString("mediaChunk", v.name).apply()

    /** CarLife 中的语音数据包大小（名称 VoiceChunk，默认“自动”；实验功能，不包含在车型预设中）。桥接器在收到每个数据包时读取。 */
    var voiceChunk: VoiceChunk
        get() = VoiceChunk.parse(sp.getString("voiceChunk", VoiceChunk.DEFAULT.name))
        set(v) = sp.edit().putString("voiceChunk", v.name).apply()

    /** TTS 提示前的静音预卷（名称 TtsPreroll，默认“自动”；实验功能，不包含在车型预设中）。桥接器在每次 TTS INIT 时读取。 */
    var ttsPreroll: TtsPreroll
        get() = TtsPreroll.parse(sp.getString("ttsPreroll", TtsPreroll.DEFAULT.name))
        set(v) = sp.edit().putString("ttsPreroll", v.name).apply()

    /** MOVE 触摸的抽稀（名称 TouchDecimation，默认“关”；实验功能，不包含在车型预设中）。桥接器在每次事件时读取。 */
    var touchDecimation: TouchDecimation
        get() = TouchDecimation.parse(sp.getString("touchDecimation", TouchDecimation.DEFAULT.name))
        set(v) = sp.edit().putString("touchDecimation", v.name).apply()

    /** 桥接视频：转码（默认）或原样 passthrough AA 的 H.264。 */
    var transcode: Boolean
        get() = sp.getBoolean("transcode", true)
        set(v) = sp.edit().putBoolean("transcode", v).apply()

    /** 视频分辨率（名称 VideoMode，默认 1280×720）。每次连接时读取一次。 */
    var videoMode: VideoMode
        get() = VideoMode.parse(sp.getString("videoMode", VideoMode.DEFAULT.name))
        set(v) = sp.edit().putString("videoMode", v.name).apply()

    /** 自定义分辨率（开发者选项）：代替 [videoMode]，仅限转码模式。每次连接时读取一次。 */
    var customRes: Boolean
        get() = sp.getBoolean("customRes", false)
        set(v) = sp.edit().putBoolean("customRes", v).apply()

    var customW: Int
        get() = sp.getInt("customW", DEFAULT_CUSTOM_W)
        set(v) = sp.edit().putInt("customW", v).apply()

    var customH: Int
        get() = sp.getInt("customH", DEFAULT_CUSTOM_H)
        set(v) = sp.edit().putInt("customH", v).apply()

    /** head unit 在上一会话的 VIDEO_ENCODER_INIT 中请求的尺寸；0 = 尚无。 */
    var lastHuW: Int
        get() = sp.getInt("lastHuW", 0)
        set(v) = sp.edit().putInt("lastHuW", v).apply()

    var lastHuH: Int
        get() = sp.getInt("lastHuH", 0)
        set(v) = sp.edit().putInt("lastHuH", v).apply()

    /** 保存 head unit 请求的尺寸（不写入 0 和垃圾值）。 */
    fun saveHuSize(w: Int, h: Int) {
        if (w > 0 && h > 0) sp.edit().putInt("lastHuW", w).putInt("lastHuH", h).apply()
    }

    /** 最终视频几何：自定义分辨率（若启用且有效）或 [videoMode]。转码通过 effective() 体现。 */
    val videoGeometry: VideoGeometry
        get() = CustomVideoMode.resolve(customRes, customW, customH, videoMode)

    /** AA 界面的手动 DPI（AaDensity），0 = 自动（使用模式的 density）。每次连接时读取一次。 */
    var aaDensity: Int
        get() = AaDensity.parse(sp.getInt("aaDensity", AaDensity.AUTO))
        set(v) = sp.edit().putInt("aaDensity", AaDensity.parse(v)).apply()

    /** 帧率（名称 FpsSetting，默认“自动”）。每次连接时读取一次。 */
    var fpsSetting: FpsSetting
        get() = FpsSetting.parse(sp.getString("fpsSetting", FpsSetting.DEFAULT.name))
        set(v) = sp.edit().putString("fpsSetting", v.name).apply()

    /** SDR 中代替「Google」的汽车品牌（CAR_MAKES）；null = 默认。连接时读取。 */
    var carMake: String?
        get() = sp.getString("carMake", null)?.takeIf { it in CAR_MAKES }
        set(v) = sp.edit().apply { if (v == null) remove("carMake") else putString("carMake", v) }.apply()

    /** 「我的汽车」分区中的车型（名称 CarPreset）；null = 未选择。选择本身只是标签，设置由 [applyCar] 写入。 */
    var carPreset: CarPreset?
        get() = CarPreset.parse(sp.getString("carPreset", null))
        set(v) = sp.edit().apply { if (v == null) remove("carPreset") else putString("carPreset", v.name) }.apply()

    /** 车型选择所设置的当前设置值。 */
    val carSettings: CarSettings
        get() = CarSettings(transcode, videoMode, aaDensity, fpsSetting, musicRate)

    /** 选择车型并写入其推荐设置。 */
    fun applyCar(p: CarPreset) {
        val s = p.settings
        sp.edit()
            .putString("carPreset", p.name)
            .putBoolean("transcode", s.transcode)
            .putString("videoMode", s.videoMode.name)
            .putInt("aaDensity", s.aaDensity)
            .putString("fpsSetting", s.fps.name)
            .putString("musicRate", s.musicRate.name)
            .apply()
    }

    /** AA 麦克风的声音来源：车机 / 手机 / 自动（head unit，静默 2 秒后切为手机）。 */
    var micMode: MicMode
        get() = MicMode.parse(sp.getString("micMode", MicMode.AUTO.key))
        set(v) = sp.edit().putString("micMode", v.key).apply()

    /** 向 head unit 发送录音请求时，同时发送 MODULE_STATUS VR=1/0（同 Baidu）。 */
    var micVrStatus: Boolean
        get() = sp.getBoolean("micVrStatus", true)
        set(v) = sp.edit().putBoolean("micVrStatus", v).apply()

    /** 来自 AA 的曲目、封面和进度 -> MEDIA_INFO / MEDIA_PROGRESS_BAR。连接时读取。 */
    var metaMusic: Boolean
        get() = sp.getBoolean("metaMusic", true)
        set(v) = sp.edit().putBoolean("metaMusic", v).apply()

    /** 来自 AA 的转弯提示 -> NAVI_NEXT_TURN_INFO（若 head unit 已订阅）。连接时读取。 */
    var metaNavi: Boolean
        get() = sp.getBoolean("metaNavi", true)
        set(v) = sp.edit().putBoolean("metaNavi", v).apply()

    /** NAVI_NEXT_TURN_INFO 的字段 6：bytes 图标（同 8.6.7）。 */
    var naviIcon: Boolean
        get() = sp.getBoolean("naviIcon", true)
        set(v) = sp.edit().putBoolean("naviIcon", v).apply()

    /** NAVI_NEXT_TURN_INFO 的字段 6：int32 时间（同 apollo-DuerOS）。 */
    var naviTime: Boolean
        get() = sp.getBoolean("naviTime", true)
        set(v) = sp.edit().putBoolean("naviTime", v).apply()

    val naviField6: NaviField6 get() = NaviField6.of(naviIcon, naviTime)

    /** MEDIA_PROGRESS_BAR 以秒代替毫秒（若 head unit 显示的进度大了 1000 倍）。 */
    var progressSeconds: Boolean
        get() = sp.getBoolean("progressSeconds", false)
        set(v) = sp.edit().putBoolean("progressSeconds", v).apply()

    /** 是否至少成功建立过一次真正的 AA 会话：用户的 head unit 服务器曾正常运转（用于区分「head unit 服务器无响应」与「请启动 head unit 服务器」）。 */
    var serverWorked: Boolean
        get() = sp.getBoolean("serverWorked", false)
        set(v) = sp.edit().putBoolean("serverWorked", v).apply()

    /** 「车机应用」分区中关于行车视频的警告已展示过（一次）。 */
    var appsWarningShown: Boolean
        get() = sp.getBoolean("appsWarningShown", false)
        set(v) = sp.edit().putBoolean("appsWarningShown", v).apply()

    /** 均衡器预设（EqPreset.key）；频段和 preamp 分开存储，预设只是标签。 */
    var eqPreset: EqPreset
        get() = EqPreset.parse(sp.getString("eqPreset", EqPreset.FLAT.key))
        set(v) = sp.edit().putString("eqPreset", v.key).apply()

    /** 频段（CSV，dB）和 preamp；写入后立即通过 EqLive 应用到桥接器。 */
    var eq: EqSettings
        get() = EqSettings(EqSettings.parseBands(sp.getString("eqBands", null)), sp.getInt("eqPreamp", 0)).normalized()
        set(v) {
            val n = v.normalized()
            sp.edit().putString("eqBands", n.bandsCsv()).putInt("eqPreamp", n.preamp).apply()
            EqLive.settings = n
        }

    /** 「自定义」的最后值：试听预设后返回该值。 */
    var eqCustom: EqSettings
        get() = EqSettings(EqSettings.parseBands(sp.getString("eqCustomBands", null)), sp.getInt("eqCustomPreamp", 0)).normalized()
        set(v) {
            val n = v.normalized()
            sp.edit().putString("eqCustomBands", n.bandsCsv()).putInt("eqCustomPreamp", n.preamp).apply()
        }

    companion object {
        const val DEFAULT_CUSTOM_W = 1920
        const val DEFAULT_CUSTOM_H = 720
    }
}

/** 「汽车品牌」设置用的品牌列表（规范英文拼写）。 */
val CAR_MAKES = listOf(
    "Honda", "Mazda", "Toyota", "Nissan", "Hyundai", "Kia", "Volkswagen", "Skoda", "Ford",
    "Chevrolet", "Mitsubishi", "Subaru", "Lexus", "Changan", "Haval", "Chery", "Geely",
)

/** 供界面显示的 AA 模式状态；由服务写入，activity 读取（同一进程）。 */
object AaState {
    @Volatile var status: String = "не запущено"
    @Volatile var running: Boolean = false
    @Volatile var cert: String = "-"
    val frames = AtomicLong(0)
    @Volatile var bytes: Long = 0
    @Volatile var codecConfigs: Long = 0
    @Volatile var selftest: String = "-"
    @Volatile var bridgeSent: Long = 0
    @Volatile var bridgeDropped: Long = 0
    @Volatile var audioBytes: Long = 0

    /** 导致桥接器无法启动的配置错误（例如缺少 head unit 证书）；null = 一切正常。 */
    @Volatile var setupError: String? = null

    /** 真实桥接连接中 head unit 服务器的失败：主界面「head unit 服务器无响应」状态的来源。 */
    val serverFailures = ServerFailures()

    fun reset() {
        frames.set(0)
        bytes = 0
        codecConfigs = 0
        bridgeSent = 0
        bridgeDropped = 0
        audioBytes = 0
    }

    fun summary(): String =
        "AA: $status\nAA cert: $cert  selftest: $selftest\nAA кадров: ${frames.get()} (config $codecConfigs, ${bytes / 1024} КиБ)\nМОСТ: в CarLife отправлено $bridgeSent, отброшено $bridgeDropped, звук ${audioBytes / 1024} КиБ"
}

/** assets 中找不到任何 head unit 证书集。 */
class AaCertsMissingException(message: String) : Exception(message)

/** 从 assets 加载证书，失败时回退到第二套证书集并输出醒目的回退日志。 */
object AaCreds {
    const val MISSING_TEXT = "Нет сертификата head unit: положите cert.pem и key.pem в app/src/main/assets/aa/jvc/ (см. README)"

    /** @throws AaCertsMissingException 若任一证书集中都没有证书文件 */
    fun load(ctx: Context, preferred: String): AapCerts.Loaded {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val loaded = try {
            AapCerts.loadWithFallback(opener, preferred)
        } catch (e: FileNotFoundException) {
            AppLog.i("AA !!!!!! $MISSING_TEXT", true)
            throw AaCertsMissingException(MISSING_TEXT)
        }
        if (loaded.error != null) {
            AppLog.i("AA !!!!!! ${loaded.error}", true)
            AppLog.i("AA !!!!!! ОТКАТ НА СЕРТИФИКАТ '${loaded.creds.certSet}' (запрошен '$preferred') !!!!!!", true)
        } else {
            AppLog.i("AA сертификат '${loaded.creds.certSet}' загружен: ${loaded.creds.chain[0].subjectX500Principal}", true)
        }
        return loaded
    }

    /** notBefore/notAfter 单独处理：JVC 的格式是带时区的 UTCTime，并非所有解析器都支持。仅供日志。 */
    fun describeValidity(creds: AapCredentials): String = try {
        val c = creds.chain[0]
        "notBefore=${c.notBefore} notAfter=${c.notAfter}"
    } catch (e: Throwable) {
        "notBefore/notAfter не читаются: ${e.javaClass.simpleName}: ${e.message}"
    }

    /** 离线自检：用两套证书集做内存中 TLS 握手，结果写入日志。 */
    fun selfTest(ctx: Context) {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val parts = ArrayList<String>()
        for (set in AapCerts.SETS) {
            try {
                AppLog.i("AA selftest [$set]: ${describeValidity(AapCerts.load(opener, set))}", true)
            } catch (e: Throwable) {
                AppLog.i("AA selftest [$set]: сертификат/ключ не загрузились: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val r = AapTlsSelfTest.run(opener, set)
            AppLog.i("AA selftest [$set]: TLS in-memory ${if (r.ok) "OK" else "FAIL"} ${r.detail}", true)
            parts.add("$set=${if (r.ok) "OK" else "FAIL"}")
        }
        AaState.selftest = parts.joinToString(" ")
    }
}
