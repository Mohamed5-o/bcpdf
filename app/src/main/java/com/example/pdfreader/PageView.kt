package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Single-page PDF surface.
 * Zoom is pinch only (no buttons). One finger pans when zoomed, swipes pages at the page edge,
 * moves the selected edit item, or erases (eraser tool). Long-press selects text.
 */
class PageView(ctx: Context, private val ui: Ui, private val st: Settings) : View(ctx) {

    // ── data ──
    var doc: PdfDoc? = null
    var edit: EditState? = null
    var textEngine: TextEngine? = null
    var pageIndex = 0
        private set
    private var pw = 595f
    private var ph = 842f

    // ── view transform: screen = page * (fit * zoom) + (ox, oy)
    private var zoom = 1f
    private var fit = 1f
    private var ox = 0f
    private var oy = 0f
    private var ovx = 0f // visual drag feedback while swiping pages
    private var ovy = 0f

    // ── bitmaps ──
    private var base: Bitmap? = null
    private var tile: Bitmap? = null
    private val tileRect = RectF()
    private var gen = 0
    private val exec = Executors.newSingleThreadExecutor()
    private val ui2 = Handler(Looper.getMainLooper())
    private var released = false

    // ── modes ──
    var editMode = false
    var eraser = false
    var eraserSizePt = 14f
    var nightMode = false
        set(v) {
            field = v
            invalidate()
        }
    var selectedId = -1L
        private set

    // ── text layer ──
    var pageText: PageText? = null
        private set
    private var selA = -1
    private var selB = -1

    // ── search / marks ──
    private var pageHits: List<SearchHit> = emptyList()
    private var currentHit: SearchHit? = null
    var markPage = -1
    var markCy = 0f

    // ── callbacks ──
    var onTap: (() -> Unit)? = null
    var onPageSwipe: ((Int) -> Unit)? = null
    var onItemSelected: ((Item?) -> Unit)? = null
    var onItemEdit: ((Item) -> Unit)? = null
    var onItemDelete: ((Item) -> Unit)? = null
    var onSelection: ((Boolean) -> Unit)? = null
    var onInteract: (() -> Unit)? = null
    var onItemsChanged: (() -> Unit)? = null
    var onPageReady: (() -> Unit)? = null

    // ── paints ──
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val tmpRect = RectF()

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val nightFilter: ColorMatrixColorFilter by lazy {
        // invert + hue-rotate(180°): dark page, text light, photos keep roughly natural colours
        val inv = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val hue = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        inv.postConcat(hue)
        ColorMatrixColorFilter(inv)
    }

    // ── gesture state ──
    private val G_NONE = 0
    private val G_PAN = 1
    private val G_PINCH = 2
    private val G_ITEM_MOVE = 3
    private val G_ITEM_RESIZE = 4
    private val G_ITEM_ROTATE = 5
    private val G_ERASE = 6
    private val G_SEL_A = 7
    private val G_SEL_B = 8
    private val G_ITEM_DELETE = 9
    private var gesture = G_NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var longPressed = false
    private var checkpointed = false
    private var overA = 0f
    private var velocity: VelocityTracker? = null
    private var lastDist = 0f
    private var lastMx = 0f
    private var lastMy = 0f
    private var startDist = 1f
    private var startSize = 1f
    private var startRot = 0f
    private var startAngle = 0f
    private var eraseItem: EraseItem? = null

    private val longPressRunnable = Runnable { startLongPress() }
    private val tileRunnable = Runnable { renderTile() }

    // ───────────────────────── public API ─────────────────────────

    private var pendingShow: Triple<Int, Boolean, FloatArray?>? = null

