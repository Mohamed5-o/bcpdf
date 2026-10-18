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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import kotlin.math.abs
import android.graphics.ColorFilter
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
    private var dragOff = 0f // horizontal drag offset while turning pages (px)
    private var settleAnim: ValueAnimator? = null
    private var settleEnd: (() -> Unit)? = null

    private class NB(val bmp: Bitmap, val pw: Float, val ph: Float)
    private val nbCache = HashMap<Int, NB>()
    private val pendingNb = HashSet<Int>()
    var onPageChanged: ((Int) -> Unit)? = null
    var forcePaged = false

    // ── continuous (all pages stacked vertically) ──
    private class CB(val bmp: Bitmap, val w: Int)
    private var psz: Array<PointF?> = emptyArray()
    private var tops = FloatArray(1)
    private var defSz = PointF(595f, 842f)
    private var cx = 0f
    private var cy = 0f
    private val cBmps = object : LinkedHashMap<Int, CB>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CB>?): Boolean = size > 5
    }
    private val cPending = HashSet<Int>()
    @Volatile private var cLo = 0
    @Volatile private var cHi = 0
    private val scroller = OverScroller(ctx)

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
        set(v) { val before = isCont(); field = v; modeChanged(before) }
    var eraser = false
        set(v) { val before = isCont(); field = v; modeChanged(before) }
    var eraserSizePt = 14f
    var nightMode = false
        set(v) {
            field = v
            rebuildFilter()
        }
    var comfort = 0                // 0 off, 1 yellow, 2 green
        set(v) { field = v; rebuildFilter() }
    var comfortLevel = 50
        set(v) { field = v; rebuildFilter() }
    var dimPct = 100
        set(v) { field = v; rebuildFilter() }
    var readModeV: String = st.readMode
        set(v) {
            if (field == v) return
            val before = isCont()
            field = v
            if (doc != null && width > 0) {
                finishSettleNow()
                dragOff = 0f
                if (before != isCont()) { scroller.abortAnimation(); showPage(pageIndex, false, null) } else { nbCache.clear(); requestBase(); invalidate() }
            }
        }
    var fixedZoomPct = 0           // 0 = automatic (fit); otherwise percent of the fitted size
        set(v) {
            if (field == v) return
            field = v
            if (doc != null && width > 0) showPage(pageIndex.coerceAtLeast(0), false, null)
        }
    var highlights: List<HighlightItem> = emptyList()
        set(v) { field = v; invalidate() }
    var onHighlightTap: ((HighlightItem) -> Unit)? = null
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
    var onMarkMoved: ((Int, Float) -> Unit)? = null

    // ── paints ──
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val tmpRect = RectF()

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val nightMatrix: ColorMatrix by lazy {
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
        inv
    }
    private var pageFilter: ColorFilter? = null

    fun rebuildFilter() {
        val cm = ColorMatrix()
        var any = false
        if (nightMode) { cm.set(nightMatrix); any = true }
        val lv = comfortLevel.coerceIn(0, 100) / 100f
        if (comfort != 0) {
            val r = if (comfort == 1) 1f else 1f - 0.42f * lv
            val g = if (comfort == 1) 1f - 0.16f * lv else 1f
            val b = if (comfort == 1) 1f - 0.55f * lv else 1f - 0.45f * lv
            cm.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            any = true
        }
        if (dimPct < 100) {
            val d = dimPct.coerceIn(20, 100) / 100f
            cm.postConcat(ColorMatrix(floatArrayOf(d, 0f, 0f, 0f, 0f, 0f, d, 0f, 0f, 0f, 0f, 0f, d, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            any = true
        }
        pageFilter = if (any) ColorMatrixColorFilter(cm) else null
        invalidate()
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
    private val G_MARK = 10
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
        if (isCont()) {
            contShow(index, keepZoom, restore)
            return
        }
        val size = d.pageSize(index) ?: return
        settleAnim?.removeAllListeners()
        settleAnim?.cancel()
        settleAnim = null
        settleEnd = null
        dragOff = 0f
        val oldBase = base
        if (oldBase != null && pageIndex != index) nbCache[pageIndex] = NB(oldBase, pw, ph)
        pageIndex = index
        pw = size.x
        ph = size.y
        gen++
        base = nbCache[index]?.bmp
        nbCache.remove(index)
        val keep = nbCache.keys.filter { it < index - 1 || it > index + 1 }
        for (k in keep) nbCache.remove(k)
        tile = null
        clearTextSelection()
        pageText = null
        pageHits = emptyList()
        currentHit = null
        deselect(false)
        computeFit()
        val fz = if (fixedZoomPct > 100) fixedZoomPct / 100f else 1f
        if (!keepZoom || restore != null) zoom = if (restore == null) fz.coerceIn(1f, 8f) else 1f
        if (restore != null) {
            zoom = restore[2].coerceIn(1f, 8f)
            val s = fit * zoom
            ox = width / 2f - restore[0] * pw * s
            oy = height / 2f - restore[1] * ph * s
        } else {
            val s = fit * zoom
            ox = if (pw * s <= width) (width - pw * s) / 2f else if (fz > 1f && st.arabic) width - pw * s else 0f
            oy = if (ph * s <= height) (height - ph * s) / 2f else 0f
        }
        clampPan()
        invalidate()
        requestBase()
        loadPageText()
    }

    fun relayout() {
        if (isCont()) { rebuildTops(); clampCont(); contEnsure(); invalidate(); return }
        nbCache.clear()
        computeFit()
        clampPan()
        requestBase()
        invalidate()
    }

    /** Visible centre as page fractions + zoom (for resume marks / auto-saved position). */
    fun viewState(): FloatArray {
        if (isCont() && psz.isNotEmpty()) {
            val i = pageIndex.coerceIn(0, psz.size - 1)
            val hh = max(1f, pageH(i) * zoom)
            val fx = ((cx + width / 2f) / max(1f, width * zoom)).coerceIn(0f, 1f)
            val fy = ((cy + height / 2f - tops[i] * zoom) / hh).coerceIn(0f, 1f)
            return floatArrayOf(fx, fy, zoom)
        }
        val s = fit * zoom
        val cx = ((width / 2f - ox) / s / pw).coerceIn(0f, 1f)
        val cy = ((height / 2f - oy) / s / ph).coerceIn(0f, 1f)
        return floatArrayOf(cx, cy, zoom)
    }

    fun pageCenterFraction(): PointF {
        if (isCont() && psz.isNotEmpty()) {
            val v = viewState()
            return PointF(v[0].coerceIn(0.05f, 0.95f), v[1].coerceIn(0.05f, 0.95f))
        }
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
            if (r != null) {
                if (isCont() && h.page in psz.indices) {
                    val sN = width * zoom / max(1f, (psz[h.page] ?: defSz).x)
                    cy = tops[h.page] * zoom + r.centerY() * sN - height * 0.4f
                    clampCont()
                    syncPageIndex(false)
                    contEnsure()
                } else ensureVisible(r)
            }
        }
        invalidate()
    }

    fun selectedText(): String {
        val pt = pageText ?: return ""
        if (selA < 0 || selB < 0) return ""
        return pt.slice(selA, selB)
    }

    fun hasSelection(): Boolean = selA >= 0 && selB >= 0
    fun zoomLevel(): Float = zoom

    /** Selected text as per-line rectangles (fractions of the page), or null. */
    fun selectionRects(): List<RectF>? {
        val pt = pageText ?: return null
        if (selA < 0 || selB < 0 || pw <= 0f || ph <= 0f) return null
        val lo = min(selA, selB)
        val hi = min(max(selA, selB), pt.rects.size - 1)
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in lo..hi) {
            val r = pt.rects[i] ?: continue
            val c = cur
            if (c != null && abs((c.top + c.bottom) / 2f - (r.top + r.bottom) / 2f) < (c.bottom - c.top) * 0.6f) {
                c.left = min(c.left, r.left); c.right = max(c.right, r.right)
                c.top = min(c.top, r.top); c.bottom = max(c.bottom, r.bottom)
            } else {
                if (c != null) out.add(c)
                cur = RectF(r)
            }
        }
        cur?.let { out.add(it) }
        return out.map { RectF(it.left / pw, it.top / ph, it.right / pw, it.bottom / ph) }
    }

    fun highlightAtPaged(x: Float, y: Float): HighlightItem? {
        if (highlights.isEmpty()) return null
        val fx = toPageX(x) / pw
        val fy = toPageY(y) / ph
        for (h in highlights.reversed()) if (h.page == pageIndex && h.contains(fx, fy, 0.004f)) return h
        return null
    }

    private fun highlightAtCont(x: Float, y: Float): HighlightItem? {
        if (highlights.isEmpty() || psz.isEmpty()) return null
        val i = pageAtY(cy + y)
        val hh = max(1f, pageH(i) * zoom)
        val fy = (cy + y - tops[i] * zoom) / hh
        val fx = (cx + x) / max(1f, width * zoom)
        for (h in highlights.reversed()) if (h.page == i && h.contains(fx, fy, 0.004f)) return h
        return null
    }

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
        settleAnim?.removeAllListeners()
        settleAnim?.cancel()
        scroller.abortAnimation()
        nbCache.clear()
        cBmps.clear()
        removeCallbacks(longPressRunnable)
        removeCallbacks(tileRunnable)
        exec.shutdownNow()
        base = null
        tile = null
    }

    // ───────────────────────── layout / rendering ─────────────────────────

    private fun computeFit() {
        if (width <= 0 || height <= 0) return
        fit = fitFor(pw, ph)
    }

    private fun fitFor(pwx: Float, phx: Float): Float {
        val pad = ui.dpf(6f)
        val fw = (width - 2 * pad) / pwx
        val fp = min(fw, (height - 2 * pad) / phx)
        return if (st.fitMode == "width") fw else fp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pending = pendingShow
        if (pending != null && w > 0 && h > 0) {
            pendingShow = null
            showPage(pending.first, pending.second, pending.third)
            return
        }
        if (isCont() && doc != null && w > 0 && h > 0) {
            if (psz.isNotEmpty()) { relayoutKeepAnchor() }
            return
        }
        if (doc != null && w > 0 && h > 0) {
            nbCache.clear()
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
                    prefetchNeighbors()
                }
            }
        }
    }

    private fun scheduleTile() {
        removeCallbacks(tileRunnable)
        postDelayed(tileRunnable, 230)
    }

    private fun renderTile() {
        if (isCont()) { contEnsure(); return }
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
        if (isCont() && psz.isNotEmpty()) {
            drawCont(canvas)
            return
        }
        if (dragOff != 0f) drawNeighbor(canvas)
        val s = scale()
        canvas.save()
        if (vert()) canvas.translate(0f, dragOff) else canvas.translate(dragOff, 0f)
        val pr = RectF(ox, oy, ox + pw * s, oy + ph * s)

        for (k in 4 downTo 1) {
            fillPaint.color = Color.argb(34, 0, 0, 0)
            val g = ui.dpf(1.6f) * k
            canvas.drawRoundRect(RectF(pr.left - g, pr.top - g * 0.6f, pr.right + g, pr.bottom + g * 1.4f), ui.dpf(3f), ui.dpf(3f), fillPaint)
        }
        fillPaint.color = if (nightMode) Color.rgb(20, 22, 21) else Color.WHITE
        canvas.drawRect(pr, fillPaint)
        pagePaint.colorFilter = pageFilter
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
        drawItems(canvas, pageIndex, pw, ph, true)
        if (markPage == pageIndex) drawMark(canvas, s, pw, ph)
        drawSelectionHandles(canvas, s)
        canvas.restore()
        canvas.restore()
    }

    private fun drawItems(c: Canvas, page: Int, W: Float, H: Float, frame: Boolean) {
        for (h in highlights) if (h.page == page) h.drawOn(c, W, H, fillPaint)
        val es = edit ?: return
        for (item in es.items) {
            if (!item.appliesTo(page)) continue
            if (item is EraseItem) {
                item.drawOn(c, W, H)
                continue
            }
            c.save()
            c.translate(item.nx * W, item.ny * H)
            c.rotate(item.rot)
            c.translate(-item.w / 2f, -item.h / 2f)
            item.drawContent(c)
            c.restore()
        }
        if (!frame) return
        eraseItem?.drawOn(c, W, H)
        val sel = es.byId(selectedId)
        if (sel != null && sel.appliesTo(page) && editMode) drawFrame(c, sel)
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

    private fun drawMark(c: Canvas, s: Float, pw: Float, ph: Float) {
        val y = markCy * ph
        strokePaint.color = Color.argb(170, 232, 190, 75)
        strokePaint.strokeWidth = ui.dpf(1.5f) / s
        c.drawLine(0f, y, pw, y, strokePaint)
        fillPaint.color = C.gold
        val a = ui.dpf(16f) / s
        val p = android.graphics.Path()
        p.moveTo(pw - a * 1.8f, y)
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
        if (isCont() && psz.isNotEmpty()) return onTouchCont(e)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishSettleNow()
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
                if (gesture == G_NONE && !editMode && markPage == pageIndex) {
                    val my = oy + markCy * ph * scale()
                    val rx = ox + pw * scale()
                    if (abs(e.y - my) < ui.dpf(30f) && e.x > min(rx, width.toFloat()) - ui.dpf(56f)) {
                        gesture = G_MARK
                        parent?.requestDisallowInterceptTouchEvent(true)
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
                    dragOff = 0f; overA = 0f
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
                if (gesture == G_MARK) {
                    markCy = ((y - oy) / scale() / ph).coerceIn(0f, 1f)
                    moved = true
                    invalidate()
                    return true
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
            g == G_MARK -> { if (moved) onMarkMoved?.invoke(markPage, markCy) }
            g == G_SEL_A || g == G_SEL_B -> {}
            g == G_PINCH -> scheduleTile()
            !moved && up && !longPressed -> handleTap(x, y)
            moved && up -> endPanSwipe()
        }
        if (dragOff != 0f && settleAnim == null) settle(0f, null)
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
        val hl = highlightAtPaged(x, y)
        if (hl != null) { onHighlightTap?.invoke(hl); return }
        onTap?.invoke()
    }

    // pan + page swipe ----------------------------------------------------------------

    private fun fwdSign(): Float = (if (vert()) -1f else (if (st.arabic) 1f else -1f)) * (if (st.flipSwipe) -1f else 1f)
    private fun gapPx() = ui.dpf(12f)

    private fun panOrSwipe(dx: Float, dy: Float) {
        val v = vert()
        val main = if (v) dy else dx
        var rm = main
        if (overA != 0f && Math.signum(main) == -Math.signum(overA)) {
            val back = if (abs(main) > abs(overA)) -overA else main
            overA += back
            rm = main - back
        }
        if (v) { ox += dx; oy += rm } else { ox += rm; oy += dy }
        val away = clampPan()
        overA += if (v) away[1] else away[0]
        val n = doc?.pageCount ?: 1
        val nextDir = Math.signum(overA) == fwdSign()
        val blocked = overA != 0f && ((nextDir && pageIndex >= n - 1) || (!nextDir && pageIndex <= 0))
        dragOff = if (blocked) overA * 0.3f else overA
        invalidate()
    }

    private fun endPanSwipe() {
        val vt = velocity
        var v = 0f
        if (vt != null) {
            vt.computeCurrentVelocity(1000)
            v = if (vert()) vt.yVelocity else vt.xVelocity
        }
        val axis = (if (vert()) height else width).toFloat()
        val dist = abs(overA)
        val far = max(ui.dpf(70f), axis * 0.18f)
        val near = ui.dpf(20f)
        val fast = abs(v) > 800f && dist > near && Math.signum(v) == Math.signum(overA)
        val n = doc?.pageCount ?: 1
        val nextDir = Math.signum(overA) == fwdSign()
        val blocked = (nextDir && pageIndex >= n - 1) || (!nextDir && pageIndex <= 0)
        if (overA != 0f && !blocked && (dist > far || fast)) {
            val dir = if (nextDir) 1 else -1
            val target = if (overA < 0) -(axis + gapPx()) else (axis + gapPx())
            settle(target) {
                overA = 0f
                onPageSwipe?.invoke(dir)
                dragOff = 0f
                invalidate()
            }
        } else {
            settle(0f, null)
            scheduleTile()
        }
        overA = 0f
    }

    private fun settle(target: Float, end: (() -> Unit)?) {
        settleAnim?.removeAllListeners()
        settleAnim?.cancel()
        if (abs(target - dragOff) < 0.5f) {
            dragOff = target
            settleAnim = null
            settleEnd = null
            if (end != null) end() else { dragOff = 0f; invalidate() }
            return
        }
        val a = ValueAnimator.ofFloat(dragOff, target)
        val frac = min(1f, abs(target - dragOff) / max(1f, (if (vert()) height else width).toFloat()))
        a.duration = (150 + 170 * frac).toLong()
        a.interpolator = DecelerateInterpolator(1.7f)
        a.addUpdateListener { dragOff = it.animatedValue as Float; invalidate() }
        settleEnd = end
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                val e = settleEnd
                settleEnd = null
                settleAnim = null
                if (e != null) e() else { dragOff = 0f; invalidate() }
            }
        })
        settleAnim = a
        a.start()
    }

    private fun finishSettleNow() {
        val a = settleAnim ?: return
        val e = settleEnd
        a.removeAllListeners()
        a.cancel()
        settleAnim = null
        settleEnd = null
        if (e != null) e() else { dragOff = 0f; invalidate() }
    }

    private fun neighborFor(off: Float): Int = pageIndex + (if (Math.signum(off) == fwdSign()) 1 else -1)

    private fun drawNeighbor(c: Canvas) {
        val d = doc ?: return
        val n = neighborFor(dragOff)
        if (n < 0 || n >= d.pageCount) return
        val vv = vert()
        val axisLen = (if (vv) height else width).toFloat()
        val xOff = dragOff + (if (dragOff < 0) axisLen + gapPx() else -(axisLen + gapPx()))
        val nb = nbCache[n]
        val pwN = nb?.pw ?: pw
        val phN = nb?.ph ?: ph
        val f = fitFor(pwN, phN)
        val w = pwN * f
        val h = phN * f
        val l = if (vv) (if (w <= width) (width - w) / 2f else 0f) else xOff + (if (w <= width) (width - w) / 2f else 0f)
        val t = if (vv) xOff + (if (h <= height) (height - h) / 2f else 0f) else (if (h <= height) (height - h) / 2f else 0f)
        tmpRect.set(l, t, l + w, t + h)
        fillPaint.color = if (nightMode) Color.rgb(20, 22, 21) else Color.WHITE
        c.drawRect(tmpRect, fillPaint)
        if (nb != null) {
            pagePaint.colorFilter = pageFilter
            c.drawBitmap(nb.bmp, null, tmpRect, pagePaint)
        }
    }

    private fun prefetchNeighbors() {
        val d = doc ?: return
        for (n in intArrayOf(pageIndex + 1, pageIndex - 1)) {
            if (n < 0 || n >= d.pageCount || nbCache.containsKey(n) || !pendingNb.add(n)) continue
            val w = width
            val h = height
            val fm = st.fitMode
            exec.execute {
                if (released || abs(n - pageIndex) > 1) {
                    ui2.post { pendingNb.remove(n) }
                    return@execute
                }
                val sz = d.pageSize(n)
                var bmp: Bitmap? = null
                if (sz != null) {
                    val pad = ui.dpf(6f)
                    val fwN = (w - 2 * pad) / sz.x
                    val fN = if (fm == "width") fwN else min(fwN, (h - 2 * pad) / sz.y)
                    bmp = d.renderFull(n, max(200, (sz.x * fN).toInt()), 4096)
                }
                ui2.post {
                    pendingNb.remove(n)
                    if (!released && bmp != null && sz != null && abs(n - pageIndex) <= 1) nbCache[n] = NB(bmp, sz.x, sz.y)
                }
            }
        }
    }

    // ───────────────────────── continuous mode ─────────────────────────

    fun isCont(): Boolean = readModeV == "c" && !editMode && !eraser && !forcePaged
    private fun vert(): Boolean = readModeV == "v" && !editMode && !eraser

    private fun modeChanged(before: Boolean) {
        val after = isCont()
        if (before == after || doc == null || width <= 0 || height <= 0) return
        scroller.abortAnimation()
        showPage(pageIndex, false, null)
    }

    private fun pageH(i: Int): Float {
        val s = psz[i] ?: defSz
        return width * s.y / max(1f, s.x)
    }

    private fun rebuildTops() {
        var y = 0f
        for (i in psz.indices) {
            tops[i] = y
            y += pageH(i) + ui.dpf(8f)
        }
        tops[psz.size] = y
    }

    private fun pageAtY(yDoc: Float): Int {
        var lo = 0
        var hi = psz.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] * zoom <= yDoc) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun clampCont() {
        val maxY = max(0f, tops[psz.size] * zoom - ui.dpf(8f) * zoom - height)
        val maxX = max(0f, width * zoom - width)
        cy = cy.coerceIn(0f, maxY)
        cx = cx.coerceIn(0f, maxX)
    }

    private fun initCont() {
        val d = doc ?: return
        val n = d.pageCount
        if (psz.size == n) return
        psz = arrayOfNulls(n)
        tops = FloatArray(n + 1)
        cBmps.clear()
        cPending.clear()
        Thread {
            for (i in 0 until n) {
                if (released) return@Thread
                val sz = d.pageSize(i)
                if (sz != null) psz[i] = sz
                if (i % 30 == 29) ui2.post { if (!released && isCont()) relayoutKeepAnchor() }
            }
            ui2.post { if (!released && isCont()) relayoutKeepAnchor() }
        }.also { it.isDaemon = true }.start()
    }

    private fun relayoutKeepAnchor() {
        if (psz.isEmpty() || width <= 0) return
        val i = pageAtY(cy + height / 2f)
        val off = cy - tops[i] * zoom
        rebuildTops()
        cy = tops[i] * zoom + off
        clampCont()
        syncPageIndex(false)
        contEnsure()
        invalidate()
    }

    private fun contShow(index: Int, keepZoom: Boolean, restore: FloatArray?) {
        val d = doc ?: return
        settleAnim?.removeAllListeners()
        settleAnim?.cancel()
        settleAnim = null
        settleEnd = null
        dragOff = 0f
        scroller.abortAnimation()
        initCont()
        val i = index.coerceIn(0, psz.size - 1)
        d.pageSize(i)?.let { psz[i] = it; defSz = it }
        val fzc = if (fixedZoomPct > 100) fixedZoomPct / 100f else 1f
        if (!keepZoom || restore != null) zoom = if (restore == null) fzc.coerceIn(1f, 4f) else 1f
        if (restore != null) zoom = restore[2].coerceIn(1f, 4f)
        zoom = zoom.coerceIn(1f, 4f)
        rebuildTops()
        if (restore != null) {
            cy = tops[i] * zoom + restore[1] * pageH(i) * zoom - height / 2f
            cx = restore[0] * width * zoom - width / 2f
        } else {
            cy = tops[i] * zoom
            if (!keepZoom) cx = if (fzc > 1f && st.arabic) max(0f, width * zoom - width) else 0f
        }
        clampCont()
        pageIndex = -1
        syncPageIndex(false, i)
        contEnsure()
        invalidate()
    }

    /** Updates [pageIndex] from the scroll position (or [force]); loads that page's text layer. */
    private fun syncPageIndex(notify: Boolean, force: Int = -1) {
        if (psz.isEmpty()) return
        val i = if (force >= 0) force else pageAtY(cy + height / 2f)
        if (i == pageIndex) return
        pageIndex = i
        val s = psz[i] ?: defSz
        pw = s.x
        ph = s.y
        gen++
        selA = -1
        selB = -1
        pageText = null
        pageHits = emptyList()
        currentHit = null
        loadPageText()
        if (notify) onPageChanged?.invoke(i)
    }

    private fun contPan(dx: Float, dy: Float) {
        cx -= dx
        cy -= dy
        clampCont()
        syncPageIndex(true)
        contEnsure()
        invalidate()
    }

    private fun contZoom(newZoom: Float, fx: Float, fy: Float) {
        val z0 = zoom
        val z1 = newZoom.coerceIn(1f, 4f)
        val dxx = (cx + fx) / z0
        val dyy = (cy + fy) / z0
        zoom = z1
        cx = dxx * z1 - fx
        cy = dyy * z1 - fy
        clampCont()
    }

    private fun contEnsure() {
        val d = doc ?: return
        if (width <= 0 || psz.isEmpty()) return
        val n = psz.size
        val lo = max(0, pageAtY(cy) - 1)
        val hi = min(n - 1, pageAtY(cy + height) + 1)
        cLo = lo
        cHi = hi
        val want = min((width * zoom).toInt(), 1800).coerceAtLeast(300)
        for (i in lo..hi) {
            val cb = cBmps[i]
            if (cb != null && cb.w >= want * 0.8f) continue
            if (!cPending.add(i)) continue
            exec.execute {
                if (released || i < cLo || i > cHi) {
                    ui2.post { cPending.remove(i) }
                    return@execute
                }
                val bmp = d.renderFull(i, want, 4096)
                ui2.post {
                    cPending.remove(i)
                    if (!released && bmp != null) {
                        cBmps[i] = CB(bmp, want)
                        invalidate()
                    }
                }
            }
        }
    }

    private fun drawCont(canvas: Canvas) {
        val n = psz.size
        val z = zoom
        pagePaint.colorFilter = pageFilter
        var i = pageAtY(cy)
        while (i < n) {
            val top = tops[i] * z - cy
            if (top > height) break
            val h = pageH(i) * z
            val w = width * z
            tmpRect.set(-cx, top, -cx + w, top + h)
            val rect = RectF(tmpRect)
            fillPaint.color = if (nightMode) Color.rgb(20, 22, 21) else Color.WHITE
            canvas.drawRect(rect, fillPaint)
            val cb = cBmps[i]
            if (cb != null) canvas.drawBitmap(cb.bmp, null, rect, pagePaint)
            else {
                fillPaint.color = C.muted
                fillPaint.textSize = ui.dpf(14f)
                fillPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("${i + 1}", rect.centerX(), min(max(rect.centerY(), 40f), height - 40f), fillPaint)
            }
            val sz = psz[i] ?: defSz
            val sN = w / max(1f, sz.x)
            canvas.save()
            canvas.clipRect(rect)
            canvas.translate(rect.left, rect.top)
            canvas.scale(sN, sN)
            if (i == pageIndex) drawHighlights(canvas, sN)
            drawItems(canvas, i, sz.x, sz.y, false)
            if (markPage == i) drawMark(canvas, sN, sz.x, sz.y)
            canvas.restore()
            i++
        }
    }

    override fun computeScroll() {
        if (isCont() && scroller.computeScrollOffset()) {
            cx = scroller.currX.toFloat()
            cy = scroller.currY.toFloat()
            clampCont()
            syncPageIndex(true)
            contEnsure()
            postInvalidateOnAnimation()
        }
    }

    private fun onTouchCont(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onInteract?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false
                gesture = G_PAN
                if (markPage in psz.indices && markPage < tops.size - 1) {
                    val top = tops[markPage] * zoom - cy
                    val my = top + markCy * pageH(markPage) * zoom
                    if (abs(e.y - my) < ui.dpf(30f) && e.x > min(-cx + width * zoom, width.toFloat()) - ui.dpf(56f)) gesture = G_MARK
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    gesture = G_PINCH
                    lastDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    lastMx = (e.getX(0) + e.getX(1)) / 2f
                    lastMy = (e.getY(0) + e.getY(1)) / 2f
                    moved = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == G_PINCH && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 0f && d > 0f) contZoom(zoom * d / lastDist, lastMx, lastMy)
                    cx -= mx - lastMx
                    cy -= my - lastMy
                    clampCont()
                    syncPageIndex(true)
                    lastDist = d; lastMx = mx; lastMy = my
                    invalidate()
                } else if (e.pointerCount == 1 && gesture == G_MARK) {
                    val top = tops[markPage] * zoom - cy
                    markCy = ((e.y - top) / max(1f, pageH(markPage) * zoom)).coerceIn(0f, 1f)
                    moved = true
                    invalidate()
                } else if (e.pointerCount == 1 && gesture == G_PAN) {
                    if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) moved = true
                    if (moved) contPan(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == G_PINCH && e.pointerCount <= 2) {
                    val i = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(i); lastY = e.getY(i)
                    downX = lastX; downY = lastY
                    gesture = G_PAN
                    moved = true
                    scheduleTile()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (gesture == G_PAN) {
                    if (!moved && up) {
                        val hl = highlightAtCont(e.x, e.y)
                        if (hl != null) onHighlightTap?.invoke(hl) else onTap?.invoke()
                    } else if (moved && up) {
                        val vt = velocity
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000)
                            val maxY = max(0f, tops[psz.size] * zoom - ui.dpf(8f) * zoom - height).toInt()
                            val maxX = max(0f, width * zoom - width).toInt()
                            scroller.fling(cx.toInt(), cy.toInt(), (-vt.xVelocity).toInt(), (-vt.yVelocity).toInt(), 0, maxX, 0, maxY)
                            postInvalidateOnAnimation()
                        }
                    }
                } else if (gesture == G_MARK) {
                    if (moved && up) onMarkMoved?.invoke(markPage, markCy)
                } else if (gesture == G_PINCH) scheduleTile()
                velocity?.recycle()
                velocity = null
                gesture = G_NONE
            }
        }
        return true
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
