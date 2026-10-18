package com.example.pdfreader

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Page-level operations built on PDFBox: extract / reorder / delete / merge. Output is always a new file. */
object PdfTools {
    /** [sources]: file + zero-based page indices in output order (null = all pages). Returns the page count of the result. */
    fun build(sources: List<Pair<File, List<Int>?>>, dst: File, onProgress: (Int, Int) -> Unit): Int {
        val opened = ArrayList<PDDocument>()
        val out = PDDocument()
        try {
            val plans = ArrayList<Pair<PDDocument, List<Int>>>()
            var total = 0
            for ((f, sel) in sources) {
                val d = PDDocument.load(f)
                opened.add(d)
                val idx = (sel ?: (0 until d.numberOfPages).toList()).filter { it in 0 until d.numberOfPages }
                plans.add(d to idx)
                total += idx.size
            }
            if (total == 0) throw IllegalStateException("empty")
            var done = 0
            for ((d, idx) in plans) {
                for (i in idx) {
                    out.importPage(d.getPage(i))
                    done++
                    onProgress(done, total)
                }
            }
            out.save(dst)
            return total
        } finally {
            try { out.close() } catch (_: Exception) {}
            for (d in opened) try { d.close() } catch (_: Exception) {}
        }
    }
}

/** UI for the page tools (opened from the reader's side rail). */
class PageTools(private val act: MainActivity, private val r: ReaderScreen) {
    private val ui get() = act.ui

    fun menu() {
        if (r.edit.dirty && !r.edit.isEmpty()) {
            ui.toast(ui.t("احفظ تعديلاتك أولًا ثم استخدم أدوات الصفحات", "Save your edits first, then use the page tools"))
            return
        }
        ui.menu(
            listOf(
                ui.t("ترتيب الصفحات وحذفها", "Reorder / delete pages") to { manager() },
                ui.t("استخراج جزء من الملف (تقسيم)", "Extract a page range (split)") to { split() },
                ui.t("دمج مع ملفات أخرى", "Merge with other files") to { merge() }
            ),
            ui.t("أدوات الملف", "File tools")
        )
    }

    private fun run(name: String, sources: List<Pair<File, List<Int>?>>) {
        var label: TextView? = null
        val dlg = ui.dialog(false) { card, _ ->
            val t = ui.txt(ui.t("جارٍ التنفيذ…", "Working…"), 15f, C.white, true, true)
            label = t
            card.addView(t)
        }
        val tmp = File(act.cacheDir, "tool_" + System.currentTimeMillis() + ".pdf")
        Thread {
            var pages = 0
            var err: String? = null
            try {
                pages = PdfTools.build(sources, tmp) { d, t -> act.runOnUiThread { label?.text = ui.t("جارٍ التنفيذ… $d / $t", "Working… $d / $t") } }
                if (!Saver.verify(tmp, pages)) throw IllegalStateException("verify")
            } catch (ex: Throwable) {
                err = ui.t("تعذر التنفيذ: ", "Failed: ") + (ex.message ?: ex.javaClass.simpleName)
            }
            act.runOnUiThread {
                dlg.dismiss()
                if (err != null) {
                    tmp.delete()
                    ui.toast(err)
                    return@runOnUiThread
                }
                val e = act.addGeneratedPdf(tmp, name, pages)
                act.refreshLibrary()
                ui.menu(
                    listOf(
                        ui.t("فتح الملف الجديد", "Open the new file") to { act.openEntry(e, 0) },
                        ui.t("البقاء هنا", "Stay here") to {}
                    ),
                    ui.t("تم إنشاء «${e.name}» ($pages صفحة) في المكتبة", "Created \"${e.name}\" ($pages pages) in the library")
                )
            }
        }.start()
    }

    private fun baseName() = r.entry.name.removeSuffix(".pdf").removeSuffix(".PDF")

