package com.example.pdfreader

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    lateinit var st: Settings
    lateinit var ui: Ui
    lateinit var store: LibraryStore
    lateinit var marks: MarkStore
    lateinit var drafts: DraftStore
    lateinit var dialogs: Dialogs
    lateinit var thumbs: ThumbLoader
    lateinit var ops: LibOps
    private lateinit var screens: Screens
    private lateinit var screens2: Screens2
    private lateinit var content: FrameLayout

    private var reader: ReaderScreen? = null
    private var library: LibraryScreen? = null

    private class Nav(val key: String, val build: () -> View)

    private val stack = ArrayList<Nav>()
    private var current: Nav? = null

    private var imageCb: ((Uri) -> Unit)? = null
    private var pendingTmp: File? = null
    private var pendingName: String = ""
    private var pendingAfter: (() -> Unit)? = null

    companion object {
        const val REQ_IMPORT = 101
        const val REQ_LINK = 102
        const val REQ_FOLDER = 103
        const val REQ_IMAGE = 104
        const val REQ_CREATE = 105
        const val REQ_DEFFOLDER = 106
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext) } catch (_: Throwable) {}
        st = Settings(this)
        C.accent = C.accents[st.accentIdx.coerceIn(0, C.accents.size - 1)]
        ui = Ui(this, st)
        store = LibraryStore(this)
        marks = MarkStore(this)
        drafts = DraftStore(this)
        dialogs = Dialogs(ui, st)
        thumbs = ThumbLoader(this)
        ops = LibOps(this)
        screens = Screens(this)
        screens2 = Screens2(this)

        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        content = FrameLayout(this)
        content.setBackgroundColor(C.bg)
        setContentView(content)

        if (!handleIntent(intent)) {
            if (savedInstanceState == null) goTo("home", false) { screens2.home() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i == null) return false
        var uri: Uri? = null
        if (i.action == Intent.ACTION_VIEW) uri = i.data
        else if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            uri = i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) return false
        val u = uri
        i.action = null
        Thread {
            try {
                val e = ops.link(u)
                runOnUiThread { openEntry(e, null) }
            } catch (ex: Exception) {
                runOnUiThread { ui.toast(ui.t("تعذر فتح الملف", "Could not open the file")) }
            }
        }.start()
        return true
    }

    override fun onPause() {
        super.onPause()
        reader?.onPause()
    }

    override fun onDestroy() {
        screens2.cancelSearch()
        if (isFinishing) {
            reader?.destroy()
            reader = null
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val c = current ?: return
        if (c.key == "reader") reader?.detachViews()
        show(c)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = current
        if (c?.key == "reader") {
            reader?.onBack()
            return
        }
        goBack()
    }

    // ───────────────────────── navigation ─────────────────────────

    private fun applyBars(key: String) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (key == "reader") {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun show(n: Nav) {
        content.removeAllViews()
        val v = n.build()
        v.layoutDirection = ui.dir()
        content.layoutDirection = ui.dir()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyBars(n.key)
    }

    private fun goTo(key: String, push: Boolean = true, build: () -> View) {
        val prev = current
        if (prev != null && prev.key == "reader") reader?.detachViews()
        if (prev != null && prev.key == "search") screens2.cancelSearch()
        if (prev != null && push && prev.key != key) stack.add(prev)
        val n = Nav(key, build)
        current = n
        show(n)
    }

    fun goBack() {
        val prev = current
        if (prev != null && prev.key == "reader") reader?.detachViews()
        if (prev != null && prev.key == "search") screens2.cancelSearch()
        if (stack.isEmpty()) {
            finish()
            return
        }
        val n = stack.removeAt(stack.size - 1)
        current = n
        show(n)
    }

    fun showLibrary() {
        stack.clear()
        val lib = LibraryScreen(this)
        library = lib
        val prev = current
        if (prev != null && prev.key == "reader") reader?.detachViews()
        val n = Nav("library") { lib.build() }
        current = n
        show(n)
    }

    fun refreshLibrary() {
        if (current?.key == "library") library?.refresh()
    }

    fun showSettings() = goTo("settings") { screens.settings() }
    fun showAbout() = goTo("about") { screens.about() }
    fun showFiles() = goTo("files") { screens.files() }
    fun showBookmarks(docId: String?) = goTo("bm:" + (docId ?: "all")) { screens.bookmarks(docId) }
    fun showSearch(r: ReaderScreen) = goTo("search") { screens2.search(r) }
    fun showSaveScreen(r: ReaderScreen) = goTo("save") { screens2.save(r) }

    fun showReader() {
        val r = reader ?: return
        // unwind to the reader if it is already on the stack
        val idx = stack.indexOfLast { it.key == "reader" }
        if (idx >= 0) {
            while (stack.size > idx + 1) stack.removeAt(stack.size - 1)
            val n = stack.removeAt(stack.size - 1)
            val prev = current
            if (prev != null && prev.key == "search") screens2.cancelSearch()
            current = n
            show(n)
        } else goTo("reader") { r.build() }
    }

    fun closeReader() {
        reader?.persistPosition()
        reader?.destroy()
        reader = null
        showLibrary()
    }

    fun currentReader(): ReaderScreen? = reader
    fun currentReaderEntryId(): String? = reader?.entry?.id

    // ───────────────────────── opening files ─────────────────────────

    fun openAt(docId: String, page: Int) {
        val e = store.find(docId)
        if (e == null) {
            ui.toast(ui.t("الملف غير موجود في المكتبة", "File is not in the library"))
            return
        }
        if (reader?.entry?.id == docId) {
            reader?.currentPage = page
            showReader()
        } else openEntry(e, page)
    }

    fun openEntry(e: LibEntry, page: Int?) {
        val busy = ui.dialog(false) { card, _ ->
            card.addView(ui.txt(ui.t("جارٍ فتح الملف…", "Opening…"), 15f, C.white, true, true))
        }
        Thread {
            var doc: PdfDoc? = null
            var err: String? = null
            try {
                doc = PdfDoc(this, Uri.parse(e.uri), e.id)
                doc.open()
            } catch (ex: SecurityException) {
                err = ui.t("الملف محمي بكلمة مرور أو لم يعد لدى التطبيق إذن الوصول إليه", "The file is password-protected or access was revoked")
                doc?.close()
            } catch (ex: Throwable) {
                err = ui.t("تعذر فتح الملف — قد يكون تالفًا أو غير مدعوم أو تم نقله", "Could not open the file — it may be damaged, unsupported or moved")
                doc?.close()
            }
            runOnUiThread {
                busy.dismiss()
                if (err != null || doc == null) {
                    ui.toast(err ?: "")
                    return@runOnUiThread
                }
                e.pages = doc.pageCount
                e.lastOpened = System.currentTimeMillis()
                store.update(e)
                reader?.destroy()
                val r = ReaderScreen(this, e, doc)
                reader = r
                r.prepare(page)
                stack.clear()
                val lib = LibraryScreen(this)
                library = lib
                stack.add(Nav("library") { lib.build() })
                current = null
                goTo("reader", false) { r.build() }
            }
        }.start()
    }

    // ───────────────────────── pickers & results ─────────────────────────

    private fun pdfIntent(multi: Boolean, persist: Boolean): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi)
        if (persist) i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return i
    }

    private fun launch(i: Intent, req: Int) {
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            ui.toast(ui.t("لا يوجد تطبيق لاختيار الملفات", "No app available to pick files"))
        }
    }

    fun pickImport() = launch(pdfIntent(true, false), REQ_IMPORT)
    fun pickLinked() = launch(pdfIntent(true, true), REQ_LINK)
    fun pickFolder() = launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_FOLDER)
    fun pickDefaultFolder() = launch(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ), REQ_DEFFOLDER
    )

    fun pickImage(cb: (Uri) -> Unit) {
        imageCb = cb
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        launch(i, REQ_IMAGE)
    }

    private fun urisOf(d: Intent?): List<Uri> {
        if (d == null) return emptyList()
        val out = ArrayList<Uri>()
        val cd = d.clipData
        if (cd != null) for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out.add(it) }
        else d.data?.let { out.add(it) }
        return out
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CREATE) {
            val tmp = pendingTmp
            val after = pendingAfter
            pendingTmp = null
            pendingAfter = null
            val u = if (resultCode == RESULT_OK) data?.data else null
            if (tmp != null && u != null) {
                Thread {
                    val ok = try { writeTo(tmp, u); true } catch (e: Exception) { false }
                    tmp.delete()
                    runOnUiThread {
                        ui.toast(if (ok) ui.t("تم حفظ الملف في المكان المختار", "Saved to the chosen location") else ui.t("تعذر الحفظ في المكان المختار (النسخة محفوظة في المكتبة)", "Could not save there (copy is kept in the library)"))
                    }
                }.start()
            } else {
                tmp?.delete()
                if (tmp != null) ui.toast(ui.t("النسخة محفوظة في مكتبة التطبيق", "The copy is kept in the app library"))
            }
            after?.invoke()
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> ops.importUris(urisOf(data))
            REQ_LINK -> Thread {
                for (u in urisOf(data)) try { ops.link(u) } catch (_: Exception) {}
                runOnUiThread { refreshLibrary() }
            }.start()
            REQ_FOLDER -> data.data?.let { ops.scanTree(it) }
            REQ_IMAGE -> data.data?.let { u -> imageCb?.invoke(u); imageCb = null }
            REQ_DEFFOLDER -> data.data?.let { u ->
                ops.persist(u)
                st.defSaveFolder = u.toString()
                if (current?.key == "settings") showSettings()
            }
        }
    }

    // ───────────────────────── signature ─────────────────────────

    private fun sigFile() = File(filesDir, "signature.png")

    fun loadSignature(): Bitmap? = try {
        val f = sigFile()
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Throwable) { null }

    fun saveSignature(b: Bitmap) {
        try { FileOutputStream(sigFile()).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
    }

    fun deleteSignature() { sigFile().delete() }

    // ───────────────────────── keyboard ─────────────────────────

    fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    fun showKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    // ───────────────────────── saving ─────────────────────────

    private fun writeTo(src: File, dst: Uri) {
        val out = try { contentResolver.openOutputStream(dst, "wt") } catch (_: Exception) { contentResolver.openOutputStream(dst, "w") }
            ?: throw IllegalStateException("cannot write")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
    }

    private fun readToFile(src: Uri, dst: File) {
        val input = contentResolver.openInputStream(src) ?: throw IllegalStateException("cannot read")
        input.use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
    }

    private fun uniqueLibFile(name: String): File {
        var base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!base.lowercase().endsWith(".pdf")) base += ".pdf"
        var f = File(store.libDir, base)
        var n = 1
        val stem = base.substring(0, base.length - 4)
        while (f.exists()) { f = File(store.libDir, "$stem ($n).pdf"); n++ }
        return f
    }

    fun performSave(r: ReaderScreen, name: String, overwrite: Boolean) {
        var label: TextView? = null
        val dlg = ui.dialog(false) { card, _ ->
            val t = ui.txt(ui.t("جارٍ الحفظ…", "Saving…"), 15f, C.white, true, true)
            label = t
            card.addView(t)
        }
        val items = ArrayList(r.edit.items)
        val pages = r.doc.pageCount
        val entry = r.entry
        val saveDir = File(cacheDir, "save")
        Thread {
            var error: String? = null
            var newEntry: LibEntry? = null
            var tmp: File? = null
            var overwritten = false
            try {
                saveDir.mkdirs()
                tmp = File(saveDir, "out_" + System.currentTimeMillis() + ".pdf")
                val src = r.doc.localFile()
                Saver.apply(src, items, tmp) { d, t ->
                    runOnUiThread { label?.text = ui.t("جارٍ الحفظ… $d / $t", "Saving… $d / $t") }
                }
                if (!Saver.verify(tmp, pages)) throw IllegalStateException("verify")
                if (overwrite) {
                    overwriteOriginal(r, entry, tmp, pages)
                    overwritten = true
                } else {
                    val dst = uniqueLibFile(name)
                    tmp.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
                    val us = Uri.fromFile(dst).toString()
                    val e = LibEntry(docIdOf(us), dst.name, us, dst.length(), pages, System.currentTimeMillis(), 0, true, true)
                    store.upsert(e)
                    newEntry = e
                }
            } catch (ex: Throwable) {
                error = when {
                    ex is SecurityException || (ex.message ?: "") == "readonly" ->
                        ui.t("لا يمكن تعديل الملف الأصلي (غير مسموح). احفظ نسخة جديدة بدلًا من ذلك.", "The original cannot be modified. Save a new copy instead.")
                    (ex.message ?: "") == "verify" -> ui.t("فشل التحقق من الملف الناتج، لم يُحفظ شيء. حاول حفظ نسخة جديدة.", "Verification of the result failed; nothing was saved.")
                    else -> ui.t("تعذر الحفظ: ", "Save failed: ") + (ex.message ?: ex.javaClass.simpleName)
                }
            }
            val finalTmp = tmp
            runOnUiThread {
                dlg.dismiss()
                if (error != null) {
                    finalTmp?.delete()
                    if (overwrite) {
                        // make sure the reader is usable again
                        try { r.doc.refreshLocalCopy(); r.doc.open() } catch (_: Exception) {}
                    }
                    ui.toast(error)
                    return@runOnUiThread
                }
                if (overwritten) {
                    finalTmp?.delete()
                    entry.edited = true
                    entry.size = try { contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")?.use { it.statSize } ?: entry.size } catch (_: Exception) { entry.size }
                    store.update(entry)
                    thumbs.forget(entry.id)
                    r.onSaved(true)
                    ui.toast(ui.t("تم تعديل الملف الأصلي بعد التحقق منه", "Original updated and verified"))
                    goBack()
                } else {
                    val e = newEntry!!
                    r.edit.clearAll()
                    drafts.clear(entry.id)
                    val openNew = {
                        ui.toast(ui.t("تم حفظ النسخة في المكتبة وفتحها", "Copy saved to the library and opened"))
                        openEntry(e, r.currentPage)
                    }
                    val folder = st.defSaveFolder
                    if (folder.isNotEmpty() && finalTmp != null) {
                        Thread {
                            val ok = try {
                                val tree = Uri.parse(folder)
                                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                                val u = DocumentsContract.createDocument(contentResolver, parent, "application/pdf", e.name)
                                if (u == null) false else { writeTo(finalTmp, u); true }
                            } catch (_: Exception) { false }
                            finalTmp.delete()
                            runOnUiThread {
                                if (!ok) ui.toast(ui.t("تعذر الحفظ في المجلد الافتراضي — النسخة في المكتبة", "Default folder failed — copy is in the library"))
                                openNew()
                            }
                        }.start()
                    } else if (finalTmp != null) {
                        pendingTmp = finalTmp
                        pendingAfter = { openNew() }
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "application/pdf"
                        i.putExtra(Intent.EXTRA_TITLE, e.name)
                        try { startActivityForResult(i, REQ_CREATE) } catch (_: Exception) { finalTmp.delete(); pendingTmp = null; openNew() }
                    } else openNew()
                }
            }
        }.start()
    }

    /** Backs up, writes, re-verifies and restores on any failure. Runs on a worker thread. */
    private fun overwriteOriginal(r: ReaderScreen, entry: LibEntry, tmp: File, pages: Int) {
        val uri = Uri.parse(entry.uri)
        val backupDir = File(cacheDir, "backup")
        backupDir.mkdirs()
        val backup = File(backupDir, entry.id + ".pdf")
        r.doc.close()
        textEngineClose(r)
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: throw IllegalStateException("readonly"))
                f.inputStream().use { i -> FileOutputStream(backup).use { o -> i.copyTo(o) } }
                try {
                    tmp.inputStream().use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                    if (!Saver.verify(f, pages)) throw IllegalStateException("verify")
                } catch (e: Throwable) {
                    backup.inputStream().use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                    throw e
                }
            } else {
                readToFile(uri, backup)
                try {
                    writeTo(tmp, uri)
                    val check = File(backupDir, entry.id + "_check.pdf")
                    readToFile(uri, check)
                    val ok = Saver.verify(check, pages)
                    check.delete()
                    if (!ok) throw IllegalStateException("verify")
                } catch (e: Throwable) {
                    try { writeTo(backup, uri) } catch (_: Exception) {}
                    throw e
                }
            }
        } finally {
            backup.delete()
        }
    }

    private fun textEngineClose(r: ReaderScreen) {
        try { r.textEngine.close() } catch (_: Exception) {}
    }
}
