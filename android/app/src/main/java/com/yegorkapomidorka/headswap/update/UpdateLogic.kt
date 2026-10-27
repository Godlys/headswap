package com.yegorkapomidorka.headswap.update

/**
 * 不依赖 Android 的纯更新逻辑：GitHub releases、版本比较、APK 选择、何时检查。
 * Release：标签 vX.Y.Z，文件 headswap-X.Y.Z.apk；“最新”由 GitHub 自行确定（非草稿、非预发布）。
 */
object UpdateLogic {
    const val REPO = "Godlys/headswap"
    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    /** API 未返回 200 时的备用路径（每个 IP 每小时限 60 次请求）：重定向到 tag 页面。 */
    const val PAGE_LATEST = "https://github.com/$REPO/releases/latest"

    /** 自动检查每 6 小时最多一次；失败后一小时后重试。 */
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    private const val MAX_NOTES = 1500

    /** 找到的 release；若只知道 tag（备用路径），则 size = -1、sha256 = null。 */
    data class Release(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        val notes: String,
        val page: String,
    )

    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)

    /** "v1.1.10"、"1.1.10"、"1.1.10-cx5test1" -> [1, 1, 10]；开头没有数字则为 null。 */
    fun parseVersion(s: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(s.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** remote 比 local 新；缺失的部分视为 0（1.2 == 1.2.0），测试版后缀不参与比较。 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** release 的 APK：headswap-<版本>.apk，否则取文件中唯一的 .apk。 */
    fun pickApk(assets: List<Asset>, version: String): Asset? =
        assets.firstOrNull { it.name.equals("headswap-$version.apk", ignoreCase = true) }
            ?: assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()

    /** GitHub release 文件的 digest "sha256:<hex>" -> 小写 hex；其他算法返回 null。 */
    fun sha256(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substring(7).lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** 重定向的 Location .../releases/tag/v1.1.10 -> "v1.1.10"。 */
    fun tagFromLocation(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.substringBefore('#')?.takeIf { it.isNotBlank() }

    fun assetUrl(tag: String, version: String) = "https://github.com/$REPO/releases/download/$tag/headswap-$version.apk"

    fun tagPage(tag: String) = "https://github.com/$REPO/releases/tag/$tag"

    /** 对话框用的 release 说明：去掉 \r 和多余空行，长度不超过 MAX_NOTES。 */
    fun notes(body: String?): String {
        val t = (body ?: "").replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (t.length > MAX_NOTES) t.take(MAX_NOTES).trimEnd() + "…" else t
    }

    /** 是否该自动检查：已开启且距上次检查超过 [CHECK_INTERVAL_MS]（时钟被调慢时也视为该检查）。 */
    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
