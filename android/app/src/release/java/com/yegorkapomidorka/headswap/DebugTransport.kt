package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor

/** release 中没有 TCP 传输：桥接器只能与 USB 附件配合工作。 */
object DebugTransport {
    fun port(intent: Intent?): Int? = null

    fun tcp6(intent: Intent?): Boolean = false

    fun wifiB(intent: Intent?): Boolean = false

    fun tcp6Bind(intent: Intent?): String = "127.0.0.1"

    fun connect(port: Int): ParcelFileDescriptor? = null
}
