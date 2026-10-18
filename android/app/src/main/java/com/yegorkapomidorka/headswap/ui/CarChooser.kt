package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.CarSettings

/**
 * Выбор машины: поле-выпадашка (диалог со списком CarPreset) и под ним карточка «Будет применено» с названием каждой настройки.
 * Строки карточки фиксированной высоты, при выборе меняются только значения: ничего не прыгает.
 * Общий для раздела «Мой автомобиль» и последнего шага онбординга. Сам ничего не пишет, только сообщает выбор в [onPick];
 * применяет экран по своей кнопке «Применить».
 * [withOther] - последним пунктом «Моей машины нет в списке» (выбор null, настройки не трогаются).
 */
class CarChooser(val ctx: Context, private val withOther: Boolean, private val onPick: (CarPreset?) -> Unit) {
    private val fieldValue = ctx.text("", 17f, 500).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val values = ArrayList<TextView>()

    /** false - ещё ничего не выбрано (поле «Выберите машину», значения «—»). */
    var hasChoice = false
        private set
    var choice: CarPreset? = null
        private set
    val view: LinearLayout

    init {
        val c = ctx
        val field = c.hbox(12).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 16, c.clr(R.color.line), 1.5f), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { openList() }
            addView(c.vbox(2).apply {
                addView(c.text(c.getString(R.string.car_field), 13f, 500, c.clr(R.color.on2)))
                addView(fieldValue, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)).apply { rotation = 90f })
        }
        val card = c.card().apply { pad(c, 0, 6, 0, 6) }
        card.addView(c.text(c.getString(R.string.car_will_apply), 14f, 600, c.clr(R.color.acc)).apply { pad(c, 16, 10, 16, 4) })
        for (label in LABELS) {
            val v = c.text("", 16f, 500).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            values += v
            card.addView(c.vbox(1).apply {
                gravity = Gravity.CENTER_VERTICAL
                pad(c, 16, 6, 16, 6)
                addView(c.text(c.getString(label), 13f, 400, c.clr(R.color.on2)))
                addView(v, lp(MATCH, WRAP))
            }, c.lpDp(MATCH, 52))
        }
        view = c.vbox(12).apply {
            addView(field, c.lpDp(MATCH, 64))
            addView(card, lp(MATCH, WRAP))
        }
        show(null, has = false)
    }

    /** Показать выбор (без вызова [onPick]). */
    fun show(p: CarPreset?, has: Boolean = true) {
        val c = ctx
        hasChoice = has
        choice = if (has) p else null
        fieldValue.text = when {
            !has -> c.getString(R.string.car_pick_hint)
            p == null -> c.getString(R.string.car_other_title)
            else -> c.getString(p.titleRes)
        }
        fieldValue.setTextColor(c.clr(if (has) R.color.on else R.color.on2))
        val texts = when {
            !has -> List(LABELS.size) { "—" }
            p == null -> List(LABELS.size) { c.getString(R.string.car_value_keep) }
            else -> c.settingNames(p.settings)
        }
        for ((v, t) in values.zip(texts)) v.text = t
    }

    private fun openList() {
        val opts: List<CarPreset?> = CarPreset.values().toList() + if (withOther) listOf(null) else emptyList()
        val labels = opts.map { ctx.getString(it?.titleRes ?: R.string.car_other_title) }.toTypedArray()
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.car_field)
            .setSingleChoiceItems(labels, if (hasChoice) opts.indexOf(choice) else -1) { d, i ->
                show(opts[i])
                d.dismiss()
                onPick(opts[i])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        /** Подписи строк «Будет применено», в порядке [settingNames]. */
        val LABELS = listOf(R.string.car_label_mode, R.string.set_resolution, R.string.set_aa_density, R.string.set_fps, R.string.set_music_rate)
    }
}

/** Названия настроек, как в разделах «Видео» и «Звук»: режим, разрешение, масштаб AA, кадры, музыка. */
private fun Context.settingNames(s: CarSettings): List<String> = listOf(
    getString(if (s.transcode) R.string.set_transcode_title else R.string.set_direct_title),
    getString(s.videoMode.effective(s.transcode).titleRes),
    if (s.aaDensity == AaDensity.AUTO) getString(R.string.car_auto) else getString(R.string.set_sum_density, s.aaDensity),
    getString(s.fps.titleRes),
    getString(s.musicRate.titleRes),
)
