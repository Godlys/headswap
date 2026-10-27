package com.yegorkapomidorka.headswap.link.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * 自动启动触发器：所选汽车（[CarProfile]）的 ACL_CONNECTED/DISCONNECTED 会启动和熄灭 Wi-Fi 等待。
 * 在 manifest 中为关闭状态（enabled=false），选中汽车后由 [CarProfile.enableTrigger] 启用。其他设备一律忽略。
 * Android 12+ 上若没有 BLUETOOTH_CONNECT 权限，系统不会投递这些广播。
 */
class CarAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!BuildConfig.WIRELESS_BETA) return
        val mac = CarProfile(context).btMac ?: return
        AppLog.init(context)      // 广播可能在 activity 和 service 之前拉起进程
        val addr = device(intent)?.address
        if (!CarMac.same(addr, mac)) {
            if (ignoreLog.tryAcquire()) WLog.ev(WLog.TRIGGER, "ignore", "action" to action.substringAfterLast('.'))
            return
        }
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            WLog.ev(WLog.TRIGGER, "acl-connected", "dev" to WLog.maskMac(addr))
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
            } catch (e: Exception) {      // ForegroundServiceStartNotAllowedException 等：后台启动前台服务被禁止时会抛出这些异常
                AppLog.e("ACL: старт сервиса", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName)
            }
        } else {
            WLog.ev(WLog.TRIGGER, "acl-disconnected", "dev" to WLog.maskMac(addr))
            // 服务不存在——没有可熄灭的东西；无需从后台对空服务调用 startService
            if (!BridgeService.alive) return
            try {
                context.startService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
            } catch (e: Exception) {
                AppLog.e("ACL: отбой сервису", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName, "action" to "off")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        /** 手机周围其他设备很多（耳机、手表等）：日志频率不超过每分钟一次。 */
        val ignoreLog = MinInterval(60_000L) { SystemClock.elapsedRealtime() }
    }
}
