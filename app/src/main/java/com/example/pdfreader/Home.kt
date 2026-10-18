package com.example.pdfreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Full-screen home image (cover-fit) with a native "GRIMOIRE" button drawn in code. */
class HomeView(ctx: Context, private val ui: Ui, private val onEnter: () -> Unit) : View(ctx) {
    private val bmp: Bitmap? = try { BitmapFactory.decodeResource(ctx.resources, R.drawable.home_bg) } catch (_: Throwable) { null }
    private val m = Matrix()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val btn = RectF()
    private val tmp = RectF()
    private val path = Path()

    private var down = false
    private var press = 0f
    private var pressAnim: ValueAnimator? = null
    private var breathe = 0f
    private var phase = 0f
    private var rpX = 0f
    private var rpY = 0f
    private var rpT = 1f
    private var rpAnim: ValueAnimator? = null
    private var breathAnim: ValueAnimator? = null

    private val gold = Color.rgb(236, 196, 96)
    private val goldLight = Color.rgb(255, 232, 160)
    private val goldDark = Color.rgb(176, 130, 48)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 2600
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            phase = it.animatedFraction
            breathe = (sin(it.animatedFraction * 2.0 * Math.PI).toFloat() + 1f) / 2f
            invalidate()
        }
        breathAnim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        breathAnim?.cancel()
        pressAnim?.cancel()
        rpAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun layoutButton() {
        val w = width.toFloat()
        val h = height.toFloat()
        val bh = min(ui.dpf(84f), h * 0.115f)
        val margin = ui.dpf(26f)
        var cy = h * 0.79f
        val b = bmp
        if (b != null) {
            val s = max(w / b.width, h / b.height)
            val dy = (h - b.height * s) / 2f
            cy = dy + 0.785f * b.height * s   // keep the button over the same part of the artwork
        }
        cy = cy.coerceIn(h * 0.62f, h * 0.86f)
        btn.set(margin, cy - bh / 2f, w - margin, cy + bh / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutButton()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.bg)
        val b = bmp
        if (b != null) {
            val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
            m.reset()
            m.postScale(s, s)
            m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
            c.drawBitmap(b, m, imgPaint)
        }
        drawButton(c)
    }

    private fun drawButton(c: Canvas) {
        val sc = 1f - 0.04f * press
        c.save()
        c.scale(sc, sc, btn.centerX(), btn.centerY())
        val h = btn.height()
        val rad = h * 0.34f
        val d = ui.dpf(1f)

        // soft glow (stacked strokes, no blur filter needed)
        val glowA = 0.35f + 0.45f * breathe + 0.2f * press
        p.style = Paint.Style.STROKE
        for (i in 7 downTo 1) {
            p.strokeWidth = d * (2f + i * 3.2f)
            p.color = Color.argb((22 * glowA * (8 - i) / 7f).toInt().coerceIn(0, 255), 60, 255, 160)
            c.drawRoundRect(btn, rad, rad, p)
        }

        // body
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, btn.top, 0f, btn.bottom,
            intArrayOf(Color.rgb(22, 112, 78), Color.rgb(10, 66, 46), Color.rgb(6, 42, 30)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(btn, rad, rad, p)
        p.shader = null
        // top sheen
        tmp.set(btn.left + d * 3, btn.top + d * 3, btn.right - d * 3, btn.centerY())
        p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, rad * 0.9f, rad * 0.9f, p)
        p.shader = null
        if (press > 0.01f) {
            p.color = Color.argb((90 * press).toInt(), 130, 255, 190)
            c.drawRoundRect(btn, rad, rad, p)
        }

        // moving light sweep + touch ripple, clipped to the button shape
        c.save()
        path.reset()
        path.addRoundRect(btn, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        if (phase < 0.5f) {
            val f = phase / 0.5f
            val bw = btn.width() * 0.28f
            val x0 = btn.left - bw + (btn.width() + bw * 2f) * f
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(x0, 0f, x0 + bw, 0f,
                intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(80, 255, 245, 200), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.save()
            c.skew(-0.35f, 0f)
            c.translate(btn.height() * 0.35f * 0.5f, 0f)
            c.drawRect(x0, btn.top, x0 + bw, btn.bottom, p)
            c.restore()
            p.shader = null
        }
        if (rpT < 1f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(((1f - rpT) * 110).toInt().coerceIn(0, 255), 190, 255, 220)
            c.drawCircle(rpX, rpY, btn.width() * 0.75f * rpT, p)
        }
        c.restore()

        // gold borders
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * 2.6f
        p.shader = LinearGradient(btn.left, 0f, btn.right, 0f,
            intArrayOf(goldDark, goldLight, gold, goldLight, goldDark), floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(btn, rad, rad, p)
        p.shader = null
        tmp.set(btn.left + d * 6, btn.top + d * 6, btn.right - d * 6, btn.bottom - d * 6)
        p.strokeWidth = d * 1f
        p.color = Color.argb(150, 236, 196, 96)
        c.drawRoundRect(tmp, rad * 0.78f, rad * 0.78f, p)

        // side ornaments: diamond + fading lines
        val cy = btn.centerY()
        for (side in intArrayOf(-1, 1)) {
            val x = if (side < 0) btn.left else btn.right
            val s = d * 7f
            path.reset()
            path.moveTo(x, cy - s)
            path.lineTo(x + s, cy)
            path.lineTo(x, cy + s)
            path.lineTo(x - s, cy)
            path.close()
            p.style = Paint.Style.FILL
            p.color = goldLight
            c.drawPath(path, p)
            p.color = Color.rgb(10, 66, 46)
            c.drawCircle(x, cy, d * 2f, p)
        }
        // corner dots
        p.style = Paint.Style.FILL
        p.color = gold
        val ins = rad * 0.55f
        c.drawCircle(btn.left + ins, btn.top + d * 1.3f, d * 2.2f, p)
        c.drawCircle(btn.right - ins, btn.top + d * 1.3f, d * 2.2f, p)
        c.drawCircle(btn.left + ins, btn.bottom - d * 1.3f, d * 2.2f, p)
        c.drawCircle(btn.right - ins, btn.bottom - d * 1.3f, d * 2.2f, p)

        // content: book icon + text
        val titleSize = h * 0.30f
        val subSize = h * 0.20f
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        p.textSize = titleSize
        p.letterSpacing = 0.14f
        p.textAlign = Paint.Align.LEFT
        val title = "GRIMOIRE"
        val tw = p.measureText(title)
        val iconW = h * 0.52f
        val gap = h * 0.22f
        val total = iconW + gap + tw
        val startX = btn.centerX() - total / 2f
        drawBook(c, startX, cy - iconW * 0.5f, iconW)
        val tx = startX + iconW + gap
        p.color = Color.rgb(236, 255, 244)
        p.setShadowLayer(d * 3f, 0f, d * 1.2f, Color.argb(160, 0, 20, 10))
        c.drawText(title, tx, cy + titleSize * 0.05f, p)
        p.clearShadowLayer()
        p.letterSpacing = 0f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = subSize
        p.color = goldLight
        p.textAlign = Paint.Align.LEFT
        val sub = ui.t("مكتبتي", "My Library")
        val sw = p.measureText(sub)
        c.drawText(sub, tx + (tw - sw) / 2f, cy + titleSize * 0.05f + subSize * 1.25f, p)
        p.typeface = Typeface.DEFAULT
        c.restore()
    }

    private fun drawBook(c: Canvas, x: Float, y: Float, w: Float) {
        val h = w * 0.84f
        val top = y + (w - h) / 2f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(x, top, x, top + h, goldLight, goldDark, Shader.TileMode.CLAMP)
        // left page
        path.reset()
        path.moveTo(x + w * 0.49f, top + h * 0.16f)
        path.cubicTo(x + w * 0.35f, top + h * 0.02f, x + w * 0.12f, top + h * 0.02f, x, top + h * 0.10f)
        path.lineTo(x, top + h * 0.86f)
        path.cubicTo(x + w * 0.12f, top + h * 0.78f, x + w * 0.35f, top + h * 0.80f, x + w * 0.49f, top + h * 0.96f)
        path.close()
        c.drawPath(path, p)
        // right page
        path.reset()
        path.moveTo(x + w * 0.51f, top + h * 0.16f)
        path.cubicTo(x + w * 0.65f, top + h * 0.02f, x + w * 0.88f, top + h * 0.02f, x + w, top + h * 0.10f)
        path.lineTo(x + w, top + h * 0.86f)
        path.cubicTo(x + w * 0.88f, top + h * 0.78f, x + w * 0.65f, top + h * 0.80f, x + w * 0.51f, top + h * 0.96f)
        path.close()
        c.drawPath(path, p)
        p.shader = null
        // text lines on pages
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, w * 0.035f)
        p.color = Color.argb(150, 10, 66, 46)
        for (i in 0..2) {
            val yy = top + h * (0.30f + 0.17f * i)
            c.drawLine(x + w * 0.10f, yy, x + w * 0.40f, yy + h * 0.02f, p)
            c.drawLine(x + w * 0.60f, yy + h * 0.02f, x + w * 0.90f, yy, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun hit(x: Float, y: Float): Boolean {
        val pad = ui.dpf(8f)
        return x >= btn.left - pad && x <= btn.right + pad && y >= btn.top - pad && y <= btn.bottom + pad
    }

    private fun animatePress(target: Float, ms: Long, end: (() -> Unit)? = null) {
        pressAnim?.cancel()
        val a = ValueAnimator.ofFloat(press, target)
        a.duration = ms
        a.addUpdateListener { press = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(an: android.animation.Animator) { if (!cancelled) end?.invoke() }
        })
        pressAnim = a
        a.start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = hit(e.x, e.y)
                if (down) {
                    rpX = e.x; rpY = e.y
                    rpAnim?.cancel()
                    rpAnim = ValueAnimator.ofFloat(0f, 1f).also { an ->
                        an.duration = 520
                        an.addUpdateListener { rpT = it.animatedValue as Float; invalidate() }
                        an.start()
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    playSoundEffect(SoundEffectConstants.CLICK)
                    animatePress(1f, 90)
                }
            }
            MotionEvent.ACTION_MOVE -> if (down && !hit(e.x, e.y)) { down = false; animatePress(0f, 130) }
            MotionEvent.ACTION_UP -> {
                if (down && hit(e.x, e.y)) {
                    down = false
                    performClick()
                    animatePress(1f, 50) { postDelayed({ onEnter() }, 110) }
                } else { down = false; animatePress(0f, 130) }
            }
            MotionEvent.ACTION_CANCEL -> { down = false; animatePress(0f, 130) }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
