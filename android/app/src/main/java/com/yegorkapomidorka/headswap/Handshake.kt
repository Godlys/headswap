package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.FpsSetting

/** 出站 CMD 消息：类型和消息体（null = 空）。 */
class OutCmd(val type: Int, val body: ByteArray?)

/**
 * 对 HU_AUTHEN_REQUEST 的响应，与 PrivateKeyManager（百度 CarLife 8.6.7）一致：versionVerify 之后的 randomValue
 * 进行签名（RSA/ECB/PKCS1Padding，ENCRYPT_MODE 使用私钥），结果以 Base64 NO_WRAP 输出。
 * 纯 JVM 逻辑：java.util.Base64 和 javax.crypto 在测试和 Android（minSdk 29）上都可用。
 */
object HuAuth {
    private const val ERROR_VERSION = "_00.00.00"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** 来自 PrivateKeyManager 的 PRIVATE_KEY（PKCS8，Base64）。 */
    private const val PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALzoBR3Io5wxQH+IMy0ekUE50s9ZOEPOI/y3bSwXSZEqU0EgDGVRnFBYhr8MSEkv2THTn5HKsUM3gHd3rpQhRG2JrcaFY+iIEJs2iueXvfKd8UqTUFQOZrc7xCz1npg4KResY4VAEN+W19ez/DsKiCS5LaxmwE844xWibTgjspY9AgMBAAECgYA7C/cVcTKaztZPRr5gWw6iKbXYNBYfM58SDi+kuX64TleJor/dJ55JivJLY0ZxAfDM304gXw/7Z6zTKui5ypA9duYu1Pohk/e5UUMFr0+cEyWzTjCJp/XaA/gEhH8g311qeWcALSo6q55vYtTJpc/OUn98nlsrOMv4XLgaBI2fwQJBAPBw7TkR4Xuvue2tfxh2Fn2x2ONJSRjckS7EipfdLSsgZCbwdQ2XL/k6VX0lMfQaLQQ6YCF/vrALlgkXFBuzFpECQQDJIWJGcoM95ms546hHBzu7DONmEpEh9b9nMEqRmOkzkitxvGApv1VjvZaX6gMrSANADmoz4lSTGXFea4nviJLtAkEA29v3H4NZMveJxWsrV5vLjx5MG/FMdP5jh2dS7/DgN5pD2lNwRYAk7vnHaErVtccluMMEWj1siZ/ejutaiMWm8QJBAIMM+4nHZ3hXwJoRmj3dmq/AMBL8GhC0nShRMOU5awmtPh13jnjlMHAywgLt+W6kF2oPemegG1dVhqbtDw1CQekCQH3xugE28LS6e29233PUBdipwoyRcbDeth70WDS3l4bl7XlWbTXOWF/D28ZZ49NSr1dRhAQwM8VVC/gHdvNpcqA="

