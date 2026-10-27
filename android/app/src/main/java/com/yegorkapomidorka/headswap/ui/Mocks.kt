package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.R

/**
 * 引导步骤的示意图：“哪里该点什么”（Onboarding.dc.html）。纯插图，不可点击。
 * 颜色取自应用主题；Android 系统 UI 的蓝色（开关、勾选）用字面量，与设计稿一致。
 * “模糊”用半透明的 line 色条实现，API 31+ 上还叠加 RenderEffect。
 */
object Mocks {
    private val SYS_BLUE = Color.parseColor("#3B7BEA")
    private val SYS_BUTTON = Color.parseColor("#2F6FD8")
    private val AA_CHECK = Color.parseColor("#1A73E8")
    private val WHITE = Color.WHITE

    fun build(ctx: Context, id: StepId): View = when (id) {
        StepId.AA_DEV -> ctx.vbox(10).apply {
            addView(
                ctx.aaWindow(
                    title = ctx.getString(R.string.mock_aa_title), row = ctx.getString(R.string.mock_aa_version),
                    chrome = true, tap = true, check = false, compact = false,
                ),
                lp(MATCH, WRAP),
            )
        }
        StepId.AA_UNKNOWN -> ctx.vbox(10).apply {
            addView(
                ctx.aaWindow(
                    title = ctx.getString(R.string.mock_aadev_title), row = ctx.getString(R.string.mock_aadev_unknown),
                    chrome = false, tap = false, check = true, compact = true,
                ),
                lp(MATCH, WRAP),
            )
        }
        StepId.AUTOSTART -> ctx.vbox(10).apply { addView(ctx.menuWindow(), lp(MATCH, ctx.dp(232))) }
        StepId.NOTIF -> ctx.vbox(10).apply { addView(ctx.shade(), lp(MATCH, WRAP)) }
        StepId.BACKGROUND -> ctx.vbox(10).apply {
            addView(ctx.activityControl(), lp(MATCH, WRAP))
            if (Readiness.isXiaomi()) addView(ctx.autostartList(), lp(MATCH, WRAP))
        }
        StepId.MIC -> ctx.vbox(10).apply { addView(ctx.micDialog(), lp(MATCH, WRAP)) }
        StepId.BLUETOOTH -> ctx.vbox(8).apply { ctx.bluetoothRows().forEach { addView(it, lp(MATCH, WRAP)) } }
        StepId.LOCATION -> ctx.vbox(8).apply { ctx.locationRows().forEach { addView(it, lp(MATCH, WRAP)) } }
        /** 车型选择步骤自行绘制列表（OnboardingScreen.renderCar），无示意图。 */
        StepId.CAR -> ctx.vbox()
        StepId.FIRST -> ctx.vbox(10).apply { addView(ctx.usbDialog(), lp(MATCH, WRAP)) }
    }

    // --- Android Auto 窗口 ---