    fun showPage(index: Int, keepZoom: Boolean, restore: FloatArray?) {
        val d = doc ?: return
        if (width <= 0 || height <= 0) {
            pendingShow = Triple(index, keepZoom, restore)
            return
        }
        val size = d.pageSize(index) ?: return
        pageIndex = index
        pw = size.x
        ph = size.y
        gen++
        base = null
        tile = null
        clearTextSelection()
        pageText = null
        pageHits = emptyList()
        currentHit = null
        deselect(false)
        computeFit()
        if (!keepZoom || restore != null) zoom = 1f
        if (restore != null) {
            zoom = restore[2].coerceIn(1f, 8f)
            val s = fit * zoom
            ox = width / 2f - restore[0] * pw * s
            oy = height / 2f - restore[1] * ph * s
        } else {
            val s = fit * zoom
            ox = if (pw * s <= width) (width - pw * s) / 2f else 0f
            oy = if (ph * s <= height) (height - ph * s) / 2f else 0f
        }
        clampPan()
        invalidate()
        requestBase()
        loadPageText()
    }

    fun relayout() {
        computeFit()
        clampPan()
        requestBase()
        invalidate()
    }

    /** Visible centre as page fractions + zoom (for resume marks / auto-saved position). */
    fun viewState(): FloatArray {
        val s = fit * zoom
        val cx = ((width / 2f - ox) / s / pw).coerceIn(0f, 1f)
        val cy = ((height / 2f - oy) / s / ph).coerceIn(0f, 1f)
        return floatArrayOf(cx, cy, zoom)
    }

    fun pageCenterFraction(): PointF {
        val s = fit * zoom
        return PointF(((width / 2f - ox) / s / pw).coerceIn(0.05f, 0.95f), ((height / 2f - oy) / s / ph).coerceIn(0.05f, 0.95f))
    }

    fun pageSizePt(): PointF = PointF(pw, ph)

    fun setPageHits(hits: List<SearchHit>, current: SearchHit?) {
        pageHits = hits
        currentHit = current
        invalidate()
    }

    fun focusHit(h: SearchHit) {
        currentHit = h
        val pt = pageText
        if (pt != null) {
            val r = unionRect(pt, h.start, h.start + h.len - 1)
            if (r != null) ensureVisible(r)
        }
        invalidate()
    }

    fun selectedText(): String {
        val pt = pageText ?: return ""
        if (selA < 0 || selB < 0) return ""
        return pt.slice(selA, selB)
    }

    fun hasSelection(): Boolean = selA >= 0 && selB >= 0

    fun selectAllText() {
        val pt = pageText ?: return
        if (pt.text.isBlank()) return
        selA = 0
        selB = pt.text.length - 1
        onSelection?.invoke(true)
        invalidate()
    }

    fun clearTextSelection() {
        if (selA >= 0 || selB >= 0) {
            selA = -1
            selB = -1
            onSelection?.invoke(false)
            invalidate()
        }
    }

    fun selectItem(id: Long) {
        selectedId = id
        onItemSelected?.invoke(edit?.byId(id))
        invalidate()
    }

    fun deselect(notify: Boolean = true) {
        if (selectedId != -1L) {
            selectedId = -1L
            if (notify) onItemSelected?.invoke(null)
            invalidate()
        }
    }

    fun selectedItem(): Item? = edit?.byId(selectedId)

    /** Places [item] in the middle of what the user is looking at and selects it. */
    fun placeAtCenter(item: Item) {
        val c = pageCenterFraction()
        item.page = pageIndex
        item.nx = c.x
        item.ny = c.y
        edit?.add(item)
        selectedId = item.id
        onItemSelected?.invoke(item)
        onItemsChanged?.invoke()
        invalidate()
    }

    fun itemsChanged() {
        invalidate()
        onItemsChanged?.invoke()
    }

    fun release() {
        released = true
        removeCallbacks(longPressRunnable)
        removeCallbacks(tileRunnable)
        exec.shutdownNow()
        base = null
        tile = null
    }

    // ───────────────────────── layout / rendering ─────────────────────────

