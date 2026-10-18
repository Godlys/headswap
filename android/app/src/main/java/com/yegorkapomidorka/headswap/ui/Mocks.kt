package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.R

/** Иллюстрации-схемы для шагов онбординга: «что где нажать» (как в Onboarding.dc.html). Только рисунок, ничего не нажимается. */
object Mocks {
    fun build(ctx: Context, id: StepId): View = when (id) {
        StepId.AA_DEV -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_aa_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_aa_notifications)),
                    ctx.row(ctx.getString(R.string.mock_aa_cars)),
                    ctx.row(ctx.getString(R.string.mock_aa_version), hi = true, h = 56, trailing = ctx.badge("×10")),
                ),
            )
        }
        StepId.AA_UNKNOWN -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_aadev_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_aadev_resolution)),
                    ctx.row(ctx.getString(R.string.mock_aadev_unknown), hi = true, h = 56, trailing = ctx.fakeSwitch()),
                ),
            )
        }
        StepId.AUTOSTART -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_a11y_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_a11y_downloaded)),
                    ctx.row(ctx.getString(R.string.autosrv_label), hi = true, h = 64, trailing = ctx.fakeSwitch()),
                ),
            )
        }
        StepId.NOTIF -> ctx.vbox(10).apply {
            addView(
                ctx.hbox(12).apply {
                    background = ctx.shape(ctx.clr(R.color.surf), 20)
                    pad(ctx, 16, 16, 16, 16)
                    gravity = Gravity.TOP
                    addView(
                        FrameLayout(ctx).apply {
                            background = ctx.shape(ctx.clr(R.color.pri), 12)
                            addView(ctx.icon(R.drawable.ic_logo, 22, ctx.clr(R.color.onpri)), FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
                        },
                        lp(ctx.dp(36), ctx.dp(36)),
                    )
                    addView(
                        ctx.vbox(2).apply {
                            addView(ctx.text(ctx.getString(R.string.mock_notif_now), 13f, 400, ctx.clr(R.color.on2)))
                            addView(ctx.text(ctx.getString(R.string.home_title_ok), 16f, 600))
                            addView(ctx.text(ctx.getString(R.string.notif_video_usb), 14f, 400, ctx.clr(R.color.on2)))
                        },
                        lp(0, WRAP, 1f),
                    )
                },
            )
            addView(
                ctx.hbox(10).apply {
                    pad(ctx, 4, 0, 4, 0)
                    addView(ctx.icon(R.drawable.ic_bell, 20, ctx.clr(R.color.on2)))
                    addView(ctx.text(ctx.getString(R.string.mock_notif_hint), 14f, 400, ctx.clr(R.color.on2)))
                },
            )
        }
        StepId.BACKGROUND -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_bg_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_bg_battery), hi = true, trailing = ctx.tick()),
                    ctx.row(ctx.getString(R.string.mock_bg_autostart), trailing = ctx.fakeSwitch()),
                    ctx.row(ctx.getString(R.string.mock_bg_saver)),
                ),
            )
        }
        StepId.MIC -> ctx.vbox(10).apply {
            addView(
                ctx.vbox(14).apply {
                    background = ctx.shape(ctx.clr(R.color.surf), 24)
                    pad(ctx, 20, 18, 20, 16)
                    addView(ctx.icon(R.drawable.ic_mic, 22, ctx.clr(R.color.acc)))
                    addView(ctx.text(ctx.getString(R.string.mock_mic_question), 15f, 400).apply { lineHeight = ctx.dp(21) })
                    addView(ctx.text(ctx.getString(R.string.mock_mic_while_using), 15f, 600, ctx.clr(R.color.onpric)).apply {
                        background = ctx.shape(ctx.clr(R.color.pric), 12)
                        pad(ctx, 12, 8, 12, 8)
                    })
                },
            )
        }
        StepId.BLUETOOTH -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_bt_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_bt_headunit), hi = true, trailing = ctx.tick()),
                    ctx.row(ctx.getString(R.string.mock_bt_headphones)),
                ),
            )
        }
        StepId.LOCATION -> ctx.vbox(10).apply {
            addView(ctx.label(ctx.getString(R.string.mock_loc_title)))
            addView(
                ctx.list(
                    ctx.row(ctx.getString(R.string.mock_loc_use), hi = true, trailing = ctx.fakeSwitch()),
                    ctx.row(ctx.getString(R.string.mock_loc_maps)),
                ),
            )
        }
        /** Шаг выбора машины рисует свой список (OnboardingScreen.renderCar), схемы нет. */
        StepId.CAR -> ctx.vbox()
        StepId.FIRST -> ctx.vbox(14).apply {
            background = ctx.shape(ctx.clr(R.color.surf), 24)
            pad(ctx, 20, 20, 20, 20)
            addView(ctx.text(ctx.getString(R.string.mock_usb_question), 15f, 400).apply { lineHeight = ctx.dp(21) })
            addView(
                ctx.hbox(10).apply {
                    addView(
                        FrameLayout(ctx).apply {
                            background = ctx.shape(ctx.clr(R.color.pri), 4)
                            addView(ctx.icon(R.drawable.ic_check, 14, ctx.clr(R.color.onpri)), FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER))
                        },
                        lp(ctx.dp(20), ctx.dp(20)),
                    )
                    addView(ctx.text(ctx.getString(R.string.mock_usb_default), 14f, 400, ctx.clr(R.color.on2)))
                },
            )
            addView(
                ctx.hbox(20).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(ctx.text(ctx.getString(R.string.mock_cancel), 15f, 600, ctx.clr(R.color.acc)).apply { alpha = 0.6f })
                    addView(ctx.text(ctx.getString(R.string.mock_ok), 15f, 600, ctx.clr(R.color.acc)).apply {
                        background = ctx.shape(ctx.clr(R.color.pric), 12)
                        pad(ctx, 10, 2, 10, 2)
                    })
                },
            )
        }
    }

    private fun Context.label(s: String) = text(s, 13f, 600, clr(R.color.on2)).apply { pad(this@label, 4, 0, 4, 0) }

    private fun Context.list(vararg rows: View): LinearLayout = card(16).apply {
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(divider())
            addView(r)
        }
    }

    private fun Context.row(s: String, hi: Boolean = false, h: Int = 48, trailing: View? = null): View = hbox(10).apply {
        pad(this@row, 16, 0, 12, 0)
        if (hi) setBackgroundColor(clr(R.color.pric))
        addView(text(s, 15f, if (hi) 600 else 400, clr(if (hi) R.color.onpric else R.color.on2)).apply { lineHeight = dp(20) }, lp(0, WRAP, 1f))
        trailing?.let { addView(it) }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(h))
    }

    private fun Context.badge(s: String) = text(s, 15f, 700, clr(R.color.onpri)).apply {
        background = shape(clr(R.color.pri), 14)
        pad(this@badge, 12, 4, 12, 4)
    }

    private fun Context.tick(): View = FrameLayout(this).apply {
        background = oval(clr(R.color.ok))
        addView(icon(R.drawable.ic_check, 14, clr(R.color.onok)), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }

    private fun Context.fakeSwitch(): View = styledSwitch().apply { isChecked = true }
}
