package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max

val FONT_FAMILIES = arrayOf("sans-serif", "serif", "monospace", "sans-serif-condensed", "cursive")

/**
 * Anything the user adds on top of a page. Position is the item centre as a fraction of the displayed page;
 * size is in PDF points; rotation is in degrees, clockwise.
 */
abstract class Item(var id: Long) {
    var page = 0
    var nx = 0.5f
    var ny = 0.5f
    var rot = 0f
    var w = 100f
    var h = 40f

    abstract fun copyItem(): Item
    abstract fun drawContent(c: Canvas)
    open fun appliesTo(p: Int): Boolean = p == page
    open fun selectable(): Boolean = true

    protected fun copyBase(o: Item) {
        o.page = page; o.nx = nx; o.ny = ny; o.rot = rot; o.w = w; o.h = h
    }
}

class TextItem(id: Long) : Item(id) {
    var text = ""
    var size = 24f
    var color = 0xFF000000.toInt()
    var font = 0
    var bold = false
    var italic = false
    var underline = false
    var alphaPct = 100
    var watermark = false
    var wmFrom = 0
    var wmTo = 0
    private var layout: StaticLayout? = null

    override fun appliesTo(p: Int): Boolean = if (watermark) (p in wmFrom..wmTo) else p == page

    fun remeasure() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = size
        var style = Typeface.NORMAL
        if (bold && italic) style = Typeface.BOLD_ITALIC else if (bold) style = Typeface.BOLD else if (italic) style = Typeface.ITALIC
        paint.typeface = Typeface.create(FONT_FAMILIES[font.coerceIn(0, FONT_FAMILIES.size - 1)], style)
        paint.isUnderlineText = underline
        val a = (alphaPct.coerceIn(5, 100) * 255 / 100)
        paint.color = (color and 0x00FFFFFF) or (a shl 24)
        val src = if (text.isEmpty()) " " else text
        var maxW = 1f
        for (line in src.split("\n")) {
            val lw = Layout.getDesiredWidth(line, paint)
            if (lw > maxW) maxW = lw
        }
        val width = ceil(maxW.toDouble()).toInt() + 2
        val l = StaticLayout.Builder.obtain(src, 0, src.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        layout = l
        w = l.width.toFloat()
        h = max(1, l.height).toFloat()
    }

    override fun drawContent(c: Canvas) {
        if (layout == null) remeasure()
        layout?.draw(c)
    }

    override fun copyItem(): Item {
        val o = TextItem(id)
        copyBase(o)
        o.text = text; o.size = size; o.color = color; o.font = font; o.bold = bold
        o.italic = italic; o.underline = underline; o.alphaPct = alphaPct
        o.watermark = watermark; o.wmFrom = wmFrom; o.wmTo = wmTo
        o.remeasure()
        o.w = w; o.h = h
        return o
    }
}

class ImageItem(id: Long, val bitmap: Bitmap, val isSignature: Boolean) : Item(id) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setWidth(widthPt: Float) {
        w = widthPt
        h = widthPt * bitmap.height / max(1, bitmap.width)
    }

    override fun drawContent(c: Canvas) {
        c.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), paint)
    }

    override fun copyItem(): Item {
        val o = ImageItem(id, bitmap, isSignature)
        copyBase(o)
        return o
    }
}

/** A cover-up stroke made with the eraser: drawn with a colour sampled from the page background. */
class EraseItem(id: Long) : Item(id) {
    val pts = ArrayList<PointF>()
    var widthPt = 14f
    var color = 0xFFFFFFFF.toInt()

    override fun selectable(): Boolean = false
    override fun drawContent(c: Canvas) {}

    fun drawOn(c: Canvas, pw: Float, ph: Float) {
        if (pts.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = widthPt
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        if (pts.size == 1) {
            p.style = Paint.Style.FILL
            c.drawCircle(pts[0].x * pw, pts[0].y * ph, widthPt / 2f, p)
            return
        }
        val path = android.graphics.Path()
        path.moveTo(pts[0].x * pw, pts[0].y * ph)
        for (i in 1 until pts.size) path.lineTo(pts[i].x * pw, pts[i].y * ph)
        c.drawPath(path, p)
    }

    override fun copyItem(): Item {
        val o = EraseItem(id)
        copyBase(o)
        for (p in pts) o.pts.add(PointF(p.x, p.y))
        o.widthPt = widthPt
        o.color = color
        return o
    }
}

/** Items + linear undo/redo (snapshot based). */
class EditState {
    val items = ArrayList<Item>()
    private val undoStack = ArrayList<List<Item>>()
    private val redoStack = ArrayList<List<Item>>()
    private var nextId = System.currentTimeMillis()
    var dirty = false
    var onChange: (() -> Unit)? = null

    fun newId(): Long = nextId++

    private fun snap(): List<Item> = items.map { it.copyItem() }

    /** Call before mutating an item in place. */
    fun checkpoint() {
        undoStack.add(snap())
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        dirty = true
    }

    fun changed() {
        dirty = true
        onChange?.invoke()
    }

