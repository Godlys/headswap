package com.yegorkapomidorka.headswap.diag

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Диагноз подключения по логу приложения: 1-3 вероятные причины и совет пользователю.
 * Kotlin-порт tools/logdiag.py (+ нужной части tools/logdigest.py), правила общие:
 * tools/logdiag_rules.json (в сборку копируется в assets). Поведение сверяется общими
 * кейсами tools/logdiag_cases/ (LogDiagTest и tools/test_logdiag.py). Чистая логика без Android.
 */
object LogDiag {
    const val SHOW = 3
    const val MIN_SCORE = 30

    class Cond(val fact: String, val op: String, val value: Any?)

    /** Шаг решения в правиле: текст ru/en и необязательное действие-кнопка (open_aa, video, ... см. DiagActions). */
    class RuleStep(val text: String, val textEn: String?, val action: String?)

    class Rule(
        val id: String,
        val title: String,
        val titleEn: String?,
        val category: String,
        val score: Int,
        val all: List<Cond>,
        val any: List<Cond>,
        val none: List<Cond>,
        val boost: List<Pair<Cond, Int>>,
        val evidence: List<String>,
        val what: String?,
        val whatEn: String?,
        val steps: List<RuleStep>,
        val advice: String,
        val adviceEn: String?,
        val ask: String?,
        val askEn: String?,
        val fixedIn: String?,
        val suppresses: List<String>,
    )

    class Rules(val latest: String, val markers: Map<String, Regex>, val rules: List<Rule>)

    /** Шаг для показа: текст на нужном языке и действие (null = без кнопки). */
    class Step(val text: String, val action: String?)

    class Cause(
        val id: String,
        val title: String,
        val category: String,
        val score: Int,
        val advice: String,
        val ask: String?,
        val evidence: List<String>,
        val what: String? = null,
        val steps: List<Step> = emptyList(),
    ) {
        val isNote get() = category == "note"
    }

    /** Окружение из device.txt (в приложении — из PackageInfo и Build). */
    class Env(val version: String?, val device: String? = null, val android: String? = null, val sdk: Int? = null)

    class Marker {
        var n = 0
        var first: String? = null
        var last: String? = null
        val ts = ArrayList<Long>()
        internal var lastT: Long? = null
    }

    class Result(val facts: Map<String, Any?>, val causes: List<Cause>)

    // ------------------------------------------------------------ правила

