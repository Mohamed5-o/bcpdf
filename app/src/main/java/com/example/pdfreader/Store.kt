package com.example.pdfreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Stable short id for a document uri. */
fun docIdOf(uri: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
    val sb = StringBuilder()
    for (i in 0 until 6) sb.append(String.format("%02x", d[i]))
    return sb.toString()
}

class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("bc_settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            p.edit().putString(k, value).apply()
        }
    }

    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(k, value).apply()
        }
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = p.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            p.edit().putInt(k, value).apply()
        }
    }

    // Reading
    var fitMode by str("fitMode", "page")          // page | width
    var scrollDir by str("scrollDir", "h")         // legacy (unused)
    var readMode by str("readMode", "h")           // h = page by page (swipe, direction follows language) | c = all pages stacked
    var flipSwipe by bool("flipSwipe", false)      // reverse the page-turn swipe direction
    var hideSecs by int("hideSecs", 8)             // 0 = never
    var autoSavePos by bool("autoSavePos", true)
    var restorePos by bool("restorePos", true)
    var showPageNum by bool("showPageNum", true)
    var night by bool("night", false)
    var comfort by int("comfort", 0)               // 0 off | 1 yellow | 2 green
    var comfortLevel by int("comfortLevel", 50)
    var dimPct by int("dimPct", 100)
    var fixedZoom by int("fixedZoom", 0)           // 0 = auto, else percent of fitted size
    var compactSide by bool("compactSide", true)

    // Appearance
    var language by str("language", "ar")
    var textScaleIdx by int("textScaleIdx", 1)
    var accentIdx by int("accentIdx", 0)
    var libraryView by str("libraryView", "grid")  // list | grid
    var effects by bool("effects", true)

    // Editing
    var saveMode by str("saveMode", "copy")        // copy | overwrite
    var defTextSize by int("defTextSize", 24)
    var defTextColor by int("defTextColor", 0xFF000000.toInt())
    var defTextFont by int("defTextFont", 0)
    var wmText by str("wmText", "BLACK CLOVER")
    var wmAlpha by int("wmAlpha", 30)
    var wmSize by int("wmSize", 48)
    var wmColor by int("wmColor", 0xFF20BE78.toInt())
    var wmAngle by int("wmAngle", 35)
    var eraserSize by int("eraserSize", 14)
    var confirmDelete by bool("confirmDelete", true)
    var saveDrafts by bool("saveDrafts", true)

    // Search
    var searchWhole by bool("searchWhole", false)
    var searchIgnoreCase by bool("searchIgnoreCase", true)
    var searchHighlight by bool("searchHighlight", true)
    var saveRecent by bool("saveRecent", true)
    var recentSearches by str("recentSearches", "")

    // Files
    var defSaveFolder by str("defSaveFolder", "")
    var autoScan by bool("autoScan", true)          // scan the device for PDFs automatically
    var askedStorage by bool("askedStorage", false)
    var lastScanMin by int("lastScanMin", 0)
    var sortOrder by str("sortOrder", "opened")    // name | size | opened

    val arabic: Boolean get() = language == "ar"
    fun textScale(): Float = when (textScaleIdx) { 0 -> 0.9f; 2 -> 1.15f; else -> 1f }

    fun addRecentSearch(q: String) {
        if (!saveRecent || q.isBlank()) return
        val list = recentSearches.split("\n").filter { it.isNotBlank() && it != q }.toMutableList()
        list.add(0, q)
        recentSearches = list.take(8).joinToString("\n")
    }
}

data class LibEntry(
    val id: String,
    var name: String,
    var uri: String,
    var size: Long,
    var pages: Int,
    var lastOpened: Long,
    var lastPage: Int,
    var edited: Boolean,
    val internal: Boolean
)

