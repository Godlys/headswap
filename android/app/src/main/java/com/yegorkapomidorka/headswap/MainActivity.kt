package com.yegorkapomidorka.headswap

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaProbe
import com.yegorkapomidorka.headswap.link.car.CarPairing
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.ui.Actions
import com.yegorkapomidorka.headswap.ui.DevScreen
import com.yegorkapomidorka.headswap.ui.DiagScreen
import com.yegorkapomidorka.headswap.ui.HomeScreen
import com.yegorkapomidorka.headswap.ui.OnboardingScreen
import com.yegorkapomidorka.headswap.ui.Screen
import com.yegorkapomidorka.headswap.ui.SettingsScreen
import com.yegorkapomidorka.headswap.ui.SettingsSection
import com.yegorkapomidorka.headswap.ui.SettingsSectionScreen
import com.yegorkapomidorka.headswap.ui.StepId
import com.yegorkapomidorka.headswap.ui.UiPrefs
import com.yegorkapomidorka.headswap.ui.UpgradeNotice
import com.yegorkapomidorka.headswap.update.Updater

/**
 * 唯一的 activity：保存屏幕栈（主屏、设置、开发者、引导页）并接收 intent 命令
 * （`--es cmd ...`）。所有与服务和 USB 相关的工作都在 ui.Actions 中，屏幕在 ui 包中。
 */
class MainActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppTheme.wrap(AppLocale.wrap(base)))
    }

    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private val keys = ArrayList<String>()
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            current()?.tick()
            handler.postDelayed(this, 500)
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            AppLog.i("результат requestPermission: granted=$granted")
            if (granted) Actions.startBridge(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppTheme.medium) setTheme(R.style.Theme_Headswap_Medium)
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        root = FrameLayout(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(root)
        registerReceiver(permReceiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        AppLog.i("MainActivity.onCreate action=${intent?.action} SDK=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")

        restoreScreens(savedInstanceState)
        val hadCmd = intent?.getStringExtra(EXTRA_CMD) != null
        val fromUsb = intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        if (savedInstanceState == null && !hadCmd && !fromUsb) {
            val prefs = UiPrefs(this)
            when (UpgradeNotice.onLaunch(prefs.onboardingDone, prefs.a11yNoticeShown)) {
                UpgradeNotice.Launch.ONBOARDING -> {
                    showOnboarding()
                    prefs.a11yNoticeShown = true         // 新手会从引导页了解到全部内容，不需要弹窗
                }
                UpgradeNotice.Launch.NOTICE -> {
                    prefs.a11yNoticeShown = true         // 显示一次就够了，即使被滑掉
                    UpgradeNotice.show(this)
                }
                UpgradeNotice.Launch.NONE -> {}
            }
        }
        handleIntent(intent, "старт activity")
        if (savedInstanceState == null && !hadCmd) {
            Thread({ AaCreds.selfTest(this) }, "aa-selftest").start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLog.i("MainActivity.onNewIntent action=${intent.action}")
        handleIntent(intent, "новый intent")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        current()?.onShow()
        handler.post(refresh)
        Updater.maybeCheck(this)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        current()?.onHide()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SCREENS, keys)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current()?.onBack() == true) return
        if (stack.size > 1) back() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        current()?.onPermissionResult()
        if (requestCode == REQ_LOCAL_NETWORK) AppLog.i("ACCESS_LOCAL_NETWORK: ${NetProbe.localNetworkGranted(this)}")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CarPairing.REQ_CAR_PAIR) {
            CarPairing.onResult(this, resultCode, data)
            current()?.onActivityResultDone()
        }
    }

    /** 无线调试：系统请求 ACCESS_LOCAL_NETWORK（Android 17+）。带它的引导页将在 Э4 中出现。 */
    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < 37) {
            AppLog.i("ACCESS_LOCAL_NETWORK: API ${Build.VERSION.SDK_INT}, разрешения нет, локальная сеть открыта")
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK), REQ_LOCAL_NETWORK)
    }

    // --- 导航 ---

    private fun current(): Screen? = stack.lastOrNull()

    private fun push(key: String, s: Screen) {
        if (resumed) current()?.onHide()
        stack.add(s)
        keys.add(key)
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    fun back() {
        if (stack.size <= 1) return
        if (resumed) current()?.onHide()
        stack.removeAt(stack.size - 1)
        keys.removeAt(keys.size - 1)
        val s = stack.last()
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    /** 将屏幕栈折叠回主屏。 */
    private fun toHome() {
        while (stack.size > 1) back()
        if (stack.isEmpty()) push("home", HomeScreen(this))
    }

    fun showSettings() = push("settings", SettingsScreen(this))

    fun showSettingsSection(s: SettingsSection) = push("settings:${s.key}", SettingsSectionScreen(this, s))

    fun showDev() = push("dev", DevScreen(this))

    fun showDiag() = push("diag", DiagScreen(this))

    /** rerun = 显示全部步骤；checklist = 直接进入就绪清单。 */
    fun showOnboarding(rerun: Boolean = false, checklist: Boolean = false, startAt: StepId? = null) {
        push(if (checklist) "checklist" else if (rerun) "onboarding_all" else "onboarding", OnboardingScreen(this, rerun, checklist, startAt))
    }

    private fun restoreScreens(saved: Bundle?) {
        push("home", HomeScreen(this))
        val savedKeys = saved?.getStringArrayList(STATE_SCREENS) ?: return
        for (k in savedKeys.drop(1)) {
            when (k) {
                "settings" -> showSettings()
                "dev" -> showDev()
                "diag" -> showDiag()
                "onboarding" -> showOnboarding()
                "onboarding_all" -> showOnboarding(rerun = true)
                "checklist" -> showOnboarding(checklist = true)
                else -> SettingsSection.byKey(k.removePrefix("settings:"))?.let { showSettingsSection(it) }     // 切换主题/语言后的设置分区
            }
        }
    }

    // --- intent 命令 ---

    private fun handleIntent(intent: Intent?, reason: String) {
        val cmd = intent?.getStringExtra(EXTRA_CMD)
        if (intent != null && cmd != null) {
            intent.removeExtra(EXTRA_CMD)        // 避免 activity 重建时重复执行命令
            AppLog.i("команда из intent: $cmd")
            when (cmd) {
                "aa_selftest" -> Actions.aaSelftest(this)
                "aa_probe" -> AaProbe.run(this)                // 不带 CarLife 时对 127.0.0.1:5277 的 AA 会话探测（targetSdk 37）
                // 无线模式的局域网探测：UDP 7999 信标，8999 响应端（--ei seconds N --es to <ip>）
                "net_probe" -> NetProbe.run(this, intent.getIntExtra("seconds", 20), intent.getStringExtra("to"))
                "net_perm" -> requestLocalNetwork()
                "open_aa" -> Actions.openAaSettings(this)
                // 没有车辆 Bluetooth 时的无线自动启动：与所选车辆的 ACL_CONNECTED / ACL_DISCONNECTED 相同
                "car_auto" -> startForegroundService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
                "car_auto_off" -> startForegroundService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
                "mic_test" -> Actions.micTest(this)
                "transcode_test" -> Actions.transcodeTest(this, intent.getStringExtra("opts") ?: "")
                "screen_main" -> toHome()
                "screen_settings" -> {                         // --es section appearance|video|sound|headunit|apps|updates
                    toHome()
                    showSettings()
                    SettingsSection.byKey(intent.getStringExtra("section"))?.let { showSettingsSection(it) }
                }
                "screen_dev" -> { toHome(); showDev() }
                "onboarding" -> {
                    toHome()
                    // --es step autostart|notif|... 直接从所需步骤打开引导页
                    val step = StepId.values().firstOrNull { it.key == intent.getStringExtra("step") }
                    showOnboarding(rerun = true, startAt = step)
                }
                "checklist" -> { toHome(); showOnboarding(checklist = true) }
                "update_check" -> Updater.check(this, manual = true)      // 立即检查 GitHub 上的版本，不必等待间隔
                "a11y_notice" -> UpgradeNotice.show(this)      // 强制显示「无障碍声明」窗口，不改动标记
                "theme" -> {                                   // --es mode system|light|medium|dark
                    AppTheme.set(this, intent.getStringExtra("mode") ?: AppTheme.SYSTEM)
                    recreate()
                }
                else -> AppLog.i("неизвестная команда '$cmd' (есть aa_selftest, aa_probe, net_probe, net_perm, mic_test, transcode_test, open_aa, car_auto, car_auto_off, a11y_notice)")
            }
            if (intent.hasExtra("scroll_y")) current()?.scrollTo(intent.getIntExtra("scroll_y", 0))     // 调试：截取屏幕下半部分
            return
        }
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val acc = accessoryFromIntent(intent)
            AppLog.i("== USB_ACCESSORY_ATTACHED, в intent: ${if (acc != null) describe(acc) else "аксессуара нет"}")
        }
        Actions.scan(this, reason)
    }

    @Suppress("DEPRECATION")
    private fun accessoryFromIntent(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    companion object {
        const val ACTION_PERMISSION = "com.yegorkapomidorka.headswap.USB_PERMISSION"
        private const val REQ_LOCAL_NETWORK = 37

        /** adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd aa_selftest */
        const val EXTRA_CMD = "cmd"
        private const val STATE_SCREENS = "screens"

        /** 将配件的所有字段拼接为一行。没有配件权限时获取序列号会抛出 SecurityException。 */
        fun describe(a: UsbAccessory): String {
            val serial = try { "\"${a.serial}\"" } catch (_: SecurityException) { "(нет разрешения)" }
            return "manufacturer=\"${a.manufacturer}\" model=\"${a.model}\" description=\"${a.description}\" " +
                "version=\"${a.version}\" uri=\"${a.uri}\" serial=$serial"
        }
    }
}