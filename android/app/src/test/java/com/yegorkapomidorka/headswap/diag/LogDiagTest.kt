package com.yegorkapomidorka.headswap.diag

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Те же кейсы, что гоняет tools/test_logdiag.py (TestCases): Python и Android обязаны давать одинаковый ответ. */
class LogDiagTest {
    private val toolsDir: File = run {
        var d: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (d != null && !File(d, "tools/logdiag_cases/cases.json").isFile) d = d.parentFile
        File(requireNotNull(d) { "не найден tools/logdiag_cases от ${System.getProperty("user.dir")}" }, "tools")
    }
    private val casesDir = File(toolsDir, "logdiag_cases")
    private val rules = LogDiag.parseRules(File(toolsDir, "logdiag_rules.json").readText(Charsets.UTF_8))

    private fun runCase(log: String, version: String, en: Boolean = false): LogDiag.Result {
        val text = File(casesDir, log).readText(Charsets.UTF_8)
        return LogDiag.run(
            listOf("headswap-20261012-090000.log" to text),
            LogDiag.Env(version, "Google Pixel 8", "16", 36),
            rules,
            en,
        )
    }

    private fun strs(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

    @Test
    fun cases() {
        val cases = JSONArray(File(casesDir, "cases.json").readText(Charsets.UTF_8))
        assertTrue(cases.length() > 0)
        val failures = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = "${case.getString("log")} @ ${case.getString("version")}"
            try {
                check(case)
            } catch (e: AssertionError) {
                failures.add("$name: ${e.message}")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(case: JSONObject) {
        val r = runCase(case.getString("log"), case.getString("version"))
        val causes = r.causes.filter { !it.isNote && it.score >= LogDiag.MIN_SCORE }.map { it.id }
        val notes = r.causes.filter { it.isNote }.map { it.id }
        if (case.has("top")) {
            val top = if (case.isNull("top")) null else case.getString("top")
            assertEquals(causes.toString(), top, causes.firstOrNull())
        }
        for (id in strs(case.optJSONArray("has"))) assertTrue("нет $id в $causes", id in causes)
        for (id in strs(case.optJSONArray("not"))) assertFalse("лишний $id в $causes", id in causes)
        for (id in strs(case.optJSONArray("notes"))) assertTrue("нет заметки $id в $notes", id in notes)
        val facts = case.optJSONObject("facts")
        if (facts != null) {
            for (k in facts.keys()) {
                val want = facts.get(k).takeIf { it != JSONObject.NULL }
                val got = r.facts[k]
                if (want is Number && got is Number) {
                    assertEquals("факт $k", want.toDouble(), got.toDouble(), 0.0)
                } else {
                    assertEquals("факт $k", want, got)
                }
            }
        }
    }

    @Test
    fun rulesHaveEnglish() {
        assertTrue(rules.rules.isNotEmpty())
        for (r in rules.rules) {
            assertTrue("${r.id}: нет title_en", !r.titleEn.isNullOrEmpty())
            assertTrue("${r.id}: нет advice_en", !r.adviceEn.isNullOrEmpty())
        }
    }

    @Test
    fun fixedInStartsWithUpdateStep() {
        for (en in listOf(false, true)) {
            val r = runCase("vw_pause.log", "2.3.4", en = en)
            val c = LogDiag.top(r.causes).first { it.id == "vw_pause_eio" }
            assertEquals("update", c.steps.first().action)
            assertTrue(c.steps.first().text.contains("2.4.0"))
            assertEquals(en, c.steps.first().text.startsWith("Update"))
            assertTrue("после update должен остаться совет", c.steps.size >= 2)
        }
    }

    @Test
    fun englishTitle() {
        val ru = runCase("aa_server_off.log", "2.4.4", en = false)
        val en = runCase("aa_server_off.log", "2.4.4", en = true)
        val ruTop = LogDiag.top(ru.causes).firstOrNull()
        val enTop = LogDiag.top(en.causes).firstOrNull()
        assertNotNull(ruTop)
        assertNotNull(enTop)
        assertEquals("aa_server_off", enTop!!.id)
        assertEquals(rules.rules.first { it.id == "aa_server_off" }.titleEn, enTop.title)
        assertTrue("заголовок не английский: ${enTop.title}", enTop.title != ruTop!!.title && enTop.title.none { it in 'а'..'я' || it in 'А'..'Я' })
    }
}
