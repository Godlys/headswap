package com.yegorkapomidorka.headswap.ui

import android.content.Context

/**
 * 无需 Context 即可持有的面向用户的文本：资源 + 格式参数，或现成字符串（例如异常消息）。
 * 纯逻辑层（HomeLogic、Readiness）返回 UiText，由 UI 或通知通过 [get] 将其转为字符串。
 */
sealed class UiText {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText()

    data class Plural(val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText()

    data class Raw(val text: String) : UiText()

    fun get(ctx: Context): String = when (this) {
        is Res -> if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args.map { it.resolve(ctx) }.toTypedArray())
        is Plural -> ctx.resources.getQuantityString(id, count, *args.map { it.resolve(ctx) }.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun raw(text: String): UiText = Raw(text)

        /** 参数本身也可以是 UiText：用同一个 context 解析。 */
        private fun Any.resolve(ctx: Context): Any = if (this is UiText) get(ctx) else this
    }
}
