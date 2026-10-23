package com.yegorkapomidorka.headswap.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.diag.DiagActions
import com.yegorkapomidorka.headswap.diag.DiagFeedback
import com.yegorkapomidorka.headswap.diag.DiagRunner
import com.yegorkapomidorka.headswap.diag.LogDiag

/**
 * «Почему не подключается»: вероятные причины по логу приложения, анализ на телефоне (diag/LogDiag).
 * Первая причина раскрыта, у каждой шаги решения с кнопками-действиями (diag/DiagActions) и отметка «Помогло / Не помогло».
 */
class DiagScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val content: LinearLayout

    private var result: LogDiag.Result? = null
    private var top: List<LogDiag.Cause> = emptyList()
    private var open: String? = null
    private var helped: LogDiag.Cause? = null
    private val notHelped = HashSet<String>()
    private val logOpen = HashSet<String>()

    init {
        val c = ctx
        content = c.vbox(12)
        content.addView(
            c.hbox(12).apply {
                pad(c, 8, 8, 8, 8)
                addView(
                    ProgressBar(c).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(c.clr(R.color.acc)) },
                    c.lpDp(c.dp(28), 28),
                )
                addView(c.text(c.getString(R.string.diag_analyzing), 16f, 400, c.clr(R.color.on2)))
            },
            lp(MATCH, WRAP),
        )
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
        addSend(true)
    }

    private fun showResult(r: LogDiag.Result) {
        result = r
        top = LogDiag.top(r.causes)
        open = top.firstOrNull()?.id
        render()
    }

    private fun note(id: String) = result?.causes?.firstOrNull { it.id == id }

    private fun render() {
        val c = ctx
        content.removeAllViews()
        helped?.let { done ->
            content.addView(helpedBanner(done), lp(MATCH, WRAP))
            content.addView(
                c.primaryButton(c.getString(R.string.diag_done)).apply { setOnClickListener { host.back() } },
                LinearLayout.LayoutParams(MATCH, c.dp(64)).margins(c, 0, 12, 0, 0),
            )
            return
        }
        if (top.isEmpty()) {
            content.addView(emptyCard(), lp(MATCH, WRAP))
        } else {
            val lead = when {
                notHelped.size >= top.size -> c.getString(R.string.diag_lead_all_failed)
                notHelped.isNotEmpty() -> c.getString(R.string.diag_lead_next)
                top.size == 1 -> c.getString(R.string.diag_lead_one)
                else -> c.resources.getQuantityString(R.plurals.diag_lead_many, top.size, top.size)
            }
            content.addView(c.text(lead, 15f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(21); pad(c, 4, 0, 4, 0) }, lp(MATCH, WRAP))
            for ((i, cause) in top.withIndex()) content.addView(causeCard(cause, i), lp(MATCH, WRAP))
        }
        note("old_version")?.let { content.addView(oldVersionNote(), lp(MATCH, WRAP)) }
        addSend(top.isEmpty() || notHelped.isNotEmpty())
    }

    // ------------------------------------------------------------ карточки

    private fun chip(text: String, fill: Int, fg: Int): TextView = ctx.text(text, 12f, 600, fg).apply {
        background = ctx.shape(fill, 12)
        pad(ctx, 10, 4, 10, 5)
    }

    private fun causeCard(cause: LogDiag.Cause, index: Int): View {
        val c = ctx
        val marked = cause.id in notHelped
        val expanded = cause.id == open
        return c.card().apply {
            pad(c, 18, 16, 18, 16)
            if (marked) alpha = 0.55f
            addView(
                c.hbox(8).apply {
                    addView(
                        if (index == 0) chip(c.getString(R.string.diag_chip_likely), c.clr(R.color.warnc), c.clr(R.color.warn))
                        else chip(c.getString(R.string.diag_chip_maybe), c.clr(R.color.surf2), c.clr(R.color.on2)),
                    )
                    if (marked) addView(chip(c.getString(R.string.diag_chip_not_helped), c.clr(R.color.surf2), c.clr(R.color.on2)))
                },
                lp(MATCH, WRAP),
            )
            addView(c.text(cause.title, 19f, 600).apply {
                lineHeight = c.dp(25)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 10, 0, 0)
            })
            if (expanded) {
                addDetails(this, cause)
                addView(feedbackRow(cause), LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 18, 0, 0))
            } else {
                addView(
                    c.linkButton(c.getString(R.string.diag_show_solution), heightDp = 44, sp = 15f).apply {
                        setOnClickListener { open = cause.id; render() }
                    },
                    LinearLayout.LayoutParams(WRAP, c.dp(44)).margins(c, -20, 4, 0, -8),
                )
            }
        }
    }

    /**
     * Что случилось, шаги, «Что в логе». Общее для причин и заметки works. Шагов в правиле нет -
     * совет показывается одним шагом без номера (adviceFallback).
     */
    private fun addDetails(box: LinearLayout, cause: LogDiag.Cause, withLog: Boolean = true, adviceFallback: Boolean = true) {
        val c = ctx
        cause.what?.let { what ->
            box.addView(c.text(what, 16f, 400, c.clr(R.color.on2)).apply {
                lineHeight = c.dp(22)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 8, 0, 0)
            })
        }
        if (cause.steps.isNotEmpty() || adviceFallback) {
            box.addView(c.text(c.getString(R.string.diag_how_to_fix), 12f, 600, c.clr(R.color.acc)).apply {
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 18, 0, 0)
            })
            val steps = c.vbox(14)
            if (cause.steps.isEmpty()) {
                steps.addView(c.text(cause.advice, 16f, 400).apply { lineHeight = c.dp(22) }, lp(MATCH, WRAP))
            } else {
                for ((i, s) in cause.steps.withIndex()) steps.addView(stepRow(i + 1, s), lp(MATCH, WRAP))
            }
            box.addView(steps, LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 12, 0, 0))
        }
        if (withLog && cause.evidence.isNotEmpty()) addLogToggle(box, cause)
    }

    private fun stepRow(n: Int, step: LogDiag.Step): View {
        val c = ctx
        return c.hbox(12).apply {
            gravity = Gravity.TOP
            addView(
                FrameLayout(c).apply {
                    background = c.oval(c.clr(R.color.pric))
                    addView(c.text("$n", 14f, 600, c.clr(R.color.onpric)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                },
                c.lpDp(c.dp(28), 28),
            )
            addView(
                c.vbox(10).apply {
                    addView(c.text(step.text, 16f, 400).apply {
                        lineHeight = c.dp(22)
                        pad(c, 0, 3, 0, 0)
                    }, lp(MATCH, WRAP))
                    val label = DiagActions.label(step.action)
                    if (label != null && step.action != null) {
                        addView(
                            c.outlineButton(c.getString(label), heightDp = 44, sp = 15f).apply {
                                setOnClickListener { DiagActions.run(host, step.action) }
                            },
                            lp(MATCH, c.dp(44)),
                        )
                    }
                },
                lp(0, WRAP, 1f),
            )
        }
    }

    private fun addLogToggle(box: LinearLayout, cause: LogDiag.Cause) {
        val c = ctx
        val shown = cause.id in logOpen
        val ev = c.text(cause.evidence.joinToString("\n\n"), 12f, 400, c.clr(R.color.on2), monospace = true).apply {
            background = c.shape(c.clr(R.color.surf2), 12)
            pad(c, 12, 10, 12, 10)
            visibility = if (shown) View.VISIBLE else View.GONE
        }
        val toggle = c.text(c.getString(if (shown) R.string.diag_hide_log else R.string.diag_show_log), 14f, 600, c.clr(R.color.acc)).apply {
            pad(c, 0, 10, 12, 10)
            background = c.rippleBg(null, 8)
            isClickable = true
        }
        toggle.setOnClickListener {
            val now = ev.visibility != View.VISIBLE
            ev.visibility = if (now) View.VISIBLE else View.GONE
            if (now) logOpen.add(cause.id) else logOpen.remove(cause.id)
            toggle.text = c.getString(if (now) R.string.diag_hide_log else R.string.diag_show_log)
        }
        box.addView(toggle, LinearLayout.LayoutParams(WRAP, WRAP).margins(c, 0, 8, 0, 0))
        box.addView(ev, lp(MATCH, WRAP))
    }

    private fun feedbackRow(cause: LogDiag.Cause): View {
        val c = ctx
        return c.hbox(10).apply {
            addView(
                okButton(c.getString(R.string.diag_helped)).apply { setOnClickListener { onHelped(cause) } },
                lp(0, c.dp(52), 1f),
            )
            addView(
                c.outlineButton(c.getString(R.string.diag_not_helped), heightDp = 52, sp = 16f).apply { setOnClickListener { onNotHelped(cause) } },
                lp(0, c.dp(52), 1f),
            )
        }
    }

    /** Кнопка «Помогло»: заливка ok-цветом (pill из Kit приватный, строим тем же способом). */
    private fun okButton(label: String): LinearLayout {
        val c = ctx
        return c.hbox().apply {
            gravity = Gravity.CENTER
            background = c.rippleBg(c.shape(c.clr(R.color.ok), 26), 26)
            isClickable = true
            isFocusable = true
            pad(c, 12, 0, 12, 0)
            addView(c.text(label, 16f, 600, c.clr(R.color.surf)).apply { gravity = Gravity.CENTER })
        }
    }

    private fun onHelped(cause: LogDiag.Cause) {
        DiagFeedback.record(ctx, cause.id, true)
        helped = cause
        render()
        scrollTo(0)
    }

    private fun onNotHelped(cause: LogDiag.Cause) {
        DiagFeedback.record(ctx, cause.id, false)
        notHelped.add(cause.id)
        val idx = top.indexOfFirst { it.id == cause.id }
        open = (top.drop(idx + 1) + top.take(idx)).firstOrNull { it.id !in notHelped }?.id
        render()
        scrollTo(0)
    }

    private fun helpedBanner(cause: LogDiag.Cause): View {
        val c = ctx
        return c.hbox(14).apply {
            background = c.shape(c.clr(R.color.okc), 20)
            pad(c, 18, 18, 18, 18)
            addView(c.icon(R.drawable.ic_check, 28, c.clr(R.color.ok)))
            addView(
                c.vbox(4).apply {
                    addView(c.text(c.getString(R.string.diag_done_title), 19f, 600, c.clr(R.color.ok)))
                    addView(c.text(c.getString(R.string.diag_done_sub, cause.title), 15f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(21) })
                },
                lp(0, WRAP, 1f),
            )
        }
    }

    /** Нет причин: «Явных сбоев нет» с описанием заметки works или «Причина не найдена». */
    private fun emptyCard(): View {
        val c = ctx
        val works = note("works")
        return c.card().apply {
            pad(c, 18, 16, 18, 16)
            addView(c.text(c.getString(if (works != null) R.string.diag_works_title else R.string.diag_none_title), 19f, 600).apply { lineHeight = c.dp(25) })
            if (works != null) {
                if (works.what == null && works.steps.isEmpty()) {
                    addView(c.text(works.advice, 16f, 400, c.clr(R.color.on2)).apply {
                        lineHeight = c.dp(22)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 8, 0, 0)
                    })
                } else {
                    addDetails(this, works, withLog = false, adviceFallback = false)
                }
            } else {
                addView(c.text(c.getString(R.string.diag_none_text), 16f, 400, c.clr(R.color.on2)).apply {
                    lineHeight = c.dp(22)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(c, 0, 8, 0, 0)
                })
            }
        }
    }

    /** Заметка old_version: версия старая, у многих причин «лечение» уже в релизе. */
    private fun oldVersionNote(): View {
        val c = ctx
        return c.hbox(12).apply {
            background = c.shape(c.clr(R.color.pric), 20)
            pad(c, 16, 12, 12, 12)
            addView(c.text(c.getString(R.string.diag_old_version), 14f, 500, c.clr(R.color.onpric)).apply { lineHeight = c.dp(20) }, lp(0, WRAP, 1f))
            addView(
                c.primaryButton(c.getString(R.string.diag_old_update), heightDp = 40, sp = 14f).apply {
                    setOnClickListener { DiagActions.run(host, "update") }
                },
                LinearLayout.LayoutParams(WRAP, c.dp(40)),
            )
        }
    }

    private fun addSend(primary: Boolean) {
        val c = ctx
        val label = c.getString(R.string.diag_send)
        val button = if (primary) c.primaryButton(label) else c.outlineButton(label)
        content.addView(
            button.apply { setOnClickListener { LogShare.shareReport(c) } },
            LinearLayout.LayoutParams(MATCH, c.dp(64)).margins(c, 0, 12, 0, 0),
        )
        content.addView(
            c.text(c.getString(R.string.diag_privacy), 12f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP),
        )
    }
}
