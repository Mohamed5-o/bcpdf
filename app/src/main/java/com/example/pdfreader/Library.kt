package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ThumbLoader(private val ctx: Context) {
    private val cache = LruCache<String, Bitmap>(48)
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "thumbs").also { it.mkdirs() }
    var onPages: ((String, Int) -> Unit)? = null

    fun load(e: LibEntry, target: ImageView) {
        target.tag = e.id
        val hit = cache.get(e.id)
        if (hit != null) {
            target.setImageBitmap(hit)
            return
        }
        target.setImageDrawable(null)
        exec.execute {
            val f = File(dir, e.id + ".png")
            var bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            var pages = 0
            if (bmp == null || e.pages == 0) {
                try {
                    val d = PdfDoc(ctx, Uri.parse(e.uri), e.id)
                    d.open()
                    pages = d.pageCount
                    if (bmp == null) {
                        bmp = d.renderFull(0, 220, 440)
                        if (bmp != null) FileOutputStream(f).use { out -> bmp!!.compress(Bitmap.CompressFormat.PNG, 90, out) }
                    }
                    d.close()
                } catch (_: Throwable) {
                }
            }
            val result = bmp
            main.post {
                if (result != null) cache.put(e.id, result)
                if (target.tag == e.id && result != null) target.setImageBitmap(result)
                if (pages > 0) onPages?.invoke(e.id, pages)
            }
        }
    }

    fun forget(id: String) {
        cache.remove(id)
        File(dir, "$id.png").delete()
    }
}

