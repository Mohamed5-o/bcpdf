package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max

class Screens2(private val act: MainActivity) {
    private val ui get() = act.ui
    private val st get() = act.st

    fun home(): View = HomeView(act, ui) { act.showLibrary() }

    // ───────────────────────── save screen ─────────────────────────

    fun save(reader: ReaderScreen): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("حفظ الملف", "Save file"), { act.goBack() }))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16))

        val base = reader.entry.name.removeSuffix(".pdf").removeSuffix(".PDF")
        val nameField = ui.field(ui.t("اسم الملف", "File name"), "$base - edited")
        var overwrite = st.saveMode == "overwrite"

        val card = ui.card(16)
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        card.addView(ui.txt(ui.t("طريقة الحفظ", "Save method"), 15f, C.gold, true, true))

        val optCopy = LinearLayout(act)
        val optOver = LinearLayout(act)
        val descCopy = ui.txt(ui.t("حفظ نسخة جديدة (الأفضل والأكثر أمانًا) — الأصل لا يتغير", "Save as a new copy (safest) — original stays untouched"), 14f, C.white, false, true)
        val descOver = ui.txt(ui.t("تعديل الملف الأصلي — يُنشأ نسخة احتياطية أولًا وقد لا يدعمه مصدر الملف", "Overwrite original — a backup is made first; the file's source may refuse it"), 14f, C.white, false, true)
        val boxes = listOf(optCopy, optOver)
        fun paint() {
            for ((i, b) in boxes.withIndex()) {
                val on = (i == 1) == overwrite
                b.background = ui.rounded(if (on) C.accentDark() else C.panel2, 12, if (on) C.accent else C.line)
            }
            nameField.visibility = if (overwrite) View.GONE else View.VISIBLE
        }
        for ((i, pair) in listOf(optCopy to descCopy, optOver to descOver).withIndex()) {
            pair.first.orientation = LinearLayout.VERTICAL
            pair.first.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            pair.first.addView(pair.second)
            pair.first.setOnClickListener { overwrite = i == 1; paint() }
            card.addView(pair.first, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
        }
        card.addView(nameField, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(12), 0, 0))
        val dest = if (st.defSaveFolder.isEmpty()) ui.t("سيُطلب منك اختيار مكان الحفظ", "You will be asked where to save")
        else ui.t("المجلد الافتراضي: ", "Default folder: ") + (android.net.Uri.parse(st.defSaveFolder).lastPathSegment ?: "")
        card.addView(ui.txt(dest, 12f, C.muted, false, true), ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
        paint()
        col.addView(card, ui.lp(ui.MATCH, ui.WRAP))

        val n = reader.edit.items.size
        col.addView(ui.txt(ui.t("عدد العناصر المضافة: $n", "Items to apply: $n"), 13f, C.muted, false, true), ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(10), 0, 0))
        col.addView(ui.primaryBtn(ui.t("حفظ", "Save")) {
            val nm = nameField.text.toString().trim().ifEmpty { "$base - edited" }
            if (overwrite) {
                ui.confirm(
                    ui.t("تعديل الملف الأصلي", "Overwrite original"),
                    ui.t("سيتم استبدال الملف الأصلي بعد إنشاء نسخة احتياطية والتحقق من سلامة الناتج.", "The original will be replaced after a backup is made and the result is verified."),
                    ui.t("متابعة", "Continue"), false
                ) { act.performSave(reader, nm, true) }
            } else act.performSave(reader, nm, false)
        }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(14), 0, 0))
        col.addView(ui.ghostBtn(ui.t("رجوع", "Back")) { act.goBack() }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))

        val sv = android.widget.ScrollView(act)
        sv.addView(col)
        r.addView(sv, ui.lp(ui.MATCH, 0, 1f))
        return r
    }

    /** Progress overlay shown while saving. */
    fun busy(message: String): View {
        val r = ui.screenRoot()
        r.gravity = android.view.Gravity.CENTER
        val pb = ProgressBar(act)
        r.addView(pb, ui.lp(ui.dp(56), ui.dp(56)))
        val t = ui.txt(message, 15f, C.white, false, true)
        t.id = View.generateViewId()
        r.addView(t, ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, ui.dp(14), 0, 0))
        r.tag = t
        return r
    }

    // ───────────────────────── search screen ─────────────────────────

    private var searchThread: Thread? = null
    @Volatile private var searchToken = 0

    fun cancelSearch() { searchToken++ }

    fun search(reader: ReaderScreen): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("البحث في الملف", "Search in file"), { cancelSearch(); act.goBack() }))

        val hits = ArrayList<SearchHit>()
        var query = reader.lastQuery
        val field = ui.field(ui.t("اكتب كلمة أو جملة", "Type a word or phrase"), query)
        field.setSingleLine(true)
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        val status = ui.txt("", 13f, C.muted, false, true)
        val prog = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        prog.visibility = View.GONE

        fun chip(label: String, on: Boolean, set: (Boolean) -> Unit): TextView {
            var v = on
            val t = ui.txt(label, 13f, C.white, true, true)
            fun paint() {
                t.background = ui.rounded(if (v) C.accentDark() else C.panel2, 18, if (v) C.accent else C.line)
                t.setTextColor(if (v) C.accent else C.white)
            }
            t.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            t.setOnClickListener { v = !v; set(v); paint() }
            paint()
            return t
        }

        val top = LinearLayout(act)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(6))
        top.addView(field, ui.lp(ui.MATCH, ui.WRAP))
        val chips = LinearLayout(act)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.layoutDirection = ui.dir()
        chips.addView(chip(ui.t("الكلمة كاملة", "Whole word"), st.searchWhole) { st.searchWhole = it })
        chips.addView(chip(ui.t("تجاهل حالة الأحرف", "Ignore case"), st.searchIgnoreCase) { st.searchIgnoreCase = it }, ui.lpm(ui.WRAP, ui.WRAP, 0f, ui.dp(8), 0, ui.dp(8), 0))
        top.addView(chips, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(8), 0, 0))
        top.addView(prog, ui.lpm(ui.MATCH, ui.dp(6), 0f, 0, ui.dp(8), 0, 0))
        top.addView(status, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(4), 0, 0))
        r.addView(top, ui.lp(ui.MATCH, ui.WRAP))

        val lv = ListView(act)
        lv.divider = null
        lv.setBackgroundColor(0)
        lv.clipToPadding = false
        lv.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12))
        val recentBox = LinearLayout(act)
        recentBox.orientation = LinearLayout.VERTICAL
        recentBox.setPadding(ui.dp(12), 0, ui.dp(12), 0)

        val adapter = object : BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(p: Int) = hits[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val h = hits[p]
                val row = LinearLayout(act)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                row.background = ui.rounded(C.panel, 12, C.line)
                row.layoutDirection = ui.dir()
                row.addView(ui.txt(ui.t("صفحة ${h.page + 1}", "Page ${h.page + 1}"), 12f, C.gold, true, true))
                val s = android.text.SpannableString(h.snippet)
                val a = h.snippetStart.coerceIn(0, s.length)
                val b = (a + h.len).coerceIn(a, s.length)
                s.setSpan(android.text.style.BackgroundColorSpan(C.accentSoft(110)), a, b, 0)
                s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, b, 0)
                val tv = ui.txt("", 14f, C.white, false, true)
                tv.text = s
                row.addView(tv, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(4), 0, 0))
                val wrap = LinearLayout(act)
                wrap.setPadding(0, ui.dp(3), 0, ui.dp(3))
                wrap.addView(row, ui.lp(ui.MATCH, ui.WRAP))
                return wrap
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            cancelSearch()
            reader.setSearch(query, ArrayList(hits), pos)
            act.showReader()
        }

        fun renderRecent() {
            recentBox.removeAllViews()
            val list = st.recentSearches.split("\n").filter { it.isNotBlank() }
            if (list.isEmpty() || hits.isNotEmpty()) return
            recentBox.addView(ui.txt(ui.t("عمليات البحث الأخيرة", "Recent searches"), 13f, C.muted, true, true))
            for (q in list) {
                val t = ui.txt("↺  $q", 15f, C.white, false, true)
                t.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                t.setOnClickListener { field.setText(q); field.setSelection(q.length); runSearch(reader, q, hits, adapter, status, prog) { renderRecent() } }
                recentBox.addView(t, ui.lp(ui.MATCH, ui.WRAP))
            }
        }
        field.setOnEditorActionListener { _, _, _ ->
            val q = field.text.toString().trim()
            if (q.isNotEmpty()) {
                query = q
                act.hideKeyboard(field)
                runSearch(reader, q, hits, adapter, status, prog) { renderRecent() }
            }
            true
        }
        r.addView(recentBox, ui.lp(ui.MATCH, ui.WRAP))
        r.addView(lv, ui.lp(ui.MATCH, 0, 1f))
        renderRecent()
        field.requestFocus()
        field.postDelayed({ act.showKeyboard(field) }, 250)
        return r
    }

    private fun runSearch(
        reader: ReaderScreen, q: String, hits: ArrayList<SearchHit>, adapter: BaseAdapter,
        status: TextView, prog: ProgressBar, done: () -> Unit
    ) {
        cancelSearch()
        val token = searchToken
        hits.clear()
        adapter.notifyDataSetChanged()
        st.addRecentSearch(q)
        prog.visibility = View.VISIBLE
        prog.max = reader.doc.pageCount
        prog.progress = 0
        status.text = ui.t("جارٍ البحث…", "Searching…")
        done()
        val whole = st.searchWhole
        val ic = st.searchIgnoreCase
        val total = reader.doc.pageCount
        searchThread = Thread {
            val n = try {
                reader.textEngine.search(q, whole, ic, total, { token != searchToken },
                    { d, t -> act.runOnUiThread { if (token == searchToken) { prog.max = t; prog.progress = d } } },
                    { batch ->
                        act.runOnUiThread {
                            if (token == searchToken) {
                                hits.addAll(batch)
                                adapter.notifyDataSetChanged()
                                status.text = ui.t("${hits.size} نتيجة حتى الآن…", "${hits.size} results so far…")
                            }
                        }
                    })
            } catch (e: Throwable) { -1 }
            act.runOnUiThread {
                if (token != searchToken) return@runOnUiThread
                prog.visibility = View.GONE
                status.text = when {
                    n < 0 -> ui.t("تعذر البحث في هذا الملف", "Could not search this file")
                    n == 0 && reader.textEngine.textPagesSeen == 0 ->
                        ui.t("لا يوجد نص قابل للبحث في هذا الملف (قد يكون ممسوحًا ضوئيًا). التعرف على الحروف غير متاح.", "No searchable text in this file (it may be scanned). OCR is not available.")
                    n == 0 -> ui.t("لا توجد نتائج", "No results")
                    n >= 800 -> ui.t("أول 800 نتيجة", "First 800 results")
                    else -> ui.t("$n نتيجة", "$n results")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
