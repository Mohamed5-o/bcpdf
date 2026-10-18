package com.example.pdfreader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Serves PDFs stored inside the app (filesDir/library) to other apps for sharing, without extra libraries. */
class ShareProvider : ContentProvider() {
    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains("/") || name.contains("..")) return null
        val f = File(File(ctx.filesDir, "library"), name)
        return if (f.exists()) f else null
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

class LibOps(private val act: MainActivity) {
    private val store get() = act.store
    private val ui get() = act.ui
    private val st0 get() = act.st

    fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "document.pdf"
        var size = 0L
        try {
            act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        return Pair(name, size)
    }

    private fun uniqueFile(dir: File, wanted: String): File {
        var base = wanted.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!base.lowercase().endsWith(".pdf")) base += ".pdf"
        var f = File(dir, base)
        var n = 1
        val stem = base.substring(0, base.length - 4)
        while (f.exists()) {
            f = File(dir, "$stem ($n).pdf")
            n++
        }
        return f
    }

    fun persist(uri: Uri) {
        try {
            act.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    /** Copies picked files into the app library (originals are untouched). */
    fun importUris(uris: List<Uri>) {
        Thread {
            var ok = 0
            for (u in uris) {
                try {
                    val (name, _) = queryMeta(u)
                    val dst = uniqueFile(store.libDir, name)
                    val input = act.contentResolver.openInputStream(u) ?: continue
                    input.use { ins -> FileOutputStream(dst).use { out -> ins.copyTo(out) } }
                    val uriStr = Uri.fromFile(dst).toString()
                    store.upsert(LibEntry(docIdOf(uriStr), dst.name, uriStr, dst.length(), 0, 0L, 0, false, true))
                    ok++
                } catch (_: Exception) {
                }
            }
            act.runOnUiThread {
                ui.toast(ui.t("تم استيراد $ok ملف", "Imported $ok file(s)"))
                act.refreshLibrary()
            }
        }.start()
    }

    fun link(uri: Uri): LibEntry {
        val s = uri.toString()
        store.findByUri(s)?.let { return it }
        persist(uri)
        val (name, size) = queryMeta(uri)
        val e = LibEntry(docIdOf(s), name, s, size, 0, 0L, 0, false, uri.scheme == "file")
        store.upsert(e)
        return e
    }

    fun scanTree(tree: Uri, silent: Boolean = false) {
        if (!silent) persist(tree)
        Thread {
            var found = 0
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            fun walk(docId: String, depth: Int) {
                if (depth > 12 || found >= 5000) return
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                try {
                    act.contentResolver.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
                        ), null, null, null
                    )?.use { c ->
                        val rows = ArrayList<Array<String>>()
                        while (c.moveToNext()) {
                            rows.add(arrayOf(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) "0" else c.getLong(3).toString()))
                        }
                        for (r in rows) {
                            if (found >= 5000) break
                            if (r[2] == DocumentsContract.Document.MIME_TYPE_DIR) {
                                walk(r[0], depth + 1)
                            } else if (r[2] == "application/pdf" || r[1].lowercase().endsWith(".pdf")) {
                                val du = DocumentsContract.buildDocumentUriUsingTree(tree, r[0]).toString()
                                if (store.findByUri(du) == null) {
                                    store.upsert(LibEntry(docIdOf(du), r[1], du, r[3].toLongOrNull() ?: 0L, 0, 0L, 0, false, false))
                                    found++
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            walk(rootId, 0)
            act.runOnUiThread {
                if (!silent) ui.toast(ui.t("تمت إضافة $found ملف من المجلد", "Added $found file(s) from the folder"))
                if (!silent || found > 0) act.refreshLibrary()
            }
        }.start()
    }

    /** Deep scan: file walk of every storage volume + MediaStore index + previously granted folders. Entries are linked, never copied. */
    fun scanDevice(manual: Boolean) {
        if (!act.hasStorageAccess()) {
            if (manual) act.requestStorageAccess()
            return
        }
        st0.lastScanMin = (System.currentTimeMillis() / 60000L).toInt()
        Thread {
            val seen = HashSet<String>()
            val found = ArrayList<Pair<String, File>>()
            val skip = setOf("thumbnails", ".thumbnails", "lost.dir", ".trash", ".trashed", "trash", ".recyclebin", "\$recycle.bin")
            fun add(f: File) {
                val key = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
                if (seen.add(key)) found.add(key to f)
            }
            fun walk(d: File, depth: Int) {
                if (depth > 16 || found.size >= 20000) return
                val kids = try { d.listFiles() } catch (_: Exception) { null } ?: return
                for (f in kids) {
                    val n = f.name
                    if (f.isDirectory) {
                        val ln = n.lowercase()
                        if (ln in skip || ln.startsWith(".trash")) continue
                        if (depth == 1 && ln == "data" && d.name.equals("android", true)) continue
                        walk(f, depth + 1)
                    } else if (n.endsWith(".pdf", ignoreCase = true) && f.length() > 100) add(f)
                }
            }
            val roots = LinkedHashSet<File>()
            try { roots.add(android.os.Environment.getExternalStorageDirectory()) } catch (_: Exception) {}
            try { File("/storage").listFiles()?.forEach { if (it.name != "self" && it.name != "emulated" && it.isDirectory) roots.add(it) } } catch (_: Exception) {}
            try { act.getExternalFilesDirs(null)?.forEach { d -> d?.absolutePath?.substringBefore("/Android/")?.let { p -> roots.add(File(p)) } } } catch (_: Exception) {}
            for (r in roots) try { walk(r, 0) } catch (_: Exception) {}
            // MediaStore index (finds files the walk could not list)
            try {
                val uri = android.provider.MediaStore.Files.getContentUri("external")
                act.contentResolver.query(
                    uri, arrayOf(android.provider.MediaStore.Files.FileColumns._ID, android.provider.MediaStore.Files.FileColumns.DATA, android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, android.provider.MediaStore.Files.FileColumns.SIZE),
                    android.provider.MediaStore.Files.FileColumns.MIME_TYPE + "=? OR " + android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("application/pdf", "%.pdf"), null
                )?.use { c ->
                    while (c.moveToNext() && found.size < 20000) {
                        val p = c.getString(1) ?: continue
                        val f = File(p)
                        if (f.isFile && f.canRead() && f.length() > 100) add(f)
                    }
                }
            } catch (_: Throwable) {}
            var added = 0
            val have = HashSet<String>()
            for (e in store.all()) have.add(e.name.lowercase() + "|" + e.size)
            for ((_, f) in found) {
                val us = Uri.fromFile(f).toString()
                if (store.findByUri(us) != null) continue
                if (!have.add(f.name.lowercase() + "|" + f.length())) continue
                store.upsert(LibEntry(docIdOf(us), f.name, us, f.length(), 0, 0L, 0, false, false))
                added++
            }
            var removed = 0
            val own = act.filesDir.absolutePath
            for (e in ArrayList(store.all())) {
                val u = Uri.parse(e.uri)
                val path = u.path ?: continue
                if (u.scheme == "file" && !e.internal && !path.startsWith(own) && !File(path).exists()) {
                    store.remove(e.id)
                    removed++
                }
            }
            act.runOnUiThread {
                if (added > 0 || removed > 0 || manual) act.refreshLibrary()
                if (manual) {
                    ui.menu(
                        listOf(
                            ui.t("إضافة ملفات يدويًا (مدير الملفات)", "Add files manually (file manager)") to { act.pickLinked() },
                            ui.t("إضافة مجلد كامل", "Add a whole folder") to { act.pickFolder() },
                            ui.t("تم", "Done") to {}
                        ),
                        ui.t("تم العثور على $added ملف جديد (${found.size} إجمالًا على الجهاز)", "Found $added new file(s) (${found.size} on the device)")
                    )
                }
            }
            // re-scan folders granted earlier through the file manager
            try {
                for (p in act.contentResolver.persistedUriPermissions) {
                    if (p.isReadPermission && DocumentsContract.isTreeUri(p.uri)) scanTree(p.uri, true)
                }
            } catch (_: Throwable) {}
        }.start()
    }

    fun copy(e: LibEntry) {
        Thread {
            try {
                val dst = uniqueFile(store.libDir, e.name.removeSuffix(".pdf").removeSuffix(".PDF") + " - copy.pdf")
                val input = act.contentResolver.openInputStream(Uri.parse(e.uri))!!
                input.use { ins -> FileOutputStream(dst).use { out -> ins.copyTo(out) } }
                val s = Uri.fromFile(dst).toString()
                store.upsert(LibEntry(docIdOf(s), dst.name, s, dst.length(), e.pages, 0L, 0, e.edited, true))
                act.runOnUiThread { act.refreshLibrary(); ui.toast(ui.t("تم النسخ", "Copied")) }
            } catch (ex: Exception) {
                act.runOnUiThread { ui.toast(ui.t("تعذر النسخ", "Copy failed")) }
            }
        }.start()
    }

    fun shareUri(e: LibEntry): Uri {
        val u = Uri.parse(e.uri)
        return if (u.scheme == "file") {
            Uri.parse("content://" + act.packageName + ".share/" + Uri.encode(File(u.path ?: "").name))
        } else u
    }

    fun share(list: List<LibEntry>) {
        if (list.isEmpty()) return
        val intent: Intent
        if (list.size == 1) {
            intent = Intent(Intent.ACTION_SEND)
            intent.putExtra(Intent.EXTRA_STREAM, shareUri(list[0]))
        } else {
            intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { shareUri(it) }))
        }
        intent.type = "application/pdf"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivity(Intent.createChooser(intent, ui.t("مشاركة", "Share")))
        } catch (_: Exception) {
            ui.toast(ui.t("لا يوجد تطبيق للمشاركة", "No app to share with"))
        }
    }

    /** Internal copies are deleted; linked files are only removed from the library (the original stays). */
    fun delete(list: List<LibEntry>) {
        for (e in list) {
            if (e.internal) {
                try { File(Uri.parse(e.uri).path ?: "").delete() } catch (_: Exception) {}
            }
            store.remove(e.id)
            act.marks.removeResume(e.id)
            act.drafts.clear(e.id)
            File(File(act.cacheDir, "thumbs"), e.id + ".png").delete()
        }
        act.refreshLibrary()
    }

    fun rename(e: LibEntry, name: String) {
        var n = name.trim()
        if (!n.lowercase().endsWith(".pdf")) n += ".pdf"
        e.name = n
        store.update(e)
        act.refreshLibrary()
    }
}
