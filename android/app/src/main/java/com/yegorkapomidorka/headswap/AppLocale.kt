package com.yegorkapomidorka.headswap

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用界面语言：「system」（默认）、「en」或「ru」。
 * Android 13+：系统级 per-app 语言（LocaleManager，在系统设置中也可见，参见 res/xml/locales_config.xml）。
 * Android 10–12：选择保存在 prefs 中，activity/服务的上下文通过 attachBaseContext 中的 [wrap] 包装。
 */
object AppLocale {
    const val SYSTEM = "system"
    val TAGS = listOf(SYSTEM, "en", "ru")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** 当前选择：33+ 从 LocaleManager 读取（可能已在系统设置中被更改），更低版本从 prefs 读取。 */
    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) return SYSTEM
            return list[0].language.takeIf { it in TAGS } ?: SYSTEM
        }
        return stored(ctx)
    }

    /** 切换语言。33+ 上系统会自动重建 activity；更低版本由调用方执行 recreate()。 */
    fun set(ctx: Context, tag: String) {
        val t = if (tag in TAGS) tag else SYSTEM
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (t == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply()
    }

    /** [set] 之后是否需要手动 recreate()。 */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < 33

    private fun stored(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /**
     * 带有所选语言的上下文，用于 Android 10–12（33+ 上系统已注入 per-app 语言，原样返回）。
     * 在 attachBaseContext 以及从 applicationContext 取字符串的地方使用。
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }
}