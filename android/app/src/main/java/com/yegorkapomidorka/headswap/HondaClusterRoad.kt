package com.yegorkapomidorka.headswap

/**
 * 将 roadName（NAVI_NEXT_TURN_INFO 字段 3）适配到本田仪表盘（CarLife 1.1.0 车机，STATISTIC_INFO channel 2077xxxx/2083xxxx，
 * HU_INFO 为空）。公式是在车上实测得出的：
 * - S = 字符串按 UTF-8 编码到第一个 NUL 的字节数；车机把这些字节当作 UTF-16，因此只能看到
 *   前 floor(S/2)-1 个字符。S >= 32 时文本为空或乱码，S <= 31 时正常。
 * - 西里尔字母等非 ASCII 字符按全宽显示（占 2 格），ASCII 占 1 格。如果可见部分超过约 24 格，
 *   仪表盘会整条丢弃（保留旧文本）；24 可以通过，26 及以上不行。
 * - 尾部空格可作为填充（NUL 不行）：「Мира」+ 2 个空格显示为「Мира」。
 *
 * 因此取满足宽度 <= [MAX_CELLS]、字符数 <= [MAX_CHARS] 且
 * max(字节数, 2*字符数+2) <= [MAX_BYTES] 的最长前缀，并用空格补齐到 S = max(字节数, 2*字符数+2)：这样前缀的所有字符都可见。
 */
object HondaClusterRoad {
    /** 可见部分的最大格数：更宽时仪表盘整条丢弃。 */
    const val MAX_CELLS = 24

    /** 最大可见字符数：当 S = 2c+2 且 S <= 31 时，c <= 14。 */
    const val MAX_CHARS = 14

    /** S 的最大值（字符串到 NUL 的字节数）：从 32 起文本为空或乱码。 */
    const val MAX_BYTES = 31

    private const val ASCII_LIMIT = 0x80
    private const val WIDE_CELLS = 2
    private const val UTF16_OVERHEAD = 2        // 只能看到 floor(S/2)-1 个字符，所以 S >= 2c+2
    private const val DIRECTION_PREFIX = "В сторону "

    fun fit(s: String): String {
        var text = normalize(s)
        if (text.length > DIRECTION_PREFIX.length && text.regionMatches(0, DIRECTION_PREFIX, 0, DIRECTION_PREFIX.length, ignoreCase = true)) {
            text = text.substring(DIRECTION_PREFIX.length).trimStart()
        }
        if (text.isEmpty()) return ""

        var end = 0
        var cells = 0
        var chars = 0
        var bytes = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val nCells = cells + cellWidth(cp)
            val nChars = chars + 1
            val nBytes = bytes + utf8Len(cp)
            if (nCells > MAX_CELLS || nChars > MAX_CHARS || maxOf(nBytes, UTF16_OVERHEAD * nChars + UTF16_OVERHEAD) > MAX_BYTES) break
            cells = nCells
            chars = nChars
            bytes = nBytes
            end += Character.charCount(cp)
        }
        val head = text.substring(0, end).trimEnd(' ')
        if (head.isEmpty()) return ""
        val headChars = head.codePointCount(0, head.length)
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        val padTo = maxOf(headBytes, UTF16_OVERHEAD * headChars + UTF16_OVERHEAD)
        return head + " ".repeat(padTo - headBytes)
    }

    /** 空白字符转为普通空格，控制字符和格式字符（U+00AD 等）丢弃，连续空格合并，最后 trim。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                Character.getType(cp) == Character.CONTROL.toInt() || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim()
    }

    private fun cellWidth(cp: Int) = if (cp < ASCII_LIMIT) 1 else WIDE_CELLS

    private fun utf8Len(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}