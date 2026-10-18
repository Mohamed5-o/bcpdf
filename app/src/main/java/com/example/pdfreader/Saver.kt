package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

object Saver {
    private const val TEXT_SCALE = 4f

    /** Maps a point on the displayed page (fractions, origin top-left) to user space (fractions, origin bottom-left). */
    private fun toUser(rotation: Int, nx: Float, ny: Float): FloatArray = when (rotation) {
        90 -> floatArrayOf(ny, nx)
        180 -> floatArrayOf(1f - nx, ny)
        270 -> floatArrayOf(1f - ny, 1f - nx)
        else -> floatArrayOf(nx, 1f - ny)
    }

    private fun renderTextBitmap(item: Item): Bitmap {
        val bw = Math.max(1, Math.ceil((item.w * TEXT_SCALE).toDouble()).toInt())
        val bh = Math.max(1, Math.ceil((item.h * TEXT_SCALE).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.scale(bw / item.w, bh / item.h)
        item.drawContent(c)
        return bmp
    }

    /**
     * Writes [src] + [items] into [dst]. Items are placed as images (text is rasterised, so Arabic and custom fonts
     * render exactly as on screen); erase strokes are vector fills. The original page content is never removed.
     */
    fun apply(src: File, items: List<Item>, dst: File, onProgress: (Int, Int) -> Unit): Int {
        val doc = PDDocument.load(src)
        try {
            val pageCount = doc.numberOfPages
            val cache = HashMap<Long, PDImageXObject>()
            val bitmaps = ArrayList<Bitmap>()
            var done = 0
            for (pi in 0 until pageCount) {
                val list = items.filter { it.appliesTo(pi) }
                if (list.isEmpty()) continue
                val page = doc.getPage(pi)
                val box = page.cropBox ?: page.mediaBox
                val rotation = ((page.rotation % 360) + 360) % 360
                val bw = box.width
                val bh = box.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (item in list) {
                        if (item is HighlightItem) {
                            val gs = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState()
                            gs.setNonStrokingAlphaConstant(0.4f)
                            cs.saveGraphicsState()
                            cs.setGraphicsStateParameters(gs)
                            cs.setNonStrokingColor(Color.red(item.color), Color.green(item.color), Color.blue(item.color))
                            for (r in item.rects) {
                                val a = toUser(rotation, r.left, r.top)
                                val b = toUser(rotation, r.right, r.bottom)
                                val x1 = box.lowerLeftX + Math.min(a[0], b[0]) * bw
                                val x2 = box.lowerLeftX + Math.max(a[0], b[0]) * bw
                                val y1 = box.lowerLeftY + Math.min(a[1], b[1]) * bh
                                val y2 = box.lowerLeftY + Math.max(a[1], b[1]) * bh
                                cs.addRect(x1, y1, x2 - x1, y2 - y1)
                                cs.fill()
                            }
                            cs.restoreGraphicsState()
                            continue
                        }
                        val u = toUser(rotation, item.nx, item.ny)
                        val cx = box.lowerLeftX + u[0] * bw
                        val cy = box.lowerLeftY + u[1] * bh
                        if (item is EraseItem) {
                            cs.setStrokingColor(Color.red(item.color), Color.green(item.color), Color.blue(item.color))
                            cs.setLineWidth(item.widthPt)
                            cs.setLineCapStyle(1)
                            cs.setLineJoinStyle(1)
                            var first = true
                            for (p in item.pts) {
                                val q = toUser(rotation, p.x, p.y)
                                val x = box.lowerLeftX + q[0] * bw
                                val y = box.lowerLeftY + q[1] * bh
                                if (first) { cs.moveTo(x, y); first = false } else cs.lineTo(x, y)
                            }
                            if (item.pts.size == 1) {
                                val q = toUser(rotation, item.pts[0].x, item.pts[0].y)
                                cs.lineTo(box.lowerLeftX + q[0] * bw, box.lowerLeftY + q[1] * bh)
                            }
                            cs.stroke()
                            continue
                        }
                        val image = cache.getOrPut(item.id) {
                            if (item is ImageItem) {
                                LosslessFactory.createFromImage(doc, item.bitmap)
                            } else {
                                val b = renderTextBitmap(item)
                                bitmaps.add(b)
                                LosslessFactory.createFromImage(doc, b)
                            }
                        }
                        val theta = Math.toRadians((rotation - item.rot).toDouble())
                        val cosT = cos(theta).toFloat()
                        val sinT = sin(theta).toFloat()
                        val w = item.w
                        val h = item.h
                        val m = Matrix(
                            w * cosT, w * sinT, -h * sinT, h * cosT,
                            cx - (w / 2f) * cosT + (h / 2f) * sinT,
                            cy - (w / 2f) * sinT - (h / 2f) * cosT
                        )
                        cs.drawImage(image, m)
                    }
                }
                done++
                onProgress(done, pageCount)
            }
            doc.save(dst)
            for (b in bitmaps) if (!b.isRecycled) b.recycle()
            return pageCount
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }
    }

    /** Re-opens the written file with the system renderer; true only when it is readable and has the expected pages. */
    fun verify(file: File, expectedPages: Int): Boolean {
        if (!file.exists() || file.length() < 100) return false
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            if (r.pageCount != expectedPages) return false
            val p = r.openPage(0)
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            bmp.recycle()
            true
        } catch (_: Throwable) {
            false
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