fun fmtSize(b: Long): String {
    if (b <= 0) return "—"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun fmtDate(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(ms))

class LibraryScreen(private val act: MainActivity) {
    private val ui get() = act.ui
    private val st get() = act.st

    private var query = ""
    private var selecting = false
    private val selected = HashSet<String>()
    private var shown: List<LibEntry> = emptyList()
    private var adapter: Adapter? = null
    private var emptyView: View? = null
    private var listView: ListView? = null
    private var selRow: LinearLayout? = null

    fun refresh() {
        val all = act.store.all()
        val q = query.trim().lowercase()
        var list = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        list = when (st.sortOrder) {
            "name" -> list.sortedBy { it.name.lowercase() }
            "size" -> list.sortedByDescending { it.size }
            else -> list.sortedByDescending { it.lastOpened }
        }
        shown = list
        selected.retainAll(all.map { it.id }.toSet())
        adapter?.notifyDataSetChanged()
        emptyView?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        listView?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        updateSelectionRow()
    }

    fun build(): View {
        val root = ui.screenRoot()

        // header: settings | title | sort  (physical left / right as in the design)
        val header = LinearLayout(act)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.layoutDirection = View.LAYOUT_DIRECTION_LTR
        header.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(2))
        header.addView(ui.icon("gear", C.white, 48) { act.showSettings() })
        val titleBox = LinearLayout(act)
        titleBox.orientation = LinearLayout.HORIZONTAL
        titleBox.gravity = Gravity.CENTER
        val logo = ImageView(act)
        logo.setImageResource(R.drawable.app_logo)
        titleBox.addView(logo, ui.lpm(ui.dp(30), ui.dp(30), 0f, 0, 0, ui.dp(8), 0))
        titleBox.addView(ui.txt(ui.t("مكتبتي", "My Library"), 22f, C.white, true, true))
        header.addView(titleBox, ui.lp(0, ui.WRAP, 1f))
        header.addView(ui.icon("sort", C.gold, 48) { showSortMenu() })
        root.addView(header)

        // search + view toggle
        val searchRow = LinearLayout(act)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        val box = LinearLayout(act)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = ui.rounded(C.panel, 14, C.accent)
        box.setPadding(ui.dp(10), 0, ui.dp(6), 0)
        box.addView(ui.icon("search", C.accent, 36))
        val input = EditText(act)
        input.hint = ui.t("ابحث عن ملف...", "Search files...")
        input.setHintTextColor(C.muted)
        input.setTextColor(C.white)
        input.textSize = ui.sp(15f)
        input.background = null
        input.setSingleLine(true)
        input.setText(query)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString() ?: ""
                refresh()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        box.addView(input, ui.lp(0, ui.dp(44), 1f))
        searchRow.addView(box, ui.lp(0, ui.WRAP, 1f))
        val viewBtn = ui.icon(if (st.libraryView == "grid") "list" else "grid", C.accent, 44) {
            st.libraryView = if (st.libraryView == "grid") "list" else "grid"
            act.showLibrary()
        }
        searchRow.addView(viewBtn, ui.lpm(ui.dp(44), ui.dp(44), 0f, ui.dp(6), 0, 0, 0))
        root.addView(searchRow)

        // selection actions
        val sr = LinearLayout(act)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.layoutDirection = ui.dir()
        sr.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        sr.background = null
        selRow = sr
        root.addView(sr, ui.lp(ui.MATCH, ui.WRAP))

        // list / empty
        val frame = FrameLayout(act)
        val lv = ListView(act)
        lv.divider = null
        lv.setBackgroundColor(Color.TRANSPARENT)
        lv.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        lv.clipToPadding = false
        listView = lv
        val ad = Adapter()
        adapter = ad
        lv.adapter = ad
        frame.addView(lv, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
        val empty = LinearLayout(act)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.addView(ui.icon("library", C.muted, 72))
        empty.addView(ui.txt(ui.t("المكتبة فارغة", "Your library is empty"), 17f, C.white, true, true), ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(8), 0, ui.dp(4)))
        empty.addView(ui.txt(ui.t("اسمح للتطبيق بالبحث عن ملفاتك أو استورد ملفًا لتبدأ", "Let the app find your PDFs, or import one to start"), 13f, C.muted, false, true))
        empty.addView(ui.primaryBtn(ui.t("البحث عن ملفات PDF في الجهاز", "Find PDFs on this device")) { act.scanDevice(true) }, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(16), 0, 0))
        emptyView = empty
        frame.addView(empty, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
        root.addView(frame, ui.lp(ui.MATCH, 0, 1f))

        // import + nav
        root.addView(
            ui.primaryBtn("＋  " + ui.t("إضافة ملفات PDF", "Add PDF files")) { showImportMenu() },
            ui.lpm(ui.MATCH, ui.WRAP, 0f, ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(6))
        )
        root.addView(bottomNav())
        refresh()
        return root
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(act)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.layoutDirection = ui.dir()
        nav.background = ui.rounded(C.panel, 16, C.line)
        nav.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        fun item(kind: String, label: String, active: Boolean, onClick: () -> Unit): View {
            val c = LinearLayout(act)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.addView(ui.icon(kind, if (active) C.accent else C.white, 36))
            c.addView(ui.txt(label, 11f, if (active) C.accent else C.white, false, true))
            c.setPadding(0, ui.dp(2), 0, ui.dp(4))
            if (active) c.background = ui.rounded(C.accentDark(), 12, C.accent)
            c.setOnClickListener { onClick() }
            return c
        }
        nav.addView(item("library", ui.t("مكتبتي", "Library"), true) {}, ui.lpm(0, ui.WRAP, 1f, ui.dp(2), 0, ui.dp(2), 0))
        nav.addView(item("bookmark", ui.t("العلامات", "Marks"), false) { act.showBookmarks(null) }, ui.lpm(0, ui.WRAP, 1f, ui.dp(2), 0, ui.dp(2), 0))
        nav.addView(item("gear", ui.t("الإعدادات", "Settings"), false) { act.showSettings() }, ui.lpm(0, ui.WRAP, 1f, ui.dp(2), 0, ui.dp(2), 0))
        val w = LinearLayout(act)
        w.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(6))
        w.addView(nav, ui.lp(ui.MATCH, ui.WRAP))
        return w
    }

    private fun showImportMenu() {
        ui.menu(
            listOf(
                ui.t("فحص الجهاز عن ملفات PDF", "Scan the device for PDFs") to { act.scanDevice(true) },
                ui.t("استيراد ملفات (نسخ داخل التطبيق)", "Import files (copy into the app)") to { act.pickImport() },
                ui.t("فتح ملف دون نسخه", "Open a file without copying") to { act.pickLinked() },
                ui.t("إضافة مجلد (البحث عن ملفات PDF)", "Add a folder (scan for PDFs)") to { act.pickFolder() }
            ),
            ui.t("إضافة ملفات", "Add files")
        )
    }

    private fun showSortMenu() {
        fun mark(k: String, s: String) = (if (st.sortOrder == k) "✓  " else "     ") + s
        ui.menu(
            listOf(
                mark("opened", ui.t("آخر فتح", "Last opened")) to { st.sortOrder = "opened"; refresh() },
                mark("name", ui.t("الاسم", "Name")) to { st.sortOrder = "name"; refresh() },
                mark("size", ui.t("الحجم", "Size")) to { st.sortOrder = "size"; refresh() }
            ),
            ui.t("ترتيب الملفات", "Sort files")
        )
    }

    // ───── selection ─────

    private fun selectedEntries(): List<LibEntry> = shown.filter { selected.contains(it.id) }

    private fun updateSelectionRow() {
        val sr = selRow ?: return
        sr.removeAllViews()
        fun act1(kind: String, label: String, tint: Int, onClick: () -> Unit) {
            val c = LinearLayout(act)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.addView(ui.icon(kind, tint, 34))
            c.addView(ui.txt(label, 10.5f, tint, false, true))
            c.setOnClickListener { onClick() }
            sr.addView(c, ui.lp(0, ui.WRAP, 1f))
        }
        act1("select", if (selecting) ui.t("إنهاء", "Done") else ui.t("تحديد", "Select"), if (selecting) C.accent else C.white) {
            selecting = !selecting
            if (!selecting) selected.clear()
            adapter?.notifyDataSetChanged()
            updateSelectionRow()
        }
        if (!selecting) return
        act1("selectAll", ui.t("تحديد الكل", "All"), C.white) {
            if (selected.size == shown.size) selected.clear() else selected.addAll(shown.map { it.id })
            adapter?.notifyDataSetChanged()
            updateSelectionRow()
        }
        act1("copy", ui.t("نسخ", "Copy"), C.white) {
            val l = selectedEntries()
            if (l.isEmpty()) ui.toast(ui.t("حدد ملفات أولًا", "Select files first")) else {
                l.forEach { act.ops.copy(it) }
                selecting = false; selected.clear()
            }
        }
        act1("rename", ui.t("إعادة التسمية", "Rename"), C.white) {
            val l = selectedEntries()
            if (l.size != 1) ui.toast(ui.t("حدد ملفًا واحدًا", "Select exactly one file")) else askRename(l[0])
        }
        act1("share", ui.t("مشاركة", "Share"), C.white) {
            val l = selectedEntries()
            if (l.isEmpty()) ui.toast(ui.t("حدد ملفات أولًا", "Select files first")) else act.ops.share(l)
        }
        act1("trash", ui.t("حذف", "Delete"), C.red) {
            val l = selectedEntries()
            if (l.isEmpty()) ui.toast(ui.t("حدد ملفات أولًا", "Select files first")) else askDelete(l)
        }
    }

    private fun askRename(e: LibEntry) {
        ui.input(ui.t("إعادة التسمية", "Rename"), ui.t("اسم الملف", "File name"), e.name, ui.t("حفظ", "Save")) {
            act.ops.rename(e, it)
        }
    }

    private fun askDelete(list: List<LibEntry>) {
        val internalCount = list.count { it.internal }
        val msg = if (internalCount == list.size) {
            ui.t("سيتم حذف ${list.size} ملف من التطبيق نهائيًا.", "${list.size} file(s) will be deleted from the app permanently.")
        } else {
            ui.t(
                "الملفات المنسوخة داخل التطبيق ($internalCount) ستُحذف نهائيًا. أما الملفات الأخرى فستُزال من المكتبة فقط ولن يُحذف أصلها من الجهاز.",
                "Copies stored inside the app ($internalCount) will be deleted. Other files are only removed from the library; the originals stay on your device."
            )
        }
        ui.confirm(ui.t("تأكيد الحذف", "Confirm delete"), msg, ui.t("حذف", "Delete"), true) {
            act.ops.delete(list)
            selecting = false
            selected.clear()
        }
    }

    private fun showInfo(e: LibEntry) {
        ui.dialog { card, dlg ->
            card.addView(ui.txt(e.name, 17f, C.gold, true, true))
            val rows = listOf(
                ui.t("الحجم", "Size") to fmtSize(e.size),
                ui.t("الصفحات", "Pages") to (if (e.pages > 0) e.pages.toString() else "—"),
                ui.t("آخر فتح", "Last opened") to fmtDate(e.lastOpened),
                ui.t("آخر صفحة", "Last page") to (e.lastPage + 1).toString(),
                ui.t("المكان", "Location") to (if (e.internal) ui.t("داخل التطبيق", "Inside the app") else ui.t("ملف على الجهاز (لم يُنسخ)", "On device (not copied)"))
            )
            for ((k, v) in rows) {
                val r = LinearLayout(act)
                r.orientation = LinearLayout.HORIZONTAL
                r.setPadding(0, ui.dp(8), 0, ui.dp(8))
                r.addView(ui.txt(k, 14f, C.muted), ui.lp(0, ui.WRAP, 1f))
                r.addView(ui.txt(v, 14f, C.white, true))
                card.addView(r)
                card.addView(ui.divider())
            }
            card.addView(ui.primaryBtn(ui.t("إغلاق", "Close")) { dlg.dismiss() }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(12), 0, 0))
        }
    }

    private fun entryMenu(e: LibEntry) {
        ui.menu(
            listOf(
                ui.t("فتح", "Open") to { act.openEntry(e, null) },
                ui.t("إعادة التسمية", "Rename") to { askRename(e) },
                ui.t("نسخ", "Copy") to { act.ops.copy(e) },
                ui.t("مشاركة", "Share") to { act.ops.share(listOf(e)) },
                ui.t("معلومات الملف", "File info") to { showInfo(e) },
                (if (e.internal) ui.t("حذف", "Delete") else ui.t("إزالة من المكتبة", "Remove from library")) to { askDelete(listOf(e)) }
            ),
            e.name
        )
    }

    // ───── rows ─────

    private fun lastReadLine(e: LibEntry): String =
        if (e.lastOpened > 0) ui.t("آخر قراءة: صفحة ${e.lastPage + 1}", "Last read: page ${e.lastPage + 1}") else ui.t("لم تُفتح بعد", "Not opened yet")

    private fun metaLine(e: LibEntry): String {
        val pages = if (e.pages > 0) ui.t("${e.pages} صفحة", "${e.pages} pages") else ""
        return listOf(fmtSize(e.size), pages).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun thumb(e: LibEntry, w: Int, h: Int): View {
        val box = FrameLayout(act)
        box.background = ui.rounded(Color.WHITE, 8)
        val iv = ImageView(act)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        act.thumbs.load(e, iv)
        box.addView(iv, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
        if (selecting) {
            val on = selected.contains(e.id)
            val chk = ui.icon("check", if (on) Color.BLACK else Color.TRANSPARENT, 26)
            chk.background = ui.rounded(if (on) C.accent else Color.argb(150, 0, 0, 0), 13, C.white)
            box.addView(chk, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.TOP or Gravity.START))
        }
        box.layoutParams = LinearLayout.LayoutParams(w, h)
        return box
    }

    private fun tapEntry(e: LibEntry) {
        if (selecting) {
            if (!selected.add(e.id)) selected.remove(e.id)
            adapter?.notifyDataSetChanged()
        } else act.openEntry(e, null)
    }

    private fun longEntry(e: LibEntry) {
        if (!selecting) {
            selecting = true
            selected.add(e.id)
            adapter?.notifyDataSetChanged()
            updateSelectionRow()
        } else entryMenu(e)
    }

    private fun listRow(e: LibEntry): View {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.layoutDirection = ui.dir()
        card.background = ui.rounded(if (selected.contains(e.id)) C.accentDark() else C.panel, 16, if (selected.contains(e.id)) C.accent else C.line)
        card.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        card.addView(thumb(e, ui.dp(64), ui.dp(86)), ui.lpm(ui.dp(64), ui.dp(86), 0f, 0, 0, ui.dp(12), 0))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val name = ui.txt(e.name, 15f, C.white, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(ui.txt(metaLine(e), 12f, C.muted))
        col.addView(ui.txt(fmtDate(e.lastOpened), 11f, C.muted))
        val bottom = LinearLayout(act)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(ui.txt(lastReadLine(e), 12f, C.accent, true), ui.lp(0, ui.WRAP, 1f))
        if (e.lastOpened > 0 && !selecting) {
            val cont = ui.btn(ui.t("متابعة القراءة", "Continue"), C.accentDark(), C.accent, C.accent) { act.openEntry(e, e.lastPage) }
            cont.textSize = ui.sp(12f)
            cont.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
            bottom.addView(cont)
        }
        col.addView(bottom, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(4), 0, 0))
        card.addView(col, ui.lp(0, ui.WRAP, 1f))

        val side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        side.gravity = Gravity.CENTER
        val hasMark = act.marks.resumeFor(e.id) != null
        side.addView(ui.icon(if (hasMark) "bookmarkFill" else "bookmark", if (hasMark) C.gold else C.muted, 36) {
            val m = act.marks.resumeFor(e.id)
            if (m != null) act.openEntry(e, m.page) else ui.toast(ui.t("لا توجد علامة استكمال لهذا الملف", "No reading mark for this file"))
        })
        side.addView(ui.icon("more", C.white, 36) { entryMenu(e) })
        card.addView(side)

        card.setOnClickListener { tapEntry(e) }
        card.setOnLongClickListener { longEntry(e); true }
        return card
    }

    private fun gridCard(e: LibEntry, cardW: Int): View {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.layoutDirection = ui.dir()
        card.background = ui.rounded(if (selected.contains(e.id)) C.accentDark() else C.panel, 16, if (selected.contains(e.id)) C.accent else C.line)
        card.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        val tw = cardW - ui.dp(16)
        card.addView(thumb(e, tw, (tw * 1.35f).toInt()))
        val name = ui.txt(e.name, 13f, C.white, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        card.addView(name, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(6), 0, 0))
        card.addView(ui.txt(metaLine(e), 11f, C.muted))
        card.addView(ui.txt(lastReadLine(e), 11f, C.accent))
        card.setOnClickListener { tapEntry(e) }
        card.setOnLongClickListener { longEntry(e); true }
        return card
    }

    private inner class Adapter : BaseAdapter() {
        private fun cols() = if (st.libraryView == "grid") 2 else 1
        override fun getCount(): Int = (shown.size + cols() - 1) / cols()
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = cols()
            if (c == 1) {
                val v = listRow(shown[position])
                v.layoutParams = AbsListViewParams(ui.MATCH, ui.WRAP)
                val wrap = FrameLayout(act)
                wrap.setPadding(0, ui.dp(5), 0, ui.dp(5))
                wrap.addView(v, FrameLayout.LayoutParams(ui.MATCH, ui.WRAP))
                return wrap
            }
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutDirection = ui.dir()
            row.setPadding(0, ui.dp(5), 0, ui.dp(5))
            val cardW = (act.resources.displayMetrics.widthPixels - ui.dp(16 + 12)) / 2
            for (i in 0 until c) {
                val idx = position * c + i
                if (idx < shown.size) {
                    row.addView(gridCard(shown[idx], cardW), ui.lpm(0, ui.WRAP, 1f, ui.dp(3), 0, ui.dp(3), 0))
                } else {
                    row.addView(View(act), ui.lp(0, 1, 1f))
                }
            }
            return row
        }

        @Suppress("FunctionName")
        private fun AbsListViewParams(w: Int, h: Int) = android.widget.AbsListView.LayoutParams(w, h)
    }
}
