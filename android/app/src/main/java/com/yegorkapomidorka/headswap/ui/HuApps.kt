package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * Android Auto 在“dev 车机”上显示的第三方应用（Headswap 伪装成 Desktop Head Unit，
 * AA 中已开启“未知来源”）。已安装的应用由我们自行发现（HuApps.detect），目录只是带发布链接的推荐。
 * 我们不会自行下载或安装任何内容。
 * 目录中的包必须同时出现在清单的 `<queries>` 中，否则在 API 30+ 上不可见（其余应用由同样位于其中的 `<intent>` 找到）。
 */
class HuApp(
    val name: String,
    val pkg: String,
    val url: String,
    val descRes: Int,
    /** 要求说明字符串（“需要 Android 15+”）或 null。 */
    val reqRes: Int? = null,
    /** 手机的最低 SDK_INT；0 表示无限制。 */
    val minSdk: Int = 0,
)

/** 关于应用要显示的内容：是否已安装以及是否能在该手机上运行。 */
data class HuAppStatus(val installed: Boolean, val unsupported: Boolean)

/** 来自 PackageManager 查询的候选：包名、是否为系统应用以及安装来源（null 表示 adb、浏览器或软件包安装器）。 */
data class AppCandidate(val pkg: String, val isSystem: Boolean, val installer: String?)

/** 筛选结果：“已安装在手机上”的包（按发现顺序）和“推荐”的目录条目。 */
class AppsSelection(val installed: List<String>, val recommended: List<HuApp>)

object HuApps {
    /** 来自 Play 的应用 Android Auto 本来就会显示，这里不再列出。 */
    const val PLAY_INSTALLER = "com.android.vending"

    private const val CATEGORY_PROJECTION = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
    private const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"

    val CATALOG: List<HuApp> = listOf(
        HuApp(
            "Fermata Auto", "me.aap.fermata.auto.dear.google.why",
            "https://github.com/AndreyPavlenko/Fermata/releases", R.string.apps_fermata_desc,
        ),
        HuApp(
            "ParkedTube", "dev.codex.aaparkedyoutube",
            "https://github.com/gadmin2151/ParkedTube/releases", R.string.apps_parkedtube_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
        HuApp(
            "AABrowser", "com.kododake.aabrowser",
            "https://github.com/kododake/AABrowser/releases", R.string.apps_aabrowser_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
    )

    /** 纯净的状态逻辑：安装检查和 SDK_INT 均由外部传入（测试中为模拟）。 */
    fun status(app: HuApp, isInstalled: (String) -> Boolean, sdkInt: Int): HuAppStatus =
        HuAppStatus(isInstalled(app.pkg), sdkInt < app.minSdk)

    /**
     * 纯净的筛选逻辑，不依赖 Android。进入“已安装”的候选须满足：不是我们自己、非系统应用且非来自 Play；
     * 再加上目录中所有已安装到手机的项（即使查询未找到它们）。“推荐”中是目录里有但未安装的项。
     */
    fun select(
        candidates: List<AppCandidate>,
        selfPkg: String,
        catalog: List<HuApp>,
        isInstalled: (String) -> Boolean,
    ): AppsSelection {
        val installed = LinkedHashSet<String>()
        for (c in candidates) {
            if (c.pkg == selfPkg || c.isSystem || c.installer == PLAY_INSTALLER) continue
            installed.add(c.pkg)
        }
        for (app in catalog) if (isInstalled(app.pkg)) installed.add(app.pkg)
        return AppsSelection(installed.toList(), catalog.filter { it.pkg !in installed })
    }

    /** 在手机上查找适用于 AA 的应用：播放器服务（CATEGORY_PROJECTION）和全屏 activity（CAR_LAUNCHER）。 */
    fun detect(ctx: Context): AppsSelection =
        select(scan(ctx.packageManager), ctx.packageName, CATALOG) { isInstalled(ctx, it) }

    private fun scan(pm: PackageManager): List<AppCandidate> {
        val projection = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_PROJECTION)
        val carLauncher = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_CAR_LAUNCHER)
        val infos = ArrayList<ApplicationInfo>()
        try {
            val services = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(projection, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentServices(projection, 0)
            }
            services.mapNotNullTo(infos) { it.serviceInfo?.applicationInfo }
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(carLauncher, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(carLauncher, 0)
            }
            activities.mapNotNullTo(infos) { it.activityInfo?.applicationInfo }
        } catch (e: Exception) {
            AppLog.e("Поиск приложений для AA не удался: ${e.message}")
        }
        return infos.distinctBy { it.packageName }.map {
            val system = it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppCandidate(it.packageName, system, installerOf(pm, it.packageName))
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun status(ctx: Context, app: HuApp): HuAppStatus = status(app, { isInstalled(ctx, it) }, Build.VERSION.SDK_INT)

    /** 启动 Intent 或 null：应用可能没有自己的图标（此时不显示“打开”按钮）。 */
    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun download(ctx: Context, app: HuApp) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("${app.name}: браузер не открылся: ${e.message}")
        }
    }

    fun open(ctx: Context, pkg: String) {
        try {
            launchIntent(ctx, pkg)?.let { ctx.startActivity(it) }
        } catch (e: Exception) {
            AppLog.e("$pkg: не запустился: ${e.message}")
        }
    }
}
