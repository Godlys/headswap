package com.yegorkapomidorka.headswap

import android.content.Context
import android.content.res.Configuration
import com.yegorkapomidorka.headswap.ui.UiPrefs

/**
 * 界面主题：「system」（默认，跟随系统）、「light」、「medium」（中性深灰）、「dark」。
 * 选择保存在 prefs "ui"（UiPrefs.theme）中。MainActivity 在 attachBaseContext 中通过 [wrap] 包装上下文：
 * light 强制 uiMode night=NO，medium 和 dark 强制 night=YES（medium 随后通过 [res] 将颜色替换为 *_mid 变体）。
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val MEDIUM = "medium"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, MEDIUM, DARK)

    /** 是否启用「中等」主题：Context.clr() 和资源引用都通过 [res] 处理。在 [wrap] 中设置。 */
    @Volatile
    var medium = false
        private set

    fun get(ctx: Context): String = UiPrefs(ctx).theme.let { if (it in MODES) it else SYSTEM }

    fun set(ctx: Context, mode: String) {
        UiPrefs(ctx).theme = if (mode in MODES) mode else SYSTEM
    }

    /** 带有强制 night 模式的上下文；已包装的 [base] 中的语言环境得以保留（复制其配置）。 */
    fun wrap(base: Context): Context {
        val mode = get(base)
        medium = mode == MEDIUM
        val night = when (mode) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            MEDIUM, DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    /** 当前主题的资源：在「中等」主题下返回 *_mid 变体，否则返回相同 id。使用显式映射，而非 getIdentifier（后者会被 shrinkResources 移除）。 */
    fun res(id: Int): Int = if (medium) MID[id] ?: id else id

    private val MID: Map<Int, Int> = mapOf(
        R.color.bg to R.color.bg_mid,
        R.color.surf to R.color.surf_mid,
        R.color.surf2 to R.color.surf2_mid,
        R.color.on to R.color.on_mid,
        R.color.on2 to R.color.on2_mid,
        R.color.line to R.color.line_mid,
        R.color.pri to R.color.pri_mid,
        R.color.onpri to R.color.onpri_mid,
        R.color.acc to R.color.acc_mid,
        R.color.pric to R.color.pric_mid,
        R.color.onpric to R.color.onpric_mid,
        R.color.ok to R.color.ok_mid,
        R.color.okc to R.color.okc_mid,
        R.color.onok to R.color.onok_mid,
        R.color.warn to R.color.warn_mid,
        R.color.warnc to R.color.warnc_mid,
        R.color.err to R.color.err_mid,
        R.color.errc to R.color.errc_mid,
        R.color.onerr to R.color.onerr_mid,
        R.color.ripple to R.color.ripple_mid,
        R.color.radio_tint to R.color.radio_tint_mid,
        R.drawable.switch_thumb to R.drawable.switch_thumb_mid,
        R.drawable.switch_track to R.drawable.switch_track_mid,
        R.drawable.ic_ring_active to R.drawable.ic_ring_active_mid,
    )
}