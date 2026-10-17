package com.example.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Simple line icons drawn on a 24x24 grid so no drawable resources are needed. */
class IconView(ctx: Context, var kind: String, var tint: Int) : View(ctx) {
    var flipForRtl = false
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        fill.style = Paint.Style.FILL
    }

    fun setKindAndTint(k: String, t: Int) {
        kind = k
        tint = t
        invalidate()
    }

    private fun poly(c: Canvas, vararg pts: Float, closed: Boolean = false) {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            p.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (closed) p.close()
        c.drawPath(p, stroke)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, stroke)

    override fun onDraw(canvas: Canvas) {
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        val size = if (availW < availH) availW else availH
        if (size <= 0f) return
        canvas.save()
        canvas.translate(paddingLeft + (availW - size) / 2f, paddingTop + (availH - size) / 2f)
        val k = size / 24f
        canvas.scale(k, k)
        stroke.color = tint
        fill.color = tint
        stroke.strokeWidth = 1.9f
        val flip = flipForRtl && (kind == "back" || kind == "chevron")
        if (flip) canvas.scale(-1f, 1f, 12f, 12f)
        drawKind(canvas)
        canvas.restore()
    }

    private fun drawKind(c: Canvas) {
        when (kind) {
            "back" -> {
                line(c, 19f, 12f, 5f, 12f); poly(c, 11f, 6f, 5f, 12f, 11f, 18f)
            }
            "chevron" -> poly(c, 9f, 6f, 15f, 12f, 9f, 18f)
            "close" -> {
                line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3f, stroke)
                c.drawCircle(12f, 12f, 7f, stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    line(
                        c, 12f + (7f * cos(a)).toFloat(), 12f + (7f * sin(a)).toFloat(),
                        12f + (10f * cos(a)).toFloat(), 12f + (10f * sin(a)).toFloat()
                    )
                }
            }
            "edit" -> {
                poly(c, 4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, closed = true)
                line(c, 14f, 6f, 18f, 10f)
            }
            "text" -> {
                line(c, 5f, 6f, 19f, 6f); line(c, 12f, 6f, 12f, 19f); line(c, 9f, 19f, 15f, 19f)
            }
            "image" -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 2f, 2f, stroke)
                c.drawCircle(9f, 10f, 1.6f, stroke)
                poly(c, 4f, 17f, 10f, 12f, 14f, 16f, 17f, 13f, 20f, 16f)
            }
            "sign" -> {
                val p = Path()
                p.moveTo(4f, 16f)
                p.cubicTo(7f, 6f, 9f, 6f, 9f, 13f)
                p.cubicTo(9f, 17f, 12f, 17f, 13f, 12f)
                p.cubicTo(14f, 9f, 16f, 12f, 20f, 10f)
                c.drawPath(p, stroke)
                line(c, 4f, 20f, 20f, 20f)
            }
            "drop" -> {
                val p = Path()
                p.moveTo(12f, 3f)
                p.cubicTo(15f, 8f, 18f, 11f, 18f, 15f)
                p.cubicTo(18f, 18.5f, 15.3f, 21f, 12f, 21f)
                p.cubicTo(8.7f, 21f, 6f, 18.5f, 6f, 15f)
                p.cubicTo(6f, 11f, 9f, 8f, 12f, 3f)
                c.drawPath(p, stroke)
            }
            "eraser" -> {
                poly(c, 3f, 15f, 13f, 5f, 20f, 12f, 12f, 20f, 7f, 20f, 3f, 16f, closed = true)
                line(c, 8f, 10f, 15f, 17f); line(c, 12f, 20f, 21f, 20f)
            }
            "search" -> {
                c.drawCircle(10f, 10f, 6f, stroke); line(c, 15f, 15f, 20f, 20f)
            }
            "goto" -> {
                line(c, 9f, 4f, 7f, 20f); line(c, 16f, 4f, 14f, 20f)
                line(c, 4f, 9f, 20f, 9f); line(c, 4f, 15f, 20f, 15f)
            }
            "bookmark" -> poly(c, 6f, 3f, 18f, 3f, 18f, 21f, 12f, 16f, 6f, 21f, closed = true)
            "bookmarkFill" -> {
                val p = Path()
                p.moveTo(6f, 3f); p.lineTo(18f, 3f); p.lineTo(18f, 21f); p.lineTo(12f, 16f); p.lineTo(6f, 21f); p.close()
                c.drawPath(p, fill)
            }
            "copy" -> {
                c.drawRoundRect(RectF(9f, 9f, 20f, 21f), 2f, 2f, stroke)
                poly(c, 15f, 5f, 15f, 4f, 5f, 4f, 5f, 16f, 9f, 16f)
            }
            "moon" -> {
                val a = Path(); a.addCircle(12f, 12f, 8f, Path.Direction.CW)
                val b = Path(); b.addCircle(16f, 9f, 7f, Path.Direction.CW)
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, fill)
            }
            "sun" -> {
                c.drawCircle(12f, 12f, 4f, stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    line(
                        c, 12f + (7f * cos(a)).toFloat(), 12f + (7f * sin(a)).toFloat(),
                        12f + (10f * cos(a)).toFloat(), 12f + (10f * sin(a)).toFloat()
                    )
                }
            }
            "trash" -> {
                line(c, 5f, 7f, 19f, 7f); line(c, 10f, 4f, 14f, 4f)
                poly(c, 7f, 7f, 8f, 20f, 16f, 20f, 17f, 7f)
                line(c, 10f, 10f, 10f, 17f); line(c, 14f, 10f, 14f, 17f)
            }
            "undo", "redo" -> {
                if (kind == "redo") c.scale(-1f, 1f, 12f, 12f)
                poly(c, 9f, 5f, 4f, 10f, 9f, 15f)
                val p = Path()
                p.moveTo(4f, 10f); p.lineTo(14f, 10f)
                p.cubicTo(19f, 10f, 19f, 19f, 14f, 19f); p.lineTo(8f, 19f)
                c.drawPath(p, stroke)
            }
            "plus" -> {
                line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f)
            }
            "check" -> poly(c, 5f, 13f, 10f, 18f, 19f, 7f)
            "share" -> {
                c.drawCircle(6f, 12f, 2.5f, stroke); c.drawCircle(18f, 6f, 2.5f, stroke); c.drawCircle(18f, 18f, 2.5f, stroke)
                line(c, 8.2f, 10.8f, 15.8f, 7.2f); line(c, 8.2f, 13.2f, 15.8f, 16.8f)
            }
            "grid" -> {
                c.drawRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 1f, 1f, stroke)
                c.drawRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 1f, 1f, stroke)
                c.drawRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 1f, 1f, stroke)
                c.drawRoundRect(RectF(13.5f, 13.5f, 20f, 20f), 1f, 1f, stroke)
            }
            "list" -> {
                line(c, 9f, 7f, 20f, 7f); line(c, 9f, 12f, 20f, 12f); line(c, 9f, 17f, 20f, 17f)
                c.drawCircle(5f, 7f, 1f, fill); c.drawCircle(5f, 12f, 1f, fill); c.drawCircle(5f, 17f, 1f, fill)
            }
            "sort" -> {
                line(c, 4f, 7f, 20f, 7f); line(c, 4f, 12f, 15f, 12f); line(c, 4f, 17f, 10f, 17f)
            }
            "folder" -> poly(c, 3f, 7f, 10f, 7f, 12f, 9f, 21f, 9f, 21f, 19f, 3f, 19f, closed = true)
            "more" -> {
                c.drawCircle(12f, 5f, 1.8f, fill); c.drawCircle(12f, 12f, 1.8f, fill); c.drawCircle(12f, 19f, 1.8f, fill)
            }
            "library" -> {
                poly(c, 3f, 5f, 11f, 7f, 11f, 19f, 3f, 17f, closed = true)
                poly(c, 21f, 5f, 13f, 7f, 13f, 19f, 21f, 17f, closed = true)
            }
            "file" -> {
                poly(c, 6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, closed = true)
                poly(c, 14f, 3f, 14f, 8f, 19f, 8f)
            }
            "save" -> {
                c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 2f, 2f, stroke)
                c.drawRect(RectF(8f, 4f, 16f, 9f), stroke)
                c.drawRect(RectF(8f, 14f, 16f, 20f), stroke)
            }
            "filter" -> poly(c, 4f, 5f, 20f, 5f, 14f, 12f, 14f, 19f, 10f, 17f, 10f, 12f, closed = true)
            "info" -> {
                c.drawCircle(12f, 12f, 9f, stroke); line(c, 12f, 11f, 12f, 17f); c.drawCircle(12f, 7.5f, 1f, fill)
            }
            "rename" -> {
                poly(c, 4f, 17f, 5f, 12f, 14f, 3f, 18f, 7f, 9f, 16f, 4f, 17f, closed = true)
                line(c, 4f, 21f, 20f, 21f)
            }
            "select" -> {
                c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 3f, 3f, stroke); poly(c, 8f, 12f, 11f, 15f, 16f, 9f)
            }
            "selectAll" -> {
                c.drawRoundRect(RectF(3f, 3f, 21f, 21f), 3f, 3f, stroke)
                c.drawRoundRect(RectF(7f, 7f, 17f, 17f), 1f, 1f, fill)
            }
            "rotate" -> {
                val r = RectF(5f, 5f, 19f, 19f)
                c.drawArc(r, -60f, 280f, false, stroke)
                poly(c, 17f, 2.5f, 17.5f, 6.5f, 13.5f, 6.5f)
            }
            "page" -> {
                poly(c, 6f, 3f, 18f, 3f, 18f, 21f, 6f, 21f, closed = true)
                line(c, 9f, 8f, 15f, 8f); line(c, 9f, 12f, 15f, 12f); line(c, 9f, 16f, 13f, 16f)
            }
            else -> c.drawCircle(12f, 12f, 6f, stroke)
        }
    }
}