    private fun computeFit() {
        if (width <= 0 || height <= 0) return
        val fp = min(width / pw, height / ph)
        val fw = width / pw
        fit = if (st.fitMode == "width") fw else fp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pending = pendingShow
        if (pending != null && w > 0 && h > 0) {
            pendingShow = null
            showPage(pending.first, pending.second, pending.third)
            return
        }
        if (doc != null && w > 0 && h > 0) {
            val keepCx = if (oldw > 0) (oldw / 2f - ox) / (fit * zoom) else 0f
            computeFit()
            if (oldw <= 0 || zoom <= 1.001f) {
                val s = fit * zoom
                ox = if (pw * s <= w) (w - pw * s) / 2f else 0f
                oy = if (ph * s <= h) (h - ph * s) / 2f else 0f
            } else {
                ox = w / 2f - keepCx * fit * zoom
            }
            clampPan()
            requestBase()
        }
    }

    private fun requestBase() {
        val d = doc ?: return
        if (width <= 0 || height <= 0) return
        val g = gen
        val idx = pageIndex
        val targetW = min((pw * fit * 1.6f).toInt(), 2600).coerceAtLeast(300)
        exec.execute {
            if (released) return@execute
            val bmp = d.renderFull(idx, targetW, 3600)
            ui2.post {
                if (!released && g == gen && bmp != null) {
                    base = bmp
                    invalidate()
                    onPageReady?.invoke()
                    scheduleTile()
                }
            }
        }
    }

    private fun scheduleTile() {
        removeCallbacks(tileRunnable)
        postDelayed(tileRunnable, 230)
    }

    private fun renderTile() {
        val d = doc ?: return
        if (zoom < 1.25f || released) {
            tile = null
            invalidate()
            return
        }
        val s = fit * zoom
        val left = max(0f, -ox / s)
        val top = max(0f, -oy / s)
        val right = min(pw, (width - ox) / s)
        val bottom = min(ph, (height - oy) / s)
        if (right <= left || bottom <= top) return
        val w = ceil(((right - left) * s).toDouble()).toInt()
        val h = ceil(((bottom - top) * s).toDouble()).toInt()
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return
        val g = gen
        val idx = pageIndex
        exec.execute {
            if (released) return@execute
            val bmp = d.renderRegion(idx, s, left, top, w, h)
            ui2.post {
                if (!released && g == gen && bmp != null && abs(fit * zoom - s) < s * 0.02f) {
                    tile = bmp
                    tileRect.set(left, top, right, bottom)
                    invalidate()
                }
            }
        }
    }

    private fun loadPageText() {
        val te = textEngine ?: return
        val g = gen
        val idx = pageIndex
        exec.execute {
            if (released) return@execute
            val pt = try { te.page(idx) } catch (_: Throwable) { null }
            ui2.post {
                if (!released && g == gen && pt != null) {
                    pageText = pt
                    val h = currentHit
                    if (h != null && h.page == pageIndex) focusHit(h)
                    invalidate()
                }
            }
        }
    }

    // ───────────────────────── geometry helpers ─────────────────────────

    private fun scale() = fit * zoom

    private fun clampPan(): FloatArray {
        val s = scale()
        val cw = pw * s
        val ch = ph * s
        val nx = if (cw <= width) (width - cw) / 2f else ox.coerceIn(width - cw, 0f)
        val ny = if (ch <= height) (height - ch) / 2f else oy.coerceIn(height - ch, 0f)
        val away = floatArrayOf(ox - nx, oy - ny)
        ox = nx
        oy = ny
        return away
    }

    private fun zoomAround(newZoom: Float, fx: Float, fy: Float) {
        val s0 = scale()
        val px = (fx - ox) / s0
        val py = (fy - oy) / s0
        zoom = newZoom.coerceIn(1f, 8f)
        val s1 = scale()
        ox = fx - px * s1
        oy = fy - py * s1
    }

    private fun toPageX(x: Float) = (x - ox) / scale()
    private fun toPageY(y: Float) = (y - oy) / scale()

    private fun ensureVisible(r: RectF) {
        val s = scale()
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val sx = ox + cx * s
        val sy = oy + cy * s
        if (sx < 0 || sx > width) ox = width / 2f - cx * s
        if (sy < height * 0.12f || sy > height * 0.88f) oy = height / 2f - cy * s
        clampPan()
        scheduleTile()
    }

