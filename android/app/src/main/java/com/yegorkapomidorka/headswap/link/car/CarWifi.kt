package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/**
 * 汽车的 Wi-Fi 热点（车机自己开启）：手机通过 WifiNetworkSpecifier 加入汽车网络，同时不丢失
 * 主网络的互联网。独立 prefs 文件 `car_wifi`，已从备份中排除（res/xml/backup_rules.xml、data_extraction_rules.xml）。
 * SSID 和密码除了这个文件和设置界面，不写入任何地方：不写 WLog、不写 AppLog、不写报告。
 */
class CarWifi(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_wifi", Context.MODE_PRIVATE)

    val ssid: String? get() = sp.getString("ssid", null)

    /** WPA2/WPA3 密码；空字符串（或 null）表示开放网络。 */
    val pass: String get() = sp.getString("pass", null).orEmpty()

    val isSet: Boolean get() = !ssid.isNullOrEmpty()

    /** 曾在此网络中成功连上 6/6：车机通过自己的 Wi-Fi 接入，请求汽车网络时无需等待。更换 SSID 时重置。 */
    val proven: Boolean get() = sp.getBoolean("proven", false)

    fun markProven() {
        sp.edit().putBoolean("proven", true).apply()
    }

    /**
     * 已成功加入汽车网络的安全方式（"open"/"wpa2"/"wpa3"），或为 null。Android 会将同一 SSID 的 WPA2 和 WPA3
     * 视为不同网络，并分配不同的随机 MAC：车机会看到一个新设备（帕维尔 2026-10-19：Known devices 里出现 4 个重复）。
     */
    val sec: String? get() = sp.getString("sec", null)

    fun rememberSec(label: String) {
        if (label != sec) sp.edit().putString("sec", label).apply()
    }

    fun save(ssid: String, pass: String) {
        val ed = sp.edit().putString("ssid", ssid).putString("pass", pass)
        if (ssid != this.ssid) ed.remove("proven")
        if (ssid != this.ssid || pass != this.pass) ed.remove("sec")
        ed.apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
