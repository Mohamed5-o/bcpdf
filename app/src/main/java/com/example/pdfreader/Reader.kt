package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * One open document: state lives here (document, edits, search, position) while the views are rebuilt on demand,
 * so language / theme changes or visiting Settings never lose the user's work.
 */
class ReaderScreen(val act: MainActivity, val entry: LibEntry, val doc: PdfDoc) {
    private val ui get() = act.ui
    private val st get() = act.st
    private val handler = Handler(Looper.getMainLooper())

    val edit = EditState()
    val textEngine = TextEngine { doc.localFile() }
    var currentPage = 0
    private var savedView: FloatArray? = null
    var searchHits: List<SearchHit> = emptyList()
    var searchIdx = -1
    var lastQuery = ""
    var lastResults: List<SearchHit> = emptyList()
    var editMode = false
        private set
    private var eraserOn = false
    private var toolsVisible = true
    private var bannerMark: ResumeMark? = null
    private var draftChecked = false

    // view settings of this document (profile or global defaults)
    val annots = ArrayList<HighlightItem>()
    var bakeHl = false
    private var vReadMode = "h"
    private var vFixedZoom = 0
    private var vComfort = 0
    private var vComfortLevel = 50
    private var vDim = 100
    private var vNight = false
    private var lastHlColor = 0xFFFFE04A.toInt()
    private val hlColors = intArrayOf(0xFFFFE04A.toInt(), 0xFF6FE08A.toInt(), 0xFFFF8FB5.toInt(), 0xFF7CC4FF.toInt())

    private var pv: PageView? = null
    private var topBar: LinearLayout? = null
    private var sideHolder: FrameLayout? = null
    private var editBar: LinearLayout? = null
    private var eraserPanel: LinearLayout? = null
    private var searchNav: LinearLayout? = null
    private var pagePill: TextView? = null
    private var titlePage: TextView? = null
    private var banner: LinearLayout? = null
    private var selBar: LinearLayout? = null
    private var undoBtn: IconView? = null
    private var redoBtn: IconView? = null
    private var delBtn: IconView? = null
    private var editSelBtn: IconView? = null
    private var searchCount: TextView? = null
    private var propPanel: LinearLayout? = null
    private var propForId = -1L
    private var scrubBox: View? = null
    private var scrubBar: android.widget.SeekBar? = null
    private var scrubTarget = 0
    private val scrubRun = Runnable { go(scrubTarget, false) }

    private val hideRunnable = Runnable { if (!keepVisible()) setToolsVisible(false) }
    private val draftRunnable = Runnable { writeDraft() }

    init {
        edit.onChange = {
            refreshEditBar()
            scheduleDraft()
        }
    }

    fun prepare(startPage: Int?) {
        val total = doc.pageCount
        if (startPage != null) {
            currentPage = startPage.coerceIn(0, total - 1)
        } else if (st.restorePos) {
            val a = act.marks.getAuto(entry.id)
            if (a != null) {
                currentPage = a[0].toInt().coerceIn(0, total - 1)
                savedView = floatArrayOf(a[1], a[2], a[3])
            }
        }
        val m = act.marks.resumeFor(entry.id)
        if (m != null && m.page != currentPage) bannerMark = m
        annots.clear()
        annots.addAll(act.annots.load(entry.id))
        bakeHl = annots.isNotEmpty()
        val pr = act.profiles.get(entry.id)
        vReadMode = pr?.readMode ?: st.readMode
        vFixedZoom = pr?.fixedZoom ?: st.fixedZoom
        vComfort = pr?.comfort ?: st.comfort
        vComfortLevel = pr?.comfortLevel ?: st.comfortLevel
        vDim = pr?.dim ?: st.dimPct
        vNight = pr?.night ?: st.night
    }

    private fun label(key: String): String = when (key) {
        "edit" -> ui.t("وضع التعديل", "Edit mode")
        "text" -> ui.t("إضافة نص", "Add text")
        "image" -> ui.t("إضافة صورة", "Add image")
        "sign" -> ui.t("إضافة توقيع", "Add signature")
        "wm" -> ui.t("علامة مائية", "Watermark")
        "eraser" -> ui.t("الاستيكة", "Eraser")
        "search" -> ui.t("بحث داخل الملف", "Search in file")
        "goto" -> ui.t("الانتقال إلى صفحة", "Go to page")
        "mark" -> ui.t("حفظ علامة القراءة", "Save reading mark")
        "copy" -> ui.t("نسخ النصوص", "Copy text")
        "view" -> ui.t("العرض والراحة للعين", "View & eye comfort")
        "hl" -> ui.t("التظليل والملاحظات", "Highlights & notes")
        "pdf" -> ui.t("أدوات الملف", "File tools")
        else -> ui.t("الوضع الليلي", "Night mode")
    }

    // ───────────────────────── build ─────────────────────────

