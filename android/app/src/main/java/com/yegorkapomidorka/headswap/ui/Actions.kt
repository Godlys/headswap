package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaServerCheck
import com.yegorkapomidorka.headswap.aa.ManualServerCheck
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.ProbeResult
import com.yegorkapomidorka.headswap.aa.TranscodeSelfCheck

/**
 * 以前直接放在 MainActivity 里的操作（UI 按钮和 intent 命令）：
 * USB 扫描、桥接服务的启动/停止、启动车机服务器、诊断。
 */
object Actions {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    /** 扫描：显示并记录配件的所有字段；未授权则请求权限；已授权则启动服务。 */
    fun scan(ctx: Context, reason: String) {
        val usb = ctx.getSystemService(UsbManager::class.java)
        AppLog.i("сканирование ($reason)")
        val list = usb.accessoryList
        if (list == null || list.isEmpty()) {
            AppLog.i("аксессуар не найден (getAccessoryList=${if (list == null) "null" else "[]"})")
            CarLifeState.accessory = "не найден"
            CarLifeState.status = "ждём аксессуар"
            return
        }
        for ((i, acc) in list.withIndex()) {
            AppLog.i("аксессуар[$i]: ${MainActivity.describe(acc)} hasPermission=${usb.hasPermission(acc)}")
            val isBaidu = acc.manufacturer == "Baidu" && acc.model == "CarLife"
            AppLog.i("  строки Baidu/CarLife: ${if (isBaidu) "да" else "НЕТ (другие строки, смотри выше)"}")
        }
        val acc = list[0]
        CarLifeState.accessory = MainActivity.describe(acc)
        if (usb.hasPermission(acc)) {
            startBridge(ctx)
        } else {
            AppLog.i("прошу разрешение на аксессуар")
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(MainActivity.ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(acc, pi)
        }
    }

    fun startBridge(ctx: Context) {
        AppLog.i("запускаю сервис")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
    }

    fun stopBridge(ctx: Context) {
        AppLog.i("кнопка Стоп")
        HeadswapStatus.markUserStop()
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
    }

    /** AA 与 TLS 证书的离线自检：结果写入日志和 AaState.selftest。 */
    fun aaSelftest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ AaCreds.selfTest(app) }, "aa-selftest").start()
    }

    fun transcodeTest(ctx: Context, opts: String) = TranscodeSelfCheck.run(ctx, opts)

    /** Android Auto 设置（车机服务器）：HyperOS 把它们藏在了系统设置之外。 */
    fun openAaSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppLog.i("открыты настройки Android Auto")
        } catch (e: Exception) {
            AppLog.e("не открыть настройки Android Auto", e)
            Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_open_aa_failed, "${e.javaClass.simpleName}: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 主屏幕和引导流程中的「打开 Android Auto」按钮：车机服务器由用户自行开启
     * （⋮ →「启用车机服务器」），因此这里只标记请求并打开 AA 设置。
     */
    fun startServerFromUi(ctx: Context) {
        HeadswapStatus.markServerRequested()
        openAaSettings(ctx)
    }

    /**
     * 手动探测车机服务器（「开发者」页）：用完整握手尝试一次，结果以 toast 和日志中的 SRVCHECK 行呈现。
     * 仅在未连接桥接和 USB 时进行：探测会干扰 Android Auto。不影响主屏幕状态。
     */
    fun checkServer(ctx: Context) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        val str = AppLocale.wrap(app)
        val refusal = AaServerCheck.start(app) { result, detail ->
            toast(
                when (result) {
                    ProbeResult.ALIVE -> str.getString(R.string.dev_check_server_alive)
                    ProbeResult.REFUSED -> str.getString(R.string.dev_check_server_refused)
                    ProbeResult.HUNG -> str.getString(R.string.dev_check_server_hung)
                    ProbeResult.ERROR -> str.getString(R.string.dev_check_server_error, detail ?: "?")
                },
            )
        }
        if (refusal != null) {
            toast(
                str.getString(
                    when (refusal) {
                        ManualServerCheck.Refusal.BRIDGE -> R.string.dev_check_server_bridge
                        ManualServerCheck.Refusal.USB -> R.string.dev_check_server_usb
                        ManualServerCheck.Refusal.RUNNING -> R.string.dev_check_server_running
                    },
                ),
            )
        }
    }

    /** 无车环境下检查手机麦克风：AudioRecord 录制 2 秒，将字节数（预期约 64000）和峰值写入日志。 */
    fun micTest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            val m = PhoneMicCapture(app)
            var bytes = 0L
            val ok = m.start { bytes += it.size }
            AppLog.i("mic_test: старт=$ok (RECORD_AUDIO ${if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "есть" else "НЕТ"})")
            if (ok) {
                Thread.sleep(2000)
                m.stop()
                Thread.sleep(300)
                AppLog.i("mic_test: за 2 с получено $bytes Б (ждём около 64000)")
            }
        }, "mic-test").start()
    }
}