    val privateKey: java.security.PrivateKey by lazy {
        java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(PRIVATE_KEY)))
    }

    /** 来自百度 8.6.7 libencryption.so 的 MD5_HEAD 盐值。 */
    private const val V2_SALT = "CARLIFE"

    /** 「2.0;<rnd>」分支（EncryptionUtils.getVerifyCode，百度原生库）。条件与百度一致。 */
    fun isV2Branch(random: String): Boolean {
        val parts = javaSplit(random, ";")
        return parts.size >= 2 && parts[0] == "2.0" && parts[1].isNotEmpty()
    }

    /** Java 的 String.split(regex)：尾部空元素会被丢弃。 */
    private fun javaSplit(s: String, sep: String): List<String> {
        val p = s.split(sep)
        return if (p.size == 1) p else p.dropLastWhile { it.isEmpty() }
    }

    /**
     * PrivateKeyManager.versionVerify，versionName = Handshake.MD_VERSION。没有「_」时百度会崩溃
     * （substring(-1)），这里字符串原样保留。非数字部分跳过（百度会捕获异常）。
     */
    fun versionVerify(str: String, mdVersion: String = Handshake.MD_VERSION): String {
        val idx = str.indexOf('_')
        if (idx < 0) return str
        val suffix = str.substring(idx)
        val stripped = str.replace(suffix, "")
        val theirs = javaSplit(suffix.replace("_", ""), ".")
        val ours = javaSplit(mdVersion, ".")
        if (ours.size != theirs.size) return stripped + ERROR_VERSION
        for (i in ours.indices) {
            val a = theirs[i].toIntOrNull() ?: continue
            val b = ours[i].toIntOrNull() ?: continue
            if (a > b) return stripped + ERROR_VERSION
            if (a < b) return str
        }
        return str
    }

    /**
     * 百度的 CONNECT_ENCRYPTION_MD_INFO 字符串：来自所发送 MD_INFO 的 brand + model + sdk
     * （CarlifeDeviceInfoManager :174）。车机从同一个 MD_INFO 中取出它，因此字段必须逐字节一致。
     */
    fun mdInfoKey(info: DeviceInfo) = info.brand + info.model + info.sdk

    /**
     * 来自 libencryption.so 的 getVerifyCode：md5(MD5_HEAD + str) 以小写 hex 输出（md5.cpp 中的 MD5 类，HEX_NUMBERS
     * "0123456789abcdef"）。跳过 APK 签名校验（APP_SIGNATURE_CARLIFE）：百度在签名不匹配时
     * 只是返回 null。车机通过 getVerifyResult 校验：相同的 md5 和 strcmp。
     */
    fun verifyCode(str: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest((V2_SALT + str).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 用于 MD_AUTHEN_RESPONSE 的 encryptValue。2.0 分支：verifyCode(mdInfo + 「;」之后的部分)，与
     * PrivateKeyManager.encryptContentProcess 相同；否则 RSA 签名。
     */
    fun encryptValue(random: String, mdInfo: String = ""): String {
        if (isV2Branch(random)) return verifyCode(mdInfo + javaSplit(random, ";")[1])
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        val sig = cipher.doFinal(versionVerify(random).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(sig)
    }
}

/**
 * CarLife 2.0 的 MD -> HU 消息序列，复刻百度 CarLife 8.6.7。
 * 纯逻辑、不依赖 android.*，以便离线校验顺序和字节。
 */
object Handshake {
    /** MODULE_STATUS 在起始消息包之后 500 毫秒发出（CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus, :752-758）。 */
    const val MODULE_STATUS_DELAY_MS = 500L

    /**
     * INIT_DONE 之后这么多毫秒仍无 VIDEO_ENCODER_START -> 视频自行启动。大众途岳 XR 2026（CarLife 2.1）不发送 START
     * 并且约 5.5 秒无视频会断开 USB；百度车机在 initDecoder 后立即发送 START（CarlifeActivity :486-497）。
     */
    const val START_FALLBACK_MS = 1500L

    const val MD_VERSION = "8.6.7"

    /** carlifeType：1 = CarLife 应用（MixConfig.isCarLifeApp，CarlifeDeviceInfoManager :109-113）。 */
    const val MD_TYPE_CARLIFE_APP = 1

    /**
     * 在回复 HU_PROTOCOL_VERSION 后立即发送的起始消息包，不等待车机的任何消息：
     * MATCH_STATUS、FOREGROUND、SCREEN_ON（如果屏幕开启）、MD_INFO
     * （CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus :743-751 + sendPhoneStatusMsg :995-1021）。
     */
    fun startBatch(screenOn: Boolean, info: DeviceInfo): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1)))
        out.add(OutCmd(Msg.FOREGROUND, null))
        if (screenOn) out.add(OutCmd(Msg.SCREEN_ON, null))
        out.add(OutCmd(Msg.MD_INFO, Msgs.deviceInfo(info)))
        return out
    }

    fun moduleStatus(huMajor: Int) = OutCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(huMajor))

    /**
     * 对 STATISTIC_INFO 的响应（CarlifeConnectHsgHandler :434-499）：MD_AUTHEN_RESULT{true}，然后是
     * FEATURE_CONFIG 请求（0x10051，空），再是 CAR_DATA_SUBSCRIBE（0x10031，空）。
     */
    fun onStatisticInfo(sendFeatureConfigRequest: Boolean): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        if (sendFeatureConfigRequest) out.add(OutCmd(Msg.MD_FEATURE_CONFIG_REQUEST, null))
        out.add(OutCmd(Msg.CAR_DATA_SUBSCRIBE, null))
        return out
    }

    /** HU_AUTHEN_REQUEST 中形如「2.0;<rnd>」的 randomValue（v2 版请求）。 */
    fun isAuthV2(random: String?) = random != null && random.startsWith("2.0;")

    /** 对 VIDEO_ENCODER_FRAME_RATE_CHANGE 的合法响应：3..30（VideoMsgHandler.changeFrameRate）。 */
    fun frameRateChangeAllowed(fps: Int) = fps in 3..30

    const val DEFAULT_FPS = 30

    /** 根据 FRAME_RATE_CHANGE 应用的最低帧率（更低时编码器/车机已来不及更新静态画面）。 */
    const val MIN_APPLIED_FPS = 10

    /**
     * 用于 INIT_DONE 的 fps。自动：与百度一致（INIT 中的 fps，如果在 1..30 内则使用，否则 30）；15 和 30 为固定值。
     * 本田发送 fps=0，起亚 15，马自达 30。[huMaxFps] - 针对性能较弱的车机设置的上限（先锋 RM56E8B，h277），null = 无限制。
     */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null): Int =
        capFps(setting.fixed ?: if (requested in 1..30) requested else DEFAULT_FPS, huMaxFps)

    /** 针对特定车机的 fps 上限（null = 无限制）。 */
    fun capFps(fps: Int, huMaxFps: Int?): Int = huMaxFps?.let { minOf(fps, it) } ?: fps
}