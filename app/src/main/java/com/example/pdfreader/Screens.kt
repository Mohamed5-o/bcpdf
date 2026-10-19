package com.example.pdfreader

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

fun dirSize(f: File): Long {
    if (!f.exists()) return 0L
    if (f.isFile) return f.length()
    var n = 0L
    f.listFiles()?.forEach { n += dirSize(it) }
    return n
}

class Screens(private val act: MainActivity) {
    private val ui get() = act.ui
    private val st get() = act.st
    private var settingsScroll = 0
    private var bmTab = 0

    private fun scroller(col: LinearLayout, onScroll: ((Int) -> Unit)? = null, restore: Int = 0): ScrollView {
        col.setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(24))
        val sv = ScrollView(act)
        sv.addView(col)
        if (onScroll != null) sv.viewTreeObserver.addOnScrollChangedListener { onScroll(sv.scrollY) }
        if (restore > 0) sv.post { sv.scrollTo(0, restore) }
        return sv
    }

    // ───────────────────────── settings ─────────────────────────

    private fun toggleRow(title: String, sub: String?, value: Boolean, set: (Boolean) -> Unit): View =
        ui.settingRow(title, sub, ui.toggle(value) { set(it) })

    private fun choiceRow(title: String, valueLabel: String, options: List<Pair<String, () -> Unit>>): View =
        ui.settingRow(title, valueLabel, ui.chevron()) { ui.menu(options, title) }

    fun settings(): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("الإعدادات", "Settings"), { act.goBack() }))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val again = { act.showSettings() }

        // A. reading
        ui.section(
            col, ui.t("إعدادات القراءة", "Reading"), listOf(
                choiceRow(
                    ui.t("الوضع الافتراضي للعرض", "Default view"),
                    if (st.fitMode == "width") ui.t("ملائمة العرض", "Fit width") else ui.t("ملائمة الصفحة", "Fit page"),
                    listOf(
                        ui.t("ملائمة الصفحة (الصفحة كاملة)", "Fit page (whole page)") to { st.fitMode = "page"; again() },
                        ui.t("ملائمة العرض (تملأ العرض)", "Fit width") to { st.fitMode = "width"; again() }
                    )
                ),
                choiceRow(
                    ui.t("طريقة التصفح", "Reading layout"),
                    when (st.readMode) {
                        "c" -> ui.t("كل الصفحات تحت بعضها", "All pages stacked")
                        "v" -> ui.t("صفحة بصفحة (سحب رأسي)", "Page by page (vertical swipe)")
                        else -> ui.t("صفحة بصفحة (سحب جانبي)", "Page by page (side swipe)")
                    },
                    listOf(
                        ui.t("صفحة بصفحة — سحب جانبي (الاتجاه حسب اللغة)", "Page by page — side swipe") to { st.readMode = "h"; again() },
                        ui.t("صفحة بصفحة — سحب رأسي (لأعلى = التالية)", "Page by page — vertical swipe") to { st.readMode = "v"; again() },
                        ui.t("كل الصفحات تحت بعضها (تمرير متصل)", "All pages stacked (continuous scroll)") to { st.readMode = "c"; again() }
                    )
                ),
                choiceRow(
                    ui.t("التكبير الثابت", "Fixed zoom"),
                    if (st.fixedZoom == 0) ui.t("تلقائي", "Auto") else "${st.fixedZoom}%",
                    listOf(0, 125, 150, 175, 200, 250, 300).map { z ->
                        (if (z == 0) ui.t("تلقائي (ملائمة)", "Auto (fit)") else "$z%") to { st.fixedZoom = z; again() }
                    }
                ),
                choiceRow(
                    ui.t("الوضع المريح للعين", "Eye comfort"),
                    when (st.comfort) { 1 -> ui.t("أصفر دافئ", "Warm yellow"); 2 -> ui.t("أخضر هادئ", "Soft green"); else -> ui.t("إيقاف", "Off") },
                    listOf(
                        ui.t("إيقاف", "Off") to { st.comfort = 0; again() },
                        ui.t("أصفر دافئ", "Warm yellow") to { st.comfort = 1; again() },
                        ui.t("أخضر هادئ", "Soft green") to { st.comfort = 2; again() }
                    )
                ),
                toggleRow(
                    ui.t("عكس اتجاه تقليب الصفحات", "Reverse page-turn direction"),
                    if (st.arabic) ui.t("الافتراضي بالعربية: السحب من اليسار لليمين = الصفحة التالية", "Default: swipe left→right = next page")
                    else ui.t("الافتراضي: السحب من اليمين لليسار = الصفحة التالية", "Default: swipe right→left = next page"),
                    st.flipSwipe
                ) { st.flipSwipe = it },
                choiceRow(
                    ui.t("مدة اختفاء الأدوات", "Hide tools after"),
                    if (st.hideSecs == 0) ui.t("أبدًا", "Never") else ui.t("${st.hideSecs} ثوانٍ", "${st.hideSecs} s"),
                    listOf(4, 8, 15, 30, 0).map { s ->
                        (if (s == 0) ui.t("أبدًا", "Never") else ui.t("$s ثوانٍ", "$s s")) to { st.hideSecs = s; again() }
                    }
                ),
                toggleRow(ui.t("حفظ موضع القراءة تلقائيًا", "Save reading position automatically"), null, st.autoSavePos) { st.autoSavePos = it },
                toggleRow(ui.t("استعادة آخر موضع عند فتح الملف", "Restore last position on open"), null, st.restorePos) { st.restorePos = it },
                toggleRow(ui.t("عرض رقم الصفحة", "Show page number"), null, st.showPageNum) { st.showPageNum = it }
            )
        )

        // B. appearance
        val accentNames = listOf(ui.t("ذهبي", "Gold"), ui.t("زمردي", "Emerald"), ui.t("سماوي", "Cyan"), ui.t("بنفسجي", "Purple"))
        val sizeNames = listOf(ui.t("صغير", "Small"), ui.t("متوسط", "Medium"), ui.t("كبير", "Large"))
        ui.section(
            col, ui.t("إعدادات المظهر", "Appearance"), listOf(
                choiceRow(
                    ui.t("اللغة", "Language"), if (st.arabic) "العربية" else "English",
                    listOf("العربية" to { st.language = "ar"; again() }, "English" to { st.language = "en"; again() })
                ),
                choiceRow(
                    ui.t("حجم النصوص في التطبيق", "App text size"), sizeNames[st.textScaleIdx.coerceIn(0, 2)],
                    sizeNames.mapIndexed { i, n -> n to { st.textScaleIdx = i; again() } }
                ),
                choiceRow(
                    ui.t("لون الواجهة", "Accent colour"), accentNames[st.accentIdx.coerceIn(0, 3)],
                    accentNames.mapIndexed { i, n -> n to { st.accentIdx = i; C.accent = C.accents[i]; again() } }
                ),
                choiceRow(
                    ui.t("عرض مكتبة الملفات", "Library layout"),
                    if (st.libraryView == "grid") ui.t("شبكة", "Grid") else ui.t("قائمة", "List"),
                    listOf(
                        ui.t("قائمة", "List") to { st.libraryView = "list"; again() },
                        ui.t("شبكة", "Grid") to { st.libraryView = "grid"; again() }
                    )
                ),
            )
        )

        // C. editing
        val sizeOptions = listOf(12, 16, 20, 24, 32, 48)
        val colorNames = listOf(
            ui.t("أسود", "Black") to 0xFF000000.toInt(), ui.t("أبيض", "White") to 0xFFFFFFFF.toInt(),
            ui.t("أزرق", "Blue") to 0xFF2F6BFF.toInt(), ui.t("أخضر", "Green") to 0xFF20BE78.toInt(),
            ui.t("أحمر", "Red") to 0xFFE85A5A.toInt()
        )
        val curColor = colorNames.firstOrNull { it.second == st.defTextColor }?.first ?: ui.t("مخصص", "Custom")
        val hasSig = act.loadSignature() != null
        ui.section(
            col, ui.t("إعدادات التعديل", "Editing"), listOf(
                choiceRow(
                    ui.t("طريقة الحفظ الافتراضية", "Default save method"),
                    if (st.saveMode == "overwrite") ui.t("تعديل الملف الأصلي", "Overwrite original") else ui.t("حفظ نسخة جديدة", "Save as new copy"),
                    listOf(
                        ui.t("حفظ نسخة جديدة (الأفضل)", "Save as new copy (safest)") to { st.saveMode = "copy"; again() },
                        ui.t("محاولة تعديل الملف الأصلي", "Try to overwrite the original") to { st.saveMode = "overwrite"; again() }
                    )
                ),
                choiceRow(ui.t("حجم النص الافتراضي", "Default text size"), "${st.defTextSize}", sizeOptions.map { s -> "$s" to { st.defTextSize = s; again() } }),
                choiceRow(ui.t("لون النص الافتراضي", "Default text colour"), curColor, colorNames.map { c -> c.first to { st.defTextColor = c.second; again() } }),
                ui.settingRow(ui.t("نص العلامة المائية الافتراضي", "Default watermark text"), st.wmText, ui.chevron()) {
                    ui.input(ui.t("نص العلامة المائية", "Watermark text"), ui.t("النص", "Text"), st.wmText, ui.t("حفظ", "Save")) { st.wmText = it; again() }
                },
                choiceRow(
                    ui.t("شفافية العلامة المائية", "Watermark opacity"), "${st.wmAlpha}%",
                    listOf(15, 25, 35, 50, 70).map { a -> "$a%" to { st.wmAlpha = a; again() } }
                ),
                ui.settingRow(
                    ui.t("التوقيع المحفوظ", "Saved signature"),
                    if (hasSig) ui.t("موجود — اضغط لحذفه", "Saved — tap to delete") else ui.t("لا يوجد (يُحفظ من نافذة التوقيع)", "None (save one from the signature dialog)"),
                    null
                ) {
                    if (hasSig) ui.confirm(ui.t("حذف التوقيع", "Delete signature"), ui.t("حذف التوقيع المحفوظ؟", "Delete the saved signature?"), ui.t("حذف", "Delete"), true) {
                        act.deleteSignature(); again()
                    }
                },
                toggleRow(ui.t("تأكيد الحذف", "Confirm before deleting items"), null, st.confirmDelete) { st.confirmDelete = it },
                toggleRow(
                    ui.t("حفظ التعديلات المؤقتة", "Keep temporary drafts"),
                    ui.t("لاستعادتها بعد إغلاق غير متوقع", "Restore them after an unexpected close"), st.saveDrafts
                ) { st.saveDrafts = it; if (!it) act.drafts.clearAll() }
            )
        )

        // D. search
        ui.section(
            col, ui.t("إعدادات البحث", "Search"), listOf(
                toggleRow(ui.t("مطابقة الكلمة كاملة", "Match whole word"), null, st.searchWhole) { st.searchWhole = it },
                toggleRow(ui.t("تجاهل اختلاف حالة الأحرف", "Ignore letter case"), null, st.searchIgnoreCase) { st.searchIgnoreCase = it },
                toggleRow(ui.t("تمييز نتائج البحث", "Highlight results"), null, st.searchHighlight) { st.searchHighlight = it },
                toggleRow(ui.t("حفظ عمليات البحث الأخيرة", "Remember recent searches"), null, st.saveRecent) { st.saveRecent = it; if (!it) st.recentSearches = "" },
                ui.settingRow(
                    ui.t("التعرف على الحروف (OCR)", "Text recognition (OCR)"),
                    ui.t("غير متاح في هذه النسخة — الملفات الممسوحة ضوئيًا لا يمكن البحث فيها", "Not available in this version — scanned files cannot be searched"),
                    null
                )
            )
        )

        // E. files
        val folderLabel = if (st.defSaveFolder.isEmpty()) ui.t("يُسأل في كل مرة", "Ask every time") else (android.net.Uri.parse(st.defSaveFolder).lastPathSegment ?: "…")
        val tempSize = dirSize(act.cacheDir)
        ui.section(
            col, ui.t("إعدادات الملفات", "Files"), listOf(
                toggleRow(ui.t("فحص الجهاز تلقائيًا", "Scan the device automatically"), ui.t("عن ملفات PDF عند فتح المكتبة", "For PDFs when the library opens"), st.autoScan) { st.autoScan = it },
                ui.settingRow(ui.t("فحص الجهاز الآن", "Scan the device now"), if (act.hasStorageAccess()) null else ui.t("يحتاج إذن الوصول للملفات", "Needs file access"), ui.chevron()) { act.scanDevice(true) },
                ui.settingRow(ui.t("مجلد الحفظ الافتراضي", "Default save folder"), folderLabel, ui.chevron()) {
                    ui.menu(
                        listOf(
                            ui.t("اختيار مجلد", "Choose a folder") to { act.pickDefaultFolder() },
                            ui.t("السؤال في كل مرة", "Ask every time") to { st.defSaveFolder = ""; again() }
                        ), ui.t("مجلد الحفظ", "Save folder")
                    )
                },
                choiceRow(
                    ui.t("ترتيب الملفات", "File order"),
                    when (st.sortOrder) { "name" -> ui.t("الاسم", "Name"); "size" -> ui.t("الحجم", "Size"); else -> ui.t("آخر فتح", "Last opened") },
                    listOf(
                        ui.t("آخر فتح", "Last opened") to { st.sortOrder = "opened"; again() },
                        ui.t("الاسم", "Name") to { st.sortOrder = "name"; again() },
                        ui.t("الحجم", "Size") to { st.sortOrder = "size"; again() }
                    )
                ),
                ui.settingRow(ui.t("إدارة النسخ المعدلة", "Edited copies"), null, ui.chevron()) { act.showFiles() },
                ui.settingRow(ui.t("إدارة الملفات المؤقتة", "Temporary files"), fmtSize(tempSize), ui.chevron()) { act.showFiles() },
                ui.settingRow(ui.t("الملفات التي يمكن للتطبيق الوصول إليها", "Files the app can access"), null, ui.chevron()) { act.showFiles() }
            )
        )

        // F. about
        ui.section(
            col, ui.t("معلومات التطبيق", "About"), listOf(
                ui.settingRow("BLACK CLOVER PDF READER & EDITOR", ui.t("الإصدار 10.6", "Version 10.6"), ui.chevron()) { act.showAbout() }
            )
        )

        r.addView(scroller(col, { settingsScroll = it }, settingsScroll), ui.lp(ui.MATCH, 0, 1f))
        return r
    }

    // ───────────────────────── about ─────────────────────────

    fun about(): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("معلومات التطبيق", "About"), { act.goBack() }))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val card = ui.card(18)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
        card.background = ui.rounded(C.panel, 18, ui.glow())
        val logo = ImageView(act)
        logo.setImageResource(R.drawable.app_logo)
        card.addView(logo, ui.lp(ui.dp(150), ui.dp(150)))
        card.addView(ui.txt("BLACK CLOVER", 24f, C.gold, true, true))
        card.addView(ui.txt("PDF READER & EDITOR", 12f, C.white, false, true), ui.lpm(ui.WRAP, ui.WRAP, 0f, 0, 0, 0, ui.dp(14)))
        fun row(k: String, v: String) {
            card.addView(ui.divider())
            val h = LinearLayout(act)
            h.orientation = LinearLayout.HORIZONTAL
            h.layoutDirection = ui.dir()
            h.setPadding(0, ui.dp(12), 0, ui.dp(12))
            h.addView(ui.txt(k, 14f, C.muted), ui.lp(0, ui.WRAP, 1f))
            h.addView(ui.txt(v, 14f, C.white, true))
            card.addView(h, ui.lp(ui.MATCH, ui.WRAP))
        }
        row(ui.t("المطور", "Developer"), "Mikey (" + ui.t("محمد محمود عامر", "Mohamed Mahmoud Amer") + ")")
        row(ui.t("الإصدار", "Version"), "10.6")
        row(ui.t("معلومات النسخة", "Build"), "Android " + android.os.Build.VERSION.RELEASE + " · API " + android.os.Build.VERSION.SDK_INT)
        card.addView(ui.divider())
        card.addView(
            ui.txt(
                ui.t(
                    "قارئ ومحرر PDF بتصميم مستوحى من Black Clover، يوفر مكتبة داخلية لإدارة الملفات، وقراءة بملء الشاشة، وأدوات لتعديل المستندات وإضافة النصوص والصور والتوقيعات والعلامات المائية، إلى جانب البحث داخل الملفات وحفظ مواضع القراءة، مع نظام تكبير وتصغير يدوي بالأصابع فقط.",
                    "A PDF reader and editor inspired by Black Clover with an in-app library, full-screen reading, tools to add text, images, signatures and watermarks, in-file search and reading marks, with pinch-only zoom."
                ), 13f, C.muted, false, true
            ), ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(12), 0, 0)
        )
        col.addView(card, ui.lp(ui.MATCH, ui.WRAP))
        r.addView(scroller(col), ui.lp(ui.MATCH, 0, 1f))
        return r
    }

    // ───────────────────────── files management ─────────────────────────

    fun files(): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("إدارة الملفات", "Manage files"), { act.goBack() }))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val edited = act.store.all().filter { it.edited }
        val editedRows = if (edited.isEmpty()) {
            listOf(ui.settingRow(ui.t("لا توجد نسخ معدلة بعد", "No edited copies yet"), null, null))
        } else edited.map { e ->
            ui.settingRow(e.name, fmtSize(e.size) + "  ·  " + fmtDate(e.lastOpened), ui.icon("trash", C.red, 40) {
                ui.confirm(
                    ui.t("إزالة النسخة", "Remove copy"),
                    if (e.internal) ui.t("سيتم حذف هذه النسخة من التطبيق. لن يتأثر الملف الأصلي.", "This copy will be deleted from the app. Your original is not affected.")
                    else ui.t("ستُزال من المكتبة فقط، ولن يُحذف الملف من الجهاز.", "It will only be removed from the library; the file stays on your device."),
                    ui.t("إزالة", "Remove"), true
                ) { act.ops.delete(listOf(e)); act.showFiles() }
            }) { act.openEntry(e, null) }
        }
        ui.section(col, ui.t("النسخ المعدلة", "Edited copies"), editedRows)

        val temp = dirSize(act.cacheDir)
        ui.section(
            col, ui.t("الملفات المؤقتة", "Temporary files"), listOf(
                ui.settingRow(
                    ui.t("الحجم الحالي", "Current size"), ui.t("نسخ العمل والصور المصغرة والمسودات", "Working copies, thumbnails and drafts"),
                    ui.btn(fmtSize(temp) + "  ✕", C.panel2, C.red, C.line) {
                        ui.confirm(
                            ui.t("حذف الملفات المؤقتة", "Delete temporary files"),
                            ui.t("سيتم حذف المسودات غير المحفوظة أيضًا. ملفاتك الأصلية لن تتأثر.", "Unsaved drafts will be deleted too. Your files are not affected."),
                            ui.t("حذف", "Delete"), true
                        ) {
                            val open = act.currentReaderEntryId()
                            act.cacheDir.listFiles()?.forEach { f ->
                                if (f.name != "src" || open == null) f.deleteRecursively()
                            }
                            act.showFiles()
                        }
                    }
                )
            )
        )

        val perms = try { act.contentResolver.persistedUriPermissions } catch (_: Exception) { emptyList() }
        val permRows = if (perms.isEmpty()) {
            listOf(ui.settingRow(ui.t("لا توجد أذونات محفوظة", "No stored permissions"), null, null))
        } else perms.map { p ->
            val name = p.uri.lastPathSegment ?: p.uri.toString()
            ui.settingRow(name, if (p.isWritePermission) ui.t("قراءة وكتابة", "Read & write") else ui.t("قراءة فقط", "Read only"), ui.icon("close", C.red, 40) {
                try { act.contentResolver.releasePersistableUriPermission(p.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                act.showFiles()
            })
        }
        ui.section(col, ui.t("الملفات والمجلدات التي يصل إليها التطبيق", "Files and folders the app can access"), permRows)

        r.addView(scroller(col), ui.lp(ui.MATCH, 0, 1f))
        return r
    }

    // ───────────────────────── bookmarks & reading marks ─────────────────────────

    fun bookmarks(docId: String?): View {
        val r = ui.screenRoot()
        r.addView(ui.header(ui.t("الإشارات المرجعية", "Bookmarks"), { act.goBack() }))

        val tabs = LinearLayout(act)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.layoutDirection = ui.dir()
        tabs.background = ui.rounded(C.panel, 14, C.line)
        tabs.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
        val names = listOf(ui.t("الإشارات المرجعية", "Bookmarks"), ui.t("علامات القراءة", "Reading marks"))
        for (i in names.indices) {
            val on = bmTab == i
            val t = ui.txt(names[i], 14f, if (on) C.accent else C.white, on, true)
            if (on) t.background = ui.rounded(C.accentDark(), 11, C.accent)
            t.setPadding(0, ui.dp(10), 0, ui.dp(10))
            t.setOnClickListener { bmTab = i; act.showBookmarks(docId) }
            tabs.addView(t, ui.lp(0, ui.WRAP, 1f))
        }
        r.addView(tabs, ui.lpm(ui.MATCH, ui.WRAP, 0f, ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(6)))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        if (bmTab == 0) {
            val list = act.marks.bookmarks().filter { docId == null || it.docId == docId }
            val rows: List<View> = if (list.isEmpty()) {
                listOf(ui.settingRow(ui.t("لا توجد إشارات مرجعية", "No bookmarks yet"), null, null))
            } else list.map { b ->
                val doc = act.store.find(b.docId)
                ui.settingRow(
                    (if (docId == null) (doc?.name ?: "?") + " — " else "") + ui.t("صفحة ${b.page + 1}", "Page ${b.page + 1}"),
                    b.label + "  ·  " + fmtDate(b.time),
                    ui.icon("trash", C.red, 40) { act.marks.removeBookmark(b.docId, b.page); act.showBookmarks(docId) }
                ) { act.openAt(b.docId, b.page) }
            }
            ui.section(col, if (docId == null) ui.t("كل الملفات", "All files") else ui.t("هذا الملف", "This file"), rows)
            val rd = act.currentReader()
            if (docId != null && rd != null && rd.entry.id == docId) {
                col.addView(
                    ui.primaryBtn("＋  " + ui.t("إضافة إشارة جديدة (صفحة ${rd.currentPage + 1})", "Add bookmark (page ${rd.currentPage + 1})")) {
                        ui.input(ui.t("إضافة إشارة", "Add bookmark"), ui.t("ملاحظة", "Note"), ui.t("صفحة ${rd.currentPage + 1}", "Page ${rd.currentPage + 1}"), ui.t("إضافة", "Add")) { note ->
                            act.marks.addBookmark(Bookmark(docId, rd.currentPage, note, System.currentTimeMillis()))
                            act.showBookmarks(docId)
                        }
                    }, ui.lpm(ui.MATCH, ui.WRAP, 0f, 0, ui.dp(14), 0, 0)
                )
            }
        } else {
            val list = act.marks.resumeMarks()
            val rows: List<View> = if (list.isEmpty()) {
                listOf(ui.settingRow(ui.t("لا توجد علامات استكمال", "No reading marks yet"), null, null))
            } else list.map { m ->
                ui.settingRow(
                    m.name, ui.t("صفحة ${m.page + 1}", "Page ${m.page + 1}") + "  ·  " + fmtDate(m.time),
                    ui.icon("trash", C.red, 40) { act.marks.removeResume(m.docId); act.showBookmarks(docId) }
                ) { act.openAt(m.docId, m.page) }
            }
            ui.section(col, ui.t("متابعة القراءة", "Continue reading"), rows)
        }
        r.addView(scroller(col), ui.lp(ui.MATCH, 0, 1f))
        return r
    }
}