    /**
     * Android Auto 设置窗口：标题栏、模糊行和带所需项的高亮行；
     * 右侧是贯穿窗口全高的“向下滚动”箭头。chrome = 返回箭头和 ⋮；compact = 矮行和两条模糊条。
     */
    private fun Context.aaWindow(title: String, row: String, chrome: Boolean, tap: Boolean, check: Boolean, compact: Boolean): View {
        val c = this
        val win = card(20)
        win.addView(
            hbox(10).apply {
                pad(c, 14, 0, 14, 0)
                if (chrome) addView(icon(R.drawable.ic_back, 18, clr(R.color.on2)))
                addView(text(title, 16f, 600), lp(0, WRAP, 1f))
                if (chrome) addView(icon(R.drawable.ic_more_vert, 20, clr(R.color.on2)))
            },
            lpDp(MATCH, 48),
        )
        win.addView(divider())
        win.addView(
            vbox(12).apply {
                pad(c, 14, 14, 14, 12)
                for (w in if (compact) listOf(62, 80) else listOf(62, 80, 54, 70)) {
                    addView(
                        hbox(10).apply {
                            addView(bar(w), lp(0, WRAP, 1f))
                            if (check) {
                                addView(View(c).apply { background = shape(0, 3, clr(R.color.line), 2) }, lp(dp(14), dp(14)))
                            }
                        },
                        lp(MATCH, WRAP),
                    )
                }
                blurred(0.55f, 1.4f)
            },
            lp(MATCH, WRAP),
        )
        win.addView(
            hbox(8).apply {
                setBackgroundColor(clr(R.color.pric))
                pad(c, 14, 0, 10, 0)
                addView(text(row, if (compact) 15f else 18f, 700, clr(R.color.onpric)).apply { maxLines = 1 }, lp(0, WRAP, 1f))
                if (check) {
                    addView(
                        FrameLayout(c).apply {
                            background = shape(AA_CHECK, 5)
                            addView(icon(R.drawable.ic_check, 20, WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        },
                        lp(dp(28), dp(28)),
                    )
                }
                if (tap) {
                    addView(
                        hbox(6).apply {
                            addView(icon(R.drawable.ic_hand_tap, 34, clr(R.color.onpric)))
                            addView(badge("×10", 18f, 6))
                        },
                    )
                }
            },
            lpDp(MATCH, if (compact) 52 else 72),
        )
        return FrameLayout(this).apply {
            addView(win, FrameLayout.LayoutParams(MATCH, WRAP).apply { rightMargin = dp(34) })
            addView(scrollArrow(), FrameLayout.LayoutParams(dp(24), MATCH, Gravity.END))
        }
    }

    /** 向下滚动箭头：杆部自上而下渐显（透明 -> 强调色），底部是箭头尖。 */
    private fun Context.scrollArrow(): View {
        val acc = clr(R.color.acc)
        return FrameLayout(this).apply {
            addView(
                View(this@scrollArrow).apply {
                    background = GradientDrawable().apply {
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        setColors(intArrayOf(acc and 0x00FFFFFF, acc, acc), floatArrayOf(0f, 0.7f, 1f))
                    }
                },
                FrameLayout.LayoutParams(dp(4), MATCH).apply { leftMargin = dp(10); bottomMargin = dp(15) },
            )
            addView(icon(R.drawable.ic_scroll_arrow, 24, acc), FrameLayout.LayoutParams(dp(24), dp(16), Gravity.BOTTOM))
        }
    }

    /** Android Auto 窗口带 ⋮ 下拉菜单（四个选项）；所需项高亮并用手指图标标记。 */
    private fun Context.menuWindow(): View {
        val c = this
        val back = vbox().apply {
            addView(
                hbox(10).apply {
                    pad(c, 14, 0, 8, 0)
                    addView(icon(R.drawable.ic_back, 18, clr(R.color.on2)))
                    addView(text(getString(R.string.mock_aa_title), 16f, 600), lp(0, WRAP, 1f))
                    addView(
                        FrameLayout(c).apply {
                            background = oval(clr(R.color.pric))
                            addView(icon(R.drawable.ic_more_vert, 20, clr(R.color.onpric)), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        },
                        lp(dp(32), dp(32)),
                    )
                },
                lpDp(MATCH, 48),
            )
            addView(divider())
            addView(
                vbox(12).apply {
                    pad(c, 14, 14, 14, 14)
                    for (w in listOf(62, 80, 54, 70, 48)) addView(bar(w), lp(MATCH, WRAP))
                    blurred(0.45f, 1.4f)
                },
                lp(MATCH, WRAP),
            )
        }
        fun item(s: String, h: Int, hi: Boolean = false) = hbox(4).apply {
            setPadding(dp(12), 0, dp(if (hi) 6 else 12), 0)
            if (hi) setBackgroundColor(clr(R.color.pric))
            val t = text(s, if (hi) 11f else 12f, if (hi) 700 else 400, clr(if (hi) R.color.onpric else R.color.on2)).apply { maxLines = 1 }
            if (hi) t.letterSpacing = -0.01f
            addView(t, lp(0, WRAP, 1f))
            if (hi) addView(icon(R.drawable.ic_hand_tap, 20, clr(R.color.onpric)))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(h))
        }
        val popup = vbox().apply {
            background = shape(clr(R.color.surf), 12)
            pad(c, 0, 6, 0, 6)
            elevation = dp(6).toFloat()
            addView(item(getString(R.string.mock_menu_help), 34))
            addView(item(getString(R.string.mock_aadev_title), 34))
            addView(item(getString(R.string.mock_menu_start), 42, hi = true))
            addView(item(getString(R.string.mock_menu_exit), 34))
        }
        return FrameLayout(this).apply {
            background = shape(clr(R.color.surf), 20)
            clipToOutline = true
            addView(back, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(popup, FrameLayout.LayoutParams(dp(228), WRAP, Gravity.END or Gravity.TOP).apply { topMargin = dp(44); rightMargin = dp(6) })
        }
    }

    // --- 系统窗口 ---

    /** 含 Headswap 通知的下拉通知栏（通知步骤）。 */
    private fun Context.shade(): View {
        val c = this
        return vbox(8).apply {
            background = shape(clr(R.color.surf), 20)
            pad(c, 10, 8, 10, 10)
            addView(
                hbox().apply {
                    pad(c, 6, 0, 6, 0)
                    addView(text("12:30", 12f, 600), lp(0, WRAP, 1f))
                    addView(icon(R.drawable.ic_status_wifi, 14, clr(R.color.on)))
                    addView(icon(R.drawable.ic_status_battery, 18, clr(R.color.on)), lp(dp(18), dp(10)).margins(c, 6, 0, 0, 0))
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(8).apply {
                    repeat(4) { addView(View(c).apply { background = shape(clr(R.color.line), 13) }, lp(0, dp(26), 1f)) }
                    blurred(0.55f, 1.2f)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(12).apply {
                    gravity = Gravity.TOP
                    background = shape(clr(R.color.bg), 16, clr(R.color.pri), 3)
                    pad(c, 12, 10, 12, 10)
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 12)
                            addView(icon(R.drawable.ic_logo, 22, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        },
                        lp(dp(36), dp(36)),
                    )
                    addView(
                        vbox(2).apply {
                            addView(text(getString(R.string.mock_notif_now), 13f, 400, clr(R.color.on2)))
                            addView(text(getString(R.string.home_title_ok), 16f, 600))
                            addView(text(getString(R.string.notif_video_usb), 14f, 400, clr(R.color.on2)))
                        },
                        lp(0, WRAP, 1f),
                    )
                },
                lp(MATCH, WRAP),
            )
            addView(View(c).apply { background = shape(clr(R.color.line), 2) }, lp(dp(36), dp(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    /** 应用信息：“活动控制：无限制”带蓝色勾选。 */
    private fun Context.activityControl(): View {
        val c = this
        return vbox(6).apply {
            addView(text(getString(R.string.mock_bg_activity), 13f, 400, clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
            addView(
                hbox(10).apply {
                    background = shape(clr(R.color.surf), 16)
                    pad(c, 14, 12, 14, 12)
                    addView(
                        vbox(2).apply {
                            addView(text(getString(R.string.mock_bg_no_limits), 15f, 600, SYS_BLUE))
                            addView(text(getString(R.string.mock_bg_no_limits_sub), 12f, 400, SYS_BLUE).apply { lineHeight = dp(16) })
                        },
                        lp(0, WRAP, 1f),
                    )
                    addView(icon(R.drawable.ic_check, 22, SYS_BLUE))
                },
                lp(MATCH, WRAP),
            )
        }
    }

    /** 小米“自启动”列表，Headswap 已开启（仅小米设备）。 */
    private fun Context.autostartList(): View {
        val c = this
        return card(16).apply {
            addView(text(getString(R.string.mock_bg_autostart), 13f, 600, clr(R.color.on2)).apply { pad(c, 14, 0, 14, 0); gravity = Gravity.CENTER_VERTICAL }, lpDp(MATCH, 36))
            addView(
                hbox(12).apply {
                    setBackgroundColor(clr(R.color.pric))
                    pad(c, 14, 0, 12, 0)
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 10)
                            addView(icon(R.drawable.ic_logo, 22, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        },
                        lp(dp(32), dp(32)),
                    )
                    addView(text("Headswap", 15f, 700, clr(R.color.onpric)), lp(0, WRAP, 1f))
                    addView(styledSwitch().apply { isChecked = true })
                },
                lpDp(MATCH, 56),
            )
        }
    }

    /** 麦克风权限请求：“使用应用期间”按钮被描边高亮。 */
    private fun Context.micDialog(): View {
        val c = this
        return vbox(8).apply {
            background = shape(clr(R.color.surf), 22)
            pad(c, 14, 14, 14, 12)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                FrameLayout(c).apply {
                    background = shape(clr(R.color.surf2), 8)
                    addView(icon(R.drawable.ic_mic, 18, clr(R.color.on)), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                },
                lp(dp(32), dp(32)),
            )
            addView(
                text(getString(R.string.mock_mic_question), 14f, 400).apply {
                    lineHeight = dp(19)
                    gravity = Gravity.CENTER
                    pad(c, 8, 0, 8, 4)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(6).apply {
                    gravity = Gravity.CENTER
                    background = shape(SYS_BUTTON, 20, clr(R.color.pri), 3)
                    addView(text(getString(R.string.mock_mic_while_using), 13f, 600, WHITE))
                    addView(icon(R.drawable.ic_hand_tap, 20, WHITE))
                },
                lpDp(MATCH, 40),
            )
            for (s in listOf(R.string.mock_mic_once, R.string.mock_mic_deny)) {
                addView(
                    text(getString(s), 13f, 400).apply {
                        gravity = Gravity.CENTER
                        background = shape(clr(R.color.surf2), 18)
                    },
                    lpDp(MATCH, 36),
                )
            }
        }
    }

    /** 系统蓝牙窗口：开关、已连接的车机、模糊行。 */
    private fun Context.bluetoothRows(): List<View> {
        val c = this
        val toggle = hbox().apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 0, 12, 0)
            addView(text(getString(R.string.mock_bt_title), 15f, 600), lp(0, WRAP, 1f))
            addView(sysSwitch())
            minimumHeight = dp(52)
        }
        val headUnit = hbox(12).apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 0, 12, 0)
            addView(icon(R.drawable.ic_car, 24, clr(R.color.on)))
            addView(
                vbox(2).apply {
                    addView(text(getString(R.string.mock_bt_headunit), 15f, 600))
                    addView(text(getString(R.string.mock_bt_connected), 12f, 400, SYS_BLUE))
                },
                lp(0, WRAP, 1f),
            )
            addView(
                FrameLayout(c).apply {
                    background = oval(clr(R.color.surf2))
                    addView(icon(R.drawable.ic_chevron, 14, clr(R.color.on2)), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                },
                lp(dp(26), dp(26)),
            )
            minimumHeight = dp(60)
        }
        val ghost = hbox(12).apply {
            background = shape(clr(R.color.surf), 16)
            pad(c, 14, 0, 14, 0)
            addView(View(c).apply { background = oval(clr(R.color.line)) }, lp(dp(22), dp(22)))
            addView(bar(55), lp(0, WRAP, 1f))
            minimumHeight = dp(48)
            blurred(0.5f, 1.2f)
        }
        return listOf(toggle, headUnit, ghost)
    }

    /** 系统定位窗口：“开启定位”开关被描边高亮，下方是模糊行。 */
    private fun Context.locationRows(): List<View> {
        val c = this
        val toggle = hbox(12).apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 12, 12, 12)
            addView(
                vbox(2).apply {
                    addView(text(getString(R.string.mock_loc_use), 15f, 600))
                    addView(text(getString(R.string.mock_loc_use_sub), 12f, 400, clr(R.color.on2)).apply { lineHeight = dp(16) })
                },
                lp(0, WRAP, 1f),
            )
            addView(sysSwitch())
        }
        val ghosts = vbox(8).apply {
            for (w in listOf(60, 45)) {
                addView(
                    hbox().apply {
                        background = shape(clr(R.color.surf), 16)
                        pad(c, 14, 0, 14, 0)
                        addView(bar(w), lp(0, WRAP, 1f))
                    },
                    lpDp(MATCH, 48),
                )
            }
            blurred(0.5f, 1.2f)
        }
        return listOf(toggle, ghosts)
    }

    /** “为 USB 配件打开 Headswap”弹窗：“默认使用”勾选框和确定按钮。 */
    private fun Context.usbDialog(): View {
        val c = this
        return vbox(14).apply {
            background = shape(clr(R.color.surf), 24)
            pad(c, 20, 20, 20, 20)
            addView(text(getString(R.string.mock_usb_question), 15f, 400).apply { lineHeight = dp(21) })
            addView(
                vbox(6).apply {
                    addView(bar(85, 9), lp(MATCH, WRAP))
                    addView(bar(60, 9), lp(MATCH, WRAP))
                    blurred(0.6f, 1.6f)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(10).apply {
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 4)
                            addView(icon(R.drawable.ic_check, 14, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                        },
                        lp(dp(20), dp(20)),
                    )
                    addView(text(getString(R.string.mock_usb_default), 14f, 400, clr(R.color.on2)))
                },
            )
            addView(
                hbox(20).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(text(getString(R.string.mock_cancel), 15f, 600, clr(R.color.acc)).apply { alpha = 0.6f })
                    addView(text(getString(R.string.mock_ok), 15f, 600, clr(R.color.acc)).apply {
                        background = shape(clr(R.color.pric), 12)
                        pad(c, 10, 2, 10, 2)
                    })
                },
            )
        }
    }

    // --- 积木组件 ---

    /** 宽度为 pct 百分比的“文本行”条（模糊用半透明的 line 色条绘制）。 */
    private fun Context.bar(pct: Int, heightDp: Int = 10): View = hbox().apply {
        weightSum = 100f
        addView(View(this@bar).apply { background = shape(clr(R.color.line), heightDp / 2) }, LinearLayout.LayoutParams(0, dp(heightDp), pct.toFloat()))
        addView(View(this@bar), LinearLayout.LayoutParams(0, dp(heightDp), (100 - pct).toFloat()))
    }

    /** 半透明效果；API 31+ 上还会应用真正的模糊。 */
    private fun <T : View> T.blurred(alphaValue: Float, blurDp: Float): T = apply {
        alpha = alphaValue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val px = blurDp * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP))
        }
    }

    private fun Context.badge(s: String, sp: Float, vPadDp: Int) = text(s, sp, 700, clr(R.color.onpri)).apply {
        background = shape(clr(R.color.pri), 16)
        pad(this@badge, 12, vPadDp, 12, vPadDp)
    }

    /** Android 系统 UI 的开启状态开关（蓝色，与设计稿一致）。 */
    private fun Context.sysSwitch(): View = FrameLayout(this).apply {
        background = shape(SYS_BLUE, 14)
        addView(
            View(this@sysSwitch).apply { background = oval(WHITE) },
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(3) },
        )
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(28))
    }
}
