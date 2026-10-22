package com.yegorkapomidorka.headswap.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.diag.DiagRunner
import com.yegorkapomidorka.headswap.diag.LogDiag

/** «Почему не подключается»: вероятные причины и совет по логу приложения, анализ на телефоне (diag/LogDiag). */
class DiagScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val content: LinearLayout

    init {
        val c = ctx
        content = c.vbox(12)
        content.addView(c.text(c.getString(R.string.diag_analyzing), 16f, 400, c.clr(R.color.on2)).apply { pad(c, 8, 8, 8, 8) })
        view = scaffold(c, c.getString(R.string.diag_title), { host.back() }, c.vbox().apply {
            pad(c, 16, 4, 16, 16)
            addView(content, lp(MATCH, WRAP))
        })
        DiagRunner.run(c) { res -> res.fold(::showResult, { showFailed() }) }
    }

    private fun showFailed() {
        val c = ctx
        content.removeAllViews()
        content.addView(c.text(c.getString(R.string.diag_failed), 16f, 400, c.clr(R.color.warn)).apply { pad(c, 8, 8, 8, 8) })
        addSend()
    }

    private fun showResult(r: LogDiag.Result) {
        val c = ctx
        content.removeAllViews()
        val top = LogDiag.top(r.causes)
        val oldVersion = r.causes.firstOrNull { it.id == "old_version" }
        if (top.isEmpty()) {
            val works = r.causes.firstOrNull { it.id == "works" }
            val msg = works?.advice ?: c.getString(R.string.diag_none)
            content.addView(c.text(msg, 16f, 400).apply { lineHeight = c.dp(22); pad(c, 8, 8, 8, 8) })
        }
        for (cause in top) content.addView(causeCard(cause), lp(MATCH, WRAP))
        if (oldVersion != null) {
            content.addView(c.text(oldVersion.advice, 14f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(20); pad(c, 8, 4, 8, 4) })
        }
        addSend()
    }

    private fun causeCard(cause: LogDiag.Cause): View {
        val c = ctx
        return c.card().apply {
            pad(c, 18, 16, 18, 16)
            addView(c.text(cause.title, 18f, 600).apply { lineHeight = c.dp(24) })
            addView(c.text(cause.advice, 15f, 400).apply {
                lineHeight = c.dp(21)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 8, 0, 0)
            })
            cause.evidence.firstOrNull()?.let { ev ->
                addView(c.text("${c.getString(R.string.diag_in_log)} $ev", 12f, 400, c.clr(R.color.on2), monospace = true).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 10, 0, 0)
                })
            }
        }
    }

    private fun addSend() {
        val c = ctx
        content.addView(
            c.primaryButton(c.getString(R.string.diag_send)).apply { setOnClickListener { LogShare.shareReport(c) } },
            LinearLayout.LayoutParams(MATCH, c.dp(64)).margins(c, 0, 12, 0, 0),
        )
        content.addView(
            c.text(c.getString(R.string.diag_privacy), 12f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP),
        )
    }
}