    // ───────── reorder / delete ─────────
    private fun manager() {
        val n = r.doc.pageCount
        val order = ArrayList<Int>((0 until n).toList())
        val removed = HashSet<Int>()
        val rows = HashMap<Int, View>()
        ui.dialog(false, true) { card, dlg ->
            card.addView(ui.txt(ui.t("ترتيب الصفحات وحذفها", "Reorder / delete pages"), 19f, C.gold, true, true))
            card.addView(ui.txt(ui.t("استخدم الأسهم للترتيب وسلة المهملات للحذف، ثم احفظ كملف جديد (الأصل لا يتغير).", "Use the arrows to reorder and the bin to delete, then save as a new file (the original is untouched)."), 12f, C.muted, false, true),
                ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(4), 0, ui.dp(8)))
            val list = LinearLayout(act)
            list.orientation = LinearLayout.VERTICAL
            val sv = ScrollView(act)
            sv.addView(list)
            fun relayout() {
                list.removeAllViews()
                for (p in order) rows[p]?.let { list.addView(it) }
            }
            fun smallIcon(kind: String, tint: Int, click: () -> Unit): View {
                val v = IconView(act, kind, tint)
                v.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                v.setOnClickListener { click() }
                return v.also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)) }
            }
            for (p in 0 until n) {
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutDirection = ui.dir()
                row.setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(2))
                row.background = ui.rounded(C.panel2, 10, C.line)
                val lbl = ui.txt(ui.t("صفحة ${p + 1}", "Page ${p + 1}"), 15f, C.white, true)
                row.addView(lbl, ui.lp(0, ui.WRAP, 1f))
                fun paint() {
                    val gone = p in removed
                    row.alpha = if (gone) 0.4f else 1f
                    lbl.paintFlags = if (gone) lbl.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else lbl.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                row.addView(smallIcon("up", C.white) {
                    val i = order.indexOf(p)
                    if (i > 0) { order[i] = order[i - 1]; order[i - 1] = p; relayout() }
                })
                row.addView(smallIcon("down", C.white) {
                    val i = order.indexOf(p)
                    if (i < order.size - 1) { order[i] = order[i + 1]; order[i + 1] = p; relayout() }
                })
                row.addView(smallIcon("trash", C.red) { if (!removed.add(p)) removed.remove(p); paint() })
                paint()
                rows[p] = row
            }
            relayout()
            card.addView(sv, ui.lp(ui.MATCH, ui.dp(380)))
            val name = ui.field(ui.t("اسم الملف الجديد", "New file name"), baseName() + " - organized")
            card.addView(name, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(10), ui.dp(6), 0))
            row.addView(ui.primaryBtn(ui.t("حفظ كملف جديد", "Save as new")) {
                val keep = order.filter { it !in removed }
                if (keep.isEmpty()) { ui.toast(ui.t("لا يمكن حذف كل الصفحات", "You cannot delete every page")); return@primaryBtn }
                dlg.dismiss()
                run(name.text.toString().trim().ifEmpty { baseName() + " - organized" }, listOf(r.doc.localFile() to keep))
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(10), 0, 0))
            card.addView(row)
        }
    }

    // ───────── split / extract ─────────
    private fun split() {
        val n = r.doc.pageCount
        ui.dialog { card, dlg ->
            card.addView(ui.txt(ui.t("استخراج جزء من الملف", "Extract a page range"), 19f, C.gold, true, true))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.layoutDirection = ui.dir()
            val from = ui.field(ui.t("من", "From"), "${r.currentPage + 1}")
            val to = ui.field(ui.t("إلى", "To"), "${minOf(n, r.currentPage + 1)}")
            from.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            to.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            row.addView(from, ui.lpm(0, ui.WRAP, 1f, 0, 0, ui.dp(6), 0))
            row.addView(to, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), 0, 0, 0))
            card.addView(row, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            card.addView(ui.txt(ui.t("عدد صفحات الملف: $n", "Pages in this file: $n"), 12f, C.muted, false, true), ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(6), 0, 0))
            val name = ui.field(ui.t("اسم الملف الجديد", "New file name"), baseName() + " - part")
            card.addView(name, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            val b = LinearLayout(act)
            b.orientation = LinearLayout.HORIZONTAL
            b.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(12), ui.dp(6), 0))
            b.addView(ui.primaryBtn(ui.t("استخراج", "Extract")) {
                val a = (from.text.toString().toIntOrNull() ?: 0).coerceIn(1, n)
                val z = (to.text.toString().toIntOrNull() ?: 0).coerceIn(1, n)
                val lo = minOf(a, z)
                val hi = maxOf(a, z)
                dlg.dismiss()
                run(name.text.toString().trim().ifEmpty { baseName() + " - part" }, listOf(r.doc.localFile() to (lo - 1 until hi).toList()))
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(12), 0, 0))
            card.addView(b)
        }
    }

    // ───────── merge ─────────
    private fun merge() {
        val others = act.store.all().filter { it.id != r.entry.id }
        if (others.isEmpty()) {
            ui.toast(ui.t("لا توجد ملفات أخرى في المكتبة", "No other files in the library"))
            return
        }
        val picked = ArrayList<LibEntry>()
        var currentFirst = true
        ui.dialog(false, true) { card, dlg ->
            card.addView(ui.txt(ui.t("دمج مع ملفات أخرى", "Merge with other files"), 19f, C.gold, true, true))
            card.addView(ui.txt(ui.t("اختر الملفات بالترتيب الذي تريده (الرقم يدل على الترتيب).", "Pick files in the order you want (the number shows the order)."), 12f, C.muted, false, true),
                ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(4), 0, ui.dp(6)))
            val where = ui.txt("", 13f, C.accent, true, true)
            fun paintWhere() { where.text = if (currentFirst) ui.t("«${r.entry.name}» يأتي أولًا — اضغط للتبديل", "\"${r.entry.name}\" goes first — tap to switch") else ui.t("«${r.entry.name}» يأتي أخيرًا — اضغط للتبديل", "\"${r.entry.name}\" goes last — tap to switch") }
            where.setOnClickListener { currentFirst = !currentFirst; paintWhere() }
            where.setPadding(0, ui.dp(8), 0, ui.dp(8))
            paintWhere()
            card.addView(where)
            val list = LinearLayout(act)
            list.orientation = LinearLayout.VERTICAL
            val sv = ScrollView(act)
            sv.addView(list)
            val badges = ArrayList<Pair<LibEntry, TextView>>()
            fun repaint() {
                for ((e, t) in badges) {
                    val i = picked.indexOfFirst { it.id == e.id }
                    t.text = if (i >= 0) "${i + 1}" else ""
                    t.background = ui.rounded(if (i >= 0) C.accentDark() else C.panel2, 14, if (i >= 0) C.accent else C.line)
                }
            }
            for (e in others) {
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutDirection = ui.dir()
                row.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                val badge = ui.txt("", 13f, C.white, true, true)
                row.addView(badge, ui.lpm(ui.dp(28), ui.dp(28), 0f, 0, 0, ui.dp(10), 0))
                val nm = ui.txt(e.name, 14f, C.white)
                nm.maxLines = 2
                row.addView(nm, ui.lp(0, ui.WRAP, 1f))
                row.setOnClickListener {
                    val i = picked.indexOfFirst { it.id == e.id }
                    if (i >= 0) picked.removeAt(i) else picked.add(e)
                    repaint()
                }
                badges.add(e to badge)
                list.addView(row, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(2), 0, ui.dp(2)))
            }
            repaint()
            card.addView(sv, ui.lp(ui.MATCH, ui.dp(300)))
            val name = ui.field(ui.t("اسم الملف الجديد", "New file name"), baseName() + " - merged")
            card.addView(name, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
            val b = LinearLayout(act)
            b.orientation = LinearLayout.HORIZONTAL
            b.addView(ui.ghostBtn(ui.t("إلغاء", "Cancel")) { dlg.dismiss() }, ui.lpm(0, ui.WRAP, 1f, 0, ui.dp(10), ui.dp(6), 0))
            b.addView(ui.primaryBtn(ui.t("دمج", "Merge")) {
                if (picked.isEmpty()) { ui.toast(ui.t("اختر ملفًا واحدًا على الأقل", "Pick at least one file")); return@primaryBtn }
                dlg.dismiss()
                val nm = name.text.toString().trim().ifEmpty { baseName() + " - merged" }
                val chosen = ArrayList(picked)
                val first = currentFirst
                Thread {
                    val srcs = ArrayList<Pair<File, List<Int>?>>()
                    try {
                        if (first) srcs.add(r.doc.localFile() to null)
                        for (e in chosen) srcs.add(PdfDoc(act, android.net.Uri.parse(e.uri), e.id).localFile() to null)
                        if (!first) srcs.add(r.doc.localFile() to null)
                        act.runOnUiThread { run(nm, srcs) }
                    } catch (ex: Throwable) {
                        act.runOnUiThread { ui.toast(ui.t("تعذر قراءة أحد الملفات", "Could not read one of the files")) }
                    }
                }.start()
            }, ui.lpm(0, ui.WRAP, 1f, ui.dp(6), ui.dp(10), 0, 0))
            card.addView(b)
        }
    }
}