class LibraryStore(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("bc_lib", Context.MODE_PRIVATE)
    private var cache: MutableList<LibEntry>? = null

    val libDir: File get() = File(ctx.filesDir, "library").also { it.mkdirs() }

    fun all(): MutableList<LibEntry> {
        cache?.let { return it }
        val out = ArrayList<LibEntry>()
        try {
            val arr = JSONArray(p.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    LibEntry(
                        o.getString("id"), o.getString("name"), o.getString("uri"),
                        o.optLong("size"), o.optInt("pages"), o.optLong("opened"),
                        o.optInt("page"), o.optBoolean("edited"), o.optBoolean("internal")
                    )
                )
            }
        } catch (_: Exception) {
        }
        cache = out
        return out
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in all()) {
            val o = JSONObject()
            o.put("id", e.id); o.put("name", e.name); o.put("uri", e.uri)
            o.put("size", e.size); o.put("pages", e.pages); o.put("opened", e.lastOpened)
            o.put("page", e.lastPage); o.put("edited", e.edited); o.put("internal", e.internal)
            arr.put(o)
        }
        p.edit().putString("items", arr.toString()).apply()
    }

    fun find(id: String): LibEntry? = all().firstOrNull { it.id == id }
    fun findByUri(uri: String): LibEntry? = all().firstOrNull { it.uri == uri }

    fun upsert(e: LibEntry) {
        val list = all()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list.add(e)
        persist()
    }

    fun update(e: LibEntry) = upsert(e)

    fun remove(id: String) {
        all().removeAll { it.id == id }
        persist()
    }
}

data class ResumeMark(
    val docId: String, val name: String, val uri: String, val page: Int,
    val cx: Float, val cy: Float, val zoom: Float, val time: Long, val size: Long
)

data class Bookmark(val docId: String, val page: Int, val label: String, val time: Long)

class MarkStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("bc_marks", Context.MODE_PRIVATE)

    fun resumeMarks(): MutableList<ResumeMark> {
        val out = ArrayList<ResumeMark>()
        try {
            val arr = JSONArray(p.getString("resume", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ResumeMark(
                        o.getString("doc"), o.optString("name"), o.optString("uri"), o.optInt("page"),
                        o.optDouble("cx", 0.5).toFloat(), o.optDouble("cy", 0.0).toFloat(),
                        o.optDouble("zoom", 1.0).toFloat(), o.optLong("time"), o.optLong("size")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveResume(list: List<ResumeMark>) {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            o.put("doc", m.docId); o.put("name", m.name); o.put("uri", m.uri); o.put("page", m.page)
            o.put("cx", m.cx.toDouble()); o.put("cy", m.cy.toDouble()); o.put("zoom", m.zoom.toDouble())
            o.put("time", m.time); o.put("size", m.size)
            arr.put(o)
        }
        p.edit().putString("resume", arr.toString()).apply()
    }

    fun resumeFor(docId: String): ResumeMark? = resumeMarks().firstOrNull { it.docId == docId }

    fun putResume(m: ResumeMark) {
        val list = resumeMarks().filter { it.docId != m.docId }.toMutableList()
        list.add(0, m)
        saveResume(list)
    }

    fun removeResume(docId: String) = saveResume(resumeMarks().filter { it.docId != docId })

    // Auto position (written on exit / page change when "auto save position" is on)
    fun putAuto(docId: String, page: Int, cx: Float, cy: Float, zoom: Float) {
        p.edit().putString("auto_$docId", "$page|$cx|$cy|$zoom").apply()
    }

    fun getAuto(docId: String): FloatArray? {
        val s = p.getString("auto_$docId", null) ?: return null
        val t = s.split("|")
        if (t.size < 4) return null
        return try {
            floatArrayOf(t[0].toFloat(), t[1].toFloat(), t[2].toFloat(), t[3].toFloat())
        } catch (_: Exception) {
            null
        }
    }

    fun bookmarks(): MutableList<Bookmark> {
        val out = ArrayList<Bookmark>()
        try {
            val arr = JSONArray(p.getString("bookmarks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bookmark(o.getString("doc"), o.optInt("page"), o.optString("label"), o.optLong("time")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject()
            o.put("doc", b.docId); o.put("page", b.page); o.put("label", b.label); o.put("time", b.time)
            arr.put(o)
        }
        p.edit().putString("bookmarks", arr.toString()).apply()
    }

    fun addBookmark(b: Bookmark) {
        val list = bookmarks().filter { !(it.docId == b.docId && it.page == b.page) }.toMutableList()
        list.add(0, b)
        saveBookmarks(list)
    }

    fun removeBookmark(docId: String, page: Int) =
        saveBookmarks(bookmarks().filter { !(it.docId == docId && it.page == page) })
}
