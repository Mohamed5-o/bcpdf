package com.example.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A text highlight (optionally with a note). Rectangles are fractions of the displayed page (0..1, origin top-left). */
class HighlightItem(id: Long) : Item(id) {
    var color = 0xFFFFE04A.toInt()
    var note = ""
    var snippet = ""
    var created = System.currentTimeMillis()
    val rects = ArrayList<RectF>()

    override fun selectable(): Boolean = false
    override fun drawContent(c: Canvas) {}

    override fun copyItem(): Item {
        val o = HighlightItem(id)
        copyBase(o)
        o.color = color; o.note = note; o.snippet = snippet; o.created = created
        for (r in rects) o.rects.add(RectF(r))
        return o
    }

    fun contains(fx: Float, fy: Float, pad: Float): Boolean {
        for (r in rects) if (fx >= r.left - pad && fx <= r.right + pad && fy >= r.top - pad && fy <= r.bottom + pad) return true
        return false
    }

    fun drawOn(c: Canvas, W: Float, H: Float, p: Paint) {
        p.style = Paint.Style.FILL
        p.color = (color and 0x00FFFFFF) or (0x66 shl 24)
        for (r in rects) c.drawRect(r.left * W, r.top * H, r.right * W, r.bottom * H, p)
        if (note.isNotEmpty() && rects.isNotEmpty()) {
            val r = rects[0]
            val s = Math.max(9f, H * 0.011f)
            val x = Math.min(W - s * 1.2f, r.right * W + s * 0.2f)
            val y = r.top * H
            p.color = 0xFFE8BE4B.toInt()
            c.drawRoundRect(x, y, x + s, y + s, s * 0.25f, s * 0.25f, p)
            p.color = 0xFF3A2A00.toInt()
            p.strokeWidth = Math.max(1f, s * 0.1f)
            p.style = Paint.Style.STROKE
            c.drawLine(x + s * 0.2f, y + s * 0.35f, x + s * 0.8f, y + s * 0.35f, p)
            c.drawLine(x + s * 0.2f, y + s * 0.65f, x + s * 0.6f, y + s * 0.65f, p)
            p.style = Paint.Style.FILL
        }
    }
}

/** Highlights are kept per document in a small JSON file, independent of the PDF, until they are baked in on save. */
class AnnotStore(private val ctx: Context) {
    private fun file(docId: String) = File(File(ctx.filesDir, "annots").also { it.mkdirs() }, "$docId.json")

    @Synchronized
    fun load(docId: String): ArrayList<HighlightItem> {
        val out = ArrayList<HighlightItem>()
        val f = file(docId)
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val h = HighlightItem(o.getLong("id"))
                h.page = o.optInt("page")
                h.color = o.optInt("color", 0xFFFFE04A.toInt())
                h.note = o.optString("note")
                h.snippet = o.optString("snip")
                h.created = o.optLong("created")
                val rs = o.optJSONArray("rects") ?: JSONArray()
                for (j in 0 until rs.length()) {
                    val r = rs.getJSONArray(j)
                    h.rects.add(RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat()))
                }
                out.add(h)
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    fun save(docId: String, list: List<HighlightItem>) {
        if (list.isEmpty()) { file(docId).delete(); return }
        val arr = JSONArray()
        for (h in list) {
            val o = JSONObject()
            o.put("id", h.id); o.put("page", h.page); o.put("color", h.color); o.put("note", h.note)
            o.put("snip", h.snippet); o.put("created", h.created)
            val rs = JSONArray()
            for (r in h.rects) rs.put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble()))
            o.put("rects", rs)
            arr.put(o)
        }
        file(docId).writeText(arr.toString())
    }

    fun clear(docId: String) { file(docId).delete() }
}

/** Per-file reading profile (view settings that follow one document). */
class ProfileStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    class Profile(val readMode: String, val fixedZoom: Int, val comfort: Int, val comfortLevel: Int, val dim: Int, val night: Boolean)

    fun get(docId: String): Profile? {
        val s = p.getString(docId, null) ?: return null
        val a = s.split("|")
        if (a.size < 6) return null
        return try { Profile(a[0], a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5] == "1") } catch (_: Exception) { null }
    }

    fun put(docId: String, pr: Profile) {
        p.edit().putString(docId, "${pr.readMode}|${pr.fixedZoom}|${pr.comfort}|${pr.comfortLevel}|${pr.dim}|${if (pr.night) 1 else 0}").apply()
    }

    fun remove(docId: String) { p.edit().remove(docId).apply() }
}
