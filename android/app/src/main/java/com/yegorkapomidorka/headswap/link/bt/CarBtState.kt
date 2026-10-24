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
 * Подключён ли телефон к машине по Bluetooth (A2DP или HEADSET), публичным API: скрытый `BluetoothDevice.isConnected`
 * не используем. Нужен проверке «переподключи Bluetooth»: ACL машины могла прийти раньше, чем мы начали слушать carlife.
 */
object CarBtState {
    private const val PROXY_TIMEOUT_MS = 2_000L
    private val PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /**
     * Ответ [done] приходит на главной нити, ровно один раз. [mac] - адрес выбранной машины; null (машина не выбрана) -
     * годится любое подключённое устройство по A2DP или HEADSET. Нет разрешения BLUETOOTH_CONNECT или Bluetooth выключен - false.
     */
    @SuppressLint("MissingPermission")
    fun check(ctx: Context, mac: String?, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val granted = Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (adapter == null || !granted || !adapter.isEnabled) return done(false)
        try {
            if (mac == null) {      // адрес не нужен: прокси не поднимаем
                return done(PROFILES.any { adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED })
            }
        } catch (_: SecurityException) {
            return done(false)
        }

        // всё ниже мутирует только главная нить: колбэки прокси перекидываем через main.post
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
        main.postDelayed({ finish() }, PROXY_TIMEOUT_MS)      // прокси может так и не подключиться
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
