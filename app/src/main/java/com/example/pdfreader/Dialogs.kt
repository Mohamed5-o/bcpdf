package com.example.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Copies every editable property (not id / page / position) from [o]. */
fun TextItem.assign(o: TextItem) {
    text = o.text; size = o.size; color = o.color; font = o.font; bold = o.bold; italic = o.italic
    underline = o.underline; alphaPct = o.alphaPct; rot = o.rot
    watermark = o.watermark; wmFrom = o.wmFrom; wmTo = o.wmTo
    remeasure()
}

class SignatureView(ctx: Context, private val ui: Ui) : View(ctx) {
    var penColor = Color.BLACK
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var minX = 1e9f
    private var minY = 1e9f
    private var maxX = -1e9f
    private var maxY = -1e9f
    private val strokes = ArrayList<Pair<Path, Int>>()
    private var cur: Path? = null

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = ui.dpf(3f)
        background = ui.rounded(Color.WHITE, 12)
    }

    fun clear() {
        strokes.clear()
        path.reset()
        minX = 1e9f; minY = 1e9f; maxX = -1e9f; maxY = -1e9f
        invalidate()
    }

    fun isEmpty() = strokes.isEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((p, col) in strokes) {
            paint.color = col
            canvas.drawPath(p, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path()
                p.moveTo(e.x, e.y)
                p.lineTo(e.x + 0.1f, e.y + 0.1f)
                strokes.add(Pair(p, penColor))
                cur = p
                track(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                cur?.lineTo(e.x, e.y)
                track(e.x, e.y)
            }
        }
        invalidate()
        return true
    }

    private fun track(x: Float, y: Float) {
        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
    }

    /** Transparent PNG-ready bitmap cropped to the drawn strokes. */
    fun export(): Bitmap? {
        if (strokes.isEmpty()) return null
        val pad = ui.dpf(6f)
        val w = max(8, (maxX - minX + pad * 2).toInt())
        val h = max(8, (maxY - minY + pad * 2).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.translate(-minX + pad, -minY + pad)
        for ((p, col) in strokes) {
            paint.color = col
            c.drawPath(p, paint)
        }
        return bmp
    }
}

private class PreviewView(ctx: Context, private val item: TextItem) : View(ctx) {
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        if (item.w <= 0f || item.h <= 0f) return
        val r = Math.toRadians(item.rot.toDouble())
        val bw = Math.abs(item.w * Math.cos(r)) + Math.abs(item.h * Math.sin(r))
        val bh = Math.abs(item.w * Math.sin(r)) + Math.abs(item.h * Math.cos(r))
        val k = min(1.6f, min((width - 16f) / bw.toFloat(), (height - 12f) / bh.toFloat()))
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(item.rot)
        canvas.scale(k, k)
        canvas.translate(-item.w / 2f, -item.h / 2f)
        item.drawContent(canvas)
        canvas.restore()
    }
}