    private fun unionRect(pt: PageText, a: Int, b: Int): RectF? {
        var out: RectF? = null
        val lo = max(0, min(a, b))
        val hi = min(pt.rects.size - 1, max(a, b))
        for (i in lo..hi) {
            val r = pt.rects[i] ?: continue
            if (out == null) out = RectF(r) else out.union(r)
        }
        return out
    }

    // ───────────────────────── drawing ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (nightMode) Color.rgb(12, 14, 13) else Color.rgb(24, 30, 28))
        if (doc == null) return
        val s = scale()
        canvas.save()
        canvas.translate(ovx, ovy)
        val pr = RectF(ox, oy, ox + pw * s, oy + ph * s)

        fillPaint.color = if (nightMode) Color.rgb(20, 22, 21) else Color.WHITE
        canvas.drawRect(pr, fillPaint)
        pagePaint.colorFilter = if (nightMode) nightFilter else null
        base?.let { canvas.drawBitmap(it, null, pr, pagePaint) }
        tile?.let {
            tmpRect.set(ox + tileRect.left * s, oy + tileRect.top * s, ox + tileRect.right * s, oy + tileRect.bottom * s)
            canvas.drawBitmap(it, null, tmpRect, pagePaint)
        }
        if (base == null) {
            fillPaint.color = C.muted
            fillPaint.textSize = ui.dpf(14f)
            fillPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("...", pr.centerX(), pr.centerY(), fillPaint)
        }