    fun add(item: Item) {
        checkpoint()
        items.add(item)
        changed()
    }

    fun remove(item: Item) {
        checkpoint()
        items.removeAll { it.id == item.id }
        changed()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(snap())
        val s = undoStack.removeAt(undoStack.size - 1)
        items.clear(); items.addAll(s)
        changed()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(snap())
        val s = redoStack.removeAt(redoStack.size - 1)
        items.clear(); items.addAll(s)
        changed()
        return true
    }

    fun byId(id: Long): Item? = items.firstOrNull { it.id == id }
    fun forPage(p: Int): List<Item> = items.filter { it.appliesTo(p) }
    fun isEmpty() = items.isEmpty()

    fun clearAll() {
        items.clear(); undoStack.clear(); redoStack.clear()
        dirty = false
    }
}

/** Crash-recovery drafts: unsaved edits are mirrored to the cache directory. */
class DraftStore(private val ctx: Context) {
    private fun dir(docId: String): File = File(File(ctx.cacheDir, "drafts"), docId).also { it.mkdirs() }

    fun exists(docId: String): Boolean = File(dir(docId), "items.json").exists()

    @Synchronized
    fun save(docId: String, items: List<Item>) {
        val d = dir(docId)
        if (items.isEmpty()) {
            clear(docId)
            return
        }
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("id", it.id); o.put("page", it.page); o.put("nx", it.nx.toDouble()); o.put("ny", it.ny.toDouble())
            o.put("rot", it.rot.toDouble()); o.put("w", it.w.toDouble()); o.put("h", it.h.toDouble())
            when (it) {
                is TextItem -> {
                    o.put("t", "t"); o.put("text", it.text); o.put("size", it.size.toDouble()); o.put("color", it.color)
                    o.put("font", it.font); o.put("bold", it.bold); o.put("italic", it.italic)
                    o.put("underline", it.underline); o.put("alpha", it.alphaPct)
                    o.put("wm", it.watermark); o.put("wmFrom", it.wmFrom); o.put("wmTo", it.wmTo)
                }
                is ImageItem -> {
                    o.put("t", "i"); o.put("sig", it.isSignature)
                    val f = File(d, "img_${it.id}.png")
                    if (!f.exists()) FileOutputStream(f).use { out -> it.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                }
                is EraseItem -> {
                    o.put("t", "e"); o.put("width", it.widthPt.toDouble()); o.put("color", it.color)
                    val sb = StringBuilder()
                    for (p in it.pts) sb.append(p.x).append(',').append(p.y).append(';')
                    o.put("pts", sb.toString())
                }
            }
            arr.put(o)
        }
        File(d, "items.json").writeText(arr.toString())
    }

    @Synchronized
    fun load(docId: String, state: EditState): Boolean {
        val d = dir(docId)
        val f = File(d, "items.json")
        if (!f.exists()) return false
        try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val item: Item = when (o.getString("t")) {
                    "t" -> {
                        val t = TextItem(id)
                        t.text = o.optString("text"); t.size = o.optDouble("size", 24.0).toFloat()
                        t.color = o.optInt("color", 0xFF000000.toInt()); t.font = o.optInt("font")
                        t.bold = o.optBoolean("bold"); t.italic = o.optBoolean("italic")
                        t.underline = o.optBoolean("underline"); t.alphaPct = o.optInt("alpha", 100)
                        t.watermark = o.optBoolean("wm"); t.wmFrom = o.optInt("wmFrom"); t.wmTo = o.optInt("wmTo")
                        t.remeasure()
                        t
                    }
                    "i" -> {
                        val bmp = BitmapFactory.decodeFile(File(d, "img_$id.png").absolutePath) ?: continue
                        ImageItem(id, bmp, o.optBoolean("sig"))
                    }
                    else -> {
                        val e = EraseItem(id)
                        e.widthPt = o.optDouble("width", 14.0).toFloat()
                        e.color = o.optInt("color", 0xFFFFFFFF.toInt())
                        for (pair in o.optString("pts").split(";")) {
                            val xy = pair.split(",")
                            if (xy.size == 2) e.pts.add(PointF(xy[0].toFloat(), xy[1].toFloat()))
                        }
                        e
                    }
                }
                item.page = o.optInt("page"); item.nx = o.optDouble("nx", 0.5).toFloat()
                item.ny = o.optDouble("ny", 0.5).toFloat(); item.rot = o.optDouble("rot").toFloat()
                if (item !is TextItem) {
                    item.w = o.optDouble("w", 100.0).toFloat(); item.h = o.optDouble("h", 40.0).toFloat()
                }
                out.add(item)
            }
            state.items.clear()
            state.items.addAll(out)
            state.dirty = out.isNotEmpty()
            return out.isNotEmpty()
        } catch (_: Exception) {
            return false
        }
    }

    @Synchronized
    fun clear(docId: String) {
        dir(docId).deleteRecursively()
    }

    fun clearAll() {
        File(ctx.cacheDir, "drafts").deleteRecursively()
    }
}
