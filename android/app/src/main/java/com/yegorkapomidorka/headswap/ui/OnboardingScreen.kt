package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.CarPreset

/**
 * 引导页（Onboarding.dc.html）与就绪检查清单。步骤来自 Readiness.STEPS；首次启动时已完成的步骤
 * （自动检测或手动标记）会被跳过，再次完整走流程时全部显示。
 * 末尾是显示每项状态的检查清单；同样的清单也可从设置中打开。
 */
class OnboardingScreen(host: MainActivity, private val showAll: Boolean, startAtChecklist: Boolean, startAt: StepId? = null) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root
    private val ui = UiPrefs(ctx)
    private val queue: List<StepInfo>
    private var pos = 0
    private var checklist = false

    /** 步骤从检查清单中打开：结束后返回检查清单。 */
    private var single: StepInfo? = null
    private var shownDone = false

    /** 在选择步骤中选中、但尚未应用的车型。 */
    private var carChoice: CarPreset? = null

    init {
        queue = if (showAll) Readiness.STEPS else Readiness.STEPS.filter { !Readiness.isDone(ctx, it.id) }
        checklist = startAtChecklist || queue.isEmpty()
        if (startAt != null) pos = queue.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        render()
    }

    override fun onShow() {
        // 从系统设置返回：状态可能已改变
        if (checklist) render() else if (current() != null && Readiness.isDone(ctx, current()!!.id) != shownDone) render()
    }

    override fun onPermissionResult() {
        val s = current() ?: return
        if (!checklist && Readiness.isDone(ctx, s.id) && (s.id == StepId.NOTIF || s.id == StepId.MIC)) next() else render()
    }

    override fun onBack(): Boolean {
        when {
            checklist -> { ui.onboardingDone = true; return false }
            single != null -> { single = null; checklist = true; render() }
            pos > 0 -> { pos--; render() }
            else -> { ui.onboardingDone = true; return false }      // 用户自行退出了引导页：不再强制展示
        }
        return true
    }

    private fun current(): StepInfo? = if (checklist) null else (single ?: queue.getOrNull(pos))

    private fun next() {
        if (single != null) {
            single = null
            checklist = true
        } else if (pos + 1 < queue.size) {
            pos++
        } else {
            checklist = true
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val s = if (checklist) null else current()
        when {
            checklist -> renderChecklist()
            s == null -> return
            s.id == StepId.CAR -> renderCar(s)
            else -> renderStep(s)
        }
    }

    /** “第 n 步，共 N 步”进度条，带圆点。 */
    private fun renderProgress(s: StepInfo) {
        val c = ctx
        val all = Readiness.STEPS
        val idx = all.indexOf(s)
        val total = if (single != null || showAll) all.size else queue.size
        val n = if (single != null || showAll) idx + 1 else pos + 1
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_step_progress, n, total), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
            val dots = c.hbox(6)
            for (i in 1..total) {
                dots.addView(View(c).apply { background = c.shape(c.clr(if (i <= n) R.color.pri else R.color.line), 3) }, lp(c.dp(if (total > 8) 14 else if (total > 6) 18 else 28), c.dp(6)))
            }
            addView(dots)
        }
        root.addView(top, c.lpDp(MATCH, 48))
    }

    private fun renderStep(s: StepInfo) {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        shownDone = done
        renderProgress(s)
        renderTitle(s)

        // 只有插图和文本滚动，标题保持不动
        val body = c.vbox().apply {
            addView(
                FrameLayout(c).apply {
                    background = c.shape(c.clr(R.color.surf2), 28)
                    // 带提示以及含 Android Auto 窗口的步骤布局更紧凑：文本无需滚动即可完整显示
                    val compact = s.hint != null || s.id == StepId.AA_DEV || s.id == StepId.AA_UNKNOWN
                    minimumHeight = c.dp(if (compact) 0 else 190)
                    pad(c, 20, 20, 20, 20)
                    addView(Mocks.build(c, s.id), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
                },
                lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
            )
            val text = c.vbox(10).apply { pad(c, 0, 18, 0, 36) }     // 底部留白大于边缘渐隐范围：文本结尾不会沉入 fade 中
            if (done) text.addView(doneChip(), lp(WRAP, WRAP))
            text.addView(numberedList(s.listItems(c)), lp(MATCH, WRAP))
            s.hint?.let {
                text.addView(
                    c.text(c.getString(it), 14f, 400, c.clr(R.color.onpric)).apply {
                        lineHeight = c.dp(20)
                        background = c.shape(c.clr(R.color.pric), 16)
                        pad(c, 14, 12, 14, 12)
                    },
                    lp(MATCH, WRAP),
                )
            }
            addView(text, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        val buttons = c.vbox(4)
        val primaryLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_next) else c.getString(s.primary)
        buttons.addView(c.primaryButton(primaryLabel).apply { setOnClickListener { onPrimary(s, done) } })
        val showExtra = s.extra != null && !done && (s.id != StepId.BACKGROUND || Readiness.isXiaomi())
        if (showExtra) {
            buttons.addView(c.containerButton(c.getString(s.extra!!)).apply { setOnClickListener { onExtra(s) } })
        }
        val skipLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_back) else c.getString(s.skip)
        buttons.addView(c.linkButton(skipLabel, 48).apply { setOnClickListener { onSkip(s, done) } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /**
     * 最后一步：您的车是哪款。下拉框只展示车辆设置，“应用”将其写入（AaPrefs.applyCar），
     * “跳过”（面向老手）不做任何改动，仅手动标记该步骤完成。
     */
    private fun renderCar(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val aa = AaPrefs(c)
        if (carChoice == null) carChoice = aa.carPreset

        val applyBtn = c.primaryButton(c.getString(s.primary))
        fun refresh() {
            applyBtn.isEnabled = carChoice != null
            applyBtn.alpha = if (carChoice != null) 1f else 0.45f
        }
        val chooser = CarChooser(c, withOther = false) { p ->
            carChoice = p
            refresh()
        }
        carChoice?.let { chooser.show(it) }

        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(chooser.view, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        applyBtn.setOnClickListener {
            val p = carChoice ?: return@setOnClickListener
            aa.applyCar(p)
            AppLog.i("онбординг, мой автомобиль: ${p.name}, применены ${p.settings}")
            next()
        }
        refresh()
        val buttons = c.vbox(4)
        buttons.addView(applyBtn)
        buttons.addView(c.linkButton(c.getString(s.skip), 48).apply {
            setOnClickListener {
                AppLog.i("онбординг, мой автомобиль: пропущен")
                ui.setManualDone(s.id.key, true)
                next()
            }
        })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** 带边缘渐隐的滚动：提示文本还有后续内容。 */
    private fun fadingScroll(content: View) = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true     // 常驻滚动条会落在插图卡片右缘；文本尾部本就靠渐隐可见
        addView(content)
    }

    /** 位于“第 n 步，共 N 步”下方的步骤标题：不参与滚动。 */
    private fun renderTitle(s: StepInfo) {
        val c = ctx
        root.addView(
            c.text(c.getString(s.title), 24f, 600).apply { letterSpacing = -0.01f; lineHeight = c.dp(30) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 16),
        )
    }

    /** 编号列表：pri 色圆点带序号，以及步骤文本。 */
    private fun numberedList(items: List<String>): View {
        val c = ctx
        return c.vbox(12).apply {
            items.forEachIndexed { i, t ->
                addView(
                    c.hbox(12).apply {
                        gravity = Gravity.TOP
                        addView(
                            FrameLayout(c).apply {
                                background = c.oval(c.clr(R.color.pri))
                                addView(c.text("${i + 1}", 14f, 700, c.clr(R.color.onpri)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                            },
                            lp(c.dp(26), c.dp(26)),
                        )
                        addView(c.text(t, 17f, 400).apply { lineHeight = c.dp(24) }, lp(0, WRAP, 1f))
                    },
                    lp(MATCH, WRAP),
                )
            }
        }
    }

    private fun doneChip(): View {
        val c = ctx
        return c.hbox(6).apply {
            background = c.shape(c.clr(R.color.okc), 10)
            pad(c, 10, 4, 12, 4)
            addView(c.icon(R.drawable.ic_check, 14, c.clr(R.color.ok)))
            addView(c.text(c.getString(R.string.onb_done_chip), 13f, 600, c.clr(R.color.ok)))
        }
    }

    private fun onPrimary(s: StepInfo, done: Boolean) {
        val c = ctx
        if (done && s.id != StepId.FIRST) { next(); return }
        when (s.id) {
            StepId.AA_DEV, StepId.AA_UNKNOWN -> Actions.openAaSettings(c)
            StepId.AUTOSTART -> Actions.startServerFromUi(c)
            StepId.NOTIF -> requestNotifications()
            StepId.BACKGROUND -> if (!Readiness.batteryOk(c)) Readiness.requestIgnoreBattery(c) else Readiness.openXiaomiAutostart(c)
            StepId.MIC -> requestMic()
            StepId.BLUETOOTH -> Readiness.openBluetoothSettings(c)
            StepId.LOCATION -> Readiness.openLocationSettings(c)
            StepId.FIRST -> { ui.setManualDone(s.id.key, true); next() }
            StepId.CAR -> next()      // 按钮在 renderCar 中自行处理
        }
    }

    private fun onExtra(s: StepInfo) {
        try {
            when (s.id) {
                StepId.BACKGROUND -> Readiness.openXiaomiAutostart(ctx)
                else -> {}
            }
        } catch (e: Exception) {
            AppLog.e("шаг ${s.id}: не открыть системный экран", e)
        }
    }

    private fun onSkip(s: StepInfo, done: Boolean) {
        if (done && s.id != StepId.FIRST) { stepBack(); return }
        when (s.id) {
            StepId.FIRST -> stepBack()
            StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.BACKGROUND, StepId.BLUETOOTH, StepId.LOCATION -> { ui.setManualDone(s.id.key, true); next() }
            else -> next()
        }
    }

    private fun stepBack() {
        if (!onBack()) host.back()
    }

    private fun requestNotifications() {
        val c = ctx
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && firstAsk(p)) {
            markAsked(p)
            host.requestPermissions(arrayOf(p), 1)
            return
        }
        // 系统对话框已弹出过（或 SDK < 33）：转到应用的通知设置中开启
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestMic() {
        val c = ctx
        if (!firstAsk(Manifest.permission.RECORD_AUDIO) && !host.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Readiness.appDetails(c)     // 被永久拒绝：只能去应用详情页授予权限
            return
        }
        markAsked(Manifest.permission.RECORD_AUDIO)
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun firstAsk(p: String) = !ui.isManualDone("asked_$p")

    private fun markAsked(p: String) = ui.setManualDone("asked_$p", true)

    // --- 检查清单 ---

    private fun renderChecklist() {
        val c = ctx
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_checklist_title), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val list = c.vbox(12).apply { pad(c, 0, 8, 0, 8) }
        val doneCount = Readiness.STEPS.count { Readiness.isDone(c, it.id) }
        val heading = if (doneCount == Readiness.STEPS.size) c.getString(R.string.onb_all_ready) else c.getString(R.string.onb_ready_count, doneCount, Readiness.STEPS.size)
        list.addView(c.text(heading, 28f, 600).apply {
            letterSpacing = -0.01f
            lineHeight = c.dp(34)
        })
        list.addView(c.text(c.getString(R.string.onb_checklist_hint), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        val card = c.card(24).apply { pad(c, 0, 6, 0, 6) }
        for (s in Readiness.STEPS) card.addView(checkRow(s), c.lpDp(MATCH, 60))
        list.addView(card, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(list), lp(MATCH, 0, 1f))

        root.addView(c.primaryButton(c.getString(R.string.onb_done)).apply {
            setOnClickListener {
                ui.onboardingDone = true
                host.back()
            }
        })
    }

    private fun checkRow(s: StepInfo): View {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        val auto = Readiness.auto(c, s.id)
        val statusText: String
        val statusColor: Int
        when {
            done -> { statusText = c.getString(if (auto == null) R.string.onb_status_marked else R.string.onb_status_ready); statusColor = R.color.ok }
            auto == null -> { statusText = c.getString(R.string.onb_status_unmarked); statusColor = R.color.on2 }
            else -> { statusText = c.getString(R.string.onb_status_off); statusColor = R.color.warn }
        }
        return c.hbox(14).apply {
            pad(c, 20, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener {
                single = s
                checklist = false
                render()
            }
            val mark = FrameLayout(c)
            when {
                done -> {
                    mark.background = c.oval(c.clr(R.color.ok))
                    mark.addView(c.icon(R.drawable.ic_check, 16, c.clr(R.color.onok)), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                }
                auto == null -> mark.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0)
                    setStroke(c.dp(2), c.clr(R.color.line))
                }
                else -> {
                    mark.background = c.oval(c.clr(R.color.warn))
                    mark.addView(c.text("!", 16f, 700, c.clr(R.color.warnc)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
            addView(mark, lp(c.dp(28), c.dp(28)))
            addView(c.vbox(1).apply {
                addView(c.text(c.getString(s.short), 16f, 500))
                addView(c.text(statusText, 13f, 500, c.clr(statusColor)))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }
}
