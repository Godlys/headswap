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
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Всё, что надо включить перед поездкой: порядок, тексты, автоопределение «уже сделано». Общее для онбординга и чеклиста. */
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

    /** Выбор машины: проверенные сообществом настройки (CarPreset). Последний шаг; опытные пропускают. */
    CAR("car"),
}

/** Тексты шага - id строковых ресурсов (hint/extra могут отсутствовать); в строку превращает UI через ctx.getString, тело - через [bodyText]. */
class StepInfo(
    val id: StepId,
    val title: Int,
    val body: Int,
    val hint: Int?,
    val primary: Int,
    val extra: Int?,
    val skip: Int,
    /** Короткое название для чеклиста. */
    val short: Int,
) {
    /** Тело шага: в тексте службы автозапуска подставляется её название из настроек спецвозможностей. */
    fun bodyText(ctx: Context): String =
        if (id == StepId.AUTOSTART) ctx.getString(body, ctx.getString(R.string.autosrv_label)) else ctx.getString(body)
}

object Readiness {
    val STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.AA_DEV, R.string.step_aa_dev_title,
            R.string.step_aa_dev_body,
            R.string.step_aa_dev_hint,
            R.string.step_aa_dev_primary, null, R.string.onb_already_done, R.string.step_aa_dev_short,
        ),
        StepInfo(
            StepId.AA_UNKNOWN, R.string.step_aa_unknown_title,
            R.string.step_aa_unknown_body,
            null,
            R.string.step_aa_unknown_primary, null, R.string.onb_already_done, R.string.step_aa_unknown_short,
        ),
        StepInfo(
            StepId.AUTOSTART, R.string.step_autostart_title,
            R.string.step_autostart_body,
            R.string.step_autostart_hint,
            R.string.step_autostart_primary, R.string.step_autostart_extra, R.string.onb_skip, R.string.step_autostart_title,
        ),
        StepInfo(
            StepId.NOTIF, R.string.step_notif_title,
            R.string.step_notif_body,
            null, R.string.onb_allow, null, R.string.onb_later, R.string.step_notif_title,
        ),
        StepInfo(
            StepId.BACKGROUND, R.string.step_background_title,
            R.string.step_background_body,
            R.string.step_background_hint,
            R.string.step_background_primary, R.string.step_background_extra, R.string.onb_already_done, R.string.step_background_title,
        ),
        StepInfo(
            StepId.MIC, R.string.step_mic_title,
            R.string.step_mic_body,
            null, R.string.onb_allow, null, R.string.onb_later, R.string.step_mic_title,
        ),
        StepInfo(
            StepId.BLUETOOTH, R.string.step_bluetooth_title,
            R.string.step_bluetooth_body,
            null, R.string.step_bluetooth_primary, null, R.string.onb_already_done, R.string.step_bluetooth_title,
        ),
        StepInfo(
            StepId.LOCATION, R.string.step_location_title,
            R.string.step_location_body,
            R.string.step_location_hint,
            R.string.step_location_primary, null, R.string.onb_already_done, R.string.step_location_title,
        ),
        StepInfo(
            StepId.FIRST, R.string.step_first_title,
            R.string.step_first_body,
            R.string.step_first_hint,
            R.string.step_first_primary, null, R.string.onb_back, R.string.step_first_title,
        ),
        StepInfo(
            StepId.CAR, R.string.step_car_title,
            R.string.car_intro,
            R.string.step_car_hint,
            R.string.step_car_primary, null, R.string.onb_skip, R.string.step_car_short,
        ),
    )

    fun info(id: StepId): StepInfo = STEPS.first { it.id == id }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** true/false - определено автоматически; null - определить нельзя (только ручная отметка «Уже сделано»). */
    fun auto(ctx: Context, id: StepId): Boolean? = when (id) {
        StepId.AUTOSTART -> AutoServer.isConnected
        StepId.NOTIF -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        StepId.MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        StepId.BACKGROUND -> batteryOk(ctx)
        StepId.CAR -> if (AaPrefs(ctx).carPreset != null) true else null      // пропуск - ручная отметка
        StepId.LOCATION -> try {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Выполнено: автоопределение, а где его нет - ручная отметка. Фон на Xiaomi требует ещё и ручной отметки. */
    fun isDone(ctx: Context, id: StepId): Boolean {
        val ui = UiPrefs(ctx)
        if (id == StepId.BACKGROUND) return batteryOk(ctx) && (!isXiaomi() || ui.isManualDone(id.key))
        return auto(ctx, id) ?: ui.isManualDone(id.key)
    }

    // --- действия кнопок ---

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

    /** Автозапуск Xiaomi (Безопасность); не вышло - карточка приложения. */
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
