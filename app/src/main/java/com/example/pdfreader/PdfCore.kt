package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Wraps android.graphics.pdf.PdfRenderer (rendering only, thread-safe through one lock). */
class PdfDoc(private val ctx: Context, val uri: Uri, val docId: String) {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private val sizes = java.util.concurrent.ConcurrentHashMap<Int, PointF>()
    var pageCount = 0
        private set

    private fun cacheCopy(): File {
        val dir = File(ctx.cacheDir, "src")
        dir.mkdirs()
        return File(dir, "$docId.pdf")
    }

    /** A real file on disk holding the PDF bytes (needed by PDFBox). */
    fun localFile(): File {
        if (uri.scheme == "file") return File(uri.path ?: "")
        val f = cacheCopy()
        if (!f.exists() || f.length() == 0L) copyTo(f)
        return f
    }

    fun refreshLocalCopy() {
        if (uri.scheme == "file") return
        copyTo(cacheCopy())
    }

    private fun copyTo(f: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read file")
        input.use { ins ->
            FileOutputStream(f).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun openFd(): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            ParcelFileDescriptor.open(File(uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("cannot open file")
        }
    }

    fun open() {
        close()
        try {
            val d = openFd()
            pfd = d
            renderer = PdfRenderer(d)
        } catch (e: SecurityException) {
            close()
            throw e
        } catch (e: Exception) {
            // Some providers give non-seekable descriptors: fall back to a cached local copy.
            close()
            val f = cacheCopy()
            copyTo(f)
            val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = d
            renderer = PdfRenderer(d)
        }
        pageCount = renderer?.pageCount ?: 0
        if (pageCount <= 0) {
            close()
            throw IllegalStateException("empty pdf")
        }
    }

    fun close() {
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            renderer = null
            try { pfd?.close() } catch (_: Exception) {}
            pfd = null
        }
    }

    fun pageSize(index: Int): PointF? {
        sizes[index]?.let { return it }
        synchronized(lock) {
            val r = renderer ?: return null
            return try {
                val p = r.openPage(index)
                val s = PointF(p.width.toFloat(), p.height.toFloat())
                p.close()
                sizes[index] = s
                s
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Renders the whole page so that it is [widthPx] wide. */
    fun renderFull(index: Int, widthPx: Int, maxHeightPx: Int): Bitmap? {
        synchronized(lock) {
            val r = renderer ?: return null
            if (index !in 0 until r.pageCount) return null
            return try {
                val page = r.openPage(index)
                try {
                    var scale = widthPx.toFloat() / page.width
                    if (page.height * scale > maxHeightPx) scale = maxHeightPx.toFloat() / page.height
                    val w = max(1, (page.width * scale).toInt())
                    val h = max(1, (page.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Renders a region of the page (in points) at [scale] device pixels per point. */
    fun renderRegion(index: Int, scale: Float, leftPt: Float, topPt: Float, outW: Int, outH: Int): Bitmap? {
        synchronized(lock) {
            val r = renderer ?: return null
            if (index !in 0 until r.pageCount) return null
            return try {
                val page = r.openPage(index)
                try {
                    val bmp = Bitmap.createBitmap(max(1, outW), max(1, outH), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix()
                    m.setScale(scale, scale)
                    m.postTranslate(-leftPt * scale, -topPt * scale)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

// ───────────────────────── Text layer (PDFBox) ─────────────────────────

class PageText(val text: String, val rects: Array<RectF?>) {
    /** Index of the character nearest to (x, y) in page points, or -1 when farther than [tol]. */
    fun indexAt(x: Float, y: Float, tol: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i] ?: continue
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0f) break
            }
        }
        return if (best >= 0 && bestD <= tol * tol) best else -1
    }

    fun slice(a: Int, b: Int): String {
        if (text.isEmpty()) return ""
        val lo = max(0, min(a, b))
        val hi = min(text.length - 1, max(a, b))
        if (lo > hi) return ""
        return text.substring(lo, hi + 1)
    }
}

class SearchHit(
    val page: Int, val start: Int, val len: Int,
    val snippet: String, val snippetStart: Int, val nth: Int
)

private class Collector : PDFTextStripper() {
    val sb = StringBuilder()
    val rects = ArrayList<RectF?>()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val stream = StringBuilder()
        val tpRects = ArrayList<RectF>()
        for (tp in textPositions) {
            val u = tp.getUnicode() ?: continue
            val x = tp.getXDirAdj()
            val y = tp.getYDirAdj()
            val r = RectF(x, y - tp.getHeightDir(), x + tp.getWidthDirAdj(), y)
            for (ch in u) {
                stream.append(ch)
                tpRects.add(r)
            }
        }
        val n = tpRects.size
        if (n == 0) {
            for (ch in text) { sb.append(ch); rects.add(null) }
            return
        }
        val same = text == stream.toString()
        val reversed = !same && text.length == n && text == stream.toString().reversed()
        for (j in text.indices) {
            sb.append(text[j])
            val idx = when {
                same -> j
                reversed -> n - 1 - j
                else -> min(n - 1, (j.toLong() * n / max(1, text.length)).toInt())
            }
            rects.add(tpRects[min(n - 1, max(0, idx))])
        }
    }

    override fun writeWordSeparator() {
        sb.append(' ')
        rects.add(null)
    }

    override fun writeLineSeparator() {
        sb.append('\n')
        rects.add(null)
    }

    override fun writeParagraphSeparator() {
        sb.append('\n')
        rects.add(null)
    }
}

class TextEngine(private val fileProvider: () -> File) {
    private var doc: PDDocument? = null
    private val cache = object : LinkedHashMap<Int, PageText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?): Boolean = size > 10
    }

    @Synchronized
    private fun ensureDoc(): PDDocument {
        doc?.let { return it }
        val d = PDDocument.load(fileProvider())
        doc = d
        return d
    }

    @Synchronized
    fun page(index: Int): PageText {
        cache[index]?.let { return it }
        val d = ensureDoc()
        val c = Collector()
        c.setStartPage(index + 1)
        c.setEndPage(index + 1)
        c.getText(d)
        val pt = PageText(c.sb.toString(), c.rects.toTypedArray())
        cache[index] = pt
        return pt
    }

    @Synchronized
    fun close() {
        try { doc?.close() } catch (_: Exception) {}
        doc = null
        cache.clear()
    }

    /** Number of pages that contained any text during the last search (0 means scanned / image-only file). */
    @Volatile
    var textPagesSeen = 0
    @Volatile
    var failedPages = 0
    @Volatile
    var lastError = ""

    private fun isWordChar(c: Char) = Character.isLetterOrDigit(c)

    /** Normalisation: Arabic marks / presentation forms (NFKC) / alef variants / whitespace, optional case folding; keeps a map to original indexes. */
    private fun normalize(s: String, ignoreCase: Boolean, map: ArrayList<Int>?): String {
        val out = StringBuilder()
        for (i in s.indices) {
            var ch = s[i]
            val code = ch.code
            if ((code in 0x064B..0x065F) || code == 0x0670 || code == 0x0640 || code == 0x200C || code == 0x200D || code == 0x200E || code == 0x200F || code == 0x00AD) continue
            val piece: String = if (code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF) {
                try { java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFKC) } catch (_: Throwable) { ch.toString() }
            } else ch.toString()
            for (pc0 in piece) {
                var pc = pc0
                val k = pc.code
                if ((k in 0x064B..0x065F) || k == 0x0670 || k == 0x0640) continue
                if (k == 0x0622 || k == 0x0623 || k == 0x0625 || k == 0x0671) pc = 'ا'
                else if (k == 0x0649 || k == 0x06CC) pc = 'ي'
                else if (k == 0x06A9) pc = 'ك'
                else if (Character.isWhitespace(pc) || k == 0x00A0) pc = ' '
                if (ignoreCase) pc = Character.toLowerCase(pc)
                out.append(pc)
                map?.add(i)
            }
        }
        return out.toString()
    }

    /**
     * Runs on the caller's (background) thread. Returns the number of hits found.
     * [onBatch] receives hits per page; [progress] gets (pageDone, pageTotal).
     */
    fun search(
        query: String, whole: Boolean, ignoreCase: Boolean, pageCount: Int,
        cancelled: () -> Boolean, progress: (Int, Int) -> Unit, onBatch: (List<SearchHit>) -> Unit
    ): Int {
        val q = normalize(query.trim(), ignoreCase, null).replace(Regex(" +"), " ")
        if (q.isEmpty()) return 0
        val rtlQ = q.any { it.code in 0x0590..0x08FF }
        val qs = if (rtlQ && q.reversed() != q) listOf(q, q.reversed()) else listOf(q)
        var total = 0
        textPagesSeen = 0
        failedPages = 0
        lastError = ""
        ensureDoc()
        for (p in 0 until pageCount) {
            if (cancelled()) break
            val pt = try { page(p) } catch (e: Throwable) { failedPages++; lastError = e.javaClass.simpleName + ": " + (e.message ?: ""); null }
            progress(p + 1, pageCount)
            if (pt == null || pt.text.isBlank()) continue
            textPagesSeen++
            val map = ArrayList<Int>()
            val norm = normalize(pt.text, ignoreCase, map)
            val batch = ArrayList<SearchHit>()
            var from = 0
            var nth = 0
            var qi = 0
            var qq = qs[0]
            while (true) {
                val at = norm.indexOf(qq, from)
                if (at < 0) {
                    if (batch.isEmpty() && qi + 1 < qs.size) { qi++; qq = qs[qi]; from = 0; continue }
                    break
                }
                from = at + max(1, qq.length)
                if (whole) {
                    val before = if (at > 0) norm[at - 1] else ' '
                    val after = if (at + qq.length < norm.length) norm[at + qq.length] else ' '
                    if (isWordChar(before) || isWordChar(after)) continue
                }
                val start = map[at]
                val end = map[at + qq.length - 1] + 1
                nth++
                val s0 = max(0, start - 22)
                val e0 = min(pt.text.length, end + 34)
                val snip = pt.text.substring(s0, e0).replace('\n', ' ')
                batch.add(SearchHit(p, start, end - start, (if (s0 > 0) "…" else "") + snip + (if (e0 < pt.text.length) "…" else ""), start - s0 + (if (s0 > 0) 1 else 0), nth))
                total++
                if (total >= 800) break
            }
            if (batch.isNotEmpty()) onBatch(batch)
            if (total >= 800) break
        }
        return total
    }
}
