package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/**
 * Wi-Fi точка доступа машины (ГУ раздаёт её сам): по ней телефон входит в сеть машины через WifiNetworkSpecifier, не теряя
 * интернет основной сети. Отдельный файл prefs `car_wifi`, исключён из бэкапа (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * SSID и пароль не пишем никуда, кроме этого файла и экрана настроек: ни в WLog, ни в AppLog, ни в отчёт.
 */
class CarWifi(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_wifi", Context.MODE_PRIVATE)

    val ssid: String? get() = sp.getString("ssid", null)

    /** Пароль WPA2/WPA3; пустая строка (или null) - открытая сеть. */
    val pass: String get() = sp.getString("pass", null).orEmpty()

    val isSet: Boolean get() = !ssid.isNullOrEmpty()

    /** 6/6 уже поднимались в этой сети: ГУ подключается через свой Wi-Fi, сеть машины просим без ожидания. Сбрасывается сменой SSID. */
    val proven: Boolean get() = sp.getBoolean("proven", false)

    fun markProven() {
        sp.edit().putBoolean("proven", true).apply()
    }

    fun save(ssid: String, pass: String) {
        val ed = sp.edit().putString("ssid", ssid).putString("pass", pass)
        if (ssid != this.ssid) ed.remove("proven")
        ed.apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