class Dialogs(private val ui: Ui, private val st: Settings) {
    private val ctx: Context = ui.ctx
    private val swatches = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF555555.toInt(), 0xFF20BE78.toInt(),
        0xFF2F6BFF.toInt(), 0xFFE8BE4B.toInt(), 0xFFA06EF0.toInt(), 0xFFE85A5A.toInt()
    )

    fun fontName(i: Int): String = when (i) {
        0 -> ui.t("عادي", "Sans")
        1 -> ui.t("كلاسيكي", "Serif")
        2 -> ui.t("أحادي", "Mono")
        3 -> ui.t("ضيق", "Condensed")
        else -> ui.t("خط يدوي", "Handwriting")
    }

    private fun circle(color: Int, selected: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        g.setStroke(ui.dp(if (selected) 3 else 1), if (selected) C.accent else Color.argb(120, 255, 255, 255))
        return g
    }

    private fun swatchRow(selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<View>()
        fun refresh(sel: Int) {
            for (i in views.indices) views[i].background = circle(swatches[i], swatches[i] == sel)
        }
        for (c in swatches) {
            val v = View(ctx)
            v.layoutParams = ui.lpm(ui.dp(28), ui.dp(28), 0f, ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2))
            v.setOnClickListener { onPick(c); refresh(c) }
            views.add(v)
            row.addView(v)
        }
        refresh(selected)
        val sc = HorizontalScrollView(ctx)
        sc.isHorizontalScrollBarEnabled = false
        sc.addView(row)
        return sc
    }

    private fun stepper(value: Int, lo: Int, hi: Int, step: Int, suffix: String, onChange: (Int) -> Unit): View {
        var v = value
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ui.rounded(C.panel2, 12, C.line)
        val num = ui.txt("$v$suffix", 16f, C.white, true, true)
        val minus = ui.txt("−", 22f, C.white, true, true)
        val plus = ui.txt("+", 22f, C.white, true, true)
        minus.setOnClickListener { v = max(lo, v - step); num.text = "$v$suffix"; onChange(v) }
        plus.setOnClickListener { v = min(hi, v + step); num.text = "$v$suffix"; onChange(v) }
        row.addView(minus, ui.lp(ui.dp(48), ui.dp(44)))
        row.addView(num, ui.lp(0, ui.dp(44), 1f))
        row.addView(plus, ui.lp(ui.dp(48), ui.dp(44)))
        return row
    }

    private fun label(s: String): TextView {
        val t = ui.txt(s, 13f, C.muted)
        t.setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(4))
        return t
    }

    private fun toggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit): TextView {
        var state = on
        val t = ui.txt(label, 16f, C.white, true, true)
        fun paint() {
            t.background = if (state) ui.rounded(C.accentDark(), 10, C.accent) else ui.rounded(C.panel2, 10, C.line)
            t.setTextColor(if (state) C.accent else C.white)
        }
        paint()
        t.setOnClickListener { state = !state; paint(); onChange(state) }
        return t
    }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { f() }
        override fun afterTextChanged(s: Editable?) {}
    }

    // ───────────── text ─────────────

    fun textDialog(existing: TextItem?, newId: Long, onDone: (TextItem) -> Unit) {
        val d = TextItem(existing?.id ?: newId)
        if (existing != null) {
            d.assign(existing)
        } else {
            d.size = st.defTextSize.toFloat(); d.color = st.defTextColor; d.font = st.defTextFont
            d.remeasure()
        }
        ui.dialog { card, dlg ->
            card.addView(ui.txt(if (existing == null) ui.t("إضافة نص جديد", "Add text") else ui.t("تعديل النص", "Edit text"), 18f, C.gold, true, true))
            val input = ui.field(ui.t("اكتب النص هنا", "Type your text"), d.text, true)
            val preview = PreviewView(ctx, d)
            fun refresh() {
                d.text = input.text.toString()
                d.remeasure()
                preview.invalidate()
            }
            input.addTextChangedListener(watcher { refresh() })
            card.addView(input, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, ui.dp(6)))
            card.addView(preview, ui.lp(ui.MATCH, ui.dp(72)))

            card.addView(label(ui.t("اللون", "Colour")))
            card.addView(swatchRow(d.color) { d.color = it; refresh() })
            card.addView(label(ui.t("حجم الخط", "Font size")))
            card.addView(stepper(d.size.toInt(), 6, 200, 2, "") { d.size = it.toFloat(); refresh() })
            card.addView(label(ui.t("نوع الخط", "Font")))
            val fontBtn = ui.ghostBtn(fontName(d.font)) {}
            fontBtn.setOnClickListener {
                ui.menu(FONT_FAMILIES.indices.map { i ->
                    fontName(i) to { d.font = i; fontBtn.text = fontName(i); refresh() }
                })
            }
            card.addView(fontBtn, ui.lp(ui.MATCH, ui.WRAP))

            val bius = LinearLayout(ctx)
            bius.orientation = LinearLayout.HORIZONTAL
            bius.addView(toggleChip("B", d.bold) { d.bold = it; refresh() }, ui.lpm(0, ui.dp(44), 1f, 0, ui.dp(10), ui.dp(4), 0))
            bius.addView(toggleChip("I", d.italic) { d.italic = it; refresh() }, ui.lpm(0, ui.dp(44), 1f, ui.dp(4), ui.dp(10), ui.dp(4), 0))
            bius.addView(toggleChip("U", d.underline) { d.underline = it; refresh() }, ui.lpm(0, ui.dp(44), 1f, ui.dp(4), ui.dp(10), 0, 0))
            card.addView(bius)

            card.addView(label(ui.t("الشفافية", "Opacity") + "  ${d.alphaPct}%"))
            val op = ui.seek(90, d.alphaPct - 10) { }
            val opLabel = card.getChildAt(card.childCount - 1) as TextView
            op.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    d.alphaPct = p + 10
                    opLabel.text = ui.t("الشفافية", "Opacity") + "  ${d.alphaPct}%"
                    refresh()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            card.addView(op)

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(14), ui.dp(6), 0))
            row.addView(ui.primaryBtn(if (existing == null) ui.t("إضافة", "Add") else ui.t("حفظ", "Save")) {
                refresh()
                if (d.text.isBlank()) {
                    ui.toast(ui.t("اكتب النص أولًا", "Type some text first"))
                } else {
                    st.defTextSize = d.size.toInt(); st.defTextColor = d.color; st.defTextFont = d.font
                    dlg.dismiss()
                    onDone(d)
                }
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(14), 0, 0))
            card.addView(row)
            refresh()
        }
    }

    // ───────────── watermark ─────────────

    fun watermarkDialog(existing: TextItem?, newId: Long, pageCount: Int, currentPage: Int, onDone: (TextItem) -> Unit) {
        val d = TextItem(existing?.id ?: newId)
        d.watermark = true
        var scope = 0
        if (existing != null) {
            d.assign(existing)
            scope = if (existing.wmFrom == existing.wmTo) 0 else if (existing.wmFrom == 0 && existing.wmTo == pageCount - 1) 1 else 2
        } else {
            d.text = st.wmText; d.size = st.wmSize.toFloat(); d.color = st.wmColor
            d.alphaPct = st.wmAlpha; d.rot = -st.wmAngle.toFloat(); d.bold = true
            d.wmFrom = currentPage; d.wmTo = currentPage
            d.remeasure()
        }
        var from = if (existing != null) existing.wmFrom + 1 else currentPage + 1
        var to = if (existing != null) existing.wmTo + 1 else min(pageCount, currentPage + 1)
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("علامة مائية", "Watermark"), 18f, C.gold, true, true))
            val input = ui.field(ui.t("نص العلامة المائية", "Watermark text"), d.text)
            val preview = PreviewView(ctx, d)
            fun refresh() {
                d.text = input.text.toString()
                d.remeasure()
                preview.invalidate()
            }
            input.addTextChangedListener(watcher { refresh() })
            card.addView(input, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, ui.dp(6)))
            card.addView(preview, ui.lp(ui.MATCH, ui.dp(90)))
            card.addView(label(ui.t("اللون", "Colour")))
            card.addView(swatchRow(d.color) { d.color = it; refresh() })
            card.addView(label(ui.t("نوع الخط", "Font")))
            val fontBtn = ui.ghostBtn(fontName(d.font)) {}
            fontBtn.setOnClickListener {
                ui.menu(FONT_FAMILIES.indices.map { i -> fontName(i) to { d.font = i; fontBtn.text = fontName(i); refresh() } })
            }
            card.addView(fontBtn, ui.lp(ui.MATCH, ui.WRAP))
            card.addView(label(ui.t("حجم الخط", "Font size")))
            card.addView(stepper(d.size.toInt(), 12, 300, 4, "") { d.size = it.toFloat(); refresh() })
            card.addView(label(ui.t("الزاوية", "Angle")))
            card.addView(stepper((-d.rot).toInt(), -90, 90, 5, "°") { d.rot = -it.toFloat(); refresh() })
            val opLabel = label(ui.t("الشفافية", "Opacity") + "  ${d.alphaPct}%")
            card.addView(opLabel)
            card.addView(ui.seek(95, d.alphaPct - 5) { p ->
                d.alphaPct = p + 5
                opLabel.text = ui.t("الشفافية", "Opacity") + "  ${d.alphaPct}%"
                refresh()
            })

            card.addView(label(ui.t("تطبيق على", "Apply to")))
            val scopeRow = LinearLayout(ctx)
            scopeRow.orientation = LinearLayout.HORIZONTAL
            val rangeRow = LinearLayout(ctx)
            rangeRow.orientation = LinearLayout.HORIZONTAL
            val fFrom = ui.field("1", from.toString())
            fFrom.inputType = InputType.TYPE_CLASS_NUMBER
            val fTo = ui.field("$pageCount", to.toString())
            fTo.inputType = InputType.TYPE_CLASS_NUMBER
            rangeRow.addView(fFrom, ui.lpm(0, ui.WRAP, 1f, 0, 0, ui.dp(6), 0))
            rangeRow.addView(fTo, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), 0, 0, 0))
            val labels = listOf(ui.t("الحالية", "Current"), ui.t("الكل", "All"), ui.t("نطاق", "Range"))
            val chips = ArrayList<TextView>()
            fun paintScope() {
                for (i in chips.indices) {
                    val on = i == scope
                    chips[i].background = if (on) ui.rounded(C.accentDark(), 10, C.accent) else ui.rounded(C.panel2, 10, C.line)
                    chips[i].setTextColor(if (on) C.accent else C.white)
                }
                rangeRow.visibility = if (scope == 2) View.VISIBLE else View.GONE
            }
            for (i in labels.indices) {
                val chip = ui.txt(labels[i], 14f, C.white, true, true)
                chip.setPadding(0, ui.dp(11), 0, ui.dp(11))
                chip.setOnClickListener { scope = i; paintScope() }
                chips.add(chip)
                scopeRow.addView(chip, ui.lpm(0, ui.WRAP, 1f, ui.dp(2), 0, ui.dp(2), 0))
            }
            card.addView(scopeRow)
            card.addView(rangeRow, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
            paintScope()

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(14), ui.dp(6), 0))
            row.addView(ui.primaryBtn(if (existing == null) ui.t("إضافة", "Add") else ui.t("حفظ", "Save")) {
                refresh()
                if (d.text.isBlank()) {
                    ui.toast(ui.t("اكتب نص العلامة", "Type the watermark text"))
                    return@primaryBtn
                }
                when (scope) {
                    0 -> { d.wmFrom = currentPage; d.wmTo = currentPage }
                    1 -> { d.wmFrom = 0; d.wmTo = pageCount - 1 }
                    else -> {
                        val a = fFrom.text.toString().toIntOrNull()
                        val b = fTo.text.toString().toIntOrNull()
                        if (a == null || b == null || a < 1 || b > pageCount || a > b) {
                            ui.toast(ui.t("نطاق الصفحات غير صحيح (1 - $pageCount)", "Invalid page range (1 - $pageCount)"))
                            return@primaryBtn
                        }
                        d.wmFrom = a - 1; d.wmTo = b - 1
                    }
                }
                st.wmText = d.text; st.wmSize = d.size.toInt(); st.wmColor = d.color
                st.wmAlpha = d.alphaPct; st.wmAngle = (-d.rot).toInt()
                dlg.dismiss()
                onDone(d)
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(14), 0, 0))
            card.addView(row)
            refresh()
        }
    }

    // ───────────── signature ─────────────

    fun signatureDialog(saved: Bitmap?, onDone: (Bitmap, Boolean) -> Unit, onDeleteSaved: () -> Unit) {
        var keep = false
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("ارسم توقيعك", "Draw your signature"), 18f, C.gold, true, true))
            val sig = SignatureView(ctx, ui)
            card.addView(sig, ui.lpm(ui.MATCH, ui.dp(200), 0f, 0, ui.dp(10), 0, ui.dp(8)))
            val pens = LinearLayout(ctx)
            pens.orientation = LinearLayout.HORIZONTAL
            pens.gravity = Gravity.CENTER
            for (col in intArrayOf(Color.BLACK, Color.rgb(20, 60, 200))) {
                val v = View(ctx)
                v.background = circle(col, false)
                v.setOnClickListener { sig.penColor = col }
                pens.addView(v, ui.lpm(ui.dp(30), ui.dp(30), 0f, ui.dp(6), 0, ui.dp(6), 0))
            }
            pens.addView(ui.ghostBtn(ui.t("مسح", "Clear")) { sig.clear() }, ui.lpm(ui.WRAP, ui.WRAP, 0f, ui.dp(16), 0, 0, 0))
            card.addView(pens)
            card.addView(toggleChip(ui.t("حفظ التوقيع لاستخدامه لاحقًا", "Keep this signature for later"), false) { keep = it },
                ui.lpm(ui.MATCH, ui.dp(44), 0f, 0, ui.dp(10), 0, 0))
            if (saved != null) {
                val r = LinearLayout(ctx)
                r.orientation = LinearLayout.HORIZONTAL
                r.addView(ui.btn(ui.t("استخدام المحفوظ", "Use saved"), C.accentDark(), C.accent, C.accent) {
                    dlg.dismiss(); onDone(saved, false)
                }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(8), ui.dp(4), 0))
                r.addView(ui.ghostBtn(ui.t("حذف المحفوظ", "Delete saved")) {
                    onDeleteSaved(); ui.toast(ui.t("تم حذف التوقيع المحفوظ", "Saved signature deleted")); dlg.dismiss()
                }, ui.lpm(0, ui.WRAP, 1f, ui.dp(4), ui.dp(8), 0, 0))
                card.addView(r)
            }
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(12), ui.dp(6), 0))
            row.addView(ui.primaryBtn(ui.t("إضافة", "Add")) {
                val bmp = sig.export()
                if (bmp == null) {
                    ui.toast(ui.t("ارسم التوقيع أولًا", "Draw a signature first"))
                } else {
                    dlg.dismiss()
                    onDone(bmp, keep)
                }
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(12), 0, 0))
            card.addView(row)
        }
    }

    // ───────────── go to page ─────────────

    fun gotoDialog(current: Int, total: Int, onGo: (Int) -> Unit, onBookmarks: (() -> Unit)?) {
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("الانتقال إلى صفحة", "Go to page"), 18f, C.gold, true, true))
            card.addView(
                ui.txt(ui.t("الصفحة الحالية: ${current + 1} من $total", "Current page: ${current + 1} of $total"), 14f, C.muted, false, true),
                ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, ui.dp(8))
            )
            val f = ui.field(ui.t("أدخل رقم الصفحة (1 - $total)", "Page number (1 - $total)"), (current + 1).toString())
            f.inputType = InputType.TYPE_CLASS_NUMBER
            f.selectAll()
            card.addView(f, ui.lp(ui.MATCH, ui.WRAP))
            card.addView(ui.primaryBtn(ui.t("انتقال", "Go")) {
                val n = f.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > total) {
                    ui.toast(ui.t("رقم الصفحة يجب أن يكون بين 1 و $total", "Page must be between 1 and $total"))
                } else {
                    dlg.dismiss()
                    onGo(n - 1)
                }
            }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(12), 0, 0))
            if (onBookmarks != null) {
                card.addView(ui.ghostBtn(ui.t("الإشارات المرجعية", "Bookmarks")) { dlg.dismiss(); onBookmarks() },
                    ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
            }
        }
    }

    // ───────────── page text ─────────────

    fun copyTextDialog(text: String) {
        ui.dialog { card, dlg ->
            val top = LinearLayout(ctx)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(ui.txt(ui.t("نص الصفحة", "Page text"), 18f, C.white, true), ui.lp(0, ui.WRAP, 1f))
            top.addView(ui.icon("close", C.white, 40) { dlg.dismiss() })
            card.addView(top)
            val e = EditText(ctx)
            e.setText(text)
            e.setTextColor(C.white)
            e.textSize = ui.sp(15f)
            e.background = ui.rounded(C.panel2, 12, C.line)
            e.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            e.setTextIsSelectable(true)
            e.showSoftInputOnFocus = false
            e.maxLines = 12
            e.gravity = Gravity.TOP or Gravity.START
            card.addView(e, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, ui.dp(10)))
            card.addView(ui.primaryBtn(ui.t("نسخ النص", "Copy text")) {
                val sel = e.selectionEnd - e.selectionStart
                val out = if (sel > 0) e.text.substring(e.selectionStart, e.selectionEnd) else text
                copyToClipboard(out)
                ui.toast(ui.t("تم نسخ النص", "Text copied"))
            }, ui.lp(ui.MATCH, ui.WRAP))
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إغلاق", "Close")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(8), ui.dp(6), 0))
            row.addView(ui.ghostBtn(ui.t("تحديد الكل", "Select all")) {
                e.requestFocus()
                e.selectAll()
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(8), 0, 0))
            card.addView(row)
        }
    }

    fun copyToClipboard(s: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF text", s))
    }
}
