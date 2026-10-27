package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.link.car.CarMac

/**
 * 手机是否通过蓝牙连接到汽车（A2DP 或 HEADSET），使用公开 API：不使用的隐藏 `BluetoothDevice.isConnected`。
 * 检查「重新连接蓝牙」时需要它：汽车的 ACL 可能比我们开始监听 carlife 更早到达。
 */
object CarBtState {
    private const val PROXY_TIMEOUT_MS = 2_000L
    private val PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /**
     * 回调 [done] 在主线程上被调用，恰好一次。[mac] - 所选汽车的地址；null（未选择汽车）-
     * 任何通过 A2DP 或 HEADSET 连接的设备都算。没有 BLUETOOTH_CONNECT 权限或蓝牙已关闭 - false。
     */
    @SuppressLint("MissingPermission")
    fun check(ctx: Context, mac: String?, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val granted = Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (adapter == null || !granted || !adapter.isEnabled) return done(false)
        try {
            if (mac == null) {      // 不需要地址：不拉起代理
                return done(PROFILES.any { adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED })
            }
        } catch (_: SecurityException) {
            return done(false)
        }

        // 以下所有状态只由主线程修改：代理回调通过 main.post 转发
        var left = PROFILES.size
        var found = false
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            done(found)
        }
        fun oneDone(ok: Boolean) {
            if (ok) found = true
            if (--left == 0) finish()
        }
        main.postDelayed({ finish() }, PROXY_TIMEOUT_MS)      // 代理可能一直连不上
        for (profile in PROFILES) {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val ok = try {
                        proxy.connectedDevices.any { CarMac.same(it.address, mac) }
                    } catch (_: SecurityException) {
                        false
                    }
                    try {
                        adapter.closeProfileProxy(p, proxy)
                    } catch (_: Exception) {
                    }
                    main.post { oneDone(ok) }
                }

                override fun onServiceDisconnected(p: Int) {}
            }
            val asked = try {
                adapter.getProfileProxy(ctx.applicationContext, listener, profile)
            } catch (_: SecurityException) {
                false
            }
            if (!asked) oneDone(false)
        }
    }
}
