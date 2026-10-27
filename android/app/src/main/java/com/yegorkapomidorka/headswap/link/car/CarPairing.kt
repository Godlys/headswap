package com.yegorkapomidorka.headswap.link.car

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * 通过 CompanionDeviceManager 选择车辆：显示蓝牙设备列表的系统对话框。关联（绑定）赋予应用
 * 从后台启动前台服务的豁免权，MAC 和名称则作为 ACL 接收器（[CarAclReceiver]）的过滤条件。
 *
 * API 33+：结果通过回调（onAssociationCreated）返回，并在 onActivityResult（EXTRA_ASSOCIATION）中重复；
 * API 29-32：仅 onActivityResult（EXTRA_DEVICE），没有关联 id。写入是幂等的：[store] 不会重复写入。
 */
object CarPairing {
    /** CDM 对话框的 startIntentSenderForResult 请求码；结果由 MainActivity.onActivityResult 捕获。 */
    const val REQ_CAR_PAIR = 38

    fun available(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
            ctx.getSystemService(CompanionDeviceManager::class.java) != null

    /** 在我们列表中选中的名称：CDM 在 33+ 返回 displayName，但部分固件返回的是 MAC。 */
    @Volatile private var pickedName: String? = null

    /**
     * 先显示我们自己带名称的已配对设备列表（CDM 系统对话框会显示周围所有设备，且常常只有 MAC），
     * 再由 CDM 确认所选的那个设备（按地址过滤，singleDevice）。
     */
    @SuppressLint("MissingPermission")
    fun start(activity: Activity) {
        if (!available(activity)) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "unavailable")
            return
        }
        val bonded = try {
            activity.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { (try { it.name } catch (_: SecurityException) { null } ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) {
            WLog.ev(WLog.TRIGGER, "pair-list", "bonded" to 0)
            associate(activity, null, null)      // 没有权限或没有已配对设备：直接使用系统对话框
            return
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(com.yegorkapomidorka.headswap.R.string.wl_car_pick)
            .setItems(bonded.map { it.first }.toTypedArray()) { _, i -> associate(activity, bonded[i].second, bonded[i].first) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun associate(activity: Activity, mac: String?, name: String?) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return
        pickedName = name
        val filter = BluetoothDeviceFilter.Builder().apply { if (mac != null) setAddress(mac) }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(mac != null)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 33) cdm.associate(request, activity.mainExecutor, ModernCallback(activity))
            else @Suppress("DEPRECATION") cdm.associate(request, LegacyCallback(activity), Handler(Looper.getMainLooper()))
            WLog.ev(WLog.TRIGGER, "pair-start", "api" to Build.VERSION.SDK_INT)
        } catch (e: Exception) {
            AppLog.e("CDM associate", e)
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to e.javaClass.simpleName)
        }
    }

