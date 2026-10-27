package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs

/** 出行前需要开启的全部事项：顺序、文案、「已完成」的自动检测。引导流程与检查清单共用。 */
enum class StepId(val key: String) {
    AA_DEV("aa_dev"),
    AA_UNKNOWN("aa_unknown"),
    AUTOSTART("autostart"),
    NOTIF("notif"),
    BACKGROUND("background"),
    MIC("mic"),
    BLUETOOTH("bluetooth"),
    LOCATION("location"),
    FIRST("first"),

    /** 选择车型：社区验证过的设置（CarPreset）。最后一步；老手会跳过。 */
    CAR("car"),
}

/**
 * 步骤文案 - 资源 id（hint/extra 可缺省）。步骤正文要么是带编号的列表（string-array [list]），
 * 要么是段落（[body]，选车步骤）。转成字符串由 UI 完成：ctx.getString，列表通过 [listItems]。
 */
class StepInfo(
    val id: StepId,
    val title: Int,
    val list: Int?,
    val body: Int?,
    val hint: Int?,
    val primary: Int,
    val extra: Int?,
    val skip: Int,
    /** 用于检查清单的简称。 */
    val short: Int,
) {
    /** 列表项；在后台步骤中，关于「自动启动」的项（3-4）仅存在于小米设备。 */
    fun listItems(ctx: Context, xiaomi: Boolean = Readiness.isXiaomi()): List<String> {
        val all = list?.let { ctx.resources.getStringArray(it).toList() } ?: return emptyList()
        return if (id == StepId.BACKGROUND && !xiaomi) all.take(2) else all
    }
}

object Readiness {
    val STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.AA_DEV, R.string.step_aa_dev_title, R.array.step_aa_dev_list, null, null,
            R.string.step_aa_dev_primary, null, R.string.onb_already_done, R.string.step_aa_dev_short,
        ),
        StepInfo(
            StepId.AA_UNKNOWN, R.string.step_aa_unknown_title, R.array.step_aa_unknown_list, null, R.string.step_aa_unknown_hint,
            R.string.step_aa_unknown_primary, null, R.string.onb_already_done, R.string.step_aa_unknown_short,
        ),
        StepInfo(
            StepId.AUTOSTART, R.string.step_autostart_title, R.array.step_autostart_list, null, R.string.step_autostart_hint,
            R.string.step_autostart_primary, null, R.string.onb_got_it, R.string.step_autostart_short,
        ),
        StepInfo(
            StepId.NOTIF, R.string.step_notif_title, R.array.step_notif_list, null, R.string.step_notif_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_notif_short,
        ),
        StepInfo(
            StepId.BACKGROUND, R.string.step_background_title, R.array.step_background_list, null, null,
            R.string.step_background_primary, R.string.step_background_extra, R.string.onb_already_done, R.string.step_background_short,
        ),
        StepInfo(
            StepId.MIC, R.string.step_mic_title, R.array.step_mic_list, null, R.string.step_mic_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_mic_short,
        ),
        StepInfo(
            StepId.BLUETOOTH, R.string.step_bluetooth_title, R.array.step_bluetooth_list, null, R.string.step_bluetooth_hint,
            R.string.step_bluetooth_primary, null, R.string.onb_already_done, R.string.step_bluetooth_short,
        ),
        StepInfo(
            StepId.LOCATION, R.string.step_location_title, R.array.step_location_list, null, R.string.step_location_hint,
            R.string.step_location_primary, null, R.string.onb_already_done, R.string.step_location_short,
        ),
        StepInfo(
            StepId.FIRST, R.string.step_first_title, R.array.step_first_list, null, R.string.step_first_hint,
            R.string.step_first_primary, null, R.string.onb_back, R.string.step_first_short,
        ),
        StepInfo(
            StepId.CAR, R.string.step_car_title, null, R.string.step_car_body, null,
            R.string.car_apply, null, R.string.onb_skip, R.string.step_car_short,
        ),
    )

    fun info(id: StepId): StepInfo = STEPS.first { it.id == id }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** true/false - 已自动检测；null - 无法检测（只能手动勾选「已完成」）。 */
    fun auto(ctx: Context, id: StepId): Boolean? = when (id) {
        StepId.NOTIF -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        StepId.MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        StepId.BACKGROUND -> batteryOk(ctx)
        StepId.CAR -> if (AaPrefs(ctx).carPreset != null) true else null      // 跳过 - 手动勾选
        StepId.LOCATION -> try {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** 已完成：优先自动检测，无法检测时用手动勾选。小米上的后台步骤额外要求手动勾选。 */
    fun isDone(ctx: Context, id: StepId): Boolean {
        val ui = UiPrefs(ctx)
        if (id == StepId.BACKGROUND) return batteryOk(ctx) && (!isXiaomi() || ui.isManualDone(id.key))
        return auto(ctx, id) ?: ui.isManualDone(id.key)
    }

    // --- 按钮操作 ---

    fun appDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestIgnoreBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.e("не открыть запрос исключения из оптимизации батареи", e)
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                appDetails(ctx)
            }
        }
    }

    /** 小米自启动（安全中心）；打不开则回退到应用详情页。 */
    fun openXiaomiAutostart(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.i("автозапуск Xiaomi не открылся (${e.javaClass.simpleName}), открываю карточку приложения")
            try {
                appDetails(ctx)
            } catch (e2: Exception) {
                AppLog.e("не открыть карточку приложения", e2)
            }
        }
    }

    fun openLocationSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun openBluetoothSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun openSettings(ctx: Context, action: String) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть $action", e)
        }
    }
}
