package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.CarSettings

/**
 * Список поддерживаемых машин (CarPreset) радио-строками; под каждой - что она выставит.
 * Общий для раздела настроек «Мой автомобиль» и последнего шага онбординга. Сам ничего не пишет, только сообщает выбор.
 * [withOther] - последней строкой «Моей машины нет в списке» (выбор null).
 */
class CarPicker(val ctx: Context, withOther: Boolean, private val onPick: (CarPreset?) -> Unit) {
    /** Ключ null - «Моей машины нет в списке». */
    private val rows = LinkedHashMap<CarPreset?, RadioRow>()
    val view: LinearLayout = ctx.vbox(6)

    init {
        for (p in CarPreset.values()) {
            val row = RadioRow(ctx, ctx.getString(p.titleRes), ctx.carSettingsText(p.settings)) { onPick(p) }
            rows[p] = row
            view.addView(row.view, lp(MATCH, WRAP))
        }
        if (withOther) {
            val other = RadioRow(ctx, ctx.getString(R.string.car_other_title), ctx.getString(R.string.car_other_sub)) { onPick(null) }
            rows[null] = other
            view.addView(other.view, lp(MATCH, WRAP))
        }
    }

    fun setSelected(p: CarPreset?) {
        for ((k, row) in rows) row.setSelected(k == p)
    }
}

/** «1728×648 · кадры: Авто · музыка: Авто» (+ «прямой проброс» и DPI, если отличаются от обычных). */
fun Context.carSettingsText(s: CarSettings): String {
    val parts = mutableListOf(s.videoMode.effective(s.transcode).outLabel)
    if (!s.transcode) parts += getString(R.string.set_direct_title)
    if (s.aaDensity != AaDensity.AUTO) parts += getString(R.string.set_sum_density, s.aaDensity)
    parts += getString(R.string.car_sub_fps, getString(s.fps.titleRes))
    parts += getString(R.string.car_sub_music, getString(s.musicRate.titleRes))
    return parts.joinToString(" · ")
}
