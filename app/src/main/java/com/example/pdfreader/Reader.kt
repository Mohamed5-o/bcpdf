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
    var editMode = false
        private set
    private var eraserOn = false
    private var toolsVisible = true
    private var bannerMark: ResumeMark? = null
    private var draftChecked = false

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
        topBar = top
        root.addView(top, ui.lp(ui.MATCH, ui.WRAP))

        val content = FrameLayout(act)
        val view = PageView(act, ui, st)
        pv = view
        view.doc = doc
        view.edit = edit
        view.textEngine = textEngine
        view.nightMode = st.night
        view.editMode = editMode
        view.eraser = eraserOn
        view.eraserSizePt = st.eraserSize.toFloat()
        val m = act.marks.resumeFor(entry.id)
        view.markPage = m?.page ?: -1
        view.markCy = m?.cy ?: 0f
        view.onTap = { setToolsVisible(!toolsVisible) }
        view.onInteract = { if (toolsVisible) scheduleHide() }
        view.onPageSwipe = { d -> go(currentPage + d, true) }
        view.onItemSelected = { refreshEditBar() }
        view.onItemsChanged = { refreshEditBar() }
        view.onSelection = { has -> selBar?.visibility = if (has) View.VISIBLE else View.GONE }
        view.onItemDelete = { item -> askDelete(item) }
        view.onItemEdit = { item -> if (item is TextItem) editTextItem(item) }
        content.addView(view, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))

        // resume banner
        val ban = LinearLayout(act)
        ban.orientation = LinearLayout.HORIZONTAL
        ban.gravity = Gravity.CENTER_VERTICAL
        ban.layoutDirection = ui.dir()
        ban.background = ui.rounded(Color.argb(235, 40, 30, 8), 14, C.gold)
        ban.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
        ban.visibility = View.GONE
        banner = ban
        content.addView(ban, FrameLayout.LayoutParams(ui.MATCH, ui.WRAP, Gravity.TOP).also { it.setMargins(ui.dp(10), ui.dp(8), ui.dp(10), 0) })

        // text-selection bar
        val sb = LinearLayout(act)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.gravity = Gravity.CENTER_VERTICAL
        sb.layoutDirection = ui.dir()
        sb.background = ui.rounded(Color.argb(240, 13, 25, 21), 14, C.accent)
        sb.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        sb.visibility = View.GONE
        sb.addView(ui.primaryBtn(ui.t("نسخ", "Copy")) {
            val s = view.selectedText()
            if (s.isNotBlank()) {
                act.dialogs.copyToClipboard(s)
                ui.toast(ui.t("تم نسخ النص", "Text copied"))
            }
            view.clearTextSelection()
        }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, ui.dp(6), 0))
        sb.addView(ui.ghostBtn(ui.t("تحديد الكل", "Select all")) { view.selectAllText() }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, ui.dp(6), 0))
        sb.addView(ui.icon("close", C.white, 40) { view.clearTextSelection() })
        selBar = sb
        content.addView(sb, FrameLayout.LayoutParams(ui.WRAP, ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.setMargins(0, ui.dp(10), 0, 0) })

        // side tools
        val holder = FrameLayout(act)
        sideHolder = holder
        content.addView(holder, FrameLayout.LayoutParams(ui.WRAP, ui.WRAP, Gravity.END or Gravity.CENTER_VERTICAL).also {
            it.setMargins(ui.dp(4), ui.dp(8), ui.dp(6), ui.dp(8))
        })
        // keep the bar on the right as in the design, even in English
        (holder.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL

        // bottom stack
        val stack = LinearLayout(act)
        stack.orientation = LinearLayout.VERTICAL
        stack.gravity = Gravity.CENTER_HORIZONTAL
        stack.layoutDirection = ui.dir()

        val nav = LinearLayout(act)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.layoutDirection = View.LAYOUT_DIRECTION_LTR
        nav.background = ui.rounded(Color.argb(240, 13, 25, 21), 22, C.accent)
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
        ep.background = ui.rounded(Color.argb(240, 13, 25, 21), 16, C.accent)
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

        val eb = LinearLayout(act)
        eb.orientation = LinearLayout.HORIZONTAL
        eb.gravity = Gravity.CENTER
        eb.layoutDirection = View.LAYOUT_DIRECTION_LTR
        eb.background = ui.rounded(Color.argb(240, 13, 25, 21), 18, C.line)
        eb.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2))
        undoBtn = ui.icon("undo", C.white, 44) { if (edit.undo()) { view.deselect(false); view.itemsChanged() } }
        redoBtn = ui.icon("redo", C.white, 44) { if (edit.redo()) { view.deselect(false); view.itemsChanged() } }
        delBtn = ui.icon("trash", C.red, 44) { view.selectedItem()?.let { askDelete(it) } }
        editSelBtn = ui.icon("edit", C.gold, 44) { (view.selectedItem() as? TextItem)?.let { editTextItem(it) } }
        eb.addView(undoBtn)
        eb.addView(redoBtn)
        eb.addView(editSelBtn)
        eb.addView(delBtn)
        eb.visibility = if (editMode) View.VISIBLE else View.GONE
        editBar = eb
        stack.addView(eb, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(6)))

        val pill = ui.txt("", 14f, C.white, true, true)
        pill.background = ui.rounded(Color.argb(210, 13, 25, 21), 16, C.line)
        pill.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
        pill.setOnClickListener { showGoto() }
        pill.visibility = if (st.showPageNum) View.VISIBLE else View.GONE
        pagePill = pill
        stack.addView(pill, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(8)))
        content.addView(stack, FrameLayout.LayoutParams(ui.WRAP, ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

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
            top.addView(ui.icon("gear", C.gold, 46) { act.showSettings() })
        } else {
            top.addView(ui.icon("close", C.white, 46) { setEditMode(false) })
            top.addView(ui.txt(ui.t("وضع التعديل", "Edit mode"), 18f, C.white, true, true), ui.lp(0, ui.WRAP, 1f))
            top.addView(ui.btn(ui.t("حفظ", "Save"), C.accent, Color.BLACK) { onSave() }.also { it.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) })
            titlePage = null
        }
        updateLabels()
    }

    private fun sideBtn(kind: String, key: String, active: Boolean, tint: Int, onClick: () -> Unit): IconView {
        val v = ui.icon(kind, if (active) C.accent else tint, 44) { onClick() }
        if (active) v.background = ui.rounded(C.accentDark(), 12, C.accent)
        v.setOnLongClickListener { ui.toast(label(key)); true }
        return v
    }

    private fun fillSidebar() {
        val holder = sideHolder ?: return
        holder.removeAllViews()
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.VERTICAL
        bar.gravity = Gravity.CENTER_HORIZONTAL
        bar.background = ui.rounded(Color.argb(232, 10, 20, 17), 20, C.accentSoft(150))
        bar.setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4))
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        list.addView(sideBtn("edit", "edit", editMode, C.gold) { setEditMode(!editMode) })
        list.addView(sideBtn("text", "text", false, C.gold) { toolText() })
        list.addView(sideBtn("image", "image", false, C.gold) { toolImage() })
        list.addView(sideBtn("sign", "sign", false, C.gold) { toolSign() })
        list.addView(sideBtn("drop", "wm", false, C.gold) { toolWatermark() })
        list.addView(sideBtn("eraser", "eraser", eraserOn, C.gold) { toolEraser() })
        list.addView(sideBtn("search", "search", false, C.white) { toolSearch() })
        list.addView(sideBtn("goto", "goto", false, C.white) { showGoto() })
        val hasMark = act.marks.resumeFor(entry.id) != null
        list.addView(sideBtn(if (hasMark) "bookmarkFill" else "bookmark", "mark", false, if (hasMark) C.gold else C.white) { toolMark() })
        list.addView(sideBtn("copy", "copy", false, C.white) { toolCopy() })
        val sv = ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list)
        bar.addView(sv, ui.lp(ui.WRAP, 0, 1f))
        bar.addView(ui.divider().also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(30), 1) })
        bar.addView(sideBtn(if (st.night) "moon" else "sun", "night", st.night, C.white) { toggleNight() })
        holder.addView(bar, FrameLayout.LayoutParams(ui.WRAP, ui.WRAP))
        // bound height: tall enough for all tools but never over the top bar
        holder.post {
            val parentH = (holder.parent as? View)?.height ?: 0
            if (parentH > 0) {
                val lp = holder.layoutParams
                val maxH = parentH - ui.dp(16)
                holder.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                if (holder.measuredHeight > maxH && lp.height != maxH) {
                    lp.height = maxH
                    holder.layoutParams = lp
                }
            }
        }
    }

    private fun refreshEditBar() {
        val sel = pv?.selectedItem()
        undoBtn?.alpha = if (edit.canUndo()) 1f else 0.35f
        redoBtn?.alpha = if (edit.canRedo()) 1f else 0.35f
        delBtn?.alpha = if (sel != null) 1f else 0.35f
        editSelBtn?.alpha = if (sel is TextItem) 1f else 0.35f
    }

    private fun updateLabels() {
        val s = "${currentPage + 1} / ${doc.pageCount}"
        pagePill?.text = s
        titlePage?.text = ui.t("الصفحة ", "Page ") + s
    }

    // ───────────────────────── visibility ─────────────────────────

    private fun keepVisible(): Boolean = editMode || eraserOn || (pv?.hasSelection() == true)

    private fun setToolsVisible(v: Boolean) {
        toolsVisible = v || keepVisible()
        sideHolder?.visibility = if (toolsVisible) View.VISIBLE else View.GONE
        if (toolsVisible) scheduleHide()
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
            ui.toast(ui.t("تم حفظ علامة القراءة في الصفحة ${currentPage + 1}", "Reading mark saved on page ${currentPage + 1}"))
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

    // ───────────────────────── tools ─────────────────────────

    fun setEditMode(on: Boolean) {
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
        st.night = !st.night
        pv?.nightMode = st.night
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
        if (edit.isEmpty()) {
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
