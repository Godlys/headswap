package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity

/** MainActivity 内的屏幕：自身是一个 View，可见期间每 500 毫秒刷新一次，响应「返回」。 */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host
    abstract val view: View

    /** 屏幕变为可见（包括从系统设置返回后）。 */
    open fun onShow() {}

    open fun onHide() {}

    /** 屏幕可见期间，每 500 毫秒调用一次。 */
    open fun tick() {}

    /** true = 「返回」按键已由屏幕处理。 */
    open fun onBack(): Boolean = false

    open fun onPermissionResult() {}

    /** 第三方 activity/对话框返回了结果（通过 CDM 选择车辆）。 */
    open fun onActivityResultDone() {}

    /** 调试用（adb --ei scroll_y N）：滚动屏幕，以便截取底部内容。 */
    fun scrollTo(y: Int) {
        fun find(v: View): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(view)?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }
}

val Context.isNight: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** UI 自身的设置：新手引导已完成、无法自动判定的步骤的手动标记。 */
class UiPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    /** 「无障碍功能免责声明」弹窗（2.6.0）已展示过，或无需展示（新手已完成引导）。 */
    var a11yNoticeShown: Boolean
        get() = sp.getBoolean("a11yNoticeShown", false)
        set(v) = sp.edit().putBoolean("a11yNoticeShown", v).apply()

    /** 界面主题：system / light / medium / dark（参见 AppTheme）。 */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    fun isManualDone(id: String): Boolean = sp.getBoolean("manual_$id", false)

    fun setManualDone(id: String, v: Boolean) = sp.edit().putBoolean("manual_$id", v).apply()
}
