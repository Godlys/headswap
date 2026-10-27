package com.yegorkapomidorka.headswap.link.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yegorkapomidorka.headswap.BuildConfig

/**
 * 用户选择的汽车：车机的 Bluetooth 设备和 CompanionDeviceManager 绑定。数据存放在独立的
 * prefs 文件中。触发器（ACL 接收器，[CarAclReceiver]）仅在已选择汽车且为无线 beta 构建时才会启用。
 */
class CarProfile(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_profile", Context.MODE_PRIVATE)

    /** MAC 按系统原样保存（大小写可能不同，比较时使用 [CarMac.same]）。 */
    val btMac: String? get() = sp.getString("btMac", null)

    val btName: String? get() = sp.getString("btName", null)

    /** CDM 绑定 id（API 33+）；-1 表示无（API 29-32 不发放 id，按 MAC 绑定）。 */
    val cdmAssociationId: Int get() = sp.getInt("cdmAssociationId", -1)

    val isSet: Boolean get() = btMac != null

    /**
     * 这辆车如何无线连接。与 [btMac] 绑定：换了车或重置后会重新变为 UNKNOWN。
     * 未选择汽车时不写入。
     */
    var connMode: CarConnMode
        get() = if (!isSet) CarConnMode.UNKNOWN else CarConnMode.entries.firstOrNull { it.name == sp.getString("connMode", null) } ?: CarConnMode.UNKNOWN
        set(v) {
            if (isSet) sp.edit().putString("connMode", v.name).apply()
        }

    fun save(mac: String, name: String?, associationId: Int) {
        val ed = sp.edit()
        if (!CarMac.same(btMac, mac)) ed.remove("connMode")
        ed.putString("btMac", mac).putString("btName", name).putInt("cdmAssociationId", associationId).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        /** 启用/禁用 ACL 接收器。仅在已选择汽车且为无线 beta 构建时启用。 */
        fun enableTrigger(ctx: Context, on: Boolean) {
            val enable = on && BuildConfig.WIRELESS_BETA && CarProfile(ctx).isSet
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx),
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** 接收器状态，用于上报。DEFAULT = 与 manifest 一致（enabled=false），即已关闭。 */
        fun triggerEnabled(ctx: Context): Boolean =
            ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        private fun component(ctx: Context) = ComponentName(ctx, CarAclReceiver::class.java)
    }
}