    /** MainActivity.onActivityResult(REQ_CAR_PAIR) 中 CDM 对话框的结果。 */
    fun onResult(ctx: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            WLog.ev(WLog.TRIGGER, "pair-cancel", "code" to resultCode)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) {
                saveFromInfo(ctx, info)
                return
            }
        }
        val dev = device(data)
        if (dev == null) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "no-device")
            return
        }
        // 读取名称需要 BLUETOOTH_CONNECT 权限（31+）；没有该权限会抛 SecurityException，此时不显示名称
        val name = try { dev.name } catch (_: SecurityException) { null }
        store(ctx, dev.address, name, -1)
    }

    /** 忘记车辆：解除 CDM 关联、清除配置文件、关闭触发器。 */
    @SuppressLint("MissingPermission")      // stopObservingDevicePresence：见 observe
    fun forget(ctx: Context) {
        val profile = CarProfile(ctx)
        val mac = profile.btMac
        val id = profile.cdmAssociationId
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
        if (cdm != null) {
            @Suppress("DEPRECATION")
            try {
                if (Build.VERSION.SDK_INT >= 31 && mac != null) {
                    try { cdm.stopObservingDevicePresence(mac.uppercase()) } catch (_: Exception) {}
                }
                if (Build.VERSION.SDK_INT >= 33 && id >= 0) cdm.disassociate(id)
                else if (mac != null) cdm.disassociate(mac.uppercase())
            } catch (e: Exception) {
                WLog.ev(WLog.TRIGGER, "forget-fail", "err" to e.javaClass.simpleName)
            }
        }
        profile.clear()
        CarProfile.enableTrigger(ctx, false)
        WLog.ev(WLog.TRIGGER, "forgot", "dev" to WLog.maskMac(mac), "cdm" to id)
    }

    @TargetApi(33)
    private fun saveFromInfo(ctx: Context, info: AssociationInfo) {
        val mac = info.deviceMacAddress?.toString()
        if (mac == null) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "no-mac")
            return
        }
        store(ctx, mac, info.displayName?.toString(), info.id)
    }

    /** 保存车辆并开启触发器。重复（回调与 onActivityResult）会被静默跳过。 */
    private fun store(ctx: Context, mac: String, name: String?, id: Int) {
        val profile = CarProfile(ctx)
        val sameMac = CarMac.same(profile.btMac, mac)
        // 相同 MAC 且要么 id 相同、要么新记录无 id（回调之后的 onActivityResult 路径）：已保存过
        if (sameMac && (id == -1 || id == profile.cdmAssociationId)) return
        val keepId = if (sameMac && id == -1) profile.cdmAssociationId else id
        // 选择了另一辆车：解除旧关联，否则在 33+ 上会积累孤立的 association
        val oldId = profile.cdmAssociationId
        if (Build.VERSION.SDK_INT >= 33 && oldId >= 0 && oldId != keepId) {
            try {
                ctx.getSystemService(CompanionDeviceManager::class.java)?.disassociate(oldId)
            } catch (e: Exception) {
                WLog.ev(WLog.TRIGGER, "forget-fail", "err" to e.javaClass.simpleName)
            }
        }
        // 我们列表中的名称比 CDM 的 displayName 更可靠（部分固件那里是 MAC）
        profile.save(mac, pickedName ?: name ?: if (sameMac) profile.btName else null, keepId)
        CarProfile.enableTrigger(ctx, true)
        WLog.ev(WLog.TRIGGER, "paired", "dev" to WLog.maskMac(mac), "cdm" to id)
        observe(ctx, mac)
    }

    /** 设备在场检测（31+）：备用路径，主触发器是 ACL。可能缺少所需权限：仅记日志。 */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")      // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE 故意未声明：缺少时抛 SecurityException，仅记日志
    private fun observe(ctx: Context, mac: String) {
        if (Build.VERSION.SDK_INT < 31) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            cdm.startObservingDevicePresence(mac.uppercase())
            WLog.ev(WLog.TRIGGER, "presence", "ok" to true)
        } catch (e: Exception) {
            WLog.ev(WLog.TRIGGER, "presence", "ok" to false, "err" to e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun device(data: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        else data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE) as? BluetoothDevice

    private fun launch(activity: Activity, sender: IntentSender) {
        try {
            activity.startIntentSenderForResult(sender, REQ_CAR_PAIR, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to e.javaClass.simpleName)
        }
    }

    /** API 33+：对话框通过 onAssociationPending 到来，就绪的关联通过 onAssociationCreated。 */
    @TargetApi(33)
    @SuppressLint("NewApi")
    private class ModernCallback(private val activity: Activity) : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) = launch(activity, intentSender)

        override fun onAssociationCreated(associationInfo: AssociationInfo) = saveFromInfo(activity, associationInfo)

        override fun onFailure(error: CharSequence?) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "callback")
        }
    }

    /** API 29-32：对话框通过 onDeviceFound 到来，所选设备在 onActivityResult 中。 */
    private class LegacyCallback(private val activity: Activity) : CompanionDeviceManager.Callback() {
        @Deprecated("Deprecated in Java")
        override fun onDeviceFound(chooserLauncher: IntentSender) = launch(activity, chooserLauncher)

        override fun onFailure(error: CharSequence?) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "callback")
        }
    }
}