        // content in page space
        canvas.save()
        canvas.clipRect(pr)
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        drawHighlights(canvas, s)
        drawItems(canvas)
        drawMark(canvas, s)
        drawSelectionHandles(canvas, s)
        canvas.restore()
        canvas.restore()
    }

    private fun drawItems(c: Canvas) {
        val es = edit ?: return
        for (item in es.items) {
            if (!item.appliesTo(pageIndex)) continue
            if (item is EraseItem) {
                item.drawOn(c, pw, ph)
                continue
            }
            c.save()
            c.translate(item.nx * pw, item.ny * ph)
            c.rotate(item.rot)
            c.translate(-item.w / 2f, -item.h / 2f)
            item.drawContent(c)
            c.restore()
        }
        eraseItem?.drawOn(c, pw, ph)
        val sel = es.byId(selectedId)
        if (sel != null && sel.appliesTo(pageIndex) && editMode) drawFrame(c, sel)
    }

    private fun drawFrame(c: Canvas, item: Item) {
        val s = scale()
        c.save()
        c.translate(item.nx * pw, item.ny * ph)
        c.rotate(item.rot)
        strokePaint.color = C.accent
        strokePaint.strokeWidth = ui.dpf(1.6f) / s
        c.drawRect(-item.w / 2f, -item.h / 2f, item.w / 2f, item.h / 2f, strokePaint)
        val r = ui.dpf(11f) / s
        // delete (top-left), resize (bottom-right), rotate (above top-centre)
        fillPaint.color = C.red
        c.drawCircle(-item.w / 2f, -item.h / 2f, r, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = ui.dpf(1.8f) / s
        val k = r * 0.42f
        c.drawLine(-item.w / 2f - k, -item.h / 2f - k, -item.w / 2f + k, -item.h / 2f + k, strokePaint)
        c.drawLine(-item.w / 2f - k, -item.h / 2f + k, -item.w / 2f + k, -item.h / 2f - k, strokePaint)
        fillPaint.color = C.accent
        c.drawCircle(item.w / 2f, item.h / 2f, r, fillPaint)
        c.drawRect(item.w / 2f - k, item.h / 2f - k, item.w / 2f + k, item.h / 2f + k, strokePaint)
        val ry = -item.h / 2f - ui.dpf(30f) / s
        strokePaint.color = C.accent
        strokePaint.strokeWidth = ui.dpf(1.2f) / s
        c.drawLine(0f, -item.h / 2f, 0f, ry, strokePaint)
        c.drawCircle(0f, ry, r, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = ui.dpf(1.6f) / s
        tmpRect.set(-k, ry - k, k, ry + k)
        c.drawArc(tmpRect, -60f, 270f, false, strokePaint)
        c.restore()
    }

    private fun drawHighlights(c: Canvas, s: Float) {
        val pt = pageText ?: return
        if (st.searchHighlight) {
            for (h in pageHits) {
                val r = unionRect(pt, h.start, h.start + h.len - 1) ?: continue
                fillPaint.color = if (h === currentHit) Color.argb(150, 255, 150, 30) else Color.argb(110, 255, 220, 40)
                c.drawRect(r.left - 1f, r.top, r.right + 1f, r.bottom, fillPaint)
            }
        }
        if (selA >= 0 && selB >= 0) {
            val lo = min(selA, selB)
            val hi = max(selA, selB)
            fillPaint.color = C.accentSoft(95)
            for (i in lo..min(hi, pt.rects.size - 1)) {
                val r = pt.rects[i] ?: continue
                c.drawRect(r.left, r.top, r.right + 0.6f, r.bottom, fillPaint)
            }
        }
    }

    private fun selHandlePoints(): Array<PointF>? {
        val pt = pageText ?: return null
        if (selA < 0 || selB < 0) return null
        val lo = min(selA, selB)
        val hi = max(selA, selB)
        var a: RectF? = null
        var b: RectF? = null
        var i = lo
        while (i <= hi && a == null) { a = pt.rects.getOrNull(i); i++ }
        i = hi
        while (i >= lo && b == null) { b = pt.rects.getOrNull(i); i-- }
        if (a == null || b == null) return null
        return arrayOf(PointF(a.left, a.bottom), PointF(b.right, b.bottom))
    }

    private fun drawSelectionHandles(c: Canvas, s: Float) {
        val pts = selHandlePoints() ?: return
        fillPaint.color = C.accent
        val r = ui.dpf(7f) / s
        for (p in pts) c.drawCircle(p.x, p.y + r, r, fillPaint)
    }

    private fun drawMark(c: Canvas, s: Float) {
        if (markPage != pageIndex) return
        val y = markCy * ph
        strokePaint.color = Color.argb(170, 232, 190, 75)
        strokePaint.strokeWidth = ui.dpf(1.5f) / s
        c.drawLine(0f, y, pw, y, strokePaint)
        fillPaint.color = C.gold
        val a = ui.dpf(12f) / s
        val p = android.graphics.Path()
        p.moveTo(pw - a * 1.6f, y)
        p.lineTo(pw, y - a)
        p.lineTo(pw, y + a)
        p.close()
        c.drawPath(p, fillPaint)
    }

    // ───────────────────────── hit testing ─────────────────────────

    private fun hitItem(px: Float, py: Float, pad: Float): Item? {
        val es = edit ?: return null
        for (i in es.items.indices.reversed()) {
            val it = es.items[i]
            if (!it.selectable() || !it.appliesTo(pageIndex)) continue
            if (hitLocal(it, px, py, pad)) return it
        }
        return null
    }

    private fun hitLocal(it: Item, px: Float, py: Float, pad: Float): Boolean {
        val l = toLocal(it, px, py)
        return abs(l[0]) <= it.w / 2f + pad && abs(l[1]) <= it.h / 2f + pad
    }

    private fun toLocal(it: Item, px: Float, py: Float): FloatArray {
        val dx = px - it.nx * pw
        val dy = py - it.ny * ph
        val r = Math.toRadians(it.rot.toDouble())
        val cr = cos(r).toFloat()
        val sr = sin(r).toFloat()
        return floatArrayOf(dx * cr + dy * sr, -dx * sr + dy * cr)
    }

    /** 1 = delete, 2 = resize, 3 = rotate, 0 = none (page coordinates). */
    private fun hitHandle(it: Item, px: Float, py: Float): Int {
        val s = scale()
        val rad = ui.dpf(24f) / s
        val l = toLocal(it, px, py)
        if (hypot(l[0] + it.w / 2f, l[1] + it.h / 2f) <= rad) return 1
        if (hypot(l[0] - it.w / 2f, l[1] - it.h / 2f) <= rad) return 2
        if (hypot(l[0], l[1] + it.h / 2f + ui.dpf(30f) / s) <= rad) return 3
        return 0
    }

    private fun hitSelHandle(x: Float, y: Float): Int {
        val pts = selHandlePoints() ?: return 0
        val s = scale()
        val rad = ui.dpf(28f)
        val ax = ox + pts[0].x * s
        val ay = oy + pts[0].y * s + ui.dpf(7f)
        val bx = ox + pts[1].x * s
        val by = oy + pts[1].y * s + ui.dpf(7f)
        val da = hypot(x - ax, y - ay)
        val db = hypot(x - bx, y - by)
        if (da <= rad && da <= db) return 1
        if (db <= rad) return 2
        return 0
    }

    // ───────────────────────── touch ─────────────────────────

    private fun startLongPress() {
        if (gesture != G_NONE || moved || editMode) return
        val pt = pageText ?: return
        val idx = pt.indexAt(toPageX(downX), toPageY(downY), 18f)
        if (idx < 0) return
        longPressed = true
        var a = idx
        var b = idx
        val t = pt.text
        if (idx < t.length && Character.isLetterOrDigit(t[idx])) {
            while (a > 0 && Character.isLetterOrDigit(t[a - 1])) a--
            while (b < t.length - 1 && Character.isLetterOrDigit(t[b + 1])) b++
        }
        selA = a
        selB = b
        gesture = G_SEL_B
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSelection?.invoke(true)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc == null) return true
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onInteract?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; longPressed = false; checkpointed = false; overA = 0f
                gesture = G_NONE
                val px = toPageX(e.x)
                val py = toPageY(e.y)
                if (editMode && eraser) {
                    startErase(px, py)
                    return true
                }
                val sel = selectedItem()
                if (editMode && sel != null && sel.appliesTo(pageIndex)) {
                    when (hitHandle(sel, px, py)) {
                        1 -> gesture = G_ITEM_DELETE
                        2 -> {
                            gesture = G_ITEM_RESIZE
                            startDist = max(1f, hypot(px - sel.nx * pw, py - sel.ny * ph))
                            startSize = if (sel is TextItem) sel.size else sel.w
                        }
                        3 -> {
                            gesture = G_ITEM_ROTATE
                            startRot = sel.rot
                            startAngle = Math.toDegrees(atan2((py - sel.ny * ph).toDouble(), (px - sel.nx * pw).toDouble())).toFloat()
                        }
                        else -> if (hitLocal(sel, px, py, 0f)) gesture = G_ITEM_MOVE
                    }
                }
                if (gesture == G_NONE && hasSelection()) {
                    val h = hitSelHandle(e.x, e.y)
                    if (h == 1) gesture = G_SEL_A else if (h == 2) gesture = G_SEL_B
                }
                if (gesture == G_NONE && !editMode) postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    removeCallbacks(longPressRunnable)
                    cancelErase()
                    gesture = G_PINCH
                    lastDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    lastMx = (e.getX(0) + e.getX(1)) / 2f
                    lastMy = (e.getY(0) + e.getY(1)) / 2f
                    ovx = 0f; ovy = 0f
                    removeCallbacks(tileRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == G_PINCH && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 0f && d > 0f) zoomAround(zoom * d / lastDist, lastMx, lastMy)
                    ox += mx - lastMx
                    oy += my - lastMy
                    clampPan()
                    lastDist = d; lastMx = mx; lastMy = my
                    moved = true
                    invalidate()
                    return true
                }
                if (e.pointerCount != 1) return true
                val x = e.x
                val y = e.y
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
                if (!moved && gesture != G_ERASE) return true
                val px = toPageX(x)
                val py = toPageY(y)
                when (gesture) {
                    G_ERASE -> continueErase(px, py)
                    G_ITEM_MOVE -> moveItem(x - lastX, y - lastY)
                    G_ITEM_RESIZE -> resizeItem(px, py)
                    G_ITEM_ROTATE -> rotateItem(px, py)
                    G_ITEM_DELETE -> {}
                    G_SEL_A, G_SEL_B -> dragSelection(px, py)
                    else -> panOrSwipe(x - lastX, y - lastY)
                }
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == G_PINCH && e.pointerCount <= 2) {
                    val i = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(i); lastY = e.getY(i)
                    downX = lastX; downY = lastY
                    gesture = G_PAN
                    moved = true
                    overA = 0f
                    scheduleTile()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                finishGesture(up, e.x, e.y)
                velocity?.recycle()
                velocity = null
                gesture = G_NONE
            }
        }
        return true
    }

    private fun finishGesture(up: Boolean, x: Float, y: Float) {
        val g = gesture
        when {
            g == G_ERASE -> finishErase(up)
            g == G_ITEM_DELETE -> {
                if (up && !moved) selectedItem()?.let { onItemDelete?.invoke(it) }
            }
            g == G_ITEM_MOVE || g == G_ITEM_RESIZE || g == G_ITEM_ROTATE -> {
                if (checkpointed) edit?.changed()
                if (!moved && up && g == G_ITEM_MOVE) {
                    val sel = selectedItem()
                    if (sel is TextItem) onItemEdit?.invoke(sel)
                }
            }
            g == G_SEL_A || g == G_SEL_B -> {}
            g == G_PINCH -> scheduleTile()
            !moved && up && !longPressed -> handleTap(x, y)
            moved && up -> endPanSwipe()
        }
        ovx = 0f; ovy = 0f
        invalidate()
    }

    private fun handleTap(x: Float, y: Float) {
        if (hasSelection()) {
            clearTextSelection()
            return
        }
        if (editMode) {
            val px = toPageX(x)
            val py = toPageY(y)
            val pad = ui.dpf(10f) / scale()
            val hit = hitItem(px, py, pad)
            if (hit != null) {
                if (hit.id != selectedId) selectItem(hit.id)
            } else if (selectedId != -1L) {
                deselect(true)
            } else {
                onTap?.invoke()
            }
            return
        }
        onTap?.invoke()
    }

    // pan + page swipe ----------------------------------------------------------------

    private fun swipeAxisIsX() = st.scrollDir != "v"

    private fun panOrSwipe(dx: Float, dy: Float) {
        ox += dx
        oy += dy
        val away = clampPan()
        overA += if (swipeAxisIsX()) away[0] else away[1]
        if (swipeAxisIsX()) { ovx = overA * 0.35f; ovy = 0f } else { ovy = overA * 0.35f; ovx = 0f }
        invalidate()
    }

    private fun endPanSwipe() {
        val vt = velocity
        var v = 0f
        if (vt != null) {
            vt.computeCurrentVelocity(1000)
            v = if (swipeAxisIsX()) vt.xVelocity else vt.yVelocity
        }
        val dist = abs(overA)
        val far = ui.dpf(64f)
        val near = ui.dpf(22f)
        val fast = abs(v) > 900f && dist > near
        if (dist > far || fast) {
            // finger moved left/up (negative) => next page
            onPageSwipe?.invoke(if (overA < 0) 1 else -1)
        } else {
            scheduleTile()
        }
        overA = 0f
    }

    // item manipulation -----------------------------------------------------------------

    private fun ensureCheckpoint() {
        if (!checkpointed) {
            edit?.checkpoint()
            checkpointed = true
        }
    }

    private fun moveItem(dx: Float, dy: Float) {
        val it = selectedItem() ?: return
        ensureCheckpoint()
        val s = scale()
        it.nx = (it.nx + dx / (s * pw)).coerceIn(0f, 1f)
        it.ny = (it.ny + dy / (s * ph)).coerceIn(0f, 1f)
        invalidate()
    }

    private fun resizeItem(px: Float, py: Float) {
        val it = selectedItem() ?: return
        ensureCheckpoint()
        val k = hypot(px - it.nx * pw, py - it.ny * ph) / startDist
        if (it is TextItem) {
            it.size = (startSize * k).coerceIn(4f, 400f)
            it.remeasure()
        } else if (it is ImageItem) {
            it.setWidth((startSize * k).coerceIn(12f, pw * 3f))
        }
        invalidate()
    }

    private fun rotateItem(px: Float, py: Float) {
        val it = selectedItem() ?: return
        ensureCheckpoint()
        val a = Math.toDegrees(atan2((py - it.ny * ph).toDouble(), (px - it.nx * pw).toDouble())).toFloat()
        var r = startRot + (a - startAngle)
        while (r > 180f) r -= 360f
        while (r <= -180f) r += 360f
        val snap = Math.round(r / 45f) * 45f
        if (abs(r - snap) < 3f) r = snap
        it.rot = r
        invalidate()
    }

    // text selection ----------------------------------------------------------------------

    private fun dragSelection(px: Float, py: Float) {
        val pt = pageText ?: return
        val idx = pt.indexAt(px, py, 60f)
        if (idx < 0) return
        if (gesture == G_SEL_A) selA = idx else selB = idx
        if (selA > selB) {
            val t = selA; selA = selB; selB = t
            gesture = if (gesture == G_SEL_A) G_SEL_B else G_SEL_A
        }
        invalidate()
    }

    // eraser ---------------------------------------------------------------------------------

    private fun startErase(px: Float, py: Float) {
        val e = edit ?: return
        val item = EraseItem(e.newId())
        item.page = pageIndex
        item.widthPt = eraserSizePt
        item.pts.add(PointF((px / pw).coerceIn(0f, 1f), (py / ph).coerceIn(0f, 1f)))
        item.color = sampleColor(item)
        eraseItem = item
        gesture = G_ERASE
        invalidate()
    }

    private fun continueErase(px: Float, py: Float) {
        val item = eraseItem ?: return
        val p = PointF((px / pw).coerceIn(0f, 1f), (py / ph).coerceIn(0f, 1f))
        val last = item.pts.last()
        if (hypot((p.x - last.x) * pw, (p.y - last.y) * ph) >= 1.5f) item.pts.add(p)
        invalidate()
    }

    private fun cancelErase() {
        if (gesture == G_ERASE) eraseItem = null
    }

    private fun finishErase(up: Boolean) {
        val item = eraseItem
        eraseItem = null
        if (item == null || !up) return
        item.color = sampleColor(item)
        edit?.add(item)
        onItemsChanged?.invoke()
    }

    /** Median colour of the ring just outside the stroke's bounding box (uniform backgrounds work best). */
    private fun sampleColor(item: EraseItem): Int {
        val b = base ?: return Color.WHITE
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (p in item.pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val kx = b.width / pw
        val ky = b.height / ph
        val m = item.widthPt / 2f + 4f
        val l = ((minX * pw - m) * kx).toInt()
        val t = ((minY * ph - m) * ky).toInt()
        val r = ((maxX * pw + m) * kx).toInt()
        val bo = ((maxY * ph + m) * ky).toInt()
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        fun take(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= b.width || y >= b.height) return
            val c = b.getPixel(x, y)
            rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c))
        }
        val stepX = max(1, (r - l) / 40)
        val stepY = max(1, (bo - t) / 40)
        var x = l
        while (x <= r) { take(x, t); take(x, bo); x += stepX }
        var y = t
        while (y <= bo) { take(l, y); take(r, y); y += stepY }
        if (rs.isEmpty()) return Color.WHITE
        rs.sort(); gs.sort(); bs.sort()
        val i = rs.size / 2
        return Color.rgb(rs[i], gs[i], bs[i])
    }
}
