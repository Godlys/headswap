package com.yegorkapomidorka.headswap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.yegorkapomidorka.headswap.aa.AaMeta
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * CarLife 用图片：AA 封面（PNG 256x256，90-127 KB）-> JPEG < 30720 字节（CarlifeUtil.java:398），
 * 转向箭头 PNG < 30720 字节（RGEventHUDCollection.compressByte）。AA 以 ENUM 模式声明：手机不发送
 * 自绘的转向图片，我们根据 nextTurn 自行绘制。
 */
object MetaImages {
    const val MAX_BYTES = 30720
    private const val ART_SIDE = 256
    private const val ART_SMALL = 160
    private const val ICON = 128

    /** 封面 -> JPEG；如果图片无法解码或即使 160x160 q10 也超出 [maxBytes] 限制，则返回 null。 */
    fun albumJpeg(src: ByteArray, maxBytes: Int = MAX_BYTES): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
        try {
            for (side in intArrayOf(ART_SIDE, ART_SMALL)) {
                val scaled = if (bmp.width > side || bmp.height > side) Bitmap.createScaledBitmap(bmp, side, side, true) else bmp
                try {
                    // JPEG 无 alpha：透明区域垫黑色背景
                    val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                    Canvas(opaque).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(scaled, 0f, 0f, null)
                    }
                    var q = 90
                    while (q >= 10) {
                        val out = compress(opaque, Bitmap.CompressFormat.JPEG, q)
                        if (out.size < maxBytes) {
                            opaque.recycle()
                            return out
                        }
                        q -= 10
                    }
                    opaque.recycle()
                } finally {
                    if (scaled !== bmp) scaled.recycle()
                }
            }
            return null
        } finally {
            bmp.recycle()
        }
    }

    /** nextTurn 转向箭头（CarLife 编码）：透明背景上的白色箭头加深色描边，PNG 128x128。 */
    fun turnIcon(nextTurn: Int): ByteArray {
        val bmp = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val g = AaMeta.glyph(nextTurn)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(200, 0, 0, 0)
        }
        val stroke = Paint(outline).apply {
            strokeWidth = 14f
            color = Color.WHITE
        }
        val fillOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 8f
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(200, 0, 0, 0)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        fun both(p: Path, head: Path?) {
            c.drawPath(p, outline)
            head?.let { c.drawPath(it, fillOutline) }
            c.drawPath(p, stroke)
            head?.let { c.drawPath(it, fill) }
        }
        val cx = ICON / 2f
        when (g.kind) {
            AaMeta.GlyphKind.ARROW -> {
                val bend = PointF2(cx, 66f)
                val len = 38f
                val (ex, ey) = dir(bend, g.angle, len)
                val p = Path().apply {
                    moveTo(cx, 116f)
                    lineTo(bend.x, bend.y)
                    lineTo(ex, ey)
                }
                both(p, head(ex, ey, g.angle))
            }
            AaMeta.GlyphKind.U_TURN -> {
                val p = Path().apply {
                    moveTo(80f, 116f)
                    lineTo(80f, 56f)
                    arcTo(RectF(40f, 36f, 80f, 76f), 0f, -180f, false)
                    lineTo(40f, 80f)
                }
                both(p, head(40f, 92f, 180))
            }
            AaMeta.GlyphKind.RING, AaMeta.GlyphKind.RING_OUT -> {
                val ring = Path().apply { addCircle(cx, 58f, 22f, Path.Direction.CW) }
                c.drawPath(ring, outline)
                c.drawPath(ring, stroke)
                val start = PointF2(cx, 80f)
                val stem = Path().apply {
                    moveTo(cx, 118f)
                    lineTo(start.x, start.y)
                }
                both(stem, null)
                val out = PointF2(cx + 22f * sin(Math.toRadians(45.0)).toFloat(), 58f - 22f * cos(Math.toRadians(45.0)).toFloat())
                val (ex, ey) = dir(out, 45, 26f)
                val exit = Path().apply {
                    moveTo(out.x, out.y)
                    lineTo(ex, ey)
                }
                both(exit, head(ex, ey, 45))
            }
            AaMeta.GlyphKind.DEST -> {
                val pin = Path().apply {
                    moveTo(cx, 116f)
                    lineTo(cx - 26f, 64f)
                    arcTo(RectF(cx - 30f, 14f, cx + 30f, 74f), 150f, 240f, false)
                    close()
                }
                c.drawPath(pin, fillOutline)
                c.drawPath(pin, fill)
                c.drawCircle(cx, 44f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 0, 0, 0) })
            }
            AaMeta.GlyphKind.START -> {
                val p = Path().apply {
                    moveTo(cx, 116f)
                    lineTo(cx, 40f)
                }
                both(p, head(cx, 40f, 0))
                c.drawCircle(cx, 112f, 10f, fill)
            }
        }
        val png = compress(bmp, Bitmap.CompressFormat.PNG, 100)
        bmp.recycle()
        return png
    }

    private class PointF2(val x: Float, val y: Float)

    /** 从 p 出发沿 angle 方向（0 向上，顺时针为正）距离 len 的点。 */
    private fun dir(p: PointF2, angle: Int, len: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        return Pair(p.x + len * sin(a).toFloat(), p.y - len * cos(a).toFloat())
    }

    /** 箭头头部：顶点在 (x, y) 沿 angle 方向稍远处的三角形。 */
    private fun head(x: Float, y: Float, angle: Int): Path {
        val a = Math.toRadians(angle.toDouble())
        val fx = sin(a).toFloat()
        val fy = -cos(a).toFloat()
        val tipX = x + fx * 16f
        val tipY = y + fy * 16f
        val bx = x - fx * 6f
        val by = y - fy * 6f
        val w = 20f
        return Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx - fy * w, by + fx * w)
            lineTo(bx + fy * w, by - fx * w)
            close()
        }
    }

    private fun compress(b: Bitmap, f: Bitmap.CompressFormat, q: Int): ByteArray {
        val out = ByteArrayOutputStream()
        b.compress(f, q, out)
        return out.toByteArray()
    }
}