    fun parseRules(json: String): Rules {
        val o = JSONObject(json)
        val mk = o.getJSONObject("markers")
        val markers = LinkedHashMap<String, Regex>()
        for (k in mk.keys()) markers[k] = Regex(mk.getString(k))
        val arr = o.getJSONArray("rules")
        val rules = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Rule(
                id = r.getString("id"),
                title = r.getString("title"),
                titleEn = r.optStr("title_en"),
                category = r.getString("category"),
                score = r.getInt("score"),
                all = conds(r.optJSONArray("all")),
                any = conds(r.optJSONArray("any")),
                none = conds(r.optJSONArray("none")),
                boost = r.optJSONArray("boost")?.let { b ->
                    (0 until b.length()).map { j -> b.getJSONArray(j).let { cond(it.getJSONArray(0)) to it.getInt(1) } }
                } ?: emptyList(),
                evidence = strs(r.optJSONArray("evidence")),
                what = r.optStr("what"),
                whatEn = r.optStr("what_en"),
                steps = r.optJSONArray("steps")?.let { a ->
                    (0 until a.length()).map { j ->
                        a.getJSONObject(j).let { s -> RuleStep(s.getString("text"), s.optStr("text_en"), s.optStr("action")) }
                    }
                } ?: emptyList(),
                advice = r.getString("advice"),
                adviceEn = r.optStr("advice_en"),
                ask = r.optStr("ask"),
                askEn = r.optStr("ask_en"),
                fixedIn = r.optStr("fixed_in"),
                suppresses = strs(r.optJSONArray("suppresses")),
            )
        }
        return Rules(o.getString("latest"), markers, rules)
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun strs(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

    private fun conds(a: JSONArray?): List<Cond> = a?.let { (0 until it.length()).map { i -> cond(it.getJSONArray(i)) } } ?: emptyList()

    private fun cond(c: JSONArray): Cond {
        val v = c.get(2)
        return Cond(c.getString(0), c.getString(1), if (v == JSONObject.NULL) null else v)
    }

    // ------------------------------------------------------------ разбор лога

    private val TS_RE = Regex("""^(\d\d):(\d\d):(\d\d)\.(\d{3}) (.*)$""")
    private val STAMP_RE = Regex("""(\d{4})(\d\d)(\d\d)-(\d\d)(\d\d)(\d\d)""")
    private val RESUME_RE = Regex("""^== РЕЗЮМЕ СЕССИИ \((\d+) с, причина: (.*)\)\s*$""")
    private val SENT_RE = Regex("""^видео: отправлено кадров=(\d+).*\(\+(\d+) за (\d+)с\)""")
    private val HU_INFO_RE = Regex("""manufacturer="([^"]*)".*?model="([^"]*)"""")
    private val AA_SUMMARY_RE = Regex("""AA итог: кадров rx=(\d+) tx=(\d+); видео config=(\d+) data=(\d+)""")
    private val AA_MSG_RE = Regex("""^AA (RX|TX) ([A-Z_]+)\((\d+)\) ([A-Z_0-9]+)""")
    private val CHANNEL_RE = Regex("""STATISTIC_INFO channel=(\d+)""")
    private val EIO_RE = Regex("""EIO \(I/O error\)|ENODEV""")
    private val PAUSE_RE = Regex("""VIDEO_ENCODER_PAUSE""")
    private val FRAMES_RE = Regex("""кадров=(\d+)""")
    private val EIO_REASON_RE = Regex("EIO|ENODEV")
    private const val DAY = 86_400_000L

    /** Время строки -> мс с датой из имени файла и переходом через полночь (как Clock в logdigest.py). */
    private class Clock(name: String) {
        private val stamp: Long?
        private var base: Long
        private var prev: Long? = null

        init {
            val m = STAMP_RE.find(name.substringAfterLast('/'))
            stamp = m?.let {
                val g = it.groupValues
                runCatching {
                    LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()).toEpochDay() * DAY +
                        (g[4].toLong() * 3600 + g[5].toLong() * 60 + g[6].toLong()) * 1000
                }.getOrNull()
            }
            base = stamp?.let { it - it % DAY } ?: 0L
        }

        fun at(h: Int, m: Int, s: Int, ms: Int): Long {
            var t = base + ((h * 3600L + m * 60 + s) * 1000 + ms)
            val p = prev
            if (p == null) {
                if (stamp != null && t < stamp - 60_000) { base += DAY; t += DAY }
            } else if (t < p - 6 * 3600_000L) {
                base += DAY; t += DAY
            }
            prev = t
            return t
        }
    }

    private class Session(val start: Long?) {
        var dur: Int? = null
        var reason: String? = null
        var frames: Int? = null
        var requested: String? = null
        val aaData = ArrayList<Int>()
    }

    private class AaSess(val start: Long) {
        var cfg = 0
        var vdata = 0
        var lastMediaStart: Long? = null
    }

    private class Digest(val markers: Map<String, Regex>) {
        val sessions = ArrayList<Session>()
        val hu = LinkedHashMap<String, Int>()
        val episodes = HashMap<String, Int>()
        val found = markers.keys.associateWith { Marker() }
        var noGms = 0
        var lines = 0
        var channel: String? = null
        var pauseEio = 0
        private var cur: Session? = null
        private var resumeTarget: Session? = null
        private var lastAttach: Long? = null
        private var aa: AaSess? = null
        private var orphanOk = false

        fun ep(k: String) { episodes[k] = (episodes[k] ?: 0) + 1 }

        fun sessionFor(t: Long): Session? =
            sessions.lastOrNull { it.start == null || it.start <= t } ?: sessions.firstOrNull()

        fun feed(name: String, text: String) {
            val clock = Clock(name)
            var inResume = false
            orphanOk = false
            var lastPause: Long? = null
            text.lineSequence().forEachIndexed { i, raw ->
                if (i == 0 && raw.startsWith("[... начало обрезано")) { orphanOk = true; return@forEachIndexed }
                val m = TS_RE.matchEntire(raw)
                val t = m?.let { g -> g.groupValues.let { clock.at(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) } }
                scanMarkers(raw, t)
                if (channel == null) CHANNEL_RE.find(raw)?.let { channel = it.groupValues[1] }
                if (m == null || t == null) {
                    if (inResume && raw.startsWith("  ") && !raw.startsWith("   ")) resumeField(raw)
                    return@forEachIndexed
                }
                inResume = false
                lines++
                val body = m.groupValues[5]
                if (PAUSE_RE.containsMatchIn(body)) {
                    lastPause = t
                } else if (lastPause != null && EIO_RE.containsMatchIn(body)) {
                    val dt = (t - lastPause!!) / 1000.0
                    if (dt in 3.0..10.0) pauseEio++
                    lastPause = null
                }
                if (body.startsWith("RAW ")) return@forEachIndexed
                inResume = runCatching { line(t, body) }.getOrDefault(false)
            }
        }

        private fun scanMarkers(raw: String, t: Long?) {
            for ((k, rx) in markers) {
                if (!rx.containsMatchIn(raw)) continue
                val f = found.getValue(k)
                val lt = f.lastT
                if (t == null || lt == null || kotlin.math.abs(t - lt) > 2000) {
                    f.n++
                    if (t != null) f.ts.add(t)
                }
                f.lastT = t
                val s = raw.trim().take(200)
                if (f.first == null) f.first = s
                f.last = s
            }
        }

        private fun newSession(start: Long?) = Session(start).also { sessions.add(it) }

        private fun resumeField(line: String) {
            val s = resumeTarget ?: return
            if (':' !in line) return
            val k = line.trim().substringBefore(':').trim()
            val v = line.trim().substringAfter(':').trim()
            when (k) {
                "запрошенное разрешение" -> s.requested = v.substringBefore("; ответ:").trim()
                "видео" -> FRAMES_RE.find(v)?.let { s.frames = it.groupValues[1].toInt() }
                "HU_INFO" -> HU_INFO_RE.find(v)?.let {
                    val h = "${it.groupValues[1]} ${it.groupValues[2]}".trim().ifEmpty { "пустой HU_INFO" }
                    hu[h] = (hu[h] ?: 0) + 1
                }
            }
        }

        /** true, если началось РЕЗЮМЕ (следующие строки с отступом - его поля). */
        private fun line(t: Long, body: String): Boolean {
            if (body.startsWith("AA ")) { aaLine(t, body); return false }
            if (body.startsWith("== ")) {
                RESUME_RE.matchEntire(body)?.let { mr ->
                    val s = cur ?: newSession(null)
                    s.dur = mr.groupValues[1].toInt()
                    s.reason = mr.groupValues[2]
                    resumeTarget = s
                    cur = null
                    orphanOk = false
                    return true
                }
                when {
                    body.startsWith("== USB_ACCESSORY_ATTACHED") -> lastAttach = t
                    body.startsWith("== ГУ ПРОСИТ РАЗРЕШЕНИЕ:") -> cur?.requested = body.substringAfter(':').trim()
                }
                return false
            }
            if (body == "сессия: старт") {
                orphanOk = false
                cur?.let { if (it.reason == null) it.reason = "без резюме" }
                val la = lastAttach
                cur = if (la != null && t - la in 0..10_000) newSession(la) else newSession(t)
                return false
            }
            SENT_RE.find(body)?.let { ms ->
                if (cur == null && orphanOk) cur = newSession(null)
                orphanOk = false
                cur?.frames = ms.groupValues[1].toInt()
                return false
            }
            if (body.startsWith("ERROR ")) {
                val msg = body.substring(6)
                listOf("ENODEV", "EIO", "EPIPE").firstOrNull { it in msg }?.let { ep(it) }
                return false
            }
            if (body.startsWith("USB_ACCESSORY_DETACHED")) ep("USB_ACCESSORY_DETACHED")
            return false
        }

        private fun aaLine(t: Long, body: String) {
            if (body.startsWith("AA TCP подключено") || body.startsWith("AA сессия установлена") && aa == null) {
                aa = AaSess(t)
                return
            }
            if (body.startsWith("AA итог:")) {
                AA_SUMMARY_RE.find(body)?.let { m ->
                    sessionFor(aa?.start ?: t)?.aaData?.add(m.groupValues[4].toInt())
                }
                aa = null
                return
            }
            if (body.startsWith("AA телефон закрыл соединение")) {
                ep("EOF")
                if ("ByeBye" in body) return
                val a = aa
                val ms = a?.lastMediaStart
                if (a != null && a.cfg == 0 && a.vdata == 0 && ms != null && t - ms <= 5000) noGms++
                return
            }
            if (body.startsWith("AA завершаю: peer closed") && "EOF" !in body) { ep("peer closed"); return }
            val m = AA_MSG_RE.find(body) ?: return
            val (d, ch, _, msg) = m.destructured
            val a = aa
            when {
                ch == "NAVIGATION" -> {}
                d == "RX" && msg == "MEDIA_START" && ch != "MEDIA_PLAYBACK" -> a?.lastMediaStart = t
                msg == "MEDIA_CODEC_CONFIG" && ch == "VIDEO" -> a?.let { it.cfg++ }
                msg == "MEDIA_DATA" && ch == "VIDEO" && d == "RX" -> a?.let { it.vdata++ }
                msg == "BYEBYE_REQUEST" -> ep("BYEBYE_REQUEST")
            }
        }
    }

    /** logs: (имя файла, текст) в хронологическом порядке. */
    fun facts(logs: List<Pair<String, String>>, env: Env, rules: Rules): Pair<Map<String, Any?>, Map<String, Marker>> {
        val dg = Digest(rules.markers)
        for ((name, text) in logs) dg.feed(name, text)
        val ss = dg.sessions
        val frames = ss.map { it.frames ?: 0 }
        val aaVideo = ss.filter { it.aaData.isNotEmpty() }.map { it.aaData.max() }
        val eio = ss.filter { EIO_REASON_RE.containsMatchIn(it.reason ?: "") }
        val f = LinkedHashMap<String, Any?>()
        f["lines"] = dg.lines
        f["version"] = env.version
        f["device"] = env.device
        f["android"] = env.android
        f["sdk"] = env.sdk
        f["hu"] = dg.hu.entries.sortedByDescending { it.value }.joinToString(" | ") { it.key }
        f["channel"] = dg.channel ?: ""
        f["requested"] = ss.mapNotNull { it.requested }.toSortedSet().joinToString(" | ")
        f["n_sessions"] = ss.size
        f["frames_max"] = frames.maxOrNull() ?: 0
        f["frames_total"] = frames.sum()
        f["two_frame_sessions"] = frames.count { it == 2 }
        f["aa_video_max"] = aaVideo.maxOrNull()
        f["eio_sessions"] = eio.size
        f["eio_short"] = eio.count { (it.dur ?: Int.MAX_VALUE) <= 15 }
        f["no_gms"] = dg.noGms
        f["pause_eio"] = dg.pauseEio
        val abort = dg.found["abort"]
        val iface = dg.found["iface_down"]
        val abortIface = if (abort != null && iface != null) near(abort.ts, iface.ts, 3000) else 0
        f["abort_iface"] = abortIface
        f["abort_no_iface"] = (abort?.n ?: 0) - abortIface
        for ((k, v) in dg.episodes) f["ep_$k"] = v
        for ((k, v) in dg.found) f["m.$k"] = v.n
        return f to dg.found
    }

    private fun near(a: List<Long>, b: List<Long>, ms: Long) = a.count { x -> b.any { kotlin.math.abs(x - it) <= ms } }

    // ------------------------------------------------------------ правила по фактам

    private val VER_RE = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    fun vtuple(v: String?): List<Int>? {
        val m = VER_RE.find(v ?: return null) ?: return null
        if (m.range.first != 0) return null  // как re.match: версия с начала строки
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    private fun cmpVer(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    private fun num(x: Any?): Double? = (x as? Number)?.toDouble()

    fun check(c: Cond, f: Map<String, Any?>): Boolean {
        val x = f[c.fact]
        val v = c.value
        when (c.op) {
            "~" -> return x != null && x != "" && x != 0 && Regex(v as String).containsMatchIn(x.toString())
            "<v", ">=v" -> {
                val a = vtuple(x as? String) ?: return false
                val b = vtuple(v as String) ?: return false
                val r = cmpVer(a, b)
                return if (c.op == "<v") r < 0 else r >= 0
            }
        }
        if (x == null && (c.op == "==" || c.op == "!=")) return (c.op == "!=") != (v == null)
        val xv: Any = x ?: 0
        val a = num(xv)
        val b = num(v)
        if (a != null && b != null) {
            return when (c.op) {
                "==" -> a == b
                "!=" -> a != b
                ">=" -> a >= b
                "<=" -> a <= b
                ">" -> a > b
                "<" -> a < b
                else -> throw IllegalArgumentException("неизвестный оператор ${c.op}")
            }
        }
        return when (c.op) {
            "==" -> xv == v
            "!=" -> xv != v
            else -> false
        }
    }

    fun diagnose(f: Map<String, Any?>, found: Map<String, Marker>, rules: Rules, en: Boolean = false): List<Cause> {
        val hits = ArrayList<Pair<Cause, List<String>>>()
        val myVer = vtuple(f["version"] as? String)
        for (r in rules.rules) {
            if (!r.all.all { check(it, f) }) continue
            if (r.any.isNotEmpty() && !r.any.any { check(it, f) }) continue
            if (r.none.any { check(it, f) }) continue
            var score = r.score
            for ((c, add) in r.boost) if (check(c, f)) score += add
            var advice = (if (en) r.adviceEn else null) ?: r.advice
            var steps = r.steps.map { Step((if (en) it.textEn else null) ?: it.text, it.action) }
            r.fixedIn?.let { fixed ->
                val fv = vtuple(fixed)
                if (myVer != null && fv != null && cmpVer(myVer, fv) >= 0) continue
                // без шагов в правиле сам совет становится вторым шагом, иначе экран показал бы только «обновитесь»
                if (steps.isEmpty()) steps = listOf(Step(advice, null))
                steps = listOf(Step(if (en) "Update Headswap to $fixed or newer" else "Обновите Headswap до $fixed или новее", "update")) + steps
                advice = (if (en) "Update Headswap to $fixed or newer (latest ${rules.latest}): this is fixed there. "
                else "Обновите Headswap до $fixed или новее (последняя ${rules.latest}): там это исправлено. ") + advice
            }
            val evidence = r.evidence.mapNotNull { k ->
                found[k]?.takeIf { it.n > 0 }?.let { e -> "${e.first}" + if (e.n > 1) "  (×${e.n})" else "" }
            }
            val cause = Cause(
                id = r.id,
                title = (if (en) r.titleEn else null) ?: r.title,
                category = r.category,
                score = minOf(score, 100),
                advice = advice,
                ask = (if (en) r.askEn else null) ?: r.ask,
                evidence = evidence,
                what = (if (en) r.whatEn else null) ?: r.what,
                steps = steps,
            )
            hits.add(cause to r.suppresses)
        }
        val suppressed = hits.flatMap { it.second }.toSet()
        return hits.map { it.first }.filter { it.id !in suppressed }.sortedByDescending { it.score }
    }

    fun run(logs: List<Pair<String, String>>, env: Env, rules: Rules, en: Boolean = false): Result {
        val (f, found) = facts(logs, env, rules)
        return Result(f, diagnose(f, found, rules, en))
    }

    /** Причины для показа (без заметок, не ниже порога, не больше SHOW). */
    fun top(causes: List<Cause>): List<Cause> = causes.filter { !it.isNote && it.score >= MIN_SCORE }.take(SHOW)
}