    fun build(): View {
        val root = ui.screenRoot()
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.layoutDirection = ui.dir()
        top.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        top.background = android.graphics.drawable.ColorDrawable(C.overlay(238))
        topBar = top

        val content = FrameLayout(act)
        val view = PageView(act, ui, st)
        pv = view
        view.doc = doc
        view.edit = edit
        view.textEngine = textEngine
        view.readModeV = vReadMode
        view.fixedZoomPct = vFixedZoom
        view.comfort = vComfort
        view.comfortLevel = vComfortLevel
        view.dimPct = vDim
        view.nightMode = vNight
        view.highlights = annots
        view.onHighlightTap = { h -> highlightDialog(h) }
        view.editMode = editMode
        view.eraser = eraserOn
        view.eraserSizePt = st.eraserSize.toFloat()
        val m = act.marks.resumeFor(entry.id)
        view.onMarkMoved = { pg, cy ->
            val mm = act.marks.resumeFor(entry.id)
            if (mm != null) act.marks.putResume(mm.copy(page = pg, cy = cy, time = System.currentTimeMillis()))
        }
        view.markPage = m?.page ?: -1
        view.markCy = m?.cy ?: 0f
        view.onTap = { setToolsVisible(!toolsVisible) }
        view.onInteract = { if (toolsVisible) scheduleHide() }
        view.onPageSwipe = { d -> go(currentPage + d, false) }
        view.onPageChanged = { i ->
            currentPage = i
            entry.lastPage = i
            view.setPageHits(searchHits.filter { it.page == i }, searchHits.getOrNull(searchIdx))
            updateLabels()
        }
        view.onItemSelected = { refreshEditBar() }
        view.onItemsChanged = { refreshEditBar() }
        view.onSelection = { has -> selBar?.visibility = if (has) View.VISIBLE else View.GONE }
        view.onItemDelete = { item -> askDelete(item) }
        view.onItemEdit = { item -> if (item is TextItem) editTextItem(item) }
        content.addView(view, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
        content.addView(top, FrameLayout.LayoutParams(ui.MATCH, ui.dp(58), Gravity.TOP))

        // resume banner
        val ban = LinearLayout(act)
        ban.orientation = LinearLayout.HORIZONTAL
        ban.gravity = Gravity.CENTER_VERTICAL
        ban.layoutDirection = ui.dir()
        ban.background = ui.rounded(Color.argb(235, 40, 30, 8), 14, C.gold)
        ban.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
        ban.visibility = View.GONE
        banner = ban
        content.addView(ban, FrameLayout.LayoutParams(ui.MATCH, ui.WRAP, Gravity.TOP).also { it.setMargins(ui.dp(10), ui.dp(66), ui.dp(10), 0) })

        // text-selection bar
        val sb = LinearLayout(act)
        sb.orientation = LinearLayout.VERTICAL
        sb.gravity = Gravity.CENTER_HORIZONTAL
        sb.background = ui.rounded(C.overlay(242), 16, C.line)
        sb.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        sb.visibility = View.GONE
        val r1 = LinearLayout(act)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.layoutDirection = ui.dir()
        r1.addView(ui.primaryBtn(ui.t("نسخ", "Copy")) {
            val s = view.selectedText()
            if (s.isNotBlank()) {
                act.dialogs.copyToClipboard(s)
                ui.toast(ui.t("تم نسخ النص", "Text copied"))
            }
            view.clearTextSelection()
        }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, ui.dp(6), 0))
        r1.addView(ui.ghostBtn(ui.t("تحديد الكل", "Select all")) { view.selectAllText() }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, ui.dp(2), 0))
        r1.addView(ui.icon("close", C.white, 40) { view.clearTextSelection() })
        sb.addView(r1)
        val r2 = LinearLayout(act)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.layoutDirection = ui.dir()
        r2.addView(IconView(act, "marker", C.gold).also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); it.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        for (col in hlColors) {
            val dot = View(act)
            val g = android.graphics.drawable.GradientDrawable()
            g.shape = android.graphics.drawable.GradientDrawable.OVAL
            g.setColor(col)
            g.setStroke(ui.dp(1), C.line)
            dot.background = g
            dot.setOnClickListener { lastHlColor = col; addHighlight(col, "") }
            r2.addView(dot, ui.lpm(ui.dp(34), ui.dp(34), 0f, ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)))
        }
        r2.addView(ui.ghostBtn(ui.t("+ ملاحظة", "+ Note")) { noteForSelection() }, ui.lpm(ui.WRAP, ui.WRAP, 0f, ui.dp(4), 0, 0, 0))
        sb.addView(r2)
        selBar = sb
        content.addView(sb, FrameLayout.LayoutParams(ui.WRAP, ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.setMargins(0, ui.dp(66), 0, 0) })

        // bottom dock (tools)
        val holder = FrameLayout(act)
        sideHolder = holder

        // bottom stack
        val stack = LinearLayout(act)
        stack.orientation = LinearLayout.VERTICAL
        stack.gravity = Gravity.CENTER_HORIZONTAL
        stack.layoutDirection = ui.dir()

        val nav = LinearLayout(act)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.layoutDirection = View.LAYOUT_DIRECTION_LTR
        nav.background = ui.rounded(C.overlay(242), 22, C.line)
        nav.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        nav.addView(ui.icon("chevron", C.white, 40) { showHit(searchIdx - 1) }.also { it.rotation = 180f; it.flipForRtl = false })
        val cnt = ui.txt("", 14f, C.white, true, true)
        searchCount = cnt
        nav.addView(cnt, ui.lp(ui.dp(76), ui.WRAP))
        nav.addView(ui.icon("chevron", C.white, 40) { showHit(searchIdx + 1) }.also { it.flipForRtl = false })
        nav.addView(ui.icon("close", C.muted, 40) { clearSearch() })
        nav.visibility = View.GONE
        searchNav = nav
        stack.addView(nav, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(6)))

        val ep = LinearLayout(act)
        ep.orientation = LinearLayout.HORIZONTAL
        ep.gravity = Gravity.CENTER_VERTICAL
        ep.layoutDirection = ui.dir()
        ep.background = ui.rounded(C.overlay(242), 18, C.line)
        ep.setPadding(ui.dp(12), ui.dp(6), ui.dp(8), ui.dp(6))
        ep.addView(ui.icon("eraser", C.accent, 36))
        val sz = ui.seek(56, st.eraserSize - 4) { p ->
            st.eraserSize = p + 4
            pv?.eraserSizePt = (p + 4).toFloat()
        }
        ep.addView(sz, ui.lpm(ui.dp(160), ui.WRAP, 0f, ui.dp(6), 0, ui.dp(6), 0))
        ep.addView(ui.primaryBtn(ui.t("تم", "Done")) { setEraser(false) })
        ep.visibility = if (eraserOn) View.VISIBLE else View.GONE
        eraserPanel = ep
        stack.addView(ep, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(6)))

        val screenW = act.resources.displayMetrics.widthPixels
        val panelW = minOf(ui.dp(330), screenW - ui.dp(24))
        val pp = LinearLayout(act)
        pp.orientation = LinearLayout.VERTICAL
        pp.layoutDirection = ui.dir()
        pp.background = ui.rounded(C.overlay(246), 18, C.line)
        pp.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        pp.visibility = View.GONE
        propPanel = pp
        propForId = -1L
        stack.addView(pp, ui.lpm(panelW, ui.WRAP, 0f, 0, 0, 0, ui.dp(6)))

        val eb = LinearLayout(act)
        eb.orientation = LinearLayout.HORIZONTAL
        eb.gravity = Gravity.CENTER
        eb.layoutDirection = View.LAYOUT_DIRECTION_LTR
        eb.background = ui.rounded(C.overlay(242), 18, C.line)
        eb.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2))
        undoBtn = ui.icon("undo", C.white, 44) { if (edit.undo()) { view.deselect(false); view.itemsChanged() } }
        redoBtn = ui.icon("redo", C.white, 44) { if (edit.redo()) { view.deselect(false); view.itemsChanged() } }
        delBtn = ui.icon("trash", C.red, 44) { view.selectedItem()?.let { askDelete(it) } }
        editSelBtn = ui.icon("edit", C.white, 44) { (view.selectedItem() as? TextItem)?.let { editTextItem(it) } }
        eb.addView(undoBtn)
        eb.addView(redoBtn)
        eb.addView(editSelBtn)
        eb.addView(delBtn)
        eb.visibility = if (editMode) View.VISIBLE else View.GONE
        editBar = eb
        stack.addView(eb, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(10)))

        val sbx = LinearLayout(act)
        sbx.orientation = LinearLayout.HORIZONTAL
        sbx.gravity = Gravity.CENTER_VERTICAL
        sbx.layoutDirection = View.LAYOUT_DIRECTION_LTR
        sbx.background = ui.rounded(C.overlay(236), 22, 0)
        sbx.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        val sbar = android.widget.SeekBar(act)
        sbar.max = maxOf(1, doc.pageCount - 1)
        sbar.progress = currentPage
        sbar.progressTintList = android.content.res.ColorStateList.valueOf(C.accent)
        sbar.thumbTintList = android.content.res.ColorStateList.valueOf(C.gold)
        sbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                scrubTarget = progress
                pagePill?.text = ltr("${progress + 1} / ${doc.pageCount}")
                handler.removeCallbacks(scrubRun)
                handler.postDelayed(scrubRun, 150)
                if (toolsVisible) scheduleHide()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                handler.removeCallbacks(scrubRun)
                go(sb?.progress ?: currentPage, false)
            }
        })
        sbx.addView(sbar, ui.lp(panelW - ui.dp(112), ui.dp(40)))
        scrubBar = sbar
        sbx.setPadding(ui.dp(10), ui.dp(2), ui.dp(6), ui.dp(2))
        sbar.visibility = if (doc.pageCount > 1) View.VISIBLE else View.GONE
        scrubBox = sbar

        val pill = ui.txt("", 14f, C.gold, true, true)
        pill.textDirection = View.TEXT_DIRECTION_LTR
        pill.minWidth = ui.dp(70)
        pill.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        pill.setOnClickListener { showGoto() }
        pagePill = pill
        sbx.addView(pill, ui.lp(ui.WRAP, ui.WRAP))
        sbx.visibility = if (st.showPageNum || doc.pageCount > 1) View.VISIBLE else View.GONE
        pill.visibility = if (st.showPageNum) View.VISIBLE else View.GONE
        stack.addView(sbx, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(8)))
        stack.addView(holder, ui.lpm(ui.MATCH, ui.WRAP, 0f, ui.dp(10), 0, ui.dp(10), ui.dp(8)))
        content.addView(stack, FrameLayout.LayoutParams(ui.MATCH, ui.WRAP, Gravity.BOTTOM))

        root.addView(content, ui.lp(ui.MATCH, 0, 1f))

        fillTopBar()
        fillSidebar()
        refreshEditBar()
        view.showPage(currentPage, true, savedView)
        savedView = null
        updateHits()
        updateLabels()
        toolsVisible = true
        holder.visibility = View.VISIBLE
        scheduleHide()
        showBannerIfNeeded()
        checkDraft()
        return root
    }

    // ───────────────────────── top bar & side bar ─────────────────────────

    private fun fillTopBar() {
        val top = topBar ?: return
        top.removeAllViews()
        if (!editMode) {
            top.addView(ui.icon("back", C.white, 46) { requestExit() })
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val name = ui.txt(entry.name, 15f, C.white, true, true)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            col.addView(name)
            val pg = ui.txt("", 12f, C.muted, false, true)
            pg.setOnClickListener { showGoto() }
            titlePage = pg
            col.addView(pg)
            top.addView(col, ui.lp(0, ui.WRAP, 1f))
            top.addView(ui.icon("gear", C.white, 46) { act.showSettings() })
        } else {
            top.addView(ui.icon("close", C.white, 46) { setEditMode(false) })
            top.addView(ui.txt(ui.t("وضع التعديل", "Edit mode"), 18f, C.white, true, true), ui.lp(0, ui.WRAP, 1f))
            top.addView(ui.btn(ui.t("حفظ", "Save"), C.accent, Color.BLACK) { onSave() }.also { it.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) })
            titlePage = null
        }
        updateLabels()
    }

    private fun dockItem(kind: String, text: String, active: Boolean, tint: Int, onClick: () -> Unit): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.setPadding(0, ui.dp(7), 0, ui.dp(6))
        val col = if (active) C.accent else tint
        val ic = IconView(act, kind, col)
        ic.flipForRtl = ui.rtl
        c.addView(ic, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
        c.addView(ui.txt(text, 10.5f, if (active) C.accent else C.muted, false, true), ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(2), 0, 0))
        c.setOnClickListener { onClick() }
        ui.pressFx(c, 0.92f)
        return c
    }

    private fun fillSidebar() {
        val holder = sideHolder ?: return
        holder.removeAllViews()
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutDirection = ui.dir()
        bar.background = ui.rounded(C.overlay(240), 24, C.line)
        bar.setPadding(ui.dp(6), 0, ui.dp(6), 0)
        fun add(v: View) = bar.addView(v, ui.lp(0, ui.WRAP, 1f))
        if (!editMode) {
            val look = vNight || vComfort != 0 || vFixedZoom != 0 || vReadMode != "h" || vDim < 100
            add(dockItem("search", ui.t("بحث", "Search"), searchHits.isNotEmpty(), C.white) { toolSearch() })
            add(dockItem("goto", ui.t("انتقال", "Go to"), false, C.white) { showGoto() })
            add(dockItem("marker", ui.t("تظليل", "Highlight"), annots.isNotEmpty(), C.white) { toolHighlight() })
            add(dockItem("eye", ui.t("العرض", "View"), look, C.white) { viewPanel() })
            add(dockItem("more", ui.t("المزيد", "More"), false, C.white) { moreSheet() })
        } else {
            add(dockItem("text", ui.t("نص", "Text"), false, C.white) { toolText() })
            add(dockItem("image", ui.t("صورة", "Image"), false, C.white) { toolImage() })
            add(dockItem("sign", ui.t("توقيع", "Sign"), false, C.white) { toolSign() })
            add(dockItem("drop", ui.t("علامة مائية", "Watermark"), false, C.white) { toolWatermark() })
            add(dockItem("eraser", ui.t("استيكة", "Eraser"), eraserOn, C.white) { toolEraser() })
        }
        holder.addView(bar, FrameLayout.LayoutParams(ui.MATCH, ui.WRAP))
    }

    private fun tile(kind: String, text: String, tint: Int, onClick: () -> Unit): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.background = ui.rounded(C.panel2, 16)
        c.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(10))
        val ic = IconView(act, kind, tint)
        ic.flipForRtl = ui.rtl
        c.addView(ic, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
        val t = ui.txt(text, 12f, C.white, false, true)
        t.maxLines = 2
        c.addView(t, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(6), 0, 0))
        c.setOnClickListener { onClick() }
        ui.pressFx(c, 0.94f)
        return c
    }

    private fun tileGrid(card: LinearLayout, tiles: List<View>) {
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutDirection = ui.dir()
            for (k in 0 until 3) {
                val v = tiles.getOrNull(i + k)
                row.addView(v ?: View(act), ui.lpm(0, if (v == null) 1 else ui.WRAP, 1f, ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)))
            }
            card.addView(row)
            i += 3
        }
    }

    private fun moreSheet() {
        ui.dialog { card, dlg ->
            fun head(s: String) = card.addView(ui.txt(s, 13f, C.muted, true, true), ui.lpm(ui.MATCH, ui.WRAP, 0f, ui.dp(4), ui.dp(10), 0, ui.dp(2)))
            fun wrap(f: () -> Unit): () -> Unit = { dlg.dismiss(); f() }
            val hasMark = act.marks.resumeFor(entry.id) != null
            head(ui.t("القراءة", "Reading"))
            tileGrid(card, listOf(
                tile(if (hasMark) "bookmarkFill" else "bookmark", ui.t("علامة القراءة", "Reading mark"), if (hasMark) C.gold else C.white, wrap { toolMark() }),
                tile("copy", ui.t("نسخ نص الصفحة", "Copy page text"), C.white, wrap { toolCopy() }),
                tile(if (vNight) "moon" else "sun", ui.t("الوضع الليلي", "Night mode"), if (vNight) C.accent else C.white, wrap { toggleNight() }),
                tile("gear", ui.t("الإعدادات", "Settings"), C.white, wrap { act.showSettings() })
            ))
            head(ui.t("التعديل", "Editing"))
            tileGrid(card, listOf(
                tile("edit", if (editMode) ui.t("إنهاء التعديل", "Stop editing") else ui.t("وضع التعديل", "Edit mode"), if (editMode) C.accent else C.white, wrap { setEditMode(!editMode) }),
                tile("text", ui.t("إضافة نص", "Add text"), C.white, wrap { toolText() }),
                tile("image", ui.t("إضافة صورة", "Add image"), C.white, wrap { toolImage() }),
                tile("sign", ui.t("توقيع", "Signature"), C.white, wrap { toolSign() }),
                tile("drop", ui.t("علامة مائية", "Watermark"), C.white, wrap { toolWatermark() }),
                tile("eraser", ui.t("استيكة", "Eraser"), C.white, wrap { toolEraser() })
            ))
            head(ui.t("الملف", "File"))
            tileGrid(card, listOf(
                tile("tools", ui.t("ترتيب وحذف الصفحات", "Reorder / delete"), C.white, wrap { PageTools(act, this).open(0) }),
                tile("page", ui.t("استخراج صفحات", "Extract pages"), C.white, wrap { PageTools(act, this).open(1) }),
                tile("library", ui.t("دمج ملفات", "Merge files"), C.white, wrap { PageTools(act, this).open(2) }),
                tile("marker", ui.t("كل التظليلات", "All highlights"), C.white, wrap { toolHighlight() })
            ))
        }
    }

    private fun refreshEditBar() {
        val sel = pv?.selectedItem()
        undoBtn?.alpha = if (edit.canUndo()) 1f else 0.35f
        redoBtn?.alpha = if (edit.canRedo()) 1f else 0.35f
        delBtn?.alpha = if (sel != null) 1f else 0.35f
        editSelBtn?.alpha = if (sel is TextItem) 1f else 0.35f
        refreshPropPanel()
    }

    private fun propSeek(max: Int, value: Int, onChange: (Int) -> Unit): android.widget.SeekBar {
        val sk = android.widget.SeekBar(act)
        sk.max = max
        sk.progress = value.coerceIn(0, max)
        sk.progressTintList = android.content.res.ColorStateList.valueOf(C.accent)
        sk.thumbTintList = android.content.res.ColorStateList.valueOf(C.accent)
        sk.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { onChange(progress); pv?.invalidate() }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { edit.checkpoint() }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { edit.changed() }
        })
        return sk
    }

    private fun propRow(label: String, bar: View): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.layoutDirection = ui.dir()
        r.addView(ui.txt(label, 13f, C.muted), ui.lp(ui.dp(74), ui.WRAP))
        r.addView(bar, ui.lp(0, ui.dp(40), 1f))
        return r
    }

    private fun refreshPropPanel() {
        val pp = propPanel ?: return
        val sel = pv?.selectedItem()
        if (!editMode || eraserOn || sel == null) {
            pp.visibility = View.GONE
            propForId = -1L
            return
        }
        pp.visibility = View.VISIBLE
        if (sel.id == propForId) return
        propForId = sel.id
        pp.removeAllViews()
        if (sel is TextItem) {
            pp.addView(propRow(ui.t("الحجم", "Size"), propSeek(114, (sel.size - 6f).toInt()) { sel.size = (it + 6).toFloat(); sel.remeasure() }))
            pp.addView(propRow(ui.t("الشفافية", "Opacity"), propSeek(90, sel.alphaPct - 10) { sel.alphaPct = it + 10; sel.remeasure() }))
        } else if (sel is ImageItem) {
            val maxW = ((pv?.pageSizePt()?.x ?: 600f) * 1.2f).toInt()
            pp.addView(propRow(ui.t("الحجم", "Size"), propSeek(maxW, sel.w.toInt()) { sel.setWidth(maxOf(20, it).toFloat()) }))
        }
        pp.addView(propRow(ui.t("الدوران", "Rotate"), propSeek(360, (sel.rot + 180f).toInt()) {
            var r = (it - 180).toFloat()
            if (kotlin.math.abs(r) < 3f) r = 0f
            sel.rot = r
        }))
        if (sel is TextItem) {
            val sw = LinearLayout(act)
            sw.orientation = LinearLayout.HORIZONTAL
            sw.gravity = Gravity.CENTER_VERTICAL
            sw.layoutDirection = ui.dir()
            val cols = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF2F6BFF.toInt(), 0xFF20BE78.toInt(), 0xFFE85A5A.toInt(), 0xFFE8BE4B.toInt(), 0xFF808080.toInt())
            for (col in cols) {
                val dot = View(act)
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(col)
                g.setStroke(ui.dp(if ((sel.color and 0xFFFFFF) == (col and 0xFFFFFF)) 3 else 1), if ((sel.color and 0xFFFFFF) == (col and 0xFFFFFF)) C.accent else C.line)
                dot.background = g
                dot.setOnClickListener {
                    edit.checkpoint()
                    sel.color = col
                    sel.remeasure()
                    edit.changed()
                    pv?.invalidate()
                    propForId = -1L
                    refreshPropPanel()
                }
                sw.addView(dot, ui.lpm(ui.dp(30), ui.dp(30), 0f, ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(2)))
            }
            val b = ui.txt("B", 15f, if (sel.bold) C.accent else C.white, true, true)
            b.background = ui.rounded(if (sel.bold) C.accentDark() else C.panel2, 10, if (sel.bold) C.accent else C.line)
            b.minimumWidth = ui.dp(40)
            b.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            b.setOnClickListener {
                edit.checkpoint()
                sel.bold = !sel.bold
                sel.remeasure()
                edit.changed()
                pv?.invalidate()
                propForId = -1L
                refreshPropPanel()
            }
            sw.addView(b, ui.lpm(ui.WRAP, ui.WRAP, 0f, ui.dp(8), 0, 0, 0))
            pp.addView(sw, ui.lp(ui.MATCH, ui.WRAP))
        }
    }

    private fun ltr(s: String) = "\u202A" + s + "\u202C"

    private fun updateLabels() {
        val s = ltr("${currentPage + 1} / ${doc.pageCount}")
        pagePill?.text = s
        scrubBar?.progress = currentPage
        titlePage?.text = ui.t("الصفحة ", "Page ") + s
    }

    // ───────────────────────── visibility ─────────────────────────

    private fun keepVisible(): Boolean = editMode || eraserOn || (pv?.hasSelection() == true)

    private fun setToolsVisible(v: Boolean) {
        toolsVisible = v || keepVisible()
        fadeTo(sideHolder, toolsVisible)
        fadeTo(topBar, toolsVisible)
        fadeTo(scrubBox, toolsVisible && doc.pageCount > 1)
        if (toolsVisible) scheduleHide()
    }

    private fun fadeTo(v: View?, show: Boolean) {
        if (v == null) return
        v.animate().cancel()
        v.tag = show
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(180).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(240).withEndAction { if (v.tag == false) v.visibility = View.GONE }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (st.hideSecs > 0 && !keepVisible()) handler.postDelayed(hideRunnable, st.hideSecs * 1000L)
    }

    // ───────────────────────── navigation ─────────────────────────

    fun go(page: Int, keepZoom: Boolean) {
        if (page !in 0 until doc.pageCount) return
        currentPage = page
        pv?.showPage(page, keepZoom, null)
        entry.lastPage = page
        updateHits()
        updateLabels()
        if (st.autoSavePos) persistPosition()
    }

    private fun showGoto() {
        act.dialogs.gotoDialog(currentPage, doc.pageCount, { go(it, true) }, { act.showBookmarks(entry.id) })
    }

    fun persistPosition() {
        val v = pv?.viewState() ?: return
        entry.lastPage = currentPage
        entry.lastOpened = System.currentTimeMillis()
        act.store.update(entry)
        if (st.autoSavePos) act.marks.putAuto(entry.id, currentPage, v[0], v[1], v[2])
    }

    // ───────────────────────── banner / marks ─────────────────────────

    private fun showBannerIfNeeded() {
        val m = bannerMark ?: return
        val ban = banner ?: return
        bannerMark = null
        ban.removeAllViews()
        val changed = m.size > 0 && entry.size > 0 && m.size != entry.size
        val msg = ui.t("لديك علامة استكمال قراءة في صفحة ${m.page + 1}", "Reading mark on page ${m.page + 1}") +
            (if (changed) ui.t("\n⚠ الملف تغيّر منذ حفظ العلامة، قد يختلف الموضع", "\n⚠ The file changed since the mark was saved") else "")
        val t = ui.txt(msg, 13f, C.gold, true)
        t.setOnClickListener {
            ban.visibility = View.GONE
            go(m.page, false)
            val v = pv
            if (v != null && m.zoom > 1.01f) v.showPage(m.page, false, floatArrayOf(m.cx, m.cy, m.zoom))
        }
        ban.addView(t, ui.lp(0, ui.WRAP, 1f))
        ban.addView(ui.icon("close", C.gold, 36) { ban.visibility = View.GONE })
        ban.visibility = View.VISIBLE
        handler.postDelayed({ ban.visibility = View.GONE }, 9000)
    }

    private fun toolMark() {
        val existing = act.marks.resumeFor(entry.id)
        val save = {
            val v = pv?.viewState() ?: floatArrayOf(0.5f, 0f, 1f)
            act.marks.putResume(ResumeMark(entry.id, entry.name, entry.uri, currentPage, v[0], v[1], v[2], System.currentTimeMillis(), entry.size))
            pv?.markPage = currentPage
            pv?.markCy = v[1]
            pv?.invalidate()
            fillSidebar()
            ui.toast(ui.t("تم حفظ العلامة في الصفحة ${currentPage + 1} — اسحب السهم الذهبي لتحريكها", "Mark saved on page ${currentPage + 1} — drag the gold arrow to move it"))
        }
        if (existing == null) {
            save()
        } else {
            ui.menu(
                listOf(
                    ui.t("تحديث العلامة إلى هنا (صفحة ${currentPage + 1})", "Move mark here (page ${currentPage + 1})") to { save() },
                    ui.t("الانتقال إلى العلامة (صفحة ${existing.page + 1})", "Go to mark (page ${existing.page + 1})") to { go(existing.page, false) },
                    ui.t("حذف العلامة", "Delete mark") to {
                        act.marks.removeResume(entry.id)
                        pv?.markPage = -1
                        pv?.invalidate()
                        fillSidebar()
                    }
                ),
                ui.t("علامة استكمال القراءة", "Reading mark")
            )
        }
    }

    // ───────────────────────── highlights & notes ─────────────────────────

    private fun persistAnnots() {
        act.annots.save(entry.id, annots)
        bakeHl = annots.isNotEmpty()
        pv?.highlights = annots
        pv?.invalidate()
    }

    private fun addHighlight(color: Int, note: String) {
        val v = pv ?: return
        val rs = v.selectionRects()
        if (rs == null || rs.isEmpty()) {
            ui.toast(ui.t("حدّد نصًا أولًا (اضغط مطولًا على كلمة)", "Select some text first (long-press a word)"))
            return
        }
        val h = HighlightItem(System.nanoTime())
        h.page = v.pageIndex
        h.color = color
        h.note = note
        h.snippet = v.selectedText().replace('\n', ' ').trim().take(140)
        h.rects.addAll(rs)
        annots.add(h)
        persistAnnots()
        v.clearTextSelection()
        fillSidebar()
        ui.toast(ui.t("تم التظليل", "Highlighted"))
    }

    private fun noteForSelection() {
        val v = pv ?: return
        if (!v.hasSelection()) return
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("إضافة ملاحظة على النص المحدد", "Note on the selected text"), 18f, C.gold, true, true))
            val f = ui.field(ui.t("اكتب ملاحظتك", "Write your note"), "", true)
            card.addView(f, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, ui.dp(12)))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, 0, ui.dp(6), 0))
            row.addView(ui.primaryBtn(ui.t("حفظ", "Save")) {
                dlg.dismiss()
                addHighlight(lastHlColor, f.text.toString().trim())
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun colorDots(current: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutDirection = ui.dir()
        val dots = ArrayList<Pair<Int, View>>()
        var cur = current
        fun paint() {
            for ((c, d) in dots) {
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(c)
                g.setStroke(ui.dp(if (c == cur) 3 else 1), if (c == cur) C.accent else C.line)
                d.background = g
            }
        }
        for (c in hlColors) {
            val d = View(act)
            d.setOnClickListener { cur = c; paint(); onPick(c) }
            dots.add(c to d)
            row.addView(d, ui.lpm(ui.dp(34), ui.dp(34), 0f, ui.dp(4), 0, ui.dp(4), 0))
        }
        paint()
        return row
    }

    private fun highlightDialog(h: HighlightItem) {
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("تظليل — صفحة ${h.page + 1}", "Highlight — page ${h.page + 1}"), 18f, C.gold, true, true))
            if (h.snippet.isNotEmpty()) {
                val s = ui.txt(h.snippet, 13f, C.muted, false, true)
                s.maxLines = 3
                s.ellipsize = TextUtils.TruncateAt.END
                card.addView(s, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(6), 0, ui.dp(8)))
            }
            val f = ui.field(ui.t("الملاحظة", "Note"), h.note, true)
            card.addView(f, ui.lp(ui.MATCH, ui.WRAP))
            card.addView(colorDots(h.color) { h.color = it; pv?.invalidate() }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.btn(ui.t("حذف", "Delete"), C.panel2, C.red, C.line) {
                dlg.dismiss()
                annots.removeAll { it.id == h.id }
                persistAnnots()
                fillSidebar()
            }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(12), ui.dp(6), 0))
            row.addView(ui.primaryBtn(ui.t("حفظ", "Save")) {
                h.note = f.text.toString().trim()
                dlg.dismiss()
                persistAnnots()
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(12), 0, 0))
            card.addView(row)
        }
    }

    private fun toolHighlight() {
        val v = pv
        if (v != null && v.hasSelection()) {
            addHighlight(lastHlColor, "")
            return
        }
        if (annots.isEmpty()) {
            ui.toast(ui.t("حدّد نصًا بالضغط مطولًا على كلمة ثم اختر لون التظليل من الشريط العلوي", "Long-press a word to select text, then pick a highlight colour from the top bar"))
            return
        }
        ui.dialog(true, true) { card, dlg ->
            card.addView(ui.txt(ui.t("التظليل والملاحظات (${annots.size})", "Highlights & notes (${annots.size})"), 19f, C.gold, true, true))
            val list = LinearLayout(act)
            list.orientation = LinearLayout.VERTICAL
            val sv = ScrollView(act)
            sv.addView(list)
            fun fill() {
                list.removeAllViews()
                for (h in annots.sortedWith(compareBy({ it.page }, { it.created }))) {
                    val row = LinearLayout(act)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.layoutDirection = ui.dir()
                    row.background = ui.rounded(C.panel2, 10, C.line)
                    row.setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8))
                    val sw = View(act)
                    sw.background = ui.rounded(h.color, 6, 0)
                    row.addView(sw, ui.lpm(ui.dp(10), ui.dp(38), 0f, 0, 0, ui.dp(10), 0))
                    val col = LinearLayout(act)
                    col.orientation = LinearLayout.VERTICAL
                    col.addView(ui.txt(ui.t("صفحة ${h.page + 1}", "Page ${h.page + 1}"), 12f, C.gold, true))
                    val sn = ui.txt(if (h.snippet.isEmpty()) "—" else h.snippet, 13f, C.white)
                    sn.maxLines = 2
                    sn.ellipsize = TextUtils.TruncateAt.END
                    col.addView(sn)
                    if (h.note.isNotEmpty()) {
                        val nt = ui.txt("✎ " + h.note, 12f, C.accent)
                        nt.maxLines = 2
                        nt.ellipsize = TextUtils.TruncateAt.END
                        col.addView(nt)
                    }
                    row.addView(col, ui.lp(0, ui.WRAP, 1f))
                    row.addView(ui.icon("trash", C.red, 44) {
                        annots.removeAll { it.id == h.id }
                        persistAnnots()
                        fillSidebar()
                        fill()
                    })
                    row.setOnClickListener { dlg.dismiss(); go(h.page, false) }
                    list.addView(row, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(3), 0, ui.dp(3)))
                }
            }
            fill()
            card.addView(sv, ui.lp(ui.MATCH, ui.dp(360)))
            card.addView(ui.btn(ui.t("حفظ نسخة بالتظليل داخل الملف", "Save a copy with highlights inside the file"), C.accentDark(), C.accent, C.accent) {
                dlg.dismiss()
                bakeHl = true
                act.showSaveScreen(this)
            }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            card.addView(ui.ghostBtn(ui.t("إغلاق", "Close")) { dlg.dismiss() }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
        }
    }

    /** Called after the highlights were baked into the original file. */
    fun afterBaked() {
        annots.clear()
        act.annots.clear(entry.id)
        bakeHl = false
        pv?.highlights = annots
        fillSidebar()
    }

    // ───────────────────────── view & eye comfort ─────────────────────────

    private fun applyView() {
        val v = pv ?: return
        v.readModeV = vReadMode
        v.fixedZoomPct = vFixedZoom
        v.comfort = vComfort
        v.comfortLevel = vComfortLevel
        v.dimPct = vDim
        v.nightMode = vNight
        fillSidebar()
    }

    private fun chipGroup(labels: List<String>, selected: () -> Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = ui.dir()
        val chips = ArrayList<TextView>()
        fun paint() {
            for ((i, t) in chips.withIndex()) {
                val on = i == selected()
                t.background = ui.rounded(if (on) C.accentDark() else C.panel2, 16, if (on) C.accent else C.line)
                t.setTextColor(if (on) C.accent else C.white)
            }
        }
        for ((i, l) in labels.withIndex()) {
            val t = ui.txt(l, 13f, C.white, true, true)
            t.setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(8))
            t.setOnClickListener { onPick(i); paint() }
            chips.add(t)
            row.addView(t, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, ui.dp(6), 0))
        }
        paint()
        val hs = android.widget.HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        return hs
    }

    private fun viewSeek(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): android.widget.SeekBar {
        val sk = android.widget.SeekBar(act)
        sk.max = max - min
        sk.progress = (value - min).coerceIn(0, max - min)
        sk.progressTintList = android.content.res.ColorStateList.valueOf(C.accent)
        sk.thumbTintList = android.content.res.ColorStateList.valueOf(C.accent)
        sk.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress + min) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        return sk
    }

    private fun viewPanel() {
        val zoomVals = listOf(0, 125, 150, 175, 200, 250, 300)
        ui.dialog(true, false, 0.10f, true) { card, dlg ->
            card.addView(ui.txt(ui.t("العرض والراحة للعين", "View & eye comfort"), 18f, C.gold, true, true))
            fun lbl(s: String) = ui.txt(s, 12f, C.muted, true, true).also { it.setPadding(0, ui.dp(10), 0, ui.dp(4)) }

            card.addView(lbl(ui.t("طريقة التقليب", "Page turning")))
            card.addView(chipGroup(
                listOf(ui.t("أفقي", "Horizontal"), ui.t("رأسي (صفحة بصفحة)", "Vertical pages"), ui.t("متصل (تمرير)", "Continuous")),
                { when (vReadMode) { "v" -> 1; "c" -> 2; else -> 0 } },
                { vReadMode = when (it) { 1 -> "v"; 2 -> "c"; else -> "h" }; applyView() }
            ))

            card.addView(lbl(ui.t("التكبير الثابت (يبقى مع كل الصفحات وبعد تدوير الشاشة)", "Fixed zoom (kept on every page and after rotation)")))
            val zl = zoomVals.map { if (it == 0) ui.t("تلقائي", "Auto") else "$it%" }
            card.addView(chipGroup(zl, { zoomVals.indexOf(vFixedZoom) }, { vFixedZoom = zoomVals[it]; applyView() }))
            card.addView(ui.ghostBtn(ui.t("تثبيت التكبير الحالي", "Lock the current zoom")) {
                val z = ((pv?.zoomLevel() ?: 1f) * 100f).toInt()
                vFixedZoom = if (z <= 105) 0 else z.coerceAtMost(800)
                applyView()
                ui.toast(if (vFixedZoom == 0) ui.t("التكبير تلقائي", "Zoom is automatic") else ui.t("تم تثبيت التكبير على $vFixedZoom%", "Zoom locked at $vFixedZoom%"))
            }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(6), 0, 0))

            card.addView(lbl(ui.t("الوضع المريح للعين", "Eye comfort")))
            card.addView(chipGroup(
                listOf(ui.t("إيقاف", "Off"), ui.t("أصفر دافئ", "Warm yellow"), ui.t("أخضر هادئ", "Soft green"), ui.t("ليلي", "Night")),
                { if (vNight) 3 else vComfort },
                { i ->
                    if (i == 3) { vNight = true; st.night = true } else { vComfort = i; if (vNight) { vNight = false; st.night = false } }
                    if (i == 0) vComfort = 0
                    applyView()
                }
            ))
            card.addView(lbl(ui.t("شدة اللون", "Tint strength")))
            card.addView(viewSeek(0, 100, vComfortLevel) { vComfortLevel = it; pv?.comfortLevel = it })
            card.addView(lbl(ui.t("سطوع الصفحة", "Page brightness")))
            card.addView(viewSeek(30, 100, vDim) { vDim = it; pv?.dimPct = it })

            card.addView(ui.btn(ui.t("حفظ هذه الإعدادات لهذا الملف فقط", "Save these settings for this file only"), C.accentDark(), C.accent, C.accent) {
                act.profiles.put(entry.id, ProfileStore.Profile(vReadMode, vFixedZoom, vComfort, vComfortLevel, vDim, vNight))
                ui.toast(ui.t("تم الحفظ لهذا الملف", "Saved for this file"))
            }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(14), 0, 0))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("تطبيق على كل الملفات", "Apply to all files")) {
                st.readMode = vReadMode; st.fixedZoom = vFixedZoom; st.comfort = vComfort
                st.comfortLevel = vComfortLevel; st.dimPct = vDim; st.night = vNight
                act.profiles.remove(entry.id)
                ui.toast(ui.t("تم التطبيق على كل الملفات", "Applied to all files"))
            }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(8), ui.dp(4), 0))
            row.addView(ui.ghostBtn(ui.t("إعادة الافتراضي", "Reset")) {
                act.profiles.remove(entry.id)
                vReadMode = st.readMode; vFixedZoom = st.fixedZoom; vComfort = st.comfort
                vComfortLevel = st.comfortLevel; vDim = st.dimPct; vNight = st.night
                applyView()
                dlg.dismiss()
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(4), ui.dp(8), 0, 0))
            card.addView(row)
        }
    }

    // ───────────────────────── tools ─────────────────────────

    fun setEditMode(on: Boolean) {
        if (on && vReadMode != "h" && !editMode) {
            ui.toast(ui.t("التعديل يعمل في وضع الصفحة الواحدة، تم التبديل مؤقتًا", "Editing uses single-page view; switched temporarily"))
        }
        editMode = on
        if (!on) setEraser(false)
        pv?.editMode = on
        pv?.deselect(false)
        editBar?.visibility = if (on) View.VISIBLE else View.GONE
        fillTopBar()
        fillSidebar()
        setToolsVisible(true)
        refreshEditBar()
    }

    private fun ensureEdit() {
        if (!editMode) setEditMode(true)
    }

    private fun toolText() {
        ensureEdit()
        act.dialogs.textDialog(null, edit.newId()) { item ->
            pv?.placeAtCenter(item)
        }
    }

    private fun editTextItem(item: TextItem) {
        if (item.watermark) {
            act.dialogs.watermarkDialog(item, item.id, doc.pageCount, currentPage) { d ->
                edit.checkpoint()
                item.assign(d)
                edit.changed()
                pv?.itemsChanged()
            }
        } else {
            act.dialogs.textDialog(item, item.id) { d ->
                edit.checkpoint()
                item.assign(d)
                edit.changed()
                pv?.itemsChanged()
            }
        }
    }

    private fun toolImage() {
        ensureEdit()
        act.pickImage { uri -> addImage(uri) }
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var bmp = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            try {
                act.contentResolver.openInputStream(uri)?.use { ins ->
                    val ex = android.media.ExifInterface(ins)
                    val deg = when (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                    if (deg != 0f) {
                        val m = Matrix()
                        m.postRotate(deg)
                        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        if (r !== bmp) bmp.recycle()
                        bmp = r
                    }
                }
            } catch (_: Exception) {
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun addImage(uri: Uri) {
        Thread {
            val bmp = decodeImage(uri)
            act.runOnUiThread {
                val v = pv
                if (bmp == null || v == null) {
                    ui.toast(ui.t("تعذر إضافة الصورة", "Could not add the image"))
                } else {
                    ensureEdit()
                    val item = ImageItem(edit.newId(), bmp, false)
                    item.setWidth(v.pageSizePt().x * 0.4f)
                    v.placeAtCenter(item)
                }
            }
        }.start()
    }

    private fun toolSign() {
        ensureEdit()
        act.dialogs.signatureDialog(act.loadSignature(), { bmp, keep ->
            if (keep) act.saveSignature(bmp)
            val v = pv
            if (v != null) {
                val item = ImageItem(edit.newId(), bmp, true)
                item.setWidth(v.pageSizePt().x * 0.28f)
                v.placeAtCenter(item)
            }
        }, { act.deleteSignature() })
    }

    private fun toolWatermark() {
        ensureEdit()
        act.dialogs.watermarkDialog(null, edit.newId(), doc.pageCount, currentPage) { item ->
            pv?.placeAtCenter(item)
        }
    }

    private fun toolEraser() {
        ensureEdit()
        setEraser(!eraserOn)
    }

    private fun setEraser(on: Boolean) {
        eraserOn = on
        pv?.eraser = on
        pv?.eraserSizePt = st.eraserSize.toFloat()
        eraserPanel?.visibility = if (on) View.VISIBLE else View.GONE
        fillSidebar()
        if (on) {
            ui.toast(ui.t("الاستيكة تغطي الجزء المحدد بلون الخلفية فقط ولا تحذف النص الأصلي من الملف", "The eraser covers an area with the background colour; it does not remove the original text from the file"))
            setToolsVisible(true)
        } else scheduleHide()
    }

    private fun askDelete(item: Item) {
        val doIt = {
            edit.remove(item)
            pv?.deselect(false)
            pv?.itemsChanged()
            refreshEditBar()
        }
        if (st.confirmDelete) {
            ui.confirm(ui.t("حذف العنصر", "Delete item"), ui.t("هل تريد حذف هذا العنصر؟ يمكنك التراجع بزر التراجع.", "Delete this item? You can undo it."), ui.t("حذف", "Delete"), true) { doIt() }
        } else doIt()
    }

    private fun toggleNight() {
        vNight = !vNight
        st.night = vNight
        pv?.nightMode = vNight
        fillSidebar()
        scheduleHide()
    }

    private fun toolCopy() {
        val v = pv ?: return
        if (v.hasSelection()) {
            val s = v.selectedText()
            if (s.isNotBlank()) {
                act.dialogs.copyToClipboard(s)
                ui.toast(ui.t("تم نسخ النص المحدد", "Selected text copied"))
                v.clearTextSelection()
                return
            }
        }
        val pt = v.pageText
        if (pt == null) {
            ui.toast(ui.t("جارٍ تحميل نص الصفحة... حاول بعد لحظة", "Loading page text... try again in a moment"))
            return
        }
        val text = pt.text.trim()
        if (text.isEmpty()) {
            ui.toast(
                ui.t(
                    "لا يوجد نص قابل للاستخراج في هذه الصفحة (قد تكون صورة ممسوحة ضوئيًا، والتعرف على الحروف OCR غير متاح في هذه النسخة)",
                    "No extractable text on this page (it may be a scanned image; OCR is not available in this version)"
                )
            )
            return
        }
        act.dialogs.copyTextDialog(text)
    }

    // ───────────────────────── search ─────────────────────────

    private fun toolSearch() {
        act.showSearch(this)
    }

    fun setSearch(query: String, hits: List<SearchHit>, index: Int) {
        lastQuery = query
        lastResults = hits
        searchHits = hits
        searchIdx = if (hits.isEmpty()) -1 else index.coerceIn(0, hits.size - 1)
        if (searchIdx >= 0) currentPage = hits[searchIdx].page
    }

    private fun clearSearch() {
        searchHits = emptyList()
        searchIdx = -1
        updateHits()
    }

    fun showHit(i: Int) {
        if (searchHits.isEmpty()) return
        val idx = ((i % searchHits.size) + searchHits.size) % searchHits.size
        searchIdx = idx
        val h = searchHits[idx]
        if (h.page != currentPage) go(h.page, true) else updateHits()
    }

    private fun updateHits() {
        val v = pv ?: return
        val cur = searchHits.getOrNull(searchIdx)
        v.setPageHits(searchHits.filter { it.page == currentPage }, cur)
        if (cur != null && cur.page == currentPage) v.focusHit(cur)
        searchNav?.visibility = if (searchHits.isEmpty()) View.GONE else View.VISIBLE
        searchCount?.text = if (searchIdx >= 0) "${searchIdx + 1} / ${searchHits.size}" else ""
    }

    // ───────────────────────── drafts, saving, exit ─────────────────────────

    private fun scheduleDraft() {
        if (!st.saveDrafts) return
        handler.removeCallbacks(draftRunnable)
        handler.postDelayed(draftRunnable, 1200)
    }

    private fun writeDraft() {
        if (!st.saveDrafts) return
        val copy = edit.items.map { it.copyItem() }
        Thread { act.drafts.save(entry.id, copy) }.start()
    }

    private fun checkDraft() {
        if (draftChecked) return
        draftChecked = true
        if (!st.saveDrafts || !act.drafts.exists(entry.id) || !edit.isEmpty()) return
        ui.dialog(false) { card, dlg ->
            card.addView(ui.txt(ui.t("تعديلات غير محفوظة", "Unsaved edits"), 18f, C.gold, true, true))
            card.addView(
                ui.txt(ui.t("وُجدت تعديلات لم تُحفظ من الجلسة السابقة. هل تريد استعادتها؟", "Edits from the previous session were not saved. Restore them?"), 14f, C.white, false, true),
                ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, ui.dp(14))
            )
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("تجاهل", "Discard")) { act.drafts.clear(entry.id); dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, 0, ui.dp(6), 0))
            row.addView(ui.primaryBtn(ui.t("استعادة", "Restore")) {
                dlg.dismiss()
                if (act.drafts.load(entry.id, edit)) {
                    setEditMode(true)
                    pv?.itemsChanged()
                }
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun onSave() {
        if (edit.isEmpty() && annots.isEmpty()) {
            ui.toast(ui.t("لا توجد تعديلات للحفظ بعد", "There are no edits to save yet"))
            return
        }
        act.showSaveScreen(this)
    }

    /** Called after a successful save. */
    fun onSaved(reloadedFromOriginal: Boolean) {
        edit.clearAll()
        act.drafts.clear(entry.id)
        pv?.deselect(false)
        if (reloadedFromOriginal) {
            doc.close()
            doc.refreshLocalCopy()
            doc.open()
            textEngine.close()
            currentPage = currentPage.coerceIn(0, doc.pageCount - 1)
        }
        pv?.invalidate()
    }

    fun onPause() {
        handler.removeCallbacks(draftRunnable)
        if (st.saveDrafts && edit.dirty) {
            val copy = edit.items.map { it.copyItem() }
            act.drafts.save(entry.id, copy)
        }
        persistPosition()
    }

    fun requestExit() {
        if (edit.dirty && !edit.isEmpty()) {
            ui.dialog { card, dlg ->
                card.addView(ui.txt(ui.t("تعديلات غير محفوظة", "Unsaved edits"), 18f, C.gold, true, true))
                card.addView(
                    ui.txt(ui.t("لديك تعديلات لم تحفظها. ماذا تريد؟", "You have edits that are not saved."), 14f, C.white, false, true),
                    ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, ui.dp(14))
                )
                card.addView(ui.primaryBtn(ui.t("حفظ", "Save")) { dlg.dismiss(); onSave() }, ui.lp(ui.MATCH, ui.WRAP))
                card.addView(ui.btn(ui.t("الخروج دون حفظ", "Leave without saving"), C.panel2, C.red, C.line) {
                    dlg.dismiss()
                    act.drafts.clear(entry.id)
                    act.closeReader()
                }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
                card.addView(ui.ghostBtn(ui.t("البقاء", "Stay")) { dlg.dismiss() }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
            }
        } else act.closeReader()
    }

    /** Returns true when the key press was consumed. */
    fun onBack(): Boolean {
        val v = pv
        if (v != null && v.hasSelection()) {
            v.clearTextSelection()
            return true
        }
        if (eraserOn) {
            setEraser(false)
            return true
        }
        if (editMode) {
            setEditMode(false)
            return true
        }
        requestExit()
        return true
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        pv?.release()
        pv = null
        textEngine.close()
        doc.close()
    }

    fun detachViews() {
        handler.removeCallbacks(hideRunnable)
        val v = pv
        if (v != null) {
            val s = v.viewState()
            savedView = s
            v.release()
        }
        pv = null
    }
}